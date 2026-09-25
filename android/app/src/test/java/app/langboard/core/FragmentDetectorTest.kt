package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmentDetectorTest {

  private fun found(before: String, selected: String? = null, after: String = "", complete: Boolean = true): Detection {
    val r = FragmentDetector.detect(before, selected, after, complete)
    assertTrue("expected Found, got $r", r is DetectResult.Found)
    return (r as DetectResult.Found).detection
  }

  private fun apply(before: String, d: Detection, replacement: String): String {
    val plan = EditPlan.replace(d, replacement)
    assertTrue(plan.matches(before, null))
    return before.dropLast(plan.expectBeforeSuffix!!.length) + plan.commit
  }

  @Test fun canonicalExample() {
    val before = "我本来想去但是 I couldn't be bothered anymore"
    val d = found(before)
    assertEquals("I couldn't be bothered anymore", d.fragment)
    assertEquals(" I couldn't be bothered anymore", d.span)
    assertEquals("我本来想去但是", d.contextBefore)
    assertEquals("我本来想去但是懒得再去了", apply(before, d, "懒得再去了"))
  }

  @Test fun noSpaceBetweenChineseAndEnglish() {
    val before = "我本来想去但是I couldn't be bothered"
    assertEquals("我本来想去但是懒得再去了", apply(before, found(before), "懒得再去了"))
  }

  @Test fun trailingPunctuationAndSpaceArePreserved() {
    val before = "他又迟到了，I'm so done! "
    val d = found(before)
    assertEquals("I'm so done", d.fragment)
    assertEquals("! ", d.tail)
    assertEquals("他又迟到了，我受够了! ", apply(before, d, "我受够了"))
  }

  @Test fun chinesePunctuationBeforeFragmentIsKept() {
    val before = "吃什么都行，up to you"
    assertEquals("吃什么都行，你决定吧", apply(before, found(before), "你决定吧"))
  }

  @Test fun curlyApostrophe() {
    assertEquals("I couldn’t be bothered", found("但是 I couldn’t be bothered").fragment)
  }

  @Test fun emojiBeforeFragmentKeepsSpacing() {
    val before = "哈哈😂 no way"
    val d = found(before)
    assertEquals("no way", d.span)
    assertEquals("哈哈😂 不会吧", apply(before, d, "不会吧"))
  }

  @Test fun sentenceBoundaryStopsTheRun() {
    assertEquals("never mind", found("好吧. never mind").fragment)
  }

  @Test fun fragmentAtStartOfField() {
    assertEquals("never mind", found("never mind").fragment)
  }

  @Test fun multilineStartsAtNewline() {
    assertEquals("no way", found("Hello there\nno way", complete = false).fragment)
  }

  @Test fun chineseAfterCaretCountsAsContext() {
    assertEquals("Well, my bad", found("Well, my bad", after = "，下次注意", complete = false).fragment)
  }

  @Test fun pureEnglishMidTextIsAmbiguous() {
    assertEquals(DetectResult.Ambiguous, FragmentDetector.detect("Hello. I couldn't be bothered", null, "", true))
  }

  @Test fun truncatedWindowWithoutChineseIsAmbiguous() {
    assertEquals(DetectResult.Ambiguous, FragmentDetector.detect("never mind", null, "", false))
  }

  @Test fun nothingEnglish() {
    assertEquals(DetectResult.None, FragmentDetector.detect("我本来想去", null, "", true))
    assertEquals(DetectResult.None, FragmentDetector.detect("", null, "", true))
    assertEquals(DetectResult.None, FragmentDetector.detect(null, null, null, true))
    assertEquals(DetectResult.None, FragmentDetector.detect("我有 123", null, "", true))
  }

  @Test fun tooLong() {
    val long = "我 " + "word ".repeat(40) + "end"
    assertEquals(DetectResult.TooLong, FragmentDetector.detect(long, null, "", true))
  }

  @Test fun selectionWins() {
    val d = found("我觉得", selected = "no big deal", after = "吧")
    assertTrue(d.fromSelection)
    val plan = EditPlan.replace(d, "没什么大不了的")
    assertTrue(plan.matches("我觉得", "no big deal"))
    assertFalse(plan.matches("我觉得", "no big"))
    assertEquals(0, plan.deleteBeforeCodePoints)
  }

  @Test fun selectionWithoutLatinIsIgnored() {
    assertEquals(DetectResult.None, FragmentDetector.detect("", "你好", "", true))
  }

  @Test fun hyphensDigitsAndCommasStayInsideTheSpan() {
    assertEquals("a last-minute thing, 2 times", found("这是 a last-minute thing, 2 times").fragment)
  }

  @Test fun ellipsisAndQuestionMarkAreTail() {
    val d = found("你说 no way…? ")
    assertEquals("no way", d.fragment)
    assertEquals("…? ", d.tail)
  }

  @Test fun fullWidthPunctuationAfterEnglishIsKept() {
    val before = "我觉得 overkill，"
    val d = found(before)
    assertEquals("overkill", d.fragment)
    assertEquals("，", d.tail)
    assertEquals("我觉得没必要，", apply(before, d, "没必要"))
  }

  @Test fun chineseResumedAfterEnglish() {
    val before = "我觉得这个有点 overkill 而且"
    val d = found(before)
    assertEquals("overkill", d.fragment)
    assertEquals(" overkill ", d.span)
    assertEquals("而且", d.tail)
    assertEquals("我觉得这个有点", d.contextBefore)
    assertTrue(d.contextAfter.startsWith("而且"))
    assertEquals("我觉得这个有点没必要而且", apply(before, d, "没必要"))
  }

  @Test fun chineseResumedWithoutSpaces() {
    val before = "我昨天去了那个concert，超级好玩"
    assertEquals("我昨天去了那个演唱会，超级好玩", apply(before, found(before), "演唱会"))
  }

  @Test fun chineseResumedWithTrailingFullStop() {
    val before = "有点 overkill 吧。"
    assertEquals("有点没必要吧。", apply(before, found(before), "没必要"))
  }

  @Test fun resumedStopsAtSentenceEnd() {
    assertEquals(DetectResult.None, FragmentDetector.detect("我觉得 overkill。然后我们走了", null, "", true))
  }

  @Test fun resumedStopsWhenTheEnglishIsFarBack() {
    val before = "我觉得 overkill 而且" + "很".repeat(FragmentDetector.MAX_RESUMED_CHARS)
    assertEquals(DetectResult.None, FragmentDetector.detect(before, null, "", true))
  }

  @Test fun trailingEnglishWinsOverEarlierEnglish() {
    assertEquals("no way", found("我觉得 overkill 而且 no way").fragment)
  }

  @Test fun maxLengthBoundary() {
    val ok = "x".repeat(FragmentDetector.MAX_FRAGMENT_CHARS)
    assertEquals(ok, found("我 $ok").fragment)
    assertEquals(DetectResult.TooLong, FragmentDetector.detect("我 ${ok}x", null, "", true))
  }

  @Test fun selectionTooLong() {
    val long = "a".repeat(FragmentDetector.MAX_FRAGMENT_CHARS + 1)
    assertEquals(DetectResult.TooLong, FragmentDetector.detect("我", long, "", true))
  }

  @Test fun contextIsBoundedToRoughlyASentence() {
    val before = "很".repeat(200) + " no way"
    val d = found(before)
    assertEquals(FragmentDetector.CONTEXT_CHARS, d.contextBefore.length)
    assertEquals(FragmentDetector.CONTEXT_CHARS, found("好 no way", after = "啊".repeat(200)).contextAfter.length)
  }

  @Test fun accentedLatinCounts() {
    assertEquals("déjà vu", found("有点 déjà vu").fragment)
  }

  @Test fun onlyTailIsNothing() {
    assertEquals(DetectResult.None, FragmentDetector.detect("我... ", null, "", true))
  }

  @Test fun cjkClassification() {
    assertTrue(FragmentDetector.isCjk('中'))
    assertTrue(FragmentDetector.isCjk('，'))
    assertTrue(FragmentDetector.isCjk('。'))
    assertFalse(FragmentDetector.isCjk('a'))
    assertFalse(FragmentDetector.isLatinLetter('中'))
    assertTrue(FragmentDetector.isLatinLetter('é'))
  }
}
