package app.langboard.history

import app.langboard.core.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryCodecTest {
  @Test fun roundTrips() {
    val items = listOf(
      Candidate("不想折腾了", "bù xiǎng zhēteng le", "didn't want the hassle", "more like “didn't want the hassle”"),
      Candidate("算了", null, null, null),
    )
    assertEquals(items, HistoryCodec.decode(HistoryCodec.encode(items)))
  }

  @Test fun emptyIsEmpty() {
    assertEquals(emptyList<Candidate>(), HistoryCodec.decode(HistoryCodec.encode(emptyList())))
  }

  @Test fun separatorsInsideFieldsCantBreakRows() {
    val decoded = HistoryCodec.decode(HistoryCodec.encode(listOf(Candidate("a\tb", "c\nd", null))))
    assertEquals(listOf(Candidate("a b", "c d", null)), decoded)
  }
}
