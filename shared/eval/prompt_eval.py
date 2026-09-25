#!/usr/bin/env python3
"""Runs the eval set against the phone's prompts and decoding, on this computer.

Renders prompts from android/app/src/main/assets/prompts.json exactly as PromptBook.kt does, runs them
through hymt_eval/ (the same C++ engine the phone uses, built for the host), cleans the answers
the way HyMtPrompts.cleanFill does, and reports how often an acceptable answer is first, and in the
top N.

    python3 shared/eval/prompt_eval.py                         # fill eval, default prompts
    python3 shared/eval/prompt_eval.py --prompts my.json -v    # a variant, every candidate shown
    python3 shared/eval/prompt_eval.py --task check|explain    # the other two jobs, printed for reading

To try a variant on the phone without rebuilding, push it where the app looks first:

    adb push my.json /sdcard/Android/data/app.langboard/files/prompts.json

The model defaults to ~/models/hymt2/hymt2-1.8b-1.25bit-tq2.gguf (the file the phone runs,
produced by model/convert_hymt_gguf.py); pass --model to use another.
"""

import argparse
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))  # shared/eval: the cases live here
ANDROID = os.path.join(os.path.dirname(os.path.dirname(HERE)), "android")
EVAL_DIR = os.path.join(HERE, "hymt_eval")
BINARY = os.path.join(EVAL_DIR, "build", "hymt_eval")


def build():
    if not os.path.isdir(os.path.join(ANDROID, "third_party", "llama.cpp")):
        sys.exit("llama.cpp is missing: run android/tools/fetch_llama_cpp.sh first")
    subprocess.run(["cmake", "-S", EVAL_DIR, "-B", os.path.join(EVAL_DIR, "build"),
                    "-DCMAKE_BUILD_TYPE=Release", "-DGGML_NATIVE=ON"], check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["cmake", "--build", os.path.join(EVAL_DIR, "build"), "-j", str(os.cpu_count()),
                    "--target", "hymt_eval"], check=True, stdout=subprocess.DEVNULL)


# ------------------------------------------------------------------ rendering (mirrors PromptBook.kt)

def fill_vars(template, values):
    out = template
    for k, v in values.items():
        out = out.replace("{" + k + "}", v)
    return out


def recent(lines, speakers, max_chars):
    """The chat nearest the field, oldest first, cut to whole lines within max_chars."""
    out, used = [], 0
    for who, text in reversed(lines):
        text = text.strip()
        if not text:
            continue
        line = speakers.get(who, "") + text
        if used + len(line) > max_chars and out:
            break
        out.append(line)
        used += len(line) + 1
    return "\n".join(reversed(out))


def render_fill(book, case):
    """The fill prompt. In the "continue" format it ends with the Chinese before the gap already
    written as the model's answer, so the model carries on the sentence from there."""
    f = book["fill"]
    chat = recent(case.get("chat", []), book["speakers"], f["screen_chars"])
    style = book["styles"][case["register"]]
    if f.get("format") == "continue":
        draft = (case["before"] + case["fragment"] + case["after"]).strip()
        user = (fill_vars(f["template"], {"context": fill_vars(f["context"], {"chat": chat}), "style": style, "draft": draft})
                if chat else fill_vars(f["template_no_context"], {"style": style, "draft": draft}))
        return fill_vars(book["chat_template"], {"user": user}) + prefill(case["before"])
    draft = (case["before"] + f["mark_open"] + case["fragment"] + f["mark_close"] + case["after"]).strip()
    context = fill_vars(f["context"] if chat else f["context_no_chat"], {"chat": chat, "draft": draft})
    user = fill_vars(f["template"], {"context": context, "style": style, "fragment": case["fragment"].strip()})
    return fill_vars(book["chat_template"], {"user": user})


# ------------------------------------------------------------------ continue format (mirrors FillContinuation.kt)

# Any of these in a token ends an answer: the phrase in the gap is over.
PUNCT = "，。！？、；：,.!?;:…~～"


def prefill(before):
    return before.rstrip()


def stops(after):
    """Text that ends a hypothesis: any punctuation, or the first two characters after the gap once
    written (one character is too little: 受不了 already contains the 了 that follows the gap)."""
    a = after.lstrip()
    return list(PUNCT), (a[:2] if len(a) >= 2 and a[0] not in PUNCT else "")


