package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RubyAlignerTest {
  private val marks = { s: String -> s }  // keep numbered pinyin, to check alignment only

  @Test fun alignsSyllablesWithCharacters() {
    assertEquals(
      listOf(Ruby("银", "yin2", 2), Ruby("行", "hang2", 2)),
      RubyAligner.align("银行", "yin2 hang2", marks),
    )
  }

  @Test fun refusesWhenTheyDontLineUp() {
    assertNull(RubyAligner.align("AA制", "A A zhi4", marks))
    assertNull(RubyAligner.align("哪儿", "na3 r5 x", marks))
  }

  @Test fun byCharacterKeepsOtherRunsWhole() {
    val table = mapOf('我' to "wo3", '好' to "hao3")
    assertEquals(
      listOf(Ruby("我", "wo3", 3), Ruby("ok ", null), Ruby("好", "hao3", 3), Ruby("！", null)),
      RubyAligner.byCharacter("我ok 好！", { table[it] }, marks),
    )
  }
}
