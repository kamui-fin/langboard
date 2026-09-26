package app.langboard.core

/**
 * What a description of how the user wants to sound comes to. Null controls weren't mentioned and
 * keep their current value.
 */
data class CompiledStyle(
  val register: Register? = null,
  val slang: Slang? = null,
  val verbosity: Verbosity? = null,
  val directness: Directness? = null,
  val rules: List<StyleRule> = emptyList(),
  val examples: List<String> = emptyList(),
  /** The description's style statements, cleaned by [StyleGuide]. */
  val guide: List<String> = emptyList(),
) {
  val isEmpty: Boolean
    get() = register == null && slang == null && verbosity == null && directness == null && rules.isEmpty() && examples.isEmpty() && guide.isEmpty()
}

/** Turns the user's own description into [CompiledStyle]. Runs only when they ask, never per request. */
fun interface StyleCompiler {
  suspend fun compile(description: String): CompiledStyle
}

/**
 * The on-device compiler: reads plain statements ("keep it short", "some slang is fine", "I say
 * 哈哈哈 instead of 笑死", "I don't like 老铁") and pasted messages. It only ever produces linguistic
 * settings: nothing it can output describes who the user is, so "like people my age text" becomes
 * casual and contemporary, never an age. Whatever it misses, the user can add by hand, and it shows
 * what it understood before anything is used.
 */
object LocalStyleCompiler : StyleCompiler {
  override suspend fun compile(description: String): CompiledStyle = compileNow(description)

  fun compileNow(description: String): CompiledStyle {
    var register: Register? = null
    var slang: Slang? = null
    var verbosity: Verbosity? = null
    var directness: Directness? = null
    val rules = ArrayList<StyleRule>()
    val examples = ArrayList<String>()

    for (clause in clauses(description, examples)) {
      val lower = clause.lowercase()
      val words = CJK_RUN.findAll(clause).map { it.value }.toList()

      // A line (or quote) that is mostly Chinese and sentence-length is an example of how they write.
      val longs = words.filter { it.length >= MIN_EXAMPLE_CHARS }
      if (longs.isNotEmpty()) {
        examples += longs
        continue
      }

      val shorts = words.filter { it.length <= MAX_WORD_CHARS }
      if (shorts.isNotEmpty()) {
        val over = INSTEAD_OF.find(clause)
        when {
          over != null -> {
            val (x, y) = over.groupValues[1] to over.groupValues[2]
            if (negated(lower.substring(0, over.range.first.coerceAtMost(lower.length)))) rules += StyleRule(StyleRule.Kind.Prefer, y, x)
            else rules += StyleRule(StyleRule.Kind.Prefer, x, y)
          }
          AVOID.containsMatchIn(lower) -> shorts.forEach { rules += StyleRule(StyleRule.Kind.Avoid, it) }
          PREFER.containsMatchIn(lower) -> shorts.forEach { rules += StyleRule(StyleRule.Kind.Prefer, it) }
        }
        continue
      }

      // Controls, from English. Checked in this order so "not too formal" reads as casual.
      when {
        mentions(lower, FORMAL_WORDS) && negatedAt(lower, FORMAL_WORDS) -> register = register ?: Register.Casual
        mentions(lower, FORMAL_WORDS) -> register = Register.Formal
        mentions(lower, CASUAL_WORDS) && !negatedAt(lower, CASUAL_WORDS) -> register = register ?: Register.Casual
        "neutral" in lower -> register = Register.Neutral
      }
      if (mentions(lower, SLANG_WORDS)) {
        slang = if (negatedAt(lower, SLANG_WORDS) || LITTLE.containsMatchIn(lower)) Slang.Low else Slang.Medium
      }
      if (mentions(lower, SHORT_WORDS) && !negatedAt(lower, SHORT_WORDS)) verbosity = Verbosity.Concise
      else if (mentions(lower, LONG_WORDS) && !negatedAt(lower, LONG_WORDS)) verbosity = Verbosity.Expressive
      if (mentions(lower, BLUNT_WORDS) && negatedAt(lower, BLUNT_WORDS)) directness = Directness.Soft
      else if (mentions(lower, SOFT_WORDS) && !negatedAt(lower, SOFT_WORDS)) directness = Directness.Soft
      else if (mentions(lower, BLUNT_WORDS)) directness = Directness.Direct

      if (mentions(lower, CONTEMPORARY_WORDS)) register = register ?: Register.Casual
    }

    return CompiledStyle(
      register, slang, verbosity, directness,
      rules.distinctBy { Triple(it.kind, it.text, it.over) }.take(MyStyle.MAX_PROFILE),
      examples.distinct().take(MyStyle.MAX_EXAMPLES),
      StyleGuide.lines(description),
    )
  }

