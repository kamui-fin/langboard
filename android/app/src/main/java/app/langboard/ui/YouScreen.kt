package app.langboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.langboard.R
import app.langboard.core.LangboardSettings
import app.langboard.history.Insights
import app.langboard.history.Strength
import kotlin.math.roundToInt

/**
 * Your Chinese and how it's going, from what's on this phone and nothing else: what you can now
 * say, how you tend to write, how well review is holding. Settings live here too.
 */
@Composable
fun YouScreen(nav: Navigator, modifier: Modifier = Modifier) {
  val snap = rememberSnapshot()
  val context = LocalContext.current
  val tuned = remember { LangboardSettings(context).review.params != null }

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
    ScreenTitle("Your Chinese", "Built from what you actually tried to say.")
    if (snap != null) {
      val i = snap.insights
      Panel(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) {
        Row {
          Stat("${i.moments.size}", "moments", Modifier.weight(1f))
          Stat("${i.expressions.size}", "expressions", Modifier.weight(1f))
          Stat("${i.canSay.size}", "you can say now", Modifier.weight(1f))
        }
        if (i.expressions.isNotEmpty()) {
          Spacer(Modifier.height(20.dp))
          StrengthBar(i)
        }
      }

      if (i.canSay.isNotEmpty()) {
        YouSection("Things you can say now")
        Column(Modifier.padding(horizontal = ScreenGutter)) {
          i.canSay.take(8).forEach { e ->
            Surface(onClick = { nav.open(Page.Expression(e.key)) }, color = MaterialTheme.colorScheme.surface) {
              Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text("“${e.meaning}”", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Muted(e.chinese, MaterialTheme.typography.bodyMedium, maxLines = 1)
              }
            }
          }
        }
      }

      val registerTotal = i.registers.sumOf { it.second }
      val showRegister = registerTotal >= MIN_FOR_PATTERN
      if (showRegister || i.apps.isNotEmpty()) {
        YouSection("How you write")
        Column(Modifier.padding(horizontal = ScreenGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          // One chat isn't a pattern: say nothing until there are enough.
          if (showRegister) i.registers.first().let { (register, n) ->
            Fact("Mostly ${register.lowercase()} chats", "${(100.0 * n / registerTotal).roundToInt()}% of the $registerTotal chats Langboard read")
          }
          if (i.apps.isNotEmpty()) {
            Fact(
              "Where you get stuck",
              i.apps.take(3).joinToString(" · ") { (pkg, n) -> "${appName(pkg) ?: "other apps"} $n" },
            )
          }
        }
      }

      if (i.reviewCount > 0) {
        YouSection("Review")
        Column(Modifier.padding(horizontal = ScreenGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Fact(
            plural(i.reviewCount, "review") + " done",
            listOfNotNull(
              i.recallRate?.let { "you recalled ${(it * 100).roundToInt()}%" },
              if (tuned) "timing fitted to your memory" else null,
            ).joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Keep going" },
          )
        }
      }
    }

    SettingsGroup(label = null, modifier = Modifier.padding(top = 20.dp)) {
      NavLink(R.drawable.ic_settings, "Settings", "Keyboard, review, Chinese pack") { nav.open(Page.Settings) }
    }
    Muted(
      "Everything here is worked out on this phone from your own history. Nothing is uploaded.",
      MaterialTheme.typography.bodySmall,
      Modifier.padding(horizontal = 32.dp).padding(top = 12.dp),
    )
  }
}

@Composable
private fun YouSection(title: String) {
  Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = ScreenGutter).padding(top = 36.dp, bottom = 10.dp))
}

@Composable
private fun Fact(title: String, detail: String) {
  Column {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    Muted(detail, MaterialTheme.typography.bodyMedium)
  }
}

/** Every expression by how well it's held, as one segmented bar with a legend. */
@Composable
private fun StrengthBar(i: Insights) {
  val counts = Strength.entries.associateWith { s -> i.expressions.count { it.strength == s } }
  val total = i.expressions.size.coerceAtLeast(1)
  val shades = listOf(
    MaterialTheme.colorScheme.outlineVariant,
    MaterialTheme.colorScheme.outline,
    MaterialTheme.colorScheme.onSurfaceVariant,
    MaterialTheme.colorScheme.secondary,
    MaterialTheme.colorScheme.onSurface,
  )
  Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
    Strength.entries.forEachIndexed { idx, s ->
      val n = counts.getValue(s)
      if (n > 0) Box(Modifier.weight(n.toFloat() / total).fillMaxSize().background(shades[idx]))
    }
  }
  Spacer(Modifier.height(10.dp))
  val held = counts.getValue(Strength.Fresh) + counts.getValue(Strength.Settling) + counts.getValue(Strength.Strong)
  Muted(
    listOfNotNull(
      held.takeIf { it > 0 }?.let { "$it remembered" },
      counts.getValue(Strength.Learning).takeIf { it > 0 }?.let { "$it learning" },
      counts.getValue(Strength.New).takeIf { it > 0 }?.let { "$it not practiced yet" },
    ).joinToString(" · "),
    MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun NavLink(icon: Int, title: String, summary: String, onClick: () -> Unit) {
  Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(painterResource(icon), contentDescription = null)
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Muted(summary, MaterialTheme.typography.bodyMedium)
      }
      Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Lookups that read the chat, before we describe a pattern in them. */
private const val MIN_FOR_PATTERN = 5
