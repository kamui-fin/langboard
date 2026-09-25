package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtPromptsTest {
  private fun req(before: String, fragment: String, after: String = "", screen: ScreenText = ScreenText.EMPTY, register: Register = Register.Casual) =
    FillGapRequest(before, fragment, after, register = register, screen = screen)

  // Outputs below are what Hy-MT2 actually returned for these requests.

  @Test fun keepsAPlainAnswer() {
    assertEquals("迟到了", HyMtPrompts.cleanFill("迟到了", req("我今天", "running late", "，你们先吃")))
  }

  @Test fun cutsTheWholeSentenceDownToTheGap() {
    assertEquals("演唱会", HyMtPrompts.cleanFill("周末一起去看演唱会吗？", req("周末一起去看", "concert", "吗？")))
  }

  @Test fun dropsCharactersRepeatedFromEitherSide() {
    assertEquals("疯狂", HyMtPrompts.cleanFill("太疯狂了", req("这个事情太", "insane", "了")))
  }

  @Test fun keepsAtLeastOneCharacter() {
    assertEquals("了", HyMtPrompts.cleanFill("了", req("太", "done", "了")))
  }

  @Test fun takesTheFirstLineAndStripsQuotes() {
    assertEquals("懒得去了", HyMtPrompts.cleanFill("“懒得去了”\n\nNote: casual", req("我本来想去但是 ", "I couldn't be bothered anymore")))
  }

  @Test fun dropsAFullStopMidSentence() {
    assertEquals("无法出席", HyMtPrompts.cleanFill("无法出席。", req("关于明天的会议，我", "might not make it", "，非常抱歉。")))
  }

  @Test fun rejectsAnswersWithoutChinese() {
    assertNull(HyMtPrompts.cleanFill("no way", req("哈哈哈", "no way")))
    assertNull(HyMtPrompts.cleanFill("  \n", req("哈哈哈", "no way")))
  }

  @Test fun dropsALineCopiedFromTheChat() {
    val chat = ScreenText(listOf(ScreenLine("周杰伦下个月来上海开演唱会！！", ScreenLine.Align.Start)))
    assertNull(HyMtPrompts.cleanFill("周杰伦下个月来上海开演唱会！！", req("周末一起去看", "concert", "吗？", screen = chat)))
    assertEquals("演唱会", HyMtPrompts.cleanFill("演唱会", req("周末一起去看", "concert", "吗？", screen = chat)))
  }

  @Test fun dropsTheSentenceEchoedBack() {
    assertNull(HyMtPrompts.cleanFill("他又迟到了", req("他又迟到了，", "ngmi")))
    assertNull(HyMtPrompts.cleanFill("你们先吃", req("我今天", "running late", "，你们先吃")))
    assertEquals("累", HyMtPrompts.cleanFill("累", req("我真的很累，太", "tired")))
  }

  @Test fun dropsEnglishCopiedFromThePrompt() {
    assertNull(HyMtPrompts.cleanFill("passive-aggressive”可以翻译为“消极攻击性的”", req("你这个人真的很", "passive-aggressive")))
  }

  @Test fun gapAnswerStopsWhereTheTextAfterTheGapStarts() {
    assertEquals("取", HyMtPrompts.gapAnswer("取一下快递吗", req("你能帮我", "pick up", "一下快递吗")))
    // Cut off before the whole of it was written.
    assertEquals("取", HyMtPrompts.gapAnswer("取一下快", req("你能帮我", "pick up", "一下快递吗")))
    // The last place it fits: 受不了 keeps its own 了.
    assertEquals("受不了", HyMtPrompts.gapAnswer("受不了了。", req("我真的", "can't", "了")))
    assertEquals("疯狂", HyMtPrompts.gapAnswer("疯狂了", req("这个事情太", "insane", "了")))
  }

  @Test fun gapAnswerWithNothingAfterEndsAtPunctuation() {
    assertEquals("懒得去了", HyMtPrompts.gapAnswer("懒得去了，下次吧", req("我本来想去但是", "I couldn't be bothered")))
    assertEquals("迟到了", HyMtPrompts.gapAnswer("迟到了，", req("我今天", "running late", "，你们先吃")))
  }

  @Test fun gapAnswerIsNullWhenTheModelSkipsTheGap() {
    assertNull(HyMtPrompts.gapAnswer("吧", req("我们一起去", "hiking", "吧")))
    assertNull(HyMtPrompts.gapAnswer("hiking吧", req("我们一起去", "hiking", "吧")))
  }

  @Test fun leadsOnWhenWhatFollowsTheAnswerIsTheTextAfterTheGap() {
    assertTrue(HyMtPrompts.leadsOn("受不了了。", "受不了", "了"))
    assertTrue(HyMtPrompts.leadsOn("取一下快", "取", "一下快递吗"))
    assertTrue(HyMtPrompts.leadsOn("懒得去了。", "懒得去了", ""))
    assertFalse(HyMtPrompts.leadsOn("吃完饭吧", "吃完饭", "了吧，我们走"))
    assertFalse(HyMtPrompts.leadsOn("旱冰鞋，然后", "旱冰鞋", ""))
  }

  @Test fun stopsAndTheTextAnswersAreScoredWith() {
    assertEquals("一下", HyMtPrompts.stopsFor("一下快递吗").second)
    assertEquals("", HyMtPrompts.stopsFor("了").second)
    assertEquals("", HyMtPrompts.stopsFor("，你们先吃").second)
    assertEquals("，你们先吃", HyMtPrompts.afterHead("，你们先吃", 6))
    assertEquals("，非常抱歉。", HyMtPrompts.afterHead("，非常抱歉。我会", 8))
    assertEquals("一下这个问", HyMtPrompts.afterHead(" 一下这个问题", 5))
  }

  @Test fun cleanFillsKeepsOrderAndDropsRepeats() {
    val r = req("我今天", "running late", "，你们先吃")
    assertEquals(listOf("迟到了", "晚点到"), HyMtPrompts.cleanFills(listOf("迟到了", "迟到了。", "晚点到", "late"), r, 10))
  }

  @Test fun checkIgnoresPunctuationAndParticlesUnlessPicky() {
    assertNull(HyMtPrompts.cleanCheck("你吃饭了吗？", "你吃饭了吗", ScreenText.EMPTY, picky = false))
    assertNull(HyMtPrompts.cleanCheck("我好累啊", "我好累", ScreenText.EMPTY, picky = false))
    assertEquals("我好累啊", HyMtPrompts.cleanCheck("我好累啊", "我好累", ScreenText.EMPTY, picky = true))
  }

  @Test fun checkKeepsTheUsersOwnEnding() {
    assertEquals("我昨天看了一部电影", HyMtPrompts.cleanCheck("我昨天看了一部电影。", "我昨天看一本电影", ScreenText.EMPTY, picky = false))
    assertEquals("我好累啊！", HyMtPrompts.cleanCheck("我好累啊", "我是很累！", ScreenText.EMPTY, picky = false))
  }
}
