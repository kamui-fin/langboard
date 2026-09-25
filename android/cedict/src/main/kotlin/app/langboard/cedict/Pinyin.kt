package app.langboard.cedict

/** Converts CC-CEDICT numbered pinyin (`Zhong1 guo2`, `nu:3`, `r5`) to tone marks (`Zhōng guó`, `nǚ`, `r`). */
object Pinyin {
  private val MARKS = mapOf(
    'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì", 'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
    'A' to "ĀÁǍÀ", 'E' to "ĒÉĚÈ", 'I' to "ĪÍǏÌ", 'O' to "ŌÓǑÒ", 'U' to "ŪÚǓÙ", 'Ü' to "ǕǗǙǛ",
  )
  private val SYLLABLE = Regex("^([A-Za-zü:Ü]+)([1-5])$")

  fun toToneMarks(numbered: String): String = numbered.split(' ').joinToString(" ", transform = ::syllable)

  private fun syllable(token: String): String {
    val m = SYLLABLE.matchEntire(token) ?: return token
    val base = m.groupValues[1].replace("u:", "ü").replace("U:", "Ü").replace('v', 'ü').replace('V', 'Ü')
    val tone = m.groupValues[2][0] - '0'
    if (tone == 5) return base
    val lower = base.lowercase()
    val at = when {
      'a' in lower -> lower.indexOf('a')
      'e' in lower -> lower.indexOf('e')
      "ou" in lower -> lower.indexOf('o')
      else -> lower.indexOfLast { it in "aeiouü" }
    }
    if (at < 0) return base
    val marked = MARKS[base[at]]?.get(tone - 1) ?: return base
    return base.substring(0, at) + marked + base.substring(at + 1)
  }
}
