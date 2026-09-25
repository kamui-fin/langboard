package app.langboard.core

/**
 * One Chinese word or expression explained. [meaning] is the dictionary sense; [here] is what it
 * means in this message when that differs; [note] warns about the literal reading ("Not “cow” here").
 */
data class Term(
  val text: String,
  val pinyin: String?,
  val meaning: String?,
  val here: String? = null,
  val note: String? = null,
  /** Register or usage, e.g. "very colloquial", "internet slang". */
  val tag: String? = null,
)

/**
 * A received message, understood. [terms] are the expressions worth explaining, most useful first;
 * [words] is the whole message split into words for the breakdown.
 */
data class ExplainResult(
  val message: String,
  val translation: String?,
  val terms: List<Term>,
  val words: List<Term> = emptyList(),
) {
  /** The one expression the main panel leads with: the first term, else the longest dictionary word. */
  val key: Term?
    get() = terms.firstOrNull()
      ?: words.filter { it.meaning != null && it.text.length >= 2 }.maxByOrNull { it.text.length }
}

interface ExplainEngine {
  /** [screen] is the conversation around the message, for meaning in context. Must be cancellable. */
  suspend fun explain(message: String, screen: ScreenText): ExplainResult?
}
