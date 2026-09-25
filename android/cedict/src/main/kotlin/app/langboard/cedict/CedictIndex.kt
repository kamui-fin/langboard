package app.langboard.cedict

/** Storage behind [Cedict]: an in-memory map, or e.g. a SQLite table on Android. */
interface CedictIndex {
  /** Pinyin → entry ids for an exact headword, in source order; null if the headword is unknown. */
  fun lookup(script: Script, hanzi: String): Map<String, IndexEntry>?

  fun entry(id: Int): CedictEntry
}

/** One headword → entry mapping produced while indexing. */
data class IndexRow(val script: Script, val hanzi: String, val pinyin: String, val entryId: Int, val isVariant: Boolean)

/**
 * Assigns entry ids and emits index rows with the same rules as cc-cedict's `pushVariantTo`:
 * every entry is indexed under its own traditional and simplified forms, and an entry that is a
 * "variant of X[pinyin]" is also indexed, as a variant, under X — but only when the reference has pinyin.
 */
class CedictIndexBuilder {
  private val seen = HashSet<String>()
  var count = 0
    private set
  var duplicates = 0
    private set

  class Added(val id: Int, val rows: List<IndexRow>)

  /** Returns null for a duplicate traditional+simplified+pinyin line (upstream aborts; we keep the first). */
  fun add(entry: CedictEntry): Added? {
    if (!seen.add("${entry.traditional}_${entry.simplified}_${entry.pinyin}")) {
      duplicates++
      return null
    }
    val id = count++
    val rows = ArrayList<IndexRow>(2 + entry.variantOf.size * 2)
    rows += IndexRow(Script.Traditional, entry.traditional, entry.pinyin, id, isVariant = false)
    rows += IndexRow(Script.Simplified, entry.simplified, entry.pinyin, id, isVariant = false)
    for (ref in entry.variantOf) {
      val pinyin = ref.pinyin ?: continue
      rows += IndexRow(Script.Traditional, ref.traditional, pinyin, id, isVariant = true)
      rows += IndexRow(Script.Simplified, ref.simplified, pinyin, id, isVariant = true)
    }
    return Added(id, rows)
  }
}

/** Whole dictionary in memory, like the npm package. Fine on the JVM; use a disk index on phones. */
class InMemoryCedictIndex private constructor(
  private val entries: List<CedictEntry>,
  private val traditional: Map<String, Map<String, IndexEntry>>,
  private val simplified: Map<String, Map<String, IndexEntry>>,
) : CedictIndex {

  val size get() = entries.size

  override fun lookup(script: Script, hanzi: String) =
    (if (script == Script.Traditional) traditional else simplified)[hanzi]

  override fun entry(id: Int) = entries[id]

  companion object {
    fun build(entries: Sequence<CedictEntry>): InMemoryCedictIndex {
      val builder = CedictIndexBuilder()
      val all = ArrayList<CedictEntry>()
      val maps = mapOf(
        Script.Traditional to HashMap<String, LinkedHashMap<String, Pair<MutableList<Int>, MutableList<Int>>>>(),
        Script.Simplified to HashMap(),
      )
      for (entry in entries) {
        val added = builder.add(entry) ?: continue
        all += entry
        for (row in added.rows) {
          val lists = maps.getValue(row.script)
            .getOrPut(row.hanzi) { LinkedHashMap() }
            .getOrPut(row.pinyin) { mutableListOf<Int>() to mutableListOf() }
          (if (row.isVariant) lists.second else lists.first) += row.entryId
        }
      }
      fun freeze(m: Map<String, LinkedHashMap<String, Pair<MutableList<Int>, MutableList<Int>>>>) =
        m.mapValues { (_, byPinyin) ->
          byPinyin.mapValues { (_, l) -> IndexEntry(l.first.toIntArray(), l.second.toIntArray()) }
        }
      return InMemoryCedictIndex(all, freeze(maps.getValue(Script.Traditional)), freeze(maps.getValue(Script.Simplified)))
    }

    fun fromLines(lines: Sequence<String>) = build(lines.mapNotNull(CedictParser::parseLine))
  }
}
