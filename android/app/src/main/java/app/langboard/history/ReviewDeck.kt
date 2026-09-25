package app.langboard.history

import app.langboard.core.Fsrs
import app.langboard.core.ReviewPrefs

/** One card in a review session: the lookup it asks about and where FSRS has it. */
data class ReviewItem(val key: String, val entry: HistoryEntry, val card: Fsrs.Card)

/**
 * What to review, from the history and the cards' FSRS state. Every Chinese answer the user got
 * (a fill, or a saved word with its meaning) is one card, however many times it was looked up.
 */
class ReviewDeck(entries: List<HistoryEntry>, private val cards: Map<String, Fsrs.Card>) {
  /** One entry per card: a saved lookup over an unsaved one, then the newest. */
  private val byKey: Map<String, HistoryEntry> = entries.filter { it.reviewable }
    .sortedWith(compareByDescending<HistoryEntry> { it.saved }.thenByDescending { it.createdAt })
    .distinctBy { HistoryStore.reviewKey(it) }
    .associateBy { HistoryStore.reviewKey(it) }

  /** Cards reviewed before and due by [now], the most overdue first. */
  fun due(now: Long): List<ReviewItem> = byKey.mapNotNull { (key, e) ->
    cards[key]?.takeIf { it.due <= now }?.let { ReviewItem(key, e, it) }
  }.sortedBy { it.card.due }

  /**
   * Cards never reviewed, saved ones first, then ones looked up but not used (what the user
   * didn't know well enough to send), newest first.
   */
  fun new(now: Long): List<ReviewItem> = byKey.filterKeys { it !in cards }.values
    .sortedWith(
      compareByDescending<HistoryEntry> { it.saved }
        .thenBy { it.outcome == Outcome.Inserted }
        .thenByDescending { it.createdAt }
    )
    .map { ReviewItem(HistoryStore.reviewKey(it), it, Fsrs.Card(due = now)) }

  /**
   * A session within today's [limits]: cards in their learning steps (never limited, or they'd
   * stall halfway), then due reviews, then new cards.
   */
  fun session(now: Long, limits: Limits): List<ReviewItem> {
    val (learning, reviews) = due(now).partition { it.card.state != Fsrs.State.Review }
    return learning + reviews.take(limits.reviewsLeft) + new(now).take(limits.newLeft)
  }

  /** What's left of today's allowance. */
  data class Limits(val newLeft: Int, val reviewsLeft: Int) {
    companion object {
      fun of(prefs: ReviewPrefs, today: HistoryStore.DailyCounts) = Limits(
        newLeft = (prefs.newPerDay - today.newCards).coerceAtLeast(0),
        reviewsLeft = (prefs.reviewsPerDay - today.reviews).coerceAtLeast(0),
      )
    }
  }

  /** When the next card not yet due comes up, or null when none is waiting. */
  fun nextDue(now: Long): Long? = byKey.keys.mapNotNull { cards[it]?.due }.filter { it > now }.minOrNull()

  companion object {
    /** Cards graded again within this long stay in the session instead of waiting for the next one. */
    const val LEARN_AHEAD_MS = 20 * Fsrs.MINUTE
  }
}

val HistoryEntry.reviewable: Boolean
  get() = answer != null && (kind == HistoryKind.Fill || (kind == HistoryKind.Word && meaning != null))

/** The front of the card: the English asked about, or a saved word's meaning. */
val HistoryEntry.reviewPrompt: String
  get() = if (kind == HistoryKind.Word) meaning.orEmpty() else source
