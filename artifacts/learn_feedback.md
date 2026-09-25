I agree with the direction: **don’t make “slang freshness” a core product responsibility.** That becomes a treadmill. Better to optimize for durable conversational language, and let users explicitly personalize the edge cases they actually care about.

The strongest personalization system here is probably **example-driven**, not demographic-driven and not trend-chasing.

I’d design it around four concepts:

* **Style profile** — concise summary of how the user tends to want to sound
* **Learn from this message** — save a real sentence as a positive example
* **Wrong / Not me** — mark bad suggestions and optionally provide a replacement
* **Custom answer** — user enters the wording they actually prefer, including something they researched externally

That gives you a much cleaner learning loop.

### The UX I’d use in the keyboard

Primary result:

```text
✦ Best here

阴阳怪气

[ Insert ]

Alternatives
话里带刺       softer
有点攻击性     neutral

[ More ]
```

Then under **More**:

```text
Explain
Save phrase
Learn from this message
Wrong / not how I'd say it
Custom answer
```

I would not clutter the default panel with all of these. The top suggestion still needs to feel fast and authoritative.

### “Wrong / not how I’d say it”

This should distinguish **incorrect** from **personal preference**.

Something like:

```text
What was wrong?

○ Meaning is wrong
○ Sounds unnatural
○ Wrong tone
○ Too formal
○ Too slangy
○ Not how I talk
```

Then:

```text
Know a better way?

[ Type preferred wording... ]

[ Save feedback ]
```

That distinction is extremely valuable later.

Because:

```text
Meaning is wrong
```

is model-quality training data.

Whereas:

```text
Not how I talk
```

is personalization data.

You don't want to mix those together.

### Custom answer is critical

This solves the problem you mentioned where the user leaves Langboard, asks a native speaker, checks Google, uses Pleco, or figures it out later.

They come back and say:

```text
For:
"that's so insane"

I prefer:
也太颠了吧
```

Then Langboard saves:

```json
{
  "context": "朋友之间聊天",
  "source": "that's so insane",
  "model_suggestion": "太离谱了",
  "user_preferred": "也太颠了吧",
  "feedback_type": "personal_preference"
}
```

That is gold for their personal profile.

And if they explicitly say:

> the model was wrong

it can also enter the candidate queue for future global model improvement, subject to consent/review.

## “Learn from this message” could be killer

Imagine they receive:

> 你别给我整这出

and think:

> oh, this is exactly the kind of Chinese I want Langboard to use.

They tap:

**Learn from this message**

Langboard saves:

```text
你别给我整这出
```

plus lightweight metadata:

```text
source: received conversation
style: casual
relationship: friend
```

No need to understand every linguistic property immediately.

Over time they build a tiny personal corpus:

```text
哈哈没事
咋了
也太离谱了吧
别整这出
我真服了
懒得搞了
真的假的
```

Now the interesting part starts.

## Don’t fine-tune the user model immediately

I would not do per-user LoRA in V1.

Instead periodically compress their examples into a **style profile**.

Something like:

```text
User style profile

- Prefers short casual sentences
- Uses Mainland internet-influenced vocabulary
- Frequently uses 啥 / 咋 instead of 什么 / 怎么
- Likes sentence-final 吧 / 啊 / 了
- Prefers 离谱 over 夸张 in casual contexts
- Avoids very formal wording
- Moderate slang use
- Usually concise rather than expressive
- Often softens disagreement with 有点 / 感觉
```

Then pass that into the model.

That will probably deliver much of the benefit of personalization without any per-user training.

### How to generate that profile

You were exactly on the right track.

Periodically run an LLM over:

```text
positive examples
+
accepted suggestions
+
rejected suggestions
+
custom replacements
```

and ask it to produce a **compact behavioral profile**.

Crucially, don't just analyze what they accepted.

Compare pairs:

```text
accepted:
离谱

rejected:
夸张
```

and:

```text
model:
我没有兴趣了

custom:
我没啥兴趣了
```

Those contrasts tell you much more.

The profile generation prompt should basically ask:

> Infer stable stylistic preferences only when supported by repeated evidence. Do not infer demographics or personality traits. Describe lexical, syntactic, register, tone, and brevity preferences.

That avoids creepy or brittle inference.

## Separate three memory layers

I’d make personalization internally look like this:

```text
1. Explicit settings
2. Learned style profile
3. Retrieved examples
```

