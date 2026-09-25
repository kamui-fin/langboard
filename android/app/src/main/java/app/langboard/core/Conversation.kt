package app.langboard.core

/**
 * The text on screen when Langboard was invoked, one line per text element, in reading order. In
 * memory only, for one field; dropped when the keyboard closes.
 */
data class ScreenText(val lines: List<ScreenLine>) {
  /** Everything as one string, the form the model reads. */
  val flat: String get() = lines.joinToString("\n") { it.text }

  val isEmpty: Boolean get() = lines.isEmpty()

  companion object {
    val EMPTY = ScreenText(emptyList())
  }
}

/**
 * [align] is where the element sits horizontally. Chat apps put received bubbles at the start and
 * sent ones at the end; everything else (titles, full-width text) is [Align.Wide].
 */
data class ScreenLine(val text: String, val align: Align = Align.Wide) {
  enum class Align { Start, End, Wide }
}

object Conversation {
  /** How far back a conversation is considered "nearby" for register and the message count. */
  const val RECENT_LINES = 8

  private val TIME = Regex("""^(\d{1,2}[:：]\d{2}(\s?[AaPp][Mm])?|(上午|下午|晚上|凌晨|昨天|今天|星期.|周.)\s*\d{1,2}[:：]\d{2})$""")

  /**
   * The newest message someone else sent that has Chinese in it: the last start-aligned line, or
   * failing that (apps that expose no layout) the last Chinese line that isn't sent by the user.
   * Timestamps and one-character labels are skipped.
   */
  fun latestIncoming(screen: ScreenText): String? {
    val candidates = screen.lines.filter { isMessage(it.text) && it.text.count(FragmentDetector::isCjkIdeograph) >= 2 }
    return (candidates.lastOrNull { it.align == ScreenLine.Align.Start }
      ?: candidates.lastOrNull { it.align == ScreenLine.Align.Wide })?.text?.trim()
  }

  /** Lines that look like chat messages near the field, oldest first. */
  fun recentMessages(screen: ScreenText): List<ScreenLine> =
    screen.lines.filter { it.align != ScreenLine.Align.Wide && isMessage(it.text) }.takeLast(RECENT_LINES)

  private fun isMessage(text: String): Boolean {
    val t = text.trim()
    if (t.length < 2 || TIME.matches(t)) return false
    return t.any { it.isLetter() }
  }
}

enum class Register(val promptName: String) {
  Casual("casual"), Neutral("neutral"), Formal("formal");
}

/** How the people in this conversation talk, and the evidence for it. Inferred locally, never asked for. */
data class RegisterGuess(val register: Register, val cues: List<String>)

object RegisterDetector {
  private val CASUAL = listOf(
    "哈哈", "嘿嘿", "嘻嘻", "我靠", "卧槽", "我去", "绝了", "离谱", "笑死", "无语", "牛逼", "nb", "yyds", "666",
    "啥", "咋", "干嘛", "整", "咱", "hhh", "lol", "lmao", "啦", "呀", "嘛", "哇", "诶", "～", "~", "！！", "??", "？？",
    "宝", "兄弟", "姐妹", "家人们", "破防", "绷不住", "摆烂",
  )
  private val FORMAL = listOf(
    "您", "请问", "麻烦您", "谢谢您", "老师", "领导", "贵公司", "敬请", "此致", "敬礼", "烦请", "恳请", "尊敬的",
    "收到，", "好的，", "特此", "望", "是否方便", "辛苦了",
  )

  /** [lines] are the nearby messages, oldest first; later lines count more. */
  fun infer(lines: List<String>): RegisterGuess {
    if (lines.isEmpty()) return RegisterGuess(Register.Neutral, emptyList())
    var casual = 0.0
    var formal = 0.0
    val cues = LinkedHashSet<String>()
    lines.forEachIndexed { i, raw ->
      val line = raw.lowercase()
      val weight = 0.6 + 0.4 * (i + 1) / lines.size
      CASUAL.filter { line.contains(it) }.forEach { casual += weight; cues += it }
      FORMAL.filter { line.contains(it) }.forEach { formal += weight * 1.5; cues += it }
      if (raw.any(::isEmoji)) { casual += weight; cues += emojiAt(raw) }
    }
    val register = when {
      formal >= 1.5 && formal > casual -> Register.Formal
      casual >= 1.0 && casual >= formal -> Register.Casual
      else -> Register.Neutral
    }
    return RegisterGuess(register, if (register == Register.Neutral) emptyList() else cues.take(3))
  }

  private fun isEmoji(c: Char) = Character.isSurrogate(c) || c in '☀'..'➿'

  private fun emojiAt(s: String): String {
    val i = s.indexOfFirst(::isEmoji)
    return s.substring(i, minOf(s.length, i + if (Character.isHighSurrogate(s[i])) 2 else 1))
  }
}

/**
 * Greedy longest-match word segmentation of Chinese, the way a reader splits a sentence to look
 * words up. [isWord] says whether a string is a dictionary headword. Non-Chinese runs stay whole.
 */
object Segmenter {
  const val MAX_WORD = 8

  fun segment(text: String, isWord: (String) -> Boolean): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (!FragmentDetector.isCjkIdeograph(c)) {
        var j = i + 1
        while (j < text.length && !FragmentDetector.isCjkIdeograph(text[j])) j++
        out += text.substring(i, j)
        i = j
        continue
      }
      var len = minOf(MAX_WORD, text.length - i)
      while (len > 1) {
        val w = text.substring(i, i + len)
        if (w.all(FragmentDetector::isCjkIdeograph) && isWord(w)) break
        len--
      }
      out += text.substring(i, i + len)
      i += len
    }
    return out
  }
}
