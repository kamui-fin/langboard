package app.langboard.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmenterTest {
  private val dict = setOf("阴阳怪气", "阴阳", "怪气", "你们", "牛奶", "等你", "半天")

  @Test fun longestMatchWins() {
    assertEquals(listOf("你", "别", "在", "那", "阴阳怪气", "的"), Segmenter.segment("你别在那阴阳怪气的", dict::contains))
  }

  @Test fun nonChineseRunsStayWhole() {
    assertEquals(listOf("我们", "等你", "半天", "了", " 😭"), Segmenter.segment("我们等你半天了 😭", setOf("我们", "等你", "半天")::contains))
  }

  @Test fun unknownCharactersAreSingleWords() {
    assertEquals(listOf("喝", "牛奶"), Segmenter.segment("喝牛奶", dict::contains))
  }
}
