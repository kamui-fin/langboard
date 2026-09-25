package app.langboard.dictionary

import app.langboard.cedict.DictionaryEntry

/** Pure query rules behind [DictionarySearch]; no SQLite, so they're unit-tested on the JVM. */
internal object QueryRules {
  private val NON_WORD = Regex("[^a-z0-9]+")
  private val PARENS = Regex("\\([^)]*\\)")
  private val SPACES = Regex("\\s+")

  /** Function words, plus the stems FTS leaves from contractions ("couldn't" → "couldn" + "t"). */
  val STOPWORDS = setOf(
    "a", "an", "the", "i", "im", "me", "my", "you", "your", "it", "its", "is", "am", "are", "was", "were",
    "be", "been", "to", "of", "and", "or", "in", "on", "at", "for", "so", "just", "really", "very", "anymore",
    "do", "does", "did", "that", "this",
    "t", "s", "d", "ll", "re", "ve", "m",
    "couldn", "wouldn", "shouldn", "didn", "don", "doesn", "isn", "wasn", "weren", "aren", "haven", "hasn", "won",
  )

  fun isChinese(query: String) = query.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

  /** Smallest string greater than every string starting with [prefix] (last code point + 1). */
  fun prefixUpperBound(prefix: String): String {
    require(prefix.isNotEmpty())
    val cps = prefix.codePoints().toArray()
    cps[cps.lastIndex]++
    return String(cps, 0, cps.size)
  }

  /** Lowercase alphanumeric words, as the FTS tokenizer sees them. */
  fun words(query: String) = query.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

  /** Words worth matching; falls back to all words when the query is only stopwords ("to be"). */
  fun contentWords(words: List<String>) = words.filter { it !in STOPWORDS && it.length > 1 }.ifEmpty { words }

  /** Implicit-AND FTS query; the last word becomes a prefix for search-as-you-type. */
  fun ftsAnd(content: List<String>, prefixLast: Boolean) =
    content.mapIndexed { i, w -> if (prefixLast && i == content.lastIndex) "$w*" else w }.joinToString(" ")

  fun ftsOr(content: List<String>) = content.joinToString(" OR ")

  /** Lowercased sense with parentheticals and a leading "to " removed: "to open (a door)" → "open". */
  fun normalizeSense(sense: String) = sense.lowercase()
    .replace(PARENS, " ")
    .replace(NON_WORD, " ")
    .trim()
    .replace(SPACES, " ")
    .removePrefix("to ")

  /** CC-CEDICT has no frequency data, so rank by how directly a sense matches the query. */
  fun score(e: DictionaryEntry, phrase: String, content: List<String>): Int {
    val senses = e.english.map(::normalizeSense)
    var s = when {
      senses.any { it == phrase } -> 100
      senses.any { it.startsWith("$phrase ") } -> 45
      senses.any { " $it ".contains(" $phrase ") } -> 25
      else -> 0
    }
    val senseWords = senses.flatMap { it.split(' ') }.toSet()
    s += content.count { w -> senseWords.any { it.startsWith(w) } } * 8
    if (e.english.any { it.startsWith("surname ") }) s -= 30
    if (e.pinyin.firstOrNull()?.isUpperCase() == true) s -= 20 // proper noun
    if (e.english.isEmpty() || e.english.all { "variant of" in it }) s -= 40
    if (e.english.any { it.startsWith("(archaic)") || it.startsWith("(old)") || it.startsWith("(literary)") }) s -= 10
    return s
  }

  /** Best first; ties go to shorter headwords, then shorter definitions. */
  fun rank(entries: List<DictionaryEntry>, phrase: String, content: List<String>): List<DictionaryEntry> =
    entries
      .map { it to score(it, phrase, content) }
      .sortedWith(
        compareByDescending<Pair<DictionaryEntry, Int>> { it.second }
          .thenBy { it.first.simplified.length }
          .thenBy { it.first.english.sumOf(String::length) }
      )
      .map { it.first }
}
