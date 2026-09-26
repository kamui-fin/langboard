package app.langboard.core

/**
 * Task contract lb1 (shared/contract/CONTRACT.md) on the phone: the user turn and pre-fill for a
 * fill or naturalize request, rendered exactly as shared/contract/contract.py renders them for
 * training and eval. Keep the two in step; Lb1Test checks this against contract.py's output.
 * The chat template around the turn comes from prompts.json, as for every other prompt.
 */
object Lb1 {
  const val VERSION = "lb1"
  const val UNCHANGED = "UNCHANGED"
  private const val GAP_OPEN = "⟦"
  private const val GAP_CLOSE = "⟧"
  const val MAX_CONTEXT_CHARS = 240

  fun register(r: Register): String = when (r) {
    Register.Casual -> "casual_friend"
    Register.Neutral -> "casual_neutral"
    Register.Formal -> "work_chat"
  }

  fun style(r: Register, p: PersonalStyle): String =
    "${register(r)} slang:${p.slang.lb1} verbosity:${p.verbosity.lb1} directness:${p.directness.lb1}"

  /** The screen as lb1 `conversation_context`: sent and received lines only, oldest first. */
  fun conversation(screen: ScreenText): List<Pair<String, String>> = screen.lines.mapNotNull { l ->
    when (l.align) {
      ScreenLine.Align.Start -> "them" to l.text
      ScreenLine.Align.End -> "me" to l.text
      ScreenLine.Align.Wide -> null
    }
  }

  fun fill(r: FillGapRequest): String = userText(
    task = "fill", lang = "${r.sourceLocale}→${r.targetLocale}", style = style(r.register, r.personal),
    personal = r.personal, context = conversation(r.screen),
    body = "<draft>" + defuse(r.before) + GAP_OPEN + defuse(r.fragment.trim()) + GAP_CLOSE + defuse(r.after) + "</draft>",
  )

  /** Fill continues the draft from the text before the gap; trailing spaces are left to the model. */
  fun fillPrefill(r: FillGapRequest): String = defuse(r.before).trimEnd()

  fun naturalize(text: String, register: Register, personal: PersonalStyle, screen: ScreenText, targetLocale: String = "zh-Hans-CN"): String =
    userText("naturalize", targetLocale, style(register, personal), personal, conversation(screen), "<text>${defuse(text)}</text>")

  private fun userText(
    task: String, lang: String, style: String, personal: PersonalStyle, context: List<Pair<String, String>>, body: String,
  ): String {
    val out = mutableListOf("<task>$task</task>", "<lang>$lang</lang>", "<style>$style</style>")
    val profile = personal.profile.take(MyStyle.MAX_PROFILE)
    if (profile.isNotEmpty()) out += "<profile>\n" + profile.joinToString("\n") { "- ${oneLine(it)}" } + "\n</profile>"
    val examples = personal.examples.take(MyStyle.MAX_EXAMPLES)
    if (examples.isNotEmpty()) out += "<examples>\n" + examples.joinToString("\n") { "- ${oneLine(it)}" } + "\n</examples>"
    val chat = recentContext(context)
    if (chat.isNotEmpty()) out += "<chat>\n" + chat.joinToString("\n") + "\n</chat>"
    out += body
    return out.joinToString("\n")
  }

  private val TAG = Regex("<(/?)(task|lang|style|profile|examples|chat|draft|text)>", RegexOption.IGNORE_CASE)

  /** Anything shaped like one of our tags or the gap markers, made harmless: a message can't close <chat> and open <task>. */
  fun defuse(s: String): String =
    TAG.replace(s) { m -> "‹${m.groupValues[1]}${m.groupValues[2]}›" }.replace(GAP_OPEN, "[").replace(GAP_CLOSE, "]")

  /** Python's " ".join(s.split()): whitespace runs, including newlines, become one space. */
  fun oneLine(s: String): String = defuse(s).split(PY_WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

  /** Newest lines that fit in [maxChars], oldest first. A line that doesn't fit whole is dropped, and so is everything older. */
  fun recentContext(context: List<Pair<String, String>>, maxChars: Int = MAX_CONTEXT_CHARS): List<String> {
    val kept = ArrayList<String>()
    var used = 0
    for ((who, raw) in context.asReversed()) {
      val line = oneLine(raw)
      if (used + line.length > maxChars) break
      kept += "$who: $line"
      used += line.length
    }
    return kept.asReversed()
  }

  private val PY_WHITESPACE = Regex("[\\s\\u001c-\\u001f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+")
}
