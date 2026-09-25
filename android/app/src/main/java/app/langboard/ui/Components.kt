package app.langboard.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.langboard.R
import app.langboard.history.Expression
import app.langboard.history.Strength
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Horizontal margin shared by every screen. */
val ScreenGutter = 24.dp

val PillShape = RoundedCornerShape(28.dp)
val GroupShape = RoundedCornerShape(20.dp)

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, top: Dp = 24.dp) {
  Column(modifier.padding(horizontal = ScreenGutter).padding(top = top, bottom = 16.dp)) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (subtitle != null) {
      Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Borderless, rounded input on a tinted fill: the search box and the composer. */
@Composable
fun PillTextField(
  value: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  placeholder: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  singleLine: Boolean = false,
  maxLines: Int = if (singleLine) 1 else 6,
  textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
  leadingIcon: (@Composable () -> Unit)? = null,
  trailingIcon: (@Composable () -> Unit)? = null,
) {
  val fill = MaterialTheme.colorScheme.surfaceContainerHigh
  TextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    singleLine = singleLine,
    maxLines = maxLines,
    textStyle = textStyle,
    placeholder = { Text(placeholder, style = textStyle) },
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    shape = PillShape,
    colors = TextFieldDefaults.colors(
      focusedContainerColor = fill,
      unfocusedContainerColor = fill,
      disabledContainerColor = fill.copy(alpha = 0.6f),
      focusedIndicatorColor = Color.Transparent,
      unfocusedIndicatorColor = Color.Transparent,
      disabledIndicatorColor = Color.Transparent,
      focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
      unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
    modifier = modifier.fillMaxWidth(),
  )
}

/** A labeled block of rows on a rounded surface, iOS/Pixel-settings style. */
@Composable
fun SettingsGroup(
  label: String?,
  modifier: Modifier = Modifier,
  footer: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp)) {
    if (label != null) {
      Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
      )
    }
    Surface(shape = GroupShape, color = MaterialTheme.colorScheme.surfaceContainer) {
      Column(Modifier.fillMaxWidth(), content = content)
    }
    if (footer != null) {
      Text(
        footer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
      )
    }
  }
}

/** Hairline between rows in a [SettingsGroup], inset to the text. */
@Composable
fun GroupDivider() {
  HorizontalDivider(
    thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.outlineVariant,
    modifier = Modifier.padding(start = 16.dp),
  )
}

/** Small caps-style label over a block of content. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
  Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      text.uppercase(),
      style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    trailing?.invoke()
  }
}

@Composable
fun Muted(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  maxLines: Int = Int.MAX_VALUE,
  center: Boolean = false,
) {
  Text(
    text,
    style = style,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    textAlign = if (center) TextAlign.Center else null,
    modifier = modifier,
  )
}

/** A rounded block on the tinted surface, for Home and You. */
@Composable
fun Panel(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  color: Color = MaterialTheme.colorScheme.surfaceContainer,
  content: @Composable ColumnScope.() -> Unit,
) {
  val m = modifier.fillMaxWidth()
  if (onClick != null) {
    Surface(onClick = onClick, shape = PanelShape, color = color, modifier = m) { Column(Modifier.padding(20.dp), content = content) }
  } else {
    Surface(shape = PanelShape, color = color, modifier = m) { Column(Modifier.padding(20.dp), content = content) }
  }
}

val PanelShape = RoundedCornerShape(24.dp)

/** A big number with what it counts under it. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(value, style = MaterialTheme.typography.headlineMedium)
    Muted(label, MaterialTheme.typography.bodySmall, maxLines = 2)
  }
}

/** Centered icon, title and one line of help, for a list with nothing in it. */
@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
      contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), contentDescription = null) }
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Muted(body, MaterialTheme.typography.bodyMedium, center = true)
  }
}

/** Back arrow row at the top of a pushed page, with optional actions on the right. */
@Composable
fun PageBar(onBack: () -> Unit, title: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
  Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
    if (title != null) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
    Spacer(Modifier.weight(1f))
    actions()
  }
}

/** How well an expression is held, as a short word and a meter of four dots. */
@Composable
fun StrengthMeter(strength: Strength, modifier: Modifier = Modifier, showLabel: Boolean = true) {
  val filled = when (strength) {
    Strength.New -> 0
    Strength.Learning -> 1
    Strength.Fresh -> 2
    Strength.Settling -> 3
    Strength.Strong -> 4
  }
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    repeat(4) { i ->
      Box(
        Modifier.padding(end = 3.dp).size(6.dp).background(
          if (i < filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
          CircleShape,
        )
      )
    }
    if (showLabel) {
      Spacer(Modifier.width(6.dp))
      Muted(strength.label, MaterialTheme.typography.labelMedium)
    }
  }
}

val Strength.label: String
  get() = when (this) {
    Strength.New -> "New"
    Strength.Learning -> "Learning"
    Strength.Fresh -> "Remembered"
    Strength.Settling -> "Settling in"
    Strength.Strong -> "Yours"
  }

/** One expression in a list: the Chinese, what you were trying to say, and at most one quiet detail. */
@Composable
fun ExpressionRow(e: Expression, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
  Row(
    modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ScreenGutter, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(e.chinese, fontSize = 22.sp, lineHeight = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Muted(e.meaning, MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
    if (detail != null) {
      Spacer(Modifier.width(12.dp))
      Muted(detail, MaterialTheme.typography.labelMedium)
    }
  }
}
