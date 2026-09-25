package app.langboard.core

/**
 * The on-device language model as the engines see it: a prompt in, text out. Null when no model
 * is installed. Implemented by app.langboard.model.ModelManager over llama.cpp.
 */
interface TextModel {
  /**
   * Greedy decoding. [onText] gets the output so far and returns false once it has enough. Tokens
   * with an ASCII letter lose [latinPenalty] (infinity bans them). Must be cancellable.
   */
  suspend fun complete(prompt: String, maxTokens: Int, repeatPenalty: Float, latinPenalty: Float = 0f, onText: (String) -> Boolean): Scored?

  /** The most likely distinct completions, best first, by beam search. Must be cancellable. */
  suspend fun beams(prompt: String, search: Beams): List<Scored>?

  /** The log-probability of each of [continuations] right after [prompt], in order. Must be cancellable. */
  suspend fun score(prompt: String, continuations: List<String>): List<Double>?
}

/** A beam search; see app.langboard.llama.BeamSearch for what each setting does. */
data class Beams(
  val beams: Int,
  val maxTokens: Int,
  val lengthAlpha: Float,
  val latinPenalty: Float = 0f,
  val stops: List<String> = listOf("。", "！", "？"),
  val stopText: String = "",
  val keepUnfinished: Boolean = false,
)

/** Model output with its total natural-log probability. */
data class Scored(val text: String, val logprob: Double)

/**
 * Fill Gap on Hy-MT2. The prompt asks for the whole sentence in Chinese and starts the answer with
 * the user's Chinese before the gap ([PromptBook.fill]), so the model writes on from there; what it
 * writes has to fit the sentence (我本来想 + 玩轮滑, not the noun 旱冰鞋). Tokens with English
 * letters are penalized ([PromptBook.Fill.latinPenalty]), so it rarely copies the English back but
 * can still answer AI or AA制; [HyMtPrompts.cleanFill] drops copies and other English that slips through.
 *
 * Passes over one prompt, which stays cached:
 * 1. Greedy (the likeliest token each step): the first answer, to [onFirst] straight away.
 * 2. Beam search: the model's other likely answers. Each beam writes on past its answer into the
 *    text after the gap, so its probability already covers how the answer reads with what follows.
 *    Answers whose beams wander off somewhere else instead are dropped (unless nothing fits).
 * 3. Only with [PromptBook.Fill.rescore] > 0: each answer scored again with exactly the start of
 *    the text after the gap. Measured on the dev set it reshuffles rather than improves, and costs
 *    1.5–3 s on a Pixel 6a, so it's off in the shipped prompts.
 *
 * Options are ordered by probability, and each one's share is its probability over the total of
 * those shown. With [PromptBook.Fill.pinFirst], the greedy answer would instead stay first.
 */
