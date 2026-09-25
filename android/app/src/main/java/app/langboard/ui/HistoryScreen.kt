package app.langboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.R
import app.langboard.core.Candidate
import app.langboard.history.HistoryEntry
import app.langboard.history.HistoryKind
import app.langboard.history.HistoryStore
import app.langboard.history.Outcome
import app.langboard.history.Insights
import app.langboard.ui.theme.LocalReadingPrefs
import androidx.compose.material3.OutlinedButton
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.launch

private val Gutter = ScreenGutter

/** The day over a run of moments. */
@Composable
internal fun DayHeader(day: java.time.LocalDate) {
  Text(
    dayLabel(day),
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = Gutter).padding(top = 24.dp, bottom = 4.dp),
  )
}

/** One lookup: what was asked above, the Chinese below, then how it went. */
@Composable
internal fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
  val context = LocalContext.current
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = Gutter, vertical = 16.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Column(Modifier.weight(1f)) {
      Muted(e.prompt, MaterialTheme.typography.bodyMedium, maxLines = 1)
      Spacer(Modifier.height(4.dp))
      Text(
        e.headline,
        fontSize = if (e.answer != null) 22.sp else 18.sp,
        lineHeight = 30.sp,
        color = if (e.answer != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(8.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(e.outcome)
        Spacer(Modifier.width(8.dp))
        Muted(listOfNotNull(e.statusLabel, appName(e.app)).joinToString("  ·  "), MaterialTheme.typography.labelMedium)
      }
    }
    Spacer(Modifier.width(16.dp))
    Column(horizontalAlignment = Alignment.End) {
      Muted(timeOf(context, e.createdAt), MaterialTheme.typography.labelMedium)
      if (e.saved) {
        Spacer(Modifier.height(10.dp))
        Icon(painterResource(R.drawable.ic_star), contentDescription = "Saved", modifier = Modifier.size(18.dp))
      }
    }
  }
}

@Composable
private fun StatusDot(o: Outcome) {
  val filled = o == Outcome.Inserted
  Box(
    Modifier
      .size(8.dp)
      .background(
        if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        CircleShape,
      )
  )
}

// ---------------------------------------------------------------- Detail

/** One moment: what you asked, what Langboard answered, what you did with it. */
@Composable
fun MomentScreen(id: Long, nav: Navigator, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }
  val version by store.version.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  var e by remember { mutableStateOf<HistoryEntry?>(null) }
  var gone by remember { mutableStateOf(false) }
  LaunchedEffect(version) { e = store.get(id).also { if (it == null) gone = true } }
  LaunchedEffect(gone) { if (gone) nav.back() }
  val entry = e ?: return
  Column(modifier.fillMaxSize()) {
    PageBar(onBack = nav::back) {
      IconButton(onClick = { scope.launch { store.setSaved(entry.id, !entry.saved) } }) {
        Icon(
          painterResource(if (entry.saved) R.drawable.ic_star else R.drawable.ic_star_border),
          contentDescription = if (entry.saved) "Remove from saved" else "Save",
        )
      }
      IconButton(onClick = { scope.launch { store.delete(entry.id) } }) {
        Icon(Icons.Filled.Delete, contentDescription = "Delete")
      }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 40.dp)) {
      Muted(
        listOfNotNull(entry.kindLabel, fullDate(context, entry.createdAt), appName(entry.app)).joinToString("  ·  "),
        MaterialTheme.typography.labelLarge,
      )
      Spacer(Modifier.height(20.dp))
      when (entry.kind) {
        HistoryKind.Fill -> FillDetail(entry)
        HistoryKind.Check -> CheckDetail(entry)
        HistoryKind.Explain -> ExplainDetail(entry)
        HistoryKind.Word -> WordDetail(entry)
      }
      if (entry.kind != HistoryKind.Check && entry.answer != null && Insights.hasChinese(entry.answer)) {
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = { nav.open(Page.Expression(HistoryStore.reviewKey(entry))) }) {
          Text("Everything about ${entry.answer}")
        }
      }
    }
  }
}

