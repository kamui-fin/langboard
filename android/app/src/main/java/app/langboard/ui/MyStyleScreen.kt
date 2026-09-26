package app.langboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.langboard.core.CompiledStyle
import app.langboard.core.Directness
import app.langboard.core.LangboardSettings
import app.langboard.core.LocalStyleCompiler
import app.langboard.core.MyStyle
import app.langboard.core.Register
import app.langboard.core.Slang
import app.langboard.core.StyleRule
import app.langboard.core.Verbosity
import app.langboard.history.StyleEvidence
import kotlinx.coroutines.launch

/**
 * How the user wants to sound. Three layers, and what they say outranks what's learned:
 * in your words (kept as a cleaned style guide for the model, and read into the controls and words
 * below, only when they tap Update), the controls and words, and "Learned from you", which only
 * ever suggests.
 */
@Composable
fun MyStyleScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val settings = remember { LangboardSettings(context) }
  val scope = rememberCoroutineScope()
  var style by remember { mutableStateOf(settings.myStyle) }
  var register by remember { mutableStateOf(settings.defaultRegister) }
  var words by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(style.description)) }
  var lastCompile by remember { mutableStateOf<CompiledStyle?>(null) }
  var confirmReset by remember { mutableStateOf(false) }
  val snap = rememberSnapshot()
  val proposals = remember(snap, style) { snap?.let { StyleEvidence(it.entries).proposals(style) }.orEmpty() }

  fun save(s: MyStyle) { style = s; settings.myStyle = s }

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
    PageBar(onBack = onBack)
    ScreenTitle("My Style", "How Langboard should sound when it helps you.", top = 0.dp)

    // ------------------------------------------------------------ in your words
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
      PillTextField(
        value = words,
        onValueChange = { words = it },
        placeholder = "Casual and short. Some slang is fine. I say 哈哈哈, not 笑死.",
        maxLines = 8,
        modifier = Modifier.heightIn(min = 120.dp),
      )
      Muted(
        "Describe how you want to sound, or paste a few messages that sound like you.",
        MaterialTheme.typography.bodySmall,
        Modifier.padding(start = 8.dp, top = 8.dp),
      )
      val changed = words.text.trim() != (style.compiledFrom ?: "").trim()
      Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        lastCompile?.let { c ->
          Muted(
            if (c.isEmpty) "Nothing specific found. Name words to use or avoid, or paste a message." else "Updated below.",
            MaterialTheme.typography.bodyMedium,
            Modifier.weight(1f),
          )
        } ?: Spacer(Modifier.weight(1f))
        Button(
          enabled = changed && words.text.isNotBlank(),
          onClick = {
            val text = words.text.trim()
            scope.launch {
              val c = LocalStyleCompiler.compile(text)
              lastCompile = c
              c.register?.let { register = it; settings.defaultRegister = it }
              save(style.compiled(c, text))
            }
          },
        ) { Text("Update my style") }
      }
    }

    // ------------------------------------------------------------ controls
    SettingsGroup("Sound", footer = "The chat you're in can still make it more casual or more polite.") {
      ChoiceRow("With nobody in particular", Register.entries.map { it to it.choice }, register) {
        register = it; settings.defaultRegister = it
      }
      GroupDivider()
      ChoiceRow("Slang", Slang.entries.map { it to it.label }, style.slang) { save(style.copy(slang = it)) }
      GroupDivider()
      ChoiceRow("Length", Verbosity.entries.map { it to it.label }, style.verbosity) { save(style.copy(verbosity = it)) }
      GroupDivider()
      ChoiceRow("Tone", Directness.entries.map { it to it.label }, style.directness) { save(style.copy(directness = it)) }
    }

    // ------------------------------------------------------------ what it follows
    if (style.guide.isNotEmpty()) {
      StyleSection("Your style guide", "Your words, as the Chinese model reads them. Anything about who you are is left out.")
      Column(Modifier.padding(horizontal = 16.dp)) {
        style.guide.forEach { g -> RuleRow("“$g”") { save(style.copy(guide = style.guide - g)) } }
      }
    }
    StyleSection("Words", "Options with words you use go first; ones with words you avoid are left out.")
    Column(Modifier.padding(horizontal = 16.dp)) {
      style.told.forEach { r -> RuleRow(r.describe()) { save(style.without(r)) } }
      AddWord { rule -> save(style.with(rule)) }
    }
    if (style.examples.isNotEmpty()) {
      StyleSection("Sounds like you", null)
      Column(Modifier.padding(horizontal = 16.dp)) {
        style.examples.forEach { ex -> RuleRow(ex) { save(style.copy(examples = style.examples - ex)) } }
      }
    }

    // ------------------------------------------------------------ learned
    StyleSection("Learned from you", "From the options you pick in the keyboard. Nothing is added unless you say so.")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (proposals.isEmpty() && style.learned.isEmpty()) {
        Muted(
          "Nothing yet. When you keep choosing one way of saying something, it shows up here.",
          MaterialTheme.typography.bodyMedium,
          Modifier.padding(horizontal = 8.dp),
        )
      }
      proposals.forEach { p ->
        when (p) {
          is StyleEvidence.Proposal.Rule -> ProposalCard(
            "You usually pick ${p.rule.text} over ${p.rule.over}", p.occasions,
            onAdd = { save(style.with(p.rule)) },
            onIgnore = { save(style.copy(dismissed = style.dismissed + p.rule.line)) },
          )
          is StyleEvidence.Proposal.Shorter -> ProposalCard(
            "You usually pick the shorter option", p.occasions,
            onAdd = { save(style.copy(verbosity = Verbosity.Concise)) },
            onIgnore = { save(style.copy(dismissed = style.dismissed + MyStyle.VERBOSITY_KEY)) },
          )
        }
      }
      style.learned.forEach { r -> RuleRow(r.describe()) { save(style.without(r).copy(dismissed = style.dismissed + r.line)) } }
    }

    SettingsGroup(null, Modifier.padding(top = 20.dp)) {
      Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { confirmReset = true }) { Text("Reset My Style", color = MaterialTheme.colorScheme.error) }
      }
    }
    Muted(
      "Kept on this phone. Your description itself never reaches the model, only what's shown above.",
      MaterialTheme.typography.bodySmall,
      Modifier.padding(horizontal = 32.dp).padding(top = 12.dp),
    )
  }

  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text("Reset My Style?") },
      text = { Text("Your description, the words you added and what you accepted from Learned are cleared.") },
      confirmButton = {
        TextButton(onClick = {
          confirmReset = false
          save(MyStyle())
          words = TextFieldValue()
          lastCompile = null
        }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
  }
}

