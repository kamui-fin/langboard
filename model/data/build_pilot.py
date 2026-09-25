#!/usr/bin/env python3
"""Pilot SFT rows (handoff §6–7) from human-written text, with a local teacher for inputs only.

The target of every row is human text. The teacher (an open-weight model behind llama-server) never
writes a target: it rates how natural each human sentence is as a message, picks the span a learner
would leave as a gap and writes the source-language fragment the learner would type there, and writes
the degraded "learner" version that Naturalize must repair. That is the inverse-synthetic recipe:
authentic target → controlled degradation → train the model to recover the target.

    model/.venv/bin/python model/data/build_pilot.py select            # Tatoeba → candidates (filters, leakage)
    model/.venv/bin/python model/data/build_pilot.py annotate --server http://127.0.0.1:8080
    model/.venv/bin/python model/data/build_pilot.py assemble --out model/data/pilot_v0.jsonl

Stages cache to model/data/work/, so annotate can resume. v0 uses Tatoeba (CC BY 2.0 FR, attributed per
row). Its Chinese side is often translated from English, so Chinese targets are provisional until
native review; the teacher's naturalness rating is a filter, not ground truth.
"""

import argparse
import json
import os
import random
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from difflib import SequenceMatcher

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "shared", "contract"))

from contract import Request, Style, Personalization, UNCHANGED, validate, check, training_row  # noqa: E402
from gold import load  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")
RAW = os.path.join(HERE, "raw", "tatoeba_cmn_eng.tsv")
EN, ZH = "en-US", "zh-Hans-CN"
CJK = re.compile(r"[㐀-鿿]")

# v0 quotas (handoff §7). Style contrasts need a human target per style, which Tatoeba can't give;
# they wait for commissioned text.
QUOTA = {"fill_en_zh": 250, "fill_zh_en": 200, "naturalize_zh": 175, "naturalize_en": 125,
         "unchanged_zh": 75, "unchanged_en": 75, "p13n": 50}


# ------------------------------------------------------------------ select

def gold_guard():
    """What a training row must not share with the frozen eval: its sentences, its texts, and its
    expression families (a gold fragment together with one of its good answers)."""
    texts, families = [], []
    for split in ("dev", "locked"):
        for c in load(split):
            r = c["request"]
            if r["task"] == "fill":
                texts.append(r["before"] + "".join(c["good"][:1]) + r["after"])
                families.append((r["fragment"].lower().strip(), [g for g in c["good"] if g != UNCHANGED]))
            else:
                texts.append(r["text"])
    return [t for t in texts if len(t) >= 4], families


def leaks(zh, en, texts, families):
    for t in texts:
        if t in zh or t in en or (len(t) >= 6 and SequenceMatcher(None, t, zh).ratio() >= 0.8):
            return True
    low = en.lower()
    return any(len(frag) >= 3 and frag in low and any(g in zh or g.lower() in low for g in goods) for frag, goods in families)


def chatty_en(en):
    words = en.split()
    return 2 <= len(words) <= 14 and bool(re.search(r"\b(I|I'm|you|we|let's|you're|don't|can't|it's|that's)\b", en, re.I))


def select(args):
    from opencc import OpenCC
    t2s = OpenCC("t2s")
    texts, families = gold_guard()
    seen, out = set(), []
    for line in open(RAW, encoding="utf-8"):
        en, zh, attr = line.rstrip("\n").split("\t")[:3]
        if t2s.convert(zh) != zh or not (4 <= len(zh) <= 24) or not chatty_en(en) or zh in seen:
            continue
        if "汤姆" in zh or "玛丽" in zh or "Tom" in en or "Mary" in en:  # Tatoeba's stock names read as textbook
            continue
        if leaks(zh, en, texts, families):
            continue
        seen.add(zh)
        m = re.search(r"#(\d+) \(([^)]*)\) & #(\d+) \(([^)]*)\)", attr)
        out.append({"id": f"tatoeba-{m.group(1)}-{m.group(3)}" if m else f"tatoeba-{len(out)}", "en": en, "zh": zh, "attribution": attr})
    random.Random(1).shuffle(out)
    os.makedirs(WORK, exist_ok=True)
    with open(os.path.join(WORK, "candidates.jsonl"), "w", encoding="utf-8") as f:
        for c in out:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    print(f"{len(out)} candidate pairs → work/candidates.jsonl")


# ------------------------------------------------------------------ annotate

