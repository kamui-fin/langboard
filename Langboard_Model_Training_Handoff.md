# Langboard Local Model, Training & Personalization Handoff

*Agent-ready technical design • September 2026 • EN ↔ ZH • Mobile-first / offline-first*

## 0. Executive decision summary

> **Key point:** North-star: Langboard must be a trustworthy output assistant, not merely a translator. Learners often cannot judge whether the model is right, so the first suggestion is effectively authoritative. Optimize for semantic fidelity, native sendability, restraint, and consistency before feature breadth.

- Production should use ONE downloadable local bilingual model, not a translation model plus a second rewriting model.
- The model-choice decision is intentionally unresolved until a controlled bakeoff: Qwen3.5-2B vs Hy-MT2-1.8B vs TranslateGemma 4B.
- Qwen3-1.7B has been dropped from the primary benchmark; it adds little information relative to Qwen3.5-2B.
- TranslateGemma 4B is a benchmark / quality-ceiling candidate. It may be too large for the desired hot-path mobile experience, but it tells us how much quality the ~2B constraint costs.
- Train EN→ZH and ZH→EN together. English naturalization is as important as Chinese naturalization.
- Naturalize includes the “judge” behavior. There is no separate Judge feature: if text is already natural, output UNCHANGED; otherwise make the smallest useful repair.
- Use supervised fine-tuning (SFT/LoRA) first. Do not add continued pretraining (CPT), DPO, GRPO, or cloud inference unless a measured failure justifies it.
- Personalization is inference-time and local: explicit preferences + high-confidence user examples + retrieval. Do not train per-user LoRAs on-device.
- Do not build a real-time slang-trend/search subsystem. Prioritize durable modern colloquial language. If the user needs external research, provide an explicit button that opens their browser/search engine without sending conversation context from Langboard.

## 1. Product contract

Langboard helps a learner express what they personally want to say in genuinely native, context-appropriate target language while preserving their own production and voice. The app should feel closer to a “native output layer” than a chatbot.

| Task | Input | Required behavior |
| --- | --- | --- |
| Fill Gap EN→ZH | Chinese sentence containing an English fragment | Return only the minimal native Chinese replacement span. Do not rewrite surrounding Chinese. |
| Fill Gap ZH→EN | English sentence containing a Chinese fragment | Return only the minimal native English replacement span. Preserve tone and surrounding English. |
| Naturalize ZH | User-written Chinese | If already natural for requested style: UNCHANGED. Else smallest native-like repair. |
| Naturalize EN | User-written English | If already natural for requested style: UNCHANGED. Else smallest native-like repair. |
| Style control | Meaning + target text/context + requested style | Change realization, not meaning. Support casual friend, casual neutral, work chat, professional, concise/expressive, soft/direct, low/medium slang. |
| Personalization | Task + style profile + a few relevant saved examples | Prefer the user’s recurring lexical/syntactic habits when they remain natural and correct. |

```text
Fill Gap example

<task>fill</task>
<source>en</source>
<target>zh-Hans</target>
<style>casual_friend</style>
<before>这个也太</before>
<fragment>insane</fragment>
<after>了吧</after>

OUTPUT:
离谱
```

```text
Naturalize example

<task>naturalize</task>
<target>zh-Hans</target>
<style>casual_friend</style>
<text>我刚到家</text>

OUTPUT:
UNCHANGED
```

## 2. Trust and quality principles

- Top-pick trust: assume most learners will choose the first suggestion. Never optimize only for fluency or “sounds impressive.”
- Meaning fidelity outranks naturalness. A natural sentence with changed intent is a critical failure.
- Restraint outranks recall in Naturalize. False-positive rewrites erode trust quickly.
- Minimal patching: Fill Gap must not duplicate particles or syntax already outside the blank (e.g. existing 也太 + target 太离谱了 + existing 了).
- Multiple valid expressions may exist. Training/evaluation must not pretend Mandarin or English has one canonical phrase.
- Do not teach model mistakes back to itself. “User accepted suggestion” is preference evidence, not ground truth.
- Human evaluation question to optimize: “Would a native speaker actually send this in this context?”

