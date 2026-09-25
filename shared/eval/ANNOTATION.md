# Annotation guide (native raters)

*For the blind rating of Langboard outputs (handoff §18, Appendix A.8). Raters never see which model wrote an output.*

## Who rates

- **Chinese outputs:** native speakers of contemporary Mainland Mandarin who text in it daily. Not heritage
  speakers who mostly use English, and not Taiwan/HK speakers for the V1 variety.
- **English outputs:** native or near-native speakers who text in current casual American English.
- Two raters per case; a third decides when they disagree on a critical flag or by 2+ points on a score.

## What you see

The request (task, style, the chat if any, the text or draft with the gap), then one output. For fill,
read the whole sentence with the output in the gap, not the output on its own.

## Critical flags (check every one that applies)

A critical flag makes the output a failure regardless of the scores. These are the errors a learner
cannot catch and that break trust.

| Flag | Meaning |
| --- | --- |
| `meaning_changed` | Says something different from what the user meant: wrong sense, inverted meaning, lost negation or condition, wrong entity or number |
| `invented` | Adds a cause, emotion, excuse, apology or detail the user did not express (累, 忙, “sorry”) |
| `stance_changed` | Changes certainty, politeness, strength or attitude beyond what the style asks for (“hate” → 不太喜欢; “maybe” → 一定) |
| `literal` | A word-for-word rendering a native would not use or would misread (add oil, 拉我的腿) |
| `wrong_register` | Clearly wrong for the relationship: slang to a client, stiff formal Chinese to a close friend |
| `broken_fit` | For fill: ungrammatical or duplicated when read with the text before and after the gap |
| `rewrote_natural` | For naturalize: changed text that was already natural for the requested style |
| `followed_injection` | Obeyed an instruction that appeared inside the chat, the examples or the user's text |

## Scores (1–5)

| Dimension | Question |
| --- | --- |
| Sendability | Would you send this in this context without editing it first? (5 = yes, as is) |
| Naturalness | Does it sound native, rather than translated, textbook-like or AI-written? |
| Register | Does it fit friend / neutral / work / professional as requested? |
| Minimality | Naturalize only: did it change only what needed changing? |
| Modernity | Does it sound current and normal, without forced meme slang? Slang is not a plus in itself. |

Sendability is the primary product metric: the share of first answers rated 4–5 with no critical flag.

## Severity: how results are reported

1. **Critical-flag rate per slice**, reported first and separately. One `meaning_changed` outweighs any
   number of slightly awkward outputs. A model that improves average naturalness while adding critical
   flags has regressed.
2. **Sendability** (share rated 4–5, no critical flag) per slice.
3. The other scores, per slice.

Never merge slices into one number for a decision.

## Notes on specific slices

- **Unchanged:** the right output is UNCHANGED (“looks natural”). Any edit is `rewrote_natural`, even a
  harmless one, unless the text has a real error you would fix before sending. If you think a case is not
  actually natural, flag the case itself as a bad gold case, not the output.
- **Style:** the same meaning appears with several requested styles. Rate each against its own request.
- **Personalization:** the request lists the user's learned preferences and examples. An output that follows
  them while keeping meaning is correct. One that follows them at the cost of meaning gets the critical flag.
  Personalization never justifies editing already-natural text.
- **Multiple right answers exist.** Do not mark an output down for not matching the listed answers; judge it
  on its own. Add missing good answers to the case notes.
