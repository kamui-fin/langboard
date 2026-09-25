# Langboard — product requirements

*Commercial product draft • 24 September 2026 • Native iOS + Android • English ↔ Mandarin v1*

## 1. Product and decision

Langboard helps Mandarin learners say what they actually mean in Chinese while writing a message. When a learner gets stuck and types an English fragment after Chinese context, they switch to Langboard, preview a natural Chinese replacement, and insert it with one tap. A fast offline Chinese–English dictionary provides reliable word lookup beside the model. **The reading and inference experience works on device after initial setup.** No account, cloud inference, message uploads, advertising SDK, or behavioral analytics.

**Implementation decision:** build two native apps, not an Expo shared UI. iOS uses Swift/SwiftUI plus a custom keyboard extension; Android uses Kotlin/Jetpack Compose plus an `InputMethodService`. Share the model contract, evaluation set, dictionary transformation, design rules, and inference core where practical, while keeping each keyboard genuinely native. Android is the first hands-on development and device-feedback loop because you have a Linux machine and Android phone; cofounders build iOS in parallel. Release each platform when its own quality gates pass, without making one wait for the other. Apple custom keyboard · Android IME guide

**Positioning:** “The keyboard for the moments you know what you mean in English but can't quite say it in Chinese.” This is an *output tool* for real conversations. It is not a replacement for a Chinese IME, a generic translator, or a full language course.

## 2. Target user and jobs

- **Primary:** English-speaking learners who can write some Mandarin but regularly stop on colloquial phrasing when texting Chinese-speaking friends, classmates, or family.
- **Secondary:** intermediate learners checking pinyin and a word's meaning while composing, without switching into a browser or dictionary app.
- **Concrete job:** `我本来想去但是 I couldn't be bothered anymore` → preview `懒得再去了` → insert only that phrase. Keep the user's Chinese exactly as written.
- **V1 language:** English UI, simplified Chinese output, Mainland conversational register, English→Chinese contextual Fill Gap, and Chinese↔English dictionary search. Traditional forms appear in dictionary entries when available.
- **Future second audience:** Chinese-speaking learners of English composing an English message who insert a Chinese phrase when stuck. Plan interfaces and localization for both directions now; train, test, and launch the reverse direction separately.
- **Promise to validate:** a correct, context-fitting result inside a real chat app is worth more than raw translation speed or a long explanation.

## 3. MVP experience

### In any supported text field

1. The learner writes a Chinese message with an English fragment near the cursor using their usual keyboard.
2. They switch to Langboard through the system keyboard picker. Langboard displays the detected English span and “Make Chinese.” It does **not** run inference on every keystroke.
3. One compact result appears. Tap it to replace exactly the previewed English span. A short Undo control restores the original if the field has not changed.
4. “Dictionary” searches a word and shows concise Chinese headwords, pinyin, and English meanings. Dictionary results have a distinct label so users do not confuse a lexical hit with a context-aware expression.
5. When the host app supplies insufficient context, the user types a phrase into Langboard's own field and inserts the result at the cursor; Langboard does not guess what to delete.

**Safety invariant:** capture the candidate span, preview it, reread text around the cursor on tap, replace only if it still matches, and otherwise ask the user to retry. Preserve punctuation, adjacent Chinese, emoji, and spacing. No silent whole-message rewrites. On iOS use `UITextDocumentProxy`; on Android use `InputConnection` with appropriate batch edits, context checks, and code-point-safe deletion. Some host apps provide incomplete text or block third-party keyboards; the companion app remains usable. Apple proxy · Android input connection

### Companion app on each platform

- **Try It:** an editable example and a single “Make Chinese” action, with before/after and Copy. It demonstrates value before the user changes system keyboard settings.
- **Dictionary:** one search field for English or Chinese; simplified/traditional, numbered or marked pinyin, short definitions, and tap to copy.
- **Setup:** short, illustrated, platform-specific steps to enable/switch to the keyboard; model download and storage progress; permission explanations; a live practice field.
- **Settings:** model status/size, update/remove offline pack, restore purchase, dictionary credit/version, privacy page, help. No account or social feed.

