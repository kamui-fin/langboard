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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import app.langboard.history.ReviewDeck
import app.langboard.core.LangboardSettings
import app.langboard.history.reviewable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlinx.coroutines.launch

private val Gutter = 24.dp

/**
 * Everything Langboard answered, newest first and grouped by day, with whether it was used. One
 * entry opens its page; Review walks through phrases English-first to see what stuck.
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }
  val version by store.version.collectAsStateWithLifecycle()
  var filter by rememberSaveable { mutableStateOf(HistoryStore.Filter.All) }
  var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
  var openId by rememberSaveable { mutableStateOf<Long?>(null) }
  var reviewing by rememberSaveable { mutableStateOf(false) }
  var deck by remember { mutableStateOf<Pair<ReviewDeck, ReviewDeck.Limits>?>(null) }
  LaunchedEffect(version, filter) { entries = store.list(filter) }
  LaunchedEffect(version, reviewing) {
    val prefs = LangboardSettings(context).review
    val today = store.reviewedSince(prefs.dayStart(System.currentTimeMillis()))
    deck = ReviewDeck(store.list(), store.reviewCards()) to ReviewDeck.Limits.of(prefs, today)
  }

  val open = openId?.let { id -> entries?.firstOrNull { it.id == id } }
  AnimatedContent(
    targetState = when {
      reviewing -> "review"
      open != null -> "detail"
      else -> "list"
    },
    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
    label = "history",
    modifier = modifier.fillMaxSize(),
  ) { screen ->
    when (screen) {
      "review" -> {
        BackHandler { reviewing = false }
        ReviewScreen(onClose = { reviewing = false })
      }
      "detail" -> open?.let {
        BackHandler { openId = null }
        HistoryDetail(it, onBack = { openId = null })
      }
      else -> HistoryList(entries, filter, onFilter = { filter = it }, onOpen = { openId = it.id }, deck = deck, onReview = { reviewing = true })
    }
  }
}

@Composable
private fun HistoryList(
  entries: List<HistoryEntry>?,
  filter: HistoryStore.Filter,
  onFilter: (HistoryStore.Filter) -> Unit,
  onOpen: (HistoryEntry) -> Unit,
  deck: Pair<ReviewDeck, ReviewDeck.Limits>?,
  onReview: () -> Unit,
) {
  val days = remember(entries) { entries.orEmpty().groupBy { dayOf(it.createdAt) } }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
    item {
      Column(Modifier.padding(horizontal = Gutter).padding(top = 24.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Muted("Everything you asked Langboard, on this phone.", MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(HistoryStore.Filter.All to "All", HistoryStore.Filter.Used to "Used", HistoryStore.Filter.Saved to "Saved").forEach { (f, label) ->
            FilterChip(
              selected = filter == f,
              onClick = { onFilter(f) },
              label = { Text(label) },
              colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            )
          }
        }
      }
    }
    if (filter == HistoryStore.Filter.All && deck != null && entries.orEmpty().any { it.reviewable }) {
      item { ReviewCard(deck.first, deck.second, onReview) }
    }
    when {
      entries == null -> Unit
      entries.isEmpty() -> item { EmptyHistory(filter) }
      else -> days.forEach { (day, list) ->
        item(key = "day-$day") {
          Text(
            dayLabel(day),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Gutter).padding(top = 32.dp, bottom = 4.dp),
          )
        }
        items(list, key = { it.id }) { e -> HistoryRow(e) { onOpen(e) } }
      }
    }
  }
}

/** What's due, and a way in; when nothing is, when the next card comes up. */
@Composable
private fun ReviewCard(deck: ReviewDeck, limits: ReviewDeck.Limits, onStart: () -> Unit) {
  val now = remember(deck) { System.currentTimeMillis() }
  val session = remember(deck, limits) { deck.session(now, limits) }
  val new = session.count { it.card.lastReview == null }
  val due = session.size - new
  val next = remember(deck) { deck.nextDue(now) }
  val ready = due + new > 0
  Surface(
    shape = RoundedCornerShape(24.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    onClick = onStart,
    enabled = ready,
    modifier = Modifier.padding(horizontal = Gutter).padding(top = 24.dp).fillMaxWidth(),
  ) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Review", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Muted(
          when {
            ready -> listOfNotNull(due.takeIf { it > 0 }?.let { "$it due" }, new.takeIf { it > 0 }?.let { "$it new" }).joinToString(" · ")
            limits.newLeft == 0 && deck.new(now).isNotEmpty() -> "Done for today · more new phrases tomorrow"
            next != null -> "All caught up · next ${dueLabel(next - now)}"
            else -> "All caught up"
          },
          MaterialTheme.typography.bodyMedium,
        )
      }
      if (ready) {
        Spacer(Modifier.width(16.dp))
        Button(onClick = onStart) { Text("Start") }
      }
    }
  }
}

