#!/usr/bin/env python3
"""Builds app/src/main/assets/opendict.db, the keyboard's English -> Chinese quick-match index.

Source: Open Dictionary v2.0 (https://github.com/ahpxex/open-dictionary), `distribution.jsonl.gz`,
CC BY-SA 4.0, derived from English Wiktionary. Only each sense's short Chinese gloss is kept,
split into insertable chips; explanations, examples and rare senses are dropped.

    python3 shared/dictionary/build_open_dictionary.py distribution.jsonl.gz cedict_ts.u8

CC-CEDICT (https://www.mdbg.net/chinese/dictionary?page=cc-cedict) is used only to cross-check the
quick answers: a chip survives when CEDICT also gives the English headword as a meaning of it.

Two outputs per headword:
  - `chips`: every short gloss, for the optional "Words" breakdown.
  - `quick`: set only for single words the dictionary can answer on its own (concert → 演唱会-style
    vocabulary). Words whose meaning depends on register or context (insane, awkward, chill, off...)
    get none, so the keyboard shows no dictionary row and leaves them to the model.

Schema (read by app.langboard.dictionary.OpenDictionary):
    entry(key TEXT PRIMARY KEY, chips TEXT, quick TEXT)  -- "zh<US>pos" joined by <RS>, best first
    form(form TEXT PRIMARY KEY, lemma TEXT)   -- inflection -> headword with chips
    meta(key TEXT PRIMARY KEY, value TEXT)
"""

import gzip
import hashlib
import json
import os
import re
import sqlite3
import sys

SOURCE_SHA256 = "69af69cdc685b5dce465613d1cc8fffb598eb46714f57cf73bd6606c2ceb7e43"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "android", "app", "src", "main", "assets", "opendict.db")

MAX_CHIP_CHARS = 6
MAX_CHIPS = 8
PRIORITY = {"core": 0, "common": 1, "rare": 2}
SKIP_POS = {"name", "character", "symbol", "prefix", "suffix", "infix", "affix"}
# Glosses that describe a word rather than translate it.
NOT_A_TRANSLATION = re.compile(r"缩写|缩略|的形式|拼写|变体|姓氏|^姓|人名|地名|……|\.\.\.|[A-Za-z0-9]")
SPLIT = re.compile(r"[；;，,、/]")
PARENS = re.compile(r"[（(][^）)]*[）)]")
US, RS = "\x1f", "\x1e"

MAX_QUICK_CHIPS = 3
# A sense with one of these labels means the word's everyday use is a matter of register.
SLANG = {"slang", "Internet"}
INFORMAL = {"informal", "colloquial"}
CONTENT_POS = {"noun", "verb", "adj", "adv"}
# Register-heavy words the labels miss. Kept short on purpose: the labels do most of the work.
CONTEXT_WORDS = set("""
awkward overkill weird cute nice cool sweet fine okay ok great awesome amazing terrible horrible annoying
basically actually really totally seriously honestly super pretty kinda sorta quite rather lowkey highkey
vibe mood sus cringe bruh dead done over fun funny hard easy tough rough heavy deep low high
""".split())


def is_cjk(c):
    o = ord(c)
    return 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or 0xF900 <= o <= 0xFAFF


def chips_of(gloss):
    for part in SPLIT.split(PARENS.sub("", gloss or "")):
        p = part.strip().strip("“”\"'！!？?。")
        if not p or len(p) > MAX_CHIP_CHARS or NOT_A_TRANSLATION.search(p):
            continue
        if not all(is_cjk(c) for c in p):
            continue
        yield p


def quick_chips(key, core, cedict):
    """
    Chips from the core senses of the word's main part of speech, kept only where CC-CEDICT agrees:
    the Chinese word must list [key] as one of its English meanings. A CEDICT word found inside a
    longer gloss also counts (咖啡 in 咖啡饮料), which recovers the plain word from an explanation.
    """
    if not core:
        return None
    main_pos = core[0][0]
    glossed = []
    for pos, gloss in core:
        if pos == main_pos:
            glossed += [natural(zh, pos) for zh in chips_of(gloss)]
    by_english, first_sense = cedict
    agreed = by_english.get(key, [])
    out = [zh for zh in dict.fromkeys(glossed) if zh in agreed]
    out += [zh for zh in agreed if zh not in out and any(zh in g for g in glossed) and not any(zh in o for o in out)]
    if not out:
        # The gloss was an explanation; take the word CEDICT gives this headword as its first meaning.
        out = first_sense.get(key, [])[:2]
    # A lone literary character (顽, 妒) next to a real word is noise.
    if any(len(zh) > 1 for zh in out):
        out = [zh for zh in out if len(zh) > 1]
    return RS.join(zh + US + main_pos for zh in out[:MAX_QUICK_CHIPS]) or None


