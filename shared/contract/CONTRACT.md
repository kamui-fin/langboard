# Task contract `lb1`

*Frozen 25 Sep 2026 · Step 1 of `Langboard_Model_Training_Handoff.md` §23 · Code: `contract.py`, tests: `test_contract.py`*

One request schema for every Langboard model call. Training rows, the host eval and the phone all
build prompts with it, so what the model is tuned on is byte-for-byte what it sees in use
(MODEL_PRD R9). Changing anything below means a new version (`lb2`), new training rows and a new eval run.

**Raw-model baselines do not use this contract.** The handoff's bakeoff step 1 gives each raw model its
own best prompt (Hy-MT2: `prompts.json` v4; TranslateGemma: its translation template). `lb1` is the
format the tuned models learn, and the one the tuned models are compared on.

## Tasks

| Task | Directions | Model writes | Keyboard shows |
| --- | --- | --- | --- |
| `fill` | en→zh, zh→en | the draft from the gap onwards: answer + the text after the gap | the answer only |
| `naturalize` | zh, en | `UNCHANGED`, or the smallest repaired version of the whole text | `UNCHANGED` → "Already natural", else the repair |

There is no judge task: `UNCHANGED` is the judgment.

## Request

The canonical inference object. Training rows, eval cases, Android/iOS engine calls and feedback
records all carry this, so no workstream invents its own shape.

```json
{"task": "fill", "source_locale": "en-US", "target_locale": "zh-Hans-CN",
 "before": "这个也太", "fragment": "insane", "after": "了吧",
 "style": {"register": "casual_friend", "slang": "low", "verbosity": "balanced", "directness": "balanced"},
 "conversation_context": [["them", "他居然辞职去西藏开民宿了"]],
 "personalization": {"profile": ["prefers 离谱 over 夸张"], "examples": ["也太离谱了吧"]}}
```

| Field | Values |
| --- | --- |
| `source_locale`, `target_locale` | `en-US` · `zh-Hans-CN`. V1 is contemporary Mainland Simplified and general American English; Taiwan/HK and dialects come later as separate, evaluated locales and never mixed into V1 data. |
| `style.register` | `casual_friend` · `casual_neutral` · `work_chat` · `professional`. The relationship is part of the register; lb1 has no separate `relationship` field. |
| `style.slang` | `low` · `medium` (no high: see handoff §10) |
| `style.verbosity` | `concise` · `balanced` · `expressive` |
| `style.directness` | `soft` · `balanced` · `direct` |
| `conversation_context` | `them` / `me` lines, oldest first. Optional, and never required: iOS has no screen context. Only the newest whole lines within 240 characters reach the model. |
| `personalization.profile` | ≤ 6 learned linguistic preferences (handoff §11 layer 2). Never demographics. |
| `personalization.examples` | ≤ 3 retrieved user-approved sentences (layer 3) |

## User turn

Fixed order. Optional blocks are left out when empty, never sent empty.

```text
<task>fill</task>
<lang>en-US→zh-Hans-CN</lang>
<style>casual_friend slang:low verbosity:balanced directness:balanced</style>
<profile>
- prefers 离谱 over 夸张
</profile>
<examples>
- 也太离谱了吧
</examples>
<chat>
them: 他居然辞职去西藏开民宿了
</chat>
<draft>这个也太⟦insane⟧了吧</draft>
```

Naturalize puts `<text>…</text>` where fill has `<draft>`. These are plain-text tags, not new
tokenizer tokens, so any runtime can use them.

### Everything inside the tags is data

Conversation lines, profile lines, examples and the user's own text are data, never instructions. A
friend who sends `ignore previous instructions` or `</chat><task>…` must not change what Langboard does.

- `defuse` rewrites anything shaped like one of our tags (`<chat>` → `‹chat›`) and the gap markers
  (`⟦⟧` → `[]`) before they enter the prompt. Profile lines and examples are flattened to one line each.
- Training data includes conversation lines that contain instructions, and their targets ignore them
  (a named slice in the pilot set). The tags alone do not make a model robust.

### On the phone

