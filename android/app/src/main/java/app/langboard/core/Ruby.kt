package app.langboard.core

/**
 * One piece of Chinese text with its reading: a character with its pinyin ([tone] 1–5), or a run of
 * anything else (punctuation, Latin, emoji) with none.
 */
data class Ruby(val text: String, val pinyin: String?, val tone: Int = 0)

object RubyAligner {
  private val SYLLABLE = Regex("^[A-Za-zü:Üv]+[1-5]$")

  /**
   * [word] read as [numbered] (CC-CEDICT style, "lan3 de5"), one piece per character, or null when
   * the syllables don't line up with the characters (letters or erhua in the headword).
   */
  fun align(word: String, numbered: String, toMarks: (String) -> String): List<Ruby>? {
    val syllables = numbered.trim().split(' ').filter { it.isNotEmpty() }
    val chars = word.codePoints().toArray().map { String(Character.toChars(it)) }
    if (syllables.size != chars.size || chars.any { !FragmentDetector.isCjkIdeograph(it[0]) }) return null
    if (syllables.any { !SYLLABLE.matches(it) }) return null
    return chars.zip(syllables) { c, s -> Ruby(c, toMarks(s.lowercase()), s.last() - '0') }
  }

  /** [text] one character at a time from [reading] (character → numbered syllable); other runs whole. */
  fun byCharacter(text: String, reading: (Char) -> String?, toMarks: (String) -> String): List<Ruby> {
    val out = ArrayList<Ruby>()
    val other = StringBuilder()
    fun flush() { if (other.isNotEmpty()) { out += Ruby(other.toString(), null); other.clear() } }
    for (c in text) {
      val r = if (FragmentDetector.isCjkIdeograph(c)) reading(c) else null
      if (r == null) {
        // Latin words stay whole so they wrap as words; spaces end a run.
        other.append(c)
        if (c == ' ') flush()
      } else {
        flush()
        out += Ruby(c.toString(), toMarks(r), r.last() - '0')
      }
    }
    flush()
    return out
  }
}