private fun dueLabel(ms: Long): String {
  val hours = ms / 3_600_000
  return when {
    hours < 1 -> "within the hour"
    hours < 24 -> "in ${hours}h"
    hours < 48 -> "tomorrow"
    else -> "in ${hours / 24} days"
  }
}

@Composable
private fun EmptyHistory(filter: HistoryStore.Filter) {
  Column(
    Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 72.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(if (filter == HistoryStore.Filter.Saved) R.drawable.ic_star_border else R.drawable.ic_history),
        contentDescription = null,
      )
    }
    Spacer(Modifier.height(20.dp))
    Text(
      when (filter) {
        HistoryStore.Filter.All -> "Nothing yet"
        HistoryStore.Filter.Used -> "Nothing used yet"
        HistoryStore.Filter.Saved -> "No saved phrases"
      },
      style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Muted(
      when (filter) {
        HistoryStore.Filter.All -> "Each time you switch to Langboard, what it answered shows up here."
        HistoryStore.Filter.Used -> "Answers you insert into a message show up here."
        HistoryStore.Filter.Saved -> "Tap ☆ on an answer in the keyboard to keep it here."
      },
      MaterialTheme.typography.bodyMedium,
      center = true,
    )
  }
}

/** One lookup: what was asked above, the Chinese below, then how it went. */
@Composable
private fun HistoryRow(e: HistoryEntry, onClick: () -> Unit) {
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

@Composable
private fun HistoryDetail(e: HistoryEntry, onBack: () -> Unit) {
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }
  val scope = rememberCoroutineScope()
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
      Spacer(Modifier.weight(1f))
      IconButton(onClick = { scope.launch { store.setSaved(e.id, !e.saved) } }) {
        Icon(
          painterResource(if (e.saved) R.drawable.ic_star else R.drawable.ic_star_border),
          contentDescription = if (e.saved) "Remove from saved" else "Save",
        )
      }
      IconButton(onClick = { scope.launch { store.delete(e.id); onBack() } }) {
        Icon(Icons.Filled.Delete, contentDescription = "Delete")
      }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = 40.dp)) {
      Muted(
        listOfNotNull(e.kindLabel, fullDate(context, e.createdAt), appName(e.app)).joinToString("  ·  "),
        MaterialTheme.typography.labelLarge,
      )
      Spacer(Modifier.height(20.dp))
      when (e.kind) {
        HistoryKind.Fill -> FillDetail(e)
        HistoryKind.Check -> CheckDetail(e)
        HistoryKind.Explain -> ExplainDetail(e)
        HistoryKind.Word -> WordDetail(e)
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
private fun Hero(text: String, pinyin: String?, meaning: String?, size: Int = 40) {
  Text(text, fontSize = size.sp, lineHeight = (size + 10).sp)
  pinyin?.let { Spacer(Modifier.height(6.dp)); Muted(it, MaterialTheme.typography.titleMedium) }
  meaning?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun CandidateList(items: List<Candidate>) {
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
private fun Section(title: String, top: androidx.compose.ui.unit.Dp = 36.dp, content: @Composable () -> Unit) {
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

private val KnownApps = mapOf(
  "com.tencent.mm" to "WeChat",
  "com.whatsapp" to "WhatsApp",
  "com.google.android.apps.messaging" to "Messages",
  "com.android.chrome" to "Chrome",
  "org.telegram.messenger" to "Telegram",
  "com.discord" to "Discord",
  "com.instagram.android" to "Instagram",
  "jp.naver.line.android" to "LINE",
  "com.xingin.xhs" to "Xiaohongshu",
  "com.ss.android.ugc.aweme" to "Douyin",
  "com.sina.weibo" to "Weibo",
  "com.tencent.mobileqq" to "QQ",
  "app.langboard" to "Langboard",
)

private fun appName(pkg: String?): String? = pkg?.let { KnownApps[it] }

private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun dayLabel(day: LocalDate): String {
  val today = LocalDate.now()
  return when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"))
  }
}

private fun timeOf(context: android.content.Context, ms: Long): String =
  android.text.format.DateFormat.getTimeFormat(context).format(Date(ms))

private fun fullDate(context: android.content.Context, ms: Long): String {
  val day = dayOf(ms)
  return "${dayLabel(day)}, ${timeOf(context, ms)}"
}

@Composable
private fun Muted(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  center: Boolean = false,
) {
  Text(
    text,
    style = style,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    textAlign = if (center) TextAlign.Center else null,
    modifier = modifier,
  )
}
