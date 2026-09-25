package app.langboard.core

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEnginesTest {
  private val book = PromptBook.parse(File("src/main/assets/prompts.json").readText())

  /**
   * [beams] are (text, probability); [reply] is the greedy output, by default the likeliest beam.
   * [scores] are log-probabilities by continuation (answer and what follows it); others get -10.
   */
  private class Fake(
    reply: String? = null,
    private val beams: List<Pair<String, Double>>? = null,
    private val replyP: Double = 0.1,
    private val scores: Map<String, Double> = emptyMap(),
  ) : TextModel {
    private val reply = reply ?: beams?.firstOrNull()?.first
    var prompt: String? = null
    var stoppedAt: String? = null
    var banned = false
    var search: Beams? = null
    var scored: List<String>? = null
    override suspend fun complete(prompt: String, maxTokens: Int, repeatPenalty: Float, banLatin: Boolean, onText: (String) -> Boolean): Scored? {
      this.prompt = prompt
      banned = banLatin
      val reply = reply ?: return null
      // Stream a character at a time, as the model does, and honor the stop.
      for (i in 1..reply.length) if (!onText(reply.take(i))) return Scored(reply.take(i), Math.log(replyP)).also { stoppedAt = it.text }
      return Scored(reply, Math.log(replyP))
    }

    override suspend fun beams(prompt: String, search: Beams): List<Scored>? {
      this.prompt = prompt
      this.search = search
      return this.beams?.map { (t, p) -> Scored(t, Math.log(p)) }
    }

    override suspend fun score(prompt: String, continuations: List<String>): List<Double>? {
      if (reply == null && beams == null) return null
      scored = continuations
      return continuations.map { scores[it] ?: -10.0 }
    }
  }

  private fun fake(vararg beams: String) = Fake(beams = beams.mapIndexed { i, t -> t to 0.5 / (i + 1) })

  private fun engine(m: TextModel, book: PromptBook = this.book) = ModelFillGapEngine(m, "test") { book }

  /** The shipped prompts with the scoring pass on. */
  private val rescoring = PromptBook.parse(File("src/main/assets/prompts.json").readText().replace(Regex("\"rescore\": \\d+"), "\"rescore\": 10"))

  @Test fun fillContinuesTheSentenceFromTheChineseBeforeTheGap() = runBlocking {
    val m = Fake(reply = "玩轮滑", beams = listOf("玩轮滑" to 0.2, "玩旱冰。" to 0.1, "roller skate" to 0.1))
    val r = engine(m).fill(FillGapRequest("我本来想", "roller skate", ""))
    assertTrue(m.prompt!!.endsWith("我本来想roller skate<｜hy_Assistant｜>我本来想"))
    assertTrue(m.banned && m.search!!.banLatin)
    assertNull(m.scored)
    assertEquals(listOf("玩轮滑", "玩旱冰"), r?.candidates?.map { it.text })
    assertEquals(2f / 3, r!!.share!!, 1e-5f)
  }

  @Test fun fillOrdersByProbabilityEvenWhenTheFirstAnswerIsLessLikely() = runBlocking {
    val m = Fake(reply = "迟到了，", replyP = 0.1, beams = listOf("晚点到，" to 0.5, "迟到了，" to 0.1))
    var first: FillGapResult? = null
    val r = engine(m).fill(FillGapRequest("我今天", "running late", "，你们先吃")) { first = it }
    // The first answer is shown straight away, then the list is ordered by probability.
    assertEquals("迟到了", first?.replacement)
    assertTrue(first!!.more)
    assertEquals(listOf("晚点到", "迟到了"), r?.candidates?.map { it.text })
    assertTrue(r!!.share!! > r.alternatives[0].share!!)
  }

  @Test fun fillDropsAnswersWhoseBeamWandersOffFromTheTextAfterTheGap() = runBlocking {
    // 受不了了: the answer keeps its own 了 and goes on into the 了 after the gap.
    val m = Fake(reply = "受不了了", beams = listOf("吃完饭吧" to 0.5, "受不了了。" to 0.2, "不行了" to 0.1))
    val r = engine(m).fill(FillGapRequest("我真的", "can't", "了"))
    assertEquals(listOf("受不了", "不行"), r?.candidates?.map { it.text })
  }

  @Test fun greedyStopsOnceItReachesTheTextAfterTheGap() = runBlocking {
    val m = Fake(reply = "取一下快递吗？谢谢", beams = listOf("拿一下快" to 0.2))
    val r = engine(m).fill(FillGapRequest("你能帮我", "pick up", "一下快递吗"))
    assertEquals("取一下", m.stoppedAt)
    assertEquals("一下", m.search!!.stopText)
    assertTrue(m.search!!.keepUnfinished)
    assertEquals(listOf("拿", "取"), r?.candidates?.map { it.text })
  }

  @Test fun rescoringScoresEachAnswerWithTheTextAfterTheGap() = runBlocking {
    val m = Fake(reply = "受不了了", beams = listOf("受不了了" to 0.3, "不行了。" to 0.2), scores = mapOf("不行了。" to -1.0, "受不了了。" to -2.0))
    val r = engine(m, rescoring).fill(FillGapRequest("我真的", "can't", "了"))
    // The text after the gap here is all of it, so the sentence ends after it.
    assertEquals(listOf("受不了了。", "不行了。"), m.scored)
    assertEquals(listOf("不行", "受不了"), r?.candidates?.map { it.text })
    assertEquals(1 / (1 + Math.exp(-1.0)), r!!.share!!.toDouble(), 1e-5)
  }

  @Test fun rescoringUsesOnlyTheStartOfALongTextAfterTheGap() = runBlocking {
    val m = Fake(reply = "迟到了", beams = listOf("晚点到，" to 0.2))
    engine(m, rescoring).fill(FillGapRequest("我今天", "running late", "，你们先吃饭吧"))
    assertEquals(listOf("迟到了，你们", "晚点到，你们"), m.scored)
  }

  @Test fun fillWithoutAModelHasNoAnswer() = runBlocking {
    assertNull(engine(Fake()).fill(FillGapRequest("我", "tired", "")))
  }

  @Test fun fillKeepsAtMostTenOptions() = runBlocking {
    val m = fake(*(1..20).map { "选项$it" }.toTypedArray())
    assertEquals(10, engine(m).fill(FillGapRequest("我", "option", ""))?.candidates?.size)
  }

  @Test fun rankPutsThePinnedAnswerFirstAndSharesAddUp() {
    val r = ModelFillGapEngine.rank("b", mapOf("a" to Math.log(0.6), "b" to Math.log(0.2), "c" to Math.log(0.2)))
    assertEquals(listOf("b", "a", "c"), r.map { it.first })
    assertEquals(1f, r.sumOf { it.second.toDouble() }.toFloat(), 1e-5f)
    assertEquals(listOf("a", "b", "c"), ModelFillGapEngine.rank(null, mapOf("a" to -1.0, "b" to -2.0, "c" to -3.0)).map { it.first })
  }

  @Test fun explainTakesTheFirstLineOfTheTranslation() = runBlocking {
    val m = Fake(reply = "Don't be sarcastic about it.\nNote: casual")
    val r = ModelExplainEngine(m) { book }.explain("你别在那阴阳怪气的", ScreenText.EMPTY)
    assertEquals("Don't be sarcastic about it.", r?.translation)
    assertEquals("Don't be sarcastic about it.\n", m.stoppedAt)
  }

  @Test fun checkIsQuietWhenTheModelKeepsTheSentence() = runBlocking {
    val m = fake("你吃饭了吗？", "你吃了吗")
    assertNull(ModelCheckEngine(m) { book }.review("你吃饭了吗", AssistMode.COACH, Register.Casual, ScreenText.EMPTY))
  }

  @Test fun checkSuggestsTheRewriteAndItsAlternatives() = runBlocking {
    val m = fake("我昨天看了一部电影。", "我昨天看了部电影", "我昨天看了一部电影")
    val r = ModelCheckEngine(m) { book }.review("我昨天看一本电影", AssistMode.COACH, Register.Neutral, ScreenText.EMPTY)
    assertEquals("我昨天看了一部电影", r?.natural)
    assertEquals(listOf("我昨天看了部电影"), r?.alternatives)
  }

  @Test fun checkIsOffInStuckOnlyMode() = runBlocking {
    val m = fake("我好累")
    assertNull(ModelCheckEngine(m) { book }.review("我是很累", AssistMode.STUCK, Register.Casual, ScreenText.EMPTY))
    assertNull(m.prompt)
  }
}
