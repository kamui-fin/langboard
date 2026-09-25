package app.langboard.ime

import android.content.Intent
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.ClipData
import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.langboard.cedict.Pinyin
import app.langboard.assist.ConversationContextService
import app.langboard.core.AssistMode
import app.langboard.core.Candidate
import app.langboard.core.DetectResult
import app.langboard.core.Detection
import app.langboard.core.EditPlan
import app.langboard.core.FillGapEngine
import app.langboard.core.FillGapRequest
import app.langboard.core.FillGapResult
import app.langboard.core.FragmentDetector
import app.langboard.core.LangboardSettings
import app.langboard.core.NaturalizeEngine
import app.langboard.core.NaturalizeResult
import app.langboard.core.ModelState
import app.langboard.core.SentenceFinder
import app.langboard.core.SentenceSpan
import app.langboard.core.Conversation
import app.langboard.core.ExplainEngine
import app.langboard.core.ExplainResult
import app.langboard.model.Engines
import app.langboard.model.ModelManager
import app.langboard.ui.theme.ReadingPrefs
import app.langboard.MainActivity
import app.langboard.core.QuickLookup
import app.langboard.core.ScreenText
import app.langboard.core.Term
import app.langboard.dictionary.DictionaryManager
import app.langboard.dictionary.OpenDictionary
import app.langboard.dictionary.Readings
import app.langboard.history.HistoryEntry
import app.langboard.history.HistoryKind
import app.langboard.history.HistoryStore
import app.langboard.history.Outcome
import app.langboard.ui.theme.LangboardTheme
import app.langboard.billing.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LangboardImeService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

  private val lifecycleRegistry = LifecycleRegistry(this)
  private val savedStateController = SavedStateRegistryController.create(this)
  override val lifecycle: Lifecycle get() = lifecycleRegistry
  override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
  override val viewModelStore = ViewModelStore()

  private val engine get() = Engines.fill
  private val naturalizer: NaturalizeEngine get() = Engines.check
  private val explainer: ExplainEngine get() = Engines.explain
  private lateinit var settings: LangboardSettings
  private lateinit var history: HistoryStore
  /** History writes outlive the panel's own jobs, which are cancelled whenever the text changes. */
  private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  var state by mutableStateOf<ImeState>(ImeState.Idle(checked = false))
    private set
  /** Pinyin and tone colors, from Settings; re-read each time the keyboard shows. */
  var reading by mutableStateOf(ReadingPrefs())
  private var dynamicColor by mutableStateOf(true)

  /** How many options the panel lists (Settings). */
  var optionCount by mutableStateOf(10)
    private set
  /** The Chinese pack: loading and missing change what the fill panel says. */
  var modelState by mutableStateOf<ModelState>(ModelState.NotInstalled)
    private set
  var panel by mutableStateOf<ImePanel?>(null)
    private set
  var quick by mutableStateOf<QuickRow?>(null)
    private set
  var words by mutableStateOf<WordsState?>(null)
    private set
  /** Whether what the main panel shows is saved. */
  var saved by mutableStateOf(false)
    private set
  /** Expressions from the current message the user saved, by text. */
  var savedTerms by mutableStateOf<Set<String>>(emptySet())
    private set
  private var mode = AssistMode.COACH
  /** Inverse of the last edit Langboard made; valid until the user moves the caret. */
  private var undoPlan by mutableStateOf<EditPlan?>(null)

  /** Bumped whenever the target editor changes; stale work compares against it. */
  private var session = 0
  private var fieldSensitive = false
  private var fieldNonText = false
  private var job: Job? = null
  private var quickJob: Job? = null
  private var wordsJob: Job? = null
  /** Caret position our own edit should produce, so its selection update isn't treated as a user move. */
  private var expectedCaret = -1
  private var selStart = -1
  private var selEnd = -1
  private var currentField: FieldKey? = null
  private var sameFieldRestart = false
  /** Recent answers for this field, so moving the caret back and forth doesn't re-run the engine. */
  private val cache = object : LinkedHashMap<FillGapRequest, FillGapResult>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FillGapRequest, FillGapResult>) = size > CACHE_SIZE
  }
  /** Checked sentences for this field, so moving the caret doesn't run the model again. */
  private data class CheckOutcome(val result: NaturalizeResult?)
  private val checks = HashMap<String, CheckOutcome>()

  /**
   * Screen text read once per invocation (switching here), not per caret move: the screen doesn't
   * change while the user edits their draft. Dropped with the rest of the field state.
   */
  private var chat: ChatContext? = null

  /** The history row for what's on the panel. [entryKey] stops caret moves from recording it twice. */
  private var entryKey: String? = null
  private var entry: HistoryEntry? = null
  private var entryId: Deferred<Long>? = null
  private val termIds = HashMap<String, Deferred<Long>>()

  private data class FieldKey(val packageName: String?, val fieldId: Int, val inputType: Int)

  override fun onCreate() {
    super.onCreate()
    savedStateController.performRestore(null)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    settings = LangboardSettings(this)
    history = HistoryStore.get(this)
    ModelManager.init(this)
    lifecycleScope.launch { ModelManager.state.collect { modelState = it } }
    ModelManager.preload()
    // Pinyin tables, so the first options show their readings without a wait.
    lifecycleScope.launch(Dispatchers.Default) { Readings.get(this@LangboardImeService).warm() }
  }

  override fun onCreateInputView(): View {
    window.window?.decorView?.let { attachOwners(it) }
    return ComposeView(this).apply {
      attachOwners(this)
      setContent {
        LangboardTheme(reading = reading, dynamicColor = dynamicColor) {
          KeyboardPanel(
            state = state, model = modelState, panel = panel, optionCount = optionCount, quick = quick, words = words, saved = saved, savedTerms = savedTerms,
            onAction = ::onAction, onPanelBounds = ::onPanelBounds,
          )
        }
      }
    }
  }

  /** Window-pixel tops of the visible panel and of the panel it's settling at; see [onComputeInsets]. */
  private var visibleTop = -1
  private var roomTop = -1

  private fun onPanelBounds(visible: Int, room: Int) {
    if (visible == visibleTop && room == roomTop) return
    visibleTop = visible
    roomTop = room
    // Insets are recomputed on the next traversal; the animating panel already schedules one each frame.
    window.window?.decorView?.postInvalidateOnAnimation()
  }

  /**
   * The window is always as tall as the tallest panel. Apps make room for the panel the sheet is
   * settling at, compact or expanded: the host app re-lays out once when a panel opens or closes, never
   * per frame while it animates or is dragged. Covering the cursor isn't an option: apps draw its handle
   * in a popup above our window. Only the visible panel takes touches; the transparent area above it
   * passes them through to the app.
   */
  override fun onComputeInsets(outInsets: Insets) {
    super.onComputeInsets(outInsets)
    val decor = window.window?.decorView ?: return
    if (roomTop < 0 || !isInputViewShown) return
    outInsets.contentTopInsets = roomTop
    outInsets.visibleTopInsets = roomTop
    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
    outInsets.touchableRegion.set(0, minOf(visibleTop, roomTop), decor.width, decor.height)
  }

  private fun attachOwners(view: View) {
    view.setViewTreeLifecycleOwner(this)
    view.setViewTreeSavedStateRegistryOwner(this)
    view.setViewTreeViewModelStoreOwner(this)
  }

  // Never take over the whole screen in landscape; the host text must stay visible.
  override fun onEvaluateFullscreenMode(): Boolean = false

  override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
    super.onStartInput(attribute, restarting)
    selStart = attribute.initialSelStart
    selEnd = attribute.initialSelEnd
    // `restarting` alone doesn't mean the same editor: focusing a field after an empty (TYPE_NULL)
    // start arrives as a restart too. Only an identical field keeps its suggestions/Undo.
    val field = FieldKey(attribute.packageName, attribute.fieldId, attribute.inputType)
    sameFieldRestart = restarting && field == currentField
    currentField = field
    classifyField(attribute)
    if (sameFieldRestart) return
    session++
    resetField()
  }

  override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    // Android may have killed the process since the model was last loaded.
    ModelManager.preload()
    if (!settings.keyboardUsed) settings.keyboardUsed = true
    mode = settings.assistMode
    reading = ReadingPrefs(pinyin = settings.showPinyin, toneColors = settings.toneColors)
    dynamicColor = settings.colorSource == LangboardSettings.ColorSource.DEVICE
    optionCount = settings.optionCount
    // A restart on the same field keeps what's on screen; edits are re-verified anyway.
    val keep = sameFieldRestart && state !is ImeState.Idle
    if (!keep) refresh()
  }

  override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    // Also called from super.onDestroy(), after our lifecycle is already DESTROYED.
    if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
  }

  override fun onFinishInput() {
    super.onFinishInput()
    // Drop all transient host text.
    resetField()
  }

  private fun resetField() {
    cancelWork()
    cache.clear()
    checks.clear()
    chat = null
    entryKey = null
    entry = null
    entryId = null
    termIds.clear()
    saved = false
    savedTerms = emptySet()
    undoPlan = null
    state = ImeState.Idle(checked = false)
    panel = null
  }

  override fun onUpdateSelection(
    oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
  ) {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    selStart = newSelStart
    selEnd = newSelEnd
    if (expectedCaret >= 0 && newSelStart == expectedCaret && newSelEnd == expectedCaret) {
      expectedCaret = -1
      return
    }
    expectedCaret = -1
    if (!isInputViewShown) return
    // The user moved the cursor or the text changed underneath us: suggestions and Undo are stale.
    cancelWork()
    undoPlan = null
    refresh()
    when (panel) {
      ImePanel.Words -> if (currentDetection() == null) panel = null else openWords()
      ImePanel.Details -> if (currentDetection() == null) panel = null
      ImePanel.Naturalize -> if (state !is ImeState.Unnatural) panel = null
      ImePanel.Message -> if (state !is ImeState.Explain) panel = null
      is ImePanel.TermDetail -> if (state !is ImeState.Explain && state !is ImeState.Suggestions) panel = null
      null -> Unit
    }
  }

  override fun onDestroy() {
    cancelWork()
    // super first: it finishes the input view, which still needs a live lifecycle.
    super.onDestroy()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    viewModelStore.clear()
  }

  private fun classifyField(info: EditorInfo) {
    val kind = FieldPolicy.classify(info.inputType)
    fieldSensitive = kind == FieldPolicy.Kind.Sensitive
    fieldNonText = kind == FieldPolicy.Kind.NonText
  }

  private fun cancelWork() {
    job?.cancel(); job = null
    quickJob?.cancel(); quickJob = null
    wordsJob?.cancel(); wordsJob = null
    quick = null
    words = null
  }

  /**
   * Reads the text around the caret and decides what the panel does, with no menu to ask: English
   * in the draft is filled in right away (switching here is the request), a Chinese draft is checked
   * unless the mode is Stuck only, and an empty draft explains the latest message received.
   */
  private fun refresh() {
    if (!Subscription.activeOffline(settings)) {
      state = ImeState.Locked
      return
    }
    if (fieldSensitive || fieldNonText) {
      state = ImeState.UnsupportedField(sensitive = fieldSensitive)
      return
    }
    val ic = currentInputConnection ?: run { state = ImeState.Unreadable; return }
    val (before, selected, after) = VerifiedEditor(ic).snapshot(WINDOW_BEFORE, WINDOW_AFTER)
    if (before == null && selected == null) {
      state = ImeState.Unreadable
      return
    }
    val complete = before != null && before.length < WINDOW_BEFORE
    when (val r = FragmentDetector.detect(before, selected, after, complete)) {
      is DetectResult.Found -> fill(r.detection)
      DetectResult.Ambiguous -> state = ImeState.NeedsSelection(tooLong = false)
      DetectResult.TooLong -> state = ImeState.NeedsSelection(tooLong = true)
      DetectResult.None -> {
        if (before.isNullOrBlank() && after.isNullOrBlank() && selected.isNullOrEmpty()) return explainLatest()
        val sentence = if (selected.isNullOrEmpty()) SentenceFinder.find(before, complete) else null
        // Checking needs the model; without it there's nothing to say about a Chinese draft.
        if (sentence == null || mode == AssistMode.STUCK || !ModelManager.state.value.installed) {
          state = ImeState.Idle(checked = false)
          return
        }
        checks[sentence.sentence]?.let { r ->
          state = r.result?.let { ImeState.Unnatural(sentence, it) } ?: ImeState.Idle(checked = true)
          return
        }
        val mySession = session
        state = ImeState.Checking(sentence)
        job?.cancel()
        job = lifecycleScope.launch {
          val ctx = chatContext()
          val result = runCatching {
            withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
              withContext(Dispatchers.Default) { CheckOutcome(naturalizer.review(sentence.sentence, mode, ctx.style, ctx.screen)) }
            }
          }
          ensureActive()
          if (mySession != session) return@launch
          val outcome = result.getOrNull()
          state = when {
            outcome == null -> ImeState.Error
            outcome.result == null -> ImeState.Idle(checked = true)
            else -> ImeState.Unnatural(sentence, outcome.result)
          }
          if (outcome != null) checks[sentence.sentence] = outcome
          outcome?.result?.let(::recordCheck)
        }
      }
    }
  }

  private fun fill(d: Detection) {
    val mySession = session
    lookupQuick(d)
    state = ImeState.Working(d)
    job?.cancel()
    job = lifecycleScope.launch {
      val ctx = chatContext()
      val request = FillGapRequest(
        d.contextBefore, d.fragment, d.contextAfter, register = ctx.style, screen = ctx.screen,
      )
      val prompt = runCatching { ModelManager.prompts().fill(request) }.getOrNull()
      cache[request]?.let { state = ImeState.Suggestions(d, it, ctx, prompt); recordFill(d, it, ctx); return@launch }
      val started = System.nanoTime()
      val result = runCatching {
        withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
          withContext(Dispatchers.Default) {
            engine.fill(request) { first ->
              // The first answer goes up the moment it exists; the other options follow below it.
              withContext(Dispatchers.Main) {
                Log.d(TAG, "first answer after ${(System.nanoTime() - started) / 1_000_000}ms") // never text
                if (mySession == session && state is ImeState.Working) state = ImeState.Suggestions(d, first, ctx, prompt)
              }
            }
          }
        }
      }
      // runCatching also catches our own cancellation; a superseded run must not overwrite the new one.
      ensureActive()
      Log.d(TAG, "fill took ${(System.nanoTime() - started) / 1_000_000}ms, ${ctx.messages} messages") // never text
      result.exceptionOrNull()?.let { Log.w(TAG, "fill failed: ${it.javaClass.name}: ${it.message}") }
      val waiting = state.let { it is ImeState.Working || (it is ImeState.Suggestions && it.detection == d && it.result.more) }
      if (mySession != session || !waiting) return@launch
      val answer = result.getOrNull()
      state = result.fold(
        onSuccess = { if (answer == null) ImeState.NoMatch(d, modelMissing = !ModelManager.state.value.installed) else ImeState.Suggestions(d, answer, ctx, prompt).also { cache[request] = answer } },
        onFailure = { ImeState.Error },
      )
      if (result.isSuccess) recordFill(d, answer, ctx)
      // Meanings for options that are dictionary words, filled in once they're on screen.
      if (answer != null) {
        val meant = answer.copy(
          meaning = withReading(Candidate(answer.replacement, null)).meaning,
          alternatives = answer.alternatives.map { withReading(it) },
        )
        val now = state as? ImeState.Suggestions
        if (mySession == session && now?.result == answer) {
          state = now.copy(result = meant)
          cache[request] = meant
        }
      }
    }
  }

  /** The screen, read once per invocation within [CONTEXT_TIMEOUT_MS]; empty when context is off. */
  private suspend fun chatContext(): ChatContext = chat ?: withContext(Dispatchers.Default) {
    ChatContext.of(withTimeoutOrNull(CONTEXT_TIMEOUT_MS) { ConversationContextService.read() } ?: ScreenText.EMPTY, settings.defaultRegister)
  }.also { chat = it }

  /**
   * Empty draft: explain the newest message someone sent, if the screen shows one. The glossary (the
   * model, later) picks the expressions worth explaining; CC-CEDICT splits the rest into words.
   */
  private fun explainLatest() {
    if (!ConversationContextService.isRunning) {
      state = ImeState.Idle(checked = false)
      return
    }
    val current = state as? ImeState.Explain
    if (current?.result != null && chat?.let { Conversation.latestIncoming(it.screen) } == current.message) return
    val mySession = session
    state = ImeState.Explain("", null, ChatContext.NONE)
    job?.cancel()
    job = lifecycleScope.launch {
      val ctx = chatContext()
      val message = Conversation.latestIncoming(ctx.screen)
      if (mySession != session) return@launch
      if (message == null) {
        state = ImeState.Idle(checked = false)
        return@launch
      }
      state = ImeState.Explain(message, null, ctx)
      val found = runCatching {
        withTimeoutOrNull(GENERATION_TIMEOUT_MS) { withContext(Dispatchers.Default) { explainer.explain(message, ctx.screen) } }
      }.getOrNull()
      ensureActive()
      val words = runCatching { DictionaryManager.get(this@LangboardImeService).breakdown(message) }.getOrNull()
        ?.filter { (w, _) -> w.any(FragmentDetector::isCjkIdeograph) }
        ?.map { (w, e) -> Term(w, e?.pinyin?.let(Pinyin::toToneMarks), e?.english?.take(2)?.joinToString("; ")) }
        .orEmpty()
      ensureActive()
      if (mySession != session) return@launch
      val result = (found ?: ExplainResult(message, null, emptyList())).copy(words = words)
      state = ImeState.Explain(message, result, ctx)
      recordExplain(result, ctx)
    }
  }

  private fun onAction(action: ImeAction) {
    when (action) {
      is ImeAction.Insert -> insert(action.candidate)
      is ImeAction.ReplaceSentence -> replaceSentence(action.text)
      ImeAction.Undo -> undo()
      ImeAction.Retry -> { cancelWork(); refresh() }
      ImeAction.SwitchKeyboard -> switchKeyboard()
      ImeAction.OpenApp -> startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
      )
      is ImeAction.OpenPanel -> {
        panel = action.panel
        if (action.panel == ImePanel.Words) openWords()
      }
      ImeAction.ClosePanel -> panel = (panel as? ImePanel.TermDetail)?.parent
      is ImeAction.Copy -> copy(action.candidate)
      is ImeAction.ToggleSave -> toggleSave(action.answer)
      is ImeAction.ToggleSaveTerm -> toggleSaveTerm(action.term)
    }
  }

  private fun currentDetection(): Detection? = when (val s = state) {
    is ImeState.Working -> s.detection
    is ImeState.Suggestions -> s.detection
    is ImeState.NoMatch -> s.detection
    else -> null
  }

  /** Replaces the detected English with [c]: the model's answer, a Coach pick or a dictionary chip. */
  private fun insert(c: Candidate) {
    val target = currentDetection() ?: return
    panel = null
    job?.cancel()
    // A dictionary chip used before the model answered is still a lookup worth keeping.
    record("fill|${target.fragment}") {
      HistoryEntry(
        createdAt = System.currentTimeMillis(), kind = HistoryKind.Fill, app = currentField?.packageName,
        source = target.fragment, answer = c.text, pinyin = c.pinyin, meaning = c.meaning,
      )
    }
    edit(EditPlan.replace(target, c.text), c.text) {
      val sentence = (target.contextBefore + c.text + target.tail).trim()
      outcome(Outcome.Inserted, used = c.text.takeIf { it != entry?.answer }, sentence = sentence)
    }
  }

  private fun replaceSentence(text: String) {
    val s = state as? ImeState.Unnatural ?: return
    panel = null
    edit(EditPlan.rewrite(s.span, text), text) { outcome(Outcome.Inserted, used = text.takeIf { it != s.result.natural }) }
  }

  /** [onDone] runs once the edit is in, before any switch back to the user's keyboard. */
  private fun edit(plan: EditPlan, shown: String, onDone: () -> Unit) {
    if (!applyVerified(plan, rememberUndo = true)) {
      state = ImeState.ChangedText
      return
    }
    onDone()
    haptic()
    // Return mode: straight back to the user's keyboard, no confirmation screen in between.
    if (settings.afterInsert == LangboardSettings.AfterInsert.RETURN) return switchKeyboard()
    state = ImeState.Inserted(shown)
  }

  private fun haptic() {
    val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    window.window?.decorView?.performHapticFeedback(haptic)
  }

  private fun undo() {
    val plan = undoPlan ?: return
    undoPlan = null
    if (!applyVerified(plan, rememberUndo = false)) {
      state = ImeState.ChangedText
      return
    }
    outcome(Outcome.Undone)
    // The same phrase comes back; it's the same lookup, not a new one.
    refresh()
  }

  /** Dictionary chips for [d], shown before (and beside) the model's answer. Kept while the phrase is the same. */
  private fun lookupQuick(d: Detection) {
    if (quick?.phrase == d.fragment) return
    val phrase = d.fragment
    quick = QuickRow(phrase, chips = null, hasWords = QuickLookup.hasWords(phrase))
    quickJob?.cancel()
    quickJob = lifecycleScope.launch {
      val match = withContext(Dispatchers.IO) { runCatching { OpenDictionary.get(this@LangboardImeService).lookup(phrase) }.getOrNull() }
      val chips = match?.chips.orEmpty().take(QuickLookup.MAIN_CHIPS).map { Candidate(it.zh, null, nuance = DICTIONARY) }
      quick = quick?.takeIf { it.phrase == phrase }?.copy(chips = chips) ?: return@launch
      // Readings and meanings for press-and-hold, from CC-CEDICT when it's installed.
      val detailed = chips.map { withReading(it) }
      quick = quick?.takeIf { it.phrase == phrase }?.copy(chips = detailed) ?: return@launch
    }
  }

  private fun openWords() {
    val phrase = currentDetection()?.fragment ?: return
    if (words?.phrase == phrase && words?.rows != null) return
    words = WordsState(phrase, rows = null)
    wordsJob?.cancel()
    wordsJob = lifecycleScope.launch {
      val found = withContext(Dispatchers.IO) { runCatching { OpenDictionary.get(this@LangboardImeService).words(phrase) }.getOrNull() }
      val rows = found.orEmpty().map { m -> m.headword to m.chips.map { withReading(Candidate(it.zh, null, nuance = DICTIONARY)) } }
      if (words?.phrase == phrase) words = WordsState(phrase, rows)
    }
  }

  /** Adds pinyin and a short English meaning from CC-CEDICT when [c] lacks them and the dictionary is installed. */
  private suspend fun withReading(c: Candidate): Candidate {
    if (c.pinyin != null && c.meaning != null) return c
    val entry = DictionaryManager.get(this).search(c.text, limit = 1, wholePhrase = true)
      ?.firstOrNull { it.entry.simplified == c.text }?.entry ?: return c
    return c.copy(
      pinyin = c.pinyin ?: Pinyin.toToneMarks(entry.pinyin),
      meaning = c.meaning ?: entry.english.take(3).joinToString("; "),
    )
  }

  private fun copy(c: Candidate) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Langboard", c.text))
    haptic()
  }


  // ---------------------------------------------------------------- history

  private fun recordFill(d: Detection, r: FillGapResult?, ctx: ChatContext) = record("fill|${d.fragment}") {
    HistoryEntry(
      createdAt = System.currentTimeMillis(), kind = HistoryKind.Fill, app = currentField?.packageName,
      source = d.fragment, answer = r?.replacement, pinyin = r?.pinyin, meaning = r?.meaning,
      sentence = r?.let { (d.contextBefore + it.replacement + d.tail).trim() }, items = r?.alternatives.orEmpty(),
      register = ctx.register.register.promptName.takeIf { ctx.used }, contextMessages = ctx.messages,
      outcome = if (r == null) Outcome.NoAnswer else Outcome.Shown,
    )
  }

  private fun recordCheck(r: NaturalizeResult) = record("check|${r.original}") {
    HistoryEntry(
      createdAt = System.currentTimeMillis(), kind = HistoryKind.Check, app = currentField?.packageName,
      source = r.original, answer = r.natural, items = r.alternatives.map { Candidate(it, null) },
    )
  }

  private fun recordExplain(r: ExplainResult, ctx: ChatContext) = record("explain|${r.message}") {
    val key = r.key
    HistoryEntry(
      createdAt = System.currentTimeMillis(), kind = HistoryKind.Explain, app = currentField?.packageName,
      source = r.message, answer = key?.text, pinyin = key?.pinyin, meaning = key?.here ?: key?.meaning,
      sentence = r.translation, items = r.terms.filter { it != key }.map { Candidate(it.text, it.pinyin, it.here ?: it.meaning) },
      register = ctx.register.register.promptName, contextMessages = ctx.messages,
    )
  }

  /** Starts a history row for a new answer. The same answer shown again (caret moves) is one row. */
  private fun record(key: String, build: () -> HistoryEntry) {
    if (key == entryKey) return
    entryKey = key
    val e = build()
    entry = e
    saved = false
    entryId = if (settings.keepHistory) historyScope.async { history.add(e) } else null
  }

  private fun outcome(o: Outcome, used: String? = null, sentence: String? = null) {
    val id = entryId ?: return
    historyScope.launch { history.setOutcome(id.await(), o, used, sentence) }
  }

  /** Saving works with history off too: saving is the user asking to keep this one. */
  private fun toggleSave(answer: String?) {
    val e = entry ?: return
    val id = entryId
    saved = !saved
    val now = saved
    // Saving an option other than the first saves that one, with the phrase it answers.
    val picked = answer?.takeIf { it != e.answer }
    when {
      id == null && now -> entryId = historyScope.async {
        history.add(e.copy(saved = true, answer = picked ?: e.answer, pinyin = if (picked != null) null else e.pinyin))
      }
      id == null -> Unit
      !now && !settings.keepHistory -> { entryId = null; historyScope.launch { history.delete(id.await()) } }
      else -> historyScope.launch { history.setSaved(id.await(), now, picked.takeIf { now }) }
    }
    haptic()
  }

  private fun toggleSaveTerm(t: Term) {
    val existing = termIds.remove(t.text)
    if (existing != null) {
      historyScope.launch { history.delete(existing.await()) }
    } else {
      val from = (state as? ImeState.Explain)?.message ?: currentDetection()?.fragment ?: t.text
      val e = HistoryEntry(
        createdAt = System.currentTimeMillis(), kind = HistoryKind.Word, app = currentField?.packageName,
        source = from, answer = t.text, pinyin = t.pinyin, meaning = t.meaning, sentence = t.here, saved = true,
      )
      termIds[t.text] = historyScope.async { history.add(e) }
    }
    savedTerms = termIds.keys.toSet()
    haptic()
  }

  /** Applies [plan] only if the field still matches it. Never edits a different field than it was made for. */
  private fun applyVerified(plan: EditPlan, rememberUndo: Boolean): Boolean {
    val ic = currentInputConnection ?: return false
    val caretBase = if (plan.expectSelected != null) minOf(selStart, selEnd) else selEnd - plan.expectBeforeSuffix!!.length
    expectedCaret = if (caretBase >= 0) caretBase + plan.commit.length else -1
    val ok = VerifiedEditor(ic).apply(plan)
    if (!ok) expectedCaret = -1
    if (ok && rememberUndo) undoPlan = plan.undo()
    return ok
  }

  private fun switchKeyboard() {
    if (switchToPreviousInputMethod()) return
    if (shouldOfferSwitchingToNextInputMethod() && switchToNextInputMethod(false)) return
    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
  }

  private companion object {
    const val TAG = "LangboardIme"
    const val WINDOW_BEFORE = 300
    const val WINDOW_AFTER = 60
    const val CACHE_SIZE = 16
    /** Includes loading the model from storage after a cold start (about 5 s on a Pixel 6a). */
    const val GENERATION_TIMEOUT_MS = 15_000L
    const val CONTEXT_TIMEOUT_MS = 400L
  }
}
