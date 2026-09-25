The product direction has gotten much clearer:

**Langboard should be an output-first language companion that helps you say what *you* were already trying to say — naturally, in your own voice — without becoming an AI ghostwriter.**

The closest analogy is still **“Grammarly for language learners,”** but I’d sharpen it further:

> **A native-expression layer between what’s in your head and what you actually send.**

Not a translator. Not Duolingo. Not ChatGPT in a keyboard. Not a keyboard replacement.

The core experience is that you continue using Gboard / Apple Keyboard normally. When you hit a linguistic wall, you summon Langboard.

### The product hierarchy

1. **Fill Gap is the wedge and flagship.**
   You write:
   `我本来想去但是 I couldn't be bothered anymore`

   Langboard understands the whole sentence and gives:
   `懒得再去了`

   It replaces only what you couldn’t express. This is the most differentiated behavior because it preserves productive struggle: **you wrote 80% of the sentence; Langboard supplies the missing 20%.**

2. **Naturalize is the second pillar.**
   You wrote the entire sentence yourself, but you’re unsure whether it sounds native.

   Langboard either says:
   `Looks natural ✓`

   or gives the smallest useful correction.

   Crucially, Naturalize contains the judgment. There should not be a separate “rate my Chinese” tool. And the model has to be comfortable doing **nothing**.

3. **Personalization becomes the long-term moat.**
   Eventually Langboard shouldn’t just know “natural Chinese.” It should know **your Chinese**.

   It learns things like:

   * you prefer concise texts;
   * you say `咋` rather than `怎么` with friends;
   * you tend to use `挺` instead of `很`;
   * you want casual Mainland wording;
   * you prefer softer disagreement;
   * you rarely use heavy internet slang;
   * in work chats, you want a different register.

   This comes from explicit settings + **Learn from this message** + alternatives you choose + corrections you make + custom answers you add.

4. **Conversation Context makes the assistance situational.**
   Particularly on Android, optional local Accessibility context can let Langboard understand:

   * who you're talking to;
   * what they just said;
   * whether the conversation is casual/work/etc.;
   * what pronouns or subjects can naturally be omitted;
   * what an ambiguous English phrase means here.

   That makes the difference between generic translation and:

   > “what would actually fit *this conversation*?”

5. **Explanation and learning sit behind the primary action.**
   A suggestion can expand into:

   * Why this?
   * Alternatives
   * Pinyin
   * Meaning
   * More casual
   * More polite
   * Save phrase

   But none of that should slow down the main keyboard flow.

So the surface stays:

```text
✦ Langboard

懒得再去了

[ Insert ]

Alternatives ›
Explain ›
```

rather than becoming a mini language-learning dashboard every time you invoke it.

---

## The important philosophical distinction

Langboard should optimize for:

> **helping the learner produce language**

rather than:

> **producing language on behalf of the learner.**

That gives us a clear test for future features.

Good:

* “I know 80% of this sentence; help with this phrase.”
* “Does what I wrote sound natural?”
* “Make this slightly softer.”
* “Why does 阴阳怪气 work here?”
* “How would I say this to my professor instead?”
* “Remember that I prefer this expression.”
* “What did my friend mean by this?”
* “Give me a hint before showing the answer.”

Less aligned:

* “Write an entire reply for me.”
* “Generate my whole email.”
* “Have an AI conversation with my friend.”
* endless canned response suggestions.

We *can* eventually offer reply assistance, but ideally as something like:

> **What are you trying to say?**

rather than one-tap AI impersonation.

---

## What makes Langboard different

The product isn't really:

**translation + keyboard.**

It's the intersection of:

```text
Translation
      +
Native expression
      +
Writing assistance
      +
Language learning
      +
Personal voice
      +
Conversation context
```

Most translation products optimize:

> What does X mean in Y?

Langboard optimizes:

> **Given what I was trying to say, how would someone like me naturally express it here?**

That's a much more interesting problem.

And the trust issue we identified becomes central. A learner often cannot evaluate whether `被动攻击型` or `阴阳怪气` is the better answer. Therefore the whole product has to be built around **quality > feature count**.

---

## V1 should actually remain pretty small