def gap_of(output, after):
    """The part of a continuation that fills the gap: up to the last point where the rest of it is
    the start of the text after the gap (or the text after the gap is its start), else up to the
    first punctuation. None when nothing was written before the text after the gap."""
    s = output.split("\n")[0].rstrip(PUNCT + " ")
    a = after.strip()
    if a and s.startswith(a[0]) and (a.startswith(s) or s.startswith(a)):
        return None
    if a:
        for i in range(len(s) - 1, 0, -1):
            if s[i] == a[0] and (a.startswith(s[i:]) or s[i:].startswith(a)):
                return s[:i].strip() or None
    cut = next((i for i, c in enumerate(s) if c in PUNCT and i > 0), -1)
    return (s[:cut] if cut > 0 else s).strip() or None


def leads_on(text, gap, after):
    """Whether a continuation goes on from its answer into the text after the gap: what follows the
    answer is the start of that text, that text is its start, or both are empty but for punctuation."""
    s = text.split("\n")[0].rstrip(PUNCT + " ")
    rest = s[s.find(gap) + len(gap):].strip() if gap in s else None
    a = after.strip().rstrip(PUNCT)
    if rest is None:
        return False
    return (a.startswith(rest) and (rest or not a)) or (bool(a) and rest.startswith(a)) or (not a and not rest)


def after_head(after, n):
    """The start of the text after the gap that each option is scored with: up to n characters,
    ending at the first sentence end."""
    a = after.lstrip()[:n]
    for i, c in enumerate(a):
        if c in "。！？!?":
            return a[:i + 1]
    return a


def render_explain(book, case):
    e = book["explain"]
    chat = recent(case.get("chat", []), e["speakers"], e["screen_chars"])
    if chat:
        user = fill_vars(e["template"], {"context": fill_vars(e["context"], {"chat": chat}), "message": case["message"]})
    else:
        user = fill_vars(e["template_no_context"], {"message": case["message"]})
    return fill_vars(book["chat_template"], {"user": user})


def render_check(book, case):
    c = book["check"]
    chat = recent(case.get("chat", []), book["speakers"], c["screen_chars"])
    style = book["styles"][case.get("register", "neutral")]
    if chat:
        user = fill_vars(c["template"], {"context": fill_vars(c["context"], {"chat": chat}), "style": style,
                                         "sentence": case["sentence"]})
    else:
        user = fill_vars(c["template_no_context"], {"style": style, "sentence": case["sentence"]})
    return fill_vars(book["chat_template"], {"user": user})


# ------------------------------------------------------------------ clean-up (mirrors HyMtPrompts.cleanFill)

def is_cjk(c):
    return "一" <= c <= "鿿" or "㐀" <= c <= "䶿"


def clean_fill(out, before, after, chat=(), fragment="", trim_after=True):
    s = next((l.strip() for l in out.strip().splitlines() if l.strip()), "")
    b, a = before.strip(), after.strip()
    if b and s.startswith(b) and s[len(b):].strip():
        s = s[len(b):].strip()
    bb = before.rstrip()
    for n in range(min(len(s) - 1, len(bb)), 0, -1):
        if bb.endswith(s[:n]):
            s = s[n:]
            break
    if trim_after and a and s.endswith(a) and s[:-len(a)].strip():
        s = s[:-len(a)].strip()
    aa = after.lstrip()
    for n in range(min(len(s) - 1, len(aa)), 0, -1) if trim_after else ():
        if aa.startswith(s[len(s) - n:]):
            s = s[:len(s) - n]
            break
    s = s.strip().strip("“”\"「」【】")
    if after.strip() or before.strip():
        s = s.rstrip("。")
    if not any(is_cjk(c) for c in s):
        return None
    # The sentence around the gap, echoed back, isn't an answer.
    bare = "".join(c for c in s if c.isalnum())
    if len(bare) >= 3 and ("".join(c for c in before if c.isalnum()).endswith(bare)
                           or "".join(c for c in after if c.isalnum()).startswith(bare)):
        return None
    # English copied from the prompt isn't an answer.
    if sum(c.isascii() and c.isalpha() for c in s) > 2 and any(len(w) > 2 and w.lower() in s.lower() for w in fragment.split(" ")):
        return None
    # A line copied back from the chat isn't an answer.
    for line in chat:
        t = line.strip()
        if len(t) >= 4 and (t in s or (len(s) >= 6 and s in t)):
            return None
    return s


def distinct(xs):
    seen, out = set(), []
    for x in xs:
        if x and x not in seen:
            seen.add(x)
            out.append(x)
    return out


# ------------------------------------------------------------------ fill pipeline

