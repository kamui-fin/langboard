package app.langboard.history

import app.langboard.core.Fsrs
import app.langboard.core.ReviewRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
  private val min = Fsrs.MINUTE
  private val day = Fsrs.DAY
  private val now = 100 * day

  private fun fill(
    source: String, answer: String?, at: Long, saved: Boolean = false, outcome: Outcome = Outcome.Shown,
    used: String? = null, app: String? = null, register: String? = null,
  ) = HistoryEntry(
    createdAt = at, kind = HistoryKind.Fill, app = app, source = source, answer = answer, saved = saved,
    outcome = outcome, used = used, register = register,
  )

  private fun review(stability: Double) = Fsrs.Card(Fsrs.State.Review, null, stability, 5.0, due = now + day, lastReview = now - day)

  @Test fun lookupsCloseTogetherAreOneOccasion() {
    assertEquals(0, Insights.occasions(emptyList()))
    assertEquals(1, Insights.occasions(listOf(0, 5 * min, 20 * min)))
    assertEquals(2, Insights.occasions(listOf(0, 31 * min)))
    assertEquals(3, Insights.occasions(listOf(2 * day, 0, day)))
  }

  @Test fun expressionsGroupByAnswerAndSkipNonChineseAndNoAnswer() {
    val i = Insights(
      listOf(
        fill("lazy", "懒得", now - 3 * day),
        fill("can't be bothered", "懒得", now - day, saved = true),
        fill("ok", "OK", now),
        fill("hmm", null, now, outcome = Outcome.NoAnswer),
      ),
      emptyMap(),
    )
    assertEquals(3, i.moments.size)
    assertEquals(listOf("懒得"), i.expressions.map { it.chinese })
    val e = i.expressions.single()
    assertEquals("can't be bothered", e.meaning)
    assertEquals(2, e.occasions)
    assertEquals(listOf("懒得"), i.recurring.map { it.chinese })
    assertEquals(1, i.saved)
  }

  @Test fun strengthFollowsStability() {
    assertEquals(Strength.New, Strength.of(null))
    assertEquals(Strength.New, Strength.of(Fsrs.Card(due = now)))
    assertEquals(Strength.Learning, Strength.of(Fsrs.Card(Fsrs.State.Learning, 1, 1.0, 5.0, now, now - min)))
    assertEquals(Strength.Fresh, Strength.of(review(3.0)))
    assertEquals(Strength.Settling, Strength.of(review(10.0)))
    assertEquals(Strength.Strong, Strength.of(review(30.0)))
  }

  @Test fun canSayIsWhatMemoryHoldsForAWeekOrMore() {
    val entries = listOf(fill("a", "一", 1), fill("b", "二", 2), fill("c", "三", 3))
    val cards = mapOf("Fill|一" to review(3.0), "Fill|二" to review(40.0), "Fill|三" to review(8.0))
    fun twice(key: String) = listOf(ReviewRecord(key, 1, Fsrs.Rating.Good), ReviewRecord(key, 2, Fsrs.Rating.Good))
    val i = Insights(entries, cards, twice("Fill|一") + twice("Fill|二") + twice("Fill|三"))
    assertEquals(listOf("二", "三"), i.canSay.map { it.chinese })
    // A single review isn't enough to claim it, however stable FSRS thinks it is.
    val once = Insights(entries, cards, listOf(ReviewRecord("Fill|二", 1, Fsrs.Rating.Easy)))
    assertEquals(emptyList<String>(), once.canSay.map { it.chinese })
    assertEquals(1, i.strong)
  }

  @Test fun learningValuePrefersSavedRepeatedAndUnused() {
    fun value(vararg e: HistoryEntry) = Expression("k", e.toList(), null).learningValue
    val plainUsed = value(fill("a", "一", 0, outcome = Outcome.Inserted))
    val notUsed = value(fill("a", "一", 0))
    val needed3x = value(fill("a", "一", 0, outcome = Outcome.Inserted), fill("a", "一", day, outcome = Outcome.Inserted), fill("a", "一", 2 * day, outcome = Outcome.Inserted))
    val saved = value(fill("a", "一", 0, saved = true, outcome = Outcome.Inserted))
    val otherOption = value(fill("a", "一", 0, outcome = Outcome.Inserted, used = "二"))
    val sentence = value(fill("a", "我本来想去但是后来真的懒得去了", 0, outcome = Outcome.Inserted))
    assertTrue(notUsed > plainUsed)
    assertTrue(otherOption > plainUsed)
    assertTrue(saved > notUsed)
    assertTrue(needed3x > saved)
    assertTrue(sentence < plainUsed)
  }

  @Test fun weekCountsOnlyTheLastSevenDays() {
    val i = Insights(
      listOf(
        fill("old", "旧", now - 10 * day),
        fill("new", "新", now - 2 * day, outcome = Outcome.Inserted, app = "com.whatsapp"),
        fill("old again", "旧", now - day, app = "com.tencent.mm"),
      ),
      emptyMap(),
    )
    val w = i.week(now)
    assertEquals(2, w.moments)
    assertEquals(1, w.used)
    assertEquals(listOf("新"), w.newExpressions.map { it.chinese })
    assertEquals(2, w.apps)
  }

  @Test fun registersAndAppsAreCountedMostFirstWithoutTheLab() {
    val i = Insights(
      listOf(
        fill("a", "一", 1, register = "casual", app = "com.whatsapp"),
        fill("b", "二", 2, register = "casual", app = "com.whatsapp"),
        fill("c", "三", 3, register = "formal", app = "app.langboard"),
      ),
      emptyMap(),
    )
    assertEquals(listOf("casual" to 2, "formal" to 1), i.registers)
    assertEquals(listOf("com.whatsapp" to 2), i.apps)
  }

  @Test fun recallRateNeedsEnoughReviews() {
    val few = List(5) { ReviewRecord("k", it.toLong(), Fsrs.Rating.Good) }
    assertNull(Insights(emptyList(), emptyMap(), few).recallRate)
    val many = List(8) { ReviewRecord("k", it.toLong(), Fsrs.Rating.Good) } + List(2) { ReviewRecord("k", 9L + it, Fsrs.Rating.Again) }
    assertEquals(0.8, Insights(emptyList(), emptyMap(), many).recallRate!!, 1e-9)
  }

  @Test fun minutesRoundUp() {
    assertEquals(1, Insights.minutesFor(0))
    assertEquals(1, Insights.minutesFor(7))
    assertEquals(2, Insights.minutesFor(8))
  }
}
