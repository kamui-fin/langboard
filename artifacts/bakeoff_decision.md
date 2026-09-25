# Bakeoff decision framework (after the raw bakeoff, 25 Sep 2026)

Right now: **the plan is NOT to ship both.** The ideal outcome is **one model on-device**.

The bakeoff is telling us *which weakness is easier to train away*.

Raw models:

| | Hy-MT2 | Qwen3.5 |
|---|---:|---:|
| EN→ZH Fill | **70/101** | 45/101 |
| ZH→EN Fill | **19/26** | 14/26 |
| Leaves natural text alone | 6/27 | **26/27** |
| Personalization | 3/8 | **7/8** |

- **Hy-MT2 knows how to translate**, but behaves too much like a translator: “you gave me text, therefore I must transform it.”
- **Qwen understands the Langboard interaction better**, but its bilingual lexical/idiomatic knowledge isn't nearly as good raw.

> **Is it easier to teach Qwen better native translation, or teach Hy-MT restraint + style + personalization?**

That is what the identical 1k fine-tune answers.

## Outcomes

- **Qwen3.5 wins after SFT** (preferred if earned): it fixes literal mappings (call it a day → 今天就到这吧 / 差不多得了 / 先这样吧) while keeping UNCHANGED judgment, instruction following, personalization, Naturalize and register control → ship Qwen alone. Its general instruction-following prior suits everything Langboard eventually needs (Fill, Naturalize, judging whether to change, register, personalization, context, alternatives, minimal edits, explanations). But don't pick it for being philosophically prettier: noticeably worse translation after SFT is unacceptable, because Fill Gap is the flagship.
- **Hy-MT2 is surprisingly trainable** (e.g. Fill 82/101, ZH→EN 22/26, UNCHANGED 24/27, personalization 7/8) → ship Hy-MT2. "natural input → UNCHANGED" is an explicit behavior that may be very trainable. Less certain: whether a translation-specialized model can become good at personalization, nuanced instruction following, not translating, style controls and future tasks without losing its translation strength.

## What we don't want

Fill Gap on Hy-MT2 and Naturalize on Qwen3.5, on the phone. Two ~2B downloads: 2× storage, model management, RAM, quantization testing, runtime bugs, updates, task routing, and harder iOS/Android deployment. We're building a mobile app, not winning a leaderboard.

## Using both, as teachers

If Hy-MT2 is better at idiomatic translation, fidelity and phrase selection and Qwen at restraint, style, personalization and judgment, use each where it's strong when constructing candidate data (e.g. Hy-MT candidates → native/context filtering → training pair → one student). Much better than shipping both.

## If neither is good enough, escalate in order

- **Plan A**: one fine-tuned model (Qwen or Hy-MT). Strongly preferred.
- **Plan B**: a stronger single base, if TranslateGemma 4B shows 2B isn't enough; check whether an aggressively quantized 4B works on the minimum phone. Still one model.
- **Plan C**: distillation: Hy-MT + Qwen + native gold → teacher system → one smaller student.
- **Plan D**: two runtime models, only if the quality gap is enormous and nothing else works. A fallback, not the plan.

## Decision rule after the 1k pilot

Don't total everything into one score; Fill Gap gets the most weight.

- **Choose Qwen** if it closes most of the translation gap (e.g. Fill 80 vs Hy 84, but clearly better UNCHANGED / Naturalize / personalization).
- **Choose Hy-MT** if it keeps a meaningful translation lead **and** learns restraint (e.g. Fill 87 vs 71, UNCHANGED 91%).
- **Neither** if Hy still rewrites everything and Qwen still translates like a learner. Don't pick arbitrarily; test more targeted data, TranslateGemma, distillation, or another base.

## A subtle point

Hy's failure (我刚到家 → 我刚刚到家) is doing the wrong task with perfect Chinese: potentially very teachable. Qwen's (call it a day → 叫它一天) is a bad bilingual mapping: teaching thousands of idiomatic mappings may be harder, or LoRA may just activate knowledge Qwen already has. We don't know yet. Hy-MT's 70 vs 45 Fill lead is large enough that Hy must get its chance to learn restraint. The next fine-tune decides something; it is not benchmark theater.
