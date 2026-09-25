# Raw bakeoff: Hy-MT2-1.8B vs Qwen3.5-2B (dev)

*25 Sep 2026 · handoff §4 step 1 · `shared/eval/bakeoff.py`, gold dev split (100 legacy Fill + 102 gold_v1) · both Q4_K_M, host CPU, greedy · outputs: `raw_dev_hymt2-q4.json`, `raw_dev_qwen35-q4.json`*

Each raw model gets its best native prompt: Hy-MT2 its own translation templates from `prompts.json`
(plus a "keep it if already natural" line for Naturalize), Qwen3.5 a plain instruction. Fill continues
the draft from the text before the gap in both. **TranslateGemma 4B is not run yet** (gated: accept the
Gemma license on Hugging Face first).

## Results

| Slice (n) | Hy-MT2 hit | Hy-MT2 critical | Qwen3.5 hit | Qwen3.5 critical |
| --- | --- | --- | --- | --- |
| Fill EN→ZH (101) | **70** | 1 | 45 | 10 |
| Fill ZH→EN (26) | **19** | 1 | 14 | 0 |
| Naturalize ZH (15) | 1 (5 near) | 0 | 1 (4 near) | 6 |
| Naturalize EN (13) | 1 (4 near) | 3 | 3 (4 near) | 8 |
| UNCHANGED (27) | 6 | **21 rewrote natural text** | **26** | 1 |
| Style (12) | 2 | 5 | 5 | 4 |
| Personalization (8) | 3 | 3 | **7** | 0 |

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
- Naturalize hit rates are low for both because the listed repairs are only a few of the right ones.
  The near column and native rating are the real measure here.
- Host latency (CPU, greedy): Hy-MT2 ~100–300 ms per case, Qwen3.5 ~450–600 ms. Only device numbers count
  (handoff §19), but the gap is worth watching.

**Reading for the tuning decision:** SFT has to teach Hy-MT2 restraint, and teach Qwen3.5 translation
and when a sentence needs a repair. Which one is easier is the question the identical pilot fine-tune
answers (§4 step 2). Nothing here settles it.

## Caveats

- Gold is Claude-written and unreviewed; automatic scores are proxies (ANNOTATION.md is the measure).
- Dev split only; the locked split is for the final baseline report.
- Raw prompts differ by necessity; a better Qwen Fill prompt could narrow the gap.
- Greedy first answer only. The shipped Fill pipeline adds beams, which rescue some cases.

## Reproduce

```bash
python3 shared/eval/bakeoff.py --model ~/models/hymt2/Hy-MT2-1.8B-Q4_K_M.gguf --family hymt --latin 6 --out hymt.json
python3 shared/eval/bakeoff.py --model ~/models/qwen35/Qwen3.5-2B-Q4_K_M.gguf --family qwen35 --latin 6 --out qwen.json
python3 shared/eval/bakeoff.py --compare hymt.json qwen.json
```
