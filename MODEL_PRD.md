# Langboard — model requirements (EN→ZH Fill)

*25 September 2026 · Companion to `PRD.md` and the distillation/data plan · Covers the Fill job only; Check and Explain come later*

## 1. Problem

Fill answers are often correct as translations and wrong in the sentence. `我本来想 roller skate` gets 旱冰鞋 (roller skates, a noun) first, when only a verb phrase can follow 想. Three causes, all in how we call the model, not only in the model:

1. **The model translates the fragment on its own.** The prompt shows the sentence as background, then asks for a translation of `roller skate` alone. Hy-MT2 does what a translator does with a bare phrase: it picks the dictionary sense. Nothing in the decode checks that the answer fits after 我本来想 or before the text that follows.
2. **The text after the gap plays no part.** `这个事情太 [insane] 了` gets 太疯狂了, which doubles 太…了. Clean-up trims echoes afterwards, but the ranking never saw 了.
3. **The percentage is not a confidence.** It is each option's share of the options shown, and beams rank by total log-probability with no length normalization, so short noun translations win. 56% meant "likelier than the other nine", not "probably right".

The dictionary is not in the prompt. Adding it would not have helped here: CC-CEDICT and Open Dictionary would both say 旱冰鞋.

### What a quick experiment showed (host, shipped 1.25-bit model, 10 beams)

Same model, different call: ask for the **whole sentence** in Chinese and **pre-fill the answer with the Chinese before the gap**, so the model continues from `我本来想`:

