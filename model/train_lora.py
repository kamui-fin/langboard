#!/usr/bin/env python3
"""LoRA SFT on lb1 pilot rows for any bakeoff candidate (handoff §7, §15).

Rows are JSONL, one gold target per request, in the lb1 contract:

    {"id": "...", "request": {...lb1 request...}, "target": "离谱", "source_id": "tatoeba-cmn-eng", "family": "insane"}

For naturalize the target is the repair, or UNCHANGED. Every row's source must be in
shared/data/data_manifest.csv with commercial_checkpoint yes (or pass --research to allow others;
such a checkpoint is marked research-only). Gold eval cases are refused outright.

The prompt is rendered exactly as at inference (`contract.render`, pre-fill included) and tokenized on
its own, then the completion (answer + text after the gap + end of turn) on its own, so training sees
the same token boundary the phone does. Loss covers the completion only.

    model/.venv/bin/python model/train_lora.py --data pilot.jsonl --base ~/models/qwen35/hf --family qwen35 \\
        --out model/runs/qwen35-r16 --rank 16 --lr 1.5e-4 --epochs 2
    model/.venv/bin/python model/train_lora.py --export model/runs/qwen35-r16   # merged HF weights + GGUF for bakeoff.py --prompt lb1

The sweep in §15 is a loop over --rank/--alpha/--lr/--targets; each run writes its config next to it.
"""

import argparse
import csv
import json
import os
import random
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "shared", "contract"))

from contract import VERSION, Request, render, training_row, validate  # noqa: E402

LLAMA_CPP = os.path.join(ROOT, "android", "third_party", "llama.cpp")
# What ends the assistant turn in each family's template.
END_OF_TURN = {"hymt": None, "qwen35": "<|im_end|>", "gemma": "<end_of_turn>"}


def load_rows(path, research):
    manifest = {r["source_id"]: r for r in csv.DictReader(open(os.path.join(ROOT, "shared/data/data_manifest.csv"), encoding="utf-8"))}
    gold = set()
    for name in ("fill.jsonl", "fill_locked.jsonl", "gold_v1/dev.jsonl", "gold_v1/locked.jsonl"):
        with open(os.path.join(ROOT, "shared/eval", name), encoding="utf-8") as f:
            for line in f:
                c = json.loads(line)
                req = c.get("request") or {"before": c["before"], "fragment": c["fragment"], "after": c["after"], "text": ""}
                gold.add((req.get("before", ""), req.get("fragment", ""), req.get("after", ""), req.get("text", "")))
    rows = []
    for line in open(path, encoding="utf-8"):
        row = json.loads(line)
        req = Request.from_dict(row["request"])
        validate(req)
        if (req.before, req.fragment, req.after, req.text) in gold:
            sys.exit(f"{row['id']}: this is a gold eval case; gold never trains")
        src = manifest.get(row.get("source_id"))
        if src is None:
            sys.exit(f"{row['id']}: source {row.get('source_id')!r} is not in data_manifest.csv")
        if not src["commercial_checkpoint"].startswith("yes") and not research:
            sys.exit(f"{row['id']}: source {src['source_id']} is not cleared for a commercial checkpoint (--research to allow)")
        rows.append((row, req))
    return rows


def encode(tok, family, req, target, max_len):
    row = training_row(req, target, family=family)
    end = END_OF_TURN[family] or tok.eos_token
    p = tok(row["prompt"], add_special_tokens=False)["input_ids"]
    c = tok(row["completion"] + end, add_special_tokens=False)["input_ids"]
    ids = (p + c)[:max_len]
    labels = ([-100] * len(p) + c)[:max_len]
    return {"input_ids": ids, "labels": labels}


