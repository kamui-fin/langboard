package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLookupTest {
  private val lemmas = mapOf("matches" to "match", "went" to "go", "hanging" to "hang")
  private fun keys(p: String) = QuickLookup.candidateKeys(p, lemmas::get)

  @Test fun phraseFirstThenBaseForm() = assertEquals(listOf("top matches", "top match"), keys("top matches"))
  @Test fun singleWordFallsBackToLemma() = assertEquals(listOf("matches", "match"), keys("Matches"))
  @Test fun firstWordInflection() = assertEquals(listOf("hanging out", "hang out"), keys("hanging out"))
  @Test fun naivePluralWhenDictionaryHasNoForm() = assertEquals(listOf("big deals", "big deal"), keys("big deals"))
  @Test fun normalizesCaseQuotesAndSpacing() = assertEquals(listOf("i'm down"), keys("  I’m   Down! "))
  @Test fun emptyInputHasNoKeys() = assertEquals(emptyList<String>(), keys("  "))

  @Test fun singularRules() {
    assertEquals("party", QuickLookup.naiveSingular("parties"))
    assertEquals("match", QuickLookup.naiveSingular("matches"))
    assertEquals("cat", QuickLookup.naiveSingular("cats"))
    assertEquals(null, QuickLookup.naiveSingular("boss"))
    assertEquals(null, QuickLookup.naiveSingular("bus"))
    assertEquals(null, QuickLookup.naiveSingular("this"))
  }

  @Test fun wordsDropFunctionWords() {
    assertEquals(listOf("hard", "move", "past"), QuickLookup.wordsOf("hard to move past"))
    assertEquals(listOf("bothered"), QuickLookup.wordsOf("I couldn't be bothered anymore"))
    assertEquals(listOf("top", "matches"), QuickLookup.wordsOf("top matches"))
  }

  @Test fun onlySingleWordsGetQuickAnswers() {
    assertTrue(QuickLookup.isSingleWord("Concert"))
    assertTrue(QuickLookup.isSingleWord(" deadline! "))
    assertFalse(QuickLookup.isSingleWord("hard to move past"))
    assertFalse(QuickLookup.isSingleWord("can't"))
    assertFalse(QuickLookup.isSingleWord(""))
  }

  @Test fun wordsOnlyWhenThereIsABreakdown() {
    assertTrue(QuickLookup.hasWords("top matches"))
    assertTrue(QuickLookup.hasWords("I couldn't be bothered anymore"))
    assertFalse(QuickLookup.hasWords("insane"))
    assertFalse(QuickLookup.hasWords("the"))
  }
}
