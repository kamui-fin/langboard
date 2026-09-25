package app.langboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.langboard.model.ModelManager
import app.langboard.core.LangboardSettings
import app.langboard.ui.AppReading
import app.langboard.ui.LangboardApp
import app.langboard.ui.theme.LangboardTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ModelManager.init(this)
    // Loading takes about half a second once the file is cached; start it before the keyboard needs it.
    ModelManager.preload()
    enableEdgeToEdge()
    AppReading.load(LangboardSettings(this))
    setContent {
      LangboardTheme(reading = AppReading.prefs) {
        LangboardApp()
      }
    }
  }
}
