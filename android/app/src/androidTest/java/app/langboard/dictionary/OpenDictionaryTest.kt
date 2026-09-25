package app.langboard.dictionary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Against the bundled asset: plain vocabulary answers instantly, everything else stays silent. */
@RunWith(AndroidJUnit4::class)
class OpenDictionaryTest {
  private val dict = OpenDictionary.get(InstrumentationRegistry.getInstrumentation().targetContext)
  private fun quick(p: String) = dict.lookup(p)?.chips?.map { it.zh }

  @Test fun plainVocabularyAnswers() {
    assertEquals("音乐会", quick("concert")?.first())
    assertEquals("咖啡", quick("Coffee")?.first())
    assertEquals("机场", quick("airport")?.first())
    assertEquals("尴尬", quick("embarrassed")?.first())
  }

  @Test fun inflectionsFindTheirBaseWord() = assertEquals("会议", quick("meetings")?.first())

  @Test fun registerHeavyWordsStaySilent() {
    listOf("insane", "awkward", "chill", "crazy", "random", "overkill").forEach { assertNull(it, quick(it)) }
  }

  @Test fun phrasesAreLeftToTheModel() {
    assertNull(quick("hard to move past"))
    assertNull(quick("give up"))
  }

  @Test fun wordsBreakdownStillCoversEveryWord() {
    val words = dict.words("hard to move past").map { it.headword }
    assertEquals(listOf("hard", "move", "past"), words)
    assertTrue(dict.words("I couldn't be bothered anymore").single().chips.isNotEmpty())
  }
}
