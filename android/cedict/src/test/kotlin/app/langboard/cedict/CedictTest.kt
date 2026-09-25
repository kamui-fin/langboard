package app.langboard.cedict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from cc-cedict tests/cedict.test.ts, against real CC-CEDICT lines in fixture.u8. */
class CedictTest {
  private val cedict = Cedict(
    InMemoryCedictIndex.fromLines(javaClass.getResourceAsStream("/fixture.u8")!!.bufferedReader().lineSequence())
  )

  @Test fun bySimplified() {
    val r = cedict.getBySimplified("中国")!!
    val e = r.getValue("Zhong1 guo2").single()
    assertEquals("中國", e.traditional)
    assertEquals(listOf("China"), e.english)
    assertFalse(e.isVariant)
  }

  @Test fun byTraditionalHasSameKeys() {
    assertEquals(cedict.getBySimplified("中国")!!.keys, cedict.getByTraditional("中國")!!.keys)
  }

  @Test fun missingAndEmpty() {
    assertNull(cedict.getBySimplified("这个词不存在的啦"))
    assertNull(cedict.getBySimplified(""))
    assertNull(cedict.getBySimplified("中国", "nonexistent1"))
  }

  @Test fun pinyinFilterIncludesErhuaVariant() {
    val entries = cedict.getByTraditional("前邊", "qian2 bian5")!!.getValue("qian2 bian5")
    assertTrue(entries.single { it.traditional == "前邊" }.english.contains("front"))
    assertTrue(entries.single { it.traditional == "前邊兒" }.isVariant)
  }

  @Test fun caseInsensitivePinyin() {
    assertTrue(cedict.getByTraditional("前邊", "QIAN2 bian5", SearchConfig(caseSensitiveSearch = false))!!.containsKey("qian2 bian5"))
    assertNull(cedict.getByTraditional("前邊", "QIAN2 bian5"))
  }

  @Test fun casesKeptSeparateByDefault() {
    val r = cedict.getByTraditional("張")!!
    assertEquals(listOf("Zhang1", "zhang1"), r.keys.toList())
    assertEquals(setOf("zhang1"), cedict.getBySimplified("张", "zhang1")!!.keys)
    assertEquals(setOf("Zhang1"), cedict.getBySimplified("张", "Zhang1")!!.keys)
  }

  @Test fun mergeCases() {
    val r = cedict.getByTraditional("張", config = SearchConfig(mergeCases = true))!!
    assertEquals(setOf("zhang1"), r.keys)
    val e = r.getValue("zhang1").single()
    assertEquals("surname Zhang", e.english.first())
    assertTrue(e.english.contains("to open up"))
  }

  @Test fun flattenedFormHasSeveralReadings() {
    val all = cedict.getBySimplified("只")!!.values.flatten()
    assertTrue(all.map { it.pinyin }.toSet().size > 1)
  }

  @Test fun variantsIncludedByDefault() {
    val entries = cedict.getBySimplified("家具")!!.getValue("jia1 ju4")
    assertEquals(4, entries.size)
    val main = entries.single { !it.isVariant }
    assertEquals(2, main.classifiers.size)
    for (v in entries.filter { it.isVariant }) {
      assertEquals(HanziRef("家具", "家具", "jia1 ju4"), v.variantOf.first())
    }
  }

  @Test fun variantsExcluded() {
    val entries = cedict.getBySimplified("家具", config = SearchConfig(allowVariants = false))!!.getValue("jia1 ju4")
    assertEquals(listOf("furniture"), entries.single().english)
    assertFalse(entries.single().isVariant)
  }

  @Test fun singleAndLongWords() {
    assertTrue(cedict.getBySimplified("人")!!.containsKey("ren2"))
    assertTrue(cedict.getBySimplified("中华人民共和国") != null)
  }

  @Test fun wordThatOnlyExistsAsAVariantTarget() {
    // 汝 is its own entry, and 女[ru3] is an "old variant of 汝[ru3]".
    val entries = cedict.getBySimplified("汝")?.values?.flatten().orEmpty()
    assertTrue(entries.any { it.traditional == "女" && it.isVariant })
  }

  @Test fun umlautReadingIsItsOwnKey() {
    val r = cedict.getBySimplified("绿")!!
    assertEquals(listOf("lu4", "lu:4"), r.keys.toList())
    assertTrue(r.getValue("lu:4").single().english.contains("green"))
  }

  @Test fun traditionalOnlyHeadword() {
    // 隻 and 衹 are traditional forms of simplified 只.
    assertEquals("只", cedict.getByTraditional("隻")!!.values.flatten().first().simplified)
    assertNull(cedict.getBySimplified("隻"))
  }

  @Test fun simplifiedSharedByManyTraditionalForms() {
    val trads = cedict.getBySimplified("只", "zhi3")!!.getValue("zhi3").map { it.traditional }.toSet()
    assertTrue(trads.containsAll(listOf("只", "祇", "衹")))
  }

  @Test fun mergeCasesWithoutVariants() {
    val r = cedict.getByTraditional("張", config = SearchConfig(mergeCases = true, allowVariants = false))!!
    assertEquals(listOf("zhang1"), r.values.flatten().map { it.pinyin })
  }

  @Test fun caseInsensitiveMatchesBothCases() {
    val r = cedict.getByTraditional("張", "ZHANG1", SearchConfig(caseSensitiveSearch = false))!!
    assertEquals(listOf("Zhang1", "zhang1"), r.keys.toList())
  }

  @Test fun expandCarriesAllFields() {
    val e = Cedict.expand(CedictParser.parseLine("家具 家具 [jia1 ju4] /furniture/CL:件[jian4],套[tao4]/")!!, isVariant = true)
    assertEquals("家具", e.simplified)
    assertEquals(2, e.classifiers.size)
    assertTrue(e.isVariant)
  }
}
