package app.langboard.core

/**
 * The user's description of how they want to sound, kept in their own words but made safe to put
 * in front of the model on every request (lb1 `profile`). Whatever produced the lines (the rules in
 * [LocalStyleCompiler] or, later, a cloud compiler), they pass through [clean] before they are kept.
 *
 * - **Only how to speak, never who the user is.** A clause about the person (an age, gender,
 *   nationality, job) is cut out of its sentence; "like people my age" becomes contemporary
 *   everyday texting. Demographics never silently drive output (handoff A.6).
 * - **Only style.** A sentence that tries to change the task (ignore, pretend, reply in, always
 *   add, a link, a tag) is dropped: the model helps say what the user meant, it doesn't take orders
 *   from the profile. So is a sentence that says nothing about language.
 * - **Short and bounded.** One line per statement, at most [MAX_CHARS] each and [MAX_LINES] in all.
 *   Pasted Chinese messages aren't statements; they're examples, kept separately.
 */
object StyleGuide {
  const val MAX_LINES = 4
  const val MAX_CHARS = 100

  fun lines(description: String): List<String> =
    statements(description).mapNotNull(::clean).distinct().take(MAX_LINES)

  /** One statement made safe, or null when nothing about style is left. */
  fun clean(statement: String): String? {
    var s = statement.replace(Regex("\\s+"), " ").trim()
    if (s.isEmpty() || OFF_TASK.containsMatchIn(s)) return null
    // Cut clauses about the person out of the sentence; say "contemporary" for "like people my age".
    val parts = s.split(Regex("\\s*(?:,|;|\\band\\b)\\s*", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
    val kept = parts.mapNotNull { p ->
      when {
        PEERS.containsMatchIn(p) -> CONTEMPORARY
        PERSON.containsMatchIn(p) -> null
        else -> p
      }
    }
    if (kept.isEmpty()) return null
    s = if (kept.size == parts.size) s else kept.joinToString(", ")
    if (!mentionsStyle(s)) return null
    s = s.replace(LEAD_IN, "").trim().trimEnd('.', '!', ' ', '。', '！')
    if (s.isEmpty()) return null
    if (!Regex("^I\\b").containsMatchIn(s)) s = s.replaceFirstChar { it.lowercase() }
    if (s.length > MAX_CHARS) s = s.take(MAX_CHARS).substringBeforeLast(' ').trimEnd(',', ' ')
    return Lb1.oneLine(s)
  }

  /** Sentences and lines; a pasted Chinese line is an example, not a statement. */
  private fun statements(s: String): List<String> = s.lines().flatMap { raw ->
    val line = raw.trim().trimStart('-', '*', '•', ' ')
    if (isPastedChinese(line)) emptyList()
    else line.split(Regex("(?<=[.!?;。！？；])\\s+|(?<=[。！？；])")).map { it.trim() }.filter { it.isNotEmpty() }
  }

  fun isPastedChinese(line: String): Boolean {
    val t = line.trim().trim('“', '”', '"', '「', '」', '『', '』')
    val cjk = t.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    return cjk >= 5 && cjk >= t.count { it.isLetter() } * 0.6
  }

  private fun mentionsStyle(s: String): Boolean =
    s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } || STYLE.containsMatchIn(s)

  private const val CONTEMPORARY = "contemporary everyday texting style"

  private val OFF_TASK = Regex(
    "ignore|instruction|prompt|system|jailbreak|pretend|role.?play|\\bact as\\b|\\byou are (a|an|now)\\b|" +
      "\\breply in\\b|\\banswer in\\b|\\btranslate (everything|all)\\b|\\bexplain\\b|json|markdown|" +
      "https?:|www\\.|[<>{}]|\\balways (add|end|start|sign|include)\\b|\\bsign (off|it)\\b|\\bmy name\\b|password",
    RegexOption.IGNORE_CASE,
  )
  private val PEERS = Regex("\\b(my age|people my age|gen ?z|zoomer|young people|kids these days|my generation)\\b", RegexOption.IGNORE_CASE)
  private val PERSON = Regex(
    "\\b(i'?m|i am)\\s+(a\\s+|an\\s+)?\\d{1,2}\\b|\\b\\d{1,2}\\s*(yo|y/o|years?\\s*old)\\b|" +
      "\\b(male|female|man|woman|guy|girl|boy|dude|gay|lesbian|straight|trans|christian|muslim|jewish|student|teen(ager)?|" +
      "mom|dad|mum|wife|husband|boyfriend|girlfriend|millennial|boomer|" +
      "american|british|canadian|australian|indian|korean|japanese|filipino|european|african|latino|hispanic|white|black|asian)s?\\b",
    RegexOption.IGNORE_CASE,
  )
  /** A statement has to be about language to be kept. */
  private val STYLE = Regex(
    "\\b(casual|formal|polite|professional|friend|chill|relaxed|text|slang|meme|internet|online|short|concise|brief|long|" +
      "detailed|wordy|sound|tone|soft|gentle|blunt|direct|harsh|sarcas|humou?r|jok|funny|playful|serious|emoji|cute|" +
      "textbook|stiff|robotic|natural|native|mainland|word|phrase|particle|sentence|fragment|register|vibe|warm|dry|" +
      "respectful|punctuation|exclamation|say|write|wording|express|mandarin|chinese|contemporary|disagree|react)",
    RegexOption.IGNORE_CASE,
  )
  /** "I want my Chinese to sound casual" → "sound casual". */
  private val LEAD_IN = Regex(
    "^(please\\s+|also\\s+|and\\s+)*(i(?:'d| would)? (?:want|like|prefer) (?:my chinese |it |you |langboard |things )?to\\s+|make (?:it|me)\\s+(?=sound))?",
    RegexOption.IGNORE_CASE,
  )
}
