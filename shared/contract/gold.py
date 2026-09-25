"""Gold eval cases in the lb1 contract, and conversion from the pre-lb1 Fill gold files.

A case is a request plus what counts as right:
  {"id": "...", "slice": "fill_en_zh", "family": "rollerskate", "bins": ["pos"],
   "request": {...Request.to_dict()...}, "good": [...], "bad": [...], "reviewed": false}

Slices follow the handoff's frozen eval: fill_en_zh, fill_zh_en, naturalize_zh, naturalize_en,
unchanged, style, p13n. For naturalize cases `good` holds acceptable rewrites, or ["UNCHANGED"].
"""

import os

from contract import Request, Style, validate

SLICES = ("fill_en_zh", "fill_zh_en", "naturalize_zh", "naturalize_en", "unchanged", "style", "p13n")

# The shipped prompts' three styles. "formal" was described as 礼貌、正式的工作沟通, so it maps to work chat.
LEGACY_REGISTER = {"casual": "casual_friend", "neutral": "casual_neutral", "formal": "work_chat"}


def from_legacy_fill(case):
    """A case from shared/eval/fill.jsonl or fill_locked.jsonl, as an lb1 case."""
    req = Request(task="fill", source_locale="en-US", target_locale="zh-Hans-CN",
                  before=case["before"], fragment=case["fragment"], after=case["after"],
                  style=Style(register=LEGACY_REGISTER[case["register"]]),
                  conversation_context=[list(x) for x in case.get("chat", [])])
    validate(req)
    return {"id": case["id"], "slice": "fill_en_zh", "family": case["id"].split("-")[1],
            "bins": case["bins"], "request": req.to_dict(),
            "good": case["good"], "bad": case["bad"], "reviewed": case["reviewed"]}


def check_case(case):
    if case["slice"] not in SLICES:
        raise ValueError(f"{case['id']}: slice {case['slice']!r}")
    req = Request.from_dict(case["request"])
    validate(req)
    if not case["good"]:
        raise ValueError(f"{case['id']}: no acceptable answers")
    if case["slice"] == "unchanged" and case["good"] != ["UNCHANGED"]:
        raise ValueError(f"{case['id']}: unchanged cases accept only UNCHANGED")
    return req


EVAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "eval")
LEGACY = {"dev": "fill.jsonl", "locked": "fill_locked.jsonl"}


def load(split="dev"):
    """Every gold case of a split: the pre-lb1 EN→ZH Fill cases (converted) plus gold_v1/<split>.jsonl."""
    import json
    cases = []
    with open(os.path.join(EVAL, LEGACY[split]), encoding="utf-8") as f:
        cases += [from_legacy_fill(json.loads(line)) for line in f]
    with open(os.path.join(EVAL, "gold_v1", f"{split}.jsonl"), encoding="utf-8") as f:
        cases += [json.loads(line) for line in f]
    for c in cases:
        check_case(c)
    return cases
