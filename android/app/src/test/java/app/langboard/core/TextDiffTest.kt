package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDiffTest {
  private fun changed(old: String, new: String) = TextDiff.inserted(old, new).map { new.substring(it.first, it.last + 1) }

  @Test fun marksWhatWasAddedOrReplaced() {
    assertEquals(listOf("了", "部"), changed("我昨天看一本电影", "我昨天看了一部电影"))
    assertEquals(listOf("好", "啊"), changed("我是很累", "我好累啊"))
  }

  @Test fun sameTextHasNoChanges() {
    assertEquals(emptyList<String>(), changed("你好", "你好"))
  }
}