| Draft | Today, first | Whole sentence + pre-fill, first answer in Chinese |
| --- | --- | --- |
| 我本来想 [roller skate] | 旱冰鞋 ✗ | 玩轮滑 ✓ |
| 我本来想去但是 [I couldn't be bothered anymore] | 我懒得管了 ✗ | 现在懒得去了 ✓ |
| 我们这周末一起去 [hiking] 吧 | 去徒步旅行 (doubles 去) | 徒步吧 ✓ |
| 这个事情太 [insane] 了 | 太疯狂了 (doubles 太) | 疯狂了 ✓ |
| 他昨天又 [ghosted] 我了 | 跟踪我了 ✗ | 捉弄我了 ✗ |

The model already knows Chinese grammar; continuing a Chinese sentence uses it, translating a fragment doesn't. We get "only a verb can follow 想" without writing a grammar rule. Two new failures: the model often copies the English (`hiking吧` was the top beam), and the pre-fill does not fix what the model doesn't know (ghosted). The first is a decoding fix. The second is what tuning is for.

## 2. Goal

When a learner types a Chinese message with one English gap, the first option is something a native speaker would actually send in that spot: same meaning (including attitude), right register, and it reads correctly with the text before and after it unchanged.

**Not in scope for this document:** Chinese → more natural Chinese (Check), explaining received messages (Explain), ZH→EN, and any other language pair. Each gets its own data and gate later.

## 3. Requirements

| # | Requirement | How it's checked |
| --- | --- | --- |
| R1 | Option 1 fits the gap grammatically with the given before and after | Gold set, "patch fit" rater score |
| R2 | Meaning preserved, including attitude; no added cause or emotion (累, 忙) | Gold set, "meaning" score + prohibited readings per case |
| R3 | Register follows the chat (casual friend / neutral / work / formal) | Gold set register bins |
| R4 | Never outputs English, pinyin, explanations or quote marks | Mechanical check on every output |
| R5 | Never repeats text on either side of the gap (太…太, 去去) | Mechanical check |
| R6 | Several options when Mandarin genuinely varies, ordered by fit | Top-3 hit rate |
| R7 | Latency and memory no worse than today on a Pixel 6a: first answer ≤ 1.5 s without chat, all options ≤ 3.5 s, RSS ≤ 1 GB | `ModelBenchmarkTest` |
| R8 | The shown percentage means something, or it is removed | Calibration plot on the gold set |
| R9 | One prompt/template, versioned, identical in training, host eval and phone | `prompts.json` version recorded in every eval report |

## 4. Plan

### Phase 0 — fix the call (no training, this week)

The inference contract must be settled first, because the training data is written in it.

1. **Continuation format.** User turn: the whole draft with the English in place, plus chat and style, asking for the sentence in Chinese. Assistant turn pre-filled with `before`. Decode until the output reaches the start of `after` (or 。！？/ line end). The answer is what was generated before `after`.
2. **No Latin letters in the answer.** Mask tokens containing ASCII letters during Fill decoding (a logit mask in `engine.cpp`, not a filter afterwards, so beams spend their width on Chinese). Numbers and punctuation stay allowed.
3. **Score with the right side.** For each beam, add log P(`after` | prompt + before + answer). Candidates that make the sentence ungrammatical with what follows lose. This is one extra forced-decode pass over a few tokens per beam, sharing the cached prompt.
4. **Length normalization back on** (try α 0.6–1.0) once (3) is in, and re-derive the displayed number: probability of option given the whole sentence, not share of the list. If it still doesn't track correctness (R8), show order only.
5. **Dictionary as candidates, not as prompt text.** When the dictionary has an answer, force-score it in the same continuation (log P(word + after | before)) and let it compete with the beams. 旱冰鞋 then loses on its own; 演唱会 wins on its own. Putting glosses into the prompt biases the model towards the noun sense, which is exactly today's failure.
6. **Eval grows to 60+ cases** before any of this ships, including every case in §1 and ten verb/noun ambiguity cases (skate, fish, text, ghost, cook, date, book, iron, park, train).

Exit: current eval 13/20 first → at least 16/20, no regression in any case that passes today, latency within R7.

**Result (25 Sep 2026): shipped, partly met.** What shipped (prompts `v4-continue`):
- Continuation format with the before-text pre-filled, and a Latin-letter ban in greedy and beams.
- Beams write on into the text after the gap; the answer is cut where that text starts, and answers whose beam wanders off are dropped.
- Options ordered strictly by probability. Pinning the first answer put <1% answers above 36% ones and scored 2 worse.

Measured (1.25-bit, shipped model):
- Dev: first 55 → 58 / 100, top 3 71 → 79, top 10 82 → 89.
- Locked: first 76 → 80 / 150, top 3 91 → 102.
- Phone: all options in 1.9–3.3 s (was 2.5–3.6); with a fresh 15-message chat 5.6 s (unchanged). R7 is met except for the fresh-chat case, where reading the chat is the cost.
- **Not met:** prohibited readings first went 13 → 15 (dev) and 13 → 18 (locked). The 1.25-bit model still prefers some literal readings (拉我的腿, 睡一觉) once it's writing the sentence.

Tried and not shipped:
- (3) Scoring each option with exactly the text after the gap: same first score, reshuffles which cases win, +1.5–3 s on the phone. Kept behind `rescore` > 0.
- Scoring each option under the old fragment prompt too: +1, double the time.
- (5) Dictionary answers as extra options: no change.
- (4) Length normalization: not needed once beams end on the text after the gap.

The eval grew to 100 dev + 150 locked cases (Phase 1) instead of 60.

### Phase 1 — gold set and baselines

- **Gold set: 250 cases**, written by us and checked by two paid native Mainland speakers. At least 40 where the context decides the answer, 30 where the literal translation is plausible and wrong, 30 verb/noun/adjective ambiguity cases, 30 with non-empty `after` that constrains the answer, 30 work/formal. Each case: before, fragment, after, chat (optional), register, 1–4 acceptable answers, prohibited readings, rationale, phrase family ID. **Never used in training or in teacher prompts,** including paraphrases of the same phrase in the same kind of context.
- `shared/eval/` holds the gold set: a dev half (100 cases) plus a locked half (150) only run for baselines and release.
- **Baselines on the gold set:** today's prompt; Phase 0 call; Hy-MT2 Q4 (to see what quantization costs); TranslateGemma 4B; the teacher. Blind human scoring on the 100 dev cases for each.

This tells us how much of the gap is quantization, how much is prompting, and how much only tuning can close.

**Result (25 Sep 2026).** The gold set is written (`shared/eval/fill.jsonl`, `fill_locked.jsonl`) with bins, acceptable answers and prohibited readings. No phrase family is in both halves. **It is not reviewed yet:** a non-native speaker (Claude) wrote it, and every case says `reviewed: false`. `shared/eval/rating_sheet.py` makes the blind CSV for raters (1,068 rows for the dev baselines at top 3). Automatic scores, first answer right (prohibited reading first):

| Prompt | Model | Dev /100 | Locked /150 |
| --- | --- | --- | --- |
| v3 (fragment) | 1.25-bit TQ2_0 (shipped) | 55 (13) | 76 (13) |
| v4 (continue) | 1.25-bit TQ2_0 (shipped) | 58 (15) | 80 (18) |
| v3 | 2-bit Q2_K | 50 (12) | 72 (14) |
| v4 | 2-bit Q2_K | 62 (6) | 88 (13) |
| v3 | Q4_K_M | 62 (4) | 81 (8) |
| v4 | Q4_K_M | **77 (2)** | **92 (3)** |

**What this says:**
- **Quantization costs more than the prompt gains.** The same prompt on Q4 beats the shipped 1.25-bit by 19 on dev and 12 on locked, with almost no prohibited readings. The continuation format helps more the better the weights are (+15 on Q4, +3 on 1.25-bit).
- **This moves Phase 4 up.** Before tuning, try Q2_K on the phone with v4 (it measured ~1 GB, but was 2–3× slower in the old benchmark), and Tencent's own low-bit kernels when they land in mainline. Whatever tuning produces must survive the target quantization, or most of its gain can vanish.
- Top-10 coverage is similar across models (111–123 on locked), so ranking, not recall, is most of the gap on the small model.

Not done: TranslateGemma 4B and the teacher (neither is on this machine), and blind human scoring (needs the two native reviewers).

### Phase 2 — teacher data (1k pilot, then 5–10k)

Follows the data plan (Constructions A, B, C; open-weight teacher, Qwen3.5 class; critic; human audit). Additions and changes:

- **Training rows are written in the Phase 0 format**: user = mixed draft + chat + style; assistant = `before` + target + `after`. Loss on target + `after` only (before is pre-filled at inference, so it is input). Training on `after` too teaches the model that the answer must lead into it.
- **Part-of-speech contrast pairs** as a named category (≥10% of rows): the same English word needing a noun in one sentence and a verb or adjective in another (想 [roller skate] → 去滑旱冰; 买了一双 [roller skates] → 旱冰鞋).
- **Unknown-slang rows** (ghosted, gaslight, simp, lowkey, it's giving) with the current Mainland usage, and rows where the right answer is not slang.
- **No English in targets**, mechanically enforced; the plan's rejection list otherwise stands.
- Every row carries source ID, licence status, teacher version and prompt version (`data_manifest.csv`). Only cleared sources feed the commercial checkpoint; research-only corpora stay in a separate folder.

Mix and volumes as in the data plan (35% everyday texting, 25% pragmatic, 15% slang, 10% social nuance, 10% work, 5% formal; 1k → 5–10k accepted). Track accepted rows per category, not generations.

### Phase 3 — LoRA SFT on Hy-MT2-1.8B

- Base: the full-precision Hy-MT2-1.8B release (verify it exists at the same revision as the 1.25-bit file).
- Tencent's LoRA recipe or LLaMA-Factory; rank 16–32, sequences ≤ 512 tokens, 1–3 epochs, checkpoint eval on the dev gold every few hundred steps.
- Chat template byte-identical to `prompts.json` (R9); a test renders one row both ways and compares.
- Compare stock vs tuned at **full precision first**, then quantized.

### Phase 4 — quantize back to the phone (the main technical risk)

The shipped file is Tencent's 1.25-bit ternary model, which was almost certainly produced with quantization-aware training. Merging a LoRA into full-precision weights and re-quantizing to ternary with a plain post-training quantizer may lose most of what tuning bought. In order of preference:

1. Ask for / use Tencent's quantization tooling for the 1.25-bit and 2-bit formats and re-run it on the merged model.
2. Keep the adapter separate and apply it at runtime on top of the ternary base (llama.cpp LoRA adapters, in f16). Costs a little memory and speed; measure.
3. Ship Q2_K or Q4 of the tuned model if (1) and (2) fail, only if R7 still holds.
4. If none fit, distill into a smaller specialist with the same data (data plan, Step 6).

Every quantized candidate runs the same gold set; report quality loss vs full precision per bin.

### Phase 5 — failure mining, then maybe DPO

Run tuned checkpoints on the dev gold and a fresh set; collect failures by class (wrong part of speech, literal idiom, invented emotion, register, English leak, duplication). For each class, new training families (never gold copies). DPO only if a class survives SFT; stop if it narrows answer variety.

## 5. Metrics and release gate

- **Primary:** "would send" rate for option 1 on the locked gold, two blind raters, third on disagreement.
- **Also reported, per bin** (part of speech, idiom, slang, social nuance, work/formal, non-empty after, context-dependent): meaning fidelity, patch fit, register, top-3 hit rate, English-leak rate, duplication rate.
- **Ship when:** tuned + quantized beats the Phase 0 call on "would send" by a clear margin set after baselines are scored; no bin gets worse; zero English leaks and duplications in the locked set; R7 holds on the Pixel 6a and a 4 GB phone.
- Numbers for "clear margin" are set after Phase 1, not guessed now.

## 6. Deliverables

| Phase | Deliverable |
| --- | --- |
| 0 | `prompts.json` v4 (continuation format), Latin mask + right-side scoring in `engine.cpp`, 60-case eval |
| 1 | `gold_v1.jsonl` (dev + locked), baseline report |
| 2 | `data_manifest.csv`, `pilot_1k.jsonl`, `sft_10k.jsonl`, critic and audit report |
| 3 | Training config, adapter, full-precision eval report |
| 4 | Quantized pack(s), per-bin quality loss, device benchmark |
| 5 | Failure taxonomy, follow-up data, go/no-go |

## 7. Open questions

1. Is the full-precision Hy-MT2-1.8B at the same revision as the 1.25-bit file, and can Tencent's 1.25-bit quantizer be run on our merged weights?
2. Does a runtime LoRA on top of TQ2_0 work in mainline llama.cpp, and at what speed?
3. Which teacher: a 100-case native-speaker comparison decides.
4. Who are the two native reviewers, and at what rate per case?
5. Does the continuation format also fix Check (rewrite from the cursor rather than the whole sentence)? Worth one experiment after Phase 0.