def load_cedict(path):
    """
    English meaning (normalized like a headword) -> simplified words that carry it, and the same
    restricted to each word's first listed meaning.
    """
    by_english, first_sense = {}, {}
    line_re = re.compile(r"^(\S+) (\S+) \[[^\]]*\] /(.*)/$")
    for line in open(path, encoding="utf-8"):
        m = line_re.match(line.strip())
        if not m:
            continue
        simplified, senses = m.group(2), m.group(3).split("/")
        if not all(is_cjk(c) for c in simplified):
            continue
        first = True
        for slot in senses:
            if slot.startswith(("variant of", "old variant", "surname", "CL:", "see ", "used in", "abbr.")):
                first = False
                continue
            for sense in slot.split(";"):
                e = re.sub(r"\([^)]*\)", "", sense).strip().lower()
                e = re.sub(r"^(to be|to|a|an|the) ", "", e).strip()
                if e and len(e) <= 40:
                    by_english.setdefault(key_of(e), []).append(simplified)
                    if first:
                        first_sense.setdefault(key_of(e), []).append(simplified)
            first = False
    dedupe = lambda d: {k: list(dict.fromkeys(v)) for k, v in d.items()}
    return dedupe(by_english), dedupe(first_sense)


def natural(zh, pos):
    """固执的 → 固执, 感到尴尬的 → 尴尬: dictionary phrasing that nobody types in a message."""
    if pos == "adj" and zh.endswith("的") and len(zh) > 2:
        zh = zh[:-1]
    if zh.startswith("感到") and len(zh) > 3:
        zh = zh[2:]
    return zh


def key_of(s):
    return re.sub(r"\s+", " ", s.lower().replace("’", "'")).strip()


def main(path, cedict_path):
    cedict = load_cedict(cedict_path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != SOURCE_SHA256:
        sys.exit(f"unexpected source checksum {digest}")

    entries = {}  # key -> list of (priority, order, zh, pos)
    quick_ok = {}  # key -> still trustworthy as a standalone dictionary answer
    quick_senses = {}  # key -> core senses as (pos, gloss), in source order
    forms = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            d = json.loads(line)
            key = key_of(d["headword"])
            found = entries.setdefault(key, [])
            n = len(found)
            ok = quick_ok.setdefault(key, " " not in key and key.isalpha() and key not in CONTEXT_WORDS)
            core = quick_senses.setdefault(key, [])
            for g in d["pos_groups"]:
                pos = g["pos"]
                if pos in SKIP_POS:
                    continue
                for form in g.get("forms") or []:
                    fk = key_of(form.get("text") or "")
                    if fk and fk != key and not any(t in (form.get("tags") or []) for t in ("comparative", "superlative")):
                        forms.setdefault(fk, key)
                for m in g["meanings"]:
                    labels = set(m.get("labels") or [])
                    if "form-of" in labels:
                        continue
                    prio = m.get("priority")
                    if prio in ("core", "common") and labels & SLANG:
                        ok = False
                    if prio == "core":
                        if labels & INFORMAL or pos not in CONTENT_POS:
                            ok = False
                        if "figuratively" not in labels:
                            core.append((pos, m.get("short_gloss")))
                    for zh in chips_of(m.get("short_gloss")):
                        found.append((PRIORITY.get(m.get("priority"), 2), n, zh, pos))
                        n += 1
            quick_ok[key] = ok

    db_path = os.path.abspath(OUT)
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    if os.path.exists(db_path):
        os.remove(db_path)
    db = sqlite3.connect(db_path)
    db.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE entry(key TEXT PRIMARY KEY, chips TEXT NOT NULL, quick TEXT) WITHOUT ROWID;
        CREATE TABLE form(form TEXT PRIMARY KEY, lemma TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
    """)
    kept = {}
    for key, senses in entries.items():
        best = [s for s in senses if s[0] < 2] or senses  # rare senses only when nothing else exists
        seen, chips = set(), []
        for _, _, zh, pos in sorted(best):
            if zh not in seen:
                seen.add(zh)
                chips.append(zh + US + pos)
            if len(chips) == MAX_CHIPS:
                break
        if chips:
            kept[key] = (RS.join(chips), quick_chips(key, quick_senses.get(key, []), cedict) if quick_ok.get(key) else None)
    db.executemany("INSERT INTO entry VALUES (?, ?, ?)", [(k, c, q) for k, (c, q) in kept.items()])
    print(f"{sum(1 for _, q in kept.values() if q)} headwords answer on their own")
    # Only inflections that aren't headwords themselves, pointing at headwords that have chips.
    db.executemany(
        "INSERT INTO form VALUES (?, ?)",
        [(f, l) for f, l in forms.items() if f not in kept and l in kept],
    )
    db.executemany("INSERT INTO meta VALUES (?, ?)", [
        ("source", "Open Dictionary v2.0, distribution.jsonl.gz"),
        ("source_sha256", SOURCE_SHA256),
        ("license", "CC BY-SA 4.0; derived from English Wiktionary via Open Dictionary"),
        ("cross_check", "CC-CEDICT " + next((l[9:].strip() for l in open(cedict_path, encoding="utf-8") if l.startswith("#! date=")), "unknown date")),
    ])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"{len(kept)} entries, {sum(1 for f, l in forms.items() if f not in kept and l in kept)} forms, "
          f"{os.path.getsize(db_path) / 1e6:.1f} MB -> {db_path}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