**Keyboard layout:** an auxiliary tool, deliberately smaller and simpler than a replacement IME. A visible phrase preview; one dominant suggestion; optional second candidate only if it expresses a materially different nuance; Dictionary, Undo, and a system keyboard switch. Do not build a complete Chinese pinyin keyboard for v1. If the OS keeps Langboard open after insertion, make it easy to return to the user's normal keyboard.

## 4. Scope

| Priority | Included | Done when |
| --- | --- | --- |
| P0 | Native companion apps and keyboard integration | Users can enable each keyboard, try a sample, preview a fragment, insert safely, and recover on failure. |
| P0 | Offline CC-CEDICT | Bundled indexed data supports Chinese exact/prefix and English search without model or internet; pinyin and senses display correctly. |
| P0 | Model runtime + pack lifecycle | Each app imports/downloads, verifies, installs, loads, and removes a model; inference executes entirely on device. |
| P0 | Contextual EN→ZH Fill Gap | Output is a minimal Chinese replacement; no English fragment or original Chinese is silently rewritten beyond the previewed span. |
| P0 | Free tier + native purchase | Meaningful free use, one-time upgrade, restore, offline entitlement after purchase, and clear disclosure of each store's purchase scope. |
| Next direction | Chinese→English contextual Fill Gap for Chinese-speaking English learners, with its own quality gates and Chinese UI | Excluded from the initial launch; included in the same language-pair entitlement when ready. |
| Later | Naturalize full sentences, explanations, speech, OCR, flashcards, Japanese, sync, cloud fallback | Excluded from the initial product and marketing promise. |

**CC-CEDICT boundary:** this is a dictionary and deterministic fallback, not a source of guaranteed colloquial sentence completions. Pin and transform a source snapshot to SQLite/FTS and show attribution in the app. We will defer broader training-data rights review while prototyping, but complete it before a commercial launch. CC-CEDICT source

## 5. Native architecture and three workstreams

| Workstream | Owner's deliverables | Integration boundary |
| --- | --- | --- |
| 1 — iOS, cofounders @nicholas devore @George  | SwiftUI app, `UIInputViewController` keyboard, StoreKit 2 purchase/restore, onboarding, model asset install, TestFlight builds and device QA. | Implements the common Fill Gap JSON/fixture contract; invokes the pinned inference core and dictionary DB locally. |
| 2 — Android, @Abhay Srivatsa  | Kotlin/Compose app, `InputMethodService` keyboard UI, Play Billing purchase/restore, onboarding, model asset install, Linux/phone test loop, Play testing builds and device QA. | Implements the same request/result semantics; calls the local model through JNI/native code and uses the same dictionary snapshot. |
| 3 — shared language/data/product, assigned among the team | UX rules and copy, CC-CEDICT build script, model format/version contract, golden examples, teacher-data generation and filtering, QLoRA training, quantization, benchmark harness, launch assets and feedback synthesis. | Publishes versioned model pack + checksum, DB artifact, test fixtures and score report; does not block app scaffolding. |

**Repo layout:** `ios/`, `android/`, `shared/contracts/`, `shared/eval/`, `shared/dictionary/`, `model/`, `marketing/`. One monorepo simplifies review without forcing shared UI. Shared native C/C++ inference is optional where it reduces duplicate work: pin a known llama.cpp revision and expose a small stable wrapper; iOS links an app-extension-safe build, Android packages its own ABI builds. Keep app-specific lifecycle, UI, system text APIs, and billing separate. llama.cpp Apple build

**Contract freeze before parallel implementation:**

```json
{
  "task": "fill",
  "sourceLocale": "en-US",
  "targetLocale": "zh-Hans-CN",
  "register": "casual",
  "before": "我本来想去但是",
  "fragment": "I couldn't be bothered anymore",
  "after": ""
}
```

