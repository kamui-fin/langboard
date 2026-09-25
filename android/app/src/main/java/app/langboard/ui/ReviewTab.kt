package app.langboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.langboard.R
import app.langboard.history.Insights
import app.langboard.history.reviewPrompt

/**
 * Practice what you struggled to say: what's due today, what's in it, and a way in. The session
 * itself is [ReviewScreen].
 */
@Composable
fun ReviewTab(nav: Navigator, modifier: Modifier = Modifier) {
  val snap = rememberSnapshot()
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
    ScreenTitle("Review", "What you struggled to say, back just before you'd forget it.")
    if (snap == null) return@Column
    if (snap.nothingToLearn) {
      EmptyState(
        R.drawable.ic_cards,
        "Nothing to practice yet",
        "When you get stuck in a chat and Langboard helps, that phrase comes back here, in the sentence you were writing.",
      )
      return@Column
    }
    val ready = snap.session.isNotEmpty()
    Panel(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(if (ready) "Today" else "You're caught up", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(4.dp))
          Muted(
            when {
              ready -> "${plural(snap.session.size, "phrase")} · about ${Insights.minutesFor(snap.session.size)} min"
              snap.newWaitingTomorrow -> "More from your chats tomorrow."
              snap.nextDue != null -> "Next review ${dueIn(snap.nextDue - snap.now)}."
              else -> "Nothing waiting."
            },
            MaterialTheme.typography.bodyMedium,
          )
        }
        if (ready) {
          Spacer(Modifier.width(16.dp))
          Button(onClick = { nav.open(Page.ReviewSession) }) { Text("Start") }
        }
      }
    }

    if (ready) {
      SectionLabel("Coming up", Modifier.padding(horizontal = ScreenGutter).padding(top = 32.dp, bottom = 4.dp))
      snap.session.take(5).forEach { item ->
        // The English only: showing the Chinese here would give the answer away.
        Text(
          "“${item.entry.reviewPrompt}”",
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 10.dp),
        )
      }
      if (snap.session.size > 5) {
        Muted("and ${snap.session.size - 5} more", MaterialTheme.typography.bodyMedium, Modifier.padding(horizontal = ScreenGutter, vertical = 6.dp))
      }
    }
  }
}
