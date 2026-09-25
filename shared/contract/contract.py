"""Langboard task contract lb1: one request schema, rendered the same way for training, host eval and phone.

A request is model-agnostic and is the canonical inference object: training rows, eval cases, the
Android/iOS engine calls and feedback records all carry it. `user_text` turns it into the user turn;
`render` wraps that in a model family's chat template and appends the pre-fill; `parse` turns the
model's completion back into the answer the keyboard shows; `check` runs the mechanical output rules
the insertion layer enforces. `training_row` builds an SFT row from a request and its human target,
with the pre-fill marked so the loss covers only what follows.

Stdlib only, so the same file runs on the training box, in the eval harness and in tests.
See CONTRACT.md for the rules this code implements.
"""

from dataclasses import dataclass, field, asdict
import re

VERSION = "lb1"

TASKS = ("fill", "naturalize")
LOCALES = ("en-US", "zh-Hans-CN")  # V1 only; Taiwan/HK are later, separately evaluated variants
REGISTERS = ("casual_friend", "casual_neutral", "work_chat", "professional")
SLANG = ("low", "medium")
VERBOSITY = ("concise", "balanced", "expressive")
DIRECTNESS = ("soft", "balanced", "direct")
SPEAKERS = ("them", "me")

UNCHANGED = "UNCHANGED"
GAP_OPEN, GAP_CLOSE = "⟦", "⟧"
MAX_CONTEXT_CHARS = 240
MAX_PROFILE = 6
MAX_EXAMPLES = 3

# Chat templates of the three bakeoff candidates. The pre-fill goes right after the assistant header.
# Qwen3.5 thinks only when asked; its template writes an empty think block for non-thinking turns,
# so we write the same block (training rows must match what the template produces).
FAMILIES = {
    "hymt": ("<｜hy_begin▁of▁sentence｜><｜hy_User｜>", "<｜hy_Assistant｜>"),
    "qwen35": ("<|im_start|>user\n", "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"),
    # TranslateGemma's own template only takes translation requests; tuned use goes through the raw
    # Gemma control tokens, which its model card allows.
    "gemma": ("<bos><start_of_turn>user\n", "<end_of_turn>\n<start_of_turn>model\n"),
}


@dataclass
class Style:
    register: str = "casual_friend"
    slang: str = "low"
    verbosity: str = "balanced"
    directness: str = "balanced"

    def text(self):
        return f"{self.register} slang:{self.slang} verbosity:{self.verbosity} directness:{self.directness}"


@dataclass
class Personalization:
    profile: list = field(default_factory=list)   # learned style-profile lines ("prefers 啥 over 什么")
    examples: list = field(default_factory=list)  # retrieved user-approved sentences


@dataclass
class Request:
    task: str
    target_locale: str                    # language of the answer
    source_locale: str = ""               # fill only: language of the fragment
    before: str = ""                      # fill only
    fragment: str = ""                    # fill only
    after: str = ""                       # fill only
    text: str = ""                        # naturalize only
    style: Style = field(default_factory=Style)
    conversation_context: list = field(default_factory=list)  # [["them" | "me", "..."]], oldest first
    personalization: Personalization = field(default_factory=Personalization)

    @staticmethod
    def from_dict(d):
        d = dict(d)
        d["style"] = Style(**d.get("style", {}))
        d["personalization"] = Personalization(**d.get("personalization", {}))
        return Request(**d)

    def to_dict(self):
        return asdict(self)

    @property
    def target(self):
        return self.target_locale.split("-")[0]


class ContractError(ValueError):
    pass


