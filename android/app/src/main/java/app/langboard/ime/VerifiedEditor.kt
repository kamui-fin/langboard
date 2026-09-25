package app.langboard.ime

import android.view.inputmethod.InputConnection
import app.langboard.core.EditPlan

/** What the host editor exposes around the caret; any part may be null when the host won't say. */
data class FieldSnapshot(val before: String?, val selected: String?, val after: String?)

/**
 * The only way Langboard touches host text: re-read, check the plan still matches, then delete and
 * commit inside one batch edit. Deletion is by code points; the UTF-16 fallback is still exact
 * because the whole suffix was verified first.
 */
class VerifiedEditor(private val ic: InputConnection) {

  fun snapshot(beforeChars: Int, afterChars: Int) = FieldSnapshot(
    before = ic.getTextBeforeCursor(beforeChars, 0)?.toString(),
    selected = ic.getSelectedText(0)?.toString(),
    after = ic.getTextAfterCursor(afterChars, 0)?.toString(),
  )

  /** Returns false, having changed nothing, when the field no longer matches [plan]. */
  fun apply(plan: EditPlan): Boolean {
    ic.finishComposingText()
    val need = (plan.expectBeforeSuffix?.length ?: 0) + 1
    val before = ic.getTextBeforeCursor(need, 0)
    val selected = ic.getSelectedText(0)
    if (!plan.matches(before, selected)) return false

    ic.beginBatchEdit()
    try {
      if (plan.deleteBeforeCodePoints > 0 && !ic.deleteSurroundingTextInCodePoints(plan.deleteBeforeCodePoints, 0)) {
        ic.deleteSurroundingText(plan.expectBeforeSuffix!!.length, 0)
      }
      ic.commitText(plan.commit, 1)
    } finally {
      ic.endBatchEdit()
    }
    return true
  }
}
