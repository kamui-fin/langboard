package app.langboard.dictionary

import app.langboard.cedict.DictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryRulesTest {
  private fun entry(simplified: String, pinyin: String, vararg english: String) =
    DictionaryEntry(simplified, simplified, pinyin, english.toList(), emptyList(), emptyList(), isVariant = false)

  @Test fun chineseDetection() {
    assertTrue(QueryRules.isChinese("中国"))
    assertTrue(QueryRules.isChinese("go 去"))
    assertTrue(QueryRules.isChinese("𠀀")) // Extension B
    assertFalse(QueryRules.isChinese("furniture"))
    assertFalse(QueryRules.isChinese("，。")) // CJK punctuation alone isn't a Han query
  }

  @Test fun prefixUpperBound() {
    assertEquals("中亾", QueryRules.prefixUpperBound("中亽"))
    assertEquals("a", QueryRules.prefixUpperBound("`"))
    // Supplementary code point increments as one unit, not a broken surrogate.
    assertEquals("𠀁", QueryRules.prefixUpperBound("𠀀"))
    val upper = QueryRules.prefixUpperBound("家")
    assertTrue("家具" < upper && "家" < "家具")
  }

  @Test fun wordsAndStopwords() {
    val words = QueryRules.words("I couldn't be bothered anymore!")
    assertEquals(listOf("i", "couldn", "t", "be", "bothered", "anymore"), words)
    assertEquals(listOf("bothered"), QueryRules.contentWords(words))
  }

  @Test fun onlyStopwordsFallsBackToAllWords() {
    assertEquals(listOf("to", "be"), QueryRules.contentWords(QueryRules.words("to be")))
    assertEquals(emptyList<String>(), QueryRules.contentWords(QueryRules.words("!?")))
  }

  @Test fun ftsQueries() {
    assertEquals("home furn*", QueryRules.ftsAnd(listOf("home", "furn"), prefixLast = true))
    assertEquals("home furn", QueryRules.ftsAnd(listOf("home", "furn"), prefixLast = false))
    assertEquals("over OR school", QueryRules.ftsOr(listOf("over", "school")))
  }

  @Test fun senseNormalization() {
    assertEquals("open", QueryRules.normalizeSense("to open (a door)"))
    assertEquals("not feel like", QueryRules.normalizeSense("to not feel like (doing sth)"))
    assertEquals("furniture store", QueryRules.normalizeSense("Furniture store"))
    assertEquals("today", QueryRules.normalizeSense("(coll.) today"))
  }

  @Test fun exactSenseOutranksContaining() {
    val ranked = QueryRules.rank(
      listOf(
        entry("家居卖场", "jia1 ju1 mai4 chang3", "furniture store", "furniture mall"),
        entry("家什", "jia1 shi5", "utensils", "furniture"),
        entry("家具", "jia1 ju4", "furniture"),
      ),
      phrase = "furniture",
      content = listOf("furniture"),
    )
    assertEquals(listOf("家具", "家什", "家居卖场"), ranked.map { it.simplified })
  }

  @Test fun verbSensesMatchWithoutTo() {
    val bother = entry("打扰", "da3 rao3", "to disturb", "to bother")
    val troublesome = entry("麻烦", "ma2 fan5", "trouble; inconvenience", "to bother sb; to put sb to trouble")
    assertEquals(listOf("打扰", "麻烦"), QueryRules.rank(listOf(troublesome, bother), "bother", listOf("bother")).map { it.simplified })
  }

  @Test fun surnamesProperNounsAndVariantsSinkBelowWords() {
    val surname = entry("张", "Zhang1", "surname Zhang")
    val word = entry("张", "zhang1", "to open up", "sheet of paper")
    val variantOnly = entry("家俱", "jia1 ju4", "variant of 家具[jia1 ju4]")
    assertTrue(QueryRules.score(word, "zhang", listOf("zhang")) > QueryRules.score(surname, "zhang", listOf("zhang")))
    assertTrue(QueryRules.score(variantOnly, "furniture", listOf("furniture")) < 0)
  }

  @Test fun tiesPreferShorterHeadwordThenShorterDefinition() {
    val ranked = QueryRules.rank(
      listOf(entry("非常累", "x", "tired"), entry("累", "lei4", "tired"), entry("乏", "fa2", "tired")),
      "tired", listOf("tired"),
    )
    // 累 and 乏 tie on score and length; 非常累 is longer.
    assertEquals(setOf("累", "乏"), ranked.take(2).map { it.simplified }.toSet())
    assertEquals("非常累", ranked.last().simplified)
  }
}
