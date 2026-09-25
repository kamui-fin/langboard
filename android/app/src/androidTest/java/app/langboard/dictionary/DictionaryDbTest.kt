package app.langboard.dictionary

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.langboard.cedict.Cedict
import app.langboard.cedict.CedictParser
import app.langboard.cedict.InMemoryCedictIndex
import app.langboard.cedict.Script
import app.langboard.cedict.SearchConfig
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Builds the fixture into real on-device SQLite and checks it against the in-memory reference. */
@RunWith(AndroidJUnit4::class)
class DictionaryDbTest {
  private lateinit var dir: File
  private lateinit var db: SQLiteDatabase
  private lateinit var result: DictionaryBuilder.Result

  @Before fun build() {
    dir = Fixtures.tempDir("db")
    val file = File(dir, "cedict.db")
    result = DictionaryBuilder.build(Fixtures.text().reader().buffered(), file, minEntries = 1)
    db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
  }

  @After fun cleanup() {
    db.close()
    dir.deleteRecursively()
  }

  @Test fun headerAndMeta() {
    assertEquals(Fixtures.ENTRY_COUNT, result.entries)
    assertEquals("2026-09-23T10:03:34Z", result.header.date)
    val meta = HashMap<String, String>()
    db.rawQuery("SELECT key, value FROM meta", null).use { while (it.moveToNext()) meta[it.getString(0)] = it.getString(1) }
    assertEquals(DictionarySchema.VERSION, meta["schema"])
    assertEquals(Fixtures.ENTRY_COUNT.toString(), meta["entries"])
  }

  @Test fun sqliteIndexMatchesInMemoryReferenceForEveryHeadword() {
    val reference = Cedict(InMemoryCedictIndex.fromLines(Fixtures.text().lineSequence()))
    val onDisk = Cedict(SqliteCedictIndex(db))
    val entries = Fixtures.text().lineSequence().mapNotNull(CedictParser::parseLine).toList()
    val words = entries.flatMap { e -> listOf(Script.Traditional to e.traditional, Script.Simplified to e.simplified) } +
      entries.flatMap { e -> e.variantOf.flatMap { listOf(Script.Traditional to it.traditional, Script.Simplified to it.simplified) } }
    val configs = listOf(
      SearchConfig(),
      SearchConfig(mergeCases = true),
      SearchConfig(allowVariants = false),
      SearchConfig(caseSensitiveSearch = false, mergeCases = true, allowVariants = false),
    )
    for ((script, word) in words.distinct()) for (config in configs) {
      assertEquals("$script $word $config", reference.getByWord(script, word, config = config), onDisk.getByWord(script, word, config = config))
    }
    assertEquals(reference.getByTraditional("前邊", "qian2 bian5"), onDisk.getByTraditional("前邊", "qian2 bian5"))
  }

  @Test fun buildIsCancellable() {
    try {
      DictionaryBuilder.build(Fixtures.text().reader().buffered(), File(dir, "cancel.db"), isActive = { false }, minEntries = 1)
      fail("expected cancellation")
    } catch (_: CancellationException) {
    }
  }

  @Test fun tooFewEntriesIsRejected() {
    try {
      DictionaryBuilder.build(Fixtures.text().reader().buffered(), File(dir, "small.db"), minEntries = 1_000)
      fail("expected IOException")
    } catch (e: IOException) {
      assertTrue(e.message!!.contains("entries"))
    }
  }

  @Test fun reportsProgress() {
    var calls = 0
    DictionaryBuilder.build(Fixtures.text().reader().buffered(), File(dir, "p.db"), minEntries = 1) { _, total ->
      calls++
      assertEquals(125101, total) // from the real header's `#! entries=` line
    }
    assertTrue(calls >= 1)
  }
}
