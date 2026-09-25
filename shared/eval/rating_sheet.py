#!/usr/bin/env python3
"""Blind rating sheet for native reviewers, from prompt_eval.py --out results of several systems.

Each case's answers from every system (the first answer, and optionally the top N) are pooled,
de-duplicated and shuffled, so raters can't tell which model or prompt wrote what. Each row shows
the chat, the sentence with the answer in place, and empty columns for the five scores in the
model PRD (meaning, natural, fits, register, would send; 1–5), plus notes.

    python3 shared/eval/rating_sheet.py results/*.json --top 1 --out sheet.csv

writes sheet.csv (for raters) and sheet.key.csv (row id → systems that gave the answer; keep it
from the raters). Read the scores back with --score sheet.csv, which prints per-system means.
"""

import argparse
import csv
import json
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SCORES = ["meaning", "natural", "fits", "register", "would_send"]


def load_cases(set_name):
    name = {"dev": "fill.jsonl", "locked": "fill_locked.jsonl"}[set_name]
    return {c["id"]: c for c in (json.loads(l) for l in open(os.path.join(HERE, name), encoding="utf-8") if l.strip())}


def system_name(result, path):
    return f"{result.get('version')}@{result.get('model')}" if result.get("version") else os.path.basename(path)


def make_sheet(paths, top, out, seed):
    results = [(system_name(r, p), r) for p, r in ((p, json.load(open(p, encoding="utf-8"))) for p in paths)]
    sets = {r["set"] for _, r in results}
    if len(sets) != 1:
        sys.exit(f"results are from different case sets: {sets}")
    cases = load_cases(sets.pop())
    pooled = {}  # (case id, answer) -> systems
    for name, r in results:
        for row in r["rows"]:
            for answer, _ in row["answers"][:top]:
                pooled.setdefault((row["id"], answer), set()).add(name)
    rng = random.Random(seed)
    items = list(pooled.items())
    rng.shuffle(items)
    # Group each case's answers together (raters compare them), cases in shuffled order.
    order = {}
    for (cid, _), _ in items:
        order.setdefault(cid, len(order))
    items.sort(key=lambda it: order[it[0][0]])

    key_path = os.path.splitext(out)[0] + ".key.csv"
    with open(out, "w", newline="", encoding="utf-8") as f, open(key_path, "w", newline="", encoding="utf-8") as k:
        w, kw = csv.writer(f), csv.writer(k)
        w.writerow(["row", "case", "register", "chat", "english", "sentence", "answer"] + SCORES + ["notes"])
        kw.writerow(["row", "case", "answer", "systems"])
        for i, ((cid, answer), systems) in enumerate(items, 1):
            c = cases[cid]
            chat = " / ".join(("对方：" if who == "them" else "我：") + t for who, t in c.get("chat", []))
            w.writerow([i, cid, c["register"], chat, c["fragment"], c["before"] + "【" + answer + "】" + c["after"], answer]
                       + [""] * (len(SCORES) + 1))
            kw.writerow([i, cid, answer, ";".join(sorted(systems))])
    print(f"{len(items)} rows from {len(results)} systems over {len(order)} cases → {out} (key: {key_path})")


def score(sheet):
    key_path = os.path.splitext(sheet)[0] + ".key.csv"
    systems = {row["row"]: row["systems"].split(";") for row in csv.DictReader(open(key_path, encoding="utf-8"))}
    totals = {}
    for row in csv.DictReader(open(sheet, encoding="utf-8")):
        if not all(row[s].strip() for s in SCORES):
            continue
        for name in systems[row["row"]]:
            t = totals.setdefault(name, {s: [] for s in SCORES})
            for s in SCORES:
                t[s].append(float(row[s]))
    for name, t in sorted(totals.items()):
        n = len(t["would_send"])
        print(f"{name:50} n={n:4} " + "  ".join(f"{s} {sum(v) / len(v):.2f}" for s, v in t.items()))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results", nargs="*")
    ap.add_argument("--top", type=int, default=1, help="answers per system per case")
    ap.add_argument("--out", default="sheet.csv")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--score", help="a filled-in sheet to summarize per system")
    args = ap.parse_args()
    if args.score:
        return score(args.score)
    make_sheet(args.results, args.top, args.out, args.seed)


if __name__ == "__main__":
    main()