> **Key point:** Critical failure examples: invented emotion/cause; changed speaker stance; literal idiom mistranslation; wrong social register; rewriting already-natural text; changing context outside the requested span.

## 3. Model shortlist and hypotheses

| Model | Role in benchmark | Expected strengths | Main risk / open question |
| --- | --- | --- | --- |
| Qwen3.5-2B | Primary general-model candidate | General bilingual instruction following; rewriting; style control; Naturalize; likely best foundation for one multitask model. | Can it match a translation specialist on EN↔ZH fidelity and idiomatic Fill Gap after tuning? Mobile runtime for the newer architecture must be benchmarked. |
| Hy-MT2-1.8B | Primary translation-specialist candidate | Strong translation prior; contextual MT; terminology/format control; small official low-bit variants; attractive mobile economics. | Can it learn monolingual Naturalize, UNCHANGED restraint, style control, and personalization without losing translation strength? |
| TranslateGemma 4B | Larger translation-specialist quality ceiling | Strong translation-specialized 4B baseline; useful to estimate how much quality is lost by the ~2B mobile target. | Larger model/download/RAM; may be unsuitable as final keyboard hot-path model even if quality wins. |

Do not decide the base model from model cards. Raw-model behavior and identically fine-tuned behavior are separate questions. The winner is the best total Langboard model after the same high-quality pilot data, not the model with the best generic benchmark.

## 4. Mandatory model bakeoff

1. Run raw Qwen3.5-2B, Hy-MT2-1.8B, and TranslateGemma 4B on the frozen Langboard evaluation set using the best task-appropriate prompt for each model.
2. Fine-tune each model on the same pilot data. Use equivalent training budgets and architecture-appropriate LoRA configurations; do not force identical module names when architectures differ.
3. Re-run the exact same frozen evaluation. Keep per-task scores separate; do not hide tradeoffs behind one aggregate score.
4. Only after quality ranking, benchmark quantized variants on target Android hardware for load time, TTFT, total latency, peak RAM, thermal behavior, crash rate, and model-file size.

| Evaluation slice | What it measures | Primary metric |
| --- | --- | --- |
| EN→ZH Fill Gap — straightforward | Basic bilingual transfer + exact patching | Semantic correctness + span fit |
| EN→ZH Fill Gap — idiomatic/pragmatic | Nonliteral mapping and social meaning | Native sendability + fidelity |
| ZH→EN Fill Gap | Reverse-direction natural English realization | Native sendability + fidelity |
| ZH Naturalize | Chinese humanization/minimal repair | Edit precision + sendability |
| EN Naturalize | English humanization/minimal repair | Edit precision + sendability |
| UNCHANGED | Restraint / implicit naturalness judgment | False-positive edit rate, precision/recall |
| Casual vs work style | Register control | Blind native register match |
| Personalization | Following explicit preferences + examples | Preference-match without fidelity loss |

> **Key point:** Selection principle: prefer the Pareto winner on quality + mobile feasibility. TranslateGemma can win the quality-ceiling role without being the shipping model. Hy-MT can win if it learns the general tasks without degrading. Qwen can win if it closes the translation gap while retaining stronger Naturalize/style behavior.

## 5. Frozen evaluation set

Build the evaluation set BEFORE training. Never use its exact cases, close paraphrases, or same expression/context combinations in training.

| Slice | Target count | Coverage |
| --- | --- | --- |
| EN→ZH Fill Gap | 100 | Literal, phrasal verbs, idioms, pragmatic stance, ambiguous fragment resolved by Chinese context |
| ZH→EN Fill Gap | 80 | Natural contemporary English, idioms, register, code-switch repair |
| ZH Naturalize | 60 | Translationese, textbook Chinese, overly formal Chinese, minimal grammar repair |
| EN Naturalize | 50 | Textbook English, Chinese-English interference, assistantese, unnecessary formality |
| UNCHANGED | 50 | Perfectly natural casual/work sentences that should not be touched |
| Style/register | 40 | Same meaning across casual friend, casual neutral, work/professional, direct/soft |
| Personalization probes | 20 | Same task with/without explicit style prefs and 2–3 prior user examples |

