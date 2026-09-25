package app.langboard.model

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.langboard.core.ModelSpec
import app.langboard.core.ModelState
import app.langboard.core.PromptBook
import app.langboard.core.Scored
import app.langboard.core.Beams
import app.langboard.core.TextModel
import app.langboard.llama.BeamSearch
import app.langboard.llama.LlamaModel
import app.langboard.llama.Sampling
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Chinese pack's single source of truth, for the app and the keyboard (same process). The
 * download runs in [ModelDownloadWorker]; loading happens here, once per process, whoever asks
 * first. The model stays in memory while the process lives; Android kills the process when it
 * needs the memory back, and the next [ensureLoaded] loads it again.
 */
object ModelManager : TextModel {
  private const val TAG = "LangboardModel"
  const val WORK_NAME = "model-download"
  /** Prompt (chat included) plus room for every beam's answer. */
  const val CONTEXT_TOKENS = 1024
  const val MAX_BEAMS = 16

  val spec: ModelSpec = ModelSpec.CHINESE

  private lateinit var app: Context
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val loadLock = Mutex()
  private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled)
  val state: StateFlow<ModelState> = _state.asStateFlow()

  @Volatile private var model: LlamaModel? = null
  @Volatile private var loading = false
  @Volatile private var loadError: String? = null
  @Volatile private var work: WorkInfo? = null

  val dir: File get() = File(app.filesDir, "models/${spec.id}")
  val file: File get() = File(dir, spec.installedName)

  /** Idempotent; call from every entry point (activity, keyboard). */
  @Synchronized
  fun init(context: Context) {
    if (::app.isInitialized) return
    app = context.applicationContext
    recompute()
    scope.launch {
      WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(WORK_NAME).collect { infos ->
        val before = work
        work = infos.firstOrNull()
        recompute()
        if (before?.state?.isFinished == false && work?.state == WorkInfo.State.SUCCEEDED) ensureLoaded()
      }
    }
  }

  private fun recompute() {
    val w = work
    _state.value = when {
      model != null -> ModelState.Ready
      loading -> ModelState.Loading
      w?.state == WorkInfo.State.RUNNING || w?.state == WorkInfo.State.ENQUEUED -> progressOf(w)
      loadError != null && file.exists() -> ModelState.Error(loadError!!)
      file.exists() -> ModelState.Installed
      w?.state == WorkInfo.State.FAILED -> ModelState.Error(w.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Download failed. Try again.")
      else -> ModelState.NotInstalled
    }
  }

  private fun progressOf(w: WorkInfo): ModelState {
    val p = w.progress
    val done = p.getLong(ModelDownloadWorker.KEY_DONE, 0)
    val total = p.getLong(ModelDownloadWorker.KEY_TOTAL, spec.downloadBytes)
    val fraction = if (total > 0 && done > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    return when (p.getString(ModelDownloadWorker.KEY_PHASE)) {
      ModelDownloadWorker.PHASE_VERIFY -> ModelState.Verifying(fraction)
      else -> ModelState.Downloading(fraction, done, total)
    }
  }

  /** Loads the model if it's installed and not loaded yet. Concurrent callers share one load. */
  suspend fun ensureLoaded(): Boolean {
    model?.let { return true }
    return loadLock.withLock {
      model?.let { return@withLock true }
      if (!file.exists()) return@withLock false
      loading = true
      loadError = null
      recompute()
      try {
        val started = System.nanoTime()
        model = LlamaModel.load(app, file.path, CONTEXT_TOKENS, maxBeams = MAX_BEAMS)
        Log.i(TAG, "loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "load failed", e)
        loadError = "The Chinese model couldn't start. Try removing and downloading it again."
        false
      } finally {
        loading = false
        recompute()
      }
    }
  }

  /** Starts loading in the background, for app launch and keyboard start. */
  fun preload() {
    if (::app.isInitialized && model == null && file.exists()) scope.launch { ensureLoaded() }
  }

  override suspend fun complete(prompt: String, maxTokens: Int, repeatPenalty: Float, banLatin: Boolean, onText: (String) -> Boolean): Scored? {
    if (!ensureLoaded()) return null
    val m = model ?: return null
    val c = m.complete(prompt, Sampling(maxTokens = maxTokens, repeatPenalty = repeatPenalty, banLatin = banLatin), onText)
    Log.d(TAG, "complete: prompt ${c.promptTokens} tok (${c.reusedTokens} reused) ${c.promptMs} ms, total ${c.totalMs} ms") // never text
    return Scored(c.text, c.logprob)
  }

  override suspend fun beams(prompt: String, search: Beams): List<Scored>? {
    if (!ensureLoaded()) return null
    val m = model ?: return null
    val (hyps, c) = m.beams(
      prompt,
      BeamSearch(
        beams = search.beams, maxTokens = search.maxTokens, lengthAlpha = search.lengthAlpha, banLatin = search.banLatin,
        stops = search.stops, stopText = search.stopText, keepUnfinished = search.keepUnfinished,
      ),
    )
    Log.d(TAG, "beams: ${hyps.size} from ${search.beams} beams, prompt ${c.promptTokens} tok (${c.reusedTokens} reused) ${c.promptMs} ms, total ${c.totalMs} ms") // never text
    return hyps.map { Scored(it.text, it.logprob.toDouble()) }
  }

  override suspend fun score(prompt: String, continuations: List<String>): List<Double>? {
    if (!ensureLoaded()) return null
    val m = model ?: return null
    val (logprobs, c) = m.score(prompt, continuations)
    Log.d(TAG, "score: ${continuations.size} options, ${c.generatedTokens} tok, total ${c.totalMs} ms") // never text
    return logprobs.map { it.toDouble() }
  }

  // ---------------------------------------------------------------- prompts

  @Volatile private var book: PromptBook? = null
  @Volatile private var bookSource: Long = Long.MIN_VALUE

  /**
   * The prompts: `prompts.json` in the app's external files directory when present (push a variant
   * with adb to try it on the phone, no rebuild), otherwise the bundled one. Re-read when the file
   * changes; a broken override is logged and the bundled prompts are used.
   */
  fun prompts(): PromptBook {
    val override = app.getExternalFilesDir(null)?.let { File(it, "prompts.json") }?.takeIf { it.isFile }
    val source = override?.lastModified() ?: -1L
    book?.takeIf { source == bookSource }?.let { return it }
    val loaded = override?.let { f ->
      runCatching { PromptBook.parse(f.readText()) }
        .onFailure { Log.w(TAG, "prompts.json override ignored: ${it.message}") }
        .getOrNull()
    } ?: PromptBook.parse(app.assets.open("prompts.json").bufferedReader().use { it.readText() })
    Log.i(TAG, "prompts ${loaded.version} from ${if (override != null && source >= 0) "override" else "assets"}")
    book = loaded
    bookSource = source
    return loaded
  }

  fun download() {
    WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, ModelDownloadWorker.request())
  }

  fun cancelDownload() {
    WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
  }

  /** Stops any download, frees the memory and deletes the files, partial download included. */
  fun remove() {
    cancelDownload()
    scope.launch {
      loadLock.withLock {
        model?.release()
        model = null
        loadError = null
        dir.deleteRecursively()
      }
      WorkManager.getInstance(app).pruneWork()
      work = null
      recompute()
    }
  }
}
