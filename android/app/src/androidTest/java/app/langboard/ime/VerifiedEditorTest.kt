package app.langboard.ime

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.langboard.core.DetectResult
import app.langboard.core.EditPlan
import app.langboard.core.FragmentDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real EditText InputConnection behavior: the edits the keyboard makes in host apps. */
@RunWith(AndroidJUnit4::class)
class VerifiedEditorTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  private class Field(val view: EditText, val ic: InputConnection) {
    val editor = VerifiedEditor(ic)
    val text get() = view.text.toString()
    val caret get() = view.selectionStart to view.selectionEnd
  }

  private fun field(text: String, selStart: Int = text.length, selEnd: Int = selStart, block: Field.() -> Unit) {
    instrumentation.runOnMainSync {
      val view = EditText(instrumentation.targetContext)
      view.setText(text)
      view.setSelection(selStart, selEnd)
      Field(view, view.onCreateInputConnection(EditorInfo())!!).block()
    }
  }

  /** Same path as the keyboard: snapshot → detect → plan. */
  private fun Field.plan(replacement: String): EditPlan {
    val s = editor.snapshot(300, 60)
    val r = FragmentDetector.detect(s.before, s.selected, s.after, beforeIsComplete = true)
    return EditPlan.replace((r as DetectResult.Found).detection, replacement)
  }

  @Test fun snapshotAroundCaret() = field("我本来想去 no way 吧", selStart = "我本来想去 no way".length) {
    val s = editor.snapshot(300, 60)
    assertEquals("我本来想去 no way", s.before)
    assertEquals(" 吧", s.after)
    assertTrue(s.selected.isNullOrEmpty())
  }

  @Test fun replacesTrailingPhraseAndLeavesCaretAfterIt() = field("我本来想去但是 I couldn't be bothered anymore") {
    assertTrue(editor.apply(plan("懒得再去了")))
    assertEquals("我本来想去但是懒得再去了", text)
    assertEquals(12 to 12, caret)
  }

  @Test fun textAfterCaretIsUntouched() = field("好吧 never mind，我们走吧", selStart = "好吧 never mind".length) {
    assertTrue(editor.apply(plan("算了")))
    assertEquals("好吧算了，我们走吧", text)
  }

  @Test fun punctuationTailIsPreserved() = field("他又迟到了，I'm so done! ") {
    assertTrue(editor.apply(plan("我受够了")))
    assertEquals("他又迟到了，我受够了! ", text)
  }

  @Test fun emojiNextToDeletionBoundary() = field("哈哈😂 no way😅") {
    // Caret after 😅: the English run is not trailing, so nothing to detect.
    val s = editor.snapshot(300, 60)
    assertEquals(DetectResult.None, FragmentDetector.detect(s.before, s.selected, s.after, true))
  }

  @Test fun emojiBeforeFragmentSurvives() = field("哈哈😂 no way") {
    assertTrue(editor.apply(plan("不会吧")))
    assertEquals("哈哈😂 不会吧", text)
  }

  @Test fun surrogatePairInTail() = field("好 ok😂") {
    // 😂 isn't a tail character, so the fragment isn't trailing.
    val s = editor.snapshot(300, 60)
    assertEquals(DetectResult.None, FragmentDetector.detect(s.before, s.selected, s.after, true))
  }

  @Test fun selectionIsReplacedExactly() = field("我觉得no big deal吧", selStart = 3, selEnd = "我觉得no big deal".length) {
    assertTrue(editor.apply(plan("没什么大不了的")))
    assertEquals("我觉得没什么大不了的吧", text)
  }

  @Test fun undoRestoresOriginal() = field("我本来想去但是 I couldn't be bothered anymore") {
    val plan = plan("懒得再去了")
    assertTrue(editor.apply(plan))
    assertTrue(editor.apply(plan.undo()))
    assertEquals("我本来想去但是 I couldn't be bothered anymore", text)
  }

  @Test fun selectionUndoRestoresOriginal() = field("我觉得no big deal吧", selStart = 3, selEnd = "我觉得no big deal".length) {
    val plan = plan("没什么大不了的")
    assertTrue(editor.apply(plan))
    assertTrue(editor.apply(plan.undo()))
    assertEquals("我觉得no big deal吧", text)
  }

  @Test fun refusesWhenTextChangedAfterPreview() = field("我本来想去但是 no way") {
    val plan = plan("不会吧")
    view.append("!")
    view.setSelection(view.length())
    assertFalse(editor.apply(plan))
    assertEquals("我本来想去但是 no way!", text)
  }

  @Test fun refusesWhenCaretMoved() = field("我本来想去但是 no way") {
    val plan = plan("不会吧")
    view.setSelection(3)
    assertFalse(editor.apply(plan))
    assertEquals("我本来想去但是 no way", text)
  }

  @Test fun refusesWhenSelectionAppeared() = field("我本来想去但是 no way") {
    val plan = plan("不会吧")
    view.setSelection(8, view.length())
    assertFalse(editor.apply(plan))
    assertEquals("我本来想去但是 no way", text)
  }

  @Test fun refusesStaleUndo() = field("我本来想去但是 no way") {
    val plan = plan("不会吧")
    assertTrue(editor.apply(plan))
    view.append("。")
    view.setSelection(view.length())
    assertFalse(editor.apply(plan.undo()))
    assertEquals("我本来想去但是不会吧。", text)
  }

  @Test fun finishesComposingTextFromPreviousKeyboard() = field("我本来想去但是 ") {
    ic.setComposingText("no way", 1)
    assertTrue(editor.apply(plan("不会吧")))
    assertEquals("我本来想去但是不会吧", text)
  }

  @Test fun multilineField() = field("第一行\n第二行 my bad\n第三行", selStart = "第一行\n第二行 my bad".length) {
    assertTrue(editor.apply(plan("我的错")))
    assertEquals("第一行\n第二行我的错\n第三行", text)
  }

  @Test fun longTextOnlyReadsABoundedWindow() = field("很".repeat(1000) + " no way") {
    val s = editor.snapshot(300, 60)
    assertEquals(300, s.before!!.length)
    assertTrue(editor.apply(plan("不会吧")))
    assertEquals("很".repeat(1000) + "不会吧", text)
  }
}
