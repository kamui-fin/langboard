# Companion app: alignment with `artifacts/APP_ALIGNMENT.md`

*25 September 2026 · branch `model-contract-lb1` · tracks the UI/UX rework of the companion app*

The promise everything hangs on: **Langboard helps you say what you actually mean in Chinese, then helps you remember it for next time.** The keyboard solves the moment; the app turns those moments into lasting ability.

## Guardrails (from the direction docs, not negotiable)

- Never a key grid. The Expression Lab uses a normal text field (the user's own keyboard).
- **Minimal input, calm screens.** No typing in Review; one idea per block; no stacked badges or redundant hints (user feedback, 25 Sep).
- Nothing is shown that we can't back with data we actually have. No invented style profile, no fake progress, no pattern claimed from too few examples.
- Langboard never watches typing outside the panel, so "used it without help" metrics **can't** be measured. Only honest proxies (needed again or not, FSRS memory).
- No streaks, XP, gems, leaderboards, word-of-the-day, generic lessons.

Legend: `[ ]` todo · `[x]` done, not seen on device · `[v]` done and verified on the Pixel 6a · `[-]` deferred (reason given)

## 0. Foundation
- [x] Theme follows `artifacts/color_choice.md` (replaces the monochrome, no-dynamic-color decision): chrome uses Material You by default, Settings → Color → "Match my device / Langboard"; fallback palette porcelain #F7F7F3 / ink #171A18; `LocalAccent` is Langboard jade (#347A68, dark #79C3AE), never wallpaper-tinted. Tone 2 moved from green to orange so it can't read as jade. Seen on device: dynamic + Langboard, light only
- [v] Fuller type scale (`Type.kt`), system font
- [v] Shared building blocks (`Components.kt`): section label, panel, stat, empty state, page bar, expression row, strength meter; formatting helpers in `Format.kt`
- [v] `history/Insights.kt`: pure derivations from history + cards + review log (moments, expressions, recurring, week, can-say, strength, learning value, register/app mix, recall rate). 9 JVM tests in `InsightsTest`

## 1. Navigation
- [v] Four tabs: Home, Review, Memory, You (`LangboardApp.kt`), with a small page stack that survives process death
- [v] Expression Lab (was Write) opens from Home; back returns to Home
- [v] Settings opens from You, with back
- [v] Review session is a page over the tabs (no bottom bar); closing returns to whichever tab started it (checked from Home and from Review)
- [v] Expression and Moment pages open from any list; the bottom bar hides on pages

## 2. Home: "what should I learn from my real life?"
- [v] Greeting by time of day, no subtitle
- [x] Setup card (keyboard) and Chinese pack card, shown only while incomplete (code moved from Write; not re-seen since this phone is set up)
- [v] Review hero: "N things worth reviewing · about M min" → Review now. Ink card in light mode, raised dark surface in dark mode (a white slab glared)
- [x] Caught-up state ("Next review in 3h" / "More from your chats tomorrow"): not reachable on this phone's data yet
- [v] "What are you trying to say?" row → Expression Lab
- [v] Keeps coming up: expressions needed on more than one occasion (lookups within 30 min count once), with "2×"
- [v] This week: asked / sent / new expressions, plus up to 6 new expressions as chips
- [x] You can say this now (appears once a card is held ≥ 7 days **and** reviewed at least twice; empty on this phone, correctly)
- [x] Three-step "how it works" for a brand-new user (no data on device to show it)
- [-] Recurring gaps clustered by communicative function ("soft disagreement"): needs a model classifier; not in lb1
- [-] Weekly push recap: needs a notification schedule; Home "This week" covers the content

## 3. Review: "practice what you struggled to say"
- [v] Card front is the gap in your own sentence (`他又迟到了， ＿＿＿`), the English you meant, and the app ("In WhatsApp") when known; falls back to "How do you say…" when there's no sentence around the answer
- [v] No typing: flip and grade only (a typed-answer field was built, tried, and removed at the user's request)
- [v] Grading writes to the review log (12 → 13 on device)
- [v] New cards ordered by learning value (saved, needed again, picked another option, not sent, undone; long sentences down) and non-Chinese answers dropped. `ReviewDeckTest` +1
- [v] Review tab: Today panel + the English of what's coming up (never the Chinese)
- [-] Naturalize / recognition / contextual choice / register-switch card types: need model-generated distractors and register variants

## 4. Memory: "everything you've learned through real communication"
- [v] Search across English, Chinese, sentence, meaning and options (checked: "done" finds four expressions)
- [v] Expressions / Moments / Saved; expression rows are just the Chinese and what you meant
- [v] Expression page: pinyin hero, what you were trying to say, your sentence with the phrase in the accent, up to 3 other ways to say it, progress (memory strength, next practice, how often needed/sent), moments (duplicates from reopening the panel collapsed), save, forget (with confirmation)
- [v] Moments: grouped by day; a moment's page links to its expression
- [v] Lab ☆ saves a reply into Memory → Saved

## 5. You: "your language and how it's evolving"
- [v] Moments / expressions / can-say counts, one memory bar with a one-line breakdown
- [v] Things you can say now (hidden until there are some)
- [v] Where you get stuck (apps); register mix only after 5+ chats read (one chat isn't a pattern)
- [v] Reviews done and recall rate (after 10 reviews); mentions tuning only once FSRS is fitted
- [v] Settings entry and a one-line privacy note
- [ ] Rename "How you write" to "Where you use it". It shows the register of the *chats* Langboard read and the apps you got stuck in; neither says how you write. The real "How you write" is My Style (§5c)
- [-] Style controls (slang / directness / length): moved to §5c

## 5b. Onboarding and subscription (`artifacts/onboarding_monetization.md`)
- [v] Gate (`Onboarding.kt` → `LangboardRoot`): no entitlement → intro + paywall; entitled but not set up → setup; else the app. A lapsed subscriber goes straight to the paywall
- [v] 1 Say what you mean (tap-to-fill demo, jade replacement), 2 Make it yours (Casual / Neutral / Work → `defaultRegister`), 3 Learn from what you say (loop, privacy line, "How privacy works")
- [v] 4 Paywall (`Paywall.kt`): annual preselected, renewal price by the button, restore. Prices from the RevenueCat offering; planned prices shown when the build has no key
- [v] 5 Install: model download starts on entry (no notification prompt), keyboard enable; 6 Try it once: real fill in a real field, "You're ready" on success, skippable
- [x] `billing/Subscription.kt`: RevenueCat (10.23.2), anonymous user, entitlement `pro`, listener + refresh on resume, device-ID collection off. Not tested against a real store: no API key yet
- [x] Keyboard and selection menu locked without an entitlement (`ImeState.Locked`, reads the app's cached answer; the keyboard stays offline). Not seen on device
- [x] Settings: Color, "Sound, unless the chat says otherwise", Subscription (manage, restore, device-local Memory note), privacy text mentions RevenueCat
- [x] Launcher icon: jade tile, `[ ✦ ]` mark, monochrome layer
- [ ] Before release: `revenuecat.apiKey` in local.properties, `pro` entitlement + `langboard_pro_annual` / `langboard_pro_monthly` with a 7-day trial in Play and RevenueCat, `TERMS_URL` / `PRIVACY_URL` in `Paywall.kt`
- [-] Funnel analytics: the app promises no analytics; RevenueCat covers paywall → trial → paid
- [-] Contextual Conversation Context / notification prompts: not built yet (still in Settings / at download)

## 5c. Personalization ("My Style")

**Where it stands (25 Sep):** Langboard does not learn from how you write yet. Two things shape a suggestion: the chat's register, guessed from cue words on screen, and the Casual / Neutral / Work default from onboarding, used when the chat gives no cue. Picking another option, saving, correcting and not sending are all recorded, but only Review and Memory read them. The keyboard never does. The app still sends Hy-MT2's own prompt (`prompts.json`), which has one style slot and three fixed Chinese phrases. The lb1 contract already has `style` (register, slang, verbosity, directness) and `personalization` (≤ 6 profile lines, ≤ 3 examples), but no model is trained on it and no app code builds it.

Three layers (handoff §11), with one addition: a description written in the user's own words.

| Layer | Source | Becomes | Wins over |
| --- | --- | --- | --- |
| 1. What you told us | Onboarding choice, a few controls, and an optional free-text "How do you want to sound?" (description and/or example messages) | lb1 `style` fields + up to 6 `profile` lines ("prefer 哈哈哈 over 笑死", "avoid 老铁") | everything |
| 2. Learned from you | Repeated medium/strong signals: picked another option, corrected, "Not how I talk" | Proposed `profile` lines ("You usually pick 挺 over 很. Add to your style?" [Add] [Ignore]). Never added silently | the model's defaults |
| 3. Your examples | Saved and sent sentences | ≤ 3 retrieved into `examples` per request (lexical first, embeddings only if that fails) | n/a |

Rules: the style guide is compiled only when the user taps Update, never on each request. The prompt gets the compact lb1 form (`<style>` + `<profile>`), never the user's paragraph. Descriptions are turned into how to speak, never who the user is: "like people my age text" becomes short, contemporary, casual, not an age. The user sees what was understood, can remove any line, and can reset.

Order of work (each step depends on the one before):
1. [ ] **Model:** the lb1 SFT pilot includes rows with non-default `style` and `profile`/`examples`, plus the 20 personalization probes (handoff §6). Until a probe shows the model follows "prefer X over Y" without losing meaning, the UI has nothing honest to promise
2. [ ] **App → lb1:** build the lb1 request in the IME (register → `style.register`, `defaultRegister` as fallback) and render the lb1 user turn instead of `prompts.json` once the trained model ships
3. [ ] **Explicit controls:** You → My Style: register default (already stored), slang low/medium, concise/balanced/expressive, soft/balanced/direct. Plain chips, not sliders
4. [ ] **Free-text description → style guide:** a text field ("Describe it, or paste a few messages that sound like you"), [Update my style], then "Langboard understands this as:" with chips the user can remove and a full-guide view. **Decision needed: where the compiler runs.** The on-device model is a translation model and won't reliably turn English prose into lb1 fields. Options: (a) a cloud LLM, opt-in, only the description is sent, only when the user taps Update. The keyboard stays offline, but the privacy line "Nothing is uploaded" needs an exception. (b) Add a `profile` task to the one on-device model (costs SFT data and eval). (c) No compiler: the user picks chips and types "prefer / avoid" lines by hand. Recommended: (a) with (c) as the offline fallback
5. [ ] **Learned layer:** propose profile lines from repeated evidence (≥ 3 independent occasions, same preference direction); the keyboard's "More" gets "Not how I talk" (personalization) separate from "Wrong meaning" (quality)
6. [ ] **Retrieved examples:** top ≤ 3 saved/sent sentences by lexical overlap with the draft
7. [ ] **You screen:** replace the register stat with "Your style" (what you told us) and "Learned from you" (accepted lines, with the evidence count). Show nothing learned until something is

## 5d. Naming

Users can't tell an Expression from a Moment. They are: **Expression**, a Chinese phrase Langboard gave you (all the times you needed it merged into one); **Moment**, a single time you asked. Proposal:
- [ ] Memory tabs: **Phrases · History · Saved**. Plain words beat coined ones here; "History" is only a problem if it's the product's headline, and Memory already is
- [ ] One muted line under the tabs, per tab: "Chinese you've needed, once per phrase" / "Every time you asked, newest first"
- [ ] You stats and Home copy follow ("12 phrases from 40 lookups")
- [ ] Keep "Expression Lab" only if user testing shows it's understood; "Try a sentence" is the plain fallback

## 6. Verification
- [v] `./gradlew :app:testDebugUnitTest`: 161 tests, 0 failures
- [v] Installed on the Pixel 6a; Home, Review tab, session (front, back, grade), Memory (list, search, Saved), expression page, You, Settings, Lab (real model reply, ☆ save) checked in light mode
- [v] Dark mode: Home and Memory
- [v] Keyboard panel opened over the Lab with the new theme; Gboard restored afterwards

## Known issues / follow-ups
- Session counter stays at "0 of 13" after a Good on a new card: the card comes back within 20 min (FSRS learning step), so it isn't "done" yet. That's how it worked before; it may read as broken. Consider counting "seen" instead.
- Pinyin typed in place of English (`woxiangqu → 五香曲`) becomes an expression. The model got it wrong; the app can't tell. Maybe skip sources that are one run of pinyin syllables.
- Memory segmented control and the Moment rows (dot + status + app + time + star) are still the busiest parts of the app.
- The tab switched unexpectedly once right after a reinstall and `am start`; it didn't reproduce in five later tries.

## Log
- 25 Sep: read the doc and the app. Device has 72 real history entries, 10 cards, 12 reviews.
- 25 Sep: Insights + tests, four-tab shell, Home, Review front, Memory, expression page, You, Lab. Built, tested, installed, walked through on device.
- 25 Sep: user asked for no review input and a calmer UI. Removed the typed answer and "Tap to flip"; rows lost pinyin, meters and dates; Home lost subtitles and the faux input; You lost filler facts and the legend; Lab lost its triple explanation; 5 soft chips instead of 10 outlined; dark hero toned down.
