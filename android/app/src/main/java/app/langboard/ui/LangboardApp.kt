package app.langboard.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.langboard.R

private enum class Tab(val label: String, @DrawableRes val icon: Int) {
  Write("Write", R.drawable.ic_translate),
  History("History", R.drawable.ic_history),
  Dictionary("Dictionary", R.drawable.ic_menu_book),
  Settings("Settings", R.drawable.ic_settings),
}

@Composable
fun LangboardApp() {
  var tab by rememberSaveable { mutableStateOf(Tab.Write) }
  // Each tab keeps its scroll position and inputs when you come back to it.
  val saved = rememberSaveableStateHolder()
  val chat = remember { WriteChat() }
  Scaffold(
    modifier = Modifier.fillMaxSize(),
    bottomBar = {
      NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Tab.entries.forEach { t ->
          NavigationBarItem(
            selected = tab == t,
            onClick = { tab = t },
            icon = { Icon(painterResource(t.icon), contentDescription = null) },
            label = { Text(t.label) },
            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest),
          )
        }
      }
    },
  ) { padding ->
    // Consumed, so a screen's imePadding() adds only what the keyboard covers beyond the nav bar.
    val m = Modifier.padding(padding).consumeWindowInsets(padding)
    saved.SaveableStateProvider(tab.name) {
      when (tab) {
        Tab.Write -> WriteScreen(chat, m)
        Tab.History -> HistoryScreen(m)
        Tab.Dictionary -> DictionaryScreen(m)
        Tab.Settings -> SettingsScreen(m)
      }
    }
  }
}
