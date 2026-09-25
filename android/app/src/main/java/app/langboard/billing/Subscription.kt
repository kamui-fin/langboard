package app.langboard.billing

import android.app.Activity
import android.content.Context
import android.net.Uri
import app.langboard.BuildConfig
import app.langboard.core.LangboardSettings
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import com.revenuecat.purchases.models.Period
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Langboard Pro through RevenueCat, with no account: the SDK makes an anonymous customer, and the
 * Google Play account owns the subscription, trial eligibility and restores. One entitlement, [ENTITLEMENT];
 * an active free trial counts as active.
 *
 * Only the app talks to RevenueCat. The keyboard never uses the network, so it trusts what the app
 * last saw ([activeOffline]), until that entitlement's expiry.
 */
object Subscription {
  const val ENTITLEMENT = "pro"

  /** Built with a RevenueCat key. Without one, purchases are off (see [purchase]). */
  val configured: Boolean get() = BuildConfig.REVENUECAT_API_KEY.isNotBlank()

  private val _active = MutableStateFlow<Boolean?>(null)
  /** Whether Langboard is unlocked; null until the first answer (from the cache or RevenueCat). */
  val active: StateFlow<Boolean?> = _active

  /** Where the user manages or cancels the subscription (Google Play), once RevenueCat has said. */
  var managementUrl: Uri? = null
    private set

  private lateinit var settings: LangboardSettings

  fun init(context: Context) {
    if (::settings.isInitialized) return
    settings = LangboardSettings(context)
    // Unlocked straight away if it was last time, so a subscriber never sees the paywall flash by.
    if (activeOffline(settings)) _active.value = true
    if (!configured) {
      _active.value = activeOffline(settings)
      return
    }
    Purchases.configure(
      PurchasesConfiguration.Builder(context.applicationContext, BuildConfig.REVENUECAT_API_KEY)
        // Nothing beyond the anonymous ID and the purchase itself.
        .automaticDeviceIdentifierCollectionEnabled(false)
        .build(),
    )
    Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener(::apply)
  }

  /** Reads the entitlement again; every app start, and after coming back to the app. */
  suspend fun refresh() {
    if (!configured) return
    try {
      apply(Purchases.sharedInstance.awaitCustomerInfo())
    } catch (_: PurchasesException) {
      // Offline with nothing cached by RevenueCat: fall back to what we saw last.
      if (_active.value == null) _active.value = activeOffline(settings)
    }
  }

  private fun apply(info: CustomerInfo) {
    val pro = info.entitlements[ENTITLEMENT]
    val active = pro?.isActive == true
    settings.proActive = active
    settings.proExpiresAt = pro?.expirationDate?.time ?: -1
    managementUrl = info.managementURL
    _active.value = active
  }

  /** What the keyboard goes by: the app's last answer, until the entitlement's expiry plus a day's grace. */
  fun activeOffline(settings: LangboardSettings): Boolean {
    if (!settings.proActive) return false
    val expires = settings.proExpiresAt
    return expires < 0 || System.currentTimeMillis() < expires + GRACE_MS
  }

  // ---------------------------------------------------------------- paywall

  enum class Term { Annual, Monthly }

  /**
   * One plan on the paywall, priced by the store in the user's currency. [pkg] is null when purchases
   * are off in this build and the prices are the planned US launch prices.
   */
  data class Plan(
    val term: Term,
    val price: String,
    /** Annual only: the price spread over twelve months. */
    val perMonth: String?,
    /** Days free before the first charge, when this Play account is eligible. */
    val trialDays: Int?,
    internal val pkg: Package?,
  )

  /** The plans in the current offering, annual first. Throws when the store can't be reached. */
  suspend fun plans(): List<Plan> {
    if (!configured) return PLANNED
    val offering = Purchases.sharedInstance.awaitOfferings().current ?: return emptyList()
    return listOfNotNull(
      offering.annual?.let { plan(Term.Annual, it) },
      offering.monthly?.let { plan(Term.Monthly, it) },
    )
  }

  private fun plan(term: Term, pkg: Package): Plan {
    val product = pkg.product
    return Plan(
      term = term,
      price = product.price.formatted,
      perMonth = if (term == Term.Annual) product.pricePerMonth()?.formatted else null,
      trialDays = product.defaultOption?.freePhase?.billingPeriod?.days(),
      pkg = pkg,
    )
  }

  private fun Period.days(): Int? = when (unit) {
    Period.Unit.DAY -> value
    Period.Unit.WEEK -> value * 7
    Period.Unit.MONTH -> value * 30
    Period.Unit.YEAR -> value * 365
    else -> null
  }

  sealed interface Outcome {
    data object Done : Outcome
    data object Cancelled : Outcome
    /** Restore found nothing active on this Play account. */
    data object NothingToRestore : Outcome
    data class Failed(val message: String) : Outcome
  }

  suspend fun purchase(activity: Activity, plan: Plan): Outcome {
    val pkg = plan.pkg
    if (pkg == null) {
      // No RevenueCat key in this build. Debug builds unlock locally so the rest stays testable.
      if (!BuildConfig.DEBUG) return Outcome.Failed("Purchases aren't available in this build.")
      settings.proActive = true
      settings.proExpiresAt = -1
      _active.value = true
      return Outcome.Done
    }
    return try {
      apply(Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(activity, pkg).build()).customerInfo)
      Outcome.Done
    } catch (e: PurchasesTransactionException) {
      if (e.userCancelled) Outcome.Cancelled else Outcome.Failed(e.error.message)
    }
  }

  /** Brings back a subscription bought with this Play account (after a reinstall, or on a new phone). */
  suspend fun restore(): Outcome {
    if (!configured) return if (_active.value == true) Outcome.Done else Outcome.NothingToRestore
    return try {
      apply(Purchases.sharedInstance.awaitRestore())
      if (_active.value == true) Outcome.Done else Outcome.NothingToRestore
    } catch (e: PurchasesTransactionException) {
      Outcome.Failed(e.error.message)
    }
  }

  private const val GRACE_MS = 24 * 3_600_000L

  /** gpt/onboarding_monetization.md: 7 days free, $59.99 a year (preselected) or $11.99 a month. */
  private val PLANNED = listOf(
    Plan(Term.Annual, "$59.99", "$5.00", 7, null),
    Plan(Term.Monthly, "$11.99", null, 7, null),
  )
}
