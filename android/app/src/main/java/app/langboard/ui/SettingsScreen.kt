package app.langboard.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.BuildConfig
import app.langboard.core.AssistMode
import app.langboard.core.LangboardSettings
import app.langboard.core.LangboardSettings.AfterInsert
import app.langboard.dictionary.DictionaryManager
import app.langboard.dictionary.DictionaryStatus
import app.langboard.history.HistoryStore
import app.langboard.ime.description
import app.langboard.ime.label
import app.langboard.ui.theme.ReadingPrefs
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val settings = remember { LangboardSettings(context) }

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
    ScreenTitle("Settings")
    SuggestionSettings(settings)
    ReadingSettings(settings)
    ConversationContextSettings()
    HistorySettings(settings)
    ReviewSettings(settings)
    SettingsGroup("Chinese model") { ChineseModelCard(Modifier.padding(16.dp)) }
    DictionarySettings()
    AboutSettings()
  }
}

@Composable
private fun SuggestionSettings(settings: LangboardSettings) {
  val context = LocalContext.current
  var assistMode by remember { mutableStateOf(settings.assistMode) }
  var optionCount by remember { mutableStateOf(settings.optionCount) }
  var afterInsert by remember { mutableStateOf(settings.afterInsert) }

  SettingsGroup("How much help") {
    Column(Modifier.selectableGroup()) {
      AssistMode.entries.forEachIndexed { i, m ->
        if (i > 0) GroupDivider()
        Row(
          Modifier
            .fillMaxWidth()
            .selectable(selected = assistMode == m, role = Role.RadioButton) { assistMode = m; settings.assistMode = m }
            .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(selected = assistMode == m, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
          Spacer(Modifier.width(4.dp))
          RowText(m.label, m.description, Modifier.weight(1f))
        }
      }
    }
  }

  SettingsGroup("Keyboard") {
    ChoiceRow(
      title = "Options to show",
      choices = LangboardSettings.OPTION_COUNTS.map { it to if (it == LangboardSettings.OPTION_COUNTS.last()) "All $it" else "$it" },
      selected = optionCount,
    ) { optionCount = it; settings.optionCount = it }
    GroupDivider()
    ChoiceRow(
      title = "After inserting a phrase",
      choices = listOf(AfterInsert.STAY to "Stay, with Undo", AfterInsert.RETURN to "Back to my keyboard"),
      selected = afterInsert,
    ) { afterInsert = it; settings.afterInsert = it }
    GroupDivider()
    NavRow("Keyboard settings", "Languages, and which keyboards are on") {
      context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }
  }
}

/** The app's reading preferences, shared with the theme so a change shows at once. The keyboard reads them when it opens. */
object AppReading {
  var prefs by mutableStateOf(ReadingPrefs())

  fun load(settings: LangboardSettings) {
    prefs = ReadingPrefs(settings.showPinyin, settings.toneColors)
  }
}

@Composable
private fun ReadingSettings(settings: LangboardSettings) {
  val prefs = AppReading.prefs
  SettingsGroup("Chinese text") {
    RubyText("我今天有点懒得出门了", fontSize = 22.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
    GroupDivider()
    SwitchRow("Pinyin over Chinese", "In the keyboard, History and the dictionary.", prefs.pinyin) {
      settings.showPinyin = it
      AppReading.load(settings)
    }
    GroupDivider()
    SwitchRow("Color pinyin by tone", "1st red, 2nd green, 3rd blue, 4th purple, neutral gray.", prefs.toneColors, enabled = prefs.pinyin) {
      settings.toneColors = it
      AppReading.load(settings)
    }
  }
}

@Composable
private fun HistorySettings(settings: LangboardSettings) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var keep by remember { mutableStateOf(settings.keepHistory) }
  var confirm by remember { mutableStateOf(false) }
  SettingsGroup("History") {
    SwitchRow(
      "Keep history",
      "What you asked and what Langboard answered, on this phone only. Saved phrases are kept either way.",
      keep,
    ) { keep = it; settings.keepHistory = it }
    GroupDivider()
    ActionRow("Clear history", destructive = true) { confirm = true }
  }
  if (confirm) {
    AlertDialog(
      onDismissRequest = { confirm = false },
      title = { Text("Clear history?") },
      text = { Text("This deletes every entry, including saved phrases. It can't be undone.") },
      confirmButton = {
        TextButton(onClick = {
          confirm = false
          scope.launch { HistoryStore.get(context).clear() }
        }) { Text("Clear") }
      },
      dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun DictionarySettings() {
  val context = LocalContext.current
  val manager = remember { DictionaryManager.get(context) }
  val status by manager.status.collectAsStateWithLifecycle()
  val installed = when (val s = status) {
    is DictionaryStatus.Installed -> s.info
    is DictionaryStatus.Failed -> s.installed
    else -> null
  }
  val busy = status is DictionaryStatus.Downloading || status is DictionaryStatus.Building
  SettingsGroup("Dictionary") {
    RowText(
      "CC-CEDICT",
      when (val s = status) {
        is DictionaryStatus.Downloading -> "Downloading…"
        is DictionaryStatus.Building -> "Setting up…"
        is DictionaryStatus.Failed -> s.message
        else -> installed?.let {
          "${"%,d".format(it.entries)} entries · ${it.sourceDate?.take(10) ?: "unknown date"} · ${it.sizeBytes / 1_048_576} MB"
        } ?: "Not downloaded"
      },
      Modifier.padding(16.dp),
    )
    GroupDivider()
    when {
      busy -> ActionRow("Cancel", onClick = manager::cancel)
      installed == null -> ActionRow("Download", onClick = manager::install)
      else -> {
        ActionRow("Check for updates", onClick = manager::install)
        GroupDivider()
        ActionRow("Remove dictionary", destructive = true, onClick = manager::remove)
      }
    }
  }
}

@Composable
private fun AboutSettings() {
  SettingsGroup("Privacy") {
    Text(
      "Everything is processed on your device. Langboard reads only the sentence around your cursor when you " +
        "switch to it and, with conversation context on, the text on screen. With history on, it keeps what you " +
        "asked and what it answered on this phone, never the screen text, and never sends any of it anywhere. " +
        "History is left out of backups.\n\nDictionary searches never leave your phone; the dictionary itself is " +
        "downloaded once from MDBG, and the Chinese model once from Hugging Face. No account, no ads, no analytics.",
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.padding(16.dp),
    )
  }
  SettingsGroup(
    "About",
    footer = "Dictionary: CC-CEDICT, published by MDBG under CC BY-SA 4.0. English–Chinese quick matches: " +
      "Open Dictionary v2.0 (github.com/ahpxex/open-dictionary), derived from English Wiktionary, under CC BY-SA 4.0. " +
      "Chinese model: Hy-MT2 1.8B by Tencent (Apache 2.0), run with llama.cpp (MIT).",
  ) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Version", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

// ---------------------------------------------------------------- Rows

@Composable
private fun RowText(title: String, summary: String?, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    if (summary != null) {
      Spacer(Modifier.height(2.dp))
      Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
internal fun SwitchRow(title: String, summary: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
      .heightIn(min = 56.dp)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RowText(title, summary, Modifier.weight(1f))
    Spacer(Modifier.width(16.dp))
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}

@Composable
private fun NavRow(title: String, summary: String?, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RowText(title, summary, Modifier.weight(1f))
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun ActionRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      title,
      style = MaterialTheme.typography.bodyLarge,
      color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
  }
}

@Composable
private fun <T> ChoiceRow(title: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      choices.forEachIndexed { i, (value, label) ->
        SegmentedButton(
          selected = value == selected,
          onClick = { onSelect(value) },
          shape = SegmentedButtonDefaults.itemShape(i, choices.size),
          icon = {},
          label = { Text(label, maxLines = 1) },
        )
      }
    }
  }
}