TEACHER_PROMPT = """You help build training data for a keyboard that helps language learners. Below is a Chinese sentence and an English sentence, both written by people, meaning the same thing.

Chinese: {zh}
English: {en}

Return JSON only:
- "zh_natural": 1-5, would a contemporary Mainland native speaker actually send this exact Chinese as a chat message? 5 = exactly how people text, 3 = understandable but textbook or translated-sounding, 1 = unnatural.
- "en_natural": 1-5, the same question for the English and a native American English speaker.
- "register": one of casual_friend, casual_neutral, work_chat, professional: the chat context the sentences fit best.
- "zh_span": the one expression in the Chinese sentence a learner of Chinese would most likely not know how to say (a verb phrase, idiom, adjective or set phrase, 1-6 characters). It MUST be copied exactly from the Chinese sentence. null if nothing fits.
- "zh_span_en": the English words the learner would type in place of zh_span, taken from or matching the English sentence. English only.
- "en_span": the one expression in the English sentence a Chinese learner of English would most likely not know how to say (1-4 words), copied exactly from the English sentence. null if nothing fits.
- "en_span_zh": the Chinese the learner would type in place of en_span. Chinese only.
- "zh_learner": the Chinese sentence as an English-speaking intermediate learner might write it: same meaning and tone, but with typical learner problems (word-for-word translation from English, wrong measure word, stiff or textbook wording, 的/了 misuse, word order). Must differ from the original. Never add or drop meaning.
- "en_learner": the English sentence as a Chinese-speaking intermediate learner might write it (Chinese-English interference, missing articles, wrong tense, stiff wording). Must differ from the original. Never add or drop meaning.
"""

SCHEMA = {
    "type": "object",
    "properties": {
        "zh_natural": {"type": "integer"}, "en_natural": {"type": "integer"},
        "register": {"type": "string", "enum": ["casual_friend", "casual_neutral", "work_chat", "professional"]},
        "zh_span": {"type": ["string", "null"]}, "zh_span_en": {"type": ["string", "null"]},
        "en_span": {"type": ["string", "null"]}, "en_span_zh": {"type": ["string", "null"]},
        "zh_learner": {"type": "string"}, "en_learner": {"type": "string"},
    },
    "required": ["zh_natural", "en_natural", "register", "zh_span", "zh_span_en", "en_span", "en_span_zh", "zh_learner", "en_learner"],
}


