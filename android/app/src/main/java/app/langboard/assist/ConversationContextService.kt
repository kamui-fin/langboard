package app.langboard.assist

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.langboard.BuildConfig
import app.langboard.core.AssistPolicy
import app.langboard.core.LangboardSettings
import app.langboard.core.ScreenLine
import app.langboard.core.ScreenText
import app.langboard.ime.FieldPolicy

/**
 * Conversation Context: lets the Langboard keyboard see the text on screen when the user switches
 * to it. It reacts to no events, draws nothing and never changes text. Reading happens
 * only inside [read], which the keyboard calls when invoked; the result lives in memory for one request.
 */
class ConversationContextService : AccessibilityService() {

  private lateinit var settings: LangboardSettings

  override fun onServiceConnected() {
    super.onServiceConnected()
    settings = LangboardSettings(this)
    // Enabled from system settings without going through our disclosure: do nothing at all.
    if (!settings.contextConsent) {
      disableSelf()
      return
    }
    instance = this
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    if (instance === this) instance = null
    return super.onUnbind(intent)
  }

  override fun onDestroy() {
    if (instance === this) instance = null
    super.onDestroy()
  }

  /**
   * The text on screen, one line per text element in reading order, or empty when the screen is off
   * limits. The draft field and password fields are left out. Each line keeps only where it sits
   * horizontally (start, end or full width), so received and sent chat bubbles can be told apart.
   * Call off the main thread.
   */
  private fun readNow(): ScreenText {
    if (!settings.contextConsent) return empty()
    // The app's window is the active one while our keyboard shows (the keyboard never takes focus).
    val root = rootInActiveWindow ?: return empty("no active window")
    val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return empty("no focused field")

    val kind = if (field.inputType == 0) FieldPolicy.Kind.Text else FieldPolicy.classify(field.inputType)
    val labels = mutableListOf<String>()
    val lines = mutableListOf<ScreenLine>()
    val window = Rect().also(root::getBoundsInScreen)
    val bounds = Rect()
    walk(root) { n ->
      listOfNotNull(n.text, n.contentDescription, n.hintText, n.paneTitle).forEach { c ->
        if (c.length in 2..40) labels += c.toString()
      }
      val t = n.text
      if (n != field && !n.isEditable && !n.isPassword && n.isVisibleToUser && !t.isNullOrBlank()) {
        n.getBoundsInScreen(bounds)
        lines += ScreenLine(t.toString().trim(), alignOf(bounds, window))
      }
    }
    val facts = AssistPolicy.FieldFacts(
      packageName = field.packageName?.toString(),
      isPassword = field.isPassword || kind == FieldPolicy.Kind.Sensitive,
      fieldLabels = listOfNotNull(field.hintText, field.contentDescription, field.viewIdResourceName, field.paneTitle)
        .map { it.toString() },
      screenLabels = labels,
    )
    AssistPolicy.skipReason(facts, packageName)?.let { return empty("skipped: $it") }
    // The end of the screen is nearest the field, so that's what's kept when there's too much.
    var budget = MAX_CHARS
    var from = lines.size
    while (from > 0 && budget > 0) budget -= lines[--from].text.length + 1
    val kept = lines.subList(from, lines.size).toList()
    return ScreenText(kept).also { debug("${kept.size} lines, ${it.flat.length} chars") }
  }

  /** Start- or end-aligned when the element is narrower than the window and hugs one side. */
  private fun alignOf(b: Rect, window: Rect): ScreenLine.Align {
    val w = window.width().takeIf { it > 0 } ?: return ScreenLine.Align.Wide
    if (b.width() > w * 0.72f) return ScreenLine.Align.Wide
    val left = b.left - window.left
    val right = window.right - b.right
    return when {
      left < right && left < w * 0.25f -> ScreenLine.Align.Start
      right < left && right < w * 0.25f -> ScreenLine.Align.End
      else -> ScreenLine.Align.Wide
    }
  }

  private fun empty(why: String? = null): ScreenText {
    why?.let(::debug)
    return ScreenText.EMPTY
  }

  /** Debug builds only, and never text: why context came back the size it did. */
  private fun debug(msg: String) {
    if (BuildConfig.DEBUG) Log.d(TAG, msg)
  }

  /** Depth-first, children in order: roughly the order text reads on screen. */
  private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
    val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
    var seen = 0
    while (stack.isNotEmpty() && seen < MAX_NODES) {
      val n = stack.removeLast()
      seen++
      visit(n)
      for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::addLast)
    }
  }

  companion object {
    private const val TAG = "ConversationContext"
    private const val MAX_NODES = 600
    private const val MAX_CHARS = 2_000

    @Volatile private var instance: ConversationContextService? = null

    val isRunning: Boolean get() = instance != null

    /**
     * The screen's text for the field the keyboard is attached to, or empty when the service is off or
     * the screen is sensitive. Node reads are binder calls into the host app, so never call this on
     * the main thread.
     */
    fun read(): ScreenText =
      runCatching { instance?.readNow() }
        .onFailure { if (BuildConfig.DEBUG) Log.d(TAG, "read failed: ${it.javaClass.simpleName}") }
        .getOrNull()
        ?: ScreenText.EMPTY.also { if (BuildConfig.DEBUG && instance == null) Log.d(TAG, "service not running") }
  }
}
