package app.langboard.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.langboard.core.Candidate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryStoreTest {
  private val store = HistoryStore.get(InstrumentationRegistry.getInstrumentation().targetContext)
  /** Rows this test made; the user's own history on the test phone is left alone. */
  private val made = mutableListOf<Long>()

  private suspend fun add(e: HistoryEntry) = store.add(e).also { made += it }

  private fun fill(source: String, at: Long) = HistoryEntry(
    createdAt = at, kind = HistoryKind.Fill, app = "com.tencent.mm", source = source, answer = "懒得再去了",
    pinyin = "lǎnde zài qù le", items = listOf(Candidate("不想折腾了", "bù xiǎng zhēteng le", null, "hassle")),
    register = "casual", contextMessages = 3,
  )

  @After fun cleanUp() = runBlocking { made.forEach { store.delete(it) } }

  @Test fun addAndReadBack() = runBlocking {
    val id = add(fill("I couldn't be bothered", 1_000))
    val e = store.get(id)!!
    assertEquals("懒得再去了", e.answer)
    assertEquals(Outcome.Shown, e.outcome)
    assertEquals("不想折腾了", e.items.single().text)
    assertEquals(3, e.contextMessages)
  }

  @Test fun outcomeAndSavedUpdate() = runBlocking {
    val id = add(fill("I couldn't be bothered", 1_000))
    store.setOutcome(id, Outcome.Inserted, used = "不想折腾了", sentence = "我本来想去但是不想折腾了")
    store.setSaved(id, true)
    val e = store.get(id)!!
    assertEquals(Outcome.Inserted, e.outcome)
    assertEquals("不想折腾了", e.used)
    assertEquals("我本来想去但是不想折腾了", e.sentence)
    assertTrue(e.saved)
    assertTrue(e.updatedAt >= e.createdAt)
  }

  @Test fun filtersAndNewestFirst() = runBlocking {
    val old = add(fill("old", 1_000))
    val new = add(fill("new", 2_000))
    store.setOutcome(old, Outcome.Inserted)
    store.setSaved(new, true)
    val all = store.list().filter { it.id in made }.map { it.id }
    assertEquals(listOf(new, old), all)
    assertEquals(listOf(old), store.list(HistoryStore.Filter.Used).filter { it.id in made }.map { it.id })
    assertEquals(listOf(new), store.list(HistoryStore.Filter.Saved).filter { it.id in made }.map { it.id })
  }

  @Test fun deleteRemoves() = runBlocking {
    val id = add(fill("gone", 1_000))
    val before = store.version.value
    store.delete(id)
    assertNull(store.get(id))
    assertTrue(store.version.value > before)
  }
}
