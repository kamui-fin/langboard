package app.langboard.core

/**
 * What Hy-MT2 writes, cleaned up into answers. The prompts themselves are in [PromptBook]
 * (assets/prompts.json).
 */
object HyMtPrompts {
  /**
   * The replacement for the fragment, from what the model wrote. The model sometimes returns the
   * whole sentence (周末一起去看演唱会吗？ for "concert") or repeats a character on either side
   * (太疯狂了 inside 这个事情太…了); those overlaps are removed. Null when nothing Chinese is left.
   */
  fun cleanFill(output: String, r: FillGapRequest, trimAfter: Boolean = true): String? {
    val s = trimToGap(output, r, trimAfter) ?: return null
    // The sentence around the gap, echoed back (他又迟到了 for "他又迟到了，ngmi"), isn't an answer.
    val bare = s.filter { it.isLetterOrDigit() }
    if (bare.length >= 3 && (r.before.filter { it.isLetterOrDigit() }.endsWith(bare) || r.after.filter { it.isLetterOrDigit() }.startsWith(bare))) return null
    // A line copied back from the chat isn't an answer; nor is English copied from the prompt.
    if (r.screen.lines.any { l -> l.text.trim().let { it.length >= 4 && (s.contains(it) || (s.length >= 6 && it.contains(s))) } }) return null
    if (s.count(FragmentDetector::isLatinLetter) > 2 && r.fragment.split(' ').any { it.length > 2 && s.contains(it, ignoreCase = true) }) return null
    // Other English is a leak unless Chinese really writes it that way (AI, CC, AA制, 有点emo).
    if (!LATIN_WORD.findAll(s).all { latinOk(it.value) }) return null
    return s
  }

  private val LATIN_WORD = Regex("[A-Za-z]+")

  /** Loans Chinese writes in lowercase; all-caps acronyms up to five letters are fine too. Mirrors contract.ZH_LATIN_OK. */
  private val LATIN_LOANS = setOf("emo", "ok", "app", "wifi", "cc", "vlog", "pdf", "logo")

  fun latinOk(word: String): Boolean = (word.length <= 5 && word.all { it.isUpperCase() }) || word.lowercase() in LATIN_LOANS

  /**
   * The answer in a continuation of the sentence (the model writes on from the Chinese before the
   * gap): up to the last point where the rest is the start of the text after the gap (or that text
   * is its start), else up to the first punctuation. Cleaned as [cleanFill], except that the text
   * after the gap is already cut off: 受不了 before 了 keeps its own 了.
   */
  fun gapAnswer(continuation: String, r: FillGapRequest): String? = gapOf(continuation, r.after)?.let { cleanFill(it, r, trimAfter = false) }

  /** Punctuation: any of it ends the phrase in the gap. */
  const val PUNCTUATION = "，。！？、；：,.!?;:…~～"

  /**
   * What ends a continuation: a token with punctuation in it, or text containing the first two
   * characters after the gap (one is too few: 受不了 already contains the 了 that follows the gap).
   */
  fun stopsFor(after: String): Pair<List<String>, String> {
    val a = after.trimStart()
    return PUNCTUATION.map(Char::toString) to (if (a.length >= 2 && a[0] !in PUNCTUATION) a.take(2) else "")
  }

  /** The start of the text after the gap that answers are scored with: up to [n] characters, to the first sentence end. */
  fun afterHead(after: String, n: Int): String {
    val a = after.trimStart().take(n)
    val end = a.indexOfFirst { it in "。！？!?" }
    return if (end >= 0) a.take(end + 1) else a
  }

  /**
   * Whether a continuation goes on from its answer [gap] into the text after the gap: what follows
   * the answer is the start of that text, or that text is its start, or (with nothing after the gap)
   * nothing but punctuation follows. One that wanders off elsewhere doesn't fit the sentence.
   */
  fun leadsOn(continuation: String, gap: String, after: String): Boolean {
    val s = continuation.lineSequence().first().trimEnd { it in PUNCTUATION || it == ' ' }
    val at = s.indexOf(gap).takeIf { it >= 0 } ?: return false
    val rest = s.substring(at + gap.length).trim()
    val a = after.trim().trimEnd { it in PUNCTUATION }
    return (a.startsWith(rest) && (rest.isNotEmpty() || a.isEmpty())) || (a.isNotEmpty() && rest.startsWith(a))
  }

