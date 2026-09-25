package app.langboard.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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

/**
 * Four places, one idea each: Home (what matters today), Review (practice what you struggled to
 * say), Memory (everything you've learned) and You (how your Chinese is going, and settings).
 */
private enum class Tab(val label: String, @DrawableRes val icon: Int) {
  Home("Home", R.drawable.ic_home),
  Review("Review", R.drawable.ic_cards),
  Memory("Memory", R.drawable.ic_library),
  You("You", R.drawable.ic_person),
}

/** A page pushed over the tabs. Encoded as a string so it survives process death. */
sealed interface Page {
  data object Lab : Page
  data object Settings : Page
  data object ReviewSession : Page
  data class Expression(val key: String) : Page
  data class Moment(val id: Long) : Page

  fun encode(): String = when (this) {
    Lab -> "lab"
    Settings -> "settings"
    ReviewSession -> "review"
    is Expression -> "expr:$key"
    is Moment -> "moment:$id"
  }

  companion object {
    fun decode(s: String?): Page? = when {
      s == null -> null
      s == "lab" -> Lab
      s == "settings" -> Settings
      s == "review" -> ReviewSession
      s.startsWith("expr:") -> Expression(s.removePrefix("expr:"))
      s.startsWith("moment:") -> s.removePrefix("moment:").toLongOrNull()?.let { Moment(it) }
      else -> null
    }
  }
}

/** Opening pages from any screen. */
class Navigator(private val push: (Page) -> Unit, private val pop: () -> Unit) {
  fun open(page: Page) = push(page)
  fun back() = pop()
}

@Composable
fun LangboardApp() {
  var tab by rememberSaveable { mutableStateOf(Tab.Home) }
  // A short stack, so an expression opened from a moment goes back to the moment.
  var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
  val page = Page.decode(stack.lastOrNull())
  val nav = remember { Navigator(push = { stack = stack + it.encode() }, pop = { stack = stack.dropLast(1) }) }
  // Each tab keeps its scroll position and inputs when you come back to it.
  val saved = rememberSaveableStateHolder()
  val chat = remember { WriteChat() }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    bottomBar = {
      AnimatedVisibility(
        visible = page == null,
        enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(120)),
      ) {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
          Tab.entries.forEach { t ->
            NavigationBarItem(
              selected = tab == t,
              onClick = { tab = t },
              icon = { Icon(painterResource(t.icon), contentDescription = null) },
              label = { Text(t.label) },
              colors = NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
            )
          }
        }
      }
    },
  ) { padding ->
    // Consumed, so a screen's imePadding() adds only what the keyboard covers beyond the nav bar.
    val m = Modifier.padding(padding).consumeWindowInsets(padding)
    AnimatedContent(
      targetState = stack,
      transitionSpec = {
        val forward = targetState.size > initialState.size
        val enter = slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(200))
        val exit = slideOutHorizontally(tween(220)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(140))
        enter togetherWith exit
      },
      contentKey = { it.lastOrNull() ?: "tabs" },
      label = "page",
    ) { s ->
      when (val p = Page.decode(s.lastOrNull())) {
        null -> saved.SaveableStateProvider(tab.name) {
          when (tab) {
            Tab.Home -> HomeScreen(nav, onReviewTab = { tab = Tab.Review }, modifier = m)
            Tab.Review -> ReviewTab(nav, m)
            Tab.Memory -> MemoryScreen(nav, m)
            Tab.You -> YouScreen(nav, m)
          }
        }
        else -> {
          BackHandler { nav.back() }
          when (p) {
            Page.Lab -> WriteScreen(chat, onBack = nav::back, modifier = m)
            Page.Settings -> SettingsScreen(onBack = nav::back, modifier = m)
            Page.ReviewSession -> ReviewScreen(onClose = nav::back, modifier = m)
            is Page.Expression -> ExpressionScreen(p.key, nav, m)
            is Page.Moment -> MomentScreen(p.id, nav, m)
          }
        }
      }
    }
  }
}
