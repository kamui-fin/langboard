# Raw bakeoff: Hy-MT2-1.8B vs Qwen3.5-2B vs TranslateGemma 4B (dev)

*25 Sep 2026 · handoff §4 step 1 · `shared/eval/bakeoff.py`, gold dev split (100 legacy Fill + 102 gold_v1) · both Q4_K_M, host CPU, greedy · outputs: `raw_dev_hymt2-q4.json`, `raw_dev_qwen35-q4.json`, `raw_dev_translategemma4b-q4.json`, and `raw_locked_*.json`*

Each raw model gets its best native prompt: Hy-MT2 its own translation templates from `prompts.json`
(plus a "keep it if already natural" line for Naturalize), TranslateGemma its translation template for
Fill and the plain instruction otherwise (it has no other task), Qwen3.5 a plain instruction. Fill
continues the draft from the text before the gap in all three.

## Results

| Slice (n) | Hy-MT2 hit | crit | Qwen3.5 hit | crit | TranslateGemma 4B hit | crit |
| --- | --- | --- | --- | --- | --- | --- |
| Fill EN→ZH (101) | **70** | 1 | 45 | 10 | 42 (23 broke the sentence) | 1 |
| Fill ZH→EN (26) | **19** | 1 | 14 | 0 | 12 | 0 |
| Naturalize ZH (15) | 1 (5 near) | 0 | 1 (4 near) | 6 | 2 (7 near) | 0 |
| Naturalize EN (13) | 1 (4 near) | 3 | 3 (4 near) | 8 | 5 (8 near) | 0 |
| UNCHANGED (27) | 6 | **21** | **26** | 1 | 14 | 13 |
| Style (12) | 2 | 5 | 5 | 4 | 2 | 4 |
| Personalization (8) | 3 | 3 | **7** | 0 | 2 | 2 |

**Locked split** (150 legacy Fill + 97 gold_v1; run once for this baseline report) shows the same pattern:

| Slice (n) | Hy-MT2 hit / crit | Qwen3.5 hit / crit | TranslateGemma 4B hit / crit |
| --- | --- | --- | --- |
| Fill EN→ZH (150) | **86** / 3 | 70 / 12 | 65 / 3 (32 broke the sentence) |
| Fill ZH→EN (26) | **16** / 2 | 9 / 1 | 13 / 1 |
| Naturalize ZH (14) | 1 / 1 | 1 / 6 | **6** / 1 |
| Naturalize EN (13) | 5 / 0 | 5 / 3 | 4 / 0 |
| UNCHANGED (27) | 3 / **24** | **25** / 2 | 12 / 15 |
| Style (11) | 2 / 3 | 5 / 2 | 3 / 4 |
| Personalization (6) | 3 / 0 | **6** / 0 | 3 / 1 |

*hit* = contains a listed good answer; *near* = within 0.8 similarity of one; *critical* = a listed
prohibited reading, rewriting an UNCHANGED case, or saying UNCHANGED when the text needs repair.
Fill answers that never reach the text after the gap count as failures (the check column in `--compare`).

## What it says

- **The two models fail in opposite ways**, which is the handoff's §3 hypothesis. Hy-MT2 is the
  better translator: 25 more EN→ZH Fill hits and 9 fewer critical errors, with literal readings rare. Qwen3.5
  produces 叫它一天 (call it a day), 搞煤气灯效应 (gaslight) and quoted or broken answers.
- **Hy-MT2 cannot hold back.** It rewrote 21 of 27 natural sentences, 19 of them beyond particles, and some
  change meaning: 我觉得还可以再改改 → 您觉得还可以再调整一下吗？ (a statement becomes a question to
  someone else); 我先睡了，明天聊 → 我先去睡了，明天再聊呗. This is the trust failure the product can't ship.
- **Qwen3.5 holds back, sometimes too much.** 26/27 on UNCHANGED, but it also says UNCHANGED on 8 of
  28 sentences that need a repair. It follows the style profile (咋 for how come, ridiculous for plain wording).
- **TranslateGemma 4B is not a raw quality ceiling here.** Its Fill failures are mostly format: as a pure
  translator it rewrites or pads instead of continuing the draft (拐弯抹角，不直接点明; 太高了，简直是敲诈), so
  23 answers never reach the text after the gap. It is the cleanest at Naturalize (0 critical, most near
  matches) and halfway on restraint (14/27). Whether 4B buys quality over ~2B can only be read after the
  same fine-tune, and its full weights are gated (training needs a logged-in Hugging Face token).
- Naturalize hit rates are low for all because the listed repairs are only a few of the right ones.
  The near column and native rating are the real measure here.
- Host latency (CPU, greedy): Hy-MT2 ~100–300 ms per case, Qwen3.5 ~450–600 ms. Only device numbers count
  (handoff §19), but the gap is worth watching.

**Reading for the tuning decision:** SFT has to teach Hy-MT2 restraint, and teach Qwen3.5 translation
and when a sentence needs a repair. Which one is easier is the question the identical pilot fine-tune
answers (§4 step 2). Nothing here settles it.

## Caveats

- Gold is Claude-written and unreviewed; automatic scores are proxies (ANNOTATION.md is the measure).
- Both splits are Claude-written; the locked numbers are this baseline's reference, not a release score.
- Raw prompts differ by necessity; a better Qwen Fill prompt could narrow the gap.
- Greedy first answer only. The shipped Fill pipeline adds beams, which rescue some cases.

## Reproduce

```bash
python3 shared/eval/bakeoff.py --model ~/models/hymt2/Hy-MT2-1.8B-Q4_K_M.gguf --family hymt --latin 6 --out hymt.json
python3 shared/eval/bakeoff.py --model ~/models/qwen35/Qwen3.5-2B-Q4_K_M.gguf --family qwen35 --latin 6 --out qwen.json
python3 shared/eval/bakeoff.py --model ~/models/tgemma/translategemma-4b-it.Q4_K_M.gguf --family gemma --latin 6 --out tg.json
python3 shared/eval/bakeoff.py --compare hymt.json qwen.json tg.json
```
