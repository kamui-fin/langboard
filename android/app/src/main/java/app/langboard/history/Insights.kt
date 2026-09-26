package app.langboard.history

import app.langboard.core.Fsrs
import app.langboard.core.ReviewRecord

/**
 * How well an expression is held, from its FSRS card. Stability is how many days until recall
 * drops to 90%, so these are claims about memory, not about effort.
 */
enum class Strength {
  /** Never reviewed. */
  New,
  /** In its learning or relearning steps. */
  Learning,
  /** Recalled, held for under a week. */
  Fresh,
  /** Held for one to three weeks. */
  Settling,
  /** Held for three weeks or more. */
  Strong;

  companion object {
    fun of(card: Fsrs.Card?): Strength {
      if (card == null || card.lastReview == null) return New
      if (card.state != Fsrs.State.Review) return Learning
      val s = card.stability ?: return Fresh
      return when {
        s >= 21 -> Strong
        s >= 7 -> Settling
        else -> Fresh
      }
    }
  }
}

/**
 * One Chinese expression Langboard gave the user, with every moment it came up. [entries] are
 * newest first; [best] is the one to show (saved over unsaved, then newest), the same one review uses.
 */
data class Expression(
  val key: String,
  val entries: List<HistoryEntry>,
  val card: Fsrs.Card?,
) {
  val best: HistoryEntry = entries.sortedWith(compareByDescending<HistoryEntry> { it.saved }.thenByDescending { it.createdAt }).first()
  val chinese: String get() = best.answer.orEmpty()
  /** What the user was trying to say: the English, or a word's meaning. */
  val meaning: String get() = best.reviewPrompt
  val firstAt: Long get() = entries.minOf { it.createdAt }
  val lastAt: Long get() = entries.maxOf { it.createdAt }
  val saved: Boolean get() = entries.any { it.saved }
  val timesUsed: Int get() = entries.count { it.outcome == Outcome.Inserted }
  val strength: Strength get() = Strength.of(card)

  /** Separate occasions the user needed this: lookups more than [Insights.OCCASION_GAP_MS] apart. */
  val occasions: Int get() = Insights.occasions(entries.map { it.createdAt })

  /**
   * How much there is to learn from this, for choosing what to review first. Curation is the point:
   * not every lookup deserves a card. Higher when the user saved it, needed it again, picked a
   * different option than the first, or looked but didn't use it (they weren't sure).
   */
  val learningValue: Int get() {
    var v = 0
    if (saved) v += 3
    v += (occasions - 1).coerceIn(0, 3) * 2
    if (entries.any { it.used != null && it.used != it.answer }) v += 1
    if (entries.none { it.outcome == Outcome.Inserted }) v += 1
    if (entries.any { it.outcome == Outcome.Undone }) v += 1
    // Long answers are sentences, not expressions: less to reuse.
    if (chinese.length > 12) v -= 2
    return v
  }
}

/** Numbers for the Home and You screens, all derived from what's on the phone. Pure, so it's tested on the JVM. */
class Insights(
  entries: List<HistoryEntry>,
  cards: Map<String, Fsrs.Card>,
  private val reviews: List<ReviewRecord> = emptyList(),
) {
  private val all = entries

  /** Every lookup that got an answer. */
  val moments: List<HistoryEntry> = entries.filter { it.outcome != Outcome.NoAnswer && it.answer != null }

  /** One per Chinese expression, newest first. Explanations count: they're expressions learned from real messages. */
  val expressions: List<Expression> = moments
    .filter { it.kind != HistoryKind.Check && hasChinese(it.answer!!) }
    .groupBy { HistoryStore.reviewKey(it) }
    .map { (key, list) -> Expression(key, list.sortedByDescending { it.createdAt }, cards[key]) }
    .sortedByDescending { it.lastAt }

  /** Needed on more than one occasion, most often first: the honest form of "recurring gaps". */
  val recurring: List<Expression> = expressions.filter { it.occasions > 1 }
    .sortedWith(compareByDescending<Expression> { it.occasions }.thenByDescending { it.lastAt })

  private val reviewsByKey: Map<String, Int> = reviews.groupingBy { it.card }.eachCount()

  /**
   * Expressions held for a week or more and recalled at least twice: what the user can now say.
   * One Easy on a new card already gives FSRS a week's stability, which is too early to claim.
   */
  val canSay: List<Expression> = expressions
    .filter { it.strength >= Strength.Settling && (reviewsByKey[it.key] ?: 0) >= MIN_REVIEWS_TO_CLAIM }
    .sortedByDescending { it.card?.stability ?: 0.0 }

  val strong: Int = expressions.count { it.strength == Strength.Strong }
  val saved: Int = expressions.count { it.saved }

  data class Week(val moments: Int, val used: Int, val newExpressions: List<Expression>, val apps: Int)

  /** The last seven days. */
  fun week(now: Long): Week {
    val since = now - 7 * Fsrs.DAY
    val recent = moments.filter { it.createdAt >= since }
    return Week(
      moments = recent.size,
      used = recent.count { it.outcome == Outcome.Inserted },
      newExpressions = expressions.filter { it.firstAt >= since },
      apps = recent.mapNotNull { it.app }.distinct().size,
    )
  }

  /** Share of reviews recalled (anything but Again), or null before there are enough to mean anything. */
  val recallRate: Double? = reviews.takeIf { it.size >= MIN_REVIEWS_FOR_RATE }
    ?.let { r -> r.count { it.rating != Fsrs.Rating.Again }.toDouble() / r.size }

  val reviewCount: Int = reviews.size

  companion object {
    /** Lookups of one expression closer together than this are one occasion (the panel reopened on the same draft). */
    const val OCCASION_GAP_MS = 30 * Fsrs.MINUTE
    const val MIN_REVIEWS_FOR_RATE = 10
    const val MIN_REVIEWS_TO_CLAIM = 2

    fun occasions(times: List<Long>): Int {
      if (times.isEmpty()) return 0
      var n = 1
      val sorted = times.sorted()
      for (i in 1 until sorted.size) if (sorted[i] - sorted[i - 1] > OCCASION_GAP_MS) n++
      return n
    }

    fun hasChinese(s: String): Boolean = s.any(::hasChineseChar)

    fun hasChineseChar(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /** About how long [cards] take, at roughly 8 seconds a card, rounded up to a minute. */
    fun minutesFor(cards: Int): Int = ((cards * 8 + 59) / 60).coerceAtLeast(1)
  }
}
