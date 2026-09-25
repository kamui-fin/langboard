package app.langboard.ui

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
import androidx.compose.ui.unit.dp

/** Horizontal margin shared by every screen. */
val ScreenGutter = 24.dp

val PillShape = RoundedCornerShape(28.dp)
val GroupShape = RoundedCornerShape(20.dp)

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
  Column(modifier.padding(horizontal = ScreenGutter).padding(top = 24.dp, bottom = 16.dp)) {
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
