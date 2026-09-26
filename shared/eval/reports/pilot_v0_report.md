# Pilot v0: identical LoRA SFT on Hy-MT2-1.8B and Qwen3.5-2B (dev)

*25 Sep 2026 · handoff §4 step 2 · decision rule: `artifacts/bakeoff_decision.md` · `model/run_pilot.py --name v0`*

**Provisional.** The training data is `model/data/pilot_v0.jsonl`: 901 rows from Tatoeba (human
sentences on both sides, CC BY 2.0 FR), with Qwen3.5-27B as teacher for spans, fragments, learner
inputs and a naturalness filter. Targets are always the human text, but the Chinese side is often
translated from English and has not been reviewed by natives, so this is a research checkpoint and a
**relative** test: both models got the same rows and the same budget (LoRA r16 / α32, all linear layers,
lr 1.5e-4, 2 epochs, 5% of families held out). Scored at Q4_K_M with the lb1 prompt, greedy, on the dev
gold (Claude-written, unreviewed). TranslateGemma sits out: its full weights are gated.

| Slice (n) | Hy-MT2 raw | **Hy-MT2 tuned** | Qwen3.5 raw | **Qwen3.5 tuned** |
| --- | --- | --- | --- | --- |
| Fill EN→ZH (101) | 70 / 1 crit | **68 / 0 crit** | 45 / 10 | 52 / 7 |
| Fill ZH→EN (26) | 19 / 1 | 17 / 1 | 14 / 0 | 17 / 0 |
| UNCHANGED (27) | 6 (21 rewrites) | **26** (1) | 26 (1) | 25 (2) |
| Naturalize ZH (15) hit / near / said UNCHANGED | 1 / 5 / 0 | 3 / 5 / 6 | 1 / 4 / 3 | 1 / 4 / 6 |
| Naturalize EN (13) hit / near / said UNCHANGED | 1 / 4 / 0 | 3 / 4 / 7 | 3 / 4 / 5 | 2 / 5 / 4 |
| Style (12) | 2 | 5 | 5 | 5 |
| Personalization (8) | 3 | 5 | 7 | 6 |

Held-out training loss: Hy-MT2 0.59, Qwen3.5 0.73.

## Reading

- **Hy-MT2's weakness trained away; Qwen3.5's did not.** 901 rows took Hy-MT2 from rewriting 21 of 27
  natural sentences to leaving 26 alone, while its Fill held (68 vs 70) with no critical errors. Qwen3.5
  gained 7 on EN→ZH Fill but stays 16 behind, still with 7 critical errors. This is Outcome 2 in the
  decision framework ("Hy-MT2 turns out to be surprisingly trainable"), provisionally.
- **Restraint swung too far on both.** Tuned Hy-MT2 says UNCHANGED on 13 of 28 sentences that need a
  repair (Qwen3.5: 10). The pilot had 33% UNCHANGED within Naturalize; at 10% this drops to 7 with
  restraint intact (ablation below).
- **Personalization barely has data**: Tatoeba gave 1 of 50 rows (its Chinese almost never uses 啥/咋/挺).
  Hy-MT2 still went 3 → 5 of 8; its p13n number means little until real profile rows exist.
- Style has no training rows (it needs human targets per style) and moved only for Hy-MT2 (2 → 5).

## UNCHANGED ratio ablation (§8), Hy-MT2

Same rows as v0 except UNCHANGED, subsampled (`pilot_v0_u10.jsonl`, `pilot_v0_u20.jsonl`); same budget.

| UNCHANGED share of Naturalize | Leaves natural text alone (27) | Said UNCHANGED on a needed repair (28) | Repairs hit or near (28) | Fill EN→ZH (101) | Personalization (8) |
| --- | --- | --- | --- | --- | --- |
| **10%** | 26 | **7** | **11** | **71** | **7** |
| 20% | 26 | 10 | 8 | 66 | 5 |
| 33% (v0) | 26 | 13 | 9 | 68 | 5 |

Restraint on natural text holds even at 10%, while wrong UNCHANGED answers fall with the share. **Use
10% for the next data round** (and try 5% once, to find where restraint starts to break). One seed,
small slices: differences of 2–3 cases are noise; the trend in the missed column is the signal.

## Not yet known

- Whether this holds with native-reviewed targets, a hyperparameter sweep (§15), and blind native rating.
- TranslateGemma 4B under the same fine-tune (needs `hf auth login` for its gated weights).
- Device numbers (§19) for the tuned Q4_K_M, and whether the gain survives Hy-MT2's 1.25-bit / 2-bit
  packs (MODEL_PRD Phase 4: Tencent's QAT low-bit formats cannot simply be re-quantized).

## Bug found on the way

llama.cpp's Hunyuan converter takes the GGUF's end-of-generation token from `config.json`'s
`eod_token_id`, which is 3 (`$`) for Hy-MT2. The tuned model then never stopped and started the
sentence over. `train_lora.py --export` now aligns it with the tokenizer's EOS (120020).