Total target: ~400 cases. If reviewer cost is limiting, start with 300 but keep at least 50 UNCHANGED cases and a meaningful idiom/pragmatics slice.

- Leakage grouping: group by English/Chinese expression family, target paraphrase family, source scenario, and generated template.
- Each hard item may list 1–3 acceptable answers and explicit prohibited interpretations.
- Use blind native-speaker evaluation; reviewers must not know which model produced which output.
- For difficult cases: two native raters; third adjudicator on meaningful disagreement.

## 6. Data strategy: authentic human targets first

> **Key point:** Core data principle: the target distribution should be genuinely human whenever possible. Do not ask an LLM to invent “natural human language” and then treat its output as ground truth.

The highest-leverage construction is inverse synthetic training: start from authentic or commissioned human text, then use a teacher model to create controlled degraded inputs. Train the small model to recover the untouched human target.

```text
AUTHENTIC HUMAN TARGET
        ↓
Teacher creates controlled degradation:
- translationese / literal mapping
- textbook stiffness
- assistantese / fake empathy
- unnecessary formality
- excessive explicit subjects/connectors
- over-verbosity
- learner-like phrasing
        ↓
INPUT = degraded variant
TARGET = original human text
```

- For Fill Gap, start from an authentic human target sentence, remove a meaningful span, and replace it with a source-language fragment expressing the same intent.
- For Naturalize, generate multiple kinds of “bad but meaning-preserving” inputs from the same authentic target.
- For UNCHANGED, feed authentic human text unchanged and train the model to return UNCHANGED.
- For style control, create contrastive targets for the same semantic intent only when each target is genuinely natural for that style.
- For English and Chinese, use symmetric task design; do not treat English as a secondary afterthought.

## 7. Pilot training dataset (first experiment)

Do not start with 30k–100k samples. First prove the training contract with ~1,000 exceptionally good examples.

| Slice | Approx. count | Construction |
| --- | --- | --- |
| EN→ZH Fill Gap | 250 | Human Chinese target → remove native span → insert equivalent English fragment |
| ZH→EN Fill Gap | 200 | Human English target → remove native span → insert equivalent Chinese fragment |
| ZH Naturalize | 175 | Human Chinese target + controlled stiff/literal/AI-ish degraded input |
| EN Naturalize | 125 | Human English target + controlled textbook/assistantese/degraded input |
| UNCHANGED | 150 | Authentic human sentences mapped to UNCHANGED inside Naturalize task |
| Style/register contrasts | 100 | Same intent rendered naturally in clearly different target styles |

Scale only after evaluation shows where the model fails. Likely next stages are 2.5k → 5k–10k → 20k+, but no volume target is binding until learning curves justify it.

## 8. Naturalize design

- Naturalize includes the naturalness judgment. There is no separate Judge task in the UI.
- Contract: already natural → UNCHANGED; otherwise minimal repair only.
- Train many clean native examples. UNCHANGED ratio must be determined by ablation, not guessed.
- Pilot UNCHANGED ablation: 10%, 25%, 40%, and optionally 50% within the Naturalize slice. Measure false-positive edits vs missed necessary edits.
- Prefer span-level/minimal edits. Avoid “rewrite the whole sentence to sound better.”
- Meaning preservation is mandatory. Naturalize must not strengthen emotion, politeness, certainty, or intent unless style control explicitly requests it.

Key product metric: false-positive editing of already-natural text. The model should be conservative enough that users can trust “Naturalize” without worrying it will replace their voice with generic AI prose.

## 9. Style and register controls

