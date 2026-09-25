Yes — after looking at current onboarding/subscription patterns, I’d **cut the onboarding dramatically**. Fifteen screens was over-designed.

Apple’s current guidance is basically: onboarding should be brief, interactive, postpone nonessential customization, and teach by letting the user actually experience the interaction. ([Apple Developer][1]) RevenueCat’s 2026 subscription data also supports a hard-paywall/trial approach, but stresses that users need enough context to understand the value first. ([RevenueCat][2])

For Langboard, that means **4 screens before the trial starts, then 2 setup screens**. Everything else gets deferred.

## First: yes, you can have no accounts

RevenueCat is actually built to support this.

If you initialize RevenueCat without providing your own user ID, it creates an anonymous `$RCAnonymousID` and caches it locally. The **App Store / Google Play account**, not your Langboard account, handles the actual subscription, trial eligibility, billing, renewal and cancellation. ([RevenueCat][3])

So the flow is simply:

```text
Install Langboard
      ↓
No login
      ↓
RevenueCat creates anonymous customer
      ↓
User starts free trial via Apple/Google
      ↓
Store transaction activates RevenueCat "pro" entitlement
      ↓
Langboard unlocks
```

On Apple, eligible introductory trials are applied automatically by the App Store. On Google Play, RevenueCat can automatically select the eligible free-trial offer from the subscription options. ([RevenueCat][4])

This is what I'd do for V1.

There are **two tradeoffs you should consciously accept**.

First, uninstalling/reinstalling generates a new RevenueCat anonymous ID. A **Restore Purchases** action can reconnect an active purchase from the same Apple/Google store account, and RevenueCat explicitly recommends providing restore functionality. ([RevenueCat][5])

But your local Langboard data is different. If we're intentionally doing no cloud account/backup, then things like:

```text
History
SRS progress
My Style
learned expressions
personalization examples
```

are device-local and can be lost after deleting the app or changing devices.

I'm okay with that for V1. Just don't pretend otherwise.

Second, **iOS ↔ Android subscription sharing won't work automatically**. Someone who pays on iPhone and later switches to Android has no Langboard identity that connects those two store accounts. RevenueCat itself notes anonymous IDs are best suited to apps that don't need cross-platform identity. ([RevenueCat][3])

Again: acceptable V1 limitation. If Langboard gets traction, add optional accounts later and migrate anonymous customers into identified RevenueCat users.

---

# The revised onboarding

I'd steal principles rather than copying any one app.

Duolingo uses small decisions and quickly moves users toward actually doing something; Speak uses goal personalization and very explicit trial framing; keyboard apps like Grammarly have historically made OS keyboard setup its own guided step rather than mixing it with product education. ([ScreensDesign][6])

But Langboard needs **much less personalization than Duolingo**.

So:

| Stage | Screen                 | Purpose                          |
| ----- | ---------------------- | -------------------------------- |
| 1     | **Aha moment**         | Understand Langboard             |
| 2     | **Make it yours**      | Language + basic style           |
| 3     | **Why it gets better** | Learning loop + trust/privacy    |
| 4     | **Free trial**         | Convert                          |
| 5     | **Install Langboard**  | Download model + enable keyboard |
| 6     | **Try it once**        | Activation                       |

That's the entire flow.

---

# Screen 1 — The whole product in 10 seconds

No separate logo splash.

Header:

# **Say what you mean.**

Sub:

> Get unstuck without giving up the sentence.

Then the interactive demo:

```text
我本来想去但是 I couldn't be bothered anymore
```

Tap **Help me say this**

The English fragment fades:

```text
我本来想去但是懒得再去了
```

And underneath:

> **You wrote the sentence. Langboard only helped with the part you didn't know.**

Button:

**Continue**

That's enough.

Don't explain Fill Gap, AI, local LLMs, Naturalize, SRS, etc.

---

# Screen 2 — Make it yours

One page.

## **Make Langboard yours**

