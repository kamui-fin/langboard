# Evals

Shared by every platform. Run from the repo root.

| File | What |
| --- | --- |
| `prompt_eval.py` | Runs the prompts (`android/app/src/main/assets/prompts.json` by default) through `hymt_eval/`, a host build of the phone's engine (`android/llama/src/main/cpp/engine.cpp`, llama.cpp from `android/tools/fetch_llama_cpp.sh`) |
| `check.jsonl`, `explain.jsonl` | Check and Explain inputs, printed for reading |
| `pinyin_eval.py`, `pinyin_gold.tsv` | Pinyin readings against 80 hand-labeled chat phrases |

## Fill gold set

Hand-written English-in-Chinese cases for the Fill job (`../../MODEL_PRD.md`, Phase 1).

| File | Cases | Use |
| --- | --- | --- |
| `fill.jsonl` | 100 | **dev**: prompt and decoding work, run as often as you like |
| `fill_locked.jsonl` | 150 | **locked**: baseline and release reports only (`--set locked --i-mean-it`) |
| `prompts_v3.json` | | The fill prompt before 25 Sep 2026 (fragment translated on its own), kept for baselines |
| `rating_sheet.py` | | Blind CSV for native raters from several `--out` results, and the per-system summary |

No English phrase family is in both files (`roller skate` / `roller skates` count as one family).
Never copy a locked case, or a paraphrase of one in a similar context, into training data or a
teacher prompt.

## A case

```json
{"id": "pos-rollerskate-v", "bins": ["pos", "literal"],
 "before": "我本来想", "fragment": "roller skate", "after": "", "register": "casual",
 "chat": [["them", "..."]],
 "good": ["去滑旱冰", "玩轮滑", "..."], "bad": ["旱冰鞋", "轮滑鞋"], "reviewed": false}
```

- `good`: acceptable answers. An answer counts when it contains one of them with at most three
  extra characters (了, 我, 啊…). Automatic scoring is a proxy; the rating sheet is the real measure.
- `bad`: readings that are wrong here (the literal translation, the other part of speech, the
  wrong sense). An answer that contains one and no good one is counted as "prohibited".
- `bins`: `pos` (part of speech decides), `literal` (a literal translation is plausible and wrong),
  `slang`, `idiom`, `social`, `work`, `after` (the text after the gap constrains the answer),
  `context` (the chat decides), `everyday`. A case can be in several.
- `reviewed`: **false for every case today.** The cases and answer lists were written by a
  non-native speaker (Claude) and need two native Mainland reviewers: fix answers, add missing
  acceptable ones, drop cases that are unnatural, then set `reviewed: true`.

## Running

```bash
python3 shared/eval/prompt_eval.py                                     # dev, shipped prompts and model
python3 shared/eval/prompt_eval.py --prompts shared/eval/prompts_v3.json --out v3.json
python3 shared/eval/prompt_eval.py --model ~/models/hymt2/Hy-MT2-1.8B-Q4_K_M.gguf --out q4.json
python3 shared/eval/rating_sheet.py v3.json q4.json --top 3 --out sheet.csv
python3 shared/eval/rating_sheet.py --score sheet.csv             # after raters fill it in
```

The report line gives first / top 3 / top 10 hits, how often the first answer is a prohibited
reading, English, or doubles a character next to the gap, the median time per case on this
computer, and first-answer hits per bin.
