package app.langboard.core

/**
 * Naturalize: flag Chinese that is understandable but unnatural, and propose tiny patches instead of
 * rewriting the sentence. The user applies all of them or one at a time.
 */
enum class AssistMode {
  /** Only help when English is mixed into Chinese. */
  STUCK,
  /** Also suggest a more natural way to write Chinese drafts. The default. */
  COACH,
  /** Suggest even when the only difference is a particle (了, 啊, 吧…). */
  NATIVE,
}

/**
 * A more natural way to write [original]: [natural] is the model's best, [alternatives] the next
 * ones. [changes] are the ranges of [natural] that differ from what the user wrote.
 */
data class NaturalizeResult(val original: String, val natural: String, val alternatives: List<String> = emptyList()) {
  val changes: List<IntRange> get() = TextDiff.inserted(original, natural)
}

interface NaturalizeEngine {
  /** Returns null when the sentence already reads naturally at this [mode]. Must be cancellable. */
  suspend fun review(sentence: String, mode: AssistMode, register: Register, screen: ScreenText): NaturalizeResult?
}

/** The Chinese sentence just before the caret: the text before it ends with `sentence + tail`. */
data class SentenceSpan(val sentence: String, val tail: String) {
  val full: String get() = sentence + tail
}

object SentenceFinder {
  const val MAX_SENTENCE_CHARS = 80
  private const val END = "。！？!?\n"
  private const val TAIL = "。！？!?.…~～ \t"

  /**
   * The last sentence before the caret, when it is Chinese without Latin letters (those go to Fill
   * Gap). [beforeIsComplete] says whether [before] reaches the start of the field.
   */
  fun find(before: String?, beforeIsComplete: Boolean): SentenceSpan? {
    if (before.isNullOrEmpty()) return null
    var end = before.length
    while (end > 0 && before[end - 1] in TAIL) end--
    var start = end
    while (start > 0 && before[start - 1] !in END) start--
    if (start == 0 && !beforeIsComplete) return null
    while (start < end && before[start].isWhitespace()) start++
    val sentence = before.substring(start, end)
    if (sentence.isEmpty() || sentence.length > MAX_SENTENCE_CHARS) return null
    if (sentence.any(FragmentDetector::isLatinLetter)) return null
    if (sentence.count(FragmentDetector::isCjk) < 2) return null
    return SentenceSpan(sentence, before.substring(end))
  }
}
