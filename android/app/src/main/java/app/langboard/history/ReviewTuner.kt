package app.langboard.history

import android.content.Context
import android.util.Log
import app.langboard.core.FsrsOptimizer
import app.langboard.core.Fsrs
import app.langboard.core.LangboardSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fits FSRS to the user's review log, in the background, and keeps the result only if it predicts
 * their reviews better than what they have now. Runs by itself after a review session when
 * [app.langboard.core.ReviewPrefs.shouldTune] says so, or when the user asks.
 */
object ReviewTuner {
  sealed interface Status {
    data object Idle : Status
    data class Running(val progress: Float) : Status
    /** [improved] is false when the fit didn't beat the current parameters, which were kept. */
    data class Done(val improved: Boolean, val reviews: Int) : Status
    data class NotEnough(val reviews: Int) : Status
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val _status = MutableStateFlow<Status>(Status.Idle)
  val status: StateFlow<Status> = _status.asStateFlow()

  /** Tunes if it's due (or always, with [force]); does nothing while a run is going. */
  fun maybeTune(context: Context, force: Boolean = false) {
    if (_status.value is Status.Running) return
    val app = context.applicationContext
    scope.launch {
      val settings = LangboardSettings(app)
      val prefs = settings.review
      val optimizer = FsrsOptimizer(HistoryStore.get(app).reviewRecords())
      val now = System.currentTimeMillis()
      if (!force && !prefs.shouldTune(optimizer.scoredReviews, now)) return@launch
      if (!optimizer.hasEnoughData) {
        _status.value = Status.NotEnough(optimizer.scoredReviews)
        return@launch
      }
      _status.value = Status.Running(0f)
      val start = prefs.params ?: Fsrs.DEFAULT_PARAMETERS
      val fitted = runCatching { optimizer.optimize(start) { _status.value = Status.Running(it) } }
        .onFailure { Log.w("ReviewTuner", "tuning failed: ${it.javaClass.name}") }
        .getOrNull()
      // Re-read: the user may have changed other review settings while this ran.
      val latest = settings.review
      settings.review = latest.copy(params = fitted ?: latest.params, tunedAt = now, tunedOn = optimizer.scoredReviews)
      _status.value = Status.Done(improved = fitted != null, reviews = optimizer.scoredReviews)
    }
  }
}