Use linguistic preferences, not demographic stereotypes. Do not directly condition on age or gender in V1. Ask for the language behavior the user wants.

| Control | V1 values / behavior |
| --- | --- |
| Region / variety | Mainland Simplified (default); Taiwan/Traditional can be a later or advanced profile |
| Register | casual_friend, casual_neutral, work_chat, professional |
| Slang | low, medium; avoid a “trend-chasing/high slang” default |
| Length | concise, balanced, expressive |
| Interpersonal tone | soft/friendly ↔ direct/concise |

Use compact structured tags/fields in the pilot rather than a long prose system prompt. Do not add new tokenizer special tokens unless necessary; stable textual tags are simpler for multi-runtime deployment.

## 10. Slang and modernity policy

- Do NOT make staying current with ephemeral slang a core Langboard responsibility. It is a maintenance treadmill and can easily make output dated or embarrassing.
- Do train durable conversational language and internet-origin expressions that have become ordinary speech/writing.
- Use English slang/idiom resources primarily to improve semantic understanding and evaluation, not to force trendy output.
- No real-time search or cloud slang lookup in V1.
- If the user is unsatisfied, provide an explicit “Search web” / “Ask Google” action that opens the browser with a query. Langboard itself should not upload surrounding conversation context.
- Current/trendy expressions can enter a user’s personal example library through “Learn from this message” or custom corrections without becoming global defaults.

## 11. Personalization architecture

Personalization should learn HOW the user prefers to express meanings, not infer who the user is. The system uses three layers:

1. Explicit profile — region/variant, default register, slang tolerance, concision, directness/softness.
2. Learned compact style profile — periodically summarized only from repeated high-confidence behavior; e.g. “prefers 啥 over 什么 in friend chat,” “short clauses,” “rarely uses emojis.”
3. Retrieved examples — 2–3 highly relevant user-approved examples injected into the current prompt.

```text
Example inference context

PROFILE
- mainland simplified
- casual_friend
- slang: medium
- concise
- slightly direct

LEARNED PREFERENCES
- prefers 啥 over 什么 in casual chat
- prefers 挺 over 很 when natural
- avoids formal connectives

RELEVANT EXAMPLES
1. 太离谱了吧
2. 懒得搞了

CURRENT TASK
...
```

- V1 retrieval baseline: start with local SQLite + lexical/BM25-style retrieval if it is enough. Add a tiny embedding model only if semantic retrieval materially improves results.
- Do not dump dozens of examples into context; 2–3 good examples are likely more useful and cheaper.
- Do not perform on-device gradient training / per-user LoRA in V1.

## 12. Personalization UX and feedback taxonomy

The keyboard must keep the primary result simple. Put feedback/personalization actions behind a compact “More” surface rather than cluttering the first suggestion.

```text
Best here
阴阳怪气

[Insert]

Alternatives
话里带刺   softer

More:
- Explain
- Save phrase
- Learn from this message
- Wrong / not how I'd say it
- Custom answer
```

| Signal | Interpretation | Use |
| --- | --- | --- |
| Inserted top suggestion | Weak: may indicate trust/compliance, not correctness | Local preference statistics only; never global ground truth |
| Selected alternative | Medium: explicit preference between viable outputs | Local style preference; candidate preference pair |
| Wrong → meaning wrong / unnatural / wrong tone | Strong negative signal | Local negative constraint; optional consented global review queue |
| Manual edit of suggestion | Strong signal | Save before/after pair locally; candidate training/eval example after review |
| Custom answer | Very strong user preference | High-priority personal example; global use only with consent and native/expert verification |
| Learn from this message | Explicit positive example | Save sentence/style locally for retrieval |

Wrong / not me should distinguish model error from personal taste. Suggested reasons: Meaning is wrong; Sounds unnatural; Wrong tone; Too formal; Too slangy; Not how I talk.

## 13. External research UX