For example:

```text
Explicit
- Mainland Chinese
- Casual by default
- Slang: medium
- Concise

Learned
- prefers 啥 over 什么
- prefers short clauses
- often uses 挺 instead of 很

Retrieved examples
- "咋回事"
- "也太离谱了吧"
- "我真服了"
```

Then inference receives all three.

This is much stronger than just:

```text
Age: 21
Gender: male
```

because it's based on actual linguistic behavior.

### Onboarding should be minimal

I would not ask twenty questions.

Maybe:

```text
How do you want Langboard to sound?

Region
[ Mainland ] [ Taiwan ]

Default tone
[ Casual ] [ Neutral ] [ Professional ]

Slang
[ Low ] [ Medium ] [ High ]

Style
[ Concise ] [ Balanced ] [ Expressive ]
```

Then:

> Langboard will learn your preferences over time.

That's enough.

Don't ask age/gender unless later research shows a meaningful benefit. Actual examples are better.

## Personalization settings page

Something like:

```text
Your Chinese style

Mainland Chinese
Casual
Medium slang
Concise

Learned preferences
✓ prefers 啥 over 什么
✓ likes shorter replies
✓ often uses 挺 / 有点
✓ avoids formal phrases

Based on 47 examples

[ Edit preferences ]
[ View learned examples ]
[ Reset learned style ]
```

Transparency matters a lot.

Users should see what the app thinks it learned.

And be able to delete:

> “prefers 啥 over 什么”

if that's wrong.

## Example library

I'd give users a simple section:

```text
My examples

✓ 也太离谱了吧
✓ 我真服了
✓ 咋回事
✓ 懒得搞了
✓ 真的假的

[ + Add example ]
```

They can:

* paste things
* add manually
* save from conversations
* save model outputs

This becomes the user's linguistic taste profile.

### Maybe even categorize them automatically

```text
Casual
Work
Funny
Soft
Direct
```

But don't make them manually tag everything.

The model can suggest tags.

## “Learn this preference” after selecting alternatives

Suppose:

```text
Best:
太夸张了

Alternatives:
太离谱了
太颠了
```

They always choose:

> 太离谱了

After 3–4 instances, Langboard can infer:

```text
prefers 离谱-style casual wording over neutral 夸张
```

No explicit interaction required.

That's useful passive personalization.

But still:

**acceptance ≠ correctness.**

Use it only for **their style**, not global truth.

## Global feedback loop

I'd keep global improvement deliberately separate.

If they tap:

**Wrong**

then:

```text
Help improve Langboard?

Share this example anonymously:
[context preview]

☐ Include surrounding conversation

[ Send feedback ]
```

Then it goes into a review queue.

Possible pipeline:

```text
reported wrong
↓
deduplicate
↓
strong teacher review
↓
native reviewer
↓
gold correction
↓
future training batch
```

Never automatically throw user corrections into global training.

## “Ask Google” / external research

I agree with your simplified version.

Don't integrate web lookup.

Just give:

```text
Not satisfied?

[ Search web ]
```

which opens a browser search like:

> `"passive-aggressive" Chinese casual translation`

or maybe:

> `how to say "passive-aggressive" naturally in Chinese`

No conversation upload from Langboard.

No hidden cloud call.

No privacy mess.

And after they come back:

```text
[ Add custom answer ]
```

That's actually a pretty elegant workflow.

## The learning loop becomes

```text
Langboard suggests
      ↓
user accepts
      ↓
personal preference signal

OR

user picks alternative
      ↓
strong preference signal

OR

user says "wrong"
      ↓
quality feedback

OR

user researches externally
      ↓
custom answer
      ↓
high-value personal example
```

Then periodically:

```text
examples + contrasts
        ↓
style profiler
        ↓
compact p13n prompt
        ↓
better future suggestions
```

That's much more feasible than chasing every slang trend.

## The most important design principle

Personalization should learn:

> **how this user prefers to express meanings**

not:

> **who we think this user is.**

So instead of:

```text
21-year-old male
```

prefer:

```text
concise
casual
medium slang
prefers direct wording
frequently uses 啥 / 咋
rarely uses emojis
```

That's safer, more accurate, and actually useful to the model.

I think this could become one of Langboard's strongest features: **the more you use it, the less it sounds like “AI Chinese” and the more it sounds like your Chinese.**

