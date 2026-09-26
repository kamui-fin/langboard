package app.langboard.history

import app.langboard.core.MyStyle
import app.langboard.core.StyleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleEvidenceTest {
  private val hour = 60 * 60 * 1000L

  private fun fill(
    at: Long, source: String, answer: String, used: String? = null,
    outcome: Outcome = Outcome.Inserted, saved: Boolean = false, sentence: String? = null,
  ) = HistoryEntry(
    createdAt = at * hour, kind = HistoryKind.Fill, app = null, source = source, answer = answer,
    used = used, outcome = outcome, saved = saved, sentence = sentence,
  )

  @Test fun usualIsWhatTheyPickedOrSavedOrSentTwice() {
    val e = StyleEvidence(
      listOf(
        fill(1, "insane", "太疯狂了", used = "离谱"),
        fill(2, "Can't be bothered", "懒得"),
        fill(5, "can't  be bothered", "懒得"),
        fill(3, "whatever", "随便"),
        fill(4, "awkward", "尴尬", outcome = Outcome.Shown, saved = true),
      )
    )
    assertEquals("离谱", e.usualFor("Insane"))
    assertEquals("懒得", e.usualFor("can't be bothered"))
    assertNull("sent once is not a habit", e.usualFor("whatever"))
    assertEquals("尴尬", e.usualFor("awkward"))
  }

  @Test fun undoneChoicesDontCount() {
    val e = StyleEvidence(listOf(fill(1, "insane", "太疯狂了", used = "离谱", outcome = Outcome.Undone)))
    assertNull(e.usualFor("insane"))
  }

  @Test fun swapsAreSingleShortChineseChanges() {
    assertEquals(StyleEvidence.Swap("挺", "很"), StyleEvidence.swap("很好的", "挺好的"))
    assertEquals(StyleEvidence.Swap("啥", "什么"), StyleEvidence.swap("你说什么呢", "你说啥呢"))
    assertNull(StyleEvidence.swap("太疯狂了", "离谱"))
    assertNull(StyleEvidence.swap("好的", "好的呀呀呀呀"))
  }

  @Test fun proposesARuleAfterThreeSeparateOccasions() {
    val picks = listOf(
      fill(1, "pretty good", "很好", used = "挺好"),
      fill(1, "pretty good", "很好", used = "挺好"), // same half hour: one occasion
      fill(3, "quite tired", "很累", used = "挺累"),
    )
    assertTrue(StyleEvidence(picks).proposals(MyStyle()).isEmpty())
    val more = picks + fill(6, "pretty nice", "很不错", used = "挺不错")
    val p = StyleEvidence(more).proposals(MyStyle()).single() as StyleEvidence.Proposal.Rule
    assertEquals(StyleRule(StyleRule.Kind.Prefer, "挺", "很", learned = true), p.rule)
    assertEquals(3, p.occasions)
    // Not again once it's in, turned down, or contradicted by something the user said.
    assertTrue(StyleEvidence(more).proposals(MyStyle().with(p.rule)).isEmpty())
    assertTrue(StyleEvidence(more).proposals(MyStyle(dismissed = setOf(p.rule.line))).isEmpty())
    assertTrue(StyleEvidence(more).proposals(MyStyle().with(StyleRule(StyleRule.Kind.Prefer, "很", "挺"))).isEmpty())
  }

  @Test fun proposesShorterWhenTheyKeepPickingShorterOptions() {
    val picks = (0 until 4).map { fill(it * 2L, "x$it", "我真的不太想去了", used = "不想去") }
    assertTrue(StyleEvidence(picks).proposals(MyStyle()).any { it is StyleEvidence.Proposal.Shorter })
    assertTrue(StyleEvidence(picks.take(3)).proposals(MyStyle()).none { it is StyleEvidence.Proposal.Shorter })
  }

  @Test fun examplesAreTheirOwnSentencesClosestToTheDraft() {
    val e = StyleEvidence(
      listOf(
        fill(1, "a", "懒得", sentence = "算了懒得去了"),
        fill(2, "b", "离谱", sentence = "这也太离谱了吧"),
        fill(3, "c", "好", sentence = "好", outcome = Outcome.Inserted),
        fill(4, "d", "尴尬", sentence = "有点尴尬了哈", outcome = Outcome.Shown),
      )
    )
    assertEquals(listOf("这也太离谱了吧"), e.examples("他这个也太", pasted = emptyList()))
    assertEquals(listOf("算了懒得折腾", "算了懒得去了"), e.examples("算了懒得", pasted = listOf("算了懒得折腾")))
    assertTrue(e.examples("hello", emptyList()).isEmpty())
  }
}