Do not integrate Google/Search as an automatic backend. If the user wants external verification, open their browser with a search query. This preserves privacy boundaries and keeps Langboard offline-first.

```text
Not satisfied?
[Search web]

After returning:
[Add custom answer]
```

The user can research with a native speaker, dictionary, search engine, or another tool and then return to save the preferred expression. This is particularly valuable because learners cannot always identify whether the model or their own intuition is wrong.

## 14. Training sequence

1. Baseline raw models on frozen eval.
2. Pilot LoRA/SFT on ~1k examples for all three model candidates.
3. Evaluate blindly. Select the best base/model-size tradeoff.
4. Run UNCHANGED ratio ablation on the selected model.
5. Scale SFT data only in categories with measured failures.
6. Only if high-quality SFT plateaus on a repeatable failure class, build DPO preference pairs from human targets vs actual model failures.
7. Do not add GRPO in the current plan.
8. Do not add generic conversational CPT in the current plan. Test it only if direct SFT cannot remove assistantese/humanization problems without harming instruction following.

> **Key point:** Engineering rule: sophistication is not progress. Every new training stage must have a pre-declared failure it is intended to fix and an ablation showing it improves that failure without damaging translation, restraint, or mobile behavior.

## 15. LoRA / fine-tuning experiment protocol

Do not hard-code one “optimal” configuration from prior humanizer projects. Run a small sweep. Recommended pilot search space:

| Parameter | Pilot values |
| --- | --- |
| LoRA rank | 8, 16, 32 |
| Alpha | r, 2r |
| Target modules | attention projections vs all-linear where supported |
| Learning rate | roughly 8e-5, 1.5e-4, 3e-4 |
| Epochs | 1–3 with checkpoint evaluation / early stopping |
| Sequence length | Keep short; most Langboard examples fit comfortably in 256–512 tokens |

Use the same prompt/task schema in training and inference. Train only the desired completion/replacement tokens when the framework supports completion-only loss.

## 16. DPO policy

DPO is optional and later. First create a strong SFT baseline. Add preference training only if repeated, measurable residual problems remain.

- Chosen: authentic human target or native-reviewed preferred output.
- Rejected type A: actual SFT model failure (AI-ish, translationese, too formal, wrong span).
- Rejected type B: intentionally over-slangy/over-casual output if the model overcorrects toward “human = slang.”
- Monitor diversity and restraint. Preference training that makes outputs formulaic is a regression.
- Do not use user acceptance alone as Chosen ground truth.

## 17. Data provenance and licensing rules

> **Key point:** Not legal advice. Treat provenance as an engineering release gate. A repository’s code license does not automatically grant rights to the underlying scraped or copyrighted text.

- Every training row must carry source/provenance metadata and a terms/license status.
- Prefer commissioned/consented human text, clearly licensed commercial corpora, and internally created human targets.
- Treat social-media scraped corpora as research/reference unless underlying rights are clearly cleared.
- XiangJinYu Humanize Dataset: research/method reference; current dataset license is non-commercial (CC BY-NC 4.0).
- FCGEC: research reference; authors restrict data to non-commercial academic research.
- YACLC/MuCGEC and similar learner-correction sets often have non-commercial/research restrictions; verify each source before any commercial training use.
- Humanizer model weights can be useful for methods research even when their underlying training data cannot be reused.
- If using proprietary APIs to generate degraded inputs, verify current provider terms before using outputs in a model-training pipeline.

Maintain a data_manifest.csv with source ID, upstream URL, original author/corpus, license/terms snapshot, allowed use, derived transformations, and whether the row may enter a commercial checkpoint.

## 18. Native human review rubric

| Dimension | Reviewer question |
| --- | --- |
| Fidelity | Does it preserve the user’s actual meaning, stance, certainty, emotion, conditions, and entities? |
| Sendability | Would you genuinely send this in the specified context without editing it first? |
| Naturalness | Does it sound native rather than translated, textbooky, or AI-generated? |
| Register | Does it match friend / neutral / work / professional context? |
| Minimality | Did it change only what was necessary? |
| Modernity | Does it sound current and normal without forcing transient meme slang? |
| Boundary fit | For Fill Gap, can the output be inserted exactly between before/after with no duplication or grammar break? |

