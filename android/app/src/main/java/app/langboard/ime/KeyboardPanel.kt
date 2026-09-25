package app.langboard.ime

import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.langboard.R
import app.langboard.core.AssistMode
import app.langboard.core.Candidate
import app.langboard.core.Detection
import app.langboard.core.ModelState
import app.langboard.core.NaturalizeResult
import app.langboard.core.Register
import app.langboard.core.ScreenLine
import app.langboard.core.TextDiff
import app.langboard.core.Term
import app.langboard.ui.RubyText
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The main panel. Taller than a phone keyboard, so the options have room: the phrase, the sentence
 * as it will read, and a list of every way to say it.
 */
internal val CompactHeight = 400.dp
/** The same sheet, pulled up: more options at once, the context, word breakdowns and term pages. */
private val ExpandedHeight = 580.dp
/** The IME window is always this tall; everything above the visible panel is transparent. */
internal val MaxPanelHeight = ExpandedHeight
private val SwipeThreshold = 48.dp

/** The row Android draws under an IME for its hide and keyboard-switcher buttons. */
private val ImeNavBarHeight = 48.dp

/** Side margin for everything in the panel. */
private val Gutter = 20.dp

private val PanelSpring = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)

private fun Dp.toPx(density: androidx.compose.ui.unit.Density) = value * density.density

private fun heightOf(panel: ImePanel?): Dp = if (panel == null) CompactHeight else ExpandedHeight

/**
 * The whole keyboard. The window never changes size: panels expand and collapse inside it, so the
 * host app is never re-laid out mid-animation. [onPanelBounds] reports, in window pixels, the top of
 * the visible panel (touchable area) and the top of the panel it's settling at, compact or expanded
 * (the space apps make room for, so the cursor, and the handle apps draw over everything, stays above it).
 */
@Composable
fun KeyboardPanel(
  state: ImeState,
  model: ModelState,
  panel: ImePanel?,
  optionCount: Int,
  quick: QuickRow?,
  words: WordsState?,
  saved: Boolean,
  savedTerms: Set<String>,
  onAction: (ImeAction) -> Unit,
  onPanelBounds: (visibleTop: Int, roomTop: Int) -> Unit,
) {
  // A panel whose state went away (e.g. the text changed under Details) falls back to the main panel.
  val open = when (panel) {
    ImePanel.Details -> panel.takeIf { state.detection != null }
    ImePanel.Naturalize -> panel.takeIf { state is ImeState.Unnatural }
    ImePanel.Message -> panel.takeIf { state is ImeState.Explain && state.result != null }
    else -> panel
  }
  val expandTo = when {
    state.detection != null -> ImePanel.Details
    state is ImeState.Unnatural -> ImePanel.Naturalize
    state is ImeState.Explain && state.result != null -> ImePanel.Message
    else -> null
  }
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()
  val target = heightOf(open)
  val height = remember { Animatable(CompactHeight.value) }
  var dragging by remember { mutableStateOf(false) }
  LaunchedEffect(target) { if (!dragging) height.animateTo(target.value, PanelSpring) }

  // While pulling the panel up, show the view it would open, so the drag reveals it.
  val shown = if (open == null && dragging && expandTo != null && height.value > CompactHeight.value + 12f) expandTo else open
  val canDrag = open != null || expandTo != null
  val drag = if (!canDrag) Modifier else Modifier.pointerInput(open, expandTo) {
    detectVerticalDragGestures(
      onDragStart = { dragging = true },
      onDragEnd = {
        dragging = false
        val h = height.value
        when {
          open == null && h > CompactHeight.value + SwipeThreshold.value -> onAction(ImeAction.OpenPanel(expandTo!!))
          open != null && h < target.value - SwipeThreshold.value -> onAction(ImeAction.ClosePanel)
          else -> scope.launch { height.animateTo(target.value, PanelSpring) }
        }
      },
      onDragCancel = {
        dragging = false
        scope.launch { height.animateTo(target.value, PanelSpring) }
      },
    ) { change, dy ->
      change.consume()
      val max = if (open == null) heightOf(expandTo).value else target.value + 16f
      scope.launch { height.snapTo((height.value - dy / density.density).coerceIn(CompactHeight.value - 24f, max)) }
    }
  }

  // The option picked in the fill list, shared by the compact and expanded views. Reset per phrase.
  var picked by remember(state.detection?.fragment) { mutableStateOf<String?>(null) }

  Column(Modifier.fillMaxWidth()) {
    Box(
      Modifier
        .fillMaxWidth()
        .height(MaxPanelHeight),
      contentAlignment = Alignment.BottomCenter,
    ) {
      // Surface supplies LocalContentColor; the IME window has no themed background of its own.
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(height.value.dp)
          .onGloballyPositioned { sheet ->
            val top = sheet.positionInWindow().y
            val bottom = top + sheet.size.height
            onPanelBounds(top.roundToInt(), (bottom - target.toPx(density)).roundToInt())
          }
          .then(drag),
      ) {
        AnimatedContent(
          targetState = shown,
          transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
              .using(SizeTransform(clip = false) { _, _ -> snap() })
          },
          contentAlignment = Alignment.TopCenter,
          label = "panel",
          modifier = Modifier.fillMaxSize().clipToBounds(),
        ) { p ->
          // Each view keeps its natural height; the sheet above clips it while it grows.
          Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(heightOf(p))) {
            when (p) {
              null -> MainPanel(state, model, optionCount, quick, saved, picked, { picked = it }, onAction)
              ImePanel.Details -> state.detection?.let { d ->
                FillPanel(state, model, optionCount, d, quick, saved, picked, { picked = it }, expanded = true, onAction = onAction)
              }
              ImePanel.Naturalize -> (state as? ImeState.Unnatural)?.let { NaturalizePanel(it.result, onAction) }
              ImePanel.Words -> WordsPanel(words, onAction)
              ImePanel.Message -> (state as? ImeState.Explain)?.let { MessagePanel(it, saved, onAction) }
              is ImePanel.TermDetail -> TermPanel(p, (state as? ImeState.Explain)?.message, p.term.text in savedTerms, onAction)
            }
          }
        }
      }
      // Drag handle: the affordance that this sheet moves.
      if (canDrag) {
        Box(
          Modifier
            .align(Alignment.TopCenter)
            .offset { IntOffset(0, ((MaxPanelHeight.value - height.value) * density.density).roundToInt() + 8.dp.roundToPx()) }
            .size(width = 36.dp, height = 4.dp)
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
        )
      }
    }
    // Android draws its own keyboard switcher (and hide button) in the navigation bar below. With
    // gesture navigation that row is taller than the navigation bar inset, so keep clear of all of it.
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
      Spacer(Modifier.fillMaxWidth().height(if (navInset > 0.dp) maxOf(navInset, ImeNavBarHeight) else 0.dp))
    }
  }
}

