package app.langboard.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.langboard.core.Fsrs
import app.langboard.history.HistoryKind
import app.langboard.history.HistoryStore
import app.langboard.history.ReviewDeck
import app.langboard.history.ReviewItem
import app.langboard.history.ReviewTuner
import app.langboard.core.LangboardSettings
import app.langboard.history.reviewPrompt
import app.langboard.history.HistoryEntry
import app.langboard.ui.theme.LocalAccent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

private val Gutter = 24.dp

/**
 * Spaced review, scheduled by FSRS: the English on the front, the Chinese on the back. Tap the card
 * (or Show answer) to flip it, then say how well you knew it; each button shows when you'd see the
 * card again. Cards graded Again or Hard come back in this session; the rest wait until they're due.
 */
@Composable
internal fun ReviewScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }
  val settings = remember { LangboardSettings(context) }
  val fsrs = remember { settings.review.scheduler() }
  val scope = rememberCoroutineScope()
  val queue = remember { mutableStateListOf<ReviewItem>() }
  var total by remember { mutableIntStateOf(0) }
  var done by remember { mutableIntStateOf(0) }
  var loaded by remember { mutableStateOf(false) }
  var flipped by remember { mutableStateOf(false) }
  /** Bumped per card shown, so a card that comes back still animates in. */
  var shown by remember { mutableIntStateOf(0) }
  var shownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
  var nextDue by remember { mutableStateOf<Long?>(null) }

  LaunchedEffect(Unit) {
    val now = System.currentTimeMillis()
    val prefs = settings.review
    val limits = ReviewDeck.Limits.of(prefs, store.reviewedSince(prefs.dayStart(now)))
    queue.addAll(ReviewDeck(store.list(), store.reviewCards()).session(now, limits))
    total = queue.size
    loaded = true
    shownAt = now
  }
  LaunchedEffect(loaded, queue.isEmpty()) {
    if (loaded && queue.isEmpty()) {
      val now = System.currentTimeMillis()
      nextDue = ReviewDeck(store.list(), store.reviewCards()).nextDue(now)
      // A finished session is a good moment to re-fit FSRS, if there's enough new data.
      if (total > 0) ReviewTuner.maybeTune(context)
    }
  }

  fun rate(rating: Fsrs.Rating) {
    val item = queue.removeAt(0)
    val now = System.currentTimeMillis()
    val next = fsrs.review(item.card, rating, now)
    val took = now - shownAt
    // A card's first review is logged without a state: it was new.
    val before = item.card.state.takeIf { item.card.lastReview != null }
    scope.launch { store.saveReview(item.key, before, next, rating, took) }
    if (next.due - now <= ReviewDeck.LEARN_AHEAD_MS) queue.add(item.copy(card = next)) else done++
    flipped = false
    shown++
    shownAt = now
  }

  Column(modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close review") }
      Spacer(Modifier.weight(1f))
      if (total > 0) {
        Text(
          "$done of $total",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(end = 16.dp),
        )
      }
    }
    if (total > 0) {
      val progress by animateFloatAsState(done.toFloat() / total, spring(stiffness = Spring.StiffnessLow), label = "progress")
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.padding(horizontal = Gutter).fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurface,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        drawStopIndicator = {},
      )
    }

    val item = queue.firstOrNull()
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Gutter, vertical = 16.dp), contentAlignment = Alignment.Center) {
      when {
        !loaded -> Unit
        item == null -> Finished(total, nextDue, onClose)
        else -> AnimatedContent(
          targetState = shown to item,
          contentKey = { it.first },
          transitionSpec = {
            (slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(240, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.96f))
              .togetherWith(slideOutHorizontally(tween(220)) { -it / 3 } + fadeOut(tween(160)))
          },
          label = "card",
        ) { (_, current) ->
          FlipCard(
            flipped = flipped,
            onFlip = { flipped = !flipped },
            front = { Front(current) },
            back = { Back(current) },
          )
        }
      }
    }

    if (item != null) {
      Box(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = Gutter).padding(bottom = 24.dp)) {
        AnimatedContent(flipped, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(100)) }, label = "actions") { back ->
          if (back) {
            val preview = remember(item) { fsrs.preview(item.card, System.currentTimeMillis()) }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Fsrs.Rating.entries.forEach { r -> GradeButton(r, preview.getValue(r)) { rate(r) } }
            }
          } else {
            Button(onClick = { flipped = true }, modifier = Modifier.fillMaxSize()) {
              Text("Show answer", style = MaterialTheme.typography.titleSmall)
            }
          }
        }
      }
    }
  }
}

/**
 * A card that turns over around its vertical axis, with perspective. It dips a little at the
 * halfway point, like a card lifted off a table, and the face swaps when it's edge-on.
 */
@Composable
private fun FlipCard(
  flipped: Boolean,
  onFlip: () -> Unit,
  front: @Composable BoxScope.() -> Unit,
  back: @Composable BoxScope.() -> Unit,
) {
  val angle by animateFloatAsState(
    targetValue = if (flipped) 180f else 0f,
    animationSpec = spring(dampingRatio = 0.72f, stiffness = 220f),
    label = "flip",
  )
  val lift = sin(angle.coerceIn(0f, 180f) / 180f * PI).toFloat()
  Surface(
    shape = RoundedCornerShape(28.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    onClick = onFlip,
    modifier = Modifier
      .fillMaxWidth()
      .height(CardHeight)
      .graphicsLayer {
        rotationY = angle
        cameraDistance = 16f * density
        scaleX = 1f - 0.06f * lift
        scaleY = 1f - 0.06f * lift
      }
      .semantics { contentDescription = if (flipped) "Answer side. Tap to see the question" else "Question side. Tap to see the answer" },
  ) {
    Box(Modifier.fillMaxSize()) {
      if (abs(angle) <= 90f) {
        front()
      } else {
        // The back is drawn mirrored by the turn; turn it once more so it reads normally.
        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }, content = back)
      }
    }
  }
}