Use 1–5 ratings where useful, but also collect categorical critical-error flags. A model can be fluent and still unacceptable because it changes intent.

## 19. Runtime / Android deployment contract

- Model is a downloadable “Chinese/English Intelligence Pack,” not bundled into the APK.
- Primary runtime candidate: llama.cpp / GGUF on Android; final runtime can change if model architecture/device benchmarks demand it.
- Store in app-private persistent storage, not cache.
- Download with resumable worker, .partial file, versioned manifest, size and SHA-256 verification before activation.
- Model lifecycle states: NotInstalled → Downloading → Verifying → Installed → Loading → Ready → Error.
- Load/warm asynchronously on app launch and IME initialization. Do not keep a forever-running service solely to pin the model in RAM.
- Keyboard shows a stable “Starting language engine…” skeleton while loading; deterministic dictionary/fallback features remain usable.
- Do not expose model choice to ordinary users unless needed for debugging/advanced settings.

```text
interface LanguageEngine {
    suspend fun load(path: String)
    suspend fun unload()
    fun generate(request: LangboardRequest): Flow<String>
}
```

## 20. What is explicitly OUT of scope for V1

- Automatic web/Google/search calls from the keyboard.
- Cloud inference as the default path.
- Real-time slang-trend tracking.
- Per-user LoRA or on-device backpropagation.
- Separate Judge classifier/product surface.
- Two production LLMs (one translation + one rewriting) unless the single-model experiment clearly fails.
- GRPO / RL against AI detectors.
- Uncontrolled continued pretraining on huge chat corpora.
- Phrase Lego / drag-and-drop grammar UI.
- Training on blindly scraped private/public platform text without rights review.

## 21. Agent workstreams and concrete deliverables

| Workstream | Agent mission | Deliverable |
| --- | --- | --- |
| A. Eval | Build frozen 300–400 case bilingual benchmark and review rubric | gold_eval_v1.jsonl + annotation guide |
| B. Data | Create authentic-target inverse-synthetic pilot | pilot_sft_v1.jsonl (~1k) + generation scripts |
| C. Provenance | Audit source rights / terms row-by-row | data_manifest.csv + red/yellow/green notes |
| D. Model baseline | Run raw Qwen3.5-2B / Hy-MT2-1.8B / TranslateGemma 4B | raw_bakeoff_report.md + outputs |
| E. Training | LoRA each candidate with controlled sweep | adapters/checkpoints + reproducible configs |
| F. Human eval | Blind native review of candidate outputs | ratings.csv + disagreement/adjudication report |
| G. Mobile runtime | Benchmark quantized finalists on Android | load/TTFT/RAM/thermal/model-size report |
| H. Personalization | Implement profile + saved examples + retrieval ablation | p13n prototype + profile schema + retrieval metrics |
| I. Feedback UX | Implement Wrong / Not me / Custom / Learn from this message | interaction spec + local schema + consent flow |

## 22. Decision gates

| Decision | Gate |
| --- | --- |
| Base model | Choose after identical SFT + frozen blind eval + Android quant benchmark. |
| Add more SFT data | Only when per-category error analysis shows coverage/data deficiency. |
| Add DPO | Only if SFT plateaus on repeatable style/assistantese/over-formality failures and preference training improves them without narrowing diversity. |
| Add CPT | Only if direct SFT cannot learn human conversational priors and CPT ablation improves sendability without hurting instruction/translation. |
| Add embedding retrieval | Only if BM25/simple retrieval fails to retrieve useful personal examples and semantic retrieval produces a measurable personalization lift. |
| Consider TranslateGemma shipping | Only if its quality gain over ~2B models is large enough to justify the model size, RAM, and latency cost. |
| Cloud fallback | Not V1. Revisit only if local model has an irreducible quality gap and an explicit privacy-preserving opt-in UX is justified. |

