package app.langboard.core

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPrefsTest {
  @Test fun stepsParseAndFormat() {
    assertEquals(listOf(Fsrs.MINUTE, 10 * Fsrs.MINUTE), ReviewPrefs.parseSteps("1m 10m"))
    assertEquals(listOf(30_000L, 60 * Fsrs.MINUTE, Fsrs.DAY), ReviewPrefs.parseSteps(" 30s, 1h  1d "))
    assertEquals(listOf(5 * Fsrs.MINUTE), ReviewPrefs.parseSteps("5"))
    assertEquals(emptyList<Long>(), ReviewPrefs.parseSteps(""))
    assertNull(ReviewPrefs.parseSteps("10 minutes"))
    assertNull(ReviewPrefs.parseSteps("0m"))
    assertEquals("1m 10m 1h 2d 30s", ReviewPrefs.formatSteps(listOf(Fsrs.MINUTE, 10 * Fsrs.MINUTE, 60 * Fsrs.MINUTE, 2 * Fsrs.DAY, 30_000)))
  }

  @Test fun reviewDayStartsAtFourInTheMorning() {
    val zone = ZoneId.of("Asia/Shanghai")
    fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 3, d, h, m).atZone(zone).toInstant().toEpochMilli()
    val prefs = ReviewPrefs()
    assertEquals(at(10, 4), prefs.dayStart(at(10, 15), zone))
    // 1:30 am still belongs to the day before.
    assertEquals(at(9, 4), prefs.dayStart(at(10, 1, 30), zone))
    assertEquals(at(10, 4), prefs.dayStart(at(10, 4), zone))
    assertEquals(at(10, 0), ReviewPrefs(dayStartHour = 0).dayStart(at(10, 1, 30), zone))
  }

  @Test fun tunesWhenThereIsEnoughNewData() {
    val now = 100 * Fsrs.DAY
    assertFalse(ReviewPrefs().shouldTune(400, now))
    assertTrue(ReviewPrefs().shouldTune(600, now))
    assertFalse(ReviewPrefs(autoTune = false).shouldTune(600, now))
    val tuned = ReviewPrefs(tunedAt = now - Fsrs.DAY, tunedOn = 800)
    assertFalse(tuned.shouldTune(900, now))
    assertTrue(tuned.shouldTune(1000, now))
    // A month later, 100 more reviews is enough.
    assertTrue(tuned.shouldTune(900, now + 30 * Fsrs.DAY))
    assertFalse(tuned.shouldTune(850, now + 30 * Fsrs.DAY))
  }

  @Test fun schedulerUsesTheSettings() {
    val card = Fsrs.Card(due = 0)
    // Stricter retention, shorter intervals.
    val strict = ReviewPrefs(retention = 0.97).scheduler().review(card, Fsrs.Rating.Easy, 0)
    val loose = ReviewPrefs(retention = 0.80).scheduler().review(card, Fsrs.Rating.Easy, 0)
    assertTrue(strict.due < loose.due)
    assertEquals(Fsrs.MINUTE * 3, ReviewPrefs(learningSteps = listOf(3 * Fsrs.MINUTE)).scheduler().review(card, Fsrs.Rating.Again, 0).due)
    assertEquals(2 * Fsrs.DAY, ReviewPrefs(maxIntervalDays = 2).scheduler().review(card, Fsrs.Rating.Easy, 0).due)
  }
}
