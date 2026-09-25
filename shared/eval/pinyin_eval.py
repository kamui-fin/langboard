#!/usr/bin/env python3
"""Scores pinyin methods against pinyin_gold.tsv (hand-labeled chat phrases, dense in
characters with more than one reading).

  table     each character's everyday reading alone (assets/char_pinyin.txt)
  bundled   what Readings.kt does without the dictionary download: longest-match words from
            assets/word_pinyin.txt, the table for the rest
  cedict    what Readings.kt does with CC-CEDICT installed: longest-match words, their everyday
            entry, the table for single characters
  pypinyin  the reference library, if installed

    python3 shared/eval/pinyin_eval.py cedict_ts.u8 [-v]

"strict" compares tones exactly; "lenient" lets a neutral tone (5) match its citation tone, since
dictionaries disagree on those (告诉 gào su / gào sù).
"""
import collections
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(os.path.dirname(HERE)), "android", "app", "src", "main", "assets")
LINE = re.compile(r"^(\S+) (\S+) \[([^\]]+)\] /(.*)/$")
MAX_WORD = 8


def is_hanzi(c):
    return "一" <= c <= "鿿" or "㐀" <= c <= "䶿"


def load_table():
    t = {}
    for l in open(os.path.join(ASSETS, "char_pinyin.txt"), encoding="utf-8"):
        c, p = l.rstrip("\n").split("\t")
        t[c] = p
    return t


def load_cedict(path):
    words = collections.defaultdict(list)  # hanzi -> [(pinyin, senses, proper, variant)]
    for raw in open(path, encoding="utf-8"):
        m = LINE.match(raw.strip())
        if not m:
            continue
        trad, simp, pinyin, senses = m.groups()
        senses = senses.split("/")
        variant = all(s.startswith(("variant of", "old variant of")) for s in senses)
        entry = (pinyin, len(senses), pinyin[:1].isupper(), variant)
        words[simp].append(entry)
        if trad != simp:
            words[trad].append(entry)
    return words


def best(entries):
    # DictionarySearch.best: a direct entry over a variant, a common word over a name, most senses.
    return sorted(entries, key=lambda e: (e[3], e[2], -e[1]))[0][0]


def by_table(text, table):
    return [table.get(c, "?") for c in text if is_hanzi(c)]


def segment_forward(text, is_word):
    out, i = [], 0
    while i < len(text):
        n = min(MAX_WORD, len(text) - i)
        while n > 1 and not is_word(text[i:i + n]):
            n -= 1
        out.append(text[i:i + n])
        i += n
    return out


def by_words(text, table, words):
    out = []
    for run in re.findall(r"[\u3400-\u4dbf\u4e00-\u9fff]+", text):
        for w in segment_forward(run, lambda x: x in words):
            syl = best(words[w]).lower().split() if len(w) > 1 else []
            if len(w) > 1 and len(syl) == len(w) and all(re.fullmatch(r"[a-zu:ü]+[1-5]", s) for s in syl):
                out += [s.replace("u:", "v") for s in syl]
            else:
                out += [table.get(c, "?") for c in w]
    return out


def load_bundled():
    words = {}
    for l in open(os.path.join(ASSETS, "word_pinyin.txt"), encoding="utf-8"):
        w, p = l.rstrip("\n").split("\t")
        words[w] = [(p, 1, False, False)]
    return words


def by_pypinyin(text):
    from pypinyin import Style, lazy_pinyin
    return [p for p, c in zip(lazy_pinyin(text, style=Style.TONE3, neutral_tone_with_five=True), text) if is_hanzi(c)]


def same(a, b, lenient):
    a, b = a.replace("ü", "v"), b.replace("ü", "v")
    if a == b:
        return True
    return lenient and a[:-1] == b[:-1] and "5" in (a[-1], b[-1])


def main():
    table = load_table()
    words = load_cedict(sys.argv[1])
    gold = [l.rstrip("\n").split("\t") for l in open(os.path.join(HERE, "pinyin_gold.tsv"), encoding="utf-8")]
    bundled = load_bundled()
    methods = {
        "table": lambda t: by_table(t, table),
        "cedict": lambda t: by_words(t, table, words),
        "bundled": lambda t: by_words(t, table, bundled),
    }
    try:
        import pypinyin  # noqa: F401
        methods["pypinyin"] = by_pypinyin
    except ImportError:
        pass
    total = sum(len(g.split()) for _, g in gold)
    for name, fn in methods.items():
        strict = lenient = 0
        wrong = []
        for text, g in gold:
            got, want = fn(text), g.split()
            for c, a, b in zip([c for c in text if is_hanzi(c)], got, want):
                strict += same(a, b, False)
                lenient += same(a, b, True)
                if not same(a, b, True):
                    wrong.append(f"{text}:{c} {a}≠{b}")
        print(f"{name:9} strict {strict}/{total} ({strict / total:.0%})  lenient {lenient}/{total} ({lenient / total:.0%})")
        if "-v" in sys.argv:
            print("   " + "  ".join(wrong))


if __name__ == "__main__":
    main()
