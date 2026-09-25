# Langboard Android: prototype status

*25 September 2026 (updated: fill continues the sentence, gold eval set, companion app redesign, FSRS review, keyboard panel polish) · Android workstream (A0, parts of A1, A2 runtime) · not yet committed*

## Summary

Langboard is a layer on top of the keyboard people already use, not a keyboard of its own. You type in Gboard (or anything else); when you get stuck, you tap Android's keyboard switcher, Langboard reads what you wrote and immediately shows the best Chinese for the English part, and one tap puts it in and returns you to Gboard.

- **Works end to end on a Pixel 6a (Android 17):** Gboard → switch → answer → tap → back in Gboard with the English replaced, in native apps. No key grid anywhere.
- **Three jobs, picked automatically:** English in the draft → the best Chinese for it; a Chinese draft → a more natural version; an **empty draft → the latest message someone sent, explained** (translation, the one expression worth knowing, then word by word). No menu.
- **Conversation Context (optional, Accessibility):** when the panel opens, it reads the text on screen line by line, with only whether each line sits at the start, end or full width. That tells received bubbles from sent ones, and the nearby messages set the **register** (casual chat gets 也太颠了, a polite chat gets 真是难以置信). It never watches typing, draws or edits.
- **History, on this phone:** every lookup is recorded with its date, app, answer and outcome (used, looked up, undone, no answer). ☆ saves a lookup or a single word. The app's History tab has filters (All / Used / Saved) and a page for each entry.
- **Review with FSRS-6**, the scheduler Anki uses, ported from the reference implementation and checked against it: every phrase you got becomes a card (English → recall the Chinese, with a 3D card flip), scheduled for just before you'd forget it. Sensible defaults out of the box (10 new a day, 90% retention); after 512 reviews it tunes FSRS to your own memory by itself. Daily limits and every FSRS setting are in Settings → Review → Advanced.
- **Instant dictionary row:** Open Dictionary, cross-checked with CC-CEDICT, answers plain vocabulary (concert → 音乐会) the moment the panel opens. For slang, register-heavy words and phrases it stays silent and leaves the job to the model.
- **On-device model:** Tencent's Hy-MT2 1.8B (1.25-bit) runs through llama.cpp inside the keyboard and does all three jobs. A fill shows its first answer in about 1 s on a Pixel 6a (0.4 s when the prompt is cached), then the model's **10 likeliest options**, ordered by probability, by 2–3.3 s. It's a downloadable Chinese pack (462 MB) with a verified, resumable download and a progress notification; without it the keyboard still works with the dictionary.
- **No more stand-ins:** the Check rulebook, the Explain slang glossary and the Try-it phrasebook are gone.
- **Fill continues your sentence:** the model is asked for the whole sentence in Chinese and its answer starts with your own Chinese before the gap, so what it writes has to fit (我本来想 + 玩轮滑, not the noun 旱冰鞋). English tokens are banned while it writes. On the new 100-case dev set: first answer right 58 (was 55), in the top 3 79 (was 71).
- **Prompts are a file:** `assets/prompts.json` holds every template and decoding setting. `shared/eval/prompt_eval.py` runs it on a laptop with the phone's own engine; a copy pushed to the phone overrides it without a rebuild.
- **Companion app redesigned:** Try it is replaced by **Write**, a chat box for typing and trying the keyboard. The Dictionary shows every sense in full. Settings is laid out as grouped cards.
- **Pinyin over every character** (toggle in Settings), colored by tone (also a toggle). The part a suggestion adds is in the one accent color, #F76F53.

| Area | State |
| --- | --- |
| Keyboard panel: answer, Details, Words; Check; Explain, term page | Working on device (Explain seen in Chrome) |
| History (SQLite), ☆ save, History tab, detail | Working on device; 4 on-device store tests |
| Review: FSRS-6 scheduler, daily limits, auto-tuning optimizer, card flip, Settings → Review | Scheduler and optimizer match py-fsrs 6.3.2 (16 unit tests); review screen and flip seen on device; Settings → Review not yet seen on device, and no card graded on device yet |
| Register from the conversation | Working; phrasebook has casual/formal variants for 13 phrases |
| Verified replace, auto-return, Undo | Working; 17 on-device `InputConnection` tests |
| Conversation Context (Accessibility, read-only, lines with alignment) | Working on device in Chrome; not yet tried in WhatsApp/WeChat |
| Open Dictionary quick matches (bundled, 4.5 MB) | Working; 5 on-device lookup tests |
| CC-CEDICT library (`:cedict`) + in-app download | Working; readings for press-and-hold |
| Selection menu ("Langboard" in the text menu) | Built; not yet tested on device |
| Model runtime (llama.cpp/JNI, `:llama`), beam search, Hy-MT2 fill, check, explain | Working on device (fill and check tested e2e in the app, 26 Sep) |
| Prompt file + host eval (`prompts.json`, `shared/eval/prompt_eval.py`, `shared/eval/hymt_eval`) | Working; 100-case dev + 150-case locked gold set (`shared/eval/README.md`), not yet reviewed by native speakers |
| Pinyin ruby + tone colors, accent highlight | Working on device |
| Companion app: Write, Dictionary, Settings redesign | Checked on device (Write empty state, Dictionary search, Settings); a Write reply not yet seen on device |
| Chinese pack: download, verify, prepare, load, remove | Working on device (real 462 MB download) |
| Billing, free cap, store listing, Play Accessibility declaration | Not started (A3) |

## Changes on 25 September

- **Fill writes the sentence, not a translation of the fragment.** The old prompt translated `roller skate` on its own, so 我本来想 got the noun 旱冰鞋. Now the model continues the user's Chinese and writes on into the text after the gap, so the grammar comes from the model, with no rules (see Prompts).
- **Options are ordered by probability.** The first answer used to stay pinned in row 1, so an option at <1% could sit above one at 36%. Now the list reorders once when all the options are in.
- **No English in fill answers:** tokens with ASCII letters are banned while the model writes.
- **Faster:** all 10 options in 1.9–3.3 s on the Pixel 6a (was 2.5–3.6 s).
- **A real eval:** a 250-case gold set (100 dev, 150 locked) with bins, acceptable answers and prohibited readings, plus a blind rating sheet for native reviewers. Nobody has reviewed it yet.
- **Baselines** (first answer right, dev / locked):

  | Prompt | 1.25-bit (shipped) | 2-bit | Q4 |
  | --- | --- | --- | --- |
  | Old prompt | 55 / 76 | 50 / 72 | 62 / 81 |
  | New prompt | 58 / 80 | 62 / 88 | 77 / 92 |

  **Compression costs more than the prompt gains**, and prohibited readings on the shipped model went up (13 → 18 on locked). Details and the plan are in `../MODEL_PRD.md`.