I would resist adding everything we've brainstormed.

**Core V1:**

* English → Chinese Fill Gap
* Chinese → English Fill Gap
* Chinese Naturalize
* English Naturalize
* Casual / neutral / work register
* Open Dictionary + CC-CEDICT fallback
* downloadable offline model
* compact keyboard UX
* explicit Wrong feedback
* custom preferred answer
* Learn from this message
* simple personalization settings
* local history/examples
* Android Conversation Context as optional enhancement

That's already a powerful product.

Then after quality is proven:

**V1.x / V2:**

* alternatives with labels;
* explain;
* phrase saving;
* personal style retrieval;
* relationship-aware register;
* strength/softness adjustment;
* incoming-message explanation;
* hint-before-answer;
* individual learning insights.

Much later:

* other language pairs;
* deeper dialect/region support;
* personalized model adaptations if retrieval turns out insufficient;
* richer contextual coaching.

---

## Chinese and English should now be equal first-class directions

This changed during our model research.

Originally Langboard was basically:

> English speaker learning Chinese.

Now I think the underlying product should be:

> **language learners who can mostly communicate in their target language but still periodically hit expressive gaps.**

Our first implementation can still focus heavily on **EN ↔ ZH**, because that's where we're building the data and expertise.

But architect it symmetrically:

```text
English learner of Chinese
EN → ZH Fill Gap
ZH Naturalize

Chinese learner of English
ZH → EN Fill Gap
EN Naturalize
```

That substantially increases the eventual market without changing the core interaction.

And later the same abstraction becomes:

```text
JP ↔ EN
KR ↔ EN
ES ↔ EN
...
```

with separate downloadable language intelligence packs.

---

## Personalization is probably the feature that makes Langboard feel magical

I'd eventually have a settings screen like:

```text
My Chinese

Mainland Chinese
Casual by default
Concise
Moderately soft
Low–medium slang

Learned from you
• You prefer 啥 over 什么 with friends
• You tend to use 挺 instead of 很
• You like shorter sentences
• You rarely use emojis

Examples you've taught Langboard
47
```

And importantly:

**[Edit] [Forget] [Reset]**

Then in the keyboard:

```text
✦ Best for you

我也懒得折腾了
```

The phrase **“for you”** eventually becomes meaningful rather than marketing.

---

## “Learn from this message” fits the educational side beautifully

This might be one of my favorite secondary features.

You see a native friend write:

`我真服了`

You think:

> That's exactly the kind of expression I wish I knew.

Tap Langboard:

**Learn from this message**

Now it enters your expression/style memory.

Later when you want to express a related sentiment, Langboard can preferentially suggest things resembling expressions you've learned from real people.

So the personalization corpus also becomes your **living language-learning corpus**.

That connects AI assistance back to actual learning instead of replacing it.

---

## And external research should stay external

I agree with your decision here.

If Langboard isn't confident:

```text
Not satisfied?

[ Search the web ]
```

Open Google/browser.

The user can ask a friend, search Google, use Reddit, Pleco, whatever.

When they return:

**Add custom answer**

Now Langboard learns:

```text
AI suggested:
太夸张了

I prefer:
也太离谱了吧
```

That's much cleaner than silently uploading their conversation to cloud search systems.

---

## The core product flywheel

This is ultimately where the company/product gets interesting:

```text
better base model
      ↓
better suggestions
      ↓
real learner usage
      ↓
actual failure cases
      ↓
explicit Wrong reports
      ↓
custom/native corrections
      ↓
better evaluation + training data
      ↓
better Langboard model
```

Meanwhile locally:

```text
use Langboard
     ↓
choose expressions
     ↓
teach examples
     ↓
Langboard learns your style
     ↓
suggestions feel more like you
     ↓
you trust/use it more
```

Those are **two separate loops**:

* global model improvement;
* private individual personalization.

That's a very strong product architecture.

---

### If I had to reduce the whole direction to one sentence

**Langboard helps you communicate in another language without taking the act of communicating away from you.**

And the three things I would obsess over are:

**Native quality → trust → personalization.**

Not number of AI features.

If those three work, the keyboard itself can stay surprisingly simple.