```text
I speak
English

I'm learning
简体中文
```

Then:

**How should you sound most of the time?**

Three cards:

```text
Casual       ← selected
Natural everyday conversation

Neutral
Works almost anywhere

Work
Professional, not stiff
```

That's it.

No sliders.

No proficiency test.

No:

> Why are you learning Chinese?
> What's your age?
> What's your goal?
> How often do you study?

Those don't improve the core product enough to justify the friction.

The more advanced:

```text
Slang
Directness
Conciseness
Region
```

belong under **My Style** later.

Apple specifically recommends reasonable defaults and postponing nonessential customization. ([Apple Developer][1])

---

# Screen 3 — Sell the compounding value

This is where we stop the user from thinking:

> Oh, it's just a translator keyboard.

Header:

# **Learn from what you actually say.**

Three visually connected steps:

```text
GET UNSTUCK

I couldn't be bothered
→ 懒得...


REMEMBER IT

Langboard saves what
you needed help with.


SAY IT YOURSELF

Review it later until
you don't need help.
```

Then one trust line:

> **Runs locally. Your conversations aren't uploaded to train Langboard.**

And maybe three small status pills:

```text
Offline model       Private by default       Learns your style
```

That's enough privacy messaging for onboarding.

Don't make privacy its own screen. People who care can tap:

**How privacy works**

---

# Screen 4 — Paywall / trial

Now they understand:

1. the signature interaction;
2. personalization;
3. the learning loop;
4. privacy.

That's enough to ask.

I'd make this a **hard paywall** because you're explicitly building a premium trial-only product.

RevenueCat's 2026 dataset shows hard-paywall apps had materially higher observed download-to-paid conversion than freemium apps, though that's observational data rather than proof that hard paywalls cause the difference. ([RevenueCat][7])

The screen:

# **Try the full Langboard experience**

```text
Fill gaps naturally
Naturalize what you write
Learn from real conversations
Personalized review
Your own language memory
Adapts to your style
Works offline
```

Then pricing.

---

# Pricing: what I'd actually launch with

My starting US pricing would be:

|             |            Price |
| ----------- | ---------------: |
| **Monthly** | **$11.99/month** |
| **Annual**  |  **$59.99/year** |
| Trial       |  **7 days free** |

Annual selected by default.

That annual works out to about **$5/month**, versus $11.99 month-to-month.

I would **not offer weekly**.

Why this range?

RevenueCat's 2026 dataset puts Education around **$9.99/month and $44.99/year at the median**; North American subscription pricing also clusters near $9.99 monthly. ([RevenueCat][8])

But your closer premium comparables charge substantially more. Speak currently lists **$17.99/month / $83.99/year** in the U.S., and Grammarly's mobile-only plans currently include **$17.99/month / $83.99/year**. ([App Store][9])

So:

```text
$11.99/mo
$59.99/yr
```

positions Langboard as **premium**, but doesn't ask Speak/Grammarly money while we're still launching with essentially one primary language pair.

I like it a lot as a launch point.

Once Langboard has multiple languages, mature personalization, great insights, etc., I'd test:

```text
$12.99/mo
$69.99/yr
```

against it.

Not immediately.

---

# Why I'd change the trial from 14 days to 7

The new research changed my view here.

RevenueCat's 2026 data shows longer trials *can* have higher trial-to-paid conversion overall, but it specifically calls **5–9 days a good balance**, and **50.3% of Education apps use 5–9-day trials**. ([RevenueCat][7])

So I'd start Langboard at **7 days**.

That's enough time for:

```text
Day 0    First real Fill Gap
Day 1    History starts accumulating
Day 2    First review
Day 3    Personal expression memory forming
Day 4    Repeated gaps / insights
Day 5    Another SRS cycle
Day 6    "Your week with Langboard"
Day 7    Convert
```

You don't actually need 14 days if we deliberately make the learning loop visible during the first week.

And a 7-day trial is already familiar in language apps; Speak, for example, has used a clear seven-day trial flow.

