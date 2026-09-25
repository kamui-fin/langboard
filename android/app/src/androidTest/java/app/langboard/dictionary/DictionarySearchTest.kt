package app.langboard.dictionary

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.langboard.dictionary.DictionaryHit.Kind
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite FTS4 + porter tokenizer on the device, over the fixture. */
@RunWith(AndroidJUnit4::class)
class DictionarySearchTest {
  private lateinit var dir: File
  private lateinit var db: SQLiteDatabase
  private lateinit var search: DictionarySearch

  @Before fun setUp() {
    dir = Fixtures.tempDir("search")
    val file = File(dir, "cedict.db")
    DictionaryBuilder.build(Fixtures.text().reader().buffered(), file, minEntries = 1)
    db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    search = DictionarySearch(db)
  }

  @After fun tearDown() {
    db.close()
    dir.deleteRecursively()
  }

  private fun app(q: String, limit: Int = 50) = search.search(q, limit, wholePhrase = false)
  private fun keyboard(q: String) = search.search(q, 20, wholePhrase = true)

  @Test fun englishExactSenseRanksFirst() {
    val hits = app("furniture")
    assertEquals(listOf("家具", "家什", "家居卖场"), hits.map { it.entry.simplified })
    assertTrue(hits.all { it.kind == Kind.English })
  }

  @Test fun porterStemmingMatchesInflections() {
    assertTrue(app("bothered").any { it.entry.simplified == "打扰" }) // "to bother"
    assertTrue(app("walking").any { it.entry.simplified == "走" })
  }

  @Test fun searchAsYouTypePrefix() {
    assertEquals("家具", app("furn").first().entry.simplified)
    assertTrue("trailing space ends the prefix", app("furn ").isEmpty())
  }

  @Test fun caseAndPunctuationInsensitive() {
    assertEquals(app("furniture").map { it.entry }, app("  FURNITURE!! ").map { it.entry })
  }

  @Test fun chineseExactThenPrefix() {
    val hits = app("中国")
    assertEquals("中国" to Kind.Exact, hits[0].entry.simplified to hits[0].kind)
    assertEquals("中国人" to Kind.Prefix, hits[1].entry.simplified to hits[1].kind)
  }

  @Test fun traditionalQueryFindsSimplifiedEntry() {
    val hits = app("張")
    assertEquals(setOf("Zhang1", "zhang1"), hits.filter { it.kind == Kind.Exact }.map { it.entry.pinyin }.toSet())
    assertTrue(hits.all { it.entry.simplified == "张" })
  }

  @Test fun exactVariantsComeAfterMainEntry() {
    val hits = app("家具").filter { it.kind == Kind.Exact }
    assertEquals("家具", hits.first().entry.traditional)
    assertTrue(hits.drop(1).all { it.entry.isVariant })
  }

  @Test fun prefixSkipsWordsAlreadyShownAsExact() {
    val hits = app("家")
    val keys = hits.map { "${it.entry.traditional}_${it.entry.pinyin}" }
    assertEquals(keys.distinct(), keys)
    assertTrue(hits.any { it.entry.simplified == "家具" && it.kind == Kind.Prefix })
  }

  @Test fun prefixOrdersShorterWordsFirst() {
    val prefix = app("中").filter { it.kind == Kind.Prefix }.map { it.entry.simplified }
    assertEquals(prefix.sortedBy { it.length }, prefix)
  }

  @Test fun limitIsRespected() {
    assertEquals(1, app("家", limit = 1).size)
    assertEquals(2, app("furniture", limit = 2).size)
    assertTrue(app("家", limit = 0).isEmpty())
  }

  @Test fun emptyAndUnknown() {
    assertTrue(app("").isEmpty())
    assertTrue(app("   ").isEmpty())
    assertTrue(app("xylophone").isEmpty())
    assertTrue(app("鑫").isEmpty())
  }

  @Test fun keyboardPhraseFallsBackToOr() {
    // No sense contains every content word, so the keyboard falls back to OR and still helps.
    val hits = keyboard("I couldn't be bothered anymore")
    assertTrue(hits.isNotEmpty())
    assertTrue(hits.any { it.entry.simplified == "受窘" })
  }

  @Test fun keyboardDoesNotUsePrefixMatching() {
    assertTrue(keyboard("furn").isEmpty())
  }

  @Test fun appSearchDoesNotFallBackToOr() {
    assertTrue(app("furniture xylophone").isEmpty())
  }
}