@Composable
private fun FillDetail(e: HistoryEntry) {
  Text("“${e.source}”", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
  Spacer(Modifier.height(24.dp))
  if (e.answer == null) {
    Text("No answer yet", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Muted("Langboard didn't have a natural phrase for this.", MaterialTheme.typography.bodyLarge)
    return
  }
  Hero(e.answer, e.pinyin, e.meaning)
  Section("Status") { Text(statusSentence(e), style = MaterialTheme.typography.bodyLarge) }
  e.sentence?.let { s ->
    Section("In your message") {
      val used = e.used ?: e.answer
      Text(
        buildAnnotatedString {
          append(s)
          val i = s.indexOf(used)
          if (i >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), i, i + used.length)
        },
        fontSize = 20.sp,
        lineHeight = 30.sp,
      )
    }
  }
  if (e.items.isNotEmpty()) Section("Other ways to say it") { CandidateList(e.items) }
  if (e.contextMessages > 0 || e.register != null) {
    Section("Context") {
      Text(
        listOfNotNull(
          e.register?.replaceFirstChar { it.uppercase() }?.let { "$it register" },
          e.contextMessages.takeIf { it > 0 }?.let { "$it nearby messages read" },
        ).joinToString("  ·  "),
        style = MaterialTheme.typography.bodyLarge,
      )
      Spacer(Modifier.height(4.dp))
      Muted("The screen text itself was never saved.", MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun CheckDetail(e: HistoryEntry) {
  Section("You wrote", top = 0.dp) { Text(e.source, fontSize = 22.sp, lineHeight = 32.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
  e.answer?.let { Section("More natural") { Text(it, fontSize = 26.sp, lineHeight = 36.sp) } }
  Section("Status") { Text(statusSentence(e), style = MaterialTheme.typography.bodyLarge) }
  if (e.items.isNotEmpty()) {
    Section("Changes") {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        e.items.forEach { c ->
          Column {
            Text("${c.nuance.orEmpty()}  →  ${c.text}", fontSize = 20.sp)
            c.meaning?.let { Spacer(Modifier.height(2.dp)); Muted(it, MaterialTheme.typography.bodyMedium) }
          }
        }
      }
    }
  }
}

@Composable
private fun ExplainDetail(e: HistoryEntry) {
  Text(e.source, fontSize = 26.sp, lineHeight = 36.sp)
  e.sentence?.let { Spacer(Modifier.height(10.dp)); Muted(it, MaterialTheme.typography.titleMedium) }
  e.answer?.let {
    Section("Key expression") { Hero(it, e.pinyin, e.meaning, size = 32) }
  }
  if (e.items.isNotEmpty()) Section("Also in it") { CandidateList(e.items) }
}

@Composable
private fun WordDetail(e: HistoryEntry) {
  Hero(e.answer ?: e.source, e.pinyin, e.meaning, size = 44)
  e.sentence?.let { Section("Here") { Text("“$it”", style = MaterialTheme.typography.bodyLarge) } }
  if (e.source != e.answer) Section("Found in") { Text(e.source, fontSize = 20.sp, lineHeight = 30.sp) }
}

@Composable
internal fun Hero(text: String, pinyin: String?, meaning: String?, size: Int = 40) {
  // Pinyin comes from RubyText when the user has it on; the stored reading only when they don't.
  RubyText(text, fontSize = size.sp)
  if (!LocalReadingPrefs.current.pinyin) pinyin?.let { Spacer(Modifier.height(6.dp)); Muted(it, MaterialTheme.typography.titleMedium) }
  meaning?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
internal fun CandidateList(items: List<Candidate>) {
  Column {
    items.forEachIndexed { i, c ->
      if (i > 0) HorizontalDivider(Modifier.padding(vertical = 14.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
      Text(c.text, fontSize = 22.sp, lineHeight = 30.sp)
      c.pinyin?.let { Muted(it, MaterialTheme.typography.bodyMedium) }
      (c.nuance ?: c.meaning)?.let { Spacer(Modifier.height(4.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
  }
}

@Composable
internal fun Section(title: String, top: androidx.compose.ui.unit.Dp = 36.dp, content: @Composable () -> Unit) {
  Spacer(Modifier.height(top))
  Text(
    title.uppercase(),
    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.Medium),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Spacer(Modifier.height(10.dp))
  content()
}

// ---------------------------------------------------------------- labels

/** The small line above the Chinese: what the user asked. */
private val HistoryEntry.prompt: String
  get() = when (kind) {
    HistoryKind.Fill -> source
    HistoryKind.Check -> "Your Chinese"
    HistoryKind.Explain -> source
    HistoryKind.Word -> "Saved word"
  }

private val HistoryEntry.headline: String
  get() = when (kind) {
    HistoryKind.Fill -> answer ?: "No answer yet"
    HistoryKind.Check -> answer ?: source
    HistoryKind.Explain -> listOfNotNull(answer, sentence?.takeIf { answer == null }).firstOrNull() ?: "Explained"
    HistoryKind.Word -> listOfNotNull(answer, pinyin).joinToString("  ")
  }

private val HistoryEntry.kindLabel: String
  get() = when (kind) {
    HistoryKind.Fill -> "Said in Chinese"
    HistoryKind.Check -> "Checked"
    HistoryKind.Explain -> "Message explained"
    HistoryKind.Word -> "Saved word"
  }

private val HistoryEntry.statusLabel: String
  get() = when {
    kind == HistoryKind.Explain -> "Explained"
    kind == HistoryKind.Word -> "Saved"
    else -> when (outcome) {
      Outcome.Inserted -> "Used"
      Outcome.Undone -> "Undone"
      Outcome.NoAnswer -> "No answer"
      Outcome.Shown -> "Looked up"
    }
  }

private fun statusSentence(e: HistoryEntry): String = when (e.outcome) {
  Outcome.Inserted -> if (e.used != null && e.used != e.answer) "You used “${e.used}” in your message." else "You used it in your message."
  Outcome.Undone -> "You inserted it, then undid it."
  Outcome.NoAnswer -> "Langboard had no answer."
  Outcome.Shown -> "You looked it up but didn't insert it."
}
