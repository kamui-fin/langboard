package app.langboard.cedict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from cc-cedict tests/build.test.ts. */
class CedictParserTest {
  private fun parse(line: String): CedictEntry = CedictParser.parseLine(line).also { assertNotNull(it) }!!

  @Test fun basicLine() {
    val e = parse("中國 中国 [Zhong1 guo2] /China/")
    assertEquals("中國", e.traditional)
    assertEquals("中国", e.simplified)
    assertEquals("Zhong1 guo2", e.pinyin)
    assertEquals(listOf("China"), e.english)
    assertEquals(emptyList<HanziRef>(), e.variantOf)
    assertEquals(emptyList<HanziRef>(), e.classifiers)
  }

  @Test fun multipleMeanings() {
    assertEquals(listOf("front", "the front side", "in front of"), parse("前邊 前边 [qian2 bian5] /front/the front side/in front of/").english)
  }

  @Test fun classifiers() {
    val e = parse("家具 家具 [jia1 ju4] /furniture/CL:件[jian4],套[tao4]/")
    assertEquals(listOf("furniture"), e.english)
    assertEquals(listOf(HanziRef("件", "件", "jian4"), HanziRef("套", "套", "tao4")), e.classifiers)
  }

  @Test fun classifierWithBothForms() {
    val e = parse("人 人 [ren2] /person; people/CL:個|个[ge4],位[wei4],名[ming2]/")
    assertEquals(listOf("person; people"), e.english)
    assertEquals(HanziRef("個", "个", "ge4"), e.classifiers[0])
    assertEquals(3, e.classifiers.size)
  }

  @Test fun variantLineKeepsTextLikeUpstream() {
    // The variant regex stops before the closing ']', so upstream keeps the sense text too.
    val e = parse("家俱 家俱 [jia1 ju4] /variant of 家具[jia1 ju4]/")
    assertEquals(listOf(HanziRef("家具", "家具", "jia1 ju4")), e.variantOf)
    assertEquals(listOf("variant of 家具[jia1 ju4]"), e.english)
  }

  @Test fun erhuaVariantKeepsMeaningText() {
    val e = parse("前邊兒 前边儿 [qian2 bian5 r5] /erhua variant of 前邊|前边[qian2 bian5]/")
    assertEquals("qian2 bian5 r5", e.pinyin)
    assertEquals(listOf(HanziRef("前邊", "前边", "qian2 bian5")), e.variantOf)
    assertEquals(listOf("erhua variant of 前邊|前边[qian2 bian5]"), e.english)
  }

  @Test fun variantWithoutHanziStaysAMeaning() {
    val e = parse("甲 甲 [jia3] /variant of the above/")
    assertEquals(listOf("variant of the above"), e.english)
    assertTrue(e.variantOf.isEmpty())
  }

  @Test fun umlautPinyinPreserved() {
    assertEquals("nu:3", parse("女 女 [nu:3] /female/woman/daughter/").pinyin)
  }

  @Test fun commentsBlankAndMalformed() {
    assertNull(CedictParser.parseLine("# This is a comment"))
    assertNull(CedictParser.parseLine(""))
    assertNull(CedictParser.parseLine("   "))
    assertNull(CedictParser.parseLine("not a valid line"))
  }

  @Test fun extensionAIdeograph() {
    assertEquals("㐀", parse("㐀 㐀 [qiu1] /archaic variant of 丘[qiu1]/").traditional)
  }

  @Test fun meaningsSkipEmptyAndSplitOnSlash() {
    assertEquals(listOf("China", "country"), CedictParser.parseMeanings("/China//country/").meanings)
    assertEquals(listOf("and", "or", "either...or..."), CedictParser.parseMeanings("and/or/either...or...").meanings)
  }

  @Test fun parseVariantForms() {
    assertEquals(HanziRef("家具", "家具", "jia1 ju4"), CedictParser.parseVariant("家具[jia1 ju4]"))
    assertEquals(HanziRef("家具", "傢具", "jia1 ju4"), CedictParser.parseVariant("家具|傢具[jia1 ju4]"))
    assertEquals(HanziRef("家具", "家具", null), CedictParser.parseVariant("家具"))
    assertNull(CedictParser.parseVariant(""))
  }

  @Test fun regexes() {
    assertEquals("CL:件[jian4],套[tao4]", CedictParser.CLASSIFIERS.find("CL:件[jian4],套[tao4]")!!.value)
    assertEquals(listOf("ni3", "hao3", "ma5"), CedictParser.PINYIN.findAll("ni3 hao3 ma5").map { it.value }.toList())
    assertTrue(CedictParser.VARIANT_OF.find("variant of 家具[jia1 ju4]")!!.value.startsWith("variant of"))
  }

  @Test fun header() {
    val h = CedictParser.parseHeader(
      sequenceOf("# CC-CEDICT", "#! entries=125101", "#! date=2026-09-23T10:03:34Z", "中國 中国 [Zhong1 guo2] /China/", "#! entries=1")
    )
    assertEquals(125101, h.entries)
    assertEquals("2026-09-23T10:03:34Z", h.date)
  }

  @Test fun variantInsideParenthesesKeepsSense() {
    val e = parse("坵 丘 [qiu1] /hillock; mound (variant of 丘[qiu1])/")
    assertEquals(listOf("hillock; mound (variant of 丘[qiu1])"), e.english)
    assertEquals(listOf(HanziRef("丘", "丘", "qiu1")), e.variantOf)
  }

  @Test fun duplicateVariantsAndClassifiersAreMerged() {
    val m = CedictParser.parseMeanings("variant of 家具[jia1 ju4]/old variant of 家具[jia1 ju4]/CL:件[jian4]/CL:件[jian4]")
    assertEquals(1, m.variantOf.size)
    assertEquals(1, m.classifiers.size)
  }

  @Test fun windowsLineEndings() {
    assertEquals(listOf("China"), parse("中國 中国 [Zhong1 guo2] /China/\r").english)
  }

  @Test fun missingPinyinOrSimplifiedIsMalformed() {
    assertNull(CedictParser.parseLine("中國 中国 /China/"))
    assertNull(CedictParser.parseLine("中國 [Zhong1 guo2] /China/"))
    assertNull(CedictParser.parseLine("中國 中国 [] /China/"))
  }

  @Test fun latinAndDigitHeadwords() {
    val e = parse("11區 11区 [11 Qu1] /(ACG) Japan/")
    assertEquals("11区", e.simplified)
    assertEquals("11 Qu1", e.pinyin)
  }

  @Test fun headerLine() {
    assertEquals("entries" to "125101", CedictParser.parseHeaderLine("#! entries=125101"))
    assertNull(CedictParser.parseHeaderLine("# comment"))
    assertNull(CedictParser.parseHeaderLine("#! novalue"))
  }
}