Response: `{"replacement":"懒得再去了","modelVersion":"..."}`. `before` and `after` are always in the **target** language; `fragment` is in the **source** language. The eventual reverse request uses a Chinese fragment and English surrounding context. Version model packs and evaluation sets by direction rather than assuming that EN→ZH training transfers to ZH→EN. The generation target itself is only the replacement. Both keyboards handle Unicode boundaries, validation, undo, timeouts, and no-model states in platform-native code. Include fixtures for punctuation, selected text, stale cursor, nil context, keyboard switching, and secure fields.

### iOS details

- SwiftUI for the containing app; UIKit keyboard controller with native views or hosted SwiftUI components where extension behavior is stable. Do not embed a React Native bridge in the extension.
- App Group shares installed pack/entitlement state with the extension. iOS “Allow Full Access” may be required for that shared container; explain plainly that it grants a *capability* for network access even though Langboard never sends typed content. Bundle dictionary functionality in the keyboard so it remains useful without Full Access. Secure and phone-pad fields use the system keyboard, and some apps can opt out. Apple Open Access · Apple keyboard limits
- StoreKit 2 handles iOS purchase and restore in the companion app, with entitlement read locally by the extension. The extension never shows a checkout.
- **No MacBook plan:** commit the Xcode project, use a macOS GitHub Actions runner for builds/signing and TestFlight uploads, keep signing credentials in CI secrets, and test each build on a physical iPhone. Initial Apple account/certificate/provisioning setup is still required. Xcode Cloud can be considered after the first working CI build. GitHub macOS runners · signing guide

### Android implementation plan — owner: you (Linux + physical Android phone)

**Goal for your first merge:** enable Langboard in Android keyboard settings, switch into it from Gboard, detect a trailing English phrase in a Chinese message, preview a stub Chinese answer, and replace only that phrase safely. This vertical slice comes before model training, paywall polish, or a full keyboard layout.

#### A. Project and dependencies

- Native Android Studio/Gradle project in `android/`, Kotlin, Jetpack Compose + Material 3 for the containing app, min/target SDK chosen after the first on-device runtime spike, version catalog for pinned dependency versions. Keep Compose and IME code in separate packages/modules (for example `app`, `ime`, `core`, `dictionary`, `inference`); do not create separate installable applications.
- Android `InputMethodService` declared with `android.permission.BIND_INPUT_METHOD`, the IME service intent filter and `@xml/method` metadata; set keyboard-switch support and a meaningful English/Mandarin subtype. `BIND_INPUT_METHOD` is system-granted to a selected IME, not a runtime dialog the app asks the user to accept. The companion app can open the system IME settings and picker; it cannot silently enable itself.
- Companion dependencies: AndroidX Activity/Compose, Navigation Compose, lifecycle ViewModel, DataStore, Room/SQLite with FTS, Coroutines, Play Billing, and optionally Play Asset Delivery after testing its pack constraints. Native inference: NDK/CMake + pinned llama.cpp through JNI with ARM64 first. Add x86_64 only if emulator testing needs it; physical ARM64 is the performance authority. Use exact compatible releases selected at project creation, not untested version literals.
- Permission budget: no accessibility service, notification listener, broad storage, microphone, contacts, overlay, or clipboard polling. Network capability is required only for store billing and model delivery if the chosen transport needs it; the IME's inference code never calls network APIs. No analytics SDK.

#### B. Grammarly-inspired interaction

Grammarly's mobile presentation shows concise in-context writing recommendations and a small interaction cost, including a flow that works with an existing keyboard. Langboard borrows the **quick, visible suggestion → tap to apply** interaction, adapted to the narrow EN→ZH task. It does not clone Grammarly's generic grammar checks, constant text monitoring, appearance, or no-switch integration. Grammarly Mobile

**Default IME surface:** restrained monochrome, no chatbot. Top row shows the exact detected English phrase and a “Make Chinese” button; below it, one large Chinese suggestion with a clear tap target. Secondary controls: “Dictionary,” “Undo,” and system keyboard switch. On first open, detection runs locally and cheaply; full model generation starts only after the user taps. No suggestions in password/OTP/credit-card fields and no reads when the editor is inappropriate.