def train(args):
    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainingArguments

    rows = load_rows(args.data, args.research)
    random.Random(args.seed).shuffle(rows)
    # Hold out whole families, so held-out loss measures generalisation, not memory.
    fams = sorted({r.get("family", r["id"]) for r, _ in rows})
    held = set(random.Random(args.seed).sample(fams, max(1, int(len(fams) * args.holdout)))) if args.holdout > 0 else set()
    tok = AutoTokenizer.from_pretrained(args.base, trust_remote_code=True)
    enc = lambda part: [encode(tok, args.family, req, r["target"], args.max_len) for r, req in part]
    train_set = enc([x for x in rows if x[0].get("family", x[0]["id"]) not in held])
    eval_set = enc([x for x in rows if x[0].get("family", x[0]["id"]) in held])
    print(f"{len(train_set)} train rows, {len(eval_set)} held-out rows ({len(held)} families)")

    model = AutoModelForCausalLM.from_pretrained(args.base, dtype=torch.bfloat16, trust_remote_code=True).to("cuda")
    targets = "all-linear" if args.targets == "all-linear" else args.targets.split(",")
    lora = LoraConfig(r=args.rank, lora_alpha=args.alpha or 2 * args.rank, lora_dropout=0.05, target_modules=targets,
                      exclude_modules=r".*(visual|vision|mm_projector).*", task_type="CAUSAL_LM")
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    pad = tok.pad_token_id if tok.pad_token_id is not None else tok.eos_token_id

    def collate(batch):
        n = max(len(b["input_ids"]) for b in batch)
        ids = torch.full((len(batch), n), pad)
        labels = torch.full((len(batch), n), -100)
        mask = torch.zeros((len(batch), n), dtype=torch.long)
        for i, b in enumerate(batch):
            k = len(b["input_ids"])
            ids[i, :k] = torch.tensor(b["input_ids"])
            labels[i, :k] = torch.tensor(b["labels"])
            mask[i, :k] = 1
        return {"input_ids": ids, "labels": labels, "attention_mask": mask}

    targs = TrainingArguments(
        output_dir=args.out, num_train_epochs=args.epochs, learning_rate=args.lr, per_device_train_batch_size=args.batch,
        per_device_eval_batch_size=args.batch, gradient_accumulation_steps=args.accum, lr_scheduler_type="cosine",
        warmup_steps=0.05, logging_steps=5, eval_strategy="steps" if eval_set else "no", eval_steps=args.eval_steps,
        save_strategy="steps", save_steps=args.eval_steps, save_total_limit=3, bf16=True, report_to=[],
        remove_unused_columns=False, seed=args.seed, max_steps=args.max_steps,
    )
    Trainer(model=model, args=targs, train_dataset=train_set, eval_dataset=eval_set or None, data_collator=collate).train()
    model.save_pretrained(os.path.join(args.out, "adapter"))
    tok.save_pretrained(os.path.join(args.out, "adapter"))
    config = {**vars(args), "contract": VERSION, "rows": len(rows), "held_out_families": sorted(held),
              "commercial": not args.research}
    json.dump(config, open(os.path.join(args.out, "run.json"), "w"), ensure_ascii=False, indent=1)
    print(f"adapter and run.json in {args.out}")


def export(run_dir):
    """Merged full-precision weights, then a GGUF the host eval and phone can load."""
    import torch
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    run = json.load(open(os.path.join(run_dir, "run.json")))
    base = AutoModelForCausalLM.from_pretrained(run["base"], dtype=torch.bfloat16, trust_remote_code=True)
    merged = PeftModel.from_pretrained(base, os.path.join(run_dir, "adapter")).merge_and_unload()
    out = os.path.join(run_dir, "merged")
    merged.save_pretrained(out)
    AutoTokenizer.from_pretrained(run["base"], trust_remote_code=True).save_pretrained(out)
    gguf = os.path.join(run_dir, "model-f16.gguf")
    # A causal-LM load drops Qwen3.5's multi-token-prediction head (speculative decoding only, which
    # the phone doesn't use), so the converter is told not to expect it.
    extra = ["--no-mtp"] if run["family"] == "qwen35" else []
    subprocess.run([sys.executable, os.path.join(LLAMA_CPP, "convert_hf_to_gguf.py"), out, "--outtype", "f16",
                    "--outfile", gguf, *extra], check=True)
    print(f"{gguf}\nquantize with llama-quantize to the pack's type, then: "
          f"python3 shared/eval/bakeoff.py --model <gguf> --family {run['family']} --prompt lb1")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data")
    ap.add_argument("--base", help="Hugging Face model directory")
    ap.add_argument("--family", choices=sorted(END_OF_TURN))
    ap.add_argument("--out")
    ap.add_argument("--rank", type=int, default=16)
    ap.add_argument("--alpha", type=int, help="default 2 × rank")
    ap.add_argument("--targets", default="all-linear", help="all-linear, or comma-separated module names")
    ap.add_argument("--lr", type=float, default=1.5e-4)
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--max-steps", type=int, default=-1)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--accum", type=int, default=1)
    ap.add_argument("--max-len", type=int, default=512)
    ap.add_argument("--holdout", type=float, default=0.05, help="share of families held out for eval loss")
    ap.add_argument("--eval-steps", type=int, default=50)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--research", action="store_true", help="allow sources not cleared for commercial use")
    ap.add_argument("--export", metavar="RUN_DIR", help="merge a finished run and write a GGUF")
    args = ap.parse_args()
    if args.export:
        return export(args.export)
    if not (args.data and args.base and args.family and args.out):
        ap.error("--data, --base, --family and --out are required")
    os.makedirs(args.out, exist_ok=True)
    train(args)


if __name__ == "__main__":
    main()
