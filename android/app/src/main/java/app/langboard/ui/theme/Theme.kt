package app.langboard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Two layers (gpt/color_choice.md): the chrome (buttons, tabs, switches, containers) follows the
// phone's Material You palette by default, and falls back to these; content keeps Langboard's own
// identity through [LocalAccent], which never takes the wallpaper's hue.
private val LightColorScheme = lightColorScheme(
  primary = Ink,
  onPrimary = Porcelain,
  primaryContainer = SoftSurfaceHighest,
  onPrimaryContainer = Ink,
  secondary = MutedInk,
  onSecondary = Porcelain,
  secondaryContainer = SoftSurfaceHigh,
  onSecondaryContainer = Ink,
  tertiary = Jade,
  onTertiary = Porcelain,
  background = Porcelain,
  onBackground = Ink,
  surface = Porcelain,
  onSurface = Ink,
  surfaceVariant = SoftSurface,
  onSurfaceVariant = MutedInk,
  surfaceTint = Color.Transparent,
  surfaceContainerLowest = PorcelainLowest,
  surfaceContainerLow = PorcelainLow,
  surfaceContainer = SoftSurface,
  surfaceContainerHigh = SoftSurfaceHigh,
  surfaceContainerHighest = SoftSurfaceHighest,
  surfaceBright = PorcelainLowest,
  surfaceDim = SoftSurfaceHighest,
  inverseSurface = Ink,
  inverseOnSurface = Porcelain,
  inversePrimary = Chalk,
  outline = InkOutline,
  outlineVariant = InkOutlineVariant,
)

private val DarkColorScheme = darkColorScheme(
  primary = Chalk,
  onPrimary = Night,
  primaryContainer = Night4,
  onPrimaryContainer = Chalk,
  secondary = ChalkMuted,
  onSecondary = Night,
  secondaryContainer = Night4,
  onSecondaryContainer = Chalk,
  tertiary = JadeDark,
  onTertiary = Night,
  background = Night,
  onBackground = Chalk,
  surface = Night,
  onSurface = Chalk,
  surfaceVariant = Night3,
  onSurfaceVariant = ChalkMuted,
  surfaceTint = Color.Transparent,
  surfaceContainerLowest = NightLowest,
  surfaceContainerLow = NightLow,
  surfaceContainer = Night2,
  surfaceContainerHigh = Night3,
  surfaceContainerHighest = Night4,
  surfaceBright = Night4,
  surfaceDim = Night,
  inverseSurface = Chalk,
  inverseOnSurface = Night,
  inversePrimary = Ink,
  outline = NightOutline,
  outlineVariant = Night4,
)

/** How Chinese is shown: pinyin over each character, and whether pinyin is colored by tone. */
data class ReadingPrefs(val pinyin: Boolean = true, val toneColors: Boolean = true)

val LocalReadingPrefs = staticCompositionLocalOf { ReadingPrefs() }

/** Tone colors for the current theme, 1–5. */
val LocalToneColors = staticCompositionLocalOf { ToneLight }

/**
 * Langboard jade, fixed whatever the chrome is. Only for what Langboard gave you (the part a
 * suggestion adds), the brand mark and learned states; used everywhere, it would stop meaning anything.
 */
val LocalAccent = staticCompositionLocalOf { Jade }

/** Material You exists from Android 12. */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun LangboardTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  reading: ReadingPrefs = ReadingPrefs(),
  /** Follow the phone's palette for the chrome; false is the Langboard palette. */
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit
) {
  val context = LocalContext.current
  val scheme = when {
    dynamicColor && dynamicColorAvailable -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }
  CompositionLocalProvider(
    LocalReadingPrefs provides reading,
    LocalToneColors provides if (darkTheme) ToneDark else ToneLight,
    LocalAccent provides if (darkTheme) JadeDark else Jade,
  ) {
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
  }
}