**States:** `No phrase` → `Phrase found` → `Working` → `Preview ready` → `Inserted`/Undo, plus `No model`, `Unsupported field`, `Changed text`, and `Error`. Each state has a single obvious next action. If the text is not readable, show “Type a phrase” or “Open Langboard” rather than an empty keyboard.

**Manual entry:** a small internal composing strip plus a *minimal* English key grid for A–Z, space, apostrophe, punctuation and backspace appears only when the user requests manual phrase entry or dictionary search. Those keys update Langboard's private, transient buffer, not the host text; “Insert” then commits the selected output to the host. This avoids an unfocused `EditText` inside an IME and avoids building a Gboard competitor. Validate the key grid with TalkBack and landscape layouts.

**Switching:** show a clear globe/keyboard affordance when `shouldOfferSwitchingToNextInputMethod()` is true and call the system switching API. Test the hardware/system navigation keyboard picker and OEM behaviors. Do not pretend to auto-return to Gboard; teach the user one deliberate switch-back step. If beta users find that too disruptive, evaluate a later Android-only integration path, including its permissions and privacy cost, after the safe IME is working. Android IME switching

#### C. Android text-edit contract

- `onStartInput` / `onStartInputView`: inspect `EditorInfo.inputType`, selection state, editor flags, and `currentInputConnection`; reset all ephemeral suggestion/context state when editor or cursor changes. `onFinishInput` cancels active generation and drops transient text. Never persist neighboring host text.
- Read a bounded window around the caret using `getTextBeforeCursor` and `getTextAfterCursor` (or initial surrounding-text APIs where appropriate). Use roughly a sentence of Chinese context, not the entire host document. Detect only a clearly bounded trailing Latin-script fragment when selection is collapsed; strip neither Chinese nor punctuation silently. If multiple spans are plausible, request explicit entry.
- Store a short-lived snapshot of `EditorInfo` identity, selected span, caret context, and intended replacement. Before applying, get a fresh input connection/context and verify the target span still matches. Use a batch edit, code-point-aware deletion where available, then `commitText`; stop safely on null/invalid connections or mismatched text. Do not perform an edit on a different field after app switching. Undo is another verified replacement, not blind global backspace. InputConnection API
- Handle multi-line fields, punctuation, emoji adjacent to the fragment, surrogate pairs, composition from the user's previous IME, suggestions that are empty/too long, cursor moved into the middle of a sentence, and host editors that return partial/nil context. Disable active suggestions in passwords and other sensitive input variations; keep system behavior for numeric/phone fields.
- The IME should render without waiting for the model. Limit parallel inference to one request, cancel superseded work, keep heavy work off the main thread, and release native resources on service teardown/trim-memory. Cache model loading only while memory budgets permit. No inference on every character typed into the host.

#### D. Android UI screens and data

- **Home/Try It:** one realistic mixed-language sample, editable TextField, “Make Chinese,” replacement preview, Copy, and a short “Use this in any app” setup route. The free dictionary should work before the model pack is downloaded.
- **Setup:** enable Langboard in Settings → choose it as an input method → try a sample → optional offline pack download. Show progress and storage requirement; do not place the paywall before the user sees a working example. Explain that Android's warning for enabling a keyboard is a system warning and precisely what this app reads.
- **Dictionary:** one search box, Chinese headword/pinyin/English senses, deterministic exact/prefix English and Chinese search, fast back navigation. Ship a prebuilt indexed CC-CEDICT DB from a pinned snapshot. Do not save query history. Both Activity and IME access the same app-private read-only DB through a repository, without duplicate copies.
- **Settings:** pack status/version/size, remove/redownload, privacy explanation, purchase/restore, dictionary credit, and “Try again” diagnostics. DataStore holds only settings and entitlement metadata. A process kill or IME restart must not erase a purchased unlock or corrupt the pack.

#### E. Model delivery and monetization on Android

