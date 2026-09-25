package app.langboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.langboard.R
import app.langboard.core.DetectResult
import app.langboard.core.EditPlan
import app.langboard.core.FillGapRequest
import app.langboard.core.FragmentDetector
import app.langboard.core.Candidate
import app.langboard.history.HistoryEntry
import app.langboard.history.HistoryKind
import app.langboard.history.HistoryStore
import app.langboard.model.Engines
import app.langboard.model.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal sealed interface Turn {
  val id: Int

  data class Sent(override val id: Int, val text: String) : Turn
  data class Working(override val id: Int) : Turn
  /** [options] are the model's answers, best first; [chosen] is the one shown in the sentence. */
  data class Reply(
    override val id: Int,
    val source: String,
    val before: String,
    val options: List<String>,
    val chosen: Int,
    val after: String,
    /** The Memory entry, once saved. */
    val savedId: Long? = null,
  ) : Turn {
    val replacement get() = options[chosen]
    val text get() = before + replacement + after
  }
  data class Note(override val id: Int, val text: String) : Turn
}

/**
 * The Expression Lab thread: trying a sentence outside any chat. Held by [LangboardApp] so it
 * survives leaving the Lab; kept nowhere unless the user saves a reply to Memory.
 */
class WriteChat {
  internal val turns = mutableStateListOf<Turn>()
  var draft by mutableStateOf(TextFieldValue())
  private var nextId = 0
  internal fun id() = nextId++
  internal val busy get() = turns.lastOrNull() is Turn.Working

  /** Saves [r] to Memory (as a lookup made in the Lab), or un-saves it. */
  internal fun toggleSave(r: Turn.Reply, store: HistoryStore, scope: CoroutineScope) {
    val at = turns.indexOfFirst { it.id == r.id }.takeIf { it >= 0 } ?: return
    scope.launch {
      if (r.savedId != null) {
        store.delete(r.savedId)
        turns[at] = r.copy(savedId = null)
      } else {
        val id = store.add(
          HistoryEntry(
            createdAt = System.currentTimeMillis(),
            kind = HistoryKind.Fill,
            app = "app.langboard",
            source = r.source,
            answer = r.replacement,
            sentence = r.text,
            items = r.options.filterIndexed { i, _ -> i != r.chosen }.map { Candidate(it, null) },
            saved = true,
          )
        )
        turns[at] = r.copy(savedId = id)
      }
    }
  }

  internal fun send(scope: CoroutineScope) {
    val field = draft
    if (field.text.isBlank() || busy) return
    val text = field.text
    val sel = field.selection
    val before = text.substring(0, sel.min)
    val selected = text.substring(sel.min, sel.max)
    val after = text.substring(sel.max)
    turns += Turn.Sent(id(), text)
    draft = TextFieldValue()
    fun note(msg: String) { turns += Turn.Note(id(), msg) }
    val found = when (val r = FragmentDetector.detect(before, selected, after, beforeIsComplete = true)) {
      is DetectResult.Found -> r.detection
      DetectResult.None -> return note("Add some English after the Chinese, and Langboard will fill it in.")
      DetectResult.Ambiguous -> return note("Select the English words you want in Chinese, then send.")
      DetectResult.TooLong -> return note("That's a long phrase. Try a shorter part.")
    }
    if (!ModelManager.state.value.installed) return note("Download the Chinese model first. It runs on this phone.")
    val working = Turn.Working(id())
    turns += working
    scope.launch {
      val request = FillGapRequest(found.contextBefore, found.fragment, found.contextAfter)
      val r = runCatching { Engines.fill.fill(request) }.getOrNull()
      val reply: Turn = if (r == null) {
        Turn.Note(working.id, "No natural phrase for “${found.fragment}”. Try a shorter phrase.")
      } else {
        val plan = EditPlan.replace(found, r.replacement)
        val out = plan.applyTo(before, selected, after)
        // Split around the replacement, so any option can be shown in its place.
        if (out == null) Turn.Note(working.id, "The text changed; try again.")
        else Turn.Reply(
          id = working.id,
          source = found.fragment,
          before = out.substring(0, out.length - after.length - plan.commit.length),
          options = r.candidates.map { it.text },
          chosen = 0,
          after = out.substring(out.length - after.length - plan.commit.length + r.replacement.length),
        )
      }
      val at = turns.indexOfFirst { it.id == working.id }
      if (at >= 0) turns[at] = reply
    }
  }
}

