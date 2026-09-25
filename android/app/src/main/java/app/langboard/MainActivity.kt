package app.langboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.langboard.billing.Subscription
import app.langboard.model.ModelManager
import app.langboard.core.LangboardSettings
import app.langboard.ui.AppLook
import app.langboard.ui.AppReading
import app.langboard.ui.LangboardRoot
import app.langboard.ui.theme.LangboardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ModelManager.init(this)
    // Loading takes about half a second once the file is cached; start it before the keyboard needs it.
    ModelManager.preload()
    Subscription.init(this)
    enableEdgeToEdge()
    val settings = LangboardSettings(this)
    AppReading.load(settings)
    AppLook.load(settings)
    setContent {
      LangboardTheme(reading = AppReading.prefs, dynamicColor = AppLook.dynamicColor) {
        LangboardRoot()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Every return to the app: a renewal, cancellation or refund shows up here, and the keyboard reads it after.
    lifecycleScope.launch { Subscription.refresh() }
  }
}