- First run ships the dictionary and native runtime; the optional Qwen-derived GGUF pack is downloaded separately. Spike Play Asset Delivery on-demand packs and verify real Play testing behavior, size limits, pack location, update behavior, and mmap/performance. For early local builds, a user-invoked Android document picker (`ACTION_OPEN_DOCUMENT`) can import a developer test pack into app-private storage. The same pack manifest includes version, format, byte count, SHA-256, target ABI/runtime compatibility, and language direction.
- Validate free space; show the download size; stage/verify atomically; keep the previous working pack until replacement succeeds; never load a partially written model. The IME reads an app-private local path, not a network stream. When the app is offline after installation, purchase state and the last working pack remain available.
- Play Billing one-time product `en_zh_offline_pack` (final ID chosen in Play Console); handle `PENDING`, `PURCHASED`, acknowledgment, already-owned, canceled/refunded, and restore through `queryPurchasesAsync`. Expose only a simple entitlement state to the IME; no checkout inside a third-party host field. A local-only entitlement is easier to spoof than server verification; decide deliberately whether that is acceptable for v1, and do not suggest it is fraud-proof. Play purchase lifecycle
- The privacy copy distinguishes *offline writing after setup* from store billing and model download. Neither model request nor dictionary query is sent to Google Play or our server as part of normal inference.

#### F. Linux-first development loop and test plan

1. Install Android Studio/JDK/SDK/NDK/CMake on Linux; create the project; run the app on your physical Android phone over USB with `adb`; inspect `logcat` filtered to Langboard. Test Compose screens in emulator if useful, but judge keyboard switching and inference on the phone.
2. Build the smallest real `InputMethodService` with a system switch key, dummy suggestion, and a practice Activity. Install a debug APK locally; no store round-trip for every native edit. Add Compose to the IME only after confirming lifecycle ownership and composition disposal in the service window; fall back to ordinary Android Views for the IME shell if Compose hosting is unstable. ComposeView interop
3. Implement safe replace and Undo with unit cases for the span detector plus *instrumented* editor tests for actual `InputConnection` behavior. Install on at least your primary phone and a second lower-memory Android device before concluding it works broadly.
4. Add dictionary and pack import, then native runtime smoke test, then production model. Use Gradle release builds and Play internal testing only for store billing/asset-delivery validation; local `adb install` remains the everyday loop.

**Android acceptance matrix:** Gboard→Langboard→Gboard in Messages/WhatsApp/Chrome search/Notes; one-line and multi-line editors; empty, partial and stale surrounding text; mixed Chinese/English punctuation; emoji and CJK near deletion boundary; selection present; password and OTP fields; dark/light, large font, TalkBack, portrait/landscape; low storage; airplane mode after pack install; IME process killed/restarted; model download interrupted; billing pending/refund/restore. Record which OEM/device fails and why. Never “pass” a test merely because the companion Activity works.

**Android performance gates:** IME window appears promptly with no visible model-load pause; dictionary results feel immediate; one active model generation at a time; warm Fill Gap P95 target under ~1.5 seconds on the oldest supported device, subject to semantic quality; log startup latency, model cold/warm latency, peak PSS and OOM/LMK restarts **locally during testing** without shipping content telemetry. Hardware support and quantization are decided after those measurements.

#### G. Your Android milestones and handoff points

- [ ]  **A0, first vertical slice:** native app starts on your phone; IME appears in Settings; enable/switch works; dummy `懒得再去了` replaces a matching English span and Undo restores it.
- [ ]  **A1, useful without model:** Compose setup/Try It/Dictionary screens, CC-CEDICT in app + IME, manual entry mode, nil-context and secure-field behavior, accessibility and light/dark passes.
- [ ]  **A2, offline inference shell:** llama.cpp JNI smoke test in Activity and IME; import developer GGUF; pack validation/removal; lifecycle and memory profile. Decide on Play Asset Delivery only after real Play-track test.
- [ ]  **A3, commercial readiness:** tuned pack + held-out language score, free cap/one-time billing/restore, support/privacy screens, release AAB, internal/closed test and store listing.
- [ ]  **Shared handoff:** cofounders supply the versioned model contract, pinned DB build, gold fixtures, and pack manifest; you publish Android-specific IME failures, device benchmarks, and API compatibility decisions so the iOS and model workstreams can respond.

