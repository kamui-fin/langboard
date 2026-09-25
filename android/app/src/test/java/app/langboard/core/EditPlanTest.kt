package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditPlanTest {

  private val detection = (FragmentDetector.detect("我😂但是 no way", null, "", true) as DetectResult.Found).detection

  @Test fun deletesExactlyTheVerifiedSuffixInCodePoints() {
    val plan = EditPlan.replace(detection, "不会吧")
    assertEquals(" no way", plan.expectBeforeSuffix)
    assertEquals(7, plan.deleteBeforeCodePoints)
    assertEquals("不会吧", plan.commit)
  }

  @Test fun refusesWhenTextChanged() {
    val plan = EditPlan.replace(detection, "不会吧")
    assertFalse(plan.matches("我😂但是 no wa", null))
    assertFalse(plan.matches(null, null))
    assertFalse("selection present", plan.matches("我😂但是 no way", "way"))
    assertTrue(plan.matches("我😂但是 no way", ""))
  }

  @Test fun undoRestoresOriginal() {
    val plan = EditPlan.replace(detection, "不会吧")
    val after = "我😂但是" + plan.commit
    val undo = plan.undo()
    assertTrue(undo.matches(after, null))
    assertEquals(3, undo.deleteBeforeCodePoints)
    assertEquals("我😂但是 no way", after.dropLast(undo.expectBeforeSuffix!!.length) + undo.commit)
  }

  @Test fun surrogatePairsCountAsOneCodePoint() {
    val d = Detection("ok", "ok", "😂", "", "", fromSelection = false)
    val plan = EditPlan.replace(d, "好")
    assertEquals(3, plan.deleteBeforeCodePoints)
    assertEquals("好😂", plan.commit)
    assertEquals(2, plan.undo().deleteBeforeCodePoints)
  }

  @Test fun applyToTrailing() {
    val plan = EditPlan.replace(detection, "不会吧")
    assertEquals("我😂但是不会吧。", plan.applyTo("我😂但是 no way", "", "。"))
    assertEquals(null, plan.applyTo("我😂但是 no", "", ""))
  }

  @Test fun applyToSelection() {
    val d = (FragmentDetector.detect("我觉得", "no big deal", "吧", true) as DetectResult.Found).detection
    val plan = EditPlan.replace(d, "没什么大不了的")
    assertEquals("我觉得没什么大不了的吧", plan.applyTo("我觉得", "no big deal", "吧"))
    assertEquals(null, plan.applyTo("我觉得", "", "no big deal吧"))
  }

  @Test fun selectionUndoRestoresSelectedText() {
    val d = (FragmentDetector.detect("我觉得", "no big deal", "吧", true) as DetectResult.Found).detection
    val undo = EditPlan.replace(d, "没什么大不了的").undo()
    assertEquals("没什么大不了的", undo.expectBeforeSuffix)
    assertEquals(7, undo.deleteBeforeCodePoints)
    assertEquals("no big deal", undo.commit)
    assertEquals("我觉得no big deal吧", undo.applyTo("我觉得没什么大不了的", "", "吧"))
  }

  @Test fun undoOfUndoIsTheOriginalReplace() {
    val plan = EditPlan.replace(detection, "不会吧")
    val redo = plan.undo().undo()
    assertEquals(plan.expectBeforeSuffix, redo.expectBeforeSuffix)
    assertEquals(plan.commit, redo.commit)
    assertEquals(plan.deleteBeforeCodePoints, redo.deleteBeforeCodePoints)
  }

  @Test fun multilineContextUntouched() {
    val before = "第一行\n第二行 my bad"
    val d = (FragmentDetector.detect(before, null, "\n第三行", true) as DetectResult.Found).detection
    assertEquals("第一行\n第二行我的错\n第三行", EditPlan.replace(d, "我的错").applyTo(before, "", "\n第三行"))
  }
}