def validate(r):
    s = r.style
    for value, allowed, name in ((r.task, TASKS, "task"), (r.target_locale, LOCALES, "target_locale"),
                                 (s.register, REGISTERS, "register"), (s.slang, SLANG, "slang"),
                                 (s.verbosity, VERBOSITY, "verbosity"), (s.directness, DIRECTNESS, "directness")):
        if value not in allowed:
            raise ContractError(f"{name} {value!r} not in {allowed}")
    if r.task == "fill":
        if r.source_locale not in LOCALES or r.source_locale == r.target_locale:
            raise ContractError(f"fill needs a source locale other than the target, got {r.source_locale!r}")
        if not r.fragment.strip():
            raise ContractError("fill needs a fragment")
        if r.text:
            raise ContractError("fill takes before/fragment/after, not text")
    else:
        if not r.text.strip():
            raise ContractError("naturalize needs text")
        if r.source_locale or r.before or r.fragment or r.after:
            raise ContractError("naturalize takes text only")
    for who, _ in r.conversation_context:
        if who not in SPEAKERS:
            raise ContractError(f"conversation speaker {who!r}")
    p = r.personalization
    if len(p.profile) > MAX_PROFILE or len(p.examples) > MAX_EXAMPLES:
        raise ContractError(f"at most {MAX_PROFILE} profile lines and {MAX_EXAMPLES} examples")


# Everything the user or their contacts wrote is data. Anything shaped like one of our tags or the gap
# markers is defused before it enters the prompt, so a message cannot close <chat> and open <task>.
_TAG = re.compile(r"<(/?)(task|lang|style|profile|examples|chat|draft|text)>", re.I)


def defuse(s):
    s = _TAG.sub(lambda m: f"‹{m.group(1)}{m.group(2)}›", s)
    return s.replace(GAP_OPEN, "[").replace(GAP_CLOSE, "]")


def one_line(s):
    return " ".join(defuse(s).split())


def recent_context(context, max_chars=MAX_CONTEXT_CHARS):
    """Newest lines that fit in max_chars, oldest first. A line that does not fit whole is dropped."""
    kept, used = [], 0
    for who, line in reversed(context):
        line = one_line(line)
        if used + len(line) > max_chars:
            break
        kept.append(f"{who}: {line}")
        used += len(line)
    return kept[::-1]


def user_text(r):
    """The user turn. Field order is fixed; empty optional fields are left out entirely."""
    validate(r)
    lang = f"{r.source_locale}→{r.target_locale}" if r.task == "fill" else r.target_locale
    out = [f"<task>{r.task}</task>", f"<lang>{lang}</lang>", f"<style>{r.style.text()}</style>"]
    p = r.personalization
    if p.profile:
        out.append("<profile>\n" + "\n".join(f"- {one_line(x)}" for x in p.profile) + "\n</profile>")
    if p.examples:
        out.append("<examples>\n" + "\n".join(f"- {one_line(x)}" for x in p.examples) + "\n</examples>")
    context = recent_context(r.conversation_context)
    if context:
        out.append("<chat>\n" + "\n".join(context) + "\n</chat>")
    if r.task == "fill":
        draft = defuse(r.before) + GAP_OPEN + defuse(r.fragment.strip()) + GAP_CLOSE + defuse(r.after)
        out.append(f"<draft>{draft}</draft>")
    else:
        out.append(f"<text>{defuse(r.text)}</text>")
    return "\n".join(out)


def prefill(r):
    """What the assistant turn starts with. Fill continues the draft from the text before the gap;
    trailing spaces are left for the model, because BPE vocabularies attach a space to the next word."""
    return defuse(r.before).rstrip() if r.task == "fill" else ""


def render(r, family):
    head, tail = FAMILIES[family]
    return head + user_text(r) + tail + prefill(r)


def assistant_target(r, answer):
    """The whole assistant turn for a gold answer: the completed sentence for fill, the rewrite or
    UNCHANGED for naturalize."""
    if r.task != "fill":
        return answer
    before, after = defuse(r.before), defuse(r.after)
    if r.target != "en":
        return before + answer.strip() + after
    sep_l = "" if not before or before[-1].isspace() else " "
    sep_r = "" if not after or after[0].isspace() or after[0] in ",.!?;:'’)\"" else " "
    return before + sep_l + answer.strip() + sep_r + after


def training_row(r, answer, family=None):
    """One SFT row. `prompt` is everything the loss ignores (including the pre-fill); `completion` is
    what the loss covers, ending with the text after the gap so the model learns to lead into it."""
    full = assistant_target(r, answer)
    pre = prefill(r)
    assert full.startswith(pre), (full, pre)
    messages = [{"role": "user", "content": user_text(r)}, {"role": "assistant", "content": full}]
    row = {"version": VERSION, "messages": messages, "prefill_chars": len(pre), "completion": full[len(pre):]}
    if family:
        row["prompt"] = render(r, family)
    return row


