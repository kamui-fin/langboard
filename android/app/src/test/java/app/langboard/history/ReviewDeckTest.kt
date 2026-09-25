package app.langboard.history

import app.langboard.core.Fsrs
import app.langboard.core.ReviewPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewDeckTest {
  private fun fill(source: String, answer: String, at: Long, saved: Boolean = false, outcome: Outcome = Outcome.Shown) = HistoryEntry(
    createdAt = at, kind = HistoryKind.Fill, app = null, source = source, answer = answer, saved = saved, outcome = outcome,
  )

  private val now = 1_000_000_000L

  @Test fun oneCardPerAnswerPreferringSaved() {
    val deck = ReviewDeck(
      listOf(fill("lazy", "懒得", 1), fill("can't be bothered", "懒得", 2, saved = true), fill("tired", "累", 3)),
      emptyMap(),
    )
    val new = deck.new(now)
    assertEquals(listOf("懒得", "累"), new.map { it.entry.answer })
    assertEquals("can't be bothered", new.first().entry.source)
  }

  @Test fun newCardsSavedThenNotUsedThenNewest() {
    val deck = ReviewDeck(
      listOf(
        fill("a", "一", 1, outcome = Outcome.Inserted),
        fill("b", "二", 2),
        fill("c", "三", 3, outcome = Outcome.Inserted),
        fill("d", "四", 0, saved = true),
      ),
      emptyMap(),
    )
    assertEquals(listOf("四", "二", "三", "一"), deck.new(now).map { it.entry.answer })
  }

  @Test fun dueMostOverdueFirstAndNotYetDueWaits() {
    val cards = mapOf(
      "Fill|一" to Fsrs.Card(Fsrs.State.Review, null, 3.0, 5.0, due = now - 10),
      "Fill|二" to Fsrs.Card(Fsrs.State.Review, null, 3.0, 5.0, due = now - 500),
      "Fill|三" to Fsrs.Card(Fsrs.State.Review, null, 3.0, 5.0, due = now + 7_000),
    )
    val deck = ReviewDeck(listOf(fill("a", "一", 1), fill("b", "二", 2), fill("c", "三", 3), fill("d", "四", 4)), cards)
    assertEquals(listOf("二", "一"), deck.due(now).map { it.entry.answer })
    assertEquals(listOf("四"), deck.new(now).map { it.entry.answer })
    assertEquals(listOf("二", "一", "四"), deck.session(now, ReviewDeck.Limits(newLeft = 10, reviewsLeft = 200)).map { it.entry.answer })
    assertEquals(now + 7_000, deck.nextDue(now))
    assertNull(ReviewDeck(emptyList(), cards).nextDue(now))
  }

  @Test fun limitsCapReviewsAndNewButNeverLearningSteps() {
    val review = Fsrs.Card(Fsrs.State.Review, null, 3.0, 5.0, due = now - 10)
    val learning = Fsrs.Card(Fsrs.State.Learning, 1, 2.3, 5.0, due = now - 5, lastReview = now - 600_000)
    val cards = mapOf("Fill|一" to review, "Fill|二" to review.copy(due = now - 20), "Fill|三" to learning)
    val entries = listOf(fill("a", "一", 1), fill("b", "二", 2), fill("c", "三", 3), fill("d", "四", 4), fill("e", "五", 5))
    val deck = ReviewDeck(entries, cards)
    assertEquals(listOf("三", "二", "五"), deck.session(now, ReviewDeck.Limits(newLeft = 1, reviewsLeft = 1)).map { it.entry.answer })
    assertEquals(listOf("三"), deck.session(now, ReviewDeck.Limits(newLeft = 0, reviewsLeft = 0)).map { it.entry.answer })
  }

  @Test fun limitsSubtractWhatWasDoneToday() {
    val prefs = ReviewPrefs(newPerDay = 10, reviewsPerDay = 200)
    assertEquals(ReviewDeck.Limits(3, 150), ReviewDeck.Limits.of(prefs, HistoryStore.DailyCounts(newCards = 7, reviews = 50)))
    assertEquals(ReviewDeck.Limits(0, 0), ReviewDeck.Limits.of(prefs, HistoryStore.DailyCounts(newCards = 12, reviews = 250)))
  }
}
