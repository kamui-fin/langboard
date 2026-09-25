package app.langboard.core

/**
 * A detected English span near the caret, plus everything needed to verify and replace it later.
 *
 * In trailing mode the text before the caret ends with `span + tail`, where `span` may begin or end
 * with separator whitespace that is dropped on replacement and `tail` (trailing punctuation/spaces,
 * or Chinese the user typed after the English) is preserved verbatim. In selection mode `span` is exactly the selected text.
 */
data class Detection(
  val fragment: String,
  val span: String,
  val tail: String,
  val contextBefore: String,
  val contextAfter: String,
  val fromSelection: Boolean,
)

sealed interface DetectResult {
  data class Found(val detection: Detection) : DetectResult
  /** Nothing English next to the caret. */
  data object None : DetectResult
  /** Something English is there but not clearly bounded; ask for explicit entry. */
  data object Ambiguous : DetectResult
  data object TooLong : DetectResult
}

object FragmentDetector {
  const val MAX_FRAGMENT_CHARS = 120
  /** Roughly a sentence of Chinese context kept for the request. */
  const val CONTEXT_CHARS = 60
  /** How much text typed after the English still counts as the same thought. */
  const val MAX_RESUMED_CHARS = 40
  private const val SENTENCE_END = "。！？!?.…"

  /**
   * @param before text before the caret (bounded window), or null if the host gave nothing.
   * @param selected selected text, or null/empty when the selection is collapsed.
   * @param after text after the caret (bounded window).
   * @param beforeIsComplete true when [before] reaches the start of the field (not truncated).
   */
  fun detect(before: String?, selected: String?, after: String?, beforeIsComplete: Boolean): DetectResult {
    val ctxAfter = (after ?: "").take(CONTEXT_CHARS)

    if (!selected.isNullOrEmpty()) {
      if (!selected.any(::isLatinLetter)) return DetectResult.None
      if (selected.length > MAX_FRAGMENT_CHARS) return DetectResult.TooLong
      return DetectResult.Found(
        Detection(
          fragment = selected.trim(),
          span = selected,
          tail = "",
          contextBefore = (before ?: "").takeLast(CONTEXT_CHARS),
          contextAfter = ctxAfter,
          fromSelection = true,
        )
      )
    }

    if (before.isNullOrEmpty()) return DetectResult.None
    val trailing = detectTrailing(before, ctxAfter, after, beforeIsComplete)
    if (trailing != DetectResult.None) return trailing
    return detectResumed(before, ctxAfter, after, beforeIsComplete)
  }

  /** English right before the caret, optionally followed by punctuation/spaces. */
  private fun detectTrailing(before: String, ctxAfter: String, after: String?, complete: Boolean): DetectResult {
    // 1. Trailing tail: terminal punctuation and whitespace we keep as-is.
    var end = before.length
    while (end > 0 && isTailChar(before[end - 1])) end--
    return coreEndingAt(before, end, spanEnd = end, ctxAfter, after, complete)
  }

  /**
   * "Chinese resumed": the English sits earlier in the current sentence and the user already typed
   * Chinese after it, e.g. `我觉得这个有点 overkill 而且`. Everything after the English is kept.
   */
  private fun detectResumed(before: String, ctxAfter: String, after: String?, complete: Boolean): DetectResult {
    var i = before.length
    while (i > 0 && (before[i - 1].isWhitespace() || before[i - 1] in SENTENCE_END)) i--
    while (i > 0 && !isLatinLetter(before[i - 1])) {
      if (before[i - 1] in SENTENCE_END || before[i - 1] == '\n' || before.length - i > MAX_RESUMED_CHARS) {
        return DetectResult.None
      }
      i--
    }
    if (i == 0) return DetectResult.None
    // Space between the English and the Chinese that follows is glue, dropped with the English.
    var spanEnd = i
    while (spanEnd < before.length && before[spanEnd] == ' ') spanEnd++
    if (spanEnd == before.length || !isCjk(before[spanEnd])) spanEnd = i
    return coreEndingAt(before, i, spanEnd, ctxAfter, after, complete)
  }

  /** Builds a detection for the English core ending at [end]; the replaced span runs to [spanEnd]. */
  private fun coreEndingAt(
    before: String, end: Int, spanEnd: Int, ctxAfter: String, after: String?, complete: Boolean,
  ): DetectResult {
    // 2. Core run: must end on a word character; may contain spaces, apostrophes, hyphens, commas.
    var start = end
    while (start > 0 && isRunChar(before[start - 1])) start--
    // Trim leading non-word run chars (spaces, commas...) back off the core.
    while (start < end && !isWordChar(before[start])) start++
    if (start >= end) return DetectResult.None

    val core = before.substring(start, end)
    if (!core.any(::isLatinLetter)) return DetectResult.None
    if (core.length > MAX_FRAGMENT_CHARS) return DetectResult.TooLong

    // 3. Leading separator whitespace between Chinese and English is part of the replaced span.
    var spanStart = start
    while (spanStart > 0 && before[spanStart - 1].isWhitespace() && before[spanStart - 1] != '\n') spanStart--
    val preceding = before.getOrNull(spanStart - 1)
    if (preceding == null || !isCjk(preceding)) spanStart = start // keep spacing unless it's CJK↔EN glue

    // 4. Boundedness: need Chinese context, or the fragment starts the field / a line.
    val prefix = before.substring(0, start)
    val rest = before.substring(spanEnd)
    val atStart = prefix.isBlank() && complete || prefix.endsWith('\n')
    val hasChinese = prefix.any(::isCjk) || rest.any(::isCjk) || (after ?: "").any(::isCjk)
    if (!atStart && !hasChinese) return DetectResult.Ambiguous

    return DetectResult.Found(
      Detection(
        fragment = core,
        span = before.substring(spanStart, spanEnd),
        tail = rest,
        contextBefore = before.substring(0, spanStart).takeLast(CONTEXT_CHARS),
        contextAfter = (rest + ctxAfter).take(CONTEXT_CHARS),
        fromSelection = false,
      )
    )
  }

  fun isLatinLetter(c: Char): Boolean =
    c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

  private fun isWordChar(c: Char) = isLatinLetter(c) || c in '0'..'9' || c == '\'' || c == '’'

  private fun isRunChar(c: Char) = isWordChar(c) || c == ' ' || c == '-' || c == ','

  private fun isTailChar(c: Char) = c == ' ' || c == '\t' || c in ".!?…"

  /** A Chinese character, not CJK punctuation. */
  fun isCjkIdeograph(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
      block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
      block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
  }

  fun isCjk(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c) ?: return false
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
      block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
      block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
      block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
      block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
  }
}
