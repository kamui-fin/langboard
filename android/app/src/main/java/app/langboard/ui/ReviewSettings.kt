package app.langboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.core.FsrsOptimizer
import app.langboard.core.LangboardSettings
import app.langboard.core.ReviewPrefs
import app.langboard.history.HistoryStore
import app.langboard.history.ReviewTuner
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Review scheduling. Three settings up front that people actually change; the rest of what FSRS
 * offers (steps, maximum interval, day start, tuning) under Advanced. Every default is chosen so
 * that nobody has to open it.
 */
@Composable
fun ReviewSettings(settings: LangboardSettings) {
  var prefs by remember { mutableStateOf(settings.review) }
  var advanced by remember { mutableStateOf(false) }
  fun update(p: ReviewPrefs) { prefs = p; settings.review = p }

  SettingsGroup(
    "Review",
    footer = "Langboard schedules each phrase with FSRS, the scheduler Anki uses, for just before you'd forget it.",
  ) {
    StepperRow(
      "New phrases per day", null,
      value = prefs.newPerDay, choices = NEW_PER_DAY, label = { "$it" },
    ) { update(prefs.copy(newPerDay = it)) }
    GroupDivider()
    StepperRow(
      "Reviews per day", "Phrases you've learned, coming back",
      value = prefs.reviewsPerDay, choices = REVIEWS_PER_DAY, label = { if (it >= NO_LIMIT) "No limit" else "$it" },
    ) { update(prefs.copy(reviewsPerDay = it)) }
    GroupDivider()
    RetentionRow(prefs.retention) { update(prefs.copy(retention = it)) }
    GroupDivider()
    Row(
      Modifier.fillMaxWidth().clickable { advanced = !advanced }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Advanced", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Icon(
        Icons.Filled.KeyboardArrowDown,
        contentDescription = if (advanced) "Hide advanced settings" else "Show advanced settings",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.rotate(if (advanced) 180f else 0f),
      )
    }
    AnimatedVisibility(advanced, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      Column {
        GroupDivider()
        StepsRow("Learning steps", "A new phrase's first few reviews, before FSRS takes over", prefs.learningSteps) {
          update(prefs.copy(learningSteps = it))
        }
        GroupDivider()
        StepsRow("Relearning steps", "After you forget a phrase", prefs.relearningSteps) { update(prefs.copy(relearningSteps = it)) }
        GroupDivider()
        StepperRow(
          "Longest gap", "The most time between two reviews of a phrase",
          value = prefs.maxIntervalDays, choices = MAX_INTERVAL, label = ::gapLabel,
        ) { update(prefs.copy(maxIntervalDays = it)) }
        GroupDivider()
        StepperRow(
          "New day starts at", "Late-night reviews count towards the day before",
          value = prefs.dayStartHour, choices = (0..23).toList(), label = ::hourLabel,
        ) { update(prefs.copy(dayStartHour = it)) }
        GroupDivider()
        TuningRows(prefs, ::update, reload = { prefs = settings.review })
        GroupDivider()
        TextButton(
          onClick = { update(ReviewPrefs(params = prefs.params, tunedAt = prefs.tunedAt, tunedOn = prefs.tunedOn)) },
          enabled = prefs.copy(params = null, tunedAt = null, tunedOn = 0) != ReviewPrefs(),
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        ) { Text("Restore the default settings") }
      }
    }
  }
}

/** Tuning FSRS to the user: on by itself, with what it's based on and a way to run or undo it. */
@Composable
private fun TuningRows(prefs: ReviewPrefs, update: (ReviewPrefs) -> Unit, reload: () -> Unit) {
  val context = LocalContext.current
  val status by ReviewTuner.status.collectAsStateWithLifecycle()
  var scored by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(status) {
    scored = withContext(Dispatchers.Default) { FsrsOptimizer(HistoryStore.get(context).reviewRecords()).scoredReviews }
    if (status is ReviewTuner.Status.Done) reload()
  }

  SwitchRow(
    "Tune to my memory",
    "Fits FSRS to your own reviews once there are enough of them, and again as they grow",
    prefs.autoTune,
  ) { update(prefs.copy(autoTune = it)) }
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val needed = FsrsOptimizer.MINI_BATCH
    val running = status as? ReviewTuner.Status.Running
    Text(
      when {
        running != null -> "Tuning…"
        prefs.params != null && prefs.tunedAt != null ->
          "Tuned ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(prefs.tunedAt))}, from ${prefs.tunedOn} reviews."
        status is ReviewTuner.Status.Done -> "Your reviews fit the defaults best, so they're kept."
        scored != null && scored!! < needed -> "Using FSRS's defaults until you've done $needed reviews (${scored} so far)."
        else -> "Using FSRS's defaults."
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (running != null) LinearProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalButton(
        onClick = { ReviewTuner.maybeTune(context, force = true) },
        enabled = running == null && (scored ?: 0) >= needed,
      ) { Text("Tune now") }
      if (prefs.params != null) {
        TextButton(onClick = { update(prefs.copy(params = null, tunedAt = null, tunedOn = 0)) }) { Text("Use defaults") }
      }
    }
  }
}

