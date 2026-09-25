package app.langboard.core

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * FSRS-6, the spaced-repetition scheduler Anki uses, ported line for line from the reference
 * implementation (py-fsrs 6.3, `fsrs/scheduler.py`) and checked against it in FsrsTest. Pure: no
 * Android, no clock; times are epoch milliseconds and [random] only drives interval fuzz.
 */
class Fsrs(
  private val w: List<Double> = DEFAULT_PARAMETERS,
  private val desiredRetention: Double = 0.9,
  private val learningSteps: List<Long> = listOf(MINUTE, 10 * MINUTE),
  private val relearningSteps: List<Long> = listOf(10 * MINUTE),
  private val maximumInterval: Int = 36_500,
  private val fuzz: Boolean = true,
  private val random: Random = Random.Default,
) {
  enum class State { Learning, Review, Relearning }

  /** 1 to 4, as in every FSRS implementation and review log. */
  enum class Rating(val value: Int) { Again(1), Hard(2), Good(3), Easy(4) }

  /** What the scheduler knows about one card. A new card is `Card(due = now)`. */
  data class Card(
    val state: State = State.Learning,
    /** Index into the (re)learning steps; null in Review. */
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Long,
    val lastReview: Long? = null,
  )

  init {
    require(w.size == 21) { "FSRS-6 takes 21 parameters, got ${w.size}" }
  }

  private val decay = -w[20]
  private val factor = 0.9.pow(1 / decay) - 1

  /** Probability of recalling [card] at [now]; 0 for a card never reviewed. */
  fun retrievability(card: Card, now: Long): Double {
    val s = card.stability ?: return 0.0
    val last = card.lastReview ?: return 0.0
    val days = max(0L, floorDays(now - last))
    return (1 + factor * days / s).pow(decay)
  }

  /** [card] after being rated [rating] at [now]. */
  fun review(card: Card, rating: Rating, now: Long): Card {
    val daysSince = card.lastReview?.let { floorDays(now - it) }
    var state = card.state
    var step = card.step
    var s = card.stability ?: 0.0
    var d = card.difficulty ?: 0.0
    val next: Long

    fun sameDayOrLongTerm() {
      if (daysSince != null && daysSince < 1) {
        s = shortTermStability(s, rating)
      } else {
        s = nextStability(d, s, retrievability(card, now), rating)
      }
      d = nextDifficulty(d, rating)
    }

    fun graduate(): Long {
      state = State.Review
      step = null
      return nextInterval(s) * DAY
    }

    /** The (re)learning ladder shared by Learning and Relearning. */
    fun steps(ladder: List<Long>): Long {
      val at = step!!
      if (ladder.isEmpty() || (at >= ladder.size && rating != Rating.Again)) return graduate()
      return when (rating) {
        Rating.Again -> { step = 0; ladder[0] }
        Rating.Hard -> when {
          at == 0 && ladder.size == 1 -> (ladder[0] * 1.5).toLong()
          at == 0 -> (ladder[0] + ladder[1]) / 2
          else -> ladder[at]
        }
        Rating.Good -> if (at + 1 == ladder.size) graduate() else { step = at + 1; ladder[at + 1] }
        Rating.Easy -> graduate()
      }
    }

    when (card.state) {
      State.Learning -> {
        if (card.stability == null || card.difficulty == null) {
          s = initialStability(rating)
          d = clampDifficulty(initialDifficulty(rating))
        } else {
          sameDayOrLongTerm()
        }
        next = steps(learningSteps)
      }
      State.Review -> {
        sameDayOrLongTerm()
        next = if (rating == Rating.Again && relearningSteps.isNotEmpty()) {
          state = State.Relearning
          step = 0
          relearningSteps[0]
        } else {
          nextInterval(s) * DAY
        }
      }
      State.Relearning -> {
        sameDayOrLongTerm()
        next = steps(relearningSteps)
      }
    }

    val interval = if (fuzz && state == State.Review) fuzzed(next) else next
    return Card(state, step, s, d, due = now + interval, lastReview = now)
  }

  /** When [card] would next be due for each rating at [now], for the buttons' labels. */
  fun preview(card: Card, now: Long): Map<Rating, Long> =
    Rating.entries.associateWith { r -> Fsrs(w, desiredRetention, learningSteps, relearningSteps, maximumInterval, fuzz = false).review(card, r, now).due - now }

  private fun initialStability(r: Rating) = clampStability(w[r.value - 1])

  private fun initialDifficulty(r: Rating) = w[4] - exp(w[5] * (r.value - 1)) + 1

  private fun nextInterval(s: Double): Long {
    val raw = s / factor * (desiredRetention.pow(1 / decay) - 1)
    return pyRound(raw).coerceIn(1, maximumInterval.toLong())
  }

  private fun shortTermStability(s: Double, r: Rating): Double {
    var inc = exp(w[17] * (r.value - 3 + w[18])) * s.pow(-w[19])
    if (r != Rating.Again) inc = max(inc, 1.0)
    return clampStability(s * inc)
  }

  private fun nextDifficulty(d: Double, r: Rating): Double {
    val delta = -(w[6] * (r.value - 3))
    val damped = d + (10.0 - d) * delta / 9.0
    return clampDifficulty(w[7] * initialDifficulty(Rating.Easy) + (1 - w[7]) * damped)
  }

  private fun nextStability(d: Double, s: Double, r: Double, rating: Rating): Double =
    clampStability(if (rating == Rating.Again) forgetStability(d, s, r) else recallStability(d, s, r, rating))

  private fun forgetStability(d: Double, s: Double, r: Double): Double {
    val longTerm = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14])
    val shortTerm = s / exp(w[17] * w[18])
    return min(longTerm, shortTerm)
  }

  private fun recallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
    val hardPenalty = if (rating == Rating.Hard) w[15] else 1.0
    val easyBonus = if (rating == Rating.Easy) w[16] else 1.0
    return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp((1 - r) * w[10]) - 1) * hardPenalty * easyBonus)
  }

  /** A Review interval, moved a few days either way so cards learned together don't stay together. */
  private fun fuzzed(interval: Long): Long {
    val days = floorDays(interval)
    if (days < 2.5) return interval
    var delta = 1.0
    for ((start, end, f) in FUZZ_RANGES) delta += f * max(min(days.toDouble(), end) - start, 0.0)
    val maxIvl = min(pyRound(days + delta), maximumInterval.toLong())
    val minIvl = min(max(2L, pyRound(days - delta)), maxIvl)
    val fuzzedDays = min(pyRound(random.nextDouble() * (maxIvl - minIvl + 1) + minIvl), maximumInterval.toLong())
    return fuzzedDays * DAY
  }

  private fun clampDifficulty(d: Double) = d.coerceIn(1.0, 10.0)
  private fun clampStability(s: Double) = max(s, STABILITY_MIN)

  companion object {
    const val MINUTE = 60_000L
    const val DAY = 24 * 60 * MINUTE

    /** FSRS-6 defaults, trained on hundreds of millions of Anki reviews. */
    val DEFAULT_PARAMETERS = listOf(
      0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
      1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )
    private const val STABILITY_MIN = 0.001
    private val FUZZ_RANGES = listOf(Triple(2.5, 7.0, 0.15), Triple(7.0, 20.0, 0.1), Triple(20.0, Double.POSITIVE_INFINITY, 0.05))

    /** Whole days in [ms], rounded down like Python's `timedelta.days`. */
    private fun floorDays(ms: Long) = Math.floorDiv(ms, DAY)

    /** Python's round(): halves go to the even neighbour. */
    private fun pyRound(x: Double) = Math.rint(x).toLong()
  }
}
