package app.langboard.cedict

/**
 * Line parser ported from cc-cedict's build.ts (`parseLine`, `parseMeanings`, `parseVariant`).
 *
 * JavaScript's `\p{Unified_Ideograph}` isn't supported by java.util.regex, and Android's ICU-backed
 * regex differs from the JVM's, so ideographs are spelled out as explicit code point ranges.
 */
object CedictParser {
  private const val IDEOGRAPH =
    "[\\x{3400}-\\x{4DBF}\\x{4E00}-\\x{9FFF}\\x{F900}-\\x{FAFF}\\x{20000}-\\x{323AF}\\x{3006}\\x{3007}]" +
      "[\\x{FE00}-\\x{FE0F}\\x{E0100}-\\x{E01EF}]?"

  val VARIANT_OF = Regex("(variant of (($IDEOGRAPH){1,})?(\\|($IDEOGRAPH){1,})?(\\[([^\\]]*))?)")
  val CLASSIFIERS = Regex("(CL:((($IDEOGRAPH){1,})?(\\|($IDEOGRAPH){1,})?(\\[([^\\]]*)\\]),?)+)")
  val PINYIN = Regex("([A-Za-z:]+[0-9])")

  class Meanings(val meanings: List<String>, val variantOf: List<HanziRef>, val classifiers: List<HanziRef>)

  /** Parses one dictionary line; null for comments, blank or malformed lines. */
  fun parseLine(rawLine: String): CedictEntry? {
    val line = rawLine.trim()
    if (line.isEmpty() || line.startsWith("#")) return null

    val slash = line.indexOf('/')
    if (slash < 0) return null
    val parsed = parseMeanings(line.substring(slash + 1))

    val head = line.substring(0, slash).split('[')
    val chars = head[0]
    val pinyinPart = head.getOrNull(1)
    if (chars.isEmpty() || pinyinPart == null) return null

    val forms = chars.trim().split(' ')
    val traditional = forms[0]
    val simplified = forms.getOrNull(1)
    val pinyin = pinyinPart.substringBefore(']')
    if (traditional.isEmpty() || simplified.isNullOrEmpty() || pinyin.isEmpty()) return null

    return CedictEntry(traditional, simplified, pinyin, parsed.meanings, parsed.classifiers, parsed.variantOf)
  }

  /**
   * Splits the `/`-delimited senses, pulling out "variant of" references and `CL:` classifiers.
   * A sense that is only a variant or classifier marker is not kept as a meaning.
   */
  fun parseMeanings(input: String): Meanings {
    val meanings = mutableListOf<String>()
    val variants = LinkedHashMap<String, HanziRef>()
    val classifiers = LinkedHashMap<String, HanziRef>()

    for (sense in input.replaceFirst("\r", "").split('/')) {
      val trimmed = sense.trim()
      if (trimmed.isEmpty()) continue
      var skipMeaning = false

      VARIANT_OF.find(trimmed)?.let { match ->
        // Upstream drops the whole sense when "variant of" isn't followed by hanzi
        // (e.g. "variant of the above"); we keep the text as an ordinary meaning instead.
        val ref = parseVariant(match.value.substring("variant of ".length))
        if (ref != null) {
          if (match.value == trimmed) skipMeaning = true
          variants.putIfAbsent(ref.key(), ref)
        }
      }
      CLASSIFIERS.find(trimmed)?.let { match ->
        if (match.value == trimmed) skipMeaning = true
        for (part in match.value.substring("CL:".length).split(',')) {
          parseVariant(part)?.let { ref -> classifiers.putIfAbsent(ref.key(), ref) }
        }
      }
      if (!skipMeaning) meanings += trimmed
    }
    return Meanings(meanings, variants.values.toList(), classifiers.values.toList())
  }

  /** Parses `trad|simp[pin1 yin1]`, `hanzi[pin1]` or bare `hanzi`; null for empty input. */
  fun parseVariant(input: String): HanziRef? {
    if (input.isEmpty()) return null
    val parts = input.split('[')
    val chars = parts[0]
    val pinyinPart = parts.getOrNull(1)
    val forms = chars.split('|')
    val first = forms[0]
    if (first.isEmpty()) return null
    val second = forms.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: first
    val pinyin = pinyinPart
      ?.let { PINYIN.findAll(it).joinToString(" ") { m -> m.value } }
      ?.takeIf { it.isNotEmpty() }
    return HanziRef(first, second, pinyin)
  }

  /** Reads `#! key=value` lines; stops scanning at the first entry line. */
  fun parseHeader(lines: Sequence<String>): CedictHeader {
    val values = LinkedHashMap<String, String>()
    for (line in lines) {
      if (!line.startsWith("#")) break
      parseHeaderLine(line)?.let { (k, v) -> values[k] = v }
    }
    return CedictHeader(values)
  }

  /** `#! entries=125101` → ("entries", "125101"); null for other lines. */
  fun parseHeaderLine(line: String): Pair<String, String>? {
    if (!line.startsWith("#! ")) return null
    val kv = line.substring(3)
    val eq = kv.indexOf('=')
    return if (eq > 0) kv.substring(0, eq).trim() to kv.substring(eq + 1).trim() else null
  }

  private fun HanziRef.key() = traditional + simplified + (pinyin ?: "")
}
