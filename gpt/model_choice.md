This report pushes me pretty strongly toward **Qwen3-1.7B as the main model candidate**, not Hy-MT.

The report’s central conclusion is coherent with what we’ve been circling around: Langboard is not ultimately a translation app. It’s a **personalized output assistant** that needs Fill Gap, Naturalize, register control, style control, and user-specific expression preferences in one model. The report explicitly recommends a unified Qwen-based model plus local retrieval of user examples for personalization. 

A few parts are especially convincing.

First, the personalization architecture is solid: **explicit style controls for broad preferences + retrieved user examples for the micro-style**. The report argues that prompt/control tokens are good for coarse style, while local retrieval of 3–5 representative user examples is better for preserving the user’s actual voice.  That maps almost perfectly to the UX we just designed around “Learn from this message,” custom answers, rejected suggestions, and accepted alternatives.

Second, the Naturalize direction is right. It treats naturalness judgment as part of Naturalize and emphasizes overcorrection as the key failure mode: the model should be biased toward keeping already-natural text unchanged rather than “improving” everything.  I’d keep that.

Third, the report basically confirms that a small general Chinese-heavy LLM is likely more valuable for the whole product than a translation specialist. Hy-MT2 is still attractive for Fill Gap, but the report explicitly notes that its training objective is mostly translation and formatting, whereas Qwen is better positioned for monolingual Chinese naturalization and multi-task control. 

And Qwen3-1.7B is a real, general post-trained model: 1.7B parameters, Apache 2.0, 32k context, and Qwen explicitly claims support for 100+ languages/dialects and strong multilingual instruction following/translation. ([Hugging Face][1]) It’s also directly supported by llama.cpp’s Qwen3 architecture path, and llama.cpp has an official Android binding. ([GitHub][2])

So if I had to simplify our plan now, I’d make it:

**Qwen3-1.7B**
→ multitask LoRA/SFT for Fill Gap + Naturalize + register/style control
→ local user-profile prompt
→ local retrieval of a few learned examples
→ exact-span output discipline
→ lots of `UNCHANGED` examples for Naturalize
→ explicit wrong/not-me/custom-answer feedback loops

That is much cleaner than:

**Hy-MT + second general LLM + extra classifier + personalization layer**

I would still keep Hy-MT as a benchmark, because Tencent’s own docs make clear that it is a dedicated translation model with official GGUF/low-bit variants, and it could still beat Qwen on pure Fill Gap. ([GitHub][3]) But I would stop treating it as the likely long-term foundation.

One thing I would **not trust from the report yet** is the mobile latency table. Numbers like **40–70 ms TTFT** and 50–90 tok/s on generic flagship phones are too specific and device-dependent to use for architecture decisions without benchmarking our actual phone/runtime/quant. The report itself is useful as a strategy document, but those performance figures should be treated as placeholders rather than ground truth. The official llama.cpp Android docs emphasize hardware-dependent behavior and warn that context size and memory can materially affect runtime. ([GitHub][4])

The same goes for some of the stronger claims like “Qwen absorbs a huge amount of Chinese social-network language.” That is plausible, but not something the official model card quantifies in the way the report states. I’d treat those as hypotheses to test, not facts.

What I would actually do next is very concrete:

* Build a **200-case Langboard eval set**
* Run:

  * Qwen3-1.7B
  * Hy-MT2-1.8B
  * Qwen3.5-2B if deployment is stable enough
* Score:

  * Fill Gap idioms
  * Naturalize
  * `UNCHANGED`
  * casual vs professional register
  * user-style conditioning
  * exact-span adherence
* Fine-tune the top **two** on the same 1k examples
* Re-run the same locked eval

The report proposes essentially that same staged approach: baseline first, then a 1k LoRA experiment, then style-control testing, then personalization testing with real users. 

So my current recommendation is:

**Qwen3-1.7B should now be the favorite. Hy-MT2 should be the specialist benchmark.**

And the bigger takeaway is that **the model choice matters less than the evaluation/training loop from here onward**. The trust problem you identified means our moat is going to come from being obsessive about:

* false positives
* overcorrection
* exact meaning preservation
* native “would actually send this” judgments
* personalized examples
* user-reported wrong outputs