/** A rule in plain words. */
private fun StyleRule.describe(): String = when (kind) {
  StyleRule.Kind.Prefer -> if (over != null) "$text rather than $over" else "Use $text"
  StyleRule.Kind.Avoid -> "Avoid $text"
  StyleRule.Kind.Note -> text.replaceFirstChar { it.uppercase() }
}

@Composable
private fun StyleSection(title: String, hint: String?) {
  Column(Modifier.padding(horizontal = ScreenGutter).padding(top = 32.dp, bottom = 8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (hint != null) Muted(hint, MaterialTheme.typography.bodySmall, Modifier.padding(top = 2.dp))
  }
}

@Composable
private fun RuleRow(text: String, onRemove: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
  }
}

/** One Chinese word, then Use or Avoid. For whatever the description didn't catch. */
@Composable
private fun AddWord(onAdd: (StyleRule) -> Unit) {
  var word by remember { mutableStateOf(TextFieldValue()) }
  val w = word.text.trim()
  val ok = w.isNotEmpty() && w.length <= 8
  fun add(kind: StyleRule.Kind) { onAdd(StyleRule(kind, w)); word = TextFieldValue() }
  Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    PillTextField(
      value = word,
      onValueChange = { word = it },
      placeholder = "A word, e.g. 挺",
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { if (ok) add(StyleRule.Kind.Prefer) }),
      modifier = Modifier.weight(1f),
    )
    Spacer(Modifier.width(8.dp))
    FilledTonalButton(enabled = ok, onClick = { add(StyleRule.Kind.Prefer) }) { Text("Use") }
    Spacer(Modifier.width(4.dp))
    TextButton(enabled = ok, onClick = { add(StyleRule.Kind.Avoid) }) { Text("Avoid") }
  }
}

@Composable
private fun ProposalCard(text: String, occasions: Int, onAdd: () -> Unit, onIgnore: () -> Unit) {
  Panel {
    Text(text, style = MaterialTheme.typography.bodyLarge)
    Muted("Seen on $occasions separate occasions", MaterialTheme.typography.bodySmall, Modifier.padding(top = 2.dp))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
      TextButton(onClick = onIgnore) { Text("Not for me") }
      Spacer(Modifier.width(8.dp))
      FilledTonalButton(onClick = onAdd) { Text("Add to my style") }
    }
  }
}