## 23. Immediate next sequence

1. Freeze prompt/task schema and output contracts.
2. Author the first 100 hard evaluation cases manually to calibrate the rubric.
3. Expand to the frozen 300–400 case eval before model training.
4. Acquire/commission the first authentic human target texts with clean provenance in both English and Chinese.
5. Generate controlled degraded inputs and Fill Gap variants; manually audit the first 200 before scaling to 1k.
6. Run raw three-model bakeoff.
7. Run pilot LoRA/SFT on all three candidates.
8. Blind native evaluation + error taxonomy.
9. Select a base model or run one targeted second experiment if the winner is ambiguous.
10. Only then scale data, optimize quantization, and integrate the winning checkpoint into Android IME.

## 24. Key reference resources

- Tencent Hy-MT2: https://github.com/Tencent-Hunyuan/Hy-MT2
- Hy-MT2-1.8B: https://huggingface.co/tencent/Hy-MT2-1.8B
- Qwen3.5-2B (official): https://huggingface.co/Qwen/Qwen3.5-2B
- TranslateGemma collection / 4B model: https://huggingface.co/google/translategemma-4b-it
- LessThanThreeAI humanlike chat: https://huggingface.co/LessThanThreeAI/Qwen3.8-27B-Humanlike-Chat-GGUF
- Gemma humanizer: https://huggingface.co/jialinyyzz/humanizer-gemma-4-e4b
- XiangJinYu humanize SFT: https://huggingface.co/XiangJinYu/Qwen3.5-9B-Humanize-SFT
- XiangJinYu humanize dataset (non-commercial): https://huggingface.co/datasets/XiangJinYu/Qwen3.5-9B-Humanize-Dataset
- XiangJinYu DPO derivative: https://huggingface.co/911-copy/Qwen3.5-9B-Humanize-DPO-Round2
- Gen-Z semantic normalization resource: https://huggingface.co/datasets/Sankar-2910/genz-to-english
- llama.cpp Android docs: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- FCGEC (research-only data): https://github.com/xlxwalex/FCGEC
- YACLC: https://github.com/blcuicall/YACLC
- MuCGEC: https://github.com/HillZhang1999/MuCGEC

These references are starting points for agents. Verify current revisions, model cards, licenses, and upstream text rights at execution time; do not copy prior report numbers blindly.

## 25. Final operating principle for all agents

> **Key point:** The goal is not to build the most sophisticated training pipeline. The goal is to produce the most trustworthy bilingual output on a phone. Prefer authentic human targets, measurable blind evaluation, the smallest training method that works, and a simple local personalization loop. Every added component must earn its complexity with data.

## Appendix A. Product & systems context for agents

*Added 25 Sep 2026 from `gpt/handoff_gaps.md` and `gpt/DIRECTION.md`. Read this before making any design choice the sections above leave open.*

### A.1 What Langboard is

- **Core promise:** “help me say what *I* wanted to say,” not “write something good for me.” Langboard helps the learner produce language; it does not produce language on their behalf. No whole-reply generation, no canned replies, no one-tap impersonation.
- **Default experience:** a companion keyboard (IME), not a replacement for QWERTY/Pinyin. The user types in Gboard/Apple Keyboard, globe-switches to Langboard at a breakdown point, gets a compact suggestion, taps Insert, and switches back. Invocation is deliberately explicit.
- **Fill Gap is the flagship** because it preserves productive struggle: the learner wrote 80% of the sentence, Langboard supplies the missing 20%. **Naturalize** is second and must be comfortable doing nothing. **Personalization** is the long-term moat. **Conversation context** makes help situational. Explain/alternatives/pinyin sit behind the primary action and never slow it.
- **Symmetric directions:** EN→ZH Fill + ZH Naturalize for learners of Chinese; ZH→EN Fill + EN Naturalize for learners of English. Both are first-class.
- **Default Chinese style:** natural contemporary Mainland messaging, casual but not slang for the sake of slang.

