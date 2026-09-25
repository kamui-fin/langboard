package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `simulated_reviews.csv`: 300 phrases over 200 days by a simulated learner whose memory differs
 * from the defaults (initial stabilities 0.6/2.5/5/15 days, w8 = 2.2), scheduled with the defaults.
 * Expected losses are from py-fsrs 6.3.2's own scheduler, computed the way its Optimizer does.
 */
class FsrsOptimizerTest {
  private val records = javaClass.getResourceAsStream("/fsrs/simulated_reviews.csv")!!.bufferedReader().readLines().map { line ->
    val (card, at, rating) = line.split(',')
    ReviewRecord(card, at.toLong(), Fsrs.Rating.entries.first { it.value == rating.toInt() })
  }

  private val truth = Fsrs.DEFAULT_PARAMETERS.toMutableList().apply {
    this[0] = 0.6; this[1] = 2.5; this[2] = 5.0; this[3] = 15.0; this[8] = 2.2
  }

  @Test fun lossMatchesReference() {
    val o = FsrsOptimizer(records)
    assertEquals(1285, o.scoredReviews)
    assertEquals(0.2705127484704381, o.loss(Fsrs.DEFAULT_PARAMETERS), 1e-9)
    assertEquals(0.26469890678885966, o.loss(truth), 1e-9)
  }

  @Test fun fitsTheLearnerBetterThanTheDefaults() {
    val o = FsrsOptimizer(records)
    val fitted = requireNotNull(o.optimize())
    val before = o.loss(Fsrs.DEFAULT_PARAMETERS)
    val after = o.loss(fitted)
    assertTrue("$after vs $before", after < before - 0.002)
    // It learned the direction: this learner's first-review memories last longer than the defaults say.
    assertTrue(fitted[2] > Fsrs.DEFAULT_PARAMETERS[2])
    assertEquals(21, fitted.size)
  }

  @Test fun tooFewReviewsKeepsTheDefaults() {
    val o = FsrsOptimizer(records.take(300))
    assertFalse(o.hasEnoughData)
    assertNull(o.optimize())
  }
}
