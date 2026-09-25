package app.langboard.ui.theme

import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme()
private val DarkColorScheme = darkColorScheme()

/** How Chinese is shown: pinyin over each character, and whether pinyin is colored by tone. */
data class ReadingPrefs(val pinyin: Boolean = true, val toneColors: Boolean = true)

val LocalReadingPrefs = staticCompositionLocalOf { ReadingPrefs() }

/** Tone colors for the current theme, 1–5. */
val LocalToneColors = staticCompositionLocalOf { ToneLight }

@Composable
fun LangboardTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  reading: ReadingPrefs = ReadingPrefs(),
  content: @Composable () -> Unit
) {
  val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val context = LocalContext.current
    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  } else {
    if (darkTheme) DarkColorScheme else LightColorScheme
  }
  CompositionLocalProvider(LocalReadingPrefs provides reading, LocalToneColors provides if (darkTheme) ToneDark else ToneLight) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
  }
}
