package app.langboard.core

/**
 * Shared Fill Gap contract (spec §5). `before`/`after` are in the target language,
 * `fragment` is in the source language. The generation target is only the replacement.
 */
data class FillGapRequest(
  val before: String,
  val fragment: String,
  val after: String,
  val task: String = "fill",
  val sourceLocale: String = "en-US",
  val targetLocale: String = "zh-Hans-CN",
  /** Inferred from the conversation on screen; see [RegisterDetector]. */
  val register: Register = Register.Neutral,
  /** The text on screen around the field, line by line with who sent it, when the user allows it. In memory only. */
  val screen: ScreenText = ScreenText.EMPTY,
  /** What the user told Langboard about how they want to sound, and a few of their own sentences. */
  val personal: PersonalStyle = PersonalStyle.NONE,
)

data class FillGapResult(
  val replacement: String,
  val modelVersion: String,
  /** Display-only reading aid; not part of the model contract. */
  val pinyin: String? = null,
  /** Plain-English meaning of [replacement], shown on press-and-hold. Display-only. */
  val meaning: String? = null,
  /** The model's next most likely answers, best first. */
  val alternatives: List<Candidate> = emptyList(),
  /** Key words in the answer with their meaning, e.g. 懒得 = can't be bothered to put in effort. */
  val notes: List<Candidate> = emptyList(),
  /** [replacement]'s share of the probability of the options shown; null until they're all known. */
  val share: Float? = null,
  /** The first answer is in and the other options are still being worked out. */
  val more: Boolean = false,
) {
  /** The main answer followed by the alternatives: every option, best first. */
  val candidates: List<Candidate>
    get() = listOf(Candidate(replacement, pinyin, meaning, share = share)) + alternatives
}

/** One insertable phrasing. [nuance] explains how it differs from the main answer. */
data class Candidate(
  val text: String,
  val pinyin: String?,
  val meaning: String? = null,
  val nuance: String? = null,
  /** Share of the model's probability among the options shown, 0–1, when it comes from the model. */
  val share: Float? = null,
)

interface FillGapEngine {
  /** Returns null when the engine has no confident answer. Must be cancellable. */
  suspend fun fill(request: FillGapRequest): FillGapResult?
}
