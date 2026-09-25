package app.langboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.langboard.billing.Subscription
import app.langboard.core.LangboardSettings
import app.langboard.core.Register
import app.langboard.ui.theme.LocalAccent

/**
 * What the app shows first (gpt/onboarding_monetization.md): four screens up to the free trial, two
 * to put the keyboard where the user types, then the app. A lapsed subscriber goes straight to the
 * paywall. Everything else (Conversation Context, notifications, review) is taught when it comes up.
 */
@Composable
fun LangboardRoot() {
  val context = LocalContext.current
  val settings = remember { LangboardSettings(context) }
  val active by Subscription.active.collectAsState()
  var setupDone by remember { mutableStateOf(settings.setupDone) }
  val stage = when {
    active == null -> Stage.Waiting
    active == false -> Stage.Intro
    !setupDone -> Stage.Setup
    else -> Stage.App
  }
  Crossfade(stage, animationSpec = tween(260), label = "stage") { s ->
    when (s) {
      // Usually a few frames: RevenueCat answers from its cache.
      Stage.Waiting -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
      Stage.Intro -> Intro(settings)
      Stage.Setup -> Setup(onDone = { settings.setupDone = true; setupDone = true })
      Stage.App -> LangboardApp()
    }
  }
}

private enum class Stage { Waiting, Intro, Setup, App }

// ---------------------------------------------------------------- before the trial

@Composable
private fun Intro(settings: LangboardSettings) {
  // Someone who has seen this once doesn't need the pitch again.
  var step by rememberSaveable { mutableStateOf(if (settings.introSeen) PAYWALL else 0) }
  BackHandler(enabled = step > 0) { step-- }
  Steps(step) { s ->
    when (s) {
      0 -> DemoStep { step = 1 }
      1 -> StyleStep(settings) { step = 2 }
      2 -> LoopStep { settings.introSeen = true; step = PAYWALL }
      else -> Paywall(onBack = { step = 2 })
    }
  }
}

private const val PAYWALL = 3

/** Steps slide forward and back; the surrounding chrome doesn't move. */
@Composable
internal fun Steps(step: Int, content: @Composable (Int) -> Unit) {
  AnimatedContent(
    targetState = step,
    transitionSpec = {
      val forward = targetState > initialState
      val enter = slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(200))
      val exit = slideOutHorizontally(tween(220)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(140))
      enter togetherWith exit
    },
    label = "step",
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) { content(it) }
}

/** One onboarding page: scrolling content, and one button pinned to the bottom. */
@Composable
internal fun StepPage(
  button: String,
  onButton: () -> Unit,
  buttonEnabled: Boolean = true,
  below: (@Composable () -> Unit)? = null,
  top: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    top?.invoke()
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScreenGutter).padding(top = if (top == null) 40.dp else 8.dp, bottom = 24.dp),
      content = content,
    )
    Column(Modifier.padding(horizontal = ScreenGutter).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Button(onClick = onButton, enabled = buttonEnabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(button, style = MaterialTheme.typography.titleMedium)
      }
      below?.invoke()
    }
  }
}

@Composable
internal fun StepTitle(title: String, subtitle: String? = null) {
  Text(title, style = MaterialTheme.typography.headlineLarge)
  if (subtitle != null) {
    Spacer(Modifier.height(10.dp))
    Muted(subtitle, MaterialTheme.typography.bodyLarge)
  }
}

/** The mark: ✦ in jade, then the name. */
@Composable
private fun Wordmark() {
  Text(
    buildAnnotatedString {
      withStyle(SpanStyle(color = LocalAccent.current)) { append("✦ ") }
      append("Langboard")
    },
    style = MaterialTheme.typography.titleMedium,
  )
}

/** 1: the whole product in ten seconds. The sentence stays put; only the English turns into Chinese. */
@Composable
private fun DemoStep(onContinue: () -> Unit) {
  var helped by rememberSaveable { mutableStateOf(false) }
  StepPage(
    button = if (helped) "Continue" else "Help me say this",
    onButton = { if (helped) onContinue() else helped = true },
  ) {
    Wordmark()
    Spacer(Modifier.height(40.dp))
    StepTitle("Say what you mean.", "Get unstuck without giving up the sentence.")
    Spacer(Modifier.height(40.dp))
    Panel {
      val accent = LocalAccent.current
      val muted = MaterialTheme.colorScheme.onSurfaceVariant
      Crossfade(helped, animationSpec = tween(450), label = "demo") { done ->
        Text(
          buildAnnotatedString {
            append("我本来想去但是")
            if (done) {
              withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append("懒得再去了") }
            } else {
              withStyle(SpanStyle(color = muted)) { append(" I couldn't be bothered anymore") }
            }
          },
          fontSize = 24.sp,
          lineHeight = 34.sp,
          modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        )
      }
    }
    Spacer(Modifier.height(20.dp))
    Crossfade(helped, animationSpec = tween(450, delayMillis = 200), label = "caption") { done ->
      if (done) {
        Text(
          "You wrote the sentence. Langboard only helped with the part you didn't know.",
          style = MaterialTheme.typography.bodyLarge,
        )
      }
    }
  }
}