/** How much to remember, as a percentage, with what moving it costs. */
@Composable
private fun RetentionRow(retention: Double, onChange: (Double) -> Unit) {
  var value by remember(retention) { mutableStateOf(retention.toFloat()) }
  val percent = (value * 100).roundToInt()
  Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Remember", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text("$percent%", style = MaterialTheme.typography.titleMedium)
    }
    Spacer(Modifier.size(2.dp))
    Text(
      "Phrases come back when there's a $percent% chance you still know them. " + when {
        percent >= 94 -> "Much higher means many more reviews for a little more remembered."
        percent <= 84 -> "Fewer reviews, but you'll forget more of them."
        else -> "90% suits most people."
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
      value = value,
      onValueChange = { value = (it * 100).roundToInt() / 100f },
      onValueChangeFinished = { onChange((value * 100).roundToInt() / 100.0) },
      valueRange = 0.80f..0.97f,
      steps = 16,
    )
  }
}

/** A value picked from [choices] with − and +. A value not in the list (set elsewhere) steps to its neighbours. */
@Composable
private fun <T : Comparable<T>> StepperRow(
  title: String,
  summary: String?,
  value: T,
  choices: List<T>,
  label: (T) -> String,
  onChange: (T) -> Unit,
) {
  val lower = choices.lastOrNull { it < value }
  val higher = choices.firstOrNull { it > value }
  Row(
    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      if (summary != null) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(8.dp))
    FilledTonalIconButton(onClick = { lower?.let(onChange) }, enabled = lower != null, modifier = Modifier.size(36.dp)) {
      Text("−", style = MaterialTheme.typography.titleMedium)
    }
    Text(label(value), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 76.dp))
    FilledTonalIconButton(onClick = { higher?.let(onChange) }, enabled = higher != null, modifier = Modifier.size(36.dp)) {
      Text("+", style = MaterialTheme.typography.titleMedium)
    }
  }
}

/** Steps typed as "1m 10m"; saved when they parse, flagged when they don't. */
@Composable
private fun StepsRow(title: String, summary: String, steps: List<Long>, onChange: (List<Long>) -> Unit) {
  var text by remember(steps) { mutableStateOf(TextFieldValue(ReviewPrefs.formatSteps(steps))) }
  val parsed = ReviewPrefs.parseSteps(text.text)
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Column {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        if (parsed == null) "Use times like 1m 10m 1h (s, m, h or d)" else summary,
        style = MaterialTheme.typography.bodyMedium,
        color = if (parsed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    PillTextField(
      value = text,
      onValueChange = { v -> text = v; ReviewPrefs.parseSteps(v.text)?.let { if (it != steps) onChange(it) } },
      placeholder = "No steps",
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(),
    )
  }
}

private val NEW_PER_DAY = listOf(0, 1, 3, 5, 10, 15, 20, 30, 50, 100)
private const val NO_LIMIT = 9999
private val REVIEWS_PER_DAY = listOf(25, 50, 100, 150, 200, 300, 500, NO_LIMIT)
private val MAX_INTERVAL = listOf(30, 90, 180, 365, 730, 1825, 3650, 36500)

private fun gapLabel(days: Int) = when {
  days >= 36500 -> "No limit"
  days >= 365 -> "${days / 365} yr"
  days >= 30 -> "${days / 30} mo"
  else -> "$days d"
}

private fun hourLabel(h: Int) = when {
  h == 0 -> "12 AM"
  h < 12 -> "$h AM"
  h == 12 -> "12 PM"
  else -> "${h - 12} PM"
}
