package app.langboard.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** One logged review, as the optimizer needs it: which card, when, and how it was rated. */
data class ReviewRecord(val card: String, val at: Long, val rating: Fsrs.Rating)

/**
 * Tunes FSRS's 21 parameters to one person's reviews, as py-fsrs 6.3's `Optimizer` does: each
 * card's first 64 reviews are replayed, and at every review a day or more after the last one the
 * predicted chance of recall is scored against what happened (log loss). Adam with a cosine
 * learning-rate schedule, 5 passes, mini-batches of 512 scored reviews, parameters kept within FSRS's
 * bounds. Gradients are exact, by forward-mode differentiation ([Dual]), so no ML library is needed.
 */
class FsrsOptimizer(records: List<ReviewRecord>) {
  /** Each card's reviews in order, the cards in order of their first review. */
  private val histories: List<List<ReviewRecord>> = records.groupBy { it.card }.values
    .map { h -> h.sortedBy { it.at }.take(MAX_SEQ_LEN) }
    .sortedBy { it.first().at }

  /** Reviews that count towards the loss: a day or more after the card's previous one. */
  val scoredReviews: Int = histories.sumOf { h -> h.zipWithNext().count { (a, b) -> days(b.at - a.at) > 0 } }

  /** Enough reviews to learn from; below this the defaults are the better guess. */
  val hasEnoughData: Boolean get() = scoredReviews >= MINI_BATCH

  /** Average log loss of [params] over the reviews: lower predicts this person better. */
  fun loss(params: List<Double>): Double {
    var sum = 0.0
    var n = 0
    val model = Model(params.map { Dual.const(it) })
    for (h in histories) {
      var s: Dual? = null
      var d: Dual? = null
      var last = 0L
      for (r in h) {
        if (s != null) {
          val elapsed = days(r.at - last)
          if (elapsed > 0) {
            sum += bce(model.retrievability(elapsed, s).v, r.rating)
            n++
          }
        }
        val (ns, nd) = model.step(s, d, r, if (s == null) null else days(r.at - last))
        s = ns; d = nd; last = r.at
      }
    }
    return if (n == 0) 0.0 else sum / n
  }

  /**
   * Parameters fitted to the reviews, or null when there aren't enough of them or the fit doesn't
   * predict them better than [start]. [onProgress] gets 0 to 1.
   */
  fun optimize(start: List<Double> = Fsrs.DEFAULT_PARAMETERS, onProgress: (Float) -> Unit = {}): List<Double>? {
    if (!hasEnoughData) return null
    val p = start.toDoubleArray()
    val m = DoubleArray(N)
    val v = DoubleArray(N)
    var t = 0
    val totalSteps = ((scoredReviews + MINI_BATCH - 1) / MINI_BATCH) * EPOCHS
    val order = histories.indices.toMutableList()
    val rng = Random(42)
    var best: List<Double>? = null
    var bestLoss = loss(start)

    val grad = DoubleArray(N)
    var inBatch = 0

    fun step() {
      // Adam on the summed mini-batch loss, then back inside the bounds; the rate follows a cosine.
      val lr = LEARNING_RATE * (1 + cos(PI * t / totalSteps)) / 2
      t++
      for (i in 0 until N) {
        m[i] = BETA1 * m[i] + (1 - BETA1) * grad[i]
        v[i] = BETA2 * v[i] + (1 - BETA2) * grad[i] * grad[i]
        val mHat = m[i] / (1 - Math.pow(BETA1, t.toDouble()))
        val vHat = v[i] / (1 - Math.pow(BETA2, t.toDouble()))
        p[i] = (p[i] - lr * mHat / (sqrt(vHat) + EPS)).coerceIn(LOWER[i], UPPER[i])
      }
      grad.fill(0.0)
      inBatch = 0
    }

    repeat(EPOCHS) { epoch ->
      order.shuffle(rng)
      var model = Model(variables(p))
      for ((done, index) in order.withIndex()) {
        var s: Dual? = null
        var d: Dual? = null
        var last = 0L
        for (r in histories[index]) {
          if (s != null) {
            val elapsed = days(r.at - last)
            if (elapsed > 0) {
              val loss = bceDual(model.retrievability(elapsed, s), r.rating)
              for (i in 0 until N) grad[i] += loss.g[i]
              inBatch++
            }
          }
          val (ns, nd) = model.step(s, d, r, if (s == null) null else days(r.at - last))
          s = ns; d = nd; last = r.at
          if (inBatch == MINI_BATCH) {
            step()
            model = Model(variables(p))
            // Truncate: the card carries on with its current values but no longer depends on the old parameters.
            s = Dual.const(s.v); d = Dual.const(d!!.v)
          }
        }
        onProgress((epoch + (done + 1f) / order.size) / EPOCHS)
      }
      if (inBatch > 0) step()
      val epochLoss = loss(p.toList())
      if (epochLoss < bestLoss) { bestLoss = epochLoss; best = p.toList() }
    }
    return best
  }