class ModelFillGapEngine(
  private val model: TextModel,
  private val modelVersion: String,
  private val prompts: () -> PromptBook,
) : FillGapEngine {
  override suspend fun fill(request: FillGapRequest): FillGapResult? = fill(request) {}

  suspend fun fill(request: FillGapRequest, onFirst: suspend (FillGapResult) -> Unit): FillGapResult? {
    val book = prompts()
    val f = book.fill
    val prompt = book.fill(request)
    val version = "$modelVersion/${book.version}"
    val (stops, stopText) = HyMtPrompts.stopsFor(request.after)
    val head = HyMtPrompts.afterHead(request.after, f.afterChars)
    // Room for the answer and the start of the text after it.
    val budget = f.maxTokens + head.length
    val reached = { text: String ->
      '\n' in text || stops.any { it in text } || (stopText.isNotEmpty() && text.indexOf(stopText, 1) >= 0)
    }
    // No repetition penalty: the greedy answer should be the likeliest path, comparable with the beams.
    val greedy = model.complete(prompt, budget, 1f, latinPenalty = f.latinPenalty) { !reached(it) } ?: return null
    val first = HyMtPrompts.gapAnswer(greedy.text, request)
    if (first != null) onFirst(FillGapResult(first, version, more = true))
    val beams = model.beams(
      prompt, Beams(f.beams, budget, f.lengthAlpha, latinPenalty = f.latinPenalty, stops = stops, stopText = stopText, keepUnfinished = true),
    ).orEmpty()
    val ranked = rank(first.takeIf { f.pinFirst }, if (f.rescore > 0) rescored(prompt, request, head, first, beams, f) ?: return null else fitting(request, beams + greedy))
    if (ranked.isEmpty()) return null
    val (best, bestShare) = ranked.first()
    return FillGapResult(
      best, version, share = bestShare,
      alternatives = ranked.drop(1).map { (text, share) -> Candidate(text, null, share = share) },
    )
  }

  /** Each answer's probability as the beams (and the greedy pass) wrote it, from the ones that fit the sentence. */
  private fun fitting(request: FillGapRequest, outputs: List<Scored>): Map<String, Double> {
    val fit = LinkedHashMap<String, Double>()
    val loose = LinkedHashMap<String, Double>()
    for (o in outputs) {
      val gap = HyMtPrompts.gapOf(o.text, request.after) ?: continue
      val answer = HyMtPrompts.cleanFill(gap, request, trimAfter = false) ?: continue
      val into = if (HyMtPrompts.leadsOn(o.text, gap, request.after)) fit else loose
      into[answer] = maxOf(into[answer] ?: Double.NEGATIVE_INFINITY, o.logprob)
    }
    return fit.ifEmpty { loose }
  }

  /** Each answer scored in the sentence, followed by the start of the text after the gap (or the end of the sentence). */
  private suspend fun rescored(
    prompt: String, request: FillGapRequest, head: String, first: String?, beams: List<Scored>, f: PromptBook.Fill,
  ): Map<String, Double>? {
    val options = (listOfNotNull(first) + beams.mapNotNull { HyMtPrompts.gapAnswer(it.text, request) }).distinct().take(f.rescore + 1)
    if (options.isEmpty()) return emptyMap()
    // The whole text after the gap, or none at all: then the sentence ends there.
    val tail = head + if (head == request.after.trim()) f.endMark else ""
    val logprobs = model.score(prompt, options.map { it + tail }) ?: return null
    return options.zip(logprobs).toMap()
  }

  companion object {
    const val MAX_OPTIONS = 10

    /** Answers with their share of probability among those shown, [pinned] first, the rest likeliest first. */
    fun rank(pinned: String?, logprobs: Map<String, Double>): List<Pair<String, Float>> {
      val top = logprobs.values.maxOrNull() ?: return emptyList()
      val p = logprobs.mapValues { Math.exp(it.value - top) }
      val order = (listOfNotNull(pinned?.takeIf { it in p }) + p.entries.sortedByDescending { it.value }.map { it.key })
        .distinct().take(MAX_OPTIONS)
      val total = order.sumOf { p.getValue(it) }
      return order.map { it to (p.getValue(it) / total).toFloat() }
    }
  }
}

/** Explain on Hy-MT2: the message translated into English, with the chat around it as background. */
class ModelExplainEngine(private val model: TextModel, private val prompts: () -> PromptBook) : ExplainEngine {
  override suspend fun explain(message: String, screen: ScreenText): ExplainResult? {
    val book = prompts()
    val out = model.complete(book.explain(message, screen), book.explain.maxTokens, book.explain.repeatPenalty, onText = ::firstLineOnly)
      ?: return null
    return ExplainResult(message, HyMtPrompts.cleanTranslation(out.text), emptyList())
  }
}

/**
 * Check on Hy-MT2: the sentence rewritten as more natural Chinese in the chat's style. When the
 * model's likeliest rewrite is the sentence itself (give or take punctuation, and particles unless
 * [AssistMode.NATIVE]), it reads naturally and nothing is shown.
 */
class ModelCheckEngine(private val model: TextModel, private val prompts: () -> PromptBook) : NaturalizeEngine {
  override suspend fun review(sentence: String, mode: AssistMode, register: Register, screen: ScreenText): NaturalizeResult? {
    if (mode == AssistMode.STUCK) return null
    val book = prompts()
    val outs = model.beams(book.check(sentence, register, screen), Beams(book.check.beams, book.check.maxTokens, book.check.lengthAlpha))
      ?.map { it.text } ?: return null
    val picky = mode == AssistMode.NATIVE
    val best = outs.firstOrNull() ?: return null
    val natural = HyMtPrompts.cleanCheck(best, sentence, screen, picky) ?: return null
    val others = outs.drop(1).mapNotNull { HyMtPrompts.cleanCheck(it, sentence, screen, picky) }.filter { it != natural }.distinct()
    return NaturalizeResult(sentence, natural, others.take(MAX_OPTIONS - 1))
  }

  companion object {
    const val MAX_OPTIONS = 4
  }
}

private fun firstLineOnly(text: String) = !text.trimStart().contains('\n')
