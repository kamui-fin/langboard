package app.langboard.cedict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CedictIndexBuilderTest {
  private fun entry(line: String) = CedictParser.parseLine(line)!!

  @Test fun idsAreSequentialInSourceOrder() {
    val b = CedictIndexBuilder()
    assertEquals(0, b.add(entry("中國 中国 [Zhong1 guo2] /China/"))!!.id)
    assertEquals(1, b.add(entry("人 人 [ren2] /person/"))!!.id)
    assertEquals(2, b.count)
  }

  @Test fun baseRowsForBothScripts() {
    val rows = CedictIndexBuilder().add(entry("中國 中国 [Zhong1 guo2] /China/"))!!.rows
    assertEquals(
      listOf(
        IndexRow(Script.Traditional, "中國", "Zhong1 guo2", 0, false),
        IndexRow(Script.Simplified, "中国", "Zhong1 guo2", 0, false),
      ),
      rows,
    )
  }

  @Test fun variantRowsPointAtTheReferencedWord() {
    val rows = CedictIndexBuilder().add(entry("前邊兒 前边儿 [qian2 bian5 r5] /erhua variant of 前邊|前边[qian2 bian5]/"))!!.rows
    assertEquals(
      listOf(
        IndexRow(Script.Traditional, "前邊", "qian2 bian5", 0, true),
        IndexRow(Script.Simplified, "前边", "qian2 bian5", 0, true),
      ),
      rows.filter { it.isVariant },
    )
  }

  @Test fun variantWithoutPinyinIsNotIndexed() {
    val e = CedictEntry("甲", "甲", "jia3", listOf("x"), emptyList(), listOf(HanziRef("乙", "乙", null)))
    assertEquals(0, CedictIndexBuilder().add(e)!!.rows.count { it.isVariant })
  }

  @Test fun duplicatesAreSkippedAndCounted() {
    val b = CedictIndexBuilder()
    b.add(entry("人 人 [ren2] /person/"))
    assertNull(b.add(entry("人 人 [ren2] /people/")))
    assertEquals(1, b.count)
    assertEquals(1, b.duplicates)
    // Same characters, different reading is not a duplicate.
    assertEquals(1, b.add(entry("人 人 [Ren2] /surname/"))!!.id)
  }

  @Test fun inMemoryIndexKeepsSourceOrderPerReading() {
    val index = InMemoryCedictIndex.fromLines(
      sequenceOf(
        "家具 家具 [jia1 ju4] /furniture/",
        "傢俱 家俱 [jia1 ju4] /variant of 家具[jia1 ju4]/",
        "傢具 傢具 [jia1 ju4] /variant of 家具[jia1 ju4]/",
      )
    )
    val e = index.lookup(Script.Simplified, "家具")!!.getValue("jia1 ju4")
    assertEquals(listOf(0), e.base.toList())
    assertEquals(listOf(1, 2), e.variants.toList())
    assertNull(index.lookup(Script.Simplified, "没有"))
    assertEquals(3, index.size)
  }
}
