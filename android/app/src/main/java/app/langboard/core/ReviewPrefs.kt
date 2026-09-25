package app.langboard.core

import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * How review is scheduled. The defaults suit learning to say everyday phrases and need no
 * changing: ten new phrases a day, reviews shown when there's a 90% chance you still remember them,
 * and FSRS tuned to your own reviews automatically once there are enough.
 */
data class ReviewPrefs(
  /** New phrases introduced per day. */
  val newPerDay: Int = 10,
  /** Reviews of already-learned phrases per day; learning steps don't count. */
  val reviewsPerDay: Int = 200,
  /** Chance of still remembering a phrase when it comes up (FSRS's desired retention). */
  val retention: Double = 0.90,
  val maxIntervalDays: Int = 36_500,
  val learningSteps: List<Long> = listOf(Fsrs.MINUTE, 10 * Fsrs.MINUTE),
  val relearningSteps: List<Long> = listOf(10 * Fsrs.MINUTE),
  /** The review day starts at this hour, so a late-night session counts towards the day before. */
  val dayStartHour: Int = 4,
  /** Re-fit FSRS to the review log when there's enough new data. */
  val autoTune: Boolean = true,
  /** Parameters fitted to this user's reviews; null means FSRS's defaults. */
  val params: List<Double>? = null,
  /** When [params] were fitted, and how many scored reviews they were fitted on. */
  val tunedAt: Long? = null,
  val tunedOn: Int = 0,
) {
  fun scheduler(random: Random = Random.Default) = Fsrs(
    w = params ?: Fsrs.DEFAULT_PARAMETERS,
    desiredRetention = retention,
    learningSteps = learningSteps,
    relearningSteps = relearningSteps,
    maximumInterval = maxIntervalDays,
    random = random,
  )

  /** Start of the review day that [now] falls in, in the phone's time zone. */
  fun dayStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    val start = local.toLocalDate().atTime(dayStartHour, 0).atZone(zone)
    return (if (start.isAfter(local)) start.minusDays(1) else start).toInstant().toEpochMilli()
  }

  /**
   * Whether it's worth fitting again with [scored] reviews: the first time there are enough, then
   * each time the log has grown by a quarter (or a month has passed with at least 100 more).
   */
  fun shouldTune(scored: Int, now: Long): Boolean = when {
    !autoTune || scored < FsrsOptimizer.MINI_BATCH -> false
    tunedAt == null -> true
    scored >= tunedOn * 5 / 4 -> true
    else -> now - tunedAt >= 30 * Fsrs.DAY && scored >= tunedOn + 100
  }

  companion object {
    /** "1m 10m" → durations. Units s, m, h, d; a bare number is minutes. Null when anything doesn't parse. */
    fun parseSteps(text: String): List<Long>? {
      val parts = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
      return parts.map { p ->
        val m = Regex("(\\d+(?:\\.\\d+)?)([smhd]?)").matchEntire(p.lowercase()) ?: return null
        val n = m.groupValues[1].toDouble()
        val unit = when (m.groupValues[2]) {
          "s" -> 1_000L
          "h" -> 60 * Fsrs.MINUTE
          "d" -> Fsrs.DAY
          else -> Fsrs.MINUTE
        }
        (n * unit).toLong().takeIf { it > 0 } ?: return null
      }
    }

    fun formatSteps(steps: List<Long>): String = steps.joinToString(" ") { ms ->
      when {
        ms % Fsrs.DAY == 0L -> "${ms / Fsrs.DAY}d"
        ms % (60 * Fsrs.MINUTE) == 0L -> "${ms / (60 * Fsrs.MINUTE)}h"
        ms % Fsrs.MINUTE == 0L -> "${ms / Fsrs.MINUTE}m"
        else -> "${ms / 1_000}s"
      }
    }
  }
}
