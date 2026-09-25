package app.langboard.core

import app.langboard.core.ScreenLine.Align
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
  private fun screen(vararg lines: Pair<String, Align>) = ScreenText(lines.map { (t, a) -> ScreenLine(t, a) })

  @Test fun latestIncomingIsTheLastStartAlignedChineseLine() {
    val s = screen(
      "小王" to Align.Wide,
      "你不是说今天要来吗" to Align.Start,
      "我们等你半天了 😭" to Align.Start,
      "我在路上" to Align.End,
      "10:42" to Align.Start,
    )
    assertEquals("我们等你半天了 😭", Conversation.latestIncoming(s))
  }

  @Test fun withoutLayoutFallsBackToTheLastChineseLine() {
    val s = screen("聊天" to Align.Wide, "你别在那阴阳怪气的" to Align.Wide, "OK" to Align.Wide)
    assertEquals("你别在那阴阳怪气的", Conversation.latestIncoming(s))
  }

  @Test fun sentMessagesAreNeverTheLatestIncoming() {
    assertNull(Conversation.latestIncoming(screen("我到了" to Align.End)))
  }

  @Test fun timestampsAndSingleCharactersAreSkipped() {
    val s = screen("下午 3:15" to Align.Start, "好" to Align.Start, "昨天 10:02" to Align.Wide)
    assertNull(Conversation.latestIncoming(s))
  }

  @Test fun recentMessagesAreChatBubblesOnly() {
    val s = screen("标题" to Align.Wide, "哈哈" to Align.Start, "绝了" to Align.End, "12:00" to Align.Start)
    assertEquals(listOf("哈哈", "绝了"), Conversation.recentMessages(s).map { it.text })
  }

  @Test fun slangAndEmojiReadAsCasual() {
    val g = RegisterDetector.infer(listOf("哈哈哈哈", "我靠", "绝了", "你干嘛呢", "😭😭"))
    assertEquals(Register.Casual, g.register)
    assertTrue(g.cues.isNotEmpty())
  }

  @Test fun politeFormsReadAsFormal() {
    assertEquals(Register.Formal, RegisterDetector.infer(listOf("老师您好", "请问您明天下午有时间吗")).register)
  }

  @Test fun plainTextIsNeutral() {
    val g = RegisterDetector.infer(listOf("明天见", "我六点下班"))
    assertEquals(Register.Neutral, g.register)
    assertTrue(g.cues.isEmpty())
  }

  @Test fun noLinesIsNeutral() {
    assertEquals(Register.Neutral, RegisterDetector.infer(emptyList()).register)
  }
}