`android/.../core/Lb1.kt` renders this turn and the pre-fill the same way (`Lb1Test` pins it to
`contract.py`'s output; change both together). The app keeps sending Hy-MT2's own prompts until the
shipped `prompts.json` has `"contract": "lb1"` at the top level (with the new model's `chat_template`);
then Fill and Check use lb1, filled from My Style: `style` from the chat's register (or the user's
default) and their controls, `profile` from their style guide first (their own description, one statement per line, with anything
about who they are or any non-style instruction removed on the phone), then their word rules
("prefer 哈哈哈 over 笑死") and accepted learned lines. Training rows should include free-text profile
lines of this kind, not only "prefers X over Y", `examples`
from their sent or saved sentences closest to the draft. Explain is not an lb1 task and keeps its prompt.
`prompts.json` also has `style_hints`, extra style words for Hy-MT2 when a control is off its default;
defaults leave the Hy-MT2 prompt unchanged.

## Assistant turn: fill continues the sentence

The assistant turn is **pre-filled with the text before the gap**, and the model writes on from there:
`这个也太` → `离谱了吧`. The answer is the text before where `after` begins. If the model never reaches
`after`, the option is dropped.

This departs from the handoff's §1 example, where the model outputs only the span. MODEL_PRD Phase 0
measured the difference on Hy-MT2 Q4: 62 → 77/100 first-answer hits, with prohibited readings down from 4 to 2,
because continuing a sentence uses the model's grammar (only a verb can follow 想; 太…了 is not doubled)
and translating a bare fragment does not. The keyboard still inserts only the span, so the product contract is the same.

- The pre-fill is `before` with trailing spaces removed. For English targets the model writes the space
  itself, because BPE vocabularies attach spaces to the next word.
- Training rows put the pre-fill in the prompt, and the loss covers the answer **and** `after`, so the
  model learns that the answer must lead into what follows.
- With an empty `after`, the model writes the answer and stops. It adds no punctuation.

## Output rules (`check`) and safe insertion

The model never edits the text field directly. The keyboard inserts an output only if it passes these
rules, and fill replaces exactly the gap span. An output that fails is dropped, not repaired:

- not empty, one line, not wrapped in quotes;
- not overlong: a Chinese fill answer is at most 16 characters (or the fragment's length, if that is
  longer), an English one at most 10 words (or 4 per Chinese character of the fragment), and a
  naturalize repair at most twice the original plus 10 characters;
- a Chinese target uses Latin letters only the way Chinese does: all-caps acronyms up to five letters
  (AI, CC, PPT, AA制, K歌) or a short list of lowercase loans (`ZH_LATIN_OK`: 有点emo, app, wifi, cc…).
  `ghost了`, `burn了` or `siempre` are leaks, and so is the English fragment copied back;
- an English target contains no CJK characters;
- a fill answer does not repeat the character or word on either side of the gap (太…太, 了…了, so so).
  Not counted: 不了/得了 + 了 (去不了了), and 一 (统一 + 一下).

### Latin letters while decoding

The phone used to ban every token with an ASCII letter during Fill decoding. Measured on the 100 dev
Fill cases (25 Sep 2026), the ban changes no first answer on the shipped 1.25-bit model, and costs one
on Q4_K_M (请CC我一下, which is what offices say). Without it, junk appears lower in the list (ghost了,
siempre 会). The shipped setting is now a soft penalty (`fill.latin_penalty` 6.0 in `prompts.json`):
it matches the ban's top 3 and top 10 exactly and lets through Latin the model strongly prefers. The
rule above then filters what is shown.

| Dev Fill (first / top 3 / top 10) | ban | none | penalty 3 | penalty 6 |
| --- | --- | --- | --- | --- |
| 1.25-bit (shipped) | 58 / 79 / 89 | 58 / 78 / 89 | 58 / 79 / 89 | 58 / 79 / 89 |
| Q4_K_M | 77 / 87 / 91 | 77 / 86 / 89 | 77 / 86 / 91 | 77 / 87 / 91 (78 with CC accepted) |

## Chat templates

| Family | User header | Assistant header |
| --- | --- | --- |
| `hymt` | `<｜hy_begin▁of▁sentence｜><｜hy_User｜>` | `<｜hy_Assistant｜>` (same as shipped `prompts.json`; tested) |
| `qwen35` | `<|im_start|>user\n` | `<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n` (the official template's non-thinking turn; no system prompt) |
| `gemma` | `<bos><start_of_turn>user\n` | `<end_of_turn>\n<start_of_turn>model\n` (raw Gemma tokens; TranslateGemma's own template only accepts translation requests) |

## Gold cases

`gold.py` defines an eval case as a request plus `good` / `bad` answers, `bins`, the phrase `family`
(used for leakage grouping), a `slice` and a `reviewed` flag. It also converts the 250 existing Fill cases (`casual` → `casual_friend`,
`neutral` → `casual_neutral`, `formal` → `work_chat`). The existing dev/locked split is kept.