def ask(server, cand, model_name):
    body = {
        "model": model_name,
        "messages": [{"role": "user", "content": TEACHER_PROMPT.format(zh=cand["zh"], en=cand["en"])}],
        "temperature": 0.3, "max_tokens": 400,
        "response_format": {"type": "json_schema", "json_schema": {"name": "annotation", "schema": SCHEMA}},
        "chat_template_kwargs": {"enable_thinking": False},
    }
    req = urllib.request.Request(server.rstrip("/") + "/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        resp = json.load(r)
    return json.loads(resp["choices"][0]["message"]["content"]), resp.get("model", model_name)


def annotate(args):
    cands = [json.loads(l) for l in open(os.path.join(WORK, "candidates.jsonl"), encoding="utf-8")][: args.limit]
    path = os.path.join(WORK, "annotations.jsonl")
    done = {json.loads(l)["id"] for l in open(path, encoding="utf-8")} if os.path.exists(path) else set()
    todo = [c for c in cands if c["id"] not in done]
    print(f"{len(done)} done, {len(todo)} to annotate")
    with open(path, "a", encoding="utf-8") as f, ThreadPoolExecutor(args.parallel) as pool:
        futs = {pool.submit(ask, args.server, c, args.teacher): c for c in todo}
        for i, fut in enumerate(as_completed(futs), 1):
            c = futs[fut]
            try:
                ann, model = fut.result()
            except Exception as e:  # noqa: BLE001 — a bad generation skips the pair, it doesn't stop the run
                print(f"  {c['id']}: {e}")
                continue
            f.write(json.dumps({**c, "teacher": model, "ann": ann}, ensure_ascii=False) + "\n")
            f.flush()
            if i % 50 == 0:
                print(f"  {i}/{len(todo)}")


# ------------------------------------------------------------------ assemble

def span_parts(sentence, span):
    i = sentence.find(span)
    if not span or i < 0 or sentence.count(span) != 1:
        return None
    return sentence[:i], sentence[i + len(span):]


def rows_for(a):
    """Every row this annotated pair could give, by slice. Targets are always the human text."""
    ann, zh, en = a["ann"], a["zh"], a["en"]
    reg = ann.get("register") if ann.get("register") in ("casual_friend", "casual_neutral", "work_chat", "professional") else "casual_neutral"
    style = Style(register=reg)
    out = {}
    zh_ok, en_ok = ann.get("zh_natural", 0) >= 4, ann.get("en_natural", 0) >= 4
    if zh_ok and ann.get("zh_span") and ann.get("zh_span_en") and not CJK.search(ann["zh_span_en"]):
        parts = span_parts(zh, ann["zh_span"])
        if parts:
            req = Request(task="fill", source_locale=EN, target_locale=ZH, before=parts[0], fragment=ann["zh_span_en"].strip(), after=parts[1], style=style)
            out["fill_en_zh"] = (req, ann["zh_span"])
    if en_ok and ann.get("en_span") and ann.get("en_span_zh") and CJK.search(ann["en_span_zh"]) and not re.search("[A-Za-z]", ann["en_span_zh"]):
        parts = span_parts(en, ann["en_span"])
        if parts:
            req = Request(task="fill", source_locale=ZH, target_locale=EN, before=parts[0], fragment=ann["en_span_zh"].strip(), after=parts[1], style=style)
            out["fill_zh_en"] = (req, ann["en_span"])
    zl, el = (ann.get("zh_learner") or "").strip(), (ann.get("en_learner") or "").strip()
    if zh_ok and zl and zl != zh and CJK.search(zl) and SequenceMatcher(None, zl, zh).ratio() >= 0.4:
        out["naturalize_zh"] = (Request(task="naturalize", target_locale=ZH, text=zl, style=style), zh)
    if en_ok and el and el.lower() != en.lower() and not CJK.search(el) and SequenceMatcher(None, el.lower(), en.lower()).ratio() >= 0.4:
        out["naturalize_en"] = (Request(task="naturalize", target_locale=EN, text=el, style=style), en)
    if ann.get("zh_natural", 0) >= 5:
        out["unchanged_zh"] = (Request(task="naturalize", target_locale=ZH, text=zh, style=style), UNCHANGED)
    if ann.get("en_natural", 0) >= 5:
        out["unchanged_en"] = (Request(task="naturalize", target_locale=EN, text=en, style=style), UNCHANGED)
    return out


# A learned preference the target itself shows: personalization rows teach "follow the profile when
# it fits", with the human target as the proof it fits.
PREFS = [("啥", "什么", "prefers 啥 over 什么 in casual chat"), ("咋", "怎么", "prefers 咋 over 怎么 in casual chat"),
         ("挺", "很", "prefers 挺 over 很 when natural"), ("俩", "两个", "prefers 俩 over 两个")]


def p13n_from(req, target):
    """A fill row whose Chinese target uses a colloquial variant, with that preference in the profile."""
    if req.task != "fill" or req.target != "zh":
        return None
    for word, _, line in PREFS:
        if word in target:
            r = Request.from_dict(req.to_dict())
            r.personalization = Personalization(profile=[line])
            return r, target
    return None


def assemble(args):
    anns = [json.loads(l) for l in open(os.path.join(WORK, "annotations.jsonl"), encoding="utf-8")]
    random.Random(2).shuffle(anns)
    counts = {k: 0 for k in QUOTA}
    rows = []
    for a in anns:
        options = rows_for(a)
        # Each human pair feeds one slice, the one furthest below its quota: more distinct sentences.
        open_slices = [s for s in options if counts[s] < QUOTA[s]]
        if not open_slices:
            continue
        s = min(open_slices, key=lambda k: counts[k] / QUOTA[k])
        req, target = options[s]
        extra = p13n_from(req, target) if counts["p13n"] < QUOTA["p13n"] else None
        if extra:
            req, target, s = extra[0], extra[1], "p13n"
        validate(req)
        if target != UNCHANGED and check(req, target):
            continue
        training_row(req, target)  # raises if the target can't follow the pre-fill
        counts[s] += 1
        rows.append({"id": f"{a['id']}-{s}", "slice": s, "family": a["id"], "source_id": "tatoeba-cmn-eng",
                     "attribution": a["attribution"], "teacher": a.get("teacher"), "teacher_role": "span, fragment, degraded input, naturalness filter",
                     "target_provisional": req.target == "zh", "request": req.to_dict(), "target": target})
    with open(args.out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{len(rows)} rows → {args.out}")
    for k in QUOTA:
        print(f"  {k:15} {counts[k]:4}/{QUOTA[k]}")
    audit = os.path.splitext(args.out)[0] + "_audit.tsv"
    with open(audit, "w", encoding="utf-8") as f:
        f.write("id\tslice\tinput\ttarget\tok (y/n)\tnote\n")
        for r in random.Random(3).sample(rows, min(200, len(rows))):
            q = r["request"]
            inp = f"{q['before']}[{q['fragment']}]{q['after']}" if q["task"] == "fill" else q["text"]
            f.write(f"{r['id']}\t{r['slice']}\t{inp}\t{r['target']}\t\t\n")
    print(f"audit sample (200, handoff §23 step 5) → {audit}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["select", "annotate", "assemble"])
    ap.add_argument("--server", default="http://127.0.0.1:8080")
    ap.add_argument("--teacher", default="teacher")
    ap.add_argument("--parallel", type=int, default=4)
    ap.add_argument("--limit", type=int, default=3000)
    ap.add_argument("--out", default=os.path.join(HERE, "pilot_v0.jsonl"))
    args = ap.parse_args()
    {"select": select, "annotate": annotate, "assemble": assemble}[args.stage](args)


if __name__ == "__main__":
    main()
