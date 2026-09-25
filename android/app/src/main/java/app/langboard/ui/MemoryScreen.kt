package app.langboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.langboard.R
import app.langboard.history.Expression
import app.langboard.history.HistoryEntry
import app.langboard.history.HistoryKind

private enum class Shelf(val label: String) { Expressions("Expressions"), Moments("Moments"), Saved("Saved") }

/**
 * Everything Langboard has helped you say. Expressions are the Chinese you picked up, one each;
 * Moments are the times you asked, as they happened; Saved is what you starred.
 */
@Composable
fun MemoryScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val snap = rememberSnapshot()
  var shelf by rememberSaveable { mutableStateOf(Shelf.Expressions) }
  var query by remember { mutableStateOf(TextFieldValue()) }
  val q = query.text.trim().lowercase()

  val expressions = remember(snap, q) { snap?.insights?.expressions.orEmpty().filter { q.isEmpty() || it.matches(q) } }
  val moments = remember(snap, q) { snap?.insights?.moments.orEmpty().filter { q.isEmpty() || it.matches(q) } }
  val saved = remember(expressions) { expressions.filter { it.saved } }
  // A saved check has no expression of its own; it's still something the user kept.
  val savedChecks = remember(moments) { moments.filter { it.saved && it.kind == HistoryKind.Check } }

  LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
    item {
      ScreenTitle(
        "Memory",
        snap?.insights?.let { i ->
          if (i.expressions.isEmpty()) "Everything Langboard helps you say, kept on this phone."
          else "${plural(i.expressions.size, "expression")} from ${plural(i.moments.size, "moment")} in your own chats."
        },
      )
    }
    item {
      Column(Modifier.padding(horizontal = 16.dp)) {
        PillTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = "Search English or Chinese",
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, modifier = Modifier.size(20.dp)) },
          trailingIcon = if (query.text.isNotEmpty()) {
            { IconButton(onClick = { query = TextFieldValue() }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") } }
          } else null,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
          Shelf.entries.forEachIndexed { i, s ->
            SegmentedButton(
              selected = shelf == s,
              onClick = { shelf = s },
              shape = SegmentedButtonDefaults.itemShape(i, Shelf.entries.size),
              icon = {},
              colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
              label = { Text(s.label, maxLines = 1) },
            )
          }
        }
      }
    }
    if (snap == null) return@LazyColumn
    when (shelf) {
      Shelf.Expressions -> {
        if (expressions.isEmpty()) item { EmptyShelf(shelf, q.isNotEmpty()) }
        items(expressions, key = { it.key }) { e -> ExpressionRow(e, onClick = { nav.open(Page.Expression(e.key)) }) }
      }
      Shelf.Moments -> {
        if (moments.isEmpty()) item { EmptyShelf(shelf, q.isNotEmpty()) }
        moments.groupBy { dayOf(it.createdAt) }.forEach { (day, list) ->
          item(key = "day-$day") { DayHeader(day) }
          items(list, key = { it.id }) { e -> HistoryRow(e) { nav.open(Page.Moment(e.id)) } }
        }
      }
      Shelf.Saved -> {
        if (saved.isEmpty() && savedChecks.isEmpty()) item { EmptyShelf(shelf, q.isNotEmpty()) }
        items(saved, key = { it.key }) { e -> ExpressionRow(e, onClick = { nav.open(Page.Expression(e.key)) }) }
        items(savedChecks, key = { "check-${it.id}" }) { e -> HistoryRow(e) { nav.open(Page.Moment(e.id)) } }
      }
    }
  }
}

@Composable
private fun EmptyShelf(shelf: Shelf, searching: Boolean) {
  if (searching) {
    EmptyState(R.drawable.ic_search, "Nothing matches", "Try the English you meant, or a character from the Chinese.")
    return
  }
  when (shelf) {
    Shelf.Expressions -> EmptyState(
      R.drawable.ic_library,
      "No expressions yet",
      "Each phrase Langboard helps you say is kept here, with the sentence you used it in.",
    )
    Shelf.Moments -> EmptyState(
      R.drawable.ic_history,
      "No moments yet",
      "Each time you switch to Langboard in a chat, what you asked and what it answered shows up here.",
    )
    Shelf.Saved -> EmptyState(
      R.drawable.ic_star_border,
      "Nothing saved",
      "Tap ☆ in the keyboard or the Expression Lab to keep a phrase, and to practice it first.",
    )
  }
}

private fun HistoryEntry.matches(q: String): Boolean =
  listOfNotNull(source, answer, sentence, meaning, used).any { it.lowercase().contains(q) } ||
    items.any { it.text.contains(q) || it.meaning?.lowercase()?.contains(q) == true }

private fun Expression.matches(q: String): Boolean = entries.any { it.matches(q) }