That’s the path I’d build around now.

[1]: https://huggingface.co/Qwen/Qwen3-1.7B/tree/main?utm_source=chatgpt.com "Qwen/Qwen3-1.7B at main"
[2]: https://github.com/ggml-org/llama.cpp/blob/master/src/llama-arch.cpp?utm_source=chatgpt.com "llama.cpp/src/llama-arch.cpp at master · ggml-org/llama.cpp · GitHub"
[3]: https://github.com/Tencent-Hunyuan/Hy-MT2?utm_source=chatgpt.com "GitHub - Tencent-Hunyuan/Hy-MT2 · GitHub"
[4]: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md?utm_source=chatgpt.com "llama.cpp/docs/android.md at master · ggml-org/llama.cpp · GitHub"

I’m **not sure enough to say raw Qwen3/3.5 ~1.7–2B will be as good at translation as Hy-MT**. In fact, based on the current evidence, I’d expect **Hy-MT2-1.8B to be better at pure translation out of the box**.

Tencent built Hy-MT2 specifically for translation. Its 1.8B model is trained for 33 languages plus dialect/minority-language variants, contextual translation, terminology control, formatted translation, and translation instruction-following. Tencent reports that the 1.8B model beats larger open-source models and commercial APIs on several translation benchmarks. ([Hugging Face][1])

By contrast, Qwen3-1.7B is a **general-purpose multilingual LLM**. It can absolutely translate, and there are even derivatives fine-tuned specifically for EN↔ZH that get respectable WMT results, but that itself is evidence that raw Qwen benefits from translation-specific tuning. ([Hugging Face][2])

So the distinction is:

|                                  | Hy-MT2-1.8B         | Qwen3-1.7B / small Qwen       |
| -------------------------------- | ------------------- | ----------------------------- |
| Raw translation                  | **Likely stronger** | Good, but general-purpose     |
| Exact bilingual semantic mapping | **Strong**          | Good                          |
| Contextual translation           | **Native task**     | General capability            |
| Naturalize Chinese               | Less certain        | **Better conceptual fit**     |
| Explain / tone / style / reply   | Limited generality  | **Much better fit**           |
| Personalization/RAG examples     | Possible            | **Better suited**             |
| Multitask Langboard model        | Riskier             | **More natural architecture** |
| Mobile size                      | Excellent           | Excellent                     |

The thing I’d correct from my earlier recommendation is: **don’t replace Hy-MT with Qwen purely on architectural intuition.**

We now have two genuinely different strengths.

### Hy-MT is unusually strong for its size

Hy-MT1.5 already had a very serious training pipeline: MT-oriented pretraining, SFT, on-policy distillation, and RL. Tencent says its 1.8B model outperformed Qwen3-32B and much larger MT models on their translation evaluations. ([GitHub][3])

Hy-MT2 builds on that and is even more clearly aimed at real-world/contextual translation. ([Hugging Face][1])

So if the task is:

> “Translate this English meaning into Chinese faithfully”

Hy-MT has a huge head start.

And Fill Gap is fundamentally still partly a translation problem.

### The actual question is whether we can make Hy-MT learn the other tasks

That is the critical experiment.

Suppose we fine-tune Hy-MT on:

* Fill Gap
* Naturalize
* `UNCHANGED`
* casual vs professional
* EN Naturalize
* ZH Naturalize
* style controls

If it learns those without destroying its translation strength, then **Hy-MT might still be the best foundation**.

That could actually be ideal:

```text
Excellent translation prior
+
our humanization/naturalization data
+
style controls
+
personalization examples
```

rather than:

```text
general LLM
+
having to teach it much more translation
+
humanization
+
style controls
```

That first path might require less work.

## Where Qwen could still win

Hy-MT may hit a wall when we ask:

> “Is this Chinese already natural?”

or:

> “Make this sound more like a close friend without changing meaning.”

or:

> “Explain why this sounds weird.”

Its weights were optimized overwhelmingly around translation behavior. Tencent itself describes the family as a machine translation model series, not a general assistant. ([GitHub][4])

Qwen is more likely to have latent knowledge for those tasks.

So the competition is not:

> Which raw model translates better?

