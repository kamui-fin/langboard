package app.langboard.ui.theme

import androidx.compose.ui.graphics.Color

// Monochrome palette: black / white / grays only (spec §7).
val Ink = Color(0xFF111111)
val Ink2 = Color(0xFF3A3A3A)
val Gray50 = Color(0xFF6E6E6E)
val Gray70 = Color(0xFFB4B4B4)
val Gray85 = Color(0xFFD9D9D9)
val Gray93 = Color(0xFFEDEDED)
val Gray96 = Color(0xFFF5F5F5)
val Paper = Color(0xFFFFFFFF)

val Night = Color(0xFF0E0E0E)
val Night2 = Color(0xFF1A1A1A)
val Night3 = Color(0xFF242424)
val Night4 = Color(0xFF303030)
val Chalk = Color(0xFFF2F2F2)

/** The one accent, used only where something needs pointing out: the part a suggestion adds or changes. */
val Accent = Color(0xFFF76F53)

/** Pinyin by tone: 1 high, 2 rising, 3 dipping, 4 falling, 5 neutral. Set apart from [Accent]. */
val ToneLight = listOf(Color(0xFFD1344B), Color(0xFF2A8A57), Color(0xFF3563C9), Color(0xFF8A45B8), Color(0xFF7A7A7A))
val ToneDark = listOf(Color(0xFFFF7A8C), Color(0xFF5DCB8C), Color(0xFF7DA2FF), Color(0xFFC58BF0), Color(0xFF9A9A9A))