@Composable
fun WriteScreen(chat: WriteChat, onBack: () -> Unit, modifier: Modifier = Modifier) {
  val status = rememberKeyboardStatus()
  val model by ModelManager.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val list = rememberLazyListState()
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }

  // Keep the newest turn in view.
  LaunchedEffect(chat.turns.size, chat.turns.lastOrNull()) {
    if (chat.turns.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
  }

  Column(modifier.fillMaxSize().imePadding()) {
    LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()) {
      item {
        PageBar(onBack = onBack) {
          if (status.ready) {
            TextButton(onClick = { showPicker(context) }, modifier = Modifier.padding(end = 4.dp)) {
              Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text("Switch keyboard")
            }
          }
        }
      }
      item { ScreenTitle("Expression Lab", "Write the Chinese you know. Use English where you get stuck.", top = 0.dp) }
      if (!model.installed) {
        item {
          SettingsGroup(label = null) { ChineseModelCard(Modifier.padding(20.dp)) }
        }
      }
      items(chat.turns, key = { it.id }) { turn ->
        when (turn) {
          is Turn.Sent -> SentBubble(turn.text)
          is Turn.Working -> Thinking()
          is Turn.Note -> NoteLine(turn.text)
          is Turn.Reply -> ReplyCard(
            turn,
            onChoose = { i -> chat.turns.indexOfFirst { it.id == turn.id }.takeIf { it >= 0 }?.let { chat.turns[it] = turn.copy(chosen = i) } },
            onEdit = { chat.draft = TextFieldValue(turn.text, TextRange(turn.text.length)) },
            onCopy = { copy(context, turn.text) },
            onSave = { chat.toggleSave(turn, store, scope) },
          )
        }
      }
      item { Spacer(Modifier.height(12.dp)) }
    }
    Composer(chat, onSend = { chat.send(scope) })
  }
}

@Composable
private fun Composer(chat: WriteChat, onSend: () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    PillTextField(
      value = chat.draft,
      onValueChange = { chat.draft = it },
      placeholder = "我想 go for a walk…",
      textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    FilledIconButton(
      onClick = onSend,
      enabled = chat.draft.text.isNotBlank() && !chat.busy,
      modifier = Modifier.size(56.dp),
    ) {
      Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Make Chinese")
    }
  }
}

@Composable
private fun SentBubble(text: String) {
  Box(Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 12.dp), contentAlignment = Alignment.CenterEnd) {
    Surface(
      shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
      Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
  }
}

@Composable
private fun Thinking() {
  Row(Modifier.padding(horizontal = ScreenGutter, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    Spacer(Modifier.width(12.dp))
    Text("Finding the Chinese…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun NoteLine(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = ScreenGutter, end = 64.dp, top = 12.dp),
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyCard(r: Turn.Reply, onChoose: (Int) -> Unit, onEdit: () -> Unit, onCopy: () -> Unit, onSave: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.padding(start = 16.dp, end = 32.dp, top = 12.dp).widthIn(min = 120.dp),
  ) {
    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
      RubyText(
        r.text,
        fontSize = 22.sp,
        highlight = listOf(r.before.length until r.before.length + r.replacement.length),
        modifier = Modifier.padding(end = 8.dp),
      )
      if (r.options.size > 1) {
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
          // A handful is enough to compare; the rest are rarely better.
          r.options.take(MAX_LAB_OPTIONS).forEachIndexed { i, o ->
            Surface(
              onClick = { onChoose(i) },
              shape = RoundedCornerShape(12.dp),
              color = if (i == r.chosen) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
              Text(o, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
            }
          }
        }
      }
      Row(Modifier.align(Alignment.End)) {
        IconButton(onClick = onSave) {
          Icon(
            painterResource(if (r.savedId != null) R.drawable.ic_star else R.drawable.ic_star_border),
            contentDescription = if (r.savedId != null) "Remove from Memory" else "Save to Memory",
            modifier = Modifier.size(20.dp),
          )
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
        IconButton(onClick = onCopy) {
          Icon(painterResource(R.drawable.ic_content_copy), contentDescription = "Copy", modifier = Modifier.size(20.dp))
        }
      }
    }
  }
}

@Composable
internal fun SetupCard(status: KeyboardStatus) {
  val context = LocalContext.current
  val store = remember { HistoryStore.get(context) }
  val done = listOf(status.enabled, status.ready).count { it }
  SettingsGroup(label = null) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp)) {
      Text("Set up the keyboard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      Text("$done of 2", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Step(
      done = status.enabled,
      number = 1,
      title = "Turn on Langboard",
      detail = "Android warns that any keyboard can read what you type. Langboard only reads the sentence " +
        "around your cursor when you open it. It stays on this phone and is never saved.",
      action = "Open settings",
      enabled = !status.enabled,
    ) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
    GroupDivider()
    Step(
      done = status.ready,
      number = 2,
      title = "Switch to it",
      detail = "Choose Langboard from the keyboard list. Your regular keyboard is one tap away.",
      action = "Choose keyboard",
      enabled = status.enabled && !status.ready,
    ) { showPicker(context) }
  }
}

@Composable
private fun Step(done: Boolean, number: Int, title: String, detail: String, action: String, enabled: Boolean, onClick: () -> Unit) {
  Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
    Surface(
      shape = RoundedCornerShape(50),
      color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
      modifier = Modifier.size(28.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (done) Icon(Icons.Filled.Check, contentDescription = "Done", modifier = Modifier.size(16.dp))
        else Text("$number", style = MaterialTheme.typography.labelLarge)
      }
    }
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f).padding(top = 3.dp)) {
      Text(title, style = MaterialTheme.typography.titleSmall)
      if (!done) {
        Spacer(Modifier.height(4.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClick, enabled = enabled) { Text(action) }
      }
    }
  }
}

internal fun showPicker(context: Context) {
  context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
}

private fun copy(context: Context, text: String) {
  context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Langboard", text))
}

private const val MAX_LAB_OPTIONS = 5