// ---------------------------------------------------------------- Main panel

/** One job per screen: the options, the check, the explanation, or a short message. */
@Composable
private fun MainPanel(
  state: ImeState,
  model: ModelState,
  optionCount: Int,
  quick: QuickRow?,
  saved: Boolean,
  picked: String?,
  onPick: (String) -> Unit,
  onAction: (ImeAction) -> Unit,
) {
  Box(Modifier.fillMaxWidth().height(CompactHeight)) {
    AnimatedContent(
      targetState = state,
      contentKey = {
        when {
          it.detection != null -> "fill"
          it is ImeState.Explain -> "explain"
          it is ImeState.Checking || it is ImeState.Unnatural -> "check"
          else -> it::class
        }
      },
      transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(90)) },
      label = "main",
      modifier = Modifier.fillMaxSize(),
    ) { s ->
      val d = s.detection
      when {
        d != null -> FillPanel(s, model, optionCount, d, quick, saved, picked, onPick, expanded = false, onAction = onAction)
        s is ImeState.Explain -> ExplainMain(s, saved, onAction)
        s is ImeState.Checking -> CheckMain(s.span.sentence, null, onAction)
        s is ImeState.Unnatural -> CheckMain(s.result.original, s.result, onAction)
        s is ImeState.Inserted -> InsertedMain(s.text, saved, onAction)
        else -> Status(s, onAction)
      }
    }
  }
}

internal val ImeState.detection: Detection?
  get() = when (this) {
    is ImeState.Working -> detection
    is ImeState.Suggestions -> detection
    is ImeState.NoMatch -> detection
    else -> null
  }

/** The padding every main view shares, below the drag handle: its [top] row, then [content] in the gutters. */
@Composable
private fun MainColumn(top: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
  Column(Modifier.fillMaxSize().padding(top = 18.dp, bottom = 12.dp)) {
    top()
    Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Gutter), content = content)
  }
}

/** One insertable option in the fill list: the model's, in its order, or a dictionary word. */
private data class FillOption(val candidate: Candidate, val fromDictionary: Boolean)

/**
 * English in the draft. The phrase once, top left; the sentence as it will read with the new part
 * in the accent color; then every option, the model's first answer on top and the rest by
 * probability, and the dictionary's words below them. Tap any option and it goes in (and, by
 * default, back to the user's keyboard); press and hold one to see it in the sentence first.
 * [expanded] is the pulled-up sheet: more rows at once, and the context the model was given.
 */