  /** The FSRS-6 memory model on [Dual]s: the same formulas as [Fsrs], for stability and difficulty only. */
  private class Model(private val w: List<Dual>) {
    private val decay = -w[20]
    /** 0.9^(1/decay) − 1, so that a card is 90% recallable after exactly its stability in days. */
    private val factor = (Dual.const(ln(0.9)) / decay).exp() - 1.0

    fun retrievability(days: Long, s: Dual): Dual = (factor * days.toDouble() / s + 1.0).pow(decay)

    /** Stability and difficulty after [r]; [elapsedDays] is null for a card's first review. */
    fun step(s: Dual?, d: Dual?, r: ReviewRecord, elapsedDays: Long?): Pair<Dual, Dual> {
      if (s == null || d == null) {
        return clampS(w[r.rating.value - 1]) to clampD(initialDifficulty(r.rating.value))
      }
      val nextS = if (elapsedDays!! < 1) shortTerm(s, r.rating) else {
        val rNow = retrievability(elapsedDays, s)
        clampS(if (r.rating == Fsrs.Rating.Again) forget(d, s, rNow) else recall(d, s, rNow, r.rating))
      }
      return nextS to nextDifficulty(d, r.rating.value)
    }

    private fun initialDifficulty(g: Int) = w[4] - (w[5] * (g - 1).toDouble()).exp() + 1.0

    private fun shortTerm(s: Dual, r: Fsrs.Rating): Dual {
      var inc = (w[17] * (w[18] + (r.value - 3).toDouble())).exp() * s.pow(-w[19])
      if (r != Fsrs.Rating.Again) inc = Dual.max(inc, 1.0)
      return clampS(s * inc)
    }

    private fun nextDifficulty(d: Dual, g: Int): Dual {
      val delta = -(w[6] * (g - 3).toDouble())
      val damped = d + (-d + 10.0) * delta / 9.0
      return clampD(w[7] * initialDifficulty(4) + (-w[7] + 1.0) * damped)
    }

    private fun forget(d: Dual, s: Dual, r: Dual): Dual {
      val longTerm = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * ((-r + 1.0) * w[14]).exp()
      val shortTerm = s / (w[17] * w[18]).exp()
      return Dual.min(longTerm, shortTerm)
    }

    private fun recall(d: Dual, s: Dual, r: Dual, rating: Fsrs.Rating): Dual {
      var f = w[8].exp() * (-d + 11.0) * s.pow(-w[9]) * (((-r + 1.0) * w[10]).exp() - 1.0)
      if (rating == Fsrs.Rating.Hard) f *= w[15]
      if (rating == Fsrs.Rating.Easy) f *= w[16]
      return s * (f + 1.0)
    }

    private fun clampS(s: Dual) = Dual.max(s, 0.001)
    private fun clampD(d: Dual) = Dual.min(Dual.max(d, 1.0), 10.0)
  }

