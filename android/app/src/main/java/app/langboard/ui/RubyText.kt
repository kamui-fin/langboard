package app.langboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.langboard.dictionary.Readings
import app.langboard.ui.theme.LocalReadingPrefs
import app.langboard.ui.theme.LocalToneColors

/**
 * Chinese with pinyin over each character (when the user has pinyin on), colored by tone. The
 * characters in [highlight] (indexes into [text]) are drawn in the accent color: what a suggestion
 * adds to the sentence. Falls back to plain text while readings load and when pinyin is off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubyText(
  text: String,
  modifier: Modifier = Modifier,
  fontSize: TextUnit = 22.sp,
  color: Color = LocalContentColor.current,
  highlight: List<IntRange> = emptyList(),
  maxLines: Int = Int.MAX_VALUE,
) {
  val prefs = LocalReadingPrefs.current
  val accentColor = MaterialTheme.colorScheme.primary
  val context = LocalContext.current
  val readings = Readings.get(context)
  // produceState keeps its last value across new text; only use readings that belong to this text.
  val cached = remember(text) { readings.cached(text) }
  val loaded by produceState(cached, text, prefs.pinyin) {
    if (prefs.pinyin) value = readings.of(text)
  }
  val pieces = loaded?.takeIf { l -> l.joinToString("") { it.text } == text } ?: cached
  val accent = { i: Int -> highlight.any { i in it } }
  val shown = pieces
  if (!prefs.pinyin || shown == null) {
    Text(
      highlighted(text, highlight, accentColor),
      fontSize = fontSize,
      color = color,
      maxLines = maxLines,
      overflow = TextOverflow.Ellipsis,
      modifier = modifier.padding(top = if (prefs.pinyin) pinyinSize(fontSize).value.dp + 2.dp else 0.dp),
    )
    return
  }
  val tones = LocalToneColors.current
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  FlowRow(
    modifier.clearAndSetSemantics { contentDescription = text + ", " + shown.mapNotNull { it.pinyin }.joinToString(" ") },
    maxLines = maxLines,
  ) {
    var at = 0
    for (p in shown) {
      val start = at
      at += p.text.length
      val lit = accent(start)
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = if (p.pinyin != null) 1.dp else 0.dp)) {
        Text(
          p.pinyin ?: "",
          fontSize = pinyinSize(fontSize),
          lineHeight = pinyinSize(fontSize) * 1.15f,
          maxLines = 1,
          color = if (prefs.toneColors && p.pinyin != null) tones[(p.tone - 1).coerceIn(0, 4)] else muted,
        )
        Text(
          p.text,
          fontSize = fontSize,
          lineHeight = fontSize * 1.2f,
          maxLines = 1,
          color = if (lit) accentColor else color,
          fontWeight = if (lit) FontWeight.SemiBold else null,
        )
      }
    }
  }
}

/** Pinyin sized to sit over its character without crowding the next one. */
private fun pinyinSize(fontSize: TextUnit): TextUnit = (fontSize.value * 0.46f).coerceIn(10f, 18f).sp

private fun highlighted(text: String, ranges: List<IntRange>, accentColor: Color) = androidx.compose.ui.text.buildAnnotatedString {
  append(text)
  for (r in ranges) {
    if (r.first < 0 || r.last >= text.length) continue
    addStyle(androidx.compose.ui.text.SpanStyle(color = accentColor, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
}
}
