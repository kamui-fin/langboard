I would **not** choose between “monochrome” and “fully system-colored.” I’d split Langboard into two layers:

> **Native platform shell + consistent Langboard content identity.**

That gives you the premium native feeling **without making the brand disappear**.

Apple is basically recommending this exact separation with Liquid Glass: standard navigation/controls live in the system UI layer, while brand expression moves into the content layer. ([Apple Developer][1]) Android similarly treats dynamic color as a core Material You personalization feature, with your own light/dark palette as the fallback. ([Android Developers][2])

## My recommendation

### Android: embrace Material You / Dynamic Color

Yes, I'd use the user's system palette **by default**.

![Image](https://images.openai.com/static-rsc-4/1wo9zBeypAVPZEuLJu7fHG6uu5MiVeYkXsFDNfWRL5BU1-iRcpGEvpUx9OwmN0qkSF7TrHvlnN4hVsXu86pF5u_dXtu0wCjOMYqOa6njy7LJBXGCsKHogN-M6cdKH6XUm5JSYn2tRbl0htrg4XiH1ticc_Ql-5xrQtWjb-SE2O5Ts_KoortVhlO21aRJCui_?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/BG-FMD4s4tykYxeaP65oC3UDLnXN7W_Zr_9S-S8nH5fOC6HsjlqrCi2iIp6bH7ZnnPnOhpf5XzKud33gaMBWVDwIvzkpIH9QYXD7unV7N-fJ_f3V_nu4hc4eiXx3V0-9-23q1mqwejDFuXkjG-36_zdP_5jFRFVty0B2NnG81X2QO2Dd68gfMtaDpDt-Dphv?purpose=fullsize)

Langboard is unusually well-suited to it because the whole product is about:

> **your language, your style, your way of speaking.**

Having the app subtly belong to *their phone* reinforces that.

Use:

```text
Android 12+
→ dynamicLightColorScheme()
→ dynamicDarkColorScheme()

Older Android / dynamic color disabled
→ Langboard fallback palette
```

That's exactly the path Android's current Material 3 guidance supports. ([Android Developers][3])

But I would **not allow Material You to recolor absolutely everything**.

Think:

```text
SYSTEM / CHROME
───────────────
Buttons
selected tabs
chips
switches
containers
navigation
→ Material You colors


LANGBOARD CONTENT
─────────────────
Langboard ✦ mark
replacement highlights
learning/memory visuals
illustrations
special progress moments
→ restrained brand identity
```

So someone with a pink wallpaper might get soft rose buttons/containers, but:

```text
我本来想去但是 [I couldn't be bothered anymore]
                  ↓
              懒得再去了
```

still looks unmistakably like a Langboard interaction.

### Give Android one setting

Under Appearance:

```text
Color

● Match my device
○ Langboard
```

Default = **Match my device**.

That gives people who want the pure branded aesthetic an escape hatch without making them configure anything during onboarding.

---

# iOS: absolutely use native Liquid Glass

But **don't “design a glass app.”**

That's an important distinction.

![Image](https://images.openai.com/static-rsc-4/37b0U3BCr0CVr9oq8A58-fmFyLv77z5OO6rSNPIWAdlteKNJpxFtfVpepjGwU77Db3uVuwtFGTtiUAxa5TLdSyS3nClKP64YVFkWZA9gICDqhCX3R5r83H8mqndOSzvQEdQx21f2vSul-0IEBe5gyDXf5LgMEFd1__MKSpGhCvkLxzmvlH47P_cHKsOtdZ9d?purpose=fullsize)

![Image](https://images.openai.com/static-rsc-4/yc3hGOiJVXy4BBgJXoBeIJ09kNuDhjkUPsnfiiw9BNKInJ7G_Ft9miGT4OkGQOuGQ0a-EMxUZj6hh9SSvGl3xhzfWhkVyn-kJBzmgh6Q5K4XxdM1MdBWYfC-y1uQgTd1AxDVtIEs5rL2kKx6KXXAAvAbMKfPprhkVGPfd-nqsJZQbohRlloulrn3D4AGmqUQ?purpose=fullsize)

Use SwiftUI's native:

* navigation bars;
* tab bars;
* sheets;
* popovers;
* menus;
* buttons;
* search;
* toolbars.

And let current iOS provide Liquid Glass automatically.

Apple explicitly says standard SwiftUI/UIKit components get the new material automatically, and recommends **removing custom backgrounds that fight it**. ([Apple Developer][1])

So:

```text
BAD

glass card
 inside glass card
   inside glass sheet
      with glass button
```

No.

Apple specifically warns against overusing Liquid Glass, and its newer guidance says glass belongs mainly in the **control layer hugging the content**, not splattered through content itself. ([Apple Developer][1])

Instead:

```text
┌──────────────────────────────────┐
│         Liquid Glass chrome      │
├──────────────────────────────────┤
│                                  │
│         CLEAN CONTENT            │
│                                  │
│  懒得再去了                      │
│                                  │
│  Your original                  │
│  I couldn't be bothered anymore │
│                                  │
│                                  │
├──────────────────────────────────┤
│      native glass tab bar        │
└──────────────────────────────────┘
```

That will look much more expensive than trying to create custom glass everywhere.

---

# So should the *content* be monochrome?

**Mostly.**

That's the direction I would choose.

Langboard should have an editorial, almost writing-tool aesthetic:

* lots of whitespace;
* typography doing most of the work;
* neutral surfaces;
* very subtle separators;
* extremely restrained color;
* Chinese/English text itself being visually prominent.

I don't want:

```text
purple card
blue card
green streak
yellow vocab badge
red achievement
orange review box
```

That's language-app visual noise.

I want something closer to:

```text
                         懒得再去了

                  couldn't be bothered

                       Casual · Mainland


               You learned this Sep 24
```

The language itself is the visual content.

---

# But not literally black-and-white

Pure monochrome would make Langboard elegant but also somewhat anonymous.

I think it needs **one recognizable signature color**.

And my pick would be a **deep mineral jade / green-teal**.

Not Chinese cliché jade.

Not bright Duolingo green.

Something subdued and grown-up.

Approximately:

```text
LANGBOARD JADE
#347A68

LIGHT ACCENT
#3E806F

DARK-MODE ACCENT
#79C3AE
```

with:

```text
INK
#171A18

PORCELAIN
#F7F7F3

MUTED INK
#676C69

SOFT SURFACE
#EFEEE9
```

Don't treat those exact hex values as sacred yet; I'd tune them visually.

The palette should feel like:

> paper + ink + one beautiful annotation color.

That's extremely appropriate for a language/writing product.

---

# Where the jade appears

Rarely.

### Brand mark

```text
✦ Langboard
```

The ✦/symbol can carry it.

### The thing Langboard taught you

```text
我本来想去但是 [English]

            ↓

          懒得
          ^^^^
          jade
```

This could become a strong visual motif.

**Langboard's brand color literally represents the piece of language you acquired.**

I love that semantically.

---

### Selected / learned states

```text
✓ Learned
```

### Important visualization

Recurring gap progress.

### Marketing illustrations

One highlighted fragment among monochrome language.

That's about it.

Apple's current branding guidance is actually aligned with this: use a brand accent **judiciously** because applying it everywhere weakens its effect. ([Apple Developer][4])

---

# The brand should NOT depend on the app color scheme

This matters.

If Android is wallpaper-purple one day and wallpaper-orange another, someone should still recognize Langboard from:

* logo;
* icon;
* typography;
* spacing;
* language-edit animation;
* highlight treatment;
* voice;
* layouts.

**Color is only one component of identity.**

That makes adapting to Material You totally safe.

---

# App icon

Here I'd be much more branded.

Something extremely simple.

Perhaps:

```text
┌─────────┐
│         │
│    ✦    │
│   文A   │   <- maybe not literally this
│         │
└─────────┘
```

Although I'd avoid the cliché `文/A` translate icon because that immediately says **Google Translate clone**.

Better to build a unique abstract mark around:

* insertion;
* cursor;
* missing span;
* completion;
* conversation;
* language switching.

Maybe a glyph that looks like:

```text
[   ✦   ]
```

or a cursor/gap becoming complete.

The full icon gets the Langboard jade.

On Android, also ship a proper **monochrome themed icon layer** so users who theme all icons get a native-looking Langboard icon.

---

# Landing page: DO NOT use Material You

The website is where **you completely control the brand**.

So it should use the canonical Langboard palette.

I'd go:

```text
Warm porcelain background
#F7F7F3-ish

Near-black typography
#171A18-ish

Langboard jade
~#347A68

Occasional pale jade tint
for large content backgrounds
```

Think editorial, not SaaS gradient.

---

# Landing-page visual direction

Hero:

```text
                        LANGBOARD ✦


             Say what you actually mean.
          In the language you're learning.


      Get unstuck in real conversations.
        Learn it for the next one.


              [ Try Langboard free ]
```

Then the product does the explaining:

```text
我本来想去但是
I couldn't be bothered anymore

               ↓

我本来想去但是懒得再去了
```

The English fragment can be muted gray.

`懒得再去了` animates into jade.

That's the brand.

No giant AI orb.

No purple/blue glowing mesh.

No stock illustration of two people speaking Mandarin.

No flags everywhere.

---

# Landing-page visual motif

I think the **replacement operation itself** can become your whole graphic language.

For example:

### Hero

```text
I couldn't be bothered anymore
──────────────────────────────
          懒得再去了
```

### Naturalize

```text
我没有那个兴趣了
        ↓
我已经没啥兴趣了
       ───────
```

### Learn

```text
懒得折腾

           Sep 21      learned
           Sep 24      reviewed
           Sep 27      used yourself ✓
```

One accent color shows **language becoming yours**.

That's coherent branding.

---

# Marketing screenshots should stay branded

This is another distinction.

**In-app Android UI:** dynamic color.

**Play Store / App Store screenshots:** use Langboard's canonical brand palette.

Because screenshots need to form a coherent visual identity in a storefront.

So your App Store set might have:

```text
PORCELAIN
───────

Finish the sentence,
not your thought.

[phone]


PALE JADE
─────────

Learn the phrases
you actually needed.


INK
───

Sounds like you.
Because it learns from you.
```

That lets the actual screenshot inside the phone show the native platform treatment while the surrounding artwork carries the brand.

---

# Website can use some translucency—but not copy Liquid Glass

I wouldn't make the website pretend to be iOS.

You can have:

* subtle frosted navbar;
* soft transparent overlays;
* sophisticated blur;
* beautiful motion.

But don't make Langboard's web identity:

> “glassmorphism.”

Apple owns Liquid Glass as a platform language.

Your brand should survive if that design trend changes in five years.

---

# Typography is probably more important than color

For a language product, typography is *everything*.

I'd keep:

### Apps

System fonts.

**iOS:** San Francisco/system.

**Android:** system/Roboto family / Material defaults.

This contributes massively to “native.”

Apple's current branding guidance explicitly says custom type can work for expressive headings while system fonts are often best for smaller body text. ([Apple Developer][4])

### Website

You can afford more personality.

I'd use one excellent grotesk/sans display family with great CJK pairing.

Something with:

* very clean Latin;
* excellent numerals;
* neutral personality;
* not “tech startup bubbly”;
* large gorgeous display type.

Then a good Chinese system/CJK font stack.

The logo wordmark could also be custom.

---

# And motion becomes a major part of the brand

This is especially good because it survives platform color adaptation.

Your signature motion:

```text
source fragment
     ↓
brief shimmer / focus
     ↓
replacement appears
     ↓
surrounding text never moves
```

That communicates:

**we preserve what you wrote.**

Then when saved:

```text
replacement
     ↓
shrinks smoothly
     ↓
enters Memory
```

When mastered:

```text
previously highlighted phrase
     ↓
user produces it themselves
     ↓
jade highlight fades away

"You've got this one."
```

That's a much more distinctive brand asset than a hex code.

---

# My final system would therefore be

| Surface              | Design                                                     |
| -------------------- | ---------------------------------------------------------- |
| **Android app**      | Material 3/Expressive + Dynamic Color by default           |
| **Android content**  | Mostly neutral; Langboard signature treatments selectively |
| **iOS app**          | Native SwiftUI + Liquid Glass chrome                       |
| **iOS content**      | Flat/clean content layer + selective brand accent          |
| **Keyboard**         | Extremely neutral/native; minimal accent                   |
| **App icon**         | Strong fixed Langboard identity + Android themed variant   |
| **Landing page**     | Fixed Langboard porcelain/ink/jade palette                 |
| **Store graphics**   | Fixed branded palette                                      |
| **Emails/marketing** | Fixed branded palette                                      |
| **Typography**       | Native system fonts in apps; more expressive on web        |
| **Motion**           | Consistent Langboard-specific replacement/learning motion  |

So yes: **monochrome-ish content, native adaptive chrome, one fixed brand accent.**

I think that's significantly stronger than either extreme of:

> “make everything black and white”

or

> “let Android randomly decide the entire visual identity.”

It makes Langboard feel like **a premium native tool on the user's phone while still being unmistakably Langboard everywhere else.**

[1]: https://developer.apple.com/documentation/TechnologyOverviews/adopting-liquid-glass?changes=_2_11&utm_source=chatgpt.com "Adopting Liquid Glass | Apple Developer Documentation"
[2]: https://developer.android.com/develop/ui/compose/designsystems/material3?authuser=6&utm_source=chatgpt.com "Material Design 3 in Compose  |  Jetpack Compose  |  Android Developers"
[3]: https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary?utm_source=chatgpt.com "androidx.compose.material3  |  API reference  |  Android Developers"
[4]: https://developer.apple.com/design/human-interface-guidelines/branding?utm_source=chatgpt.com "Branding | Apple Developer Documentation"

