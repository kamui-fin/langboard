package app.langboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.langboard.R
import app.langboard.history.Outcome
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import app.langboard.history.HistoryKind
import app.langboard.history.HistoryStore
import app.langboard.history.Strength
import app.langboard.history.reviewable
import kotlinx.coroutines.launch

/**
 * One expression, with everything that makes it yours: the moment you learned it, the sentence
 * you used it in, other ways to say it, how often you needed it and how well you hold it now.
 */
@Composable
fun ExpressionScreen(key: String, nav: Navigator, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }
  val scope = rememberCoroutineScope()
  val snap = rememberSnapshot() ?: return
  val e = remember(snap, key) { snap.insights.expressions.firstOrNull { it.key == key } }
  // Deleted, or its last moment went: nothing left to show.
  LaunchedEffect(e == null) { if (e == null) nav.back() }
  if (e == null) return
  var confirmForget by remember { mutableStateOf(false) }
  val best = e.best

  Column(modifier.fillMaxSize()) {
    PageBar(onBack = nav::back) {
      IconButton(onClick = {
        scope.launch {
          if (e.saved) e.entries.filter { it.saved }.forEach { store.setSaved(it.id, false) }
          else store.setSaved(best.id, true)
        }
      }) {
        Icon(
          painterResource(if (e.saved) R.drawable.ic_star else R.drawable.ic_star_border),
          contentDescription = if (e.saved) "Remove from saved" else "Save",
        )
      }
      IconButton(onClick = { confirmForget = true }) { Icon(Icons.Filled.Delete, contentDescription = "Forget") }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(bottom = 48.dp)) {
      Hero(e.chinese, best.pinyin, best.meaning.takeIf { best.kind != HistoryKind.Word }, size = 44)
      if (best.kind == HistoryKind.Word) best.meaning?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodyLarge) }

      when (best.kind) {
        HistoryKind.Explain -> Section("From a message you got") {
          Text(best.source, fontSize = 20.sp, lineHeight = 30.sp)
          best.sentence?.let { Spacer(Modifier.height(6.dp)); Muted(it, MaterialTheme.typography.bodyLarge) }
        }
        else -> Section("You learned this trying to say") {
          Text("“${e.meaning}”", style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.height(4.dp))
          Muted(listOfNotNull(shortDay(e.firstAt), appName(best.app)).joinToString(" · "), MaterialTheme.typography.bodyMedium)
        }
      }

      best.sentence?.takeIf { best.kind == HistoryKind.Fill && it != e.chinese }?.let { s ->
        Section("In your message") {
          val i = s.indexOf(e.chinese)
          RubyText(s, fontSize = 22.sp, highlight = if (i >= 0) listOf(i until i + e.chinese.length) else emptyList())
        }
      }

      val others = remember(e) {
        e.entries.flatMap { it.items }.filter { it.text != e.chinese && it.text.isNotBlank() }.distinctBy { it.text }.take(3)
      }
      if (others.isNotEmpty() && best.kind != HistoryKind.Check) {
        Section(if (best.kind == HistoryKind.Explain) "Also in that message" else "Other ways to say it") {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            others.forEach { c ->
              Text(c.text, fontSize = 20.sp, lineHeight = 28.sp)
              (c.nuance ?: c.meaning)?.let { Muted(it, MaterialTheme.typography.bodyMedium) }
            }
          }
        }
      }

      Section("Progress") {
        StrengthMeter(e.strength)
        Spacer(Modifier.height(8.dp))
        Text(
          listOfNotNull(
            when {
              !best.reviewable -> "Not in review."
              e.strength == Strength.New -> "Waiting in Review."
              else -> e.card?.due?.let { d -> if (d > snap.now) "Next practice ${dueIn(d - snap.now)}." else "Due now." }
            },
            "Needed ${times(e.occasions)}" + (if (e.timesUsed > 0) ", sent ${times(e.timesUsed)}." else "."),
          ).joinToString(" "),
          style = MaterialTheme.typography.bodyLarge,
        )
      }

      Section("Each time you needed it") {
        // Reopening the panel on the same draft makes a lookup twice; show it once.
        e.entries.distinctBy { it.sentence ?: it.source }.forEachIndexed { i, m ->
          if (i > 0) Spacer(Modifier.height(4.dp))
          Surface(onClick = { nav.open(Page.Moment(m.id)) }, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
              Muted(
                listOfNotNull(fullDate(context, m.createdAt), appName(m.app), if (m.outcome == Outcome.Inserted) "sent" else null).joinToString(" · "),
                MaterialTheme.typography.labelMedium,
              )
              Spacer(Modifier.height(2.dp))
              Text(m.sentence ?: m.source, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
  if (confirmForget) {
    AlertDialog(
      onDismissRequest = { confirmForget = false },
      title = { Text("Forget ${e.chinese}?") },
      text = { Text("This deletes ${plural(e.entries.size, "lookup")} with it, and it won't come up in Review again. It can't be undone.") },
      confirmButton = {
        TextButton(onClick = {
          confirmForget = false
          scope.launch { e.entries.forEach { store.delete(it.id) } }
        }) { Text("Forget") }
      },
      dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
    )
  }
}

private fun times(n: Int) = when (n) { 1 -> "once"; 2 -> "twice"; else -> "$n times" }