Android IME documentation · InputConnection · Play asset delivery · Grammarly mobile reference

### Model and dictionary pack

- The first app builds include the inference engine, dictionary, installer, and a small smoke-test fixture. The tuned model arrives after the UI works. Qwen3.5-2B text-only is the candidate, not a promise it fits every keyboard process; quantization and fallback device policy follow benchmarks.
- Keep the base install small. A model is a separate, opt-in download with its size and required free space shown beforehand; verify checksum/signature, stage atomically, and keep one current copy. Consider Apple-hosted asset delivery where compatible with the iOS target; Android can use on-demand Play Asset Delivery. Confirm pack availability, host app/extension access, and storage duplication before committing to one delivery mechanism. If a store asset path cannot serve the extension cleanly, use a versioned CDN pack with a clear one-time download disclosure. Apple hosted assets · Play Asset Delivery
- If the 2B model breaches keyboard memory or latency gates, keep dictionary and Try It functional, benchmark a smaller distilled model, and do not claim in-keyboard AI on affected devices. Platform support list is set by measurements.

## 6. Privacy and honest offline claim

**Marketing wording:** “Your messages are processed on your device. After downloading the language pack, translations and dictionary lookup work offline.” Do not promise that the entire commercial app never uses the internet: initial model delivery, store purchase/restore, and app updates require store or network services. Apple/Google process purchases and may handle download metadata under their own terms; Langboard does not receive message text or build user profiles. We can still honestly say **no typed-message collection, no cloud inference, no account, and no Langboard analytics SDK** if verified in the release build.

- Do not send typed text, dictionary queries, suggestion results, or clipboard content to a server. No third-party trackers, crash-reporting SDK with automatic text capture, ads, or remote logging.
- Process host text transiently. Persist only settings, entitlement, downloaded pack metadata, and optional user-created data if a future feature clearly asks for it. No message history or recent searches in v1.
- Audit network calls in a release build; airplane-mode test every core screen and both keyboards after pack installation. Keep billing and download code isolated from the inference path.
- Explain iOS Full Access honestly during onboarding and give the user a working dictionary-only option without it.

## 7. Native feel and visual specification

- System fonts; black/white/gray palette adapting to light/dark; minimal icon, separators, and generous whitespace. No gradients, gamification, feed, chatbot transcript, or technical controls in the writing flow.
- iOS: native navigation, sheets, system Settings links where possible, SF Symbols, Dynamic Type, VoiceOver. Android: Material 3 components styled monochrome, native system bars, dynamic sizing, TalkBack, and system IME switch affordance.
- One primary action per screen. Practice first, keyboard setup second, purchase after the free experience has shown value. Never present a paywall when the user is in the middle of typing in another app.
- Fast perceived response: keyboard opens immediately; deterministic search feels instant; subtle progress during model work; clear retry instead of blank space. Large touch targets, predictable back behavior, haptic confirmation on a successful insertion.
- Write error text for actual states: “Download the Chinese pack,” “Couldn't read that phrase here—type it above,” “The message changed; try again,” “This device can't run the offline model in the keyboard.” Do not show model jargon to users.

## 8. Monetization hypothesis

**Recommended v1: free core + one-time English–Mandarin Offline Pack purchase.** Recurring subscriptions need a continuing value proposition; this v1 performs local inference and has no per-use server expense. Start with a testable one-time price hypothesis of **US$19.99** (optional introductory **US$14.99**), then test willingness to pay with real users and store conversion data before fixing the price. These are planning numbers, not observed market prices.