Later, A/B test:

```text
7 days
vs
14 days
```

Don't guess forever.

---

# The paywall itself

I'd show this extremely transparently:

```text
Langboard Pro

7 days free

Then $59.99/year
$5.00/month billed annually

✓ Cancel anytime before renewal


[ Start my 7-day free trial ]


Monthly
$11.99/month


Restore Purchases
Terms · Privacy
```

No weird trial toggle.

No hiding renewal price.

No countdown timers.

No fake “90% OFF.”

Apple has recently been rejecting confusing trial-toggle paywalls, so clear pricing is both better UX and safer. ([RevenueCat][10])

---

# After they start the trial: now setup begins

This is where my previous onboarding was too long.

**Keyboard setup is not sales onboarding.**

They've already converted.

So now we can say:

# **Let's put Langboard where you type.**

The model begins downloading in the background immediately.

```text
English ↔ Chinese
Preparing offline model...

████████░░  64%
```

Meanwhile:

```text
1. Add Langboard Keyboard
2. Switch with 🌐 when you need help
```

Button:

**Enable Langboard**

Open system keyboard settings.

When they return:

```text
✓ Keyboard enabled
```

Because the model download has been happening during this process, hopefully the user doesn't stare at a 600 MB download.

Apple explicitly warns against making large downloads block onboarding interaction. ([Apple Developer][1])

---

# Final setup screen

## **Try it once**

One editable field:

```text
这个也太 [insane] 了吧
```

Instruction:

> Switch to Langboard with 🌐

They actually invoke the keyboard.

It returns:

```text
离谱
```

Insert.

Then:

```text
✓ You're ready

[ Start using Langboard ]
```

Home.

That's it.

---

# Conversation Context is NOT onboarding

This moves later.

After they've successfully used Langboard a few times, perhaps the third time they're using it inside a chat:

> **Want better suggestions from the conversation around you?**

Then explain Android Conversation Context and request Accessibility.

Apple explicitly recommends asking for permissions **at the moment the feature needs them**, rather than dumping permission requests at launch. ([Apple Developer][11])

Same rule for notifications.

Don't request them at onboarding.

When they actually have SRS cards:

> **You have 7 expressions ready tomorrow. Want a reminder?**

Now the notification permission makes sense.

---

# RevenueCat implementation

This is how I'd structure it technically.

1. Create one entitlement in RevenueCat: **`pro`**.

2. Create two products in both stores:

```text
langboard_pro_monthly
langboard_pro_annual
```

Attach both to the same App Store subscription group / equivalent Google Play setup.

3. Configure a **7-day free introductory trial**. I'd initially allow the trial on both plans for simplicity; annual remains selected by default on the paywall. Trial eligibility is ultimately determined by the underlying store account. ([RevenueCat][4])

4. Create one RevenueCat Offering:

```text
default
  ├── monthly
  └── annual
```

5. Initialize the SDK **without an App User ID**:

```text
RevenueCat
→ generates anonymous user
→ no Langboard signup required
```

6. Every app start, read `CustomerInfo`.

Your product gate is basically:

```text
if pro entitlement is active:
    unlock Langboard
else:
    show onboarding/paywall
```

An active free trial counts as an active subscription entitlement.

7. Add a CustomerInfo listener so changes propagate immediately after purchase, renewal, cancellation/expiration, or restore.

8. Put **Restore Purchases** both on the paywall and under Settings. RevenueCat strongly recommends supporting restore. ([RevenueCat][5])

9. Use RevenueCat's **Offering**, not hard-coded product pricing, to populate the paywall. That means Apple/Google localized prices display correctly.

10. Use RevenueCat **Experiments** once traffic is meaningful. RevenueCat's current tooling lets you compare offerings/paywalls and separately measure trial starts and paid conversion. ([RevenueCat][12])

---

# What I'd test first

Don't launch five experiments at once.

The initial version is:

