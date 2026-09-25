package app.langboard.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renders the prompts the app ships (src/main/assets/prompts.json). */
class PromptBookTest {
  private val book = PromptBook.parse(File("src/main/assets/prompts.json").readText())

  private val chat = ScreenText(
    listOf(
      ScreenLine("小李", ScreenLine.Align.Wide),
      ScreenLine("到哪了？菜都上齐了", ScreenLine.Align.Start),
      ScreenLine("马上", ScreenLine.Align.End),
    )
  )

  @Test fun fillAsksForTheSentenceAndStartsTheAnswerWithTheChineseBeforeTheGap() {
    val p = book.fill(FillGapRequest("我今天 ", "running late", "，你们先吃", register = Register.Formal, screen = chat))
    assertTrue(p, p.contains("聊天记录：\n小李\n对方：到哪了？菜都上齐了\n我：马上"))
    assertTrue(p.contains("【礼貌、正式的工作沟通】"))
    assertTrue(p, p.endsWith("【待翻译文本】\n我今天 running late，你们先吃<｜hy_Assistant｜>我今天"))
    assertTrue(p.startsWith("<｜hy_begin▁of▁sentence｜><｜hy_User｜>"))
  }

  @Test fun fillWithoutChatIsAPlainTranslation() {
    val p = book.fill(FillGapRequest("我今天", "running late", "，你们先吃"))
    assertFalse(p.contains("聊天记录"))
    assertTrue(p, p.endsWith("\n\n我今天running late，你们先吃<｜hy_Assistant｜>我今天"))
  }

  @Test fun explainWithoutChatIsPlainTranslation() {
    assertFalse(book.explain("你在干嘛", ScreenText.EMPTY).contains("Background"))
    assertTrue(book.explain("你在干嘛", chat).contains("Them: 到哪了？菜都上齐了\nMe: 马上"))
  }

  @Test fun checkCarriesTheStyle() {
    assertTrue(book.check("我是很累", Register.Casual, ScreenText.EMPTY).contains("【口语化、随意的朋友聊天】"))
  }

  @Test fun recentKeepsWholeLinesNearestTheField() {
    val screen = ScreenText((1..100).map { ScreenLine("第${it}条消息", ScreenLine.Align.Start) })
    val r = PromptBook.recent(screen, mapOf(ScreenLine.Align.Start to "对方："), 40)
    assertTrue(r.endsWith("对方：第100条消息"))
    assertTrue(r.length <= 40)
    assertEquals(0, r.lines().count { !it.startsWith("对方：") })
  }
}