  fun gapOf(continuation: String, after: String): String? {
    val s = continuation.lineSequence().first().trimEnd { it in PUNCTUATION || it == ' ' }
    val a = after.trim()
    val fits = { rest: String -> a.startsWith(rest) || rest.startsWith(a) }
    // Nothing written before the text after the gap.
    if (a.isNotEmpty() && s.startsWith(a[0]) && fits(s)) return null
    if (a.isNotEmpty()) {
      for (i in s.length - 1 downTo 1) if (s[i] == a[0] && fits(s.substring(i))) return s.take(i).trim().ifEmpty { null }
    }
    val cut = s.withIndex().firstOrNull { (i, c) -> i > 0 && c in PUNCTUATION }?.index ?: s.length
    return s.take(cut).trim().ifEmpty { null }
  }

  /** The answers from a beam search, cleaned, each once, best first. */
  fun cleanFills(outputs: List<String>, r: FillGapRequest, max: Int): List<String> =
    outputs.mapNotNull { cleanFill(it, r) }.distinct().take(max)

  /**
   * A rewrite of [original] from a check, or null when it only differs in punctuation and spacing
   * (or, unless [picky], also in sentence-final particles): the sentence already reads naturally.
   */
  fun cleanCheck(output: String, original: String, screen: ScreenText, picky: Boolean): String? {
    val s = firstLine(output).trim('"', '“', '”')
    if (s.isEmpty() || !s.any(FragmentDetector::isCjkIdeograph)) return null
    if (screen.lines.any { l -> l.text.trim().let { it.length >= 4 && it != original && s.contains(it) } }) return null
    val ignore: (Char) -> Boolean = { c -> !c.isLetterOrDigit() || (!picky && c in PARTICLES) }
    if (s.filterNot(ignore) == original.filterNot(ignore)) return null
    // Keep the user's own ending: a rewrite that adds 。 or ！ shouldn't change how they punctuate.
    val body = s.trimEnd { !it.isLetterOrDigit() }
    return body + original.takeLastWhile { !it.isLetterOrDigit() }
  }

  /** Sentence-final particles: adding or dropping one is a matter of taste, not a correction. */
  private const val PARTICLES = "了啊呀吧呢哦嘛啦哈呗喔"

  private fun trimToGap(output: String, r: FillGapRequest, trimAfter: Boolean): String? {
    var s = firstLine(output)
    s = s.removeWhole(r.before.trim(), String::removePrefix)
    s = dropOverlapBefore(s, r.before.trimEnd())
    if (trimAfter) {
      s = s.removeWhole(r.after.trim(), String::removeSuffix)
      s = dropOverlapAfter(s, r.after.trimStart())
    }
    s = s.trim().trim('“', '”', '"', '「', '」', '【', '】')
    // A lone full stop doesn't belong mid-sentence; the user's own punctuation follows.
    if (r.after.isNotBlank() || r.before.isNotBlank()) s = s.trimEnd('。')
    // Chinese, or a bare acronym Chinese uses as is (AI, PPT).
    return s.takeIf { it.any(FragmentDetector::isCjkIdeograph) || (s.length in 2..5 && s.all { c -> c in 'A'..'Z' }) }
  }

  fun cleanTranslation(output: String): String? = firstLine(output).trim('"', '“', '”').takeIf { it.isNotBlank() }

  private fun firstLine(output: String) = output.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

  /** [remove] applied when something is left afterwards. */
  private fun String.removeWhole(part: String, remove: String.(String) -> String): String =
    remove(part).trim().ifEmpty { this }

  /** Removes the longest start of [s] that [before] already ends with, keeping at least one character. */
  private fun dropOverlapBefore(s: String, before: String): String {
    for (n in minOf(s.length - 1, before.length) downTo 1) {
      if (before.endsWith(s.substring(0, n))) return s.substring(n)
    }
    return s
  }

  private fun dropOverlapAfter(s: String, after: String): String {
    for (n in minOf(s.length - 1, after.length) downTo 1) {
      if (after.startsWith(s.substring(s.length - n))) return s.substring(0, s.length - n)
    }
    return s
  }
}
