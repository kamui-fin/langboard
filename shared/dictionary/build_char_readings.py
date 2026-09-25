#!/usr/bin/env python3
"""Builds the pinyin tables the keyboard reads Chinese with (dictionary/Readings.kt):

  assets/char_pinyin.txt  one line per character, "字<TAB>zi4": the reading it takes most often
                          across CC-CEDICT's words (行 is xing2, not hang2; particles fixed by hand)
  assets/word_pinyin.txt  "银行<TAB>yin2 hang2": every common 2–4 character word whose reading isn't
                          just its characters' own (so 银行, 睡觉, 便宜 read right)

Scored by shared/eval/pinyin_eval.py (96% of characters on the gold set, about what pypinyin gets).
CC-CEDICT is CC BY-SA 4.0 (attributed in the app).

    python3 shared/dictionary/build_char_readings.py cedict_ts.u8
"""
import collections
import os
import re
import sys

LINE = re.compile(r"^(\S+) (\S+) \[([^\]]+)\] /")
FULL = re.compile(r"^(\S+) (\S+) \[([^\]]+)\] /(.*)/$")
SYL = re.compile(r"^[a-zA-Zu:ü]+[1-5]$")


# Where dictionary words and everyday chat disagree: particles and the commonest function words.
OVERRIDES = {
    "了": "le5", "的": "de5", "得": "de5", "着": "zhe5", "呢": "ne5", "吧": "ba5", "啊": "a5", "都": "dou1",
    "还": "hai2", "为": "wei4", "只": "zhi3", "干": "gan4", "儿": "er2", "地": "de5", "哦": "o4", "嘛": "ma5",
    "呀": "ya5", "啦": "la5", "么": "me5", "吗": "ma5",
}


def is_hanzi(c):
    return "一" <= c <= "鿿" or "㐀" <= c <= "䶿"


def main():
    counts = collections.defaultdict(collections.Counter)
    for raw in open(sys.argv[1], encoding="utf-8"):
        m = LINE.match(raw)
        if not m:
            continue
        simp, pinyin = m.group(2), m.group(3).split()
        if len(simp) != len(pinyin) or not all(SYL.match(p) for p in pinyin):
            continue
        # Surnames and proper nouns (capitalised pinyin) have unusual readings.
        proper = any(p[0].isupper() for p in pinyin)
        for c, p in zip(simp, pinyin):
            if is_hanzi(c) and not proper:
                counts[c][p.lower().replace("u:", "v")] += 1 if len(simp) > 1 else 3
    out = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "android", "app", "src", "main", "assets", "char_pinyin.txt")
    with open(out, "w", encoding="utf-8") as f:
        for c in sorted(counts):
            f.write(f"{c}\t{OVERRIDES.get(c) or counts[c].most_common(1)[0][0]}\n")
    print(f"{len(counts)} characters → {out} ({os.path.getsize(out) // 1024} KB)")
    table = {c: OVERRIDES.get(c) or counts[c].most_common(1)[0][0] for c in counts}
    words = {}
    for raw in open(sys.argv[1], encoding="utf-8"):
        m = FULL.match(raw.strip())
        if not m:
            continue
        simp, pinyin, senses = m.group(2), m.group(3), m.group(4).split("/")
        if not 2 <= len(simp) <= 4 or not all(is_hanzi(c) for c in simp) or pinyin[:1].isupper():
            continue
        if all(x.startswith(("variant of", "old variant of")) for x in senses):
            continue
        syl = pinyin.lower().replace("u:", "v").split()
        if len(syl) != len(simp) or not all(re.fullmatch(r"[a-zv]+[1-5]", x) for x in syl):
            continue
        # The everyday entry: most senses (as DictionarySearch.best).
        if simp in words and words[simp][1] >= len(senses):
            continue
        words[simp] = (" ".join(syl), len(senses))
    out = out.replace("char_pinyin", "word_pinyin")
    kept = 0
    with open(out, "w", encoding="utf-8") as f:
        for w in sorted(words):
            syl = words[w][0].split()
            if any(table.get(c) != x for c, x in zip(w, syl)):
                f.write(f"{w}\t{words[w][0]}\n")
                kept += 1
    print(f"{kept} words → {out} ({os.path.getsize(out) // 1024} KB)")


if __name__ == "__main__":
    main()
