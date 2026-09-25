package app.langboard.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.langboard.BuildConfig
import app.langboard.billing.Subscription
import app.langboard.billing.Subscription.Outcome
import app.langboard.billing.Subscription.Plan
import app.langboard.billing.Subscription.Term
import kotlinx.coroutines.launch

/** Legal pages linked under the paywall; set before release (Play requires them for subscriptions). */
private const val TERMS_URL = ""
private const val PRIVACY_URL = ""

private val Features = listOf(
  "Fill gaps naturally",
  "Naturalize what you write",
  "Learn from real conversations",
  "Personalized review",
  "Your own language memory",
  "Casual, neutral or work tone",
  "Works offline",
)

/**
 * 4: the hard paywall. Prices come from the store through RevenueCat, annual preselected, the renewal
 * price next to the button, no toggles or countdowns. Buying flips [Subscription.active], which moves
 * [LangboardRoot] on to setup by itself.
 */
@Composable
internal fun Paywall(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val uri = LocalUriHandler.current
  var plans by remember { mutableStateOf<List<Plan>?>(null) }
  var loadFailed by remember { mutableStateOf(false) }
  var attempt by remember { mutableIntStateOf(0) }
  var term by rememberSaveable { mutableStateOf(Term.Annual) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(attempt) {
    loadFailed = false
    plans = runCatching { Subscription.plans() }.getOrNull()
    loadFailed = plans.isNullOrEmpty()
  }
  val plan = plans?.firstOrNull { it.term == term } ?: plans?.firstOrNull()
  val trial = plan?.trialDays

  fun run(action: suspend () -> Outcome) {
    busy = true
    message = null
    scope.launch {
      message = when (val o = action()) {
        Outcome.Done, Outcome.Cancelled -> null
        Outcome.NothingToRestore -> "No subscription found for this Google Play account."
        is Outcome.Failed -> o.message
      }
      busy = false
    }
  }

  StepPage(
    button = when {
      trial != null -> "Start my $trial-day free trial"
      else -> "Subscribe"
    },
    buttonEnabled = plan != null && !busy,
    onButton = { plan?.let { p -> run { Subscription.purchase(context as Activity, p) } } },
    top = { PageBar(onBack = onBack) },
    below = {
      if (plan != null) {
        Spacer(Modifier.height(10.dp))
        Muted(renewalLine(plan), MaterialTheme.typography.bodySmall, center = true)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { run { Subscription.restore() } }, enabled = !busy) { Text("Restore purchases") }
        if (TERMS_URL.isNotEmpty()) TextButton(onClick = { uri.openUri(TERMS_URL) }) { Text("Terms") }
        if (PRIVACY_URL.isNotEmpty()) TextButton(onClick = { uri.openUri(PRIVACY_URL) }) { Text("Privacy") }
      }
    },
  ) {
    SectionLabel("Langboard Pro")
    Spacer(Modifier.height(8.dp))
    StepTitle("Try the full Langboard experience")
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Features.forEach { f ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(12.dp))
          Text(f, style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
    Spacer(Modifier.height(28.dp))

    val loaded = plans
    when {
      loadFailed -> {
        Muted("Couldn't load prices. Check your connection.", MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { attempt++ }, contentPadding = PaddingValues(0.dp)) { Text("Try again") }
      }
      loaded == null -> CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally))
      else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        loaded.forEach { p ->
          ChoiceCard(
            title = if (p.term == Term.Annual) "Annual" else "Monthly",
            detail = when {
              p.term == Term.Annual && p.perMonth != null -> "${p.perMonth}/month, billed annually"
              else -> null
            },
            trailing = p.price + if (p.term == Term.Annual) "/yr" else "/mo",
            selected = p == plan,
          ) { term = p.term }
        }
      }
    }
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.width(8.dp))
      Muted("Cancel anytime before renewal", MaterialTheme.typography.bodyMedium)
    }
    message?.let {
      Spacer(Modifier.height(16.dp))
      Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (!Subscription.configured) {
      Spacer(Modifier.height(16.dp))
      Muted(
        if (BuildConfig.DEBUG) "Purchases aren't set up in this build. The button unlocks Langboard for testing."
        else "Purchases aren't set up in this build.",
        MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/** The whole deal in one line, next to the button: what's free, then what it costs. */
private fun renewalLine(p: Plan): String {
  val per = if (p.term == Term.Annual) "year" else "month"
  val then = "${p.price}/$per"
  return if (p.trialDays != null) "${p.trialDays} days free, then $then. Cancel in Google Play." else "$then. Cancel in Google Play."
}