| Tier | What the learner gets | Reason |
| --- | --- | --- |
| Free | Full offline CC-CEDICT lookup; keyboard setup and safe insertion; Try It; up to 5 context-aware fills per local day after downloading the model pack. | Real utility and enough hard cases to judge accuracy before buying. A local cap is a soft product limit, not a security boundary. |
| English–Mandarin Offline Pack — one-time | Unlimited EN→ZH Fill Gap on that store platform and offline use once installed; when ZH→EN passes release gates, it is unlocked by the same platform purchase as an optional model download. | One clear language-pair entitlement, without a second charge merely to reverse direction. |
| Future separate packs | Other language pairs or clearly new capabilities, only when useful and tested. | A transparent expansion path without silently converting the Chinese pack into a subscription. |
- Use StoreKit 2 non-consumable IAP on iOS and a Play Billing one-time product on Android; restore inside the companion app. Purchases are **store-specific**. With no account/backend, an iOS purchase does not automatically unlock Android; say so before checkout. Store purchase/restore may require connectivity, but a locally confirmed entitlement remains usable offline. Handle refunds/revocation when the store reports them. Apple StoreKit purchase · Play one-time products
- Check whether a free daily AI cap plus a large model download creates too much friction or delivery cost. Test an alternative limited *number of lifetime trial fills* or a paid-only pack if needed. Do not quietly degrade free answer quality; cap usage, not correctness.
- No ads, sale of reading/typing data, cloud-token upsell, or paywall for the dictionary. Do not introduce subscriptions simply because language-learning apps commonly have them.

## 9. Go-to-market

**Core message:** “You're texting in Chinese and suddenly need to say ‘I'm over it.’ Langboard gives you the phrase that fits *this* conversation.” Show a 6–12-second screen recording of a genuine before/after; lead with an English phrase whose literal translation is wrong. The distinctive proof is *context + native tone + one tap in the chat app*, then privacy/offline as supporting evidence.

1. **Discovery interviews and private beta:** recruit 20–30 Mandarin learners who actually text in Chinese: university Chinese programs/associations, language-exchange partners, tutor communities, and learner forums. Ask for voluntary, anonymized examples or make synthetic equivalents; never upload their live messages. Measure setup completion and how many suggestions they would truly send.
2. **Content loop:** short, faceless screen recordings: “How do you say ‘I couldn't be bothered’ naturally?”, “Why ‘I'm over it’ changes with context,” and a quick on-keyboard demo. Adapt for TikTok/Reels/Shorts; make a useful language tip even without the app. Test multiple phrases and hooks, not generic “AI keyboard” ads.
3. **Search intent:** a small site/FAQ answering specific EN→ZH phrase questions, each with context and one honest app demo. App Store/Google Play listing screenshots should show the keyboard replacing a phrase, not a settings page. Use keywords that reflect the job: Chinese keyboard, Mandarin phrases, say it in Chinese, Chinese texting, Chinese dictionary. Store listing experiments can test screenshots and hooks when volume permits. App Store product pages · Google Play listing experiments
4. **Communities and partnerships:** Chinese instructors and tutors, campus language clubs, language-exchange creators, learner newsletters. Offer demo access and an honest use case; follow each community's posting rules and do not flood generalized tech forums.
5. **Launch sequence:** Android internal/closed testing and iOS TestFlight run in parallel; publish whichever platform first passes the keyboard, model, privacy, and purchase gates, then bring the other to release. Collect feedback through an optional email/contact form that users deliberately open, not silent in-app telemetry.

**Manual launch metrics (no user tracking):** beta setup success (from voluntary sessions), median/p95 task time on test devices, human acceptance rate on held-out phrases, voluntary “would send” votes, refund/support themes, and aggregate store impressions/downloads/purchases from store dashboards. The first growth experiment is whether real learners complete the keyboard-switch action, not whether marketing video views look impressive.

## 10. Release gates and milestones