@Composable
private fun FillPanel(
  state: ImeState,
  model: ModelState,
  optionCount: Int,
  d: Detection,
  quick: QuickRow?,
  saved: Boolean,
  picked: String?,
  onPick: (String) -> Unit,
  expanded: Boolean,
  onAction: (ImeAction) -> Unit,
) {
  val suggestions = state as? ImeState.Suggestions
  val fromModel = shown(suggestions?.result?.candidates.orEmpty(), optionCount)
  val dictionary = quick?.chips.orEmpty().filter { c -> fromModel.none { it.text == c.text } }
  val options = fromModel.map { FillOption(it, false) } + dictionary.map { FillOption(it, true) }
  // What the sentence shows: an option being held, else the first answer.
  val selected = options.firstOrNull { it.candidate.text == picked }?.candidate
    ?: fromModel.firstOrNull()
  val more = suggestions?.result?.more == true
  val insert = { c: Candidate -> onAction(ImeAction.Insert(c)) }

  Column(Modifier.fillMaxSize().padding(top = if (expanded) 16.dp else 18.dp)) {
    // The phrase, once, with Save before it and the way back after it.
    TopBar(
      onAction,
      save = { SaveButton(saved, selected?.text) { selected?.let { onAction(ImeAction.ToggleSave(it.text)) } } },
      trailing = if (!expanded) null else {
        { IconButton(onClick = { onAction(ImeAction.ClosePanel) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse") } }
      },
    ) {
      Text(
        d.fragment,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    // The sentence as it will read.
    Box(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = Gutter), contentAlignment = Alignment.CenterStart) {
      if (selected != null) {
        val before = sentenceStart(d.contextBefore)
        val sentence = (before + selected.text + d.tail.take(PREVIEW_AFTER)).trimEnd()
        RubyText(sentence, fontSize = 19.sp, highlight = listOf(before.length until before.length + selected.text.length), maxLines = 2)
      } else {
        RubySkeleton(pinyin = 150.dp, hanzi = 210.dp, hanziHeight = 26.dp)
      }
    }

    Spacer(Modifier.height(14.dp))
    // What's still coming shows as skeleton rows below, nothing else moves.
    SectionHeader("Options", tag = suggestions?.context?.takeIf { it.used }?.register?.register?.label)
    Spacer(Modifier.height(6.dp))

    Box(Modifier.weight(1f)) {
      LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        itemsIndexed(fromModel, key = { _, c -> "m:" + c.text }) { i, c ->
          OptionRow(i + 1, c, c == selected, dictionary = false, onPreview = { onPick(c.text) }, onInsert = { insert(c) })
        }
        if (state is ImeState.Working || more) {
          val from = fromModel.size
          items(minOf(if (more) 2 else 3, optionCount - from)) { i -> OptionSkeleton(from + i) }
        }
        if (state is ImeState.NoMatch) {
          item { NoAnswer(state, model, onAction) }
        }
        if (dictionary.isNotEmpty() || quick?.hasWords == true) {
          item { SectionHeader("Dictionary", Modifier.padding(top = 20.dp, bottom = 6.dp)) }
          items(dictionary, key = { "d:" + it.text }) { c ->
            OptionRow(null, c, c == selected, dictionary = true, onPreview = { onPick(c.text) }, onInsert = { insert(c) })
          }
          item {
            TextButton(
              onClick = { onAction(ImeAction.OpenPanel(ImePanel.Words)) },
              contentPadding = PaddingValues(horizontal = Gutter),
            ) {
              Text(if (quick?.hasWords == true) "Look up each word" else "Look it up")
              Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
          }
        }
        if (expanded && suggestions != null) item { ContextSection(suggestions.context, suggestions.prompt) }
      }
    }
  }
}

/**
 * The first [count] options. Shares are of the options shown, so when some are cut the rest are
 * rescaled to add up to 100% again.
 */
private fun shown(all: List<Candidate>, count: Int): List<Candidate> {
  if (all.size <= count) return all
  val kept = all.take(count)
  val total = kept.sumOf { it.share?.toDouble() ?: return kept }.toFloat()
  return if (total > 0f) kept.map { it.copy(share = it.share!! / total) } else kept
}

private const val PREVIEW_BEFORE = 22
private const val PREVIEW_AFTER = 16

/** The sentence the gap is in, from its start (or the last [PREVIEW_BEFORE] characters), so the new part stays in view. */
private fun sentenceStart(before: String): String {
  val cut = before.indexOfLast { it in "。！？!?\n" }
  val sentence = before.substring(cut + 1).trimStart()
  return if (sentence.length <= PREVIEW_BEFORE) sentence else "…" + sentence.takeLast(PREVIEW_BEFORE)
}

private val Register.label: String
  get() = when (this) {
    Register.Casual -> "Casual chat"
    Register.Neutral -> "From your chat"
    Register.Formal -> "Polite"
  }

/**
 * One option: tap puts it in, press and hold shows it in the sentence. The row shown in the
 * sentence sits on a card. [Candidate.share] is its share of the model's probability.
 */
@Composable
private fun OptionRow(
  number: Int?,
  c: Candidate,
  previewed: Boolean,
  dictionary: Boolean,
  onPreview: () -> Unit,
  onInsert: () -> Unit,
) {
  val haptics = LocalHapticFeedback.current
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = if (previewed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier
      .padding(horizontal = 8.dp, vertical = 1.dp)
      .fillMaxWidth()
      .pointerInput(c) {
        detectTapGestures(
          onTap = { onInsert() },
          onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onPreview() },
        )
      }
      .semantics {
        role = Role.Button
        contentDescription = listOfNotNull(
          c.text, c.meaning, c.share?.let { "${percent(it)} likely" }, if (dictionary) "dictionary" else null,
        ).joinToString(", ")
        onClick(label = "Insert") { onInsert(); true }
        onLongClick(label = "Show in the sentence") { onPreview(); true }
      },
  ) {
    Row(
      Modifier.heightIn(min = OptionRowHeight).padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        number?.toString() ?: "",
        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(22.dp),
      )
      RubyText(c.text, fontSize = 22.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
      Column(horizontalAlignment = Alignment.End) {
        c.share?.let { Muted(percent(it), style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace)) }
        c.meaning?.let {
          Muted(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.widthIn(max = 150.dp))
        }
      }
    }
  }
}

/** A share as a whole percent; under 1% reads "<1%" rather than a misleading 0%. */
private fun percent(share: Float): String = if (share < 0.005f) "<1%" else "${(share * 100).roundToInt()}%"

private val OptionRowHeight = 68.dp

/** An option still coming, shaped like [OptionRow]: reading over characters, then its share. */
@Composable
private fun OptionSkeleton(index: Int) {
  // Different lengths, so the rows read as text rather than a pattern.
  val widths = listOf(170.dp, 124.dp, 204.dp, 146.dp)
  val w = widths[index % widths.size]
  Row(
    Modifier
      .padding(horizontal = 8.dp, vertical = 1.dp)
      .fillMaxWidth()
      .heightIn(min = OptionRowHeight)
      .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(Modifier.width(22.dp))
    Box(Modifier.weight(1f)) { RubySkeleton(pinyin = w * 0.72f, hanzi = w, hanziHeight = 24.dp) }
    ShimmerBar(34.dp, 14.dp)
  }
}

/** Placeholder for pinyin over Chinese: a thin line for the reading over a thick one for the characters. */
@Composable
private fun RubySkeleton(pinyin: Dp, hanzi: Dp, hanziHeight: Dp) {
  Column {
    ShimmerBar(pinyin, 10.dp)
    Spacer(Modifier.height(8.dp))
    ShimmerBar(hanzi, hanziHeight)
  }
}

/** A section title in sentence case, with an optional [tag] (the chat's register). */
@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier, tag: String? = null) {
  Row(
    modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = Gutter),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    tag?.let { Spacer(Modifier.width(10.dp)); Tag(it) }
  }
}