  companion object {
    const val MINI_BATCH = 512
    private const val N = 21
    private const val EPOCHS = 5
    private const val MAX_SEQ_LEN = 64
    private const val LEARNING_RATE = 4e-2
    private const val BETA1 = 0.9
    private const val BETA2 = 0.999
    private const val EPS = 1e-8

    private val LOWER = doubleArrayOf(
      0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001,
      0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
    )
    private val UPPER = doubleArrayOf(
      100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5,
      5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
    )

    private fun variables(p: DoubleArray) = p.indices.map { Dual.variable(p[it], it, N) }

    private fun days(ms: Long) = Math.floorDiv(ms, Fsrs.DAY)

    /** Log loss of predicting [p] for a review rated [rating] (recalled unless Again), clamped like PyTorch's BCELoss. */
    private fun bce(p: Double, rating: Fsrs.Rating): Double =
      if (rating == Fsrs.Rating.Again) -max(ln(1 - p), -100.0) else -max(ln(p), -100.0)

    private fun bceDual(p: Dual, rating: Fsrs.Rating): Dual =
      if (rating == Fsrs.Rating.Again) -Dual.max((-p + 1.0).ln(), -100.0) else -Dual.max(p.ln(), -100.0)
  }
}

/**
 * A number and its derivative with respect to each of the parameters (forward-mode automatic
 * differentiation). min and max pass the derivative of the side they pick, as PyTorch does.
 */
class Dual(val v: Double, val g: DoubleArray) {
  operator fun plus(o: Dual) = Dual(v + o.v, DoubleArray(g.size) { g[it] + o.g[it] })
  operator fun plus(c: Double) = Dual(v + c, g)
  operator fun minus(o: Dual) = Dual(v - o.v, DoubleArray(g.size) { g[it] - o.g[it] })
  operator fun minus(c: Double) = Dual(v - c, g)
  operator fun unaryMinus() = Dual(-v, DoubleArray(g.size) { -g[it] })
  operator fun times(o: Dual) = Dual(v * o.v, DoubleArray(g.size) { g[it] * o.v + o.g[it] * v })
  operator fun times(c: Double) = Dual(v * c, DoubleArray(g.size) { g[it] * c })
  operator fun div(o: Dual) = Dual(v / o.v, DoubleArray(g.size) { (g[it] * o.v - o.g[it] * v) / (o.v * o.v) })
  operator fun div(c: Double) = Dual(v / c, DoubleArray(g.size) { g[it] / c })

  fun exp(): Dual { val e = kotlin.math.exp(v); return Dual(e, DoubleArray(g.size) { g[it] * e }) }
  fun ln(): Dual = Dual(kotlin.math.ln(v), DoubleArray(g.size) { g[it] / v })

  /** this^[e], both varying: d = this^e · (e' ln this + e · this' / this). */
  fun pow(e: Dual): Dual {
    val r = Math.pow(v, e.v)
    val lnV = kotlin.math.ln(v)
    return Dual(r, DoubleArray(g.size) { r * (e.g[it] * lnV + e.v * g[it] / v) })
  }

  fun pow(e: Double): Dual {
    val r = Math.pow(v, e)
    return Dual(r, DoubleArray(g.size) { e * Math.pow(v, e - 1) * g[it] })
  }

  companion object {
    private const val SIZE = 21
    fun const(v: Double, size: Int = SIZE) = Dual(v, DoubleArray(size))
    fun variable(v: Double, index: Int, size: Int) = Dual(v, DoubleArray(size).also { it[index] = 1.0 })
    fun max(a: Dual, c: Double) = if (a.v >= c) a else const(c, a.g.size)
    fun min(a: Dual, c: Double) = if (a.v <= c) a else const(c, a.g.size)
    fun min(a: Dual, b: Dual) = if (a.v <= b.v) a else b
  }
}

private operator fun Double.times(d: Dual) = d * this