- [ ]  **M0 — shared contract and design:** 100–200 held-out idiomatic, contextual cases; dictionary snapshot/build pipeline; platform-neutral input/output fixtures; one-page screen/keyboard spec; separate workstream owners. Resolve duplicate/ambiguous fragments in fixtures.
- [ ]  **M1 — parallel native shells:** iOS keyboard and SwiftUI Try It screen; Android IME and Compose Try It screen; keyboard enablement, dummy suggestion, safe insertion/Undo, no-model states. iOS CI builds and distributes through TestFlight without local Mac; Android internal build installs from Linux workflow.
- [ ]  **M2 — dictionary and pack infrastructure:** offline dictionary inside both apps and keyboards; model importer/downloader, checksum, storage checks, atomic install, remove/update, airplane-mode tests. Native runtime linked and smoke-tested in both keyboard processes before investing in full training.
- [ ]  **M3 — model:** teacher examples and critic/human filtering; QLoRA candidate 2B; compare stock and tuned on the untouched gold set; quantize; measure each platform on entry-level target and a newer device. The known hard case must preserve “couldn't be bothered,” not invent tiredness.
- [ ]  **M4 — commercial beta:** free cap, one-time products, restore/refund cases, store-specific unlock explanation, privacy policy, support route, screenshots, 20–30 user sessions. Clear rights/provenance for each dataset/model/dictionary before public commercial release.
- [ ]  **M5 — staged release:** launch each platform independently when ready. Review user-submitted failures and store-provided aggregate data; iterate on the specific issues reported by users.

**Quality gates:** no unverified deletion of host text; correct handling of stale cursor, emoji and Unicode; no inference on secure fields; no network activity for core writing after pack install; repeatable keyboard startup without OS termination; acceptable memory on supported devices; accessible controls; held-out “would send” score exceeding the stock model and dictionary-only baseline. Target warm model P95 under ~1.5 seconds, subject to device tests; never trade a wrong meaning for a faster response. Publish a device compatibility list based on testing.

## 11. Future: Chinese speakers learning English

**Use case:** a Chinese-speaking English learner writes `I was going to tell him, but I [临时怂了]` and receives a context-fitting English replacement such as `chickened out at the last minute`. The app shows only the replacement span, verifies the original fragment before editing, and supports Undo.

- **UX:** when both directions exist, first-run setup asks “I'm learning Chinese” or “I'm learning English.” That choice sets practice examples, keyboard labels, dictionary emphasis, register, and UI language; users can switch modes explicitly. Never infer a learner's direction by analyzing their message history.
- **Language work:** build a separate held-out ZH→EN set that tests implied subjects, aspect and tense, politeness, particles, and natural conversational English. Compare a separate adapter/model with a joint bilingual model for meaning, size, and latency on both keyboards. Literal back-translation is not the quality bar.
- **Delivery and pricing:** the English–Mandarin purchase unlocks both directions on the same store platform once ZH→EN ships. Make the reverse model pack an optional download to avoid unwanted storage. Other *language pairs* may be separate purchases. Do not advertise ZH→EN until it passes language and device gates.
- **Marketing:** validate this audience separately and create Chinese-language demos/listings built around Chinese→English texting problems. Do not reuse the English learner campaign word for word.

## 12. Decisions to validate early

1. Can an on-demand iOS asset be made available to the keyboard extension without keeping a second full-size model copy, and how does Full Access affect it? Test before finalizing delivery.
2. Does Qwen3.5-2B fit and respond reliably inside both keyboard processes on the oldest intended devices? If not, downsize/distill; keep companion-app inference and dictionary usable.
3. Does switching keyboards feel quick enough for a single phrase, or should the auxiliary keyboard also offer a lightweight English entry row? Watch first-use sessions.
4. Is one-time $19.99 a credible price, or does usage support a different free allowance/price? Validate with actual trial-to-purchase behavior and interviews.
5. What is the minimum iOS/Android OS and hardware support? Decide from native runtime, pack delivery, and physical-device tests, not current flagship specifications.

## Sources for engineering and distribution

Apple custom keyboards · Android IME creation · Apple Open Access · Android InputConnection · StoreKit 2 · Play Billing · Apple asset packs · Play Asset Delivery · CC-CEDICT