/** The model has nothing: why, and what still works. */
@Composable
private fun NoAnswer(state: ImeState.NoMatch, model: ModelState, onAction: (ImeAction) -> Unit) {
  Column(Modifier.padding(horizontal = Gutter, vertical = 12.dp)) {
    if (state.modelMissing) {
      Text(
        when (model) {
          is ModelState.Downloading -> "Chinese model downloading" + (model.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
          is ModelState.Verifying -> "Chinese model almost ready"
          else -> "Chinese model needed"
        },
        style = MaterialTheme.typography.titleMedium,
      )
      Spacer(Modifier.height(4.dp))
      Muted("For options that fit your sentence. The dictionary below still works.", style = MaterialTheme.typography.bodyMedium)
      if (model !is ModelState.Downloading && model !is ModelState.Verifying) {
        TextButton(onClick = { onAction(ImeAction.OpenApp) }, contentPadding = PaddingValues(0.dp)) {
          Text("Get the Chinese model")
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
      }
    } else {
      Text("No natural phrase for this one", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(4.dp))
      Muted("Try a shorter phrase, or look up each word.", style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** What was read from the screen and exactly what the model was given, one tap further. */
@Composable
private fun ContextSection(ctx: ChatContext, prompt: String?) {
  var open by remember { mutableStateOf(false) }
  Column(Modifier.padding(top = 24.dp)) {
    Overline("Context", Modifier.padding(horizontal = Gutter))
    Spacer(Modifier.height(4.dp))
    Row(
      Modifier
        .fillMaxWidth()
        .clickable(onClickLabel = if (open) "Hide what the model read" else "Show what the model read") { open = !open }
        .padding(horizontal = Gutter, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f)) {
        Text(if (ctx.used) ctx.register.register.label else "Your sentence only", style = MaterialTheme.typography.bodyLarge)
        val detail = buildList {
          if (ctx.used) add("${ctx.screen.lines.size} lines from the screen")
          if (ctx.messages > 0) add("${ctx.messages} messages")
          if (ctx.register.cues.isNotEmpty()) add("from " + ctx.register.cues.joinToString("  "))
        }.joinToString("  ·  ").ifEmpty { "Turn on Conversation context in Langboard to use the chat too" }
        Muted(detail, style = MaterialTheme.typography.bodyMedium)
      }
      Icon(
        if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = Gutter).fillMaxWidth(),
      ) {
        Column(Modifier.padding(16.dp)) {
          if (ctx.used) {
            Muted("Read from the screen (not saved):", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            ctx.screen.lines.takeLast(12).forEach { l ->
              Text(
                when (l.align) {
                  ScreenLine.Align.Start -> "Them  "
                  ScreenLine.Align.End -> "Me  "
                  ScreenLine.Align.Wide -> "·  "
                } + l.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Spacer(Modifier.height(12.dp))
          }
          prompt?.let {
            Muted("Sent to the model:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Text(
              it.substringAfter("<｜hy_User｜>").substringBefore("<｜hy_Assistant｜>"),
              style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
          }
        }
      }
    }
  }
}

/**
 * Empty draft: the newest message someone sent, what it means, and one word worth knowing. Every
 * other word is one tap deeper.
 */
@Composable
private fun ExplainMain(state: ImeState.Explain, saved: Boolean, onAction: (ImeAction) -> Unit) {
  val r = state.result
  MainColumn(top = {
    TopBar(onAction, save = { SaveButton(saved, state.message.takeIf { r != null }) { onAction(ImeAction.ToggleSave()) } }) {
      Overline(if (state.context.used) "Latest message  ·  ${state.context.register.register.label.lowercase()}" else "Latest message")
    }
  }) {
    Spacer(Modifier.height(4.dp))
    if (state.message.isEmpty()) {
      ShimmerBar(240.dp, 28.dp)
      Spacer(Modifier.height(14.dp))
      ShimmerBar(180.dp, 16.dp)
      return@MainColumn
    }
    RubyText(state.message, fontSize = 22.sp, maxLines = 3)
    Spacer(Modifier.height(10.dp))
    when {
      r == null -> ShimmerBar(220.dp, 18.dp)
      r.translation != null -> Text(r.translation, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.weight(1f))
    val key = r?.key
    when {
      r == null -> Unit
      key != null -> TermCard(key) { onAction(ImeAction.OpenPanel(ImePanel.TermDetail(key, parent = null))) }
      r.words.isEmpty() -> Muted("Download the dictionary in the Langboard app to look up each word.", style = MaterialTheme.typography.bodyMedium)
    }
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
      if (r != null && r.words.size > 1) {
        TextButton(onClick = { onAction(ImeAction.OpenPanel(ImePanel.Message)) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
          Text("Word by word")
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

/** One word: the characters with their reading and its meaning, on a card that opens its page. */
@Composable
private fun TermCard(t: Term, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
  ) {
    Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        RubyText(t.text, fontSize = 22.sp)
        (t.here ?: t.meaning)?.let {
          Spacer(Modifier.height(2.dp))
          Muted(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
      }
      Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "More about ${t.text}",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * A Chinese draft: the model's more natural version with what it changes in the accent color, what
 * the user wrote below it, and Replace. [r] is null while the model works.
 */
@Composable
private fun CheckMain(original: String, r: NaturalizeResult?, onAction: (ImeAction) -> Unit) {
  MainColumn(top = { TopBar(onAction) { Overline(if (r == null) "✦  Checking your Chinese…" else "✦  More natural") } }) {
    Spacer(Modifier.height(4.dp))
    if (r == null) {
      ShimmerBar(240.dp, 30.dp)
    } else {
      // Tap it to use it, like a fill option.
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        onClick = { onAction(ImeAction.ReplaceSentence(r.natural)) },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Use ${r.natural}" },
      ) {
        RubyText(r.natural, fontSize = 24.sp, highlight = r.changes, maxLines = 3, modifier = Modifier.padding(12.dp))
      }
    }
    Spacer(Modifier.height(16.dp))
    Muted("You wrote", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(2.dp))
    RubyText(original, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
      if (r != null && r.alternatives.isNotEmpty()) {
        TextButton(onClick = { onAction(ImeAction.OpenPanel(ImePanel.Naturalize)) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
          Text("Other ways · ${r.alternatives.size}")
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
        }
      }
      Spacer(Modifier.weight(1f))
      if (r != null) Muted("Tap it to use it", style = MaterialTheme.typography.bodyMedium)
    }
  }
}

/** Stay-open mode after inserting: what went in, and Undo. */
@Composable
private fun InsertedMain(text: String, saved: Boolean, onAction: (ImeAction) -> Unit) {
  MainColumn(top = { TopBar(onAction, save = { SaveButton(saved, text) { onAction(ImeAction.ToggleSave(text)) } }) { Overline("Inserted") } }) {
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
      ) { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(22.dp)) }
      Spacer(Modifier.width(16.dp))
      RubyText(text, fontSize = 28.sp, maxLines = 2)
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { onAction(ImeAction.Undo) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Undo") }
    }
  }
}

/** Everything else: an icon, one line, at most one more, and at most one action besides the way back. */
@Composable
private fun Status(state: ImeState, onAction: (ImeAction) -> Unit) {
  val retry = "Try again" to ImeAction.Retry
  MainColumn(top = { TopBar(onAction) {} }) {
    when (state) {
      is ImeState.Idle -> if (state.checked) {
        Message(Icons.Filled.Check, "Sounds natural", "Nothing to change in your last sentence.", null, onAction)
      } else {
        Message(
          Icons.Filled.Edit, "Use English where you get stuck",
          "Then switch here. With an empty message, Langboard explains what they sent.", null, onAction,
        )
      }
      is ImeState.NeedsSelection -> Message(
        Icons.Filled.Edit,
        if (state.tooLong) "That's a long phrase" else "Which part is English?",
        if (state.tooLong) "Select a shorter part, then switch here." else "Select the English you want in Chinese.",
        null, onAction,
      )
      ImeState.Unreadable -> Message(Icons.Filled.Info, "Can't read this app", "It doesn't let keyboards see its text.", null, onAction)
      is ImeState.UnsupportedField -> Message(
        Icons.Filled.Lock,
        if (state.sensitive) "Off in password fields" else "Not for this field",
        if (state.sensitive) "Langboard never reads passwords." else "Use your regular keyboard here.",
        null, onAction,
      )
      ImeState.ChangedText -> Message(Icons.Filled.Refresh, "The message changed", null, retry, onAction)
      ImeState.Error -> Message(Icons.Filled.Warning, "Something went wrong", null, retry, onAction)
      ImeState.Locked -> Message(
        Icons.Filled.Lock, "Start your free trial", "Open Langboard to try it free for 7 days.",
        "Open Langboard" to ImeAction.OpenApp, onAction,
      )
      else -> Unit
    }
  }
}

@Composable
private fun Message(
  icon: ImageVector,
  title: String,
  body: String?,
  action: Pair<String, ImeAction>?,
  onAction: (ImeAction) -> Unit,
) {
  Column(
    Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(bottom = 24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
      contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    body?.let {
      Spacer(Modifier.height(8.dp))
      Muted(it, style = MaterialTheme.typography.bodyMedium, center = true)
    }
    action?.let { (label, a) ->
      Spacer(Modifier.height(28.dp))
      FilledTonalButton(onClick = { onAction(a) }, modifier = Modifier.height(48.dp)) { Text(label) }
    }
  }
}

@Composable
private fun WordRow(t: Term, onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = "More about ${t.text}", role = Role.Button, onClick = onClick)
      .padding(horizontal = Gutter, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      RubyText(t.text, fontSize = 22.sp)
      (t.here ?: t.meaning)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
      t.tag?.let { Spacer(Modifier.height(6.dp)); Tag(it) }
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

// ---------------------------------------------------------------- Message, word by word

@Composable
private fun MessagePanel(state: ImeState.Explain, saved: Boolean, onAction: (ImeAction) -> Unit) {
  val r = state.result ?: return
  ExpandedPanel("Word by word", onAction, save = { SaveButton(saved, state.message) { onAction(ImeAction.ToggleSave()) } }) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
      item {
        Column(Modifier.padding(horizontal = Gutter).padding(top = 8.dp)) {
          RubyText(r.message, fontSize = 24.sp)
          r.translation?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
      }
      sectionTitle("Words")
      itemsIndexed(r.words) { i, t ->
        if (i > 0) RowDivider()
        WordRow(t) { onAction(ImeAction.OpenPanel(ImePanel.TermDetail(t, parent = ImePanel.Message))) }
      }
      if (r.words.isEmpty()) {
        item {
          Muted(
            "Download the dictionary in the Langboard app to see every word.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 16.dp),
          )
        }
      }
    }
  }
}

// ---------------------------------------------------------------- One word

/** A word in depth: reading, meaning, and the message it came from. */
@Composable
private fun TermPanel(p: ImePanel.TermDetail, message: String?, saved: Boolean, onAction: (ImeAction) -> Unit) {
  val t = p.term
  ExpandedPanel(
    title = if (p.parent == null) "" else "Back",
    onAction = onAction,
    back = p.parent != null,
    save = { SaveButton(saved, t.text) { onAction(ImeAction.ToggleSaveTerm(t)) } },
  ) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
      item {
        Column(Modifier.padding(horizontal = Gutter).padding(top = 4.dp)) {
          RubyText(t.text, fontSize = 44.sp)
          t.tag?.let { Spacer(Modifier.height(12.dp)); Tag(it) }
          t.meaning?.let { Spacer(Modifier.height(20.dp)); Text(it, style = MaterialTheme.typography.titleMedium) }
        }
      }
      t.here?.takeIf { it != t.meaning }?.let { here -> section("Here") { Text("“$here”", style = MaterialTheme.typography.bodyLarge) } }
      t.note?.let { note ->
        item {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = Gutter).padding(top = 24.dp).fillMaxWidth(),
          ) { Text(note, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) }
        }
      }
      if (message != null && message.contains(t.text)) {
        section("In the message") {
          val at = message.indexOf(t.text)
          RubyText(message, fontSize = 20.sp, highlight = listOf(at until at + t.text.length))
        }
      }
      item {
        Row(Modifier.padding(horizontal = Gutter).padding(top = 28.dp)) {
          FilledTonalButton(onClick = { onAction(ImeAction.Copy(Candidate(t.text, t.pinyin, t.meaning))) }) {
            Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Copy")
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------- Naturalize

/** Every more natural way the model found, each with its changes marked; tap one to use it. */
@Composable
private fun NaturalizePanel(result: NaturalizeResult, onAction: (ImeAction) -> Unit) {
  ExpandedPanel("More natural", onAction) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
      section("You wrote", top = 8.dp) { RubyText(result.original, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
      sectionTitle("Tap one to use it")
      val all = listOf(result.natural) + result.alternatives
      itemsIndexed(all) { i, text ->
        if (i > 0) RowDivider()
        Row(
          Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Replace with $text", role = Role.Button) { onAction(ImeAction.ReplaceSentence(text)) }
            .padding(horizontal = Gutter, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RubyText(text, fontSize = 22.sp, highlight = TextDiff.inserted(result.original, text), modifier = Modifier.weight(1f))
        }
      }
    }
  }
}

val AssistMode.label: String
  get() = when (this) {
    AssistMode.STUCK -> "Stuck only"
    AssistMode.COACH -> "Coach"
    AssistMode.NATIVE -> "Native"
  }

val AssistMode.description: String
  get() = when (this) {
    AssistMode.STUCK -> "Only help when I mix English into Chinese"
    AssistMode.COACH -> "Also suggest more natural ways to write my Chinese"
    AssistMode.NATIVE -> "Suggest even small things, like particles (了, 啊, 吧)"
  }

// ---------------------------------------------------------------- Words (English phrase)

/** Each English word on its own. Tap copies, so the user can put the pieces together. */
@Composable
private fun WordsPanel(words: WordsState?, onAction: (ImeAction) -> Unit) {
  var peek by remember { mutableStateOf<Candidate?>(null) }
  var lastPeek by remember { mutableStateOf<Candidate?>(null) }
  if (peek != null) lastPeek = peek
  Box(Modifier.fillMaxSize()) {
    val rows = words?.rows
    ExpandedPanel(if (rows?.size == 1) "Dictionary" else "Each word", onAction, back = true) {
      when {
        rows == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(Gutter))
        rows.isEmpty() -> Muted(
          "No dictionary entries for these words.",
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(Gutter),
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
          item { Muted("Tap to copy  ·  hold for the meaning", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = Gutter)) }
          items(rows, key = { it.first }) { (word, chips) ->
            Column(Modifier.padding(top = 24.dp)) {
              Overline(word, Modifier.padding(horizontal = Gutter))
              Spacer(Modifier.height(10.dp))
              LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = Gutter)) {
                items(chips, key = { it.text }) { c ->
                  ResultChip(c, onTap = { onAction(ImeAction.Copy(c)) }, onPeek = { peek = it }, tapLabel = "Copy")
                }
              }
            }
          }
        }
      }
    }
    AnimatedVisibility(
      visible = peek != null,
      enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.94f),
      exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.97f),
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
    ) { lastPeek?.let { PeekCard(it) } }
  }
}

// ---------------------------------------------------------------- shared pieces

/** A taller view of the same panel. Swipe down or tap ⌄ to collapse; ← goes back a level. */
@Composable
private fun ExpandedPanel(
  title: String,
  onAction: (ImeAction) -> Unit,
  back: Boolean = false,
  save: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
    TopBar(
      onAction,
      Modifier.height(56.dp),
      save = save,
      leading = if (!back) null else {
        { IconButton(onClick = { onAction(ImeAction.ClosePanel) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
      },
      trailing = if (back) null else {
        { IconButton(onClick = { onAction(ImeAction.ClosePanel) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse") } }
      },
    ) {
      Text(
        if (back && title == "Back") "" else title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    content()
  }
}

private fun LazyListScope.sectionTitle(title: String, top: Dp = 32.dp) {
  item { Overline(title, Modifier.padding(horizontal = Gutter).padding(top = top, bottom = 4.dp)) }
}

private fun LazyListScope.section(title: String, top: Dp = 32.dp, content: @Composable () -> Unit) {
  sectionTitle(title, top)
  item { Box(Modifier.padding(horizontal = Gutter).padding(top = 8.dp)) { content() } }
}

@Composable
private fun RowDivider() {
  HorizontalDivider(Modifier.padding(horizontal = Gutter), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** Small, spaced capitals that label a section without competing with it. */
@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) {
  Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.1.sp, fontWeight = FontWeight.Medium),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
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
    textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
    modifier = modifier,
  )
}

@Composable
private fun Tag(text: String) {
  Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
  }
}

/**
 * Save as a star: keeps [what] (the option picked, the message, the word) in History → Saved, for
 * review later. Disabled (but still there, so nothing beside it moves) while there's nothing to save.
 */
@Composable
private fun SaveButton(saved: Boolean, what: String?, onClick: () -> Unit) {
  IconButton(
    onClick = onClick,
    enabled = what != null,
    modifier = Modifier.semantics {
      contentDescription = when {
        what == null -> "Save"
        saved -> "Saved $what. Tap to remove"
        else -> "Save $what to History"
      }
    },
  ) {
    Icon(
      painterResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_border),
      contentDescription = null,
      tint = when {
        what == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        saved -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      },
      modifier = Modifier.size(22.dp),
    )
  }
}

/** The way back to the user's own keyboard, in the same corner on every view. */
@Composable
private fun KeyboardButton(onAction: (ImeAction) -> Unit) {
  FilledTonalButton(
    onClick = { onAction(ImeAction.SwitchKeyboard) },
    contentPadding = PaddingValues(start = 12.dp, end = 14.dp),
    modifier = Modifier.height(36.dp).semantics { contentDescription = "Back to my keyboard" },
  ) {
    Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text("Back", style = MaterialTheme.typography.labelLarge)
  }
}

/**
 * The top row of every view: Save (when the view has something to save) at the left, [content],
 * then the way back to the user's keyboard and any [trailing] control. The star's icon lines up
 * with the gutter.
 */
@Composable
private fun TopBar(
  onAction: (ImeAction) -> Unit,
  modifier: Modifier = Modifier,
  save: (@Composable () -> Unit)? = null,
  leading: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    modifier.fillMaxWidth().height(48.dp).padding(
      start = if (leading != null) 8.dp else if (save != null) Gutter - 12.dp else Gutter,
      end = if (trailing != null) 4.dp else Gutter,
    ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    leading?.invoke()
    save?.let { it(); Spacer(Modifier.width(4.dp)) }
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = content)
    Spacer(Modifier.width(8.dp))
    KeyboardButton(onAction)
    trailing?.invoke()
  }
}

@Composable
private fun ShimmerBar(width: Dp, height: Dp) {
  val base = MaterialTheme.colorScheme.surfaceContainerHighest
  val shine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f).compositeOver(base)
  // One sweep every 1.3 s; the highlight's position is the same fraction of every bar, so they move together.
  val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
    initialValue = -1f, targetValue = 2f,
    animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "sweep",
  )
  Box(
    Modifier
      .width(width)
      .height(height)
      .clip(RoundedCornerShape(percent = 50).takeIf { height <= 14.dp } ?: RoundedCornerShape(8.dp))
      .drawBehind {
        val x = size.width * sweep
        drawRect(
          Brush.linearGradient(
            listOf(base, shine, base),
            start = Offset(x - size.width * 0.6f, 0f),
            end = Offset(x + size.width * 0.6f, size.height),
          ),
        )
      }
  )
}

/** A Chinese result: tap inserts (or copies), press and hold shows the meaning. */
@Composable
private fun ResultChip(c: Candidate, onTap: () -> Unit, onPeek: (Candidate?) -> Unit, tapLabel: String = "Insert") {
  val haptics = LocalHapticFeedback.current
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    modifier = Modifier
      .heightIn(min = 56.dp)
      .pointerInput(c) {
        detectTapGestures(
          onPress = { tryAwaitRelease(); onPeek(null) },
          onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onPeek(c) },
          onTap = { onTap() },
        )
      }
      .semantics {
        role = Role.Button
        contentDescription = listOfNotNull(c.text, c.pinyin, c.meaning).joinToString(", ")
        onClick(label = tapLabel) { onTap(); true }
      },
  ) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
      RubyText(c.text, fontSize = 19.sp, maxLines = 1)
    }
  }
}

/** Press-and-hold card: the meaning without inserting, so the learner can type it themselves. */
@Composable
private fun PeekCard(c: Candidate, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.inverseSurface,
    shadowElevation = 8.dp,
    modifier = modifier.padding(horizontal = 16.dp).fillMaxWidth(),
  ) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
      RubyText(c.text, fontSize = 28.sp)
      Spacer(Modifier.height(8.dp))
      Text(
        c.meaning ?: "Download the dictionary in the Langboard app to see meanings.",
        style = if (c.meaning != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/** [Candidate.nuance] marking a dictionary hit rather than a contextual answer. */
internal const val DICTIONARY = "dictionary"
