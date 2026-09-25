package app.langboard.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.langboard.assist.ConversationContextService
import app.langboard.core.LangboardSettings

fun isContextEnabled(context: Context): Boolean {
  val am = context.getSystemService(AccessibilityManager::class.java)
  val name = ConversationContextService::class.java.name
  return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
    it.resolveInfo.serviceInfo.packageName == context.packageName && it.resolveInfo.serviceInfo.name == name
  }
}

private fun openAccessibilitySettings(context: Context) {
  context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Settings group: on/off through the disclosure, and what's always skipped. */
@Composable
fun ConversationContextSettings() {
  val context = LocalContext.current
  val settings = remember { LangboardSettings(context) }
  var enabled by remember { mutableStateOf(isContextEnabled(context)) }
  var disclose by remember { mutableStateOf(false) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = isContextEnabled(context) }

  SettingsGroup(
    "Conversation context",
    footer = "Always skipped: password fields, sign-in and payment screens, and banking, wallet and password-manager apps.",
  ) {
    // Android only lets the user turn an accessibility service off in its own settings.
    SwitchRow(
      title = "Read the conversation",
      summary = if (enabled) "When you switch to Langboard, it also reads the text on screen so suggestions fit the conversation."
      else "Let Langboard read the text on screen when you switch to it, for suggestions that fit what the other person said.",
      checked = enabled,
    ) { if (enabled) openAccessibilitySettings(context) else disclose = true }
  }

  if (disclose) {
    ContextDisclosure(
      onContinue = {
        disclose = false
        settings.contextConsent = true
        openAccessibilitySettings(context)
      },
      onDecline = {
        disclose = false
        settings.contextConsent = false
      },
    )
  }
}

/**
 * The prominent disclosure Google Play requires before we send anyone to Accessibility settings:
 * what is read, when, why, where it goes, and an explicit choice.
 */
@Composable
fun ContextDisclosure(onContinue: () -> Unit, onDecline: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDecline,
    title = { Text("Let Langboard read the screen") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          "Langboard uses Android's Accessibility Service to read the text on screen, only at the moment you " +
            "switch to the Langboard keyboard."
        )
        Text(
          "This lets its suggestions fit the conversation, and lets it explain the latest message you received " +
            "when you switch to it with nothing typed."
        )
        Text(
          "Your text is processed entirely on your device and is never sent to our servers. The screen text isn't " +
            "saved; with history on, a message you ask Langboard to explain is kept in your history on this phone.",
          fontWeight = FontWeight.SemiBold,
        )
        Text("It doesn't watch what you type, draw on your screen, or change any text.")
        Text(
          "It never reads password fields, sign-in or payment screens, or banking and password-manager apps.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "Next, Android's Accessibility settings open. Choose Langboard Conversation Context and turn it on.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = { Button(onClick = onContinue) { Text("Continue") } },
    dismissButton = { TextButton(onClick = onDecline) { Text("Not now") } },
  )
}
