package app.langboard.dictionary

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Full install pipeline from a file:// "download": fetch → unzip → build → verify → swap. No network. */
@RunWith(AndroidJUnit4::class)
class DictionaryManagerTest {
  private lateinit var root: File
  private lateinit var dataDir: File
  private lateinit var cacheDir: File

  @Before fun setUp() {
    root = Fixtures.tempDir("manager")
    dataDir = File(root, "data").apply { mkdirs() }
    cacheDir = File(root, "cache").apply { mkdirs() }
  }

  @After fun tearDown() {
    root.deleteRecursively()
  }

  private fun manager(source: File) = DictionaryManager(dataDir, cacheDir, source.toURI().toString(), minEntries = 1)

  private fun goodZip() = Fixtures.zip(File(root, "good").apply { mkdirs() })

  @Test fun startsNotInstalled() {
    val m = manager(goodZip())
    assertEquals(DictionaryStatus.NotInstalled, m.status.value)
    assertFalse(m.isInstalled)
    assertNull(runBlocking { m.search("furniture") })
  }

  @Test fun installsAndSearches() = runBlocking {
    val m = manager(goodZip())
    m.install().join()
    val status = m.status.value as DictionaryStatus.Installed
    assertEquals(Fixtures.ENTRY_COUNT, status.info.entries)
    assertEquals("2026-09-23T10:03:34Z", status.info.sourceDate)
    assertTrue(status.info.sizeBytes > 0)
    assertEquals("家具", m.search("furniture")!!.first().entry.simplified)
    // Transient files are gone.
    assertFalse(File(cacheDir, "cedict-download.zip").exists())
    assertFalse(File(dataDir, "cedict.db.building").exists())
  }

  @Test fun installSurvivesARestart() = runBlocking {
    manager(goodZip()).install().join()
    val reopened = manager(goodZip())
    assertTrue(reopened.status.value is DictionaryStatus.Installed)
    assertEquals("中国", reopened.search("中国")!!.first().entry.simplified)
  }

  @Test fun archiveWithoutDictionaryFails() = runBlocking {
    val bad = Fixtures.zip(File(root, "bad").apply { mkdirs() }, entryName = "readme.txt")
    val m = manager(bad)
    m.install().join()
    val status = m.status.value as DictionaryStatus.Failed
    assertNull(status.installed)
    assertFalse(m.isInstalled)
  }

  @Test fun missingSourceFails() = runBlocking {
    val m = manager(File(root, "does-not-exist.zip"))
    m.install().join()
    assertTrue(m.status.value is DictionaryStatus.Failed)
  }

  @Test fun failedUpdateKeepsPreviousDictionary() = runBlocking {
    manager(goodZip()).install().join()
    val notAZip = File(root, "garbage.zip").apply { writeText("not a zip") }
    val m = manager(notAZip)
    m.install().join()
    val status = m.status.value as DictionaryStatus.Failed
    assertEquals(Fixtures.ENTRY_COUNT, status.installed!!.entries)
    assertEquals("家具", m.search("furniture")!!.first().entry.simplified)
  }

  @Test fun truncatedDictionaryIsRejected() = runBlocking {
    val tiny = Fixtures.zip(File(root, "tiny").apply { mkdirs() }, content = Fixtures.text().lines().take(40).joinToString("\n"))
    val m = DictionaryManager(dataDir, cacheDir, tiny.toURI().toString(), minEntries = 1_000)
    m.install().join()
    assertTrue(m.status.value is DictionaryStatus.Failed)
    assertFalse(File(dataDir, "cedict.db").exists())
  }

  @Test fun removeDeletesDatabase() = runBlocking {
    val m = manager(goodZip())
    m.install().join()
    m.remove()
    assertEquals(DictionaryStatus.NotInstalled, m.status.value)
    assertNull(m.search("furniture"))
    assertFalse(File(dataDir, "cedict.db").exists())
  }

  @Test fun corruptDatabaseIsDiscardedOnOpen() {
    File(dataDir, "cedict.db").writeText("garbage")
    File(dataDir, "cedict.db.building").writeText("leftover")
    val m = manager(goodZip())
    assertEquals(DictionaryStatus.NotInstalled, m.status.value)
    assertFalse(File(dataDir, "cedict.db").exists())
    assertFalse(File(dataDir, "cedict.db.building").exists())
  }

  @Test fun installWhileRunningReturnsSameJob() = runBlocking {
    val m = manager(goodZip())
    val a = m.install()
    val b = m.install()
    assertTrue(a === b)
    a.join()
  }
}
