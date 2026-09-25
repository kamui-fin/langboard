package app.langboard.cedict

/**
 * Exact headword lookup with the semantics of cc-cedict's `Cedict` class.
 *
 * Results are keyed by pinyin (sorted by code unit, as upstream), entries within a key are
 * de-duplicated by traditional form + pinyin with senses merged, then sorted by pinyin.
 * Upstream's `asObject: false` form is `results.values.flatten()`.
 */
class Cedict(private val index: CedictIndex) {

  fun getBySimplified(word: String, pinyin: String? = null, config: SearchConfig = SearchConfig()) =
    getByWord(Script.Simplified, word, pinyin, config)

  fun getByTraditional(word: String, pinyin: String? = null, config: SearchConfig = SearchConfig()) =
    getByWord(Script.Traditional, word, pinyin, config)

  /** Returns pinyin → entries, or null when nothing matches. */
  fun getByWord(
    script: Script,
    word: String,
    pinyin: String? = null,
    config: SearchConfig = SearchConfig(),
  ): Map<String, List<DictionaryEntry>>? {
    if (word.isEmpty()) return null
    val source = index.lookup(script, word) ?: return null

    val keys = when {
      pinyin.isNullOrEmpty() -> source.keys.toList()
      config.caseSensitiveSearch -> if (pinyin in source) listOf(pinyin) else emptyList()
      else -> pinyin.lowercase().let { p -> source.keys.filter { it.lowercase() == p } }
    }.sorted()

    // processResults
    val resultsMap = LinkedHashMap<String, MutableList<Int>>()
    val variantIds = HashSet<Int>()
    for (key in keys) {
      val entry = source.getValue(key)
      entry.variants.forEach { variantIds += it }
      val bucket = resultsMap.getOrPut(if (config.mergeCases) key.lowercase() else key) { mutableListOf() }
      bucket += entry.base.asList()
      if (config.allowVariants) bucket += entry.variants.asList()
    }

    // formatResults
    val results = LinkedHashMap<String, List<DictionaryEntry>>()
    for ((pinyinKey, ids) in resultsMap) {
      val items = LinkedHashMap<String, DictionaryEntry>()
      for (id in ids) {
        val isVariant = id in variantIds
        if (!config.allowVariants && isVariant) continue
        var item = expand(index.entry(id), isVariant)
        if (config.mergeCases) item = item.copy(pinyin = item.pinyin.lowercase())
        val dedupKey = "${item.traditional}_${item.pinyin}"
        val existing = items[dedupKey]
        items[dedupKey] = existing?.copy(english = existing.english + item.english) ?: item
      }
      if (items.isNotEmpty()) results[pinyinKey] = items.values.sortedBy { it.pinyin }
    }
    return results.ifEmpty { null }
  }

  companion object {
    fun expand(entry: CedictEntry, isVariant: Boolean) = DictionaryEntry(
      traditional = entry.traditional,
      simplified = entry.simplified,
      pinyin = entry.pinyin,
      english = entry.english,
      classifiers = entry.classifiers,
      variantOf = entry.variantOf,
      isVariant = isVariant,
    )
  }
}