- **Shared tools moved to the repo root:** evals and `prompt_eval.py` are in `../shared/eval/`, the dictionary builders in `../shared/dictionary/`, the GGUF converter in `../model/`. Only `fetch_llama_cpp.sh` is left in `tools/`.
- **Review is now spaced repetition (FSRS-6),** with daily limits, automatic tuning to the user's own reviews, a 3D card flip and Settings → Review (see Review below). It used to be 20 shuffled cards with no scheduling.
- **Keyboard panel:** a **Back** button (keyboard icon) in the top-right corner of every view; ☆ is an icon left of the phrase; skeleton rows shaped like real options; a plain "Options" header with no status text; "Options to show" setting (3 / 5 / 10); 400 dp panel with more room per row; the list no longer runs under Android's ⌄ and switcher buttons; the cursor's teardrop no longer shows through the pulled-up panel.
- **Engine:** new Latin ban, stop options (`stopText`, `keepUnfinished`) and a batched `score()` that gives the log-probability of each continuation. The app doesn't use `score()` by default (`rescore: 0`), but the eval does.

## Decisions made

| Decision | Choice |
| --- | --- |
| Package / applicationId | `app.langboard` |
| minSdk / targetSdk | 28 / 37 |
| What Langboard is | A layer over the user's own keyboard. **Never a key grid**, in any mode. |
| How it's invoked | Android's keyboard switcher (globe in the navigation bar). Switching is the request: analysis starts on open, no second button. |
| Getting back | A **Back** button (keyboard icon) in the top-right corner of every panel view, always in the same place. It calls `switchToPreviousInputMethod`, so it returns to the exact keyboard and language you came from. |
| One-tap entry through Accessibility | Considered and dropped. Only an Accessibility service can switch keyboards for the user (`SoftKeyboardController.switchToInputMethod`, API 30+), which would break the read-only rule. Tapping the switcher cycles through every enabled keyboard *and language* (seen on device: Gboard English → Gboard 拼音 → Langboard); press and hold opens the list, two taps every time. |
| Proactive suggestions over Gboard | Tried (an Accessibility overlay pill) and dropped for v1: noisy triggers, keyboard-geometry guessing, and harder to defend to Google Play. A possible V3 for users who ask for it. |
| Accessibility use | Read-only Conversation Context: `isAccessibilityTool=false`, in-app prominent disclosure with explicit Continue before Android's settings open, no events acted on, no drawing, no text changes. |
| Context format | The screen's text as lines in reading order, each tagged start / end / wide from its bounds (capped at 2,000 characters nearest the field). The model gets them joined as one string. Locally: latest received message (last start-aligned Chinese line, timestamps skipped) and register (casual / neutral / formal from cue words and emoji). |
| What the panel does | Decided by the draft, never asked: English → fill; Chinese → check; empty → explain the latest received message (needs Conversation Context). |
| History | SQLite `history.db`, on by default, can be turned off and cleared in Settings, excluded from backup and device transfer. Stores the phrase, answer, alternatives, the sentence with the answer in it, app package, register, *how many* messages were read, outcome and saved flag. Never the screen text; an explained message is stored because it's what the user asked about. Saving works even with history off. |
| Screen Lens (reading the whole screen as tappable text) | Not doing it. The empty-draft explain covers the common case. |
| Never read | Password fields; sign-in, verification and payment screens (by short labels on screen); banking, wallet, password-manager and authenticator apps; Android Settings and system UI; Langboard itself. |
| Instant lookups | Open Dictionary v2.0 (CC BY-SA 4.0), bundled. Single words only, and only when the word passes the trust gate below. Never combines word translations. |
| Main panel budget | One job per screen. Every view's top row: ☆ (icon only) at the left, the phrase or section label, then Back at the right. Fill: the English once, top left; the sentence as it will read with option 1 in it, the new part in the accent color; then the options, numbered, each with its probability, and the dictionary's words below them. No Insert button: **tap any option and it goes in and the keyboard switches back.** Press and hold an option to see it in the sentence first. Option 1 sits on a card (no checkmark). Check works the same way: tap the suggestion to use it. Swipe up for more rows and the context. |
| After inserting | Default is now "Return to my keyboard". |
| Save (☆) | An icon to the left of the phrase (it was a labeled button, which crowded out Back): keeps the phrase with the option you picked (not always the first) in History → Saved. Greyed out, not hidden, while there's nothing to save, so the phrase never shifts. |
| Loading and the options header | Skeleton rows shaped like real options (a reading line over a character line, then a share), with a soft highlight sweeping across; the sentence preview gets a two-line skeleton too. The header is plain "Options" in sentence case, with a register chip when Conversation Context is on. No status text or spinner: the skeletons are the only sign of work in progress. |
| Options to show | Setting: 3, 5 or all 10 (default 10). Display only; the model still searches 10 beams. When fewer are shown, their %s are rescaled to add up to 100. |
| Options | Two passes over one prompt that continues the sentence (see Prompts). (1) Greedy (the likeliest token at each step, no penalty, English tokens banned), shown at once. (2) Beam search, 10 beams, over the now-cached prompt; each beam writes on past its answer into the text after the gap (up to punctuation, or until it has written the first two characters of that text). The answer is cut where the text after the gap starts. Answers whose beam wanders off into something else are dropped. **The list is ordered by probability**: the first answer can move down when the others arrive (pinning it was tried; it put <1% answers above 36% ones and scored 2 worse). The % is each option's share of the probability of the options shown (relative, not a confidence score). Beams rank by total log-probability (no length normalization). Tencent's sampling settings (temperature 0.7, top-k 20, top-p 0.6, repetition penalty 1.05) are the `Sampling` defaults; Explain decodes greedily with the 1.05 penalty. |
| After inserting | Setting: "Return to my keyboard" switches back immediately with no confirmation screen, or "Stay open with Undo". |
| Panel height | 400 dp main panel, 580 dp pulled up. Option rows 68 dp. 20 dp side margins. Below the panel it keeps clear of the row where Android draws ⌄ and the keyboard switcher (48 dp in gesture navigation, taller than the navigation-bar inset), which used to cover the last option. |
| What apps make room for | The panel it's settling at, compact or pulled up. The host re-lays out once when a panel opens or closes, never per frame while it animates or is dragged. It used to be the compact panel only, but a pulled-up panel then covered the cursor, and apps draw the cursor's handle in a popup *above* the keyboard, so the teardrop showed through. |
| Review scheduling | FSRS-6, ported line for line from py-fsrs 6.3.2 (`core/Fsrs.kt`), not the FSRS-Kotlin repo: that one isn't a library, and has bugs that change every interval (initial stability capped at 0.1 instead of floored, the difficulty damping misplaced, an exponential forgetting curve instead of FSRS-6's power curve, locale-dependent number parsing). One card per Chinese answer and kind, however often it was looked up. |
| Review defaults | Chosen so nobody has to open settings: 10 new phrases a day (saved first, then looked up but not used, newest first), 200 reviews a day, 90% desired retention, learning steps 1m 10m, relearning 10m, longest gap 100 years, day starts at 4 AM, automatic tuning on. Unchanged settings are stored as unset, so a better default later reaches everyone who never touched it. |
| Tuning FSRS to the user | Automatic: after a review session once there are 512 scored reviews (py-fsrs's minimum), then whenever the log grows by a quarter, or after a month with 100 more. Same method as py-fsrs's optimizer (Adam, cosine learning rate, 5 epochs, mini-batches of 512, first 64 reviews per card, parameters clamped to FSRS's bounds), with exact gradients by forward-mode differentiation instead of PyTorch. The fit is kept only if it predicts the reviews better than the current parameters. |
| Visual style | Monochrome Material 3, light/dark, no dynamic color. One accent, #F76F53, only for what a suggestion adds and the picked option's check. Pinyin tone colors: 1 red, 2 green, 3 blue, 4 purple, neutral gray. |
| Pinyin | The standard approach (as pypinyin): longest-match words from a word table, each other character by its everyday reading. Both tables bundled, built from CC-CEDICT by `shared/dictionary/build_char_readings.py`: 8,011 words whose reading isn't their characters' own (银行 yín háng, 睡觉 shuì jiào; 180 KB) and 10,390 characters (92 KB). In memory, no SQLite, same with or without the dictionary download. `shared/eval/pinyin_eval.py` on 80 hand-labeled chat phrases (403 characters, dense in characters with several readings): characters alone 88%, bundled tables **96%**, full CC-CEDICT 97%, pypinyin 96% (tones compared leniently where a neutral tone is involved). Bidirectional matching was tried and didn't help. Misses left: 还 (hái/huán) and 数 (shǔ/shù) as verbs, 背 bēi, 教 jiāo, 量 liáng. Tone sandhi (一, 不) isn't applied: citation tones, as dictionaries show. |

## Module layout

```
android/
├── app/src/main/
│   ├── assets/opendict.db          # built by shared/dictionary/build_open_dictionary.py
│   └── java/app/langboard/
│       ├── core/                   # platform-neutral, unit-tested
│       │   ├── FillGap.kt              # request/result contract (+ screen text, register) and FillGapEngine
│       │   ├── Conversation.kt         # ScreenText lines, latest incoming message, register, word segmenter
│       │   ├── Explain.kt              # ExplainEngine contract
│       │   ├── FragmentDetector.kt     # English span near the cursor (trailing or "Chinese resumed")
│       │   ├── EditPlan.kt             # verified replace / Undo plans (code-point aware)
│       │   ├── QuickLookup.kt          # dictionary-row rules: single word, base forms, Words breakdown
│       │   ├── AssistPolicy.kt         # where Conversation Context reads nothing
│       │   ├── HyMtPrompts.kt          # answer clean-up; cutting the answer out of a continuation
│       │   ├── ModelEngines.kt         # Fill (greedy + beams, ranked by probability), Check, Explain, behind a TextModel interface
│       │   ├── ModelSpec.kt            # the Chinese pack (pinned URL, checksums) and ModelState
│       │   ├── GgufRepack.kt           # Tencent STQ1_0 → llama.cpp TQ2_0, lossless, on the phone
│       │   ├── Naturalize.kt           # Check contract, sentence finder
│       │   ├── PromptBook.kt           # renders assets/prompts.json (chat, draft, style; fill pre-fills the Chinese before the gap)
│       │   ├── TextDiff.kt, Ruby.kt    # what a suggestion changed; pinyin per character
│       │   ├── Fsrs.kt                 # FSRS-6 scheduler (port of py-fsrs 6.3.2)
│       │   ├── FsrsOptimizer.kt        # fits FSRS's 21 parameters to a review log (forward-mode autodiff)
│       │   ├── ReviewPrefs.kt          # review settings and their defaults; review day, when to re-tune
│       │   └── LangboardSettings.kt
│       ├── ime/                    # the switch-to panel
│       │   ├── LangboardImeService.kt  # state machine; dictionary row and model run in parallel
│       │   ├── ImeState.kt, KeyboardPanel.kt, VerifiedEditor.kt, FieldPolicy.kt
│       ├── assist/
│       │   ├── ConversationContextService.kt  # read-only Accessibility service
│       │   └── ProcessTextActivity.kt         # "Langboard" in the text selection menu
│       ├── dictionary/             # CC-CEDICT download/index/search/breakdown + OpenDictionary.kt
│       ├── history/                # HistoryStore (lookups, review cards, review log), ReviewDeck (what's due, daily limits), ReviewTuner
│       ├── model/                  # ModelManager (state, load once, remove), ModelDownloadWorker
│       └── ui/                     # Write, History, ReviewScreen (flip card), Dictionary, Settings (+ ReviewSettings), Conversation Context disclosure; Components.kt (shared title, pill field, settings groups)
├── cedict/                         # pure Kotlin CC-CEDICT library
├── llama/                          # engine.cpp (prefix cache, greedy, beam search, Latin ban, continuation scoring) + JNI + LlamaModel
├── third_party/llama.cpp           # fetched by tools/fetch_llama_cpp.sh at a pinned commit; gitignored
└── tools/fetch_llama_cpp.sh       # llama.cpp at the pinned commit into third_party/

Shared with other platforms, at the repo root:
../shared/eval/                     # gold sets, prompt_eval.py + hymt_eval/ (this engine on the host), rating_sheet.py, pinyin_eval.py
../shared/dictionary/               # build_open_dictionary.py (→ assets/opendict.db), build_char_readings.py (→ assets/*_pinyin.txt)
../model/convert_hymt_gguf.py       # Tencent's 2-bit / 1.25-bit GGUFs → mainline llama.cpp types
```

## The keyboard panel

Switching here is the request; the draft decides what the panel does.

**A. English in the draft (fill).** In Gboard: `我本来想去但是 I couldn't be bothered anymore` → switch:

```
I couldn't be bothered anymore                      ☆
✦ BEST HERE · CASUAL CHAT
┌──────────────────────────────────────────────────┐
│ 懒得再去了                                         │
│ lǎnde zài qù le                                   │
│ couldn't be bothered to go anymore                │
└──────────────────────────────────────────────────┘

Other ways · 2 ›                          [ Insert ]
```

- **Answer card:** same size while loading (shimmer), answered or empty, so nothing moves. Tap inserts, press and hold opens Details.
- **Action slot:** one fixed-height row that shows dictionary chips while the model works, "Other ways ›" + Insert once it answers, or chips + "Look it up ›" when it has no answer.
- **Details** (swipe up, "Other ways ›" or hold): the answer large, *In your message* (the sentence with the answer in bold), *Other ways to say it* (tap inserts), *Key words* (tap opens the term page), *Dictionary*, and *Context* (register, how many messages, cue words; the exact screen text one tap further). Insert is pinned at the bottom.

**B. Chinese draft (check).** The more natural version large with changes in bold, what you wrote below it, the first change and its reason, then "N changes ›" and Replace.

**C. Empty draft (explain).** Needs Conversation Context.

```
LATEST MESSAGE · CASUAL CHAT                        ☆
你别在那阴阳怪气的          (key expression underlined)
Stop being so passive-aggressive.

┌──────────────────────────────────────────────────┐
│ 阴阳怪气  yīn yáng guài qì                       › │
│ being snide in a roundabout way                   │
└──────────────────────────────────────────────────┘
Word by word ›
```

- **Term page:** the expression very large, pinyin, a usage tag (colloquial, internet slang), the dictionary meaning, *Here* (what it means in this message), a callout for misleading literal readings ("Not “cow” here"), the message with it in bold, ☆ and Copy.
- **Word by word:** the message split with CC-CEDICT (greedy longest match), each word with pinyin and its first two senses; glossary expressions override the dictionary. Tap a word for its term page; ← comes back.
- Without CC-CEDICT, only the glossary expressions are shown, with a note to download the dictionary.

**Other states:** an icon in a soft circle, one title, at most one line (sounds natural, needs selection, password field, unreadable app, text changed, error), and "Try again" where it helps; Back is in the top row like everywhere else. Stay-open mode after inserting shows the inserted text, ☆ and Undo.

**Current fill layout** (the sketch in A predates it): top row ☆ · phrase · Back; the sentence as it will read, option 1 in the accent color; "Options"; the numbered options with pinyin and %; "Dictionary" and "Look up each word ›". Pulled up adds ⌄ after Back, and the context below.

**No own bottom bar:** Android already draws the keyboard switcher and hide button in the navigation bar.

**Safety rules (unchanged):** bounded reads (300 characters before the cursor, 60 after); every edit re-reads the field and must still match; one batch edit, by code points; never edits a different field; Undo is a verified edit. Screen text and the surrounding field text are dropped when the keyboard closes; what history keeps is listed under History below.

**Stale work:** each run of the model and the dictionary is cancelled when the text changes, and a cancelled run can no longer overwrite a newer one (this was the "Something went wrong" bug).

## On-device model

**Model:** [tencent/Hy-MT2-1.8B-1.25Bit-GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF) at commit `9df5c82`, SHA-256 `cc497fe8…a93` (462 MB). Apache 2.0. Pinned in `ModelSpec.CHINESE`.

**Runtime:** llama.cpp master `84e76d8` (24 Sep 2026), built by `:llama` with one CPU backend per ARM feature level (armv8.0 → armv9.2, picked at runtime), KleidiAI, 16 KB page-aligned. The JNI (`llama_jni.cpp`) is purpose-built rather than the `llama.android` example: that example is a multi-turn chat engine (global chat history, 8k context, no mid-token cancel, minSdk 33). Ours runs single stateless completions: 1024-token context, the prompt passed pre-formatted, output streamed as UTF-8 bytes, cancellable during the prompt too, and the **shared start of the previous prompt reused from the KV cache**.

**Tencent's low-bit files don't load in mainline llama.cpp.** They were written by forks whose tensor type ids clash with mainline: the 2-bit file's type 40 is Q2_0C (open PR #19357, SME2-only kernels) where mainline has NVFP4, and the 1.25-bit file's type 42 is STQ1_0 "Sherry" (open PR #22836) where mainline has Q2_0. Both map exactly onto mainline types, so we convert instead of patching llama.cpp:

| Tencent file | Tencent type | Converted to | Why it's exact | Size |
| --- | --- | --- | --- | --- |
| 2-bit | Q2_0C, `(2c−3)·d` | Q2_K | scale 1, min 3, `d_K = 2d`, `dmin = d` | 601 → 715 MB |
| 1.25-bit | STQ1_0, ternary per 256 | TQ2_0 | same values, same fp16 scale | 462 → 607 MB |

Every converted tensor is checked by dequantizing both sides (`model/convert_hymt_gguf.py`). The phone does the 1.25-bit conversion itself after download (`GgufRepack`), and a unit test checks it is byte-identical to the Python tool's output.

**Threads:** 4 is fastest on the Pixel 6a (2: prompt 1.7 s, 3: 1.3–1.4 s, 6: 1.2–1.3 s on the short case vs ~1.0 s at 4).

**First answer (on device, 24 Sep):** 1.2 s for a new phrase without chat, 0.3 s when the prompt is cached; all options by ~3 s. Chat context is now capped at 240 characters, since at ~55 prompt tokens/s the chat is most of a first lookup's wait.

**Fill since 25 Sep (Pixel 6a, `ModelBenchmarkTest`, top-app, v4-continue prompts):** first answer 0.9–1.9 s for a new phrase (0.4 s cached), all 10 options 1.9–3.3 s; with a fresh 15-message chat 3.7 s / 5.6 s (reading the chat is most of it). RSS unchanged (~1.6 GB, 1.3 GB file-backed).

**Before that, beam search alone (10 beams, 1024 context, top-app):** short fill 2.5–3.6 s total (prompt ~1 s, beams 1.1–2.5 s); first fill with a 15-message chat 5.6 s, the next one in the same chat 2.3 s. RSS ~1.6 GB of which 1.3 GB file-backed; anonymous ~300 MB. The beams share the prompt's cache cells (`kv_unified`) and decode together, one batch per step.

**Earlier single-answer benchmark (greedy, 4 threads, top-app):**

| Build | Load (cached) | Memory | Short fill | With chat | Next fill, same chat | Answers |
| --- | --- | --- | --- | --- | --- | --- |
| **1.25-bit → TQ2_0 (shipped)** | 0.3–0.5 s | 800–920 MB RSS, ~750 MB of it file-backed | **1.1–1.5 s** | 3.2–4.0 s | **0.9–1.1 s** | 懒得去了, 疯狂(了), 无法出席, "Don't be sarcastic about it." |
| 2-bit → Q2_K | 1.0 s | ~960 MB | 3.9 s* | 12.7 s* | – | 没心思了, 离谱, 无法出席会议 |
| Q4_K_M (official) | 4.6 s | **2.2 GB** (1 GB anonymous: weights repacked) | 1.6 s* | 5.4 s* | – | 没兴趣了, 疯了, 无法参加会议 |

\* measured in the `foreground` cpuset, before the top-app finding; relative order held. Cold load from storage after a reboot or first install is about 4.5 s. Reading the prompt (~50 tokens/s) is most of the wait; writing the answer runs at ~10 tokens/s and answers are 3–6 tokens.

**Scheduling matters more than the quant.** A background process gets the `foreground` cpuset (CPUs 0–5: four A55s and two A76s), which halves prompt speed. The keyboard, while its panel is showing, is in `top-app` (sched group 3) and gets the X1 cores too (checked with Langboard open over Chrome).

**Prompts (`assets/prompts.json`, rendered by `PromptBook`):** Hy-MT2's chat format, rendered by hand (llama.cpp's built-in formatter mistakes it for HunyuanVL's). Fill asks for the whole draft in Chinese, with the chat as the model card's "background information", and **starts the answer with the user's Chinese before the gap**, so the model carries on the sentence:

```
<user>【背景信息】
聊天记录：
对方：到哪了？菜都上齐了        ← received (start-aligned)
我：马上                        ← sent (end-aligned)

请结合背景信息将以下文本翻译为中文。注意翻译的风格要严格符合【口语化、随意的朋友聊天】…
【待翻译文本】
我今天running late，你们先吃
<assistant>我今天                  ← pre-filled; the model writes on from here
```

Why: asked to translate `roller skate` on its own (the old prompt), the model gives the dictionary noun 旱冰鞋 even after 我本来想; continuing a Chinese sentence it uses its grammar and writes 玩轮滑. No grammar rules involved. Tried and not shipped (numbers in `../MODEL_PRD.md`): a second pass scoring every option with exactly the text after the gap (same first-answer score, 1.5–3 s more on the phone), also scoring each option under the old fragment prompt for meaning (+1, twice the time), dictionary answers as extra options (no change).

The chat is the lines nearest the field, whole lines, up to 240 characters. Details → Context shows exactly what was read and the exact prompt sent. Check asks for a more natural rewrite in the chat's style; when the model's likeliest rewrite is the sentence itself (ignoring punctuation, and particles unless Native), nothing is shown. Explain translates the message with the chat as labeled background (Them / Me).

Clean-up (`HyMtPrompts`): the answer is cut from the continuation at the last point where the rest matches the text after the gap (受不了了 before 了 → 受不了), repeats of the text before are trimmed, and answers are dropped when they copy a chat line or the English back.

**Iterating on prompts:** from the repo root, `python3 shared/eval/prompt_eval.py [--prompts variant.json] [-v] [--task fill|check|explain]` builds `shared/eval/hymt_eval` (the phone's `engine.cpp` for the host) and runs the gold set in `shared/eval/` (dev by default; `--set locked --i-mean-it` for reports; `--out` for the blind rating sheet, see `shared/eval/README.md`). About 30 s for the 100 dev cases on a laptop. It reports first / top 3 / top 10 hits, prohibited readings, English and doubled characters, and hits per bin. To try one on the phone: `adb push variant.json /sdcard/Android/data/app.langboard/files/prompts.json`; it's picked up on the next lookup (logcat `LangboardModel` says which file).

**Pack lifecycle (`ModelManager`, `ModelDownloadWorker`):** one `ModelState` (NotInstalled → Downloading → Verifying → Installed → Loading → Ready, or Error) drives the app card and the keyboard.
- The download is a WorkManager foreground job (`dataSync`). It writes `download.partial`, resumes with `Range`, checks the SHA-256, converts to `.preparing`, checks that SHA-256 too, and only then renames into place. A killed or damaged download can't pass for a model.
- It checks free space first (~1.2 GB during preparation) and retries network errors 5 times with backoff.
- Measured on Wi-Fi: 462 MB downloaded, verified and prepared in about 40 s, then loaded automatically.
- Loading happens once per process, whoever asks first (app launch, keyboard `onCreate`, `onStartInputView`), behind a mutex. The model is memory-mapped, so Android can reclaim its pages without killing the keyboard. There is no keep-alive service.
- Remove cancels the download, waits for any running completion, frees the model and deletes the files.

**Keyboard states:** model loading → "✦ Loading Chinese…" over the same shimmer card, with dictionary chips beside it, and the answer when ready. Model missing → "Chinese model needed" (or "downloading · 64%"), dictionary chips, and "Get the Chinese model ›", which opens the app. Write needs the model and says so when it isn't installed.

## Open Dictionary quick matches

- **Source:** [ahpxex/open-dictionary](https://github.com/ahpxex/open-dictionary) v2.0 `distribution.jsonl.gz` (checksum-pinned), 84,212 English headwords with Chinese short glosses, CC BY-SA 4.0 from Wiktionary. The full release (217 MB SQLite) is far too big; the build keeps only glosses.
- **Output:** `assets/opendict.db`, 4.5 MB (about 1.9 MB in the APK): 50,076 headwords with chips for the Words panel, 57,223 inflections pointing at their headword, and **10,905 words trusted for the instant row**.
- **Trust gate (build time), all must hold:**
  1. A single word.
  2. No core or common sense labeled slang or Internet (drops insane, crazy, chill, based, lit, sick, wild).
  3. No core sense labeled informal or colloquial, and no core sense that is a function word (drops literally, whatever, off).
  4. Not on a short list of register-heavy words the labels miss (awkward, overkill, weird…).
  5. **CC-CEDICT agrees:** the Chinese word must list the English headword as a meaning. This removes wrong and explanatory glosses (nervous → 神经, meeting → 遇见这件事) and recovers the plain word from an explanation (咖啡饮料 → 咖啡).
- **Written the way people type:** 固执的 → 固执, 感到尴尬的 → 尴尬; lone literary characters (顽, 妒) are dropped next to a real word.
- **Examples:** concert → 音乐会, coffee → 咖啡, airport → 机场, weekend → 周末, embarrassed → 尴尬, cancel → 取消, subway → 地铁. Silent: insane, awkward, chill, random, and every phrase.
- **Lookup:** the word as typed, then its base form (meetings → meeting). A known headword without a trusted answer stays silent rather than falling through.
- **Rebuild:** `python3 shared/dictionary/build_open_dictionary.py distribution.jsonl.gz cedict_ts.u8` (from the repo root). The CC-CEDICT date is recorded in the database's `meta` table.

## Conversation Context (Accessibility)

- **When it reads:** only when the Langboard panel opens and a phrase needs an answer; once per field, with a 400 ms limit. Everything is in memory for that field only.
- **What it reads:** the active window's visible, non-editable text in reading order, as one string, capped at the 2,000 characters nearest the end of the screen. The draft field and password fields are excluded.
- **What the user sees:** "✦ Best here · read the screen", and the exact text in the More panel under "Read from the screen".
- **Consent:** Settings → Conversation context → Turn on → disclosure ("Let Langboard read the screen": what, when, on-device only, never sent or saved, never edits, what's skipped) → Continue → Android's Accessibility settings. If the service is enabled without that consent, it disables itself.
- **Measured:** 24 characters read from the two-bubble test chat in Chrome in well under the limit.
- **Debug builds** log only why context came back empty and its length, never the text.

## History

- **When a row is written:** once per answer shown (fill, check, explain, or "no answer"). Moving the caret back and forth over the same phrase doesn't add rows. A dictionary chip inserted before the model answers is recorded too.
- **Outcome updates:** Insert → *Used*, with the exact text used when it was an alternative and the resulting sentence. Undo → *Undone*. Otherwise the row stays *Looked up*.
- **☆ on the keyboard:** saves the current lookup. On a term page it saves that single word as its own row (kind *Word*). With history off, ☆ still creates the row, and un-☆ deletes it again.
- **History tab:** filters All / Used / Saved, grouped by day (Today, Yesterday, weekday dates). Each row shows what was asked in small text, the Chinese large, then status · app and the time. Tapping opens a page with the answer, status in plain words ("You looked it up but didn't insert it."), the sentence, alternatives and context. From there it can be saved or deleted.
- **Review:** see the next section.
- **Settings → History:** Keep history switch, Clear history (with confirmation). The privacy text and the Conversation Context disclosure now say what is kept.
- **Writes** run on a separate IO scope in the keyboard, so cancelling a lookup never loses an outcome update. The app watches a version counter and reloads.

## Review (FSRS)

- **Cards:** every fill with an answer, and every saved word with a meaning; one card per Chinese answer and kind (`Fill|懒得去了`). Front: "How do you say" and the English (or "Which word means" and a word's meaning). Back: the English small, the Chinese large with pinyin, and the sentence it was used in.
- **Session:** cards still in their learning steps (never limited, so nothing stalls halfway), then due reviews up to what's left of today's review limit, most overdue first, then new cards up to what's left of today's new limit. A card graded to come back within 20 minutes stays in the session.
- **Screen:** "3 of 12" with a progress bar. Show answer (or tap the card) flips it in 3D: perspective, a spring, a slight dip halfway; the face swaps when edge-on. Then Again / Hard / Good / Easy, each with when you'd see it again (1m, 6m, 10m, 8d for a new card; Good is the filled one). The next card slides in from the right. The end screen says when the next review is due.
- **History tab card:** "5 due · 3 new", "Done for today · more new phrases tomorrow", or "All caught up · next tomorrow".
- **Settings → Review:** New phrases per day, Reviews per day, and a Remember slider (80–97%) that says what moving it costs. **Advanced:** learning and relearning steps (typed as "1m 10m"), longest gap, when the new day starts, "Tune to my memory" with its status (defaults until 512 reviews, and how many so far; or when it was tuned and from how many), Tune now, Use defaults, and Restore the default settings.
- **Storage (`history.db` schema 3, upgraded in place):** `review_cards` (FSRS state per card: state, step, stability, difficulty, due, last review) and `review_log` (every review: card, time, rating, how long it took, and the card's state before it). The log is what tuning learns from and what daily limits count: new cards by their first review, reviews by Review-state entries since the review day began. Clear history clears both.
- **Checked against py-fsrs 6.3.2:** four review histories, 30 reviews in all (new cards, lapses, same-day repeats, overdue and early reviews), match its state, step, stability, difficulty and due time to 1e-9. The optimizer's loss matches py-fsrs's to 1e-9 on a simulated learner (2,029 reviews, `src/test/resources/fsrs/simulated_reviews.csv`); tuning takes it from 0.2705 (defaults) to 0.2650, against 0.2647 for the learner's true parameters, in under a second.

## Selection menu (basic mode)

Select text anywhere → "Langboard" in the menu → a bottom sheet with the answer and alternatives. It replaces only the English part of the selection and hands the result back to the app, or copies it when the app's text is read-only. Needs no permission. Built, not yet tested on device.

## Companion app

Four tabs: Write, History, Dictionary, Settings. Each tab keeps its scroll position and inputs when you switch away. Screens share one look (`ui/Components.kt`): a bold title, borderless pill-shaped inputs, and rounded grouped cards.

- **Write** (replaces Try it; the samples and the Make Chinese button are gone): a chat thread with a composer pinned above the keyboard. Send runs the same fill as the keyboard; the reply shows the sentence with pinyin and the new part in the accent color, the other options as chips, and Edit / Copy. You can also switch to Langboard while typing in the composer. The setup checklist and the Chinese pack card appear at the top only while needed. The thread lasts for the session and is never saved.
- **Dictionary:** CC-CEDICT download and search-as-you-type in a pill search field. Every sense is shown in full and numbered (it used to cut off at 3 lines). Pinyin is colored by tone. Up to 200 results (was 50), with a note when the limit is hit.
- **History:** see above.
- **Settings**, in grouped cards: How much help (Stuck only / Coach / Native); Keyboard (options to show and after inserting as segmented buttons, keyboard settings link); Chinese text (preview, pinyin and tone-color switches); Conversation context (a switch through the disclosure, skipped apps as a footnote); History (keep switch, Clear history in red); Review (see above); Chinese model; Dictionary (status, Check for updates, Remove); Privacy; About (version, attributions).

## CC-CEDICT library (`:cedict`)

This is a port of [edvardsr/cc-cedict](https://github.com/edvardsr/cc-cedict) (MIT, TypeScript).

- **Same behavior as upstream:**
  - Parsing: `variant of …` and `CL:` extraction, variant and classifier de-duplication, traditional|simplified references.
  - Indexing: each entry sits under both forms, and variants sit under the word they point to, only when the reference has pinyin.
  - Lookup: pinyin keys sorted by code unit, exact or case-insensitive pinyin filter, `mergeCases`, `allowVariants`, de-duplication by traditional form + pinyin with senses merged.
- **Deliberate differences:**
  - "variant of" followed by no Chinese (e.g. "variant of the above") keeps the sense as text. Upstream drops it silently.
  - Duplicate lines are skipped and counted. Upstream aborts the whole build.
  - Chinese characters are matched with explicit Unicode ranges, because the JVM and Android regex engines don't support JavaScript's `\p{Unified_Ideograph}` the same way.
  - A missing variant pinyin is `null` rather than `""`.
  - Upstream's `asObject: false` form is `results.values.flatten()`.
- **Storage is pluggable:** `CedictIndex` has an in-memory implementation (JVM and tests) and a SQLite implementation (Android). Both give identical results, which the on-device tests check.
- **Performance:** the full file (125,101 entries) parses and indexes in about 0.4 s on the JVM.

## Dictionary download and storage

- **Source:** `https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip` (about 4 MB zip, 9.8 MB text).
- **Pipeline:** check free space (80 MB) → download to cache (capped at 64 MB) → stream-parse the zip straight into `files/dictionary/cedict.db.building` → build indexes → write metadata (schema, entry count, source date) → reject anything under 50,000 entries → rename over `cedict.db`.
- **Schema:** `entries` (source order), `headwords` (the cc-cedict index, including variant links), `english_fts` (FTS4, porter stemming), `meta`.
- **Robustness:**
  - The previous dictionary stays usable until the new one is complete, and a failed update keeps it.
  - Leftovers from a killed install are deleted at startup, never loaded. A corrupt or old-schema database is discarded when opened.
  - Download and install can be cancelled.
- **Backups:** the dictionary is excluded from cloud backup and device transfer.
- **Privacy:** the `INTERNET` permission exists only for this download, started from the app. Searches never leave the phone.
- **Use in the keyboard:** pinyin and English meanings for press-and-hold on any Chinese result.
- **Measured on the Pixel 6a:** about 10 s end to end, 29 MB database.

## Tests

| Suite | Where | Count | What it covers |
| --- | --- | --- | --- |
| `:cedict` | JVM | 51 | Parser, index builder, lookup options, pinyin, full-file test (with `CEDICT_U8`) |
| `app` unit | JVM | 150 | FSRS scheduler against py-fsrs, FSRS optimizer (loss against py-fsrs, fitting a simulated learner), review settings (steps, review day, when to re-tune), review deck (one card per answer, order, daily limits), fragment detector, edit plans, phrasebook (incl. register variants), dictionary query rules and schema, field policy, Conversation Context skip rules, quick-lookup rules, latest incoming message, register inference, segmenter, glossary explainer, history codec, Hy-MT2 prompts and answer clean-up, model engines (with a fake model), GGUF repack (values per weight; full file with `HYMT_125_GGUF`) |
| `app` instrumented | Pixel 6a | 59 | History store (4), SQLite CC-CEDICT build and parity, FTS search, install pipeline, real `EditText` edits (17), settings, Open Dictionary asset lookups (trusted words answer, slang and phrases stay silent, inflections, Words), model benchmark (the whole fill pipeline: first-answer and all-options time per pass; skipped unless models are staged, see its KDoc) |

The unit suites pass (201). On device, 56 of 59 pass and the benchmark is skipped. The 2 failures (`VerifiedEditorTest.emojiNextToDeletionBoundary`, `surrogatePairInTail`) are stale expectations from before "Chinese resumed" detection: `no way😅` is now detected with the emoji kept as the tail, and the tests still expect nothing to be detected. The detector's own unit tests pass. Decide which behavior is right, then update one side.

```bash
./gradlew :cedict:test :app:testDebugUnitTest

# On device. Keep the flag, or Gradle uninstalls the app afterwards,
# which disables the keyboard and deletes the downloaded dictionary and settings.
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

## Running it

```bash
./tools/fetch_llama_cpp.sh        # once: llama.cpp at the pinned commit
./gradlew :app:installDebug
adb shell ime enable app.langboard/.ime.LangboardImeService
```

Logs: `adb logcat -s LangboardIme ConversationContext LangboardDict LangboardModel LangboardLlama`. Only timings, lengths and reasons are logged, never text.

## Bugs found and fixed during device testing

1. **Invisible keyboard text in dark mode.** Fixed by wrapping the panel in a `Surface`.
2. **Try It result below the fold.** The screen now scrolls to it.
3. **Normal fields treated as unsupported** after switching keyboards first. Only an identical field now counts as the same field.
4. **"Something went wrong" while typing.** A cancelled model run caught its own cancellation and overwrote the newer result with Error. Fixed in all three background jobs.
5. **Conversation Context read nothing.** It asked the field for its window, which needs a flag the read-only service doesn't declare. It now reads from the active window's root.
6. **Auto-return had a 1.2 s confirmation screen.** "Return to my keyboard" now switches back the moment the text is replaced.
7. **Dictionary chip clipped by the navigation bar,** and the panel felt cramped. The panel is now close to Gboard's height with tighter spacing.
8. **Dictionary definitions cut off** after 3 lines with "…". Now shown in full.
9. **Extra gap above the keyboard in the app:** the screens added the bottom tab bar's height on top of the keyboard's. The app now consumes the Scaffold's insets, so the composer and search results sit right above the keyboard.
10. **The last option ran under Android's ⌄ and keyboard-switcher buttons.** The navigation-bar inset in gesture mode (about 22 dp) is shorter than the row Android draws under a keyboard (48 dp); the panel now leaves room for the whole row.
11. **The cursor's teardrop showed through the pulled-up panel.** Apps only made room for the compact panel, so the pulled-up one covered the cursor while the app still drew its handle, in a popup above the keyboard. Apps now make room for whichever panel is showing.
12. **A failed fill left no trace.** The error was swallowed; the exception type and message (never text) are now logged. That's how a `prompts.json` ahead of `PromptBook` (missing `context_no_chat`) was spotted mid-change.

## Known gaps and limitations

- **Dictionary English ranking:** for "good", rare characters (嘏, 媾) rank above 好. The ranking code in `QueryRules.rank` needs work; the UI isn't the cause.

- **Slang in Explain is hit and miss:** the 1.8B model gets 阴阳怪气 and 摆烂 but mistranslates 笑死我了绷不住了 and 有点东西. The key word is now the longest CC-CEDICT word, which knows no slang.
- **The first lookup in a chat is still slow:** reading the chat into the prompt costs ~2 s before anything shows. Later lookups in the same chat reuse it.
- **Check marks only what it adds.** Deleted characters (我…是 in a rewrite) aren't shown.
- **Latest-message detection is a heuristic:** start-aligned = received. Apps whose bubbles are full-width fall back to the last Chinese line not sent by the user, which can be a title or a quoted message. Sender names in group chats come before their bubble, so they shouldn't win, but a start-aligned label *after* the last message (a Chinese "read" receipt or a reaction line) would. Worth checking in WeChat.
- **History keeps an explained message verbatim** (someone else's words). It's disclosed and can be turned off, but it should be in the Play data-safety answers.

- **Tapping the switcher cycles** through every enabled keyboard and language. With Gboard in English and 拼音, one tap from English lands on 拼音, the next on Langboard, and Back then returns to 拼音, not English. Press and hold opens the list (two taps, exact return). A keyboard can't put itself next in line.
- **Review:** changing FSRS's parameters (tuning, or Use defaults) applies from each card's next review; due dates aren't recomputed. No optimal-retention calculator, no suspend or bury, no leech handling.
- **The Write screen's composer may hide behind the pulled-up panel** if it doesn't scroll into view (seen with the old Try it box); chat apps, whose composer sits just above the keyboard, move up instead. Not re-checked on Write.
- **Chrome web pages lose their scroll position on keyboard switch.** Chrome only shrinks the visible viewport for keyboards and scrolls the field into view when a keyboard first shows. A switch hides one keyboard and shows another, so the page is left scrolled to the top, even after returning to Gboard. Native apps (Messages, WhatsApp, WeChat, Discord, Instagram) resize normally and are unaffected. No keyboard-side fix found yet.
- **Not yet tested in WhatsApp or WeChat,** where custom text views are most likely to differ; only Chrome and Langboard's own screens were exercised. adb can't type Chinese, so Messages wasn't testable end to end.
- **Model answers still miss slang and idioms:** first-answer hits on dev are 8/23 for slang and 8/16 for idioms (ghosted → 捉弄, simp → 傻瓜, pull my leg → 拉我的腿, double booked → 重复预订). The prompt can't fix what the model doesn't know; that's the tuning plan in `../MODEL_PRD.md`.
- **The shipped 1.25-bit model is the weak link:** the same prompt on Q4 gets 77/100 first on dev vs 58, and 2-bit gets 62. With the new prompt, prohibited readings first rose to 18/150 on locked (from 13), mostly literal ones like 拉我的腿 and 睡一觉.
- **Latin letters are banned in fill answers,** so AA制, PUA, KTV, emo can't be suggested. Worth revisiting once the model is tuned.
- **Tencent's kernels aren't used.** We run their weights through mainline TQ2_0. Their STQ1_0 NEON kernel (PR #22836) would keep the file at 462 MB and should be faster. Worth re-checking when either PR merges; the converter can then go.
- **Chat context costs ~2 s the first time** it goes into a prompt in a field; later lookups reuse it. A shorter context or a smaller prompt would help.
- **Memory:** ~800–900 MB RSS while loaded, mostly file-backed. Not yet tested on a 4 GB phone, or for what happens when Android trims the keyboard.
- **Download notifications:** asked for when the user taps Download (Android 13+). Progress with % and MB, a "Preparing" phase, and a "Chinese model ready" (or failed) notification at the end; tapping opens the app.
- **Debug native libraries aren't stripped** ("Unable to strip"); release packaging still needs checking.
- **Dictionary quality is conservative:** some answers are dated or oddly chosen (nervous → 提心吊胆, jealous → 眼红, hungry → 饥 / 饿), and some plain words stay silent (exam). Silence is the intended failure.
- **Play review:** the Accessibility declaration, review video and privacy policy aren't written. Approval isn't automatic.
- **Clean-up:** an old `com.example.langboard` build is on the test phone; `ic_keyboard_hide.xml`, `ic_language.xml` and `ic_undo.xml` are unused; Settings uses SharedPreferences, not DataStore.

## Next steps

1. Test in WhatsApp, WeChat and Instagram with a real conversation (context read, latest message, register, replace, return), and on a lower-memory device.
2. Try the 2-bit pack with the new prompt on the phone (62 vs 58 on dev, about 1 GB, but it was 2–3× slower in the old benchmark), and re-check when Tencent's low-bit kernels reach mainline.
3. Model quality: have two native speakers review the gold set in `shared/eval/` (`reviewed` is false everywhere) and fill in the rating sheet; then the tuning plan in `../MODEL_PRD.md`. Mark deletions in Check.
4. Decide whether Chrome's scroll behavior needs a workaround (for example, nudging the caret after returning).
5. Write the Play Accessibility declaration and record the review video (disclosure → consent → settings → switch → answer → tap).
6. Commit on a branch and open a PR.
