package app.langboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.core.LangboardSettings
import app.langboard.history.HistoryEntry
import app.langboard.history.HistoryStore
import app.langboard.history.Insights
import app.langboard.history.ReviewDeck
import app.langboard.history.ReviewItem

/** Everything Home, Review, Memory and You show, read once per change to the store. */
class Snapshot(
  val now: Long,
  val entries: List<HistoryEntry>,
  val insights: Insights,
  val deck: ReviewDeck,
  val limits: ReviewDeck.Limits,
) {
  /** Today's session, within the daily limits. */
  val session: List<ReviewItem> = deck.session(now, limits)
  val nextDue: Long? = deck.nextDue(now)
  /** Nothing has ever been looked up that could become a card. */
  val nothingToLearn: Boolean = deck.new(now).isEmpty() && nextDue == null && deck.due(now).isEmpty()
  /** New cards are waiting but today's allowance is spent. */
  val newWaitingTomorrow: Boolean = limits.newLeft == 0 && deck.new(now).isNotEmpty()
}

/** Reloads whenever the history store changes; null until the first load. */
@Composable
fun rememberSnapshot(): Snapshot? {
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }
  val version by store.version.collectAsStateWithLifecycle()
  var snapshot by remember { mutableStateOf<Snapshot?>(null) }
  LaunchedEffect(version) {
    val now = System.currentTimeMillis()
    val prefs = LangboardSettings(context).review
    val entries = store.list(limit = 5000)
    val cards = store.reviewCards()
    val today = store.reviewedSince(prefs.dayStart(now))
    snapshot = Snapshot(
      now = now,
      entries = entries,
      insights = Insights(entries, cards, store.reviewRecords()),
      deck = ReviewDeck(entries, cards),
      limits = ReviewDeck.Limits.of(prefs, today),
    )
  }
  return snapshot
}