/** 2: the language pair and one choice. Everything finer waits for My Style. */
@Composable
private fun StyleStep(settings: LangboardSettings, onContinue: () -> Unit) {
  var register by rememberSaveable { mutableStateOf(settings.defaultRegister) }
  StepPage(button = "Continue", onButton = { settings.defaultRegister = register; onContinue() }) {
    StepTitle("Make Langboard yours")
    Spacer(Modifier.height(28.dp))
    Panel {
      Row {
        Column(Modifier.weight(1f)) {
          Muted("I speak", MaterialTheme.typography.bodyMedium)
          Text("English", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f)) {
          Muted("I'm learning", MaterialTheme.typography.bodyMedium)
          Text("简体中文", style = MaterialTheme.typography.titleMedium)
        }
      }
    }
    Spacer(Modifier.height(36.dp))
    Text("How should you sound most of the time?", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Register.entries.sortedBy { it.order }.forEach { r ->
        ChoiceCard(r.choice, r.choiceDetail, selected = register == r) { register = r }
      }
    }
  }
}

/** Casual first: it's what most people type in chats. */
private val Register.order get() = when (this) { Register.Casual -> 0; Register.Neutral -> 1; Register.Formal -> 2 }

/** How onboarding and Settings name each register. */
internal val Register.choice: String
  get() = when (this) { Register.Casual -> "Casual"; Register.Neutral -> "Neutral"; Register.Formal -> "Work" }

internal val Register.choiceDetail: String
  get() = when (this) {
    Register.Casual -> "Natural everyday conversation"
    Register.Neutral -> "Works almost anywhere"
    Register.Formal -> "Professional, not stiff"
  }

/** A selectable card: title, one line, and a check when it's the one. */
@Composable
internal fun ChoiceCard(title: String, detail: String?, selected: Boolean, trailing: String? = null, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = GroupShape,
    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail != null) Muted(detail, MaterialTheme.typography.bodyMedium)
      }
      if (trailing != null) {
        Text(trailing, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
      }
      Box(
        Modifier.size(24.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
      }
    }
  }
}

/** 3: why it gets better with use, and the one-line privacy promise. */
@Composable
private fun LoopStep(onContinue: () -> Unit) {
  var privacy by remember { mutableStateOf(false) }
  StepPage(button = "Continue", onButton = onContinue) {
    StepTitle("Learn from what you actually say.")
    Spacer(Modifier.height(32.dp))
    val accent = LocalAccent.current
    LoopRow("Get unstuck", last = false) {
      Text(
        buildAnnotatedString {
          append("I couldn't be bothered  →  ")
          withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append("懒得…") }
        },
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    LoopRow("Remember it", last = false) { Muted("Langboard saves what you needed help with.", MaterialTheme.typography.bodyLarge) }
    LoopRow("Say it yourself", last = true) { Muted("Review it later until you don't need help.", MaterialTheme.typography.bodyLarge) }
    Spacer(Modifier.height(32.dp))
    Text("Runs on your phone. Your conversations aren't uploaded to train Langboard.", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("Offline model", "Private by default", "No account").forEach { Pill(it) }
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { privacy = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
      Text("How privacy works")
    }
  }
  if (privacy) PrivacyDialog { privacy = false }
}

/** A step of the loop, joined to the next by a thin line. */
@Composable
private fun LoopRow(title: String, last: Boolean, body: @Composable () -> Unit) {
  Row {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(12.dp)) {
      Box(Modifier.padding(top = 6.dp).size(9.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
      if (!last) Box(Modifier.width(1.dp).height(58.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
    Spacer(Modifier.width(16.dp))
    Column(Modifier.padding(bottom = if (last) 0.dp else 12.dp)) {
      SectionLabel(title)
      Spacer(Modifier.height(4.dp))
      body()
    }
  }
}

@Composable
private fun Pill(text: String) {
  Surface(shape = PillShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
  }
}

@Composable
internal fun PrivacyDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("How privacy works") },
    text = {
      Text(
        "Langboard's model runs on your phone. It reads the sentence around your cursor only when you switch " +
          "to it, and, if you turn on Conversation Context later, the messages on screen at that moment.\n\n" +
          "What you asked and what Langboard answered stay on this phone, in Memory. Nothing you write is " +
          "uploaded, and none of it trains Langboard.\n\nThere's no account. Your subscription goes through " +
          "Google Play and RevenueCat, which see an anonymous ID and your purchase, never your text.",
      )
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
  )
}