```text
7-day trial
$11.99 monthly
$59.99 annual
Annual preselected
Hard paywall
No account
```

Once you have enough paywall traffic, test exactly one major variable at a time.

First:

```text
$59.99 annual
vs
$69.99 annual
```

Then potentially:

```text
7-day trial
vs
14-day trial
```

Then paywall copy/visuals.

If you change pricing + trial + onboarding simultaneously, you learn nothing.

---

# The conversion funnel I'd track

This matters more than raw installs.

```text
App installed
    ↓
Onboarding started
    ↓
Demo completed
    ↓
Paywall viewed
    ↓
Trial started
    ↓
Keyboard enabled
    ↓
FIRST SUCCESSFUL INSERT   ← huge metric
    ↓
3 successful uses
    ↓
First review completed
    ↓
Day-3 active
    ↓
Trial → paid
    ↓
Month-1 retained
```

The metric I would obsess over isn't even trial-start initially.

It's:

> **What percentage of trial starters successfully use Langboard in another app within the first session?**

Because if someone starts the trial but never successfully enables/uses the keyboard, they're almost certainly cancelling.

---

# So the final onboarding is much simpler

```text
1  Say what you mean
   → interactive Langboard demo

2  Make it yours
   → language + Casual / Neutral / Work

3  Learn from real conversations
   → learning loop + one-line privacy promise

4  Start 7-day trial
   → $59.99 annual / $11.99 monthly

──────── PAID/TRIAL STARTED ────────

5  Install Langboard
   → model download + keyboard enable

6  Try it once
   → actual keyboard success
```

**Six screens total. Only four before conversion.**

Everything else—advanced style settings, Accessibility context, notifications, My Style explanation, SRS tutorial, history explanation—gets taught **contextually when the user encounters it**.

That's much closer to what I'd ship.

[1]: https://developer.apple.com/design/human-interface-guidelines/onboarding?changes=_7&utm_source=chatgpt.com "Onboarding | Apple Developer Documentation"
[2]: https://www.revenuecat.com/docs/playbooks/guides/hard-paywall?utm_source=chatgpt.com "Hard Paywalls | In-App Subscriptions Made Easy – RevenueCat"
[3]: https://www.revenuecat.com/docs/customers/identifying-customers?utm_source=chatgpt.com "Identifying Customers | In-App Subscriptions Made Easy – RevenueCat"
[4]: https://www-docs.revenuecat.com/docs/subscription-guidance/subscription-offers?utm_source=chatgpt.com "Free Trials & Promo Offers | In-App Subscriptions Made Easy – RevenueCat"
[5]: https://www.revenuecat.com/docs/getting-started/restoring-purchases?utm_source=chatgpt.com "Restoring Purchases | In-App Subscriptions Made Easy – RevenueCat"
[6]: https://screensdesign.com/explore/apps/duolingo/onboarding/?utm_source=chatgpt.com "Duolingo onboarding screens | ScreensDesign"
[7]: https://www.revenuecat.com/state-of-subscription-apps "State of Subscription Apps 2026 – RevenueCat"
[8]: https://www.revenuecat.com/state-of-subscription-apps?utm_source=chatgpt.com "State of Subscription Apps 2026 – RevenueCat"
[9]: https://apps.apple.com/us/app/speak-language-learning/id1286609883?platform=ipad&utm_source=chatgpt.com "‎Speak: Language Learning App - App Store"
[10]: https://www.revenuecat.com/blog/growth/rip-toggle-paywall?utm_source=chatgpt.com "R.I.P. toggle paywall: we hardly knew ye | RevenueCat"
[11]: https://developer.apple.com/design/human-interface-guidelines/privacy?changes=l_9_3&utm_source=chatgpt.com "Privacy | Apple Developer Documentation"
[12]: https://www.revenuecat.com/docs/tools/paywalls?utm_source=chatgpt.com "Paywalls | In-App Subscriptions Made Easy – RevenueCat"

