package app.langboard.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.langboard.core.LangboardSettings

data class KeyboardStatus(val enabled: Boolean, val current: Boolean, val used: Boolean) {
  val ready get() = enabled && (used || current)
}

fun readKeyboardStatus(context: Context): KeyboardStatus {
  val imm = context.getSystemService(InputMethodManager::class.java)
  val pkg = context.packageName
  val enabled = imm.enabledInputMethodList.any { it.packageName == pkg }
  val currentId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    imm.currentInputMethodInfo?.id
  } else {
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
  }
  val current = currentId?.startsWith("$pkg/") == true
  return KeyboardStatus(enabled, current, LangboardSettings(context).keyboardUsed)
}

/**
 * Re-reads on resume (back from system Settings) and on window focus (the IME picker is a
 * dialog over this Activity, so closing it doesn't resume us).
 */
@Composable
fun rememberKeyboardStatus(): KeyboardStatus {
  val context = LocalContext.current
  val view = LocalView.current
  var status by remember { mutableStateOf(readKeyboardStatus(context)) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = readKeyboardStatus(context) }
  DisposableEffect(view) {
    val listener = ViewTreeObserver.OnWindowFocusChangeListener { status = readKeyboardStatus(context) }
    view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
    onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
  }
  return status
}