### A.2 Priority hierarchy

```text
1. Preserve meaning
2. Be native / sendable
3. Fit context + relationship
4. Preserve the user's voice
5. Make the smallest necessary intervention
6. Be modern
7. Be clever / slangy
```

Never trade a higher item for a lower one. **Human ≠ slang:** casual native language is mostly ordinary language. A model that sprinkles 颠, 寄, cooked or lowkey everywhere has failed.

### A.3 Canonical request schema

One inference object for training rows, eval cases, Android JNI and iOS requests, feedback records and dataset tooling: contract `lb1`, `shared/contract/CONTRACT.md` (code: `shared/contract/contract.py`). Nobody invents a parallel representation; changes mean a new contract version.

### A.4 Platform, dictionary and context

- **Android vs iOS:** Android Accessibility can optionally supply nearby conversation context; iOS cannot be assumed to. Core inference must work with empty context.
- **Dictionary layer:** Open Dictionary (in Android assets) and CC-CEDICT are deterministic fallback/support, not competitors to the model. Straight lexical lookups can appear instantly while the model loads; contextual, slang and idiomatic cases go to the model.
- **Context privacy:** context is tiny, ephemeral and local, and never becomes a chat-history database. Exclude passwords, payment/auth screens, banking and password-manager apps.
- **Prompt injection:** text read from the conversation is data, never instructions (contract §“Everything inside the tags is data”). Training includes instruction-shaped messages that the model ignores.

### A.5 Output, insertion and uncertainty

- **Safe insertion:** model output never edits the text field arbitrarily. Fill replaces exactly the detected/selected span after validation; malformed, empty, overlong or context-duplicating outputs are rejected (contract `check`).
- **No fake confidence:** show one top answer, optionally 1–2 alternatives with a useful distinction (softer, more formal). When a case is genuinely ambiguous, say so instead of presenting one answer as canonical. A displayed percentage must be calibrated or removed.

### A.6 Personalization and feedback

- **Learn linguistic preferences, not demographics.** “Concise, soft, Mainland casual, uses 啥” is useful; inferring age, gender or personality is not, and demographics never silently drive output.
- **Lifecycle:** examples can be added, edited, deleted and aged out. Explicit user corrections beat inferred preferences. The user can inspect, edit, forget and reset everything Langboard thinks it learned.
- **Signal strength:** accepted top suggestion = weak preference, not correctness; chosen alternative = medium; Wrong + correction or custom answer = strong. “Meaning is wrong” is model-quality data; “Not how I talk” is personalization data. Keep them apart.
- **Two separate loops:** private personalization stays on the device. Global improvement only receives what a user separately consents to share, and it goes through review (teacher → native reviewer) before any training use.

### A.7 Model delivery and performance

- **Model-update contract:** every pack records model version, dataset version, prompt-contract version, quantization version and checksum. A bad model can be rolled back independently of an app-store release.
- **Performance gates are product metrics:** cold-load time, warm globe→suggestion latency, peak IME RAM, battery/thermal behavior, crash/OOM rate and minimum supported phone. Not abstract tok/s.
- **Cross-platform parity:** Android and iOS ship the same semantic checkpoint, even if runtime or quantization differs.

### A.8 Data and evaluation

- **Teacher ≠ ground truth.** A teacher may create degraded inputs, labels and candidate variants; it is never the unquestioned source of what natives say. Authentic/commissioned human targets and native review are the anchor.
- **Reviewer population matches the variety:** contemporary Mainland native speakers for Chinese; native or fluent speakers familiar with current casual messaging for English. “Chinese speaker” alone is too broad.
- **Severity taxonomy:** meaning inversion, invented intent or emotion, and wrong stance count far more than slightly awkward wording. Report critical failures separately; never let averages hide them (see `shared/eval/ANNOTATION.md`).
