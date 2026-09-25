#!/usr/bin/env python3
"""The identical pilot fine-tune (handoff §4 step 2, artifacts/bakeoff_decision.md): same rows, same budget,
every candidate; then each is merged, quantized to the bakeoff's Q4_K_M and scored on the dev gold
with the lb1 prompt, next to its raw score.

    model/.venv/bin/python model/run_pilot.py --data model/data/pilot_v0.jsonl --name v0 [--research]
    model/.venv/bin/python model/run_pilot.py --name v0 --only hymt     # one candidate

Outputs land in model/runs/<name>-<family>/ and the comparison in shared/eval/reports/pilot_<name>_dev.txt.
"""

import argparse
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY = os.path.join(ROOT, "model", ".venv", "bin", "python")
QUANTIZE = os.path.join(ROOT, "model", ".llama-cuda", "bin", "llama-quantize")
REPORTS = os.path.join(ROOT, "shared", "eval", "reports")
HOME = os.path.expanduser("~/models")

CANDIDATES = {
    "hymt": (f"{HOME}/hymt2/hf", "raw_dev_hymt2-q4.json"),
    "qwen35": (f"{HOME}/qwen35/hf", "raw_dev_qwen35-q4.json"),
    "gemma": (f"{HOME}/tgemma/hf", "raw_dev_translategemma4b-q4.json"),
}


def run(cmd, log):
    print("  $", " ".join(cmd[:4]), "…", flush=True)
    with open(log, "a", encoding="utf-8") as f:
        subprocess.run(cmd, check=True, stdout=f, stderr=subprocess.STDOUT)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data")
    ap.add_argument("--name", required=True)
    ap.add_argument("--only", help="comma-separated families")
    ap.add_argument("--research", action="store_true")
    ap.add_argument("--rank", default="16")
    ap.add_argument("--lr", default="1.5e-4")
    ap.add_argument("--epochs", default="2")
    ap.add_argument("--skip-train", action="store_true", help="re-export and re-score existing runs")
    args = ap.parse_args()
    fams = args.only.split(",") if args.only else [f for f, (base, _) in CANDIDATES.items() if os.path.isdir(base)]
    outs = []
    for fam in fams:
        base, raw = CANDIDATES[fam]
        rd = os.path.join(ROOT, "model", "runs", f"{args.name}-{fam}")
        os.makedirs(rd, exist_ok=True)
        log = os.path.join(rd, "pilot.log")
        print(f"== {fam}", flush=True)
        if not args.skip_train:
            run([PY, os.path.join(ROOT, "model", "train_lora.py"), "--data", args.data, "--base", base, "--family", fam,
                 "--out", rd, "--rank", args.rank, "--lr", args.lr, "--epochs", args.epochs, "--eval-steps", "25"]
                + (["--research"] if args.research else []), log)
        run([PY, os.path.join(ROOT, "model", "train_lora.py"), "--export", rd], log)
        q4 = os.path.join(rd, "model-Q4_K_M.gguf")
        run([QUANTIZE, os.path.join(rd, "model-f16.gguf"), q4, "Q4_K_M"], log)
        os.remove(os.path.join(rd, "model-f16.gguf"))  # 4 GB each; the merged HF weights stay
        out = os.path.join(REPORTS, f"pilot_{args.name}_dev_{fam}.json")
        run([sys.executable, os.path.join(ROOT, "shared", "eval", "bakeoff.py"), "--model", q4, "--family", fam,
             "--prompt", "lb1", "--latin", "6", "--out", out], log)
        outs += [os.path.join(REPORTS, raw), out]
    table = subprocess.run([sys.executable, os.path.join(ROOT, "shared", "eval", "bakeoff.py"), "--compare", *outs],
                           check=True, capture_output=True, text=True).stdout
    with open(os.path.join(REPORTS, f"pilot_{args.name}_dev.txt"), "w", encoding="utf-8") as f:
        f.write(table)
    print(table)


if __name__ == "__main__":
    main()