class Model:
    """hymt_eval kept running, one request at a time, so each case reuses its cached prompt."""

    def __init__(self, path):
        self.proc = subprocess.Popen([BINARY, path], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")

    def __call__(self, **req):
        self.proc.stdin.write(json.dumps(req, ensure_ascii=False) + "\n")
        self.proc.stdin.flush()
        return json.loads(self.proc.stdout.readline())

    def close(self):
        self.proc.stdin.close()
        self.proc.wait()


def rank(pinned, scored):
    """[(answer, share)], [pinned] first, the rest likeliest first. [scored] maps answer → probability."""
    order = distinct(([pinned] if pinned in scored else []) + sorted(scored, key=scored.get, reverse=True))
    total = sum(scored[a] for a in order[:10])
    return [(a, scored[a] / total if total else 0.0) for a in order[:10]]


def fill_case(book, model, case):
    """What the app would show for [case]: [(answer, share)] best first, and the time taken."""
    import math
    f = book["fill"]
    prompt = render_fill(book, case)
    chat = [t for _, t in case.get("chat", [])]
    clean = lambda out: clean_fill(out, case["before"], case["after"], chat, case["fragment"])
    ms = 0
    if f.get("format") != "continue":
        g = model(prompt=prompt, mode="greedy", max_tokens=f["max_tokens"], repeat_penalty=1.0, stops=["\n", "。", "！", "？"])
        b = model(prompt=prompt, mode="beams", beams=f["beams"], max_tokens=f["max_tokens"], length_alpha=f["length_alpha"])
        ms = g["ms"] + b["ms"]
        mass = {}
        seen = set()
        for text, lp in [(g["text"], g["logprob"])] + [(c["text"], c["logprob"]) for c in b["candidates"]]:
            if text.strip() in seen:
                continue
            seen.add(text.strip())
            a = clean(text)
            if a:
                mass[a] = mass.get(a, 0.0) + math.exp(lp)
        return rank(clean(g["text"]), mass), ms, None

    # The gap is already cut from the text after it; 受不了 before 了 keeps its own 了.
    clean = lambda out: clean_fill(out, case["before"], case["after"], chat, case["fragment"], trim_after=False)
    st, stop_text = stops(case["after"])
    # Room for the answer and the start of the text after it.
    budget = f["max_tokens"] + len(after_head(case["after"], f["after_chars"]))
    g = model(prompt=prompt, mode="greedy", max_tokens=budget, repeat_penalty=1.0, ban_latin=True, stops=st, stop_text=stop_text)
    b = model(prompt=prompt, mode="beams", beams=f["beams"], max_tokens=budget, length_alpha=f["length_alpha"],
              ban_latin=True, stops=st, stop_text=stop_text, keep_unfinished=True)
    ms = g["ms"] + b["ms"]
    first = clean(gap_of(g["text"], case["after"]) or "")
    options = distinct([first] + [clean(gap_of(c["text"], case["after"]) or "") for c in b["candidates"]])[:f["rescore"] + 1]
    if not options:
        return [], ms, None
    if f["rescore"] == 0:
        # No scoring pass: each answer's probability is that of the beams that wrote it and then
        # went on into the text actually after the gap (or ended the sentence when there is none).
        mass, loose = {}, {}
        for text, lp in [(c["text"], c["logprob"]) for c in b["candidates"]] + [(g["text"], g["logprob"])]:
            gap = gap_of(text, case["after"])
            a = clean(gap or "")
            if not a:
                continue
            target = mass if leads_on(text, gap, case["after"]) else loose
            target[a] = max(target.get(a, 0.0), math.exp(lp))
        return rank(first if f["pin_first"] else None, mass or loose), ms, {"greedy": g["text"], "beams": [c["text"] for c in b["candidates"]]}
    head = after_head(case["after"], f["after_chars"])
    # The whole text after the gap, or none: the sentence ends there.
    tail = head + (f["end_mark"] if head == case["after"].strip() else "")
    sc = model(prompt=prompt, mode="score", continuations=[o + tail for o in options])
    ms += sc["ms"]
    top = max(sc["logprobs"])
    scored = {o: math.exp(lp - top) for o, lp in zip(options, sc["logprobs"])}
    return rank(first if f["pin_first"] else None, scored), ms, {"greedy": g["text"], "beams": [c["text"] for c in b["candidates"]]}


def is_latin(c):
    return c.isascii() and c.isalpha()


def judge(case, answers):
    good, bad = case["good"], case.get("bad", [])
    # An acceptable phrase, with at most a few characters around it (了, 我, 啊…).
    hit = lambda a: any(g in a and len(a) <= len(g) + 3 for g in good)
    first = answers[0] if answers else None
    b, a = case["before"].rstrip(), case["after"].lstrip()
    return {
        "first": bool(first) and hit(first),
        "top3": any(hit(x) for x in answers[:3]),
        "top10": any(hit(x) for x in answers),
        "bad": bool(first) and not hit(first) and any(x in first for x in bad),
        "leak": bool(first) and not hit(first) and any(is_latin(c) for c in first),
        "dup": bool(first) and bool((b and is_cjk(b[-1]) and first[0] == b[-1]) or (a and is_cjk(a[0]) and first[-1] == a[0])),
        "none": not answers,
    }


def run_fill(args, book):
    sets = {"dev": "fill.jsonl", "locked": "fill_locked.jsonl"}
    if args.set == "locked" and not args.i_mean_it:
        sys.exit("The locked set is for release and baseline reports only; add --i-mean-it.")
    cases = [json.loads(l) for l in open(os.path.join(HERE, sets[args.set]), encoding="utf-8") if l.strip()]
    if args.only:
        cases = [c for c in cases if args.only in c["id"]]
    model = Model(args.model)
    started = time.time()
    rows = []
    for c in cases:
        answers, ms, raw = fill_case(book, model, c)
        j = judge(c, [a for a, _ in answers])
        rows.append({"id": c["id"], "bins": c["bins"], "answers": answers, "ms": ms, **j})
        mark = "✓" if j["first"] else ("~" if j["top10"] else "✗")
        print(f"{mark} {c['id']:28} {c['before']}[{c['fragment']}]{c['after']}  {ms:5}ms  "
              + " / ".join(f"{a} {p:.0%}" for a, p in answers[:6]))
        if args.verbose and raw:
            print("      greedy:", raw["greedy"].replace("\n", "⏎"), "| beams:", " / ".join(x.replace("\n", "⏎") for x in raw["beams"][:6]))
    model.close()

    n = len(rows)
    pct = lambda k, rs: f"{sum(r[k] for r in rs)}/{len(rs)}"
    print(f"\n{book.get('version', '?')} on {args.set} ({os.path.basename(args.model)}): "
          f"first {pct('first', rows)}, top 3 {pct('top3', rows)}, top 10 {pct('top10', rows)}; "
          f"prohibited first {pct('bad', rows)}, English first {pct('leak', rows)}, doubled first {pct('dup', rows)}, "
          f"no answer {pct('none', rows)}; median {sorted(r['ms'] for r in rows)[n // 2]} ms; {time.time() - started:.0f}s")
    bins = sorted({b for r in rows for b in r["bins"]})
    print("  " + "  ".join(f"{b} {pct('first', [r for r in rows if b in r['bins']])}" for b in bins))
    if args.out:
        json.dump({"version": book.get("version"), "model": os.path.basename(args.model), "set": args.set, "rows": rows},
                  open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


# ------------------------------------------------------------------ main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompts", default=os.path.join(ANDROID, "app", "src", "main", "assets", "prompts.json"))
    ap.add_argument("--model", default=os.path.expanduser("~/models/hymt2/hymt2-1.8b-1.25bit-tq2.gguf"))
    ap.add_argument("--task", choices=["fill", "explain", "check"], default="fill")
    ap.add_argument("--set", choices=["dev", "locked"], default="dev", help="fill cases: dev (fill.jsonl) or locked (fill_locked.jsonl)")
    ap.add_argument("--i-mean-it", action="store_true", help="allow the locked set")
    ap.add_argument("--only", help="fill cases whose id contains this")
    ap.add_argument("--out", help="write per-case results as JSON (for shared/eval/rating_sheet.py)")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if not os.path.exists(BINARY):
        build()
    book = json.load(open(args.prompts, encoding="utf-8"))
    if args.task == "fill":
        return run_fill(args, book)
    cases = [json.loads(l) for l in open(os.path.join(HERE, f"{args.task}.jsonl"), encoding="utf-8") if l.strip()]

    requests = []
    for c in cases:
        if args.task == "check":
            k = book["check"]
            requests.append({"prompt": render_check(book, c), "mode": "beams", "beams": k["beams"],
                             "max_tokens": k["max_tokens"], "length_alpha": k["length_alpha"]})
        else:
            e = book["explain"]
            requests.append({"prompt": render_explain(book, c), "mode": "greedy", "max_tokens": e["max_tokens"],
                             "repeat_penalty": e["repeat_penalty"]})

    proc = subprocess.run([BINARY, args.model], input="\n".join(json.dumps(r, ensure_ascii=False) for r in requests) + "\n",
                          capture_output=True, text=True, encoding="utf-8")
    if proc.returncode != 0:
        sys.exit(proc.stderr)
    for c, r in zip(cases, [json.loads(l) for l in proc.stdout.splitlines() if l.strip()]):
        if args.task == "check":
            answers = distinct(x["text"].strip() for x in r["candidates"])
            print(f"{c['sentence']}  →  " + " / ".join(answers) + f"   [{r['ms']}ms]")
        else:
            print(f"{c['message']}  →  {r['text'].strip()}   [{r['ms']}ms]")


if __name__ == "__main__":
    main()
