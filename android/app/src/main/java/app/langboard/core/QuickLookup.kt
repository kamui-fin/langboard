package app.langboard.core

/**
 * Rules for the instant dictionary row, independent of storage. Only a single word gets a quick
 * answer, looked up as typed and then by its base form ("meetings" → "meeting"). Phrases are the
 * model's job; their words are never combined here. The per-word breakdown lives behind "Words ›".
 */
object QuickLookup {
  const val MAIN_CHIPS = 3
  const val WORD_CHIPS = 4
  const val MAX_WORDS = 6

  fun isSingleWord(phrase: String): Boolean = key(phrase).let { it.isNotEmpty() && it.all(Char::isLetter) }

  /** Lowercased, curly apostrophes straightened, spacing collapsed: the dictionary's key form. */
  fun key(s: String): String = s.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trim().trim('.', ',', '!', '?')

  /**
   * Keys to try for [phrase], best first. [lemmaOf] maps an inflected word or phrase to its headword
   * when the dictionary knows one ("matches" → "match").
   */
  fun candidateKeys(phrase: String, lemmaOf: (String) -> String?): List<String> {
    val k = key(phrase)
    if (k.isEmpty()) return emptyList()
    val out = linkedSetOf(k)
    lemmaOf(k)?.let(out::add)
    val words = k.split(' ')
    if (words.size > 1) {
      // Only the last word inflects in most English phrases: "top matches", "hanging out" aside.
      val last = words.last()
      val base = lemmaOf(last) ?: naiveSingular(last)
      if (base != null && base != last) out += (words.dropLast(1) + base).joinToString(" ")
      val first = words.first()
      val firstBase = lemmaOf(first)
      if (firstBase != null && firstBase != first) out += (listOf(firstBase) + words.drop(1)).joinToString(" ")
    }
    return out.toList()
  }

  /** "matches" → "match", "cats" → "cat"; null when the word doesn't look plural. */
  fun naiveSingular(w: String): String? = when {
    w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
    w.length > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes")) -> w.dropLast(2)
    w.length > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
    else -> null
  }

  /** The words worth looking up on their own: no function words, no repeats, at most [MAX_WORDS]. */
  fun wordsOf(phrase: String): List<String> =
    key(phrase).split(' ', '-')
      .map { it.trim('\'', ',', '.') }
      .filter { it.length > 1 && it !in STOP_WORDS && it.any(Char::isLetter) }
      .distinct()
      .take(MAX_WORDS)

  /** True when "Words ›" has something to show beyond the phrase itself. */
  fun hasWords(phrase: String): Boolean {
    val words = wordsOf(phrase)
    return words.size > 1 || words.size == 1 && words[0] != key(phrase)
  }

  private val STOP_WORDS = setOf(
    "a", "an", "the", "to", "of", "in", "on", "at", "for", "with", "by", "from", "as", "and", "or", "but",
    "is", "am", "are", "was", "were", "be", "been", "being", "it", "its", "it's", "this", "that", "i", "i'm",
    "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "so", "too", "very",
    "just", "do", "does", "did", "not", "no", "can", "could", "would", "should", "will", "have", "has", "had",
    "couldn't", "can't", "don't", "didn't", "won't", "wouldn't", "anymore",
  )
}