Hy-MT probably wins.

It is:

> **After the same Langboard fine-tune, which model produces the best total product quality?**

That is unresolved.

## I’d actually test THREE conditions

This is now the experiment I’d run:

### A. Raw Hy-MT2-1.8B

Use Tencent’s official contextual-translation prompt.

### B. Raw Qwen3-1.7B

Use carefully designed task prompts.

### C. Fine-tune both with exactly the same 1k dataset

Something like:

```text
350 Fill Gap EN→ZH
150 Fill Gap ZH→EN
250 ZH Naturalize
100 EN Naturalize
100 style/register contrast
50 UNCHANGED-heavy cases
```

Then evaluate both on a **locked 300-case set**.

Score separately:

* Fill Gap semantic fidelity
* idiom/pragmatic translation
* exact patch fit
* Chinese naturalness
* English naturalness
* Naturalize false-positive rate
* register following
* style following
* personalization following
* format adherence

Do not use one aggregate score initially.

You might discover:

```text
Hy-MT:
95 translation
77 naturalize
70 personalization

Qwen:
88 translation
91 naturalize
90 personalization
```

Then the product decision becomes obvious.

Or maybe Hy-MT surprises us:

```text
Hy-MT after LoRA:
94 translation
89 naturalize
86 personalization
```

In that case I’d happily keep Hy-MT.

## There is also a possible middle ground

Use **Hy-MT as the base**, and train it to become more general.

Because it's still an autoregressive causal LM under the hood, not some ancient encoder-decoder-only architecture. Hugging Face loads it with `AutoModelForCausalLM`. ([Hugging Face][5])

So we're not dealing with a rigid translation engine incapable of other generation tasks.

The question is simply how much latent general-language capability survived its specialization.

That's empirically testable.

## And one more thing: Hy-MT has a massive mobile advantage

Its official 1.25-bit model is around **440 MB** and Tencent reports 1.5× speed improvement on Apple A15 relative to traditional 4-bit inference. ([Hugging Face][1])

That's insanely attractive for a keyboard.

So I would be reluctant to throw it away unless Qwen clearly wins on product quality.

## My current stance

I’d revise the recommendation to:

**Do not choose Qwen yet. Do not choose Hy-MT yet.**

Treat them as the final two candidates.

My expectation:

* **Hy-MT2-1.8B wins raw translation**
* **Qwen3-1.7B wins raw general-language flexibility**
* **Fine-tuning decides the actual Langboard winner**

And because trust is so central, I'd rather spend a week doing this controlled bakeoff than make an architecture choice based on model-card vibes.

The most important experiment now is:

> **Can Hy-MT retain its translation advantage while learning Naturalize + style + restraint + personalization?**

If yes, I’d probably choose Hy-MT.

If no, Qwen becomes the better foundation.

[1]: https://huggingface.co/tencent/Hy-MT2-1.8B/blob/efcd3a57bcb8f0f96166cd55ab729091e7a0b0af/README.md?utm_source=chatgpt.com "README.md · tencent/Hy-MT2-1.8B at efcd3a57bcb8f0f96166cd55ab729091e7a0b0af"
[2]: https://huggingface.co/Ismantic/Interpreter-Qwen3-1.7B?utm_source=chatgpt.com "Ismantic/Interpreter-Qwen3-1.7B · Hugging Face"
[3]: https://github.com/Tencent/AngelSlim/blob/main/docs/source/models/Hy-MT1.5/hy-mt1.5.md?utm_source=chatgpt.com "AngelSlim/docs/source/models/Hy-MT1.5/hy-mt1.5.md at main · Tencent/AngelSlim · GitHub"
[4]: https://github.com/Tencent-Hunyuan/Hy-MT2/blob/main/README_CN.md?utm_source=chatgpt.com "Hy-MT2/README_CN.md at main · Tencent-Hunyuan/Hy-MT2 · GitHub"
[5]: https://huggingface.co/tencent/Hy-MT2-1.8B/tree/cf56b463e627b20aaac3dde40fab05a8cd5a2c46?utm_source=chatgpt.com "tencent/Hy-MT2-1.8B at cf56b463e627b20aaac3dde40fab05a8cd5a2c46"

