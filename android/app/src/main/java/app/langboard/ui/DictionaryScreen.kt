package app.langboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import app.langboard.ui.theme.LocalReadingPrefs
import app.langboard.ui.theme.LocalToneColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.cedict.DictionaryEntry
import app.langboard.cedict.Pinyin
import app.langboard.dictionary.DictionaryHit
import app.langboard.dictionary.DictionaryManager
import app.langboard.dictionary.DictionaryStatus
import kotlinx.coroutines.delay

@Composable
fun DictionaryScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val manager = remember { DictionaryManager.get(context) }
  val status by manager.status.collectAsStateWithLifecycle()
  val installed = status is DictionaryStatus.Installed ||
    (status as? DictionaryStatus.Failed)?.installed != null

  var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
  val query = field.text
  var hits by remember { mutableStateOf<List<DictionaryHit>>(emptyList()) }
  val list = rememberLazyListState()
  LaunchedEffect(query, installed) {
    if (!installed || query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
    delay(120) // debounce typing
    hits = manager.search(query, limit = RESULT_LIMIT).orEmpty()
    list.scrollToItem(0)
  }

  Column(modifier.fillMaxSize().imePadding()) {
    ScreenTitle("Dictionary")
    PillTextField(
      value = field,
      onValueChange = { field = it },
      enabled = installed,
      singleLine = true,
      placeholder = "English or 汉字",
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.padding(start = 4.dp)) },
      trailingIcon = {
        if (query.isNotEmpty()) IconButton(onClick = { field = TextFieldValue() }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      modifier = Modifier.padding(horizontal = 16.dp),
    )
    Spacer(Modifier.height(8.dp))

    when (val s = status) {
      is DictionaryStatus.NotInstalled -> DownloadCard(manager)
      is DictionaryStatus.Downloading -> Progress(
        "Downloading… ${mb(s.bytes)}${s.totalBytes?.let { " of ${mb(it)}" } ?: ""}",
        s.totalBytes?.let { s.bytes.toFloat() / it },
        manager,
      )
      is DictionaryStatus.Building -> Progress(
        "Setting up… ${s.totalEntries?.let { "${s.entries * 100 / it}%" } ?: ""}",
        s.totalEntries?.let { s.entries.toFloat() / it },
        manager,
      )
      is DictionaryStatus.Failed -> if (s.installed == null) FailedCard(s.message, manager) else Results(query, hits, list)
      is DictionaryStatus.Installed -> Results(query, hits, list)
    }
  }
}

@Composable
private fun DownloadCard(manager: DictionaryManager) {
  InfoCard {
    Text("Offline Chinese–English dictionary", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
      "CC-CEDICT, about 125,000 entries. Downloads about 4 MB from MDBG, the dictionary's publisher, and sets up " +
        "on this phone. After that, search works offline and in the keyboard.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = manager::install) { Text("Download dictionary") }
  }
}

@Composable
private fun FailedCard(message: String, manager: DictionaryManager) {
  InfoCard {
    Text(message, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    Button(onClick = manager::install) { Text("Try again") }
  }
}

@Composable
private fun Progress(label: String, fraction: Float?, manager: DictionaryManager) {
  InfoCard {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    else LinearProgressIndicator(Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = manager::cancel) { Text("Cancel") }
  }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
  SettingsGroup(label = null) {
    Column(Modifier.padding(20.dp)) { content() }
  }
}

@Composable
private fun Results(query: String, hits: List<DictionaryHit>, list: LazyListState) {
  val context = LocalContext.current
  if (query.isBlank()) {
    Hint("Search an English word, or Chinese characters. Tap a result to copy it.")
    return
  }
  if (hits.isEmpty()) {
    Hint("No matches for “${query.trim()}”.")
    return
  }
  LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
    items(hits, key = { "${it.entry.traditional}_${it.entry.simplified}_${it.entry.pinyin}" }) { hit ->
      EntryRow(hit.entry) { copyHeadword(context, hit.entry.simplified) }
      HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = ScreenGutter),
      )
    }
    item {
      Text(
        (if (hits.size >= RESULT_LIMIT) "First $RESULT_LIMIT matches · " else "") + "CC-CEDICT · CC BY-SA 4.0",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(ScreenGutter),
      )
    }
  }
}

@Composable
private fun Hint(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = ScreenGutter, vertical = 12.dp),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryRow(entry: DictionaryEntry, onClick: () -> Unit) {
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  Column(
    Modifier
      .fillMaxWidth()
      .clickable(onClickLabel = "Copy ${entry.simplified}", role = Role.Button, onClick = onClick)
      .padding(horizontal = ScreenGutter, vertical = 14.dp),
  ) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.Bottom) {
      Text(entry.simplified, fontSize = 26.sp, lineHeight = 32.sp)
      if (entry.traditional != entry.simplified) {
        Text(entry.traditional, fontSize = 18.sp, lineHeight = 30.sp, color = muted)
      }
      Text(tonedPinyin(entry.pinyin), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 2.dp))
    }
    Spacer(Modifier.height(6.dp))
    // Every sense, numbered when there's more than one. Nothing is cut off.
    Text(
      buildAnnotatedString {
        val many = entry.english.size > 1
        entry.english.forEachIndexed { i, sense ->
          if (many) withStyle(SpanStyle(color = muted, fontWeight = FontWeight.SemiBold)) { append("${i + 1} ") }
          append(sense)
          if (i < entry.english.lastIndex) append("   ")
        }
      },
      style = MaterialTheme.typography.bodyMedium,
    )
  }
}

/** Tone-marked pinyin, each syllable colored by its tone when the user has tone colors on. */
@Composable
private fun tonedPinyin(numbered: String): AnnotatedString {
  val prefs = LocalReadingPrefs.current
  val tones = LocalToneColors.current
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  return buildAnnotatedString {
    numbered.split(' ').forEachIndexed { i, syllable ->
      if (i > 0) append(' ')
      val tone = syllable.lastOrNull()?.digitToIntOrNull()
      val color = if (prefs.toneColors && tone != null) tones[(tone - 1).coerceIn(0, 4)] else muted
      withStyle(SpanStyle(color = color)) { append(Pinyin.toToneMarks(syllable)) }
    }
  }
}

private fun copyHeadword(context: Context, text: String) {
  context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Langboard", text))
  // Android 13+ shows its own copy confirmation.
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied $text", Toast.LENGTH_SHORT).show()
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

private const val RESULT_LIMIT = 200
