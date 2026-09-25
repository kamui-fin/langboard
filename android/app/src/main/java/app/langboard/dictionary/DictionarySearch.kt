package app.langboard.dictionary

import android.database.sqlite.SQLiteDatabase
import app.langboard.cedict.Cedict
import app.langboard.cedict.DictionaryEntry
import app.langboard.cedict.Script

data class DictionaryHit(val entry: DictionaryEntry, val kind: Kind) {
  enum class Kind { Exact, Prefix, English }
}

/** Chinese exact + prefix search and English full-text search. Deterministic; no model involved. */
internal class DictionarySearch(private val db: SQLiteDatabase) {
  private val index = SqliteCedictIndex(db)
  val cedict = Cedict(index)

  fun search(query: String, limit: Int, wholePhrase: Boolean): List<DictionaryHit> {
    val q = query.trim()
    if (q.isEmpty() || limit <= 0) return emptyList()
    return if (QueryRules.isChinese(q)) chinese(q, limit) else english(q, limit, wholePhrase, prefixLast = !wholePhrase && !query.last().isWhitespace())
  }

  private fun chinese(q: String, limit: Int): List<DictionaryHit> {
    val exact = LinkedHashMap<String, DictionaryEntry>()
    for (script in listOf(Script.Simplified, Script.Traditional)) {
      cedict.getByWord(script, q)?.values?.flatten()?.forEach { e -> exact.putIfAbsent(key(e), e) }
    }
    val hits = exact.values.sortedBy { it.isVariant }.map { DictionaryHit(it, DictionaryHit.Kind.Exact) }.toMutableList()
    if (hits.size >= limit) return hits.take(limit)

    val ids = ArrayList<Int>()
    db.rawQuery(
      """SELECT entry_id, MIN(length(hanzi)) AS len, MIN(hanzi) AS hz FROM headwords
         WHERE hanzi > ? AND hanzi < ? AND is_variant = 0
         GROUP BY entry_id ORDER BY len, hz, entry_id LIMIT ?""",
      arrayOf(q, QueryRules.prefixUpperBound(q), (limit + exact.size).toString()),
    ).use { c -> while (c.moveToNext()) ids += c.getInt(0) }
    val entries = index.entries(ids)
    val seen = exact.keys.toMutableSet()
    for (id in ids) {
      if (hits.size >= limit) break
      val e = Cedict.expand(entries[id] ?: continue, isVariant = false)
      if (seen.add(key(e))) hits += DictionaryHit(e, DictionaryHit.Kind.Prefix)
    }
    return hits
  }

  /**
   * FTS over senses. In the app the last word is a prefix while typing ([prefixLast]). With [wholePhrase]
   * (keyboard, whole detected phrase), falls back to OR when no sense contains every word.
   */
  private fun english(q: String, limit: Int, wholePhrase: Boolean, prefixLast: Boolean): List<DictionaryHit> {
    val words = QueryRules.words(q)
    val content = QueryRules.contentWords(words)
    if (content.isEmpty()) return emptyList()
    var rows = match(QueryRules.ftsAnd(content, prefixLast))
    if (rows.isEmpty() && wholePhrase && content.size > 1) rows = match(QueryRules.ftsOr(content))
    return QueryRules.rank(rows, words.joinToString(" "), content)
      .take(limit)
      .map { DictionaryHit(it, DictionaryHit.Kind.English) }
  }

  private fun match(ftsQuery: String): List<DictionaryEntry> {
    val out = ArrayList<DictionaryEntry>()
    db.rawQuery(
      """SELECT e.id, e.traditional, e.simplified, e.pinyin, e.english, e.classifiers, e.variant_of
         FROM english_fts JOIN entries e ON e.id = english_fts.docid
         WHERE english_fts MATCH ? LIMIT $CANDIDATES""",
      arrayOf(ftsQuery),
    ).use { c -> while (c.moveToNext()) out += Cedict.expand(SqliteCedictIndex.readEntry(c), isVariant = false) }
    return out
  }

  fun isHeadword(hanzi: String): Boolean =
    db.rawQuery("SELECT 1 FROM headwords WHERE hanzi = ? LIMIT 1", arrayOf(hanzi)).use { it.moveToFirst() }

  /**
   * The everyday reading of [hanzi]: a direct entry over a variant, a common word (lowercase pinyin)
   * over a name, then the entry with the most senses.
   */
  fun best(hanzi: String): DictionaryEntry? {
    val all = listOf(Script.Simplified, Script.Traditional).firstNotNullOfOrNull { cedict.getByWord(it, hanzi) }
      ?.values?.flatten() ?: return null
    return all.sortedWith(
      compareBy<DictionaryEntry>({ it.isVariant }, { it.pinyin.firstOrNull()?.isUpperCase() == true }, { -it.english.size })
    ).firstOrNull()
  }

  private fun key(e: DictionaryEntry) = "${e.traditional}_${e.simplified}_${e.pinyin}"

  private companion object {
    const val CANDIDATES = 400
  }
}
