package app.langboard.core

import android.content.Context
import kotlin.math.roundToInt

/** Settings shared by the app and the IME. Holds preferences only, never host text. */
class LangboardSettings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("langboard_settings", Context.MODE_PRIVATE)

  enum class AfterInsert { STAY, RETURN }

  var afterInsert: AfterInsert
    get() = prefs.getString(KEY_AFTER_INSERT, null)?.let { runCatching { AfterInsert.valueOf(it) }.getOrNull() }
      ?: AfterInsert.RETURN
    set(value) = prefs.edit().putString(KEY_AFTER_INSERT, value.name).apply()

  var assistMode: AssistMode
    get() = prefs.getString(KEY_ASSIST_MODE, null)?.let { runCatching { AssistMode.valueOf(it) }.getOrNull() }
      ?: AssistMode.COACH
    set(value) = prefs.edit().putString(KEY_ASSIST_MODE, value.name).apply()

  /** The user accepted the Conversation Context disclosure. Required before we send them to Accessibility settings. */
  var contextConsent: Boolean
    get() = prefs.getBoolean(KEY_CONTEXT_CONSENT, false)
    set(value) = prefs.edit().putBoolean(KEY_CONTEXT_CONSENT, value).apply()

  /** How many of the model's options the keyboard lists; one of [OPTION_COUNTS]. */
  var optionCount: Int
    get() = prefs.getInt(KEY_OPTION_COUNT, OPTION_COUNTS.last()).takeIf { it in OPTION_COUNTS } ?: OPTION_COUNTS.last()
    set(value) = prefs.edit().putInt(KEY_OPTION_COUNT, value).apply()

  /** Set once the keyboard has actually been opened; completes the setup checklist. */
  var keyboardUsed: Boolean
    get() = prefs.getBoolean(KEY_KEYBOARD_USED, false)
    set(value) = prefs.edit().putBoolean(KEY_KEYBOARD_USED, value).apply()

  /** Keep a history of what Langboard answered, on this device. On by default; the user can turn it off and clear it. */
  var keepHistory: Boolean
    get() = prefs.getBoolean(KEY_KEEP_HISTORY, true)
    set(value) = prefs.edit().putBoolean(KEY_KEEP_HISTORY, value).apply()

  /** Pinyin over Chinese everywhere Langboard shows it. */
  var showPinyin: Boolean
    get() = prefs.getBoolean(KEY_SHOW_PINYIN, true)
    set(value) = prefs.edit().putBoolean(KEY_SHOW_PINYIN, value).apply()

  /** Pinyin colored by tone. */
  var toneColors: Boolean
    get() = prefs.getBoolean(KEY_TONE_COLORS, true)
    set(value) = prefs.edit().putBoolean(KEY_TONE_COLORS, value).apply()

  /** Review scheduling. Unset fields keep their defaults, so improving a default reaches everyone who never changed it. */
  var review: ReviewPrefs
    get() {
      val d = ReviewPrefs()
      fun steps(key: String, default: List<Long>) = prefs.getString(key, null)?.let { ReviewPrefs.parseSteps(it) } ?: default
      return ReviewPrefs(
        newPerDay = prefs.getInt(KEY_NEW_PER_DAY, d.newPerDay),
        reviewsPerDay = prefs.getInt(KEY_REVIEWS_PER_DAY, d.reviewsPerDay),
        retention = prefs.getFloat(KEY_RETENTION, d.retention.toFloat()).toDouble().let { (it * 100).roundToInt() / 100.0 },
        maxIntervalDays = prefs.getInt(KEY_MAX_INTERVAL, d.maxIntervalDays),
        learningSteps = steps(KEY_LEARNING_STEPS, d.learningSteps),
        relearningSteps = steps(KEY_RELEARNING_STEPS, d.relearningSteps),
        dayStartHour = prefs.getInt(KEY_DAY_START, d.dayStartHour),
        autoTune = prefs.getBoolean(KEY_AUTO_TUNE, d.autoTune),
        params = prefs.getString(KEY_PARAMS, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 21 },
        tunedAt = prefs.getLong(KEY_TUNED_AT, -1).takeIf { it >= 0 },
        tunedOn = prefs.getInt(KEY_TUNED_ON, 0),
      )
    }
    set(v) {
      val d = ReviewPrefs()
      prefs.edit().apply {
        fun int(key: String, value: Int, default: Int) { if (value == default) remove(key) else putInt(key, value) }
        int(KEY_NEW_PER_DAY, v.newPerDay, d.newPerDay)
        int(KEY_REVIEWS_PER_DAY, v.reviewsPerDay, d.reviewsPerDay)
        if (v.retention == d.retention) remove(KEY_RETENTION) else putFloat(KEY_RETENTION, v.retention.toFloat())
        int(KEY_MAX_INTERVAL, v.maxIntervalDays, d.maxIntervalDays)
        if (v.learningSteps == d.learningSteps) remove(KEY_LEARNING_STEPS) else putString(KEY_LEARNING_STEPS, ReviewPrefs.formatSteps(v.learningSteps))
        if (v.relearningSteps == d.relearningSteps) remove(KEY_RELEARNING_STEPS) else putString(KEY_RELEARNING_STEPS, ReviewPrefs.formatSteps(v.relearningSteps))
        int(KEY_DAY_START, v.dayStartHour, d.dayStartHour)
        if (v.autoTune == d.autoTune) remove(KEY_AUTO_TUNE) else putBoolean(KEY_AUTO_TUNE, v.autoTune)
        // Locale-independent: always '.', joined by ','.
        if (v.params == null) remove(KEY_PARAMS) else putString(KEY_PARAMS, v.params.joinToString(",") { it.toString() })
        if (v.tunedAt == null) remove(KEY_TUNED_AT) else putLong(KEY_TUNED_AT, v.tunedAt)
        putInt(KEY_TUNED_ON, v.tunedOn)
      }.apply()
    }

  companion object {
    /** The model searches 10 beams, so 10 is every option it has. */
    val OPTION_COUNTS = listOf(3, 5, 10)
    private const val KEY_OPTION_COUNT = "option_count"
    private const val KEY_SHOW_PINYIN = "show_pinyin"
    private const val KEY_TONE_COLORS = "tone_colors"
    private const val KEY_KEEP_HISTORY = "keep_history"
    private const val KEY_AFTER_INSERT = "after_insert"
    private const val KEY_KEYBOARD_USED = "keyboard_used"
    private const val KEY_ASSIST_MODE = "assist_mode"
    private const val KEY_CONTEXT_CONSENT = "context_consent"
    private const val KEY_NEW_PER_DAY = "review_new_per_day"
    private const val KEY_REVIEWS_PER_DAY = "review_reviews_per_day"
    private const val KEY_RETENTION = "review_retention"
    private const val KEY_MAX_INTERVAL = "review_max_interval"
    private const val KEY_LEARNING_STEPS = "review_learning_steps"
    private const val KEY_RELEARNING_STEPS = "review_relearning_steps"
    private const val KEY_DAY_START = "review_day_start"
    private const val KEY_AUTO_TUNE = "review_auto_tune"
    private const val KEY_PARAMS = "review_fsrs_params"
    private const val KEY_TUNED_AT = "review_tuned_at"
    private const val KEY_TUNED_ON = "review_tuned_on"
  }
}
