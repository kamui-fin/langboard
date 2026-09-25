package app.langboard.core

/**
 * Where Conversation Context reads nothing. Pure rules over what the accessibility layer reports,
 * so the service only has to collect facts. Every check errs toward not reading.
 */
object AssistPolicy {

  enum class Skip { OwnApp, DeniedApp, Password, SensitiveScreen }

  /** Facts about the focused field and its window, already read out of the node tree. */
  data class FieldFacts(
    val packageName: String?,
    val isPassword: Boolean,
    /** Hint, content description and view id of the field itself. */
    val fieldLabels: List<String>,
    /** Short labels visible in the same window (titles, buttons), used only to spot sign-in/payment screens. */
    val screenLabels: List<String>,
  )

  fun skipReason(f: FieldFacts, ownPackage: String): Skip? {
    val pkg = f.packageName ?: return Skip.DeniedApp
    return when {
      pkg == ownPackage -> Skip.OwnApp
      isDeniedPackage(pkg) -> Skip.DeniedApp
      f.isPassword -> Skip.Password
      f.fieldLabels.any(::isSensitiveLabel) -> Skip.SensitiveScreen
      f.screenLabels.any(::isSensitiveLabel) -> Skip.SensitiveScreen
      else -> null
    }
  }

  // --- Apps -----------------------------------------------------------------------------------

  /** Password managers, wallets and system credential UI. Matched exactly or as a package prefix. */
  private val DENIED_PACKAGES = listOf(
    // Password managers
    "com.x8bit.bitwarden", "com.bitwarden.", "com.onepassword.", "com.agilebits.onepassword",
    "com.lastpass.", "com.dashlane", "keepass2android.", "com.kunzisoft.keepass", "org.keepassdx",
    "com.callpod.android_apps.keeper", "com.enpass.", "io.enpass.", "com.nordpass.", "proton.android.pass",
    // Payments and wallets
    "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa", "com.paypal.", "com.venmo",
    "com.squareup.cash", "com.eg.android.AlipayGphone", "com.alipay.", "com.samsung.android.spay",
    "com.revolut.", "com.transferwise.", "com.wise.",
    // Authenticators and system credential screens
    "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.", "com.twilio.authy",
    "com.android.settings", "com.google.android.gms", "com.android.systemui", "com.android.keychain",
  )

  /** Words that appear in banking app package names across regions. */
  private val BANK_PACKAGE_WORDS = listOf("bank", "banking", "credit", "wallet", "finance", "brokerage", "trading")

  fun isDeniedPackage(pkg: String): Boolean {
    val p = pkg.lowercase()
    if (DENIED_PACKAGES.any { d -> if (d.endsWith('.')) p.startsWith(d.lowercase()) else p == d.lowercase() }) return true
    val parts = p.split('.', '_')
    return parts.any { part -> BANK_PACKAGE_WORDS.any(part::contains) }
  }

  // --- Screens --------------------------------------------------------------------------------

  private val SENSITIVE_WORDS = listOf(
    // Sign-in and verification
    "password", "passcode", "passwd", "pin code", "one-time code", "verification code", "otp", "2fa",
    "sign in", "signin", "log in", "login", "security code",
    "密码", "验证码", "登录", "登陆", "口令",
    // Payment
    "card number", "cardnumber", "credit card", "debit card", "cvv", "cvc", "expiry", "expiration date",
    "iban", "routing number", "account number", "sort code", "billing address", "checkout", "pay now",
    "银行卡", "卡号", "支付", "付款", "安全码",
  )

  /** Short labels only: a long chat message that happens to mention "password" is not a sign-in screen. */
  fun isSensitiveLabel(label: String): Boolean {
    if (label.length > MAX_LABEL_CHARS) return false
    val norm = label.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').lowercase()
    return SENSITIVE_WORDS.any { w ->
      if (w.any(FragmentDetector::isCjk)) norm.contains(w)
      else Regex("(^|[^a-z])${Regex.escape(w)}($|[^a-z])").containsMatchIn(norm)
    }
  }

  private const val MAX_LABEL_CHARS = 40
}