  /**
   * Sentences and the halves of "X but Y", each judged on its own. A line that is mostly Chinese is
   * a pasted message: it goes to [examples] whole, commas and all.
   */
  private fun clauses(s: String, examples: MutableList<String>): List<String> = s.lines().flatMap { raw ->
    val line = raw.trim().trimStart('-', '*', '•', ' ').trim().trim('“', '”', '"', '「', '」', '『', '』')
    when {
      line.isEmpty() -> emptyList()
      StyleGuide.isPastedChinese(line) -> { examples += line; emptyList() }
      else -> line.split(Regex("[.;!?]+\\s*|,\\s*|\\bbut\\b|\\bthough\\b", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
    }
  }

  private fun mentions(s: String, keys: List<String>) = keys.any { k -> Regex("\\b${Regex.escape(k)}").containsMatchIn(s) }

  /** A negation within a few words before any of [keys]: "no slang", "not too blunt", "don't make it formal". */
  private fun negatedAt(s: String, keys: List<String>): Boolean = keys.any { k ->
    Regex("\\b${Regex.escape(k)}").findAll(s).any { m -> negated(s.substring(0, m.range.first)) }
  }

  private fun negated(before: String): Boolean {
    val recent = before.split(Regex("\\s+")).filter { it.isNotEmpty() }.takeLast(NEGATION_REACH).joinToString(" ")
    return NEGATION.containsMatchIn(recent)
  }

  private val CJK_RUN = Regex("[\\u3400-\\u9fff\\uf900-\\ufaff][\\u3400-\\u9fff\\uf900-\\ufaff，、。…～~！？]*")
  private val INSTEAD_OF = Regex(
    "([\\u3400-\\u9fff]+)[”\"」』']?\\s*(?:instead of|over|rather than|not|and not)\\s*[“\"「『']?([\\u3400-\\u9fff]+)",
    RegexOption.IGNORE_CASE,
  )
  private val AVOID = Regex("\\b(don'?t|do not|never|avoid|no|not|hate|dislike|stop|without|cringe)\\b")
  private val PREFER = Regex("\\b(like|love|prefer|use|say|write|more|fine)\\b")
  private val NEGATION = Regex("\\b(no|not|don'?t|do not|never|avoid|without|less|hate|dislike|nothing|isn'?t|aren'?t|too much|overdo)\\b")
  private val LITTLE = Regex("\\b(a little|little|light|minimal|barely|rarely|low)\\b")
  private const val NEGATION_REACH = 4
  private const val MIN_EXAMPLE_CHARS = 5
  private const val MAX_WORD_CHARS = 4

  private val FORMAL_WORDS = listOf("formal", "professional", "for work", "at work", "work chat", "workplace", "office", "colleague", "boss", "manager", "client", "polished")
  private val CASUAL_WORDS = listOf("casual", "friend", "chill", "relaxed", "texting", "laid-back", "laid back", "informal")
  private val SLANG_WORDS = listOf("slang", "meme", "internet speak", "internet words", "chronically online", "online speak")
  private val SHORT_WORDS = listOf("short", "concise", "brief", "to the point", "terse", "few words")
  private val LONG_WORDS = listOf("detailed", "expressive", "longer", "full sentences", "elaborate", "wordy")
  private val SOFT_WORDS = listOf("soft", "gentle", "polite", "diplomatic", "tactful")
  private val BLUNT_WORDS = listOf("blunt", "direct", "straightforward", "harsh", "rude")
  private val CONTEMPORARY_WORDS = listOf("my age", "young", "modern", "contemporary", "how people text")
}
