package app.langboard.cedict

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs only with CEDICT_U8 pointing at a real cedict_ts.u8. */
class FullDictionaryTest {
  @Test fun parsesEveryEntry() {
    val path = System.getenv("CEDICT_U8")
    assumeTrue("CEDICT_U8 not set", path != null)
    val file = File(path!!)
    val header = file.useLines { CedictParser.parseHeader(it) }
    val started = System.nanoTime()
    val index = file.useLines { InMemoryCedictIndex.fromLines(it) }
    println("parsed ${index.size} entries (header ${header.entries}) in ${(System.nanoTime() - started) / 1_000_000} ms")
    assertEquals(header.entries, index.size)
    val cedict = Cedict(index)
    assertTrue(cedict.getBySimplified("懒得")!!.values.flatten().isNotEmpty())
  }
}
