package app.langboard.cedict

import org.junit.Assert.assertEquals
import org.junit.Test

class PinyinTest {
  private fun t(numbered: String) = Pinyin.toToneMarks(numbered)

  @Test fun basics() {
    assertEquals("Zhōng guó", t("Zhong1 guo2"))
    assertEquals("nǐ hǎo", t("ni3 hao3"))
    assertEquals("qián bian", t("qian2 bian5"))
  }

  @Test fun placementRules() {
    assertEquals("hǎo", t("hao3"))   // a wins
    assertEquals("xuě", t("xue3"))   // e wins
    assertEquals("dōu", t("dou1"))   // ou → o
    assertEquals("guì", t("gui4"))   // last vowel
    assertEquals("liù", t("liu4"))
  }

  @Test fun umlautAndErhua() {
    assertEquals("nǚ", t("nu:3"))
    assertEquals("lǜ", t("lu:4"))
    assertEquals("Lǚ", t("Lu:3"))
    assertEquals("qián bian r", t("qian2 bian5 r5"))
  }

  @Test fun nonSyllablesPassThrough() {
    assertEquals("11 Qū", t("11 Qu1"))
    assertEquals("A B", t("A B"))
    assertEquals("m", t("m1")) // syllabic m/n/ng have no vowel to mark
  }

  @Test fun moreSyllables() {
    assertEquals("lüè", t("lu:e4"))
    assertEquals("ér", t("er2"))
    assertEquals("Ōu zhōu", t("Ou1 zhou1"))
    assertEquals("xiǎo", t("xiao3"))
    assertEquals("shuǐ", t("shui3"))
    assertEquals("xx", t("xx5"))
    assertEquals("", t(""))
  }

  @Test fun punctuationTokensUntouched() {
    assertEquals("Mǎ kè · Tǔ wēn", t("Ma3 ke4 · Tu3 wen1"))
    assertEquals("A A zhì", t("A A zhi4"))
  }
}
