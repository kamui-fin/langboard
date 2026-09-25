package app.langboard.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.core.ModelState
import app.langboard.model.ModelManager
import app.langboard.ui.theme.LocalAccent

/**
 * After the trial starts: 5, put the keyboard where the user types while the model downloads in the
 * background; 6, use it once for real. Not a sales pitch any more; each can be skipped.
 */
@Composable
internal fun Setup(onDone: () -> Unit) {
  var step by rememberSaveable { mutableStateOf(0) }
  BackHandler(enabled = step > 0) { step-- }
  // The download starts at once, so nobody waits on 460 MB at the end. Notification permission is
  // left for later: the download runs without it, just without the progress notification.
  LaunchedEffect(Unit) { if (ModelManager.state.value == ModelState.NotInstalled) ModelManager.download() }
  Steps(step) { s ->
    if (s == 0) InstallStep(onContinue = { step = 1 }) else TryStep(onDone)
  }
}

@Composable
private fun InstallStep(onContinue: () -> Unit) {
  val context = LocalContext.current
  val status = rememberKeyboardStatus()
  StepPage(
    button = if (status.enabled) "Continue" else "Enable Langboard",
    onButton = { if (status.enabled) onContinue() else context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
  ) {
    StepTitle("Let's put Langboard where you type.")
    Spacer(Modifier.height(28.dp))
    Panel { ModelProgress() }
    Spacer(Modifier.height(28.dp))
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
      SetupLine(
        1, "Add the Langboard keyboard", status.enabled,
        "Android warns that any keyboard can read what you type. Langboard reads only the sentence around " +
          "your cursor, when you switch to it.",
      )
      SetupLine(2, "Switch to it with 🌐 when you need help", done = false, "Your usual keyboard stays one tap away.")
    }
  }
}

/** The Chinese pack's progress, in one line and a bar. */
@Composable
private fun ModelProgress() {
  val state by ModelManager.state.collectAsStateWithLifecycle()
  Text("English ↔ Chinese", style = MaterialTheme.typography.titleMedium)
  Spacer(Modifier.height(10.dp))
  when (val s = state) {
    ModelState.NotInstalled -> Muted("Offline model not downloaded.", MaterialTheme.typography.bodyMedium)
    is ModelState.Downloading -> {
      Muted("Preparing offline model…", MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.weight(1f))
        else LinearProgressIndicator(Modifier.weight(1f))
        s.progress?.let {
          Spacer(Modifier.width(12.dp))
          Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
      }
    }
    is ModelState.Verifying -> {
      Muted("Preparing offline model…", MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(10.dp))
      LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    ModelState.Installed, ModelState.Loading, ModelState.Ready -> Done("Offline model ready")
    is ModelState.Error -> {
      Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
      TextButton(onClick = ModelManager::download, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
    }
  }
}

@Composable
private fun SetupLine(number: Int, title: String, done: Boolean, detail: String) {
  Row {
    Text(if (done) "✓" else "$number", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
    Column(Modifier.weight(1f)) {
      Text(if (done) "Keyboard enabled" else title, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(2.dp))
      Muted(detail, MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun Done(text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Filled.Check, contentDescription = null, tint = LocalAccent.current, modifier = Modifier.size(20.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge)
  }
}

/** 6: one real fill in a real text field. Success is the English gone and Chinese in its place. */
@Composable
private fun TryStep(onDone: () -> Unit) {
  val context = LocalContext.current
  val model by ModelManager.state.collectAsStateWithLifecycle()
  var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
    mutableStateOf(TextFieldValue(TrySentence, selection = TextRange(TrySentence.indexOf("了"))))
  }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  val text = value.text
  val success = text != TrySentence && text.none { it in 'a'..'z' || it in 'A'..'Z' } && text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

  StepPage(
    button = if (success) "Start using Langboard" else "Switch keyboard",
    onButton = { if (success) onDone() else showPicker(context) },
    below = { if (!success) TextButton(onClick = onDone) { Text("Skip for now") } },
  ) {
    StepTitle("Try it once", "Switch to Langboard with 🌐, then tap its answer.")
    Spacer(Modifier.height(28.dp))
    PillTextField(
      value = value,
      onValueChange = { value = it },
      placeholder = "",
      textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 22.sp),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Spacer(Modifier.height(20.dp))
    when {
      success -> Done("You're ready")
      model is ModelState.Downloading || model is ModelState.Verifying ->
        Muted("The offline model is still downloading. The keyboard answers what it can until it's ready.", MaterialTheme.typography.bodyMedium)
    }
  }
}

private const val TrySentence = "这个也太 insane 了吧"
