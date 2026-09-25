package app.langboard.dictionary

import android.database.sqlite.SQLiteDatabase
import app.langboard.cedict.CedictEntry
import app.langboard.cedict.CedictHeader
import app.langboard.cedict.CedictIndex
import app.langboard.cedict.CedictIndexBuilder
import app.langboard.cedict.CedictParser
import app.langboard.cedict.HanziRef
import app.langboard.cedict.IndexEntry
import app.langboard.cedict.Script
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * On-disk CC-CEDICT: `entries` (one row per line, id = source order), `headwords` (the cc-cedict
 * index, including variant links) and an FTS4 index over English senses.
 */
internal object DictionarySchema {
  const val VERSION = "1"

  val CREATE = listOf(
    "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    """CREATE TABLE entries(
      id INTEGER PRIMARY KEY, traditional TEXT NOT NULL, simplified TEXT NOT NULL, pinyin TEXT NOT NULL,
      english TEXT NOT NULL, classifiers TEXT NOT NULL, variant_of TEXT NOT NULL)""",
    "CREATE TABLE headwords(script INTEGER NOT NULL, hanzi TEXT NOT NULL, pinyin TEXT NOT NULL, entry_id INTEGER NOT NULL, is_variant INTEGER NOT NULL)",
    "CREATE VIRTUAL TABLE english_fts USING fts4(content=\"entries\", english, tokenize=porter)",
  )

  // Senses never contain newlines; hanzi never contain tabs.
  fun encodeSenses(english: List<String>) = english.joinToString("\n")
  fun decodeSenses(s: String) = if (s.isEmpty()) emptyList() else s.split('\n')
  fun encodeRefs(refs: List<HanziRef>) = refs.joinToString("\n") { "${it.traditional}\t${it.simplified}\t${it.pinyin.orEmpty()}" }
  fun decodeRefs(s: String) = if (s.isEmpty()) emptyList() else s.split('\n').map {
    val p = it.split('\t')
    HanziRef(p[0], p[1], p.getOrNull(2)?.takeIf(String::isNotEmpty))
  }
}

internal object DictionaryBuilder {
  class Result(val header: CedictHeader, val entries: Int)

  /**
   * Streams CC-CEDICT text into a fresh database at [out]. Runs as one transaction with journaling
   * off: the file is scratch until the caller verifies it and renames it into place.
   */
  fun build(
    reader: BufferedReader,
    out: File,
    isActive: () -> Boolean = { true },
    minEntries: Int = MIN_ENTRIES,
    onProgress: (done: Int, total: Int?) -> Unit = { _, _ -> },
  ): Result {
    out.delete()
    val db = SQLiteDatabase.openDatabase(
      out.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )
    try {
      db.rawQuery("PRAGMA journal_mode=OFF", null).close()
      db.execSQL("PRAGMA synchronous=OFF")
      DictionarySchema.CREATE.forEach(db::execSQL)

      val header = LinkedHashMap<String, String>()
      val indexer = CedictIndexBuilder()
      db.beginTransaction()
      try {
        val insertEntry = db.compileStatement("INSERT INTO entries VALUES (?,?,?,?,?,?,?)")
        val insertHeadword = db.compileStatement("INSERT INTO headwords VALUES (?,?,?,?,?)")
        for (line in reader.lineSequence()) {
          if (line.startsWith("#")) {
            CedictParser.parseHeaderLine(line)?.let { (k, v) -> header[k] = v }
            continue
          }
          val entry = CedictParser.parseLine(line) ?: continue
          val added = indexer.add(entry) ?: continue
          insertEntry.apply {
            bindLong(1, added.id.toLong())
            bindString(2, entry.traditional)
            bindString(3, entry.simplified)
            bindString(4, entry.pinyin)
            bindString(5, DictionarySchema.encodeSenses(entry.english))
            bindString(6, DictionarySchema.encodeRefs(entry.classifiers))
            bindString(7, DictionarySchema.encodeRefs(entry.variantOf))
            executeInsert()
          }
          for (row in added.rows) {
            insertHeadword.apply {
              bindLong(1, row.script.ordinal.toLong())
              bindString(2, row.hanzi)
              bindString(3, row.pinyin)
              bindLong(4, row.entryId.toLong())
              bindLong(5, if (row.isVariant) 1 else 0)
              executeInsert()
            }
          }
          if (added.id % 2_000 == 0) {
            if (!isActive()) throw CancellationException()
            onProgress(added.id, CedictHeader(header).entries)
          }
        }
        db.setTransactionSuccessful()
      } finally {
        db.endTransaction()
      }

      val parsed = CedictHeader(header)
      if (indexer.count < minEntries) throw IOException("only ${indexer.count} entries")
      db.execSQL("CREATE INDEX headwords_hanzi ON headwords(hanzi, script)")
      db.execSQL("INSERT INTO english_fts(english_fts) VALUES('rebuild')")
      val meta = mapOf(
        "schema" to DictionarySchema.VERSION,
        "entries" to indexer.count.toString(),
        "source_date" to (parsed.date ?: ""),
        "built_at" to System.currentTimeMillis().toString(),
      )
      for ((k, v) in meta) db.execSQL("INSERT INTO meta VALUES (?, ?)", arrayOf(k, v))
      return Result(parsed, indexer.count)
    } finally {
      db.close()
    }
  }

  /** A real CC-CEDICT has ~125k entries; far fewer means a truncated or wrong file. */
  const val MIN_ENTRIES = 50_000
}

/** The cc-cedict index contract over the `headwords`/`entries` tables. */
internal class SqliteCedictIndex(private val db: SQLiteDatabase) : CedictIndex {

  override fun lookup(script: Script, hanzi: String): Map<String, IndexEntry>? {
    val lists = LinkedHashMap<String, Pair<MutableList<Int>, MutableList<Int>>>()
    db.rawQuery(
      "SELECT pinyin, entry_id, is_variant FROM headwords WHERE hanzi = ? AND script = ? ORDER BY rowid",
      arrayOf(hanzi, script.ordinal.toString()),
    ).use { c ->
      while (c.moveToNext()) {
        val l = lists.getOrPut(c.getString(0)) { mutableListOf<Int>() to mutableListOf() }
        (if (c.getInt(2) == 1) l.second else l.first) += c.getInt(1)
      }
    }
    if (lists.isEmpty()) return null
    return lists.mapValues { (_, l) -> IndexEntry(l.first.toIntArray(), l.second.toIntArray()) }
  }

  override fun entry(id: Int): CedictEntry = entries(listOf(id)).getValue(id)

  fun entries(ids: Collection<Int>): Map<Int, CedictEntry> {
    if (ids.isEmpty()) return emptyMap()
    val out = HashMap<Int, CedictEntry>(ids.size)
    db.rawQuery(
      "SELECT id, traditional, simplified, pinyin, english, classifiers, variant_of FROM entries WHERE id IN (${ids.joinToString(",")})",
      null,
    ).use { c ->
      while (c.moveToNext()) out[c.getInt(0)] = readEntry(c)
    }
    return out
  }

  companion object {
    fun readEntry(c: android.database.Cursor, offset: Int = 1) = CedictEntry(
      traditional = c.getString(offset),
      simplified = c.getString(offset + 1),
      pinyin = c.getString(offset + 2),
      english = DictionarySchema.decodeSenses(c.getString(offset + 3)),
      classifiers = DictionarySchema.decodeRefs(c.getString(offset + 4)),
      variantOf = DictionarySchema.decodeRefs(c.getString(offset + 5)),
    )
  }
}
