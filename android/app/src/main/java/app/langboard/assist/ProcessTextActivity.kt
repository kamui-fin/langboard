package app.langboard.assist

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.langboard.core.Candidate
import app.langboard.core.DetectResult
import app.langboard.core.Detection
import app.langboard.core.EditPlan
import app.langboard.core.FillGapRequest
import app.langboard.core.FillGapResult
import app.langboard.core.FragmentDetector
import app.langboard.core.LangboardSettings
import app.langboard.billing.Subscription
import app.langboard.model.Engines
import app.langboard.model.ModelManager
import app.langboard.ui.RubyText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import app.langboard.ui.theme.LangboardTheme

/**
 * Basic mode, no special permission: select text, pick "Langboard" in the selection menu. The
 * selection can be just the English or a whole mixed sentence; only the English is replaced and the
 * edited selection goes back to the app. Read-only selections get Copy instead.
 */
class ProcessTextActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
    // English at the end of the selection, or the whole selection when it is only English.
    val detection = (FragmentDetector.detect(selected, null, "", beforeIsComplete = true) as? DetectResult.Found)?.detection

    val settings = LangboardSettings(this)
    val unlocked = Subscription.activeOffline(settings)
    setContent {
      LangboardTheme(dynamicColor = settings.colorSource == LangboardSettings.ColorSource.DEVICE) {
        Sheet(onDismiss = ::finish) {
          if (!unlocked) {
            Message("Open Langboard to start your free trial.")
          } else if (detection == null) {
            Message("Select some English to turn into Chinese.")
          } else {
            Suggestions(detection, readOnly) { c -> finishWith(selected, detection, c, readOnly) }
          }
        }
      }
    }
  }

  private fun finishWith(selected: String, d: Detection, c: Candidate, readOnly: Boolean) {
    val out = EditPlan.replace(d, c.text).applyTo(selected, "", "") ?: return finish()
    if (readOnly) {
      getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Langboard", c.text))
      setResult(RESULT_CANCELED)
    } else {
      setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, out))
    }
    finish()
  }
}

@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
  Box(
    Modifier
      .fillMaxSize()
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      modifier = Modifier
        .fillMaxWidth()
        // Swallow taps inside the sheet so they don't dismiss it.
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
      Column(Modifier.navigationBarsPadding().padding(vertical = 12.dp)) {
        Box(
          Modifier
            .align(Alignment.CenterHorizontally)
            .width(32.dp)
            .height(4.dp)
            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(12.dp))
        content()
      }
    }
  }
}

@Composable
private fun Message(text: String) {
  Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
}

@Composable
private fun Suggestions(d: Detection, readOnly: Boolean, onPick: (Candidate) -> Unit) {
  var result by remember { mutableStateOf<FillGapResult?>(null) }
  var done by remember { mutableStateOf(false) }
  val context = LocalContext.current
  LaunchedEffect(d) {
    ModelManager.init(context)
    result = runCatching { Engines.fill.fill(FillGapRequest(d.contextBefore, d.fragment, d.contextAfter)) }.getOrNull()
    done = true
  }
  Text(
    "“${d.fragment}”",
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 20.dp),
  )
  Spacer(Modifier.height(8.dp))
  val r = result
  when {
    !done -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
    r == null && !ModelManager.state.value.installed -> Message("Download the Chinese model in the Langboard app first.")
    r == null -> Message("No natural phrase for this one yet.")
    else -> r.candidates.forEachIndexed { i, c ->
      if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
      Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (readOnly) "Copy" else "Replace") { onPick(c) }
          .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(verticalAlignment = Alignment.Bottom) {
          RubyText(c.text, fontSize = 22.sp)
        }
        val gloss = listOfNotNull(c.meaning?.let { "= $it" }, c.nuance).joinToString(" · ")
        if (gloss.isNotEmpty()) {
          Text(gloss, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
  if (readOnly && r != null) {
    Text(
      "This text can't be edited here. Tap a phrase to copy it.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
  }
}
