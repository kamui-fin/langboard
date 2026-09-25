package app.langboard.llama

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Sampling for one completion. `temperature <= 0` is greedy. The other defaults are Tencent's
 * recommendation for Hy-MT2 1.8B (top-k 20, top-p 0.6, repetition penalty 1.05, temperature 0.7
 * when sampling).
 */
data class Sampling(
  val maxTokens: Int = 64,
  val temperature: Float = 0f,
  val topK: Int = 20,
  val topP: Float = 0.6f,
  val repeatPenalty: Float = 1.05f,
  val seed: Int = 1,
  /**
   * Subtracted from the logit of every token with an ASCII letter in it, so a Chinese answer only
   * uses Latin letters (AI, AA制, K歌) when the model strongly prefers them. Infinity bans them.
   */
  val latinPenalty: Float = 0f,
)

data class Completion(
  val text: String,
  val promptTokens: Int,
  val generatedTokens: Int,
  /** From the call to the prompt being processed. */
  val promptMs: Long,
  /** From the call to the first output token, or -1 when nothing was generated. */
  val firstTokenMs: Long,
  val totalMs: Long,
  /** Prompt tokens reused from the previous completion's cache instead of processed again. */
  val reusedTokens: Int = 0,
  /** Natural-log probability of [text] under the model's unmodified distribution. */
  val logprob: Double = 0.0,
)

/** Beam search settings. [lengthAlpha] 0 ranks by total log-probability, which favours short answers. */
data class BeamSearch(
  val beams: Int = 10,
  val maxTokens: Int = 24,
  val lengthAlpha: Float = 0f,
  /** A newline ends a hypothesis: answers are one line. */
  val stopAtNewline: Boolean = true,
  /** As [Sampling.latinPenalty]. */
  val latinPenalty: Float = 0f,
  /** A token whose text contains one of these ends a hypothesis, with the token kept. */
  val stops: List<String> = listOf("。", "！", "？"),
  /** A hypothesis whose text contains this after its first character ends there. Empty for none. */
  val stopText: String = "",
  /** Hypotheses still going at [maxTokens] are returned rather than dropped. */
  val keepUnfinished: Boolean = false,
)

/** One beam-search result. [logprob] is its total natural-log probability; higher is likelier. */
data class Hypothesis(val text: String, val logprob: Float)

class LlamaException(message: String) : Exception(message)

/**
 * A loaded GGUF with its own context. One completion or beam search runs at a time, on a dedicated
 * thread. A prompt that starts like the previous one reuses that part of the cache. Cancelling the calling coroutine stops the work, prompt included.
 */
class LlamaModel private constructor(private var handle: Long, val contextSize: Int, val maxBeams: Int) : AutoCloseable {

  private val mutex = Mutex()

  /**
   * [onText] gets the text generated so far after each token and returns false once it has enough
   * (for example, a first line), which ends the completion normally.
   */
  suspend fun complete(
    prompt: String,
    sampling: Sampling = Sampling(),
    onText: (String) -> Boolean = { true },
  ): Completion {
    val text = StringBuilder()
    val r = run { job ->
      val sink = TokenSink { bytes ->
        text.append(String(bytes, Charsets.UTF_8))
        job.isActive && onText(text.toString())
      }
      val t = LongArray(8)
      LlamaNative.generate(
        handle, prompt.toByteArray(Charsets.UTF_8), sampling.maxTokens, sampling.temperature,
        sampling.topK, sampling.topP, sampling.repeatPenalty, sampling.seed, sampling.latinPenalty, sink, t,
      )
      t
    }
    return completion(text.toString(), r)
  }

  /**
   * The most likely distinct completions of [prompt], best first: up to about twice [search]'s beam
   * count, as every beam that ended is kept. Beams share the prompt in the cache and are decoded
   * together, one batch per token, so ten cost little more than one.
   */
  suspend fun beams(prompt: String, search: BeamSearch = BeamSearch()): Pair<List<Hypothesis>, Completion> {
    var out: Array<ByteArray> = emptyArray()
    val scores = FloatArray(search.beams * 3)
    val r = run {
      val t = LongArray(8)
      out = LlamaNative.beams(
        handle, prompt.toByteArray(Charsets.UTF_8), search.beams, search.maxTokens, search.lengthAlpha,
        search.stopAtNewline, search.latinPenalty, search.stops.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        search.stopText.toByteArray(Charsets.UTF_8), search.keepUnfinished, scores, t,
      )
      t
    }
    val hyps = out.mapIndexed { i, b -> Hypothesis(String(b, Charsets.UTF_8), scores.getOrElse(i) { Float.NEGATIVE_INFINITY }) }
    return hyps to completion(hyps.firstOrNull()?.text.orEmpty(), r)
  }

  /**
   * The natural-log probability of each of [continuations] written right after [prompt], scored
   * together in one batch over the cached prompt. The sums compare: every continuation is scored
   * from the same point.
   */
  suspend fun score(prompt: String, continuations: List<String>): Pair<FloatArray, Completion> {
    val logprobs = FloatArray(continuations.size)
    val r = run {
      val t = LongArray(8)
      LlamaNative.score(
        handle, prompt.toByteArray(Charsets.UTF_8), continuations.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        logprobs, t,
      )
      t
    }
    return logprobs to completion("", r)
  }