private val CardHeight: Dp = 400.dp

@Composable
private fun BoxScope.Front(item: ReviewItem) {
  val e = item.entry
  val gap = remember(e) { gapIn(e) }
  Column(
    Modifier.align(Alignment.Center).padding(28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (gap != null) {
      // The moment it came from: your own sentence, with the part you couldn't say left open.
      Label(appName(e.app)?.takeIf { e.app != "app.langboard" }?.let { "In $it" } ?: "You were writing")
      Spacer(Modifier.height(20.dp))
      Text(
        buildAnnotatedString {
          append(gap.first)
          withStyle(SpanStyle(color = LocalAccent.current, fontWeight = FontWeight.SemiBold)) { append(" ＿＿＿ ") }
          append(gap.second)
        },
        fontSize = 24.sp,
        lineHeight = 34.sp,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(Modifier.height(24.dp))
      Text("“${e.reviewPrompt}”", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    } else {
      Label(if (e.kind == HistoryKind.Word) "Which word means" else "How do you say")
      Spacer(Modifier.height(14.dp))
      Text(e.reviewPrompt, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    }
  }
  if (item.card.lastReview == null) Label("New", Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp))
}

/** The sentence the answer went into, split around it; null when there's no real sentence around it. */
private fun gapIn(e: HistoryEntry): Pair<String, String>? {
  val s = e.sentence ?: return null
  val a = e.answer ?: return null
  if (e.kind != HistoryKind.Fill || s == a) return null
  val i = s.indexOf(a).takeIf { it >= 0 } ?: return null
  val before = s.substring(0, i)
  val after = s.substring(i + a.length)
  return if (before.isBlank() && after.isBlank()) null else before to after
}

@Composable
private fun BoxScope.Back(item: ReviewItem) {
  val e = item.entry
  Label(e.reviewPrompt, Modifier.align(Alignment.TopCenter).padding(top = 24.dp, start = 28.dp, end = 28.dp), maxLines = 2)
  Column(
    Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    RubyText(e.answer.orEmpty(), fontSize = 36.sp)
    // A fill's sentence shows the phrase where the user actually needed it.
    e.sentence?.takeIf { e.kind == HistoryKind.Fill && it != e.answer }?.let {
      Spacer(Modifier.height(20.dp))
      Text(
        it,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
      )
    }
  }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    maxLines = maxLines,
    modifier = modifier,
  )
}

/** A grade, with when the card would come back. Good, the usual answer, is the filled one. */
@Composable
private fun RowScope.GradeButton(rating: Fsrs.Rating, interval: Long, onClick: () -> Unit) {
  val content: @Composable RowScope.() -> Unit = {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(rating.name, style = MaterialTheme.typography.labelLarge)
      Text(intervalLabel(interval), style = MaterialTheme.typography.labelSmall)
    }
  }
  val modifier = Modifier.weight(1f).fillMaxSize().semantics {
    contentDescription = "${rating.name}, see it again in ${intervalLabel(interval, long = true)}"
  }
  val padding = PaddingValues(horizontal = 4.dp)
  if (rating == Fsrs.Rating.Good) Button(onClick, modifier, contentPadding = padding, content = content)
  else FilledTonalButton(onClick, modifier, contentPadding = padding, content = content)
}

/** "1m", "10m", "3h", "8d", "2.5mo", "1.2y"; spelled out for screen readers when [long]. */
private fun intervalLabel(ms: Long, long: Boolean = false): String {
  val minutes = ms / Fsrs.MINUTE.toDouble()
  val days = ms / Fsrs.DAY.toDouble()
  fun one(x: Double) = if (x < 10) ((x * 10).roundToInt() / 10.0).toString().removeSuffix(".0") else x.roundToInt().toString()
  return when {
    minutes < 60 -> maxOf(1, minutes.roundToInt()).let { if (long) "$it minutes" else "${it}m" }
    days < 1 -> (minutes / 60).roundToInt().let { if (long) "$it hours" else "${it}h" }
    days < 30 -> days.roundToInt().let { if (long) "$it days" else "${it}d" }
    days < 365 -> one(days / 30).let { if (long) "$it months" else "${it}mo" }
    else -> one(days / 365).let { if (long) "$it years" else "${it}y" }
  }
}

@Composable
private fun Finished(total: Int, nextDue: Long?, onClose: () -> Unit) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(if (total == 0) "Nothing to review" else "All done", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    val wait = nextDue?.let { intervalLabel(maxOf(0, it - System.currentTimeMillis()), long = true) }
    Text(
      listOfNotNull(if (total > 0) "You went through $total." else null, wait?.let { "Next review in $it." }).joinToString(" ")
        .ifEmpty { "Look something up with Langboard and it'll show up here." },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))
    Button(onClick = onClose, modifier = Modifier.height(52.dp)) { Text("Done") }
  }
}
