package app.langboard.ime

import app.langboard.core.Candidate
import app.langboard.core.Conversation
import app.langboard.core.Detection
import app.langboard.core.ExplainResult
import app.langboard.core.FillGapResult
import app.langboard.core.NaturalizeResult
import app.langboard.core.Register
import app.langboard.core.RegisterDetector
import app.langboard.core.RegisterGuess
import app.langboard.core.ScreenText
import app.langboard.core.SentenceSpan
import app.langboard.core.Term

/**
 * What the panel shows. Switching to Langboard is the request, and the draft decides the job:
 * English in it is filled in, a Chinese draft is checked, and an empty draft explains the latest
 * message someone sent.
 */
sealed interface ImeState {
  /** Nothing to say. [checked] is true when a Chinese sentence was reviewed and reads naturally. */
  data class Idle(val checked: Boolean) : ImeState
  data class Working(val detection: Detection) : ImeState
  data class Suggestions(
    val detection: Detection,
    val result: FillGapResult,
    val context: ChatContext = ChatContext.NONE,
    /** Exactly what the model was given, shown under Context so it can be checked. */
    val prompt: String? = null,
  ) : ImeState
  /** No answer. [modelMissing]: the Chinese model isn't installed, so only the dictionary can help. */
  data class NoMatch(val detection: Detection, val modelMissing: Boolean = false) : ImeState
  /** A Chinese draft being checked by the model. */
  data class Checking(val span: SentenceSpan) : ImeState
  data class Unnatural(val span: SentenceSpan, val result: NaturalizeResult) : ImeState
  /** The latest received message, explained. [result] is null while it's being worked out. */
  data class Explain(val message: String, val result: ExplainResult?, val context: ChatContext) : ImeState
  data class Inserted(val text: String) : ImeState
  /** English is present but not clearly bounded, or too long. */
  data class NeedsSelection(val tooLong: Boolean) : ImeState
  /** Host returned no readable context. */
  data object Unreadable : ImeState
  data class UnsupportedField(val sensitive: Boolean) : ImeState
  data object ChangedText : ImeState
  data object Error : ImeState
}

/** What was read from the screen for an answer, and what Langboard made of it. In memory only. */
data class ChatContext(val screen: ScreenText, val register: RegisterGuess, val messages: Int) {
  val used: Boolean get() = !screen.isEmpty

  companion object {
    val NONE = ChatContext(ScreenText.EMPTY, RegisterGuess(Register.Neutral, emptyList()), 0)

    fun of(screen: ScreenText): ChatContext {
      if (screen.isEmpty) return NONE
      val recent = Conversation.recentMessages(screen)
      // Apps that expose no layout still have text; judge register from the last few lines of it.
      val sample = recent.map { it.text }.ifEmpty { screen.lines.takeLast(Conversation.RECENT_LINES).map { it.text } }
      return ChatContext(screen, RegisterDetector.infer(sample), recent.size)
    }
  }
}

/** Views that expand the panel above its compact size. */
sealed interface ImePanel {
  /** The options with room to breathe, and what was read from the screen. */
  data object Details : ImePanel
  /** Every way to write the Chinese draft more naturally. */
  data object Naturalize : ImePanel
  /** Each English word of the phrase in the dictionary. */
  data object Words : ImePanel
  /** The received message, word by word. */
  data object Message : ImePanel
  /** One Chinese expression in depth. Back returns to [parent], or to the main panel. */
  data class TermDetail(val term: Term, val parent: ImePanel?) : ImePanel
}

/**
 * The instant dictionary row for the detected phrase, filled in parallel with the model's answer.
 * [chips] is empty when the dictionary has nothing it can be trusted to say; the row is then hidden.
 */
data class QuickRow(val phrase: String, val chips: List<Candidate>?, val hasWords: Boolean) {
  val loading get() = chips == null
}

/** "Each word ›": each content word of the phrase on its own, for the user to combine themselves. */
data class WordsState(val phrase: String, val rows: List<Pair<String, List<Candidate>>>?)

sealed interface ImeAction {
  data class Insert(val candidate: Candidate) : ImeAction
  /** Replace the checked sentence with [text], the suggestion or one of its alternatives. */
  data class ReplaceSentence(val text: String) : ImeAction
  data object Undo : ImeAction
  data object Retry : ImeAction
  data object SwitchKeyboard : ImeAction
  /** Open the Langboard app, e.g. to download the Chinese model. */
  data object OpenApp : ImeAction
  data class OpenPanel(val panel: ImePanel) : ImeAction
  /** Collapse, or go back one level from a term. */
  data object ClosePanel : ImeAction
  data class Copy(val candidate: Candidate) : ImeAction
  /** Save or unsave what's on the main panel; [answer] is the option picked, when it isn't the first. */
  data class ToggleSave(val answer: String? = null) : ImeAction
  /** Save or unsave one expression from a message. */
  data class ToggleSaveTerm(val term: Term) : ImeAction
}
