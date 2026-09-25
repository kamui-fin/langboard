package app.langboard.core

import org.json.JSONObject

/**
 * Every prompt and decoding setting for Hy-MT2, from `prompts.json` (bundled in assets; a copy
 * pushed to the app's external files directory overrides it, for trying prompts on the phone
 * without a rebuild). shared/eval/prompt_eval.py renders the same file the same way on a laptop; keep
 * the two in step.
 *
 * Templates fill `{name}` placeholders. A fill prompt asks for the whole sentence being written,
 * English still in it, in Chinese, with the chat on screen (nearest the field, one line per message
 * with who sent it, "对方：", "我：") as background and the style from the chat's register. The
 * answer is started with the user's own Chinese before the gap, so the model carries on the
 * sentence from there: what it writes next has to fit what came before.
 */
class PromptBook(
  val version: String,
  private val chatTemplate: String,
  private val speakers: Map<ScreenLine.Align, String>,
  private val styles: Map<Register, String>,
  val fill: Fill,
  val explain: Explain,
  val check: Check,
) {
  class Fill(
    val template: String,
    val templateNoContext: String,
    val context: String,
    val screenChars: Int,
    val beams: Int,
    /** Tokens for the answer; the start of the text after the gap gets room on top. */
    val maxTokens: Int,
    val lengthAlpha: Float,
    /** How many of the beams' distinct answers are scored in the sentence. */
    val rescore: Int,
    /** How much of the text after the gap each answer is scored with. */
    val afterChars: Int,
    /** Scored after an answer when nothing follows the gap: the sentence ends there. */
    val endMark: String,
    /** The first answer stays first, so the list doesn't move under the user's finger. */
    val pinFirst: Boolean,
    /** Logit penalty on tokens with Latin letters; infinity (the default when unset) bans them. */
    val latinPenalty: Float = Float.POSITIVE_INFINITY,
  )

  class Explain(
    val template: String,
    val templateNoContext: String,
    val context: String,
    val speakers: Map<ScreenLine.Align, String>,
    val screenChars: Int,
    val maxTokens: Int,
    val repeatPenalty: Float,
  )

  class Check(
    val template: String,
    val templateNoContext: String,
    val context: String,
    val screenChars: Int,
    val beams: Int,
    val maxTokens: Int,
    val lengthAlpha: Float,
  )

  /** The sentence as the user is writing it, with the English in place. */
  fun draft(r: FillGapRequest): String = "${r.before}${r.fragment}${r.after}".trim()

  /** The fill prompt, ending with the Chinese before the gap already written as the answer. */
  fun fill(r: FillGapRequest): String {
    val chat = recent(r.screen, speakers, fill.screenChars)
    val style = styles.getValue(r.register)
    val user =
      if (chat.isEmpty()) vars(fill.templateNoContext, "style" to style, "draft" to draft(r))
      else vars(fill.template, "context" to vars(fill.context, "chat" to chat), "style" to style, "draft" to draft(r))
    return turn(user) + r.before.trimEnd()
  }

  fun explain(message: String, screen: ScreenText): String {
    val chat = recent(screen, explain.speakers, explain.screenChars)
    return turn(
      if (chat.isEmpty()) vars(explain.templateNoContext, "message" to message)
      else vars(explain.template, "context" to vars(explain.context, "chat" to chat), "message" to message)
    )
  }

  fun check(sentence: String, register: Register, screen: ScreenText): String {
    val chat = recent(screen, speakers, check.screenChars)
    val style = styles.getValue(register)
    return turn(
      if (chat.isEmpty()) vars(check.templateNoContext, "style" to style, "sentence" to sentence)
      else vars(check.template, "context" to vars(check.context, "chat" to chat), "style" to style, "sentence" to sentence)
    )
  }

  /** One user turn in the model's chat format (Hy-MT2 has no system prompt). */
  private fun turn(user: String) = vars(chatTemplate, "user" to user)

  companion object {
    fun parse(json: String): PromptBook {
      val o = JSONObject(json)
      val f = o.getJSONObject("fill")
      val e = o.getJSONObject("explain")
      val c = o.getJSONObject("check")
      return PromptBook(
        version = o.optString("version", "?"),
        chatTemplate = o.getString("chat_template"),
        speakers = speakers(o.getJSONObject("speakers")),
        styles = o.getJSONObject("styles").let { s -> Register.entries.associateWith { s.getString(it.promptName) } },
        fill = Fill(
          template = f.getString("template"),
          templateNoContext = f.getString("template_no_context"),
          context = f.getString("context"),
          screenChars = f.getInt("screen_chars"),
          beams = f.getInt("beams"),
          maxTokens = f.getInt("max_tokens"),
          lengthAlpha = f.getDouble("length_alpha").toFloat(),
          rescore = f.getInt("rescore"),
          afterChars = f.getInt("after_chars"),
          endMark = f.getString("end_mark"),
          pinFirst = f.getBoolean("pin_first"),
          latinPenalty = f.optDouble("latin_penalty", Double.POSITIVE_INFINITY).toFloat(),
        ),
        explain = Explain(
          template = e.getString("template"),
          templateNoContext = e.getString("template_no_context"),
          context = e.getString("context"),
          speakers = speakers(e.getJSONObject("speakers")),
          screenChars = e.getInt("screen_chars"),
          maxTokens = e.getInt("max_tokens"),
          repeatPenalty = e.getDouble("repeat_penalty").toFloat(),
        ),
        check = Check(
          template = c.getString("template"),
          templateNoContext = c.getString("template_no_context"),
          context = c.getString("context"),
          screenChars = c.getInt("screen_chars"),
          beams = c.getInt("beams"),
          maxTokens = c.getInt("max_tokens"),
          lengthAlpha = c.getDouble("length_alpha").toFloat(),
        ),
      )
    }

    private fun speakers(o: JSONObject) = mapOf(
      ScreenLine.Align.Start to o.optString("them"),
      ScreenLine.Align.End to o.optString("me"),
      ScreenLine.Align.Wide to o.optString("other"),
    )

    fun vars(template: String, vararg values: Pair<String, String>): String =
      values.fold(template) { s, (k, v) -> s.replace("{$k}", v) }

    /**
     * The chat nearest the field, oldest first, labeled by who sent each line, cut to whole lines
     * within [maxChars]. Reading the prompt is most of the wait on a phone, so this is bounded.
     */
    fun recent(screen: ScreenText, speakers: Map<ScreenLine.Align, String>, maxChars: Int): String {
      val out = ArrayList<String>()
      var used = 0
      for (l in screen.lines.asReversed()) {
        val text = l.text.trim()
        if (text.isEmpty()) continue
        val line = speakers[l.align].orEmpty() + text
        if (used + line.length > maxChars && out.isNotEmpty()) break
        out += line
        used += line.length + 1
      }
      return out.asReversed().joinToString("\n")
    }
  }
}