  /** Runs [call] on the model thread, one at a time; cancelling the caller aborts it, prompt included. */
  private suspend fun run(call: (kotlinx.coroutines.Job) -> LongArray): LongArray = mutex.withLock {
    check(handle != 0L) { "model closed" }
    coroutineContext.ensureActive()
    coroutineScope {
      val done = AtomicBoolean(false)
      val job = coroutineContext.job
      // Aborts the native call when this coroutine is cancelled.
      val watcher = launch {
        try { awaitCancellation() } finally { if (!done.get()) LlamaNative.cancel(handle) }
      }
      val r = withContext(dispatcher) { call(job) }
      done.set(true)
      watcher.cancel()
      coroutineContext.ensureActive()
      r
    }
  }

  private fun completion(text: String, r: LongArray): Completion = when (r[0]) {
    STATUS_OK -> Completion(text, r[1].toInt(), r[2].toInt(), r[3] / 1000, if (r[2] > 0) r[4] / 1000 else -1, r[5] / 1000, r[6].toInt(), r[7] / 1e6)
    STATUS_CANCELLED -> throw kotlinx.coroutines.CancellationException("completion cancelled")
    STATUS_TOO_LONG -> throw LlamaException("prompt of ${r[1]} tokens does not fit a $contextSize-token context")
    else -> throw LlamaException("generation failed")
  }

  /** Stops a running completion, waits for it to end, then frees the model. */
  suspend fun release() {
    val h = handle
    if (h != 0L) LlamaNative.cancel(h)
    mutex.withLock { close() }
  }

  /** Frees the model. Only when no completion can be running; otherwise use [release]. */
  override fun close() {
    if (handle != 0L) {
      LlamaNative.free(handle)
      handle = 0L
    }
  }

  companion object {
    private const val STATUS_OK = 0L
    private const val STATUS_CANCELLED = 1L
    private const val STATUS_TOO_LONG = 2L

    /** All native calls run here, so a model is never used from two threads at once. */
    private val dispatcher: CoroutineDispatcher =
      Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { isDaemon = true } }.asCoroutineDispatcher()

    @Volatile private var initialized = false

    private fun init(context: Context) {
      if (initialized) return
      synchronized(this) {
        if (!initialized) {
          LlamaNative.init(context.applicationInfo.nativeLibraryDir)
          initialized = true
        }
      }
    }

    /** Which CPU features llama.cpp found and uses, for logs and diagnostics. */
    suspend fun systemInfo(context: Context): String = withContext(dispatcher) {
      init(context)
      LlamaNative.systemInfo()
    }

    /**
     * Memory-maps the model at [path] and creates a [contextSize]-token context using [threads]
     * threads, with room for beam searches of up to [maxBeams] beams. Throws [LlamaException] when
     * the file isn't a model this build can run.
     */
    suspend fun load(
      context: Context, path: String, contextSize: Int = 1024, threads: Int = defaultThreads(), maxBeams: Int = 16,
    ): LlamaModel =
      withContext(dispatcher) {
        init(context)
        val h = LlamaNative.load(path, contextSize, threads, maxBeams)
        if (h == 0L) throw LlamaException("could not load $path")
        LlamaModel(h, contextSize, maxBeams)
      }

    /** The big cores: phones have 2–4, and using the little ones too makes decoding slower. */
    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else maxOf(1, it / 2) }
  }
}

internal fun interface TokenSink {
  fun onBytes(bytes: ByteArray): Boolean
}

internal object LlamaNative {
  init {
    System.loadLibrary("langboard_llama")
  }

  external fun init(nativeLibDir: String)
  external fun systemInfo(): String
  external fun load(path: String, contextSize: Int, threads: Int, maxBeams: Int): Long
  /** Timings go into [out]: {status, promptTokens, generatedTokens, promptUs, firstTokenUs, totalUs, reusedTokens, logprob µnats}. */
  external fun generate(
    handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, topK: Int, topP: Float,
    repeatPenalty: Float, seed: Int, latinPenalty: Float, sink: TokenSink, out: LongArray,
  ): Long
  /** Completions best first; their log-probabilities go into [scores], timings into [out] as for [generate]. */
  external fun beams(
    handle: Long, prompt: ByteArray, beams: Int, maxTokens: Int, lengthAlpha: Float, stopAtNewline: Boolean,
    latinPenalty: Float, stops: Array<ByteArray>, stopText: ByteArray, keepUnfinished: Boolean, scores: FloatArray, out: LongArray,
  ): Array<ByteArray>
  /** Log-probabilities of [continuations] after [prompt] go into [logprobs], timings into [out] as for [generate]. */
  external fun score(handle: Long, prompt: ByteArray, continuations: Array<ByteArray>, logprobs: FloatArray, out: LongArray): Long
  external fun cancel(handle: Long)
  external fun free(handle: Long)
}