def parse(r, completion):
    """The answer the keyboard shows. For fill, `completion` is what the model wrote after the pre-fill
    and the answer is the part before the text after the gap; None means the model never reached that
    text (the beam wandered off) and the option should be dropped. For naturalize, returns UNCHANGED
    when the model said so or returned the input unchanged."""
    if r.task == "naturalize":
        out = completion.strip()
        if out == UNCHANGED or out == r.text.strip():
            return UNCHANGED
        return out
    text = completion
    after = defuse(r.after).strip()
    if after:
        i = gap_end(text, after, r.target == "en")
        if i < 0:
            return None
        text = text[:i]
    return text.strip() or None


def gap_end(text, after, en):
    """Where the text after the gap begins in a continuation: the last point from which the rest of
    the continuation starts with that text, or is its start (decoding stops a few characters in).
    A partial match needs two characters, so 去不了了 before 了吧 does not cut after 去不了.
    English compares case-insensitively and only at word starts."""
    t, a = (text.lower(), after.lower()) if en else (text, after)
    need = min(2, len(a))
    for i in range(len(t) - 1, -1, -1):
        rest = t[i:].rstrip()
        if not rest or (en and i > 0 and t[i - 1].isalnum() and a[:1].isalnum()) or rest[0].isspace():
            continue
        if rest.startswith(a) or (a.startswith(rest) and len(rest) >= need):
            return i
    return -1


LATIN = re.compile(r"[A-Za-z]+")
# Latin letters in a Chinese answer are fine when Chinese really uses them: an all-caps acronym (AI, CC,
# PPT, AA制, K歌) or one of these lowercase loans (有点emo). Anything else (ghost了, siempre) is a leak.
ZH_LATIN_OK = {"emo", "ok", "app", "wifi", "cc", "vlog", "pdf", "logo"}


def zh_latin_ok(word):
    return (word.isupper() and len(word) <= 5) or word.lower() in ZH_LATIN_OK
CJK = re.compile(r"[㐀-鿿豈-﫿]")


def too_long(r, answer):
    """A fill answer replaces one gap; a naturalize repair stays close to the original's length."""
    if r.task == "naturalize":
        return len(answer) > 2 * len(r.text.strip()) + 10
    if r.target == "zh":
        return len(answer) > max(16, len(r.fragment.strip()))
    return len(answer.split()) > max(10, 4 * len(CJK.findall(r.fragment)))


def check(r, answer):
    """Mechanical rules every output must pass before it may be inserted. Returns a list of
    violations (empty = fine)."""
    if answer is None or not answer.strip():
        return ["empty"]
    problems = []
    if "\n" in answer:
        problems.append("multiline")
    if r.task == "naturalize" and answer == UNCHANGED:
        return problems
    if too_long(r, answer):
        problems.append("too_long")
    if r.target == "zh" and not all(zh_latin_ok(w) for w in LATIN.findall(answer)):
        problems.append("latin_in_zh")
    if r.target == "en" and CJK.search(answer):
        problems.append("cjk_in_en")
    if answer[:1] in "\"'“‘「『" or answer[-1:] in "\"'”’」』":
        problems.append("quoted")
    if r.task == "fill" and r.target == "zh":
        b, a = r.before.rstrip(), r.after.lstrip()
        # 统一 + 一下 is fine; 一 starts and ends too many words to count as a repeat.
        if b and answer.startswith(b[-1]) and b[-1] != "一":
            problems.append("repeats_before")
        # 去不了 + 了 is fine: the first 了 is liǎo (不了/得了), the second the particle.
        if a and answer.endswith(a[0]) and a[0] != "一" \
                and not (a[0] == "了" and answer[-2:] in ("不了", "得了")):
            problems.append("repeats_after")
    if r.task == "fill" and r.target == "en":
        bw, aw = r.before.split()[-1:], r.after.split()[:1]
        words = answer.split()
        if bw and words[0].lower() == bw[0].lower():
            problems.append("repeats_before")
        if aw and words[-1].lower().strip(",.!?") == aw[0].lower().strip(",.!?"):
            problems.append("repeats_after")
    return problems
