package app.langboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.history.Expression
import app.langboard.history.Insights
import app.langboard.model.ModelManager
import app.langboard.ui.theme.LocalAccent

/**
 * What Langboard thinks matters today, taken from the user's own conversations: what's worth
 * reviewing, what keeps coming up, what they picked up this week and what they can now say.
 */
@Composable
fun HomeScreen(nav: Navigator, onReviewTab: () -> Unit, modifier: Modifier = Modifier) {
  val snap = rememberSnapshot()
  val status = rememberKeyboardStatus()
  val model by ModelManager.state.collectAsStateWithLifecycle()

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
    ScreenTitle(greeting())

    if (!status.ready) SetupCard(status)
    if (!model.installed) {
      SettingsGroup(label = null) { ChineseModelCard(Modifier.padding(20.dp)) }
    }
    if (snap == null) return@Column

    val i = snap.insights
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      if (snap.nothingToLearn) {
        HowItWorks()
      } else {
        ReviewHero(snap, onStart = { nav.open(Page.ReviewSession) }, onMore = onReviewTab)
      }
      LabEntry { nav.open(Page.Lab) }
    }

    if (i.recurring.isNotEmpty()) {
      HomeSection("Keeps coming up")
      i.recurring.take(3).forEach { e ->
        ExpressionRow(e, onClick = { nav.open(Page.Expression(e.key)) }, detail = "${e.occasions}×")
      }
    }

    val week = remember(snap) { i.week(snap.now) }
    if (week.moments > 0) {
      HomeSection("This week")
      WeekPanel(week, onOpen = { nav.open(Page.Expression(it.key)) })
    }

    if (i.canSay.isNotEmpty()) {
      HomeSection("You can say this now")
      i.canSay.take(3).forEach { e -> CanSayRow(e) { nav.open(Page.Expression(e.key)) } }
    }
  }
}

@Composable
private fun HomeSection(title: String) {
  Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = ScreenGutter).padding(top = 36.dp, bottom = 4.dp))
}

/** The one strong block on the screen: what's worth reviewing, and a way in. */
@Composable
private fun ReviewHero(snap: Snapshot, onStart: () -> Unit, onMore: () -> Unit) {
  val count = snap.session.size
  val ready = count > 0
  // Ink on paper in light mode; in dark mode a white slab glares, so it's a raised dark surface.
  val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val heroColor = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.inverseSurface
  val heroContent = if (dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.inverseOnSurface
  Surface(
    shape = PanelShape,
    color = if (ready) heroColor else MaterialTheme.colorScheme.surfaceContainer,
    contentColor = if (ready) heroContent else MaterialTheme.colorScheme.onSurface,
    onClick = if (ready) onStart else onMore,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(24.dp)) {
      if (ready) {
        Text(
          if (count == 1) "1 thing worth reviewing" else "$count things worth reviewing",
          style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "From your own chats · about ${Insights.minutesFor(count)} min",
          style = MaterialTheme.typography.bodyMedium,
          color = heroContent.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(20.dp))
        Button(
          onClick = onStart,
          colors = ButtonDefaults.buttonColors(
            containerColor = heroContent,
            contentColor = heroColor,
          ),
        ) { Text("Review now") }
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp)) }
          }
          Spacer(Modifier.width(16.dp))
          Column(Modifier.weight(1f)) {
            Text("You're caught up", style = MaterialTheme.typography.titleMedium)
            Muted(
              when {
                snap.newWaitingTomorrow -> "More from your chats tomorrow."
                snap.nextDue != null -> "Next review ${dueIn(snap.nextDue - snap.now)}."
                else -> "Nothing waiting."
              },
              MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }
    }
  }
}

/** A way into the Expression Lab: one quiet row. */
@Composable
private fun LabEntry(onClick: () -> Unit) {
  Surface(onClick = onClick, shape = PanelShape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("What are you trying to say?", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekPanel(week: Insights.Week, onOpen: (Expression) -> Unit) {
  Panel(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
    Row {
      Stat("${week.moments}", "asked", Modifier.weight(1f))
      Stat("${week.used}", "sent", Modifier.weight(1f))
      Stat("${week.newExpressions.size}", "new phrases", Modifier.weight(1f))
    }
    if (week.newExpressions.isNotEmpty()) {
      Spacer(Modifier.height(18.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        week.newExpressions.take(6).forEach { e ->
          Surface(
            onClick = { onOpen(e) },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
          ) {
            Text(
              e.chinese,
              fontSize = 16.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CanSayRow(e: Expression, onClick: () -> Unit) {
  Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Jade: a learned state, the one place Home uses the brand color.
      Icon(Icons.Filled.Check, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text("“${e.meaning}”", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Muted(e.chinese, MaterialTheme.typography.bodyMedium, maxLines = 1)
      }
    }
  }
}

/** For someone with nothing looked up yet: the loop, in three steps. */
@Composable
private fun HowItWorks() {
  Panel {
    Text("How Langboard works", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
    listOf(
      "Get unstuck" to "Typing in Chinese and missing a phrase? Write it in English, switch to Langboard, tap the best fit.",
      "Learn from it" to "What you struggled to say becomes practice here, with the sentence you were writing.",
      "Need less help" to "Review brings each phrase back just before you'd forget it.",
    ).forEachIndexed { n, (title, body) ->
      Row(Modifier.padding(bottom = if (n < 2) 16.dp else 0.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(28.dp)) {
          Box(contentAlignment = Alignment.Center) { Text("${n + 1}", style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
          Text(title, style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(2.dp))
          Muted(body, MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

internal fun dueIn(ms: Long): String {
  val hours = ms / 3_600_000
  return when {
    hours < 1 -> "within the hour"
    hours < 24 -> "in ${hours}h"
    hours < 48 -> "tomorrow"
    else -> "in ${hours / 24} days"
  }
}
