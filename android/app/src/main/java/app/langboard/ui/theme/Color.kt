package app.langboard.ui.theme

import androidx.compose.ui.graphics.Color

// The Langboard palette: paper, ink and one annotation color (gpt/color_choice.md). The chrome uses it
// only when the user picks "Langboard" in Settings or the phone has no dynamic color; content always
// uses Jade for what Langboard taught you, whatever the chrome is.
val Ink = Color(0xFF171A18)
val MutedInk = Color(0xFF676C69)
val Porcelain = Color(0xFFF7F7F3)
val PorcelainLowest = Color(0xFFFCFCFA)
val PorcelainLow = Color(0xFFF3F3EF)
val SoftSurface = Color(0xFFEFEEE9)
val SoftSurfaceHigh = Color(0xFFE9E8E2)
val SoftSurfaceHighest = Color(0xFFE3E2DB)
val InkOutline = Color(0xFFA9ADA9)
val InkOutlineVariant = Color(0xFFD9D8D1)

val Night = Color(0xFF121413)
val NightLowest = Color(0xFF0D0F0E)
val NightLow = Color(0xFF171A18)
val Night2 = Color(0xFF1C1F1D)
val Night3 = Color(0xFF242826)
val Night4 = Color(0xFF2E3230)
val Chalk = Color(0xFFECEDE8)
val ChalkMuted = Color(0xFFA3A8A4)
val NightOutline = Color(0xFF6D726F)

/** Langboard jade: the brand color, and in content the piece of language Langboard gave you. */
val Jade = Color(0xFF347A68)
val JadeDark = Color(0xFF79C3AE)

/** Pinyin by tone: 1 high, 2 rising, 3 dipping, 4 falling, 5 neutral. No green, so nothing reads as [Jade]. */
val ToneLight = listOf(Color(0xFFD1344B), Color(0xFFC0701A), Color(0xFF3563C9), Color(0xFF8A45B8), Color(0xFF7A7A7A))
val ToneDark = listOf(Color(0xFFFF7A8C), Color(0xFFF0A04B), Color(0xFF7DA2FF), Color(0xFFC58BF0), Color(0xFF9A9A9A))
