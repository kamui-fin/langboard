package app.langboard.core

import app.langboard.core.Fsrs.Rating
import app.langboard.core.Fsrs.State
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected value comes from the reference implementation, py-fsrs 6.3.2, with fuzz off
 * (`Scheduler(enable_fuzzing=False)`); each step reviews when the card is due unless noted.
 */
class FsrsTest {
  private data class Step(
    val rating: Int, val at: Long, val state: State, val step: Int?, val stability: Double, val difficulty: Double, val due: Long,
  )

private val t0 = 1767225600000L

  private val reference = mapOf(
    "good_path" to listOf(
      Step(3, 1767225600000L, State.Learning, 1, 2.3065, 2.118103970459016, 1767226200000L),
      Step(3, 1767226200000L, State.Review, null, 2.3065, 2.111214235785395, 1767399000000L),
      Step(3, 1767399000000L, State.Review, null, 10.971048263078135, 2.1043313908464483, 1768349400000L),
      Step(3, 1768349400000L, State.Review, null, 46.316858440073425, 2.0974554287524403, 1772323800000L),
      Step(3, 1772323800000L, State.Review, null, 162.99981577472244, 2.0905863426205262, 1786407000000L),
      Step(3, 1786407000000L, State.Review, null, 497.8765551245907, 2.083724125574744, 1829434200000L),
      Step(3, 1829434200000L, State.Review, null, 1347.918623024373, 2.0768687707460076, 1945901400000L),
      Step(3, 1945901400000L, State.Review, null, 3298.6456099035986, 2.0700202712721, 2230935000000L),
    ),
    "easy_first" to listOf(
      Step(4, 1767225600000L, State.Review, null, 8.2956, 1.0, 1767916800000L),
      Step(3, 1767916800000L, State.Review, null, 38.90514998232684, 1.0, 1771286400000L),
      Step(3, 1771286400000L, State.Review, null, 153.00006571733257, 1.0, 1784505600000L),
      Step(4, 1784505600000L, State.Review, null, 820.7828490530677, 1.0, 1855440000000L),
    ),
    "again_hard_mix" to listOf(
      Step(1, 1767225600000L, State.Learning, 0, 0.212, 6.4133, 1767225660000L),
      Step(2, 1767225660000L, State.Learning, 0, 0.212, 7.604209769076838, 1767225990000L),
      Step(3, 1767225990000L, State.Learning, 1, 0.24668918777567272, 7.5918339286046, 1767226590000L),
      Step(3, 1767226590000L, State.Review, null, 0.2842063592758949, 7.579470463972833, 1767312990000L),
      Step(1, 1767312990000L, State.Relearning, 0, 0.12496820594701799, 9.189616770405554, 1767313590000L),
      Step(3, 1767313590000L, State.Review, null, 0.15056266021531958, 9.175655522931985, 1767399990000L),
      Step(3, 1767399990000L, State.Review, null, 0.7309482144610868, 9.16170823670589, 1767486390000L),
      Step(2, 1767486390000L, State.Review, null, 1.3001048851753993, 9.428731232426037, 1767572790000L),
      Step(3, 1767572790000L, State.Review, null, 2.168566375484708, 9.414530870490449, 1767745590000L),
    ),
    "overdue_and_early" to listOf(
      Step(3, 1767225600000L, State.Learning, 1, 2.3065, 2.118103970459016, 1767226200000L),
      Step(3, 1767226200000L, State.Review, null, 2.3065, 2.111214235785395, 1767399000000L),
      Step(3, 1767399000000L, State.Review, null, 10.971048263078135, 2.1043313908464483, 1768349400000L),
      Step(3, 1768263000000L, State.Review, null, 43.96600477964826, 2.0974554287524403, 1772064600000L),
      Step(3, 1768270200000L, State.Review, null, 43.96600477964826, 2.0905863426205262, 1772071800000L),
      Step(1, 1770070200000L, State.Relearning, 0, 2.671047394945294, 7.385457884129076, 1770070800000L),
      Step(3, 1770070800000L, State.Review, null, 2.671047394945294, 7.373300795541786, 1770330000000L),
      Step(4, 1770330000000L, State.Review, null, 11.678117703947462, 6.480808694891617, 1771366800000L),
    ),
  )

  @Test fun matchesReference() {
    val fsrs = Fsrs(fuzz = false)
    for ((name, steps) in reference) {
      var card = Fsrs.Card(due = t0)
      steps.forEachIndexed { i, s ->
        card = fsrs.review(card, Rating.entries.first { it.value == s.rating }, s.at)
        val where = "$name step $i"
        assertEquals(where, s.state, card.state)
        assertEquals(where, s.step, card.step)
        assertEquals(where, s.stability, card.stability!!, 1e-9)
        assertEquals(where, s.difficulty, card.difficulty!!, 1e-9)
        assertEquals(where, s.due, card.due)
        assertEquals(where, s.at, card.lastReview)
      }
    }
  }

  @Test fun fuzzStaysNearTheInterval() {
    val plain = Fsrs(fuzz = false)
    val fuzzy = Fsrs(random = Random(7))
    var card = Fsrs.Card(due = t0)
    var now = t0
    repeat(6) {
      val expected = plain.review(card, Rating.Good, now)
      val got = fuzzy.review(card, Rating.Good, now)
      val days = (expected.due - now) / Fsrs.DAY
      val fuzzedDays = (got.due - now) / Fsrs.DAY
      if (days >= 3) assertTrue("$fuzzedDays vs $days", fuzzedDays in (days * 0.9 - 2).toLong()..(days * 1.1 + 2).toLong())
      else assertEquals(expected.due, got.due)
      card = expected
      now = expected.due
    }
  }

  @Test fun previewShowsEachRating() {
    val p = Fsrs().preview(Fsrs.Card(due = t0), t0)
    assertEquals(Fsrs.MINUTE, p[Rating.Again])
    assertEquals(330_000L, p[Rating.Hard])
    assertEquals(10 * Fsrs.MINUTE, p[Rating.Good])
    assertEquals(8 * Fsrs.DAY, p[Rating.Easy])
  }

  @Test fun retrievabilityFallsWithTime() {
    val fsrs = Fsrs(fuzz = false)
    val card = fsrs.review(Fsrs.Card(due = t0), Rating.Easy, t0)
    assertEquals(0.0, fsrs.retrievability(Fsrs.Card(due = t0), t0), 0.0)
    assertEquals(1.0, fsrs.retrievability(card, t0), 1e-12)
    // Due when recall is predicted at the desired 90%.
    assertEquals(0.9, fsrs.retrievability(card, card.due), 0.01)
  }
}
