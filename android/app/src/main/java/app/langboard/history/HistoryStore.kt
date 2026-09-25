package app.langboard.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.langboard.core.Candidate
import app.langboard.core.Fsrs
import app.langboard.core.ReviewRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What the user asked Langboard for. */
enum class HistoryKind {
  /** English in a Chinese draft → Chinese. */
  Fill,
  /** A Chinese draft checked for naturalness. */
  Check,
  /** A received message explained. */
  Explain,
  /** One word saved from a breakdown. */
  Word,
}

/** What happened after Langboard answered. */
enum class Outcome {
  /** Answered; the user looked and moved on. */
  Shown,
  /** The answer (or an alternative) went into the message. */
  Inserted,
  /** Inserted, then undone. */
  Undone,
  /** Langboard had no answer. */
  NoAnswer,
}

/**
 * One invocation of Langboard. For [HistoryKind.Fill], [source] is the English and [answer] the
 * Chinese; for Check, the draft and its natural version; for Explain, the message and its key
 * expression, with the translation in [sentence].
 */
data class HistoryEntry(
  val id: Long = 0,
  val createdAt: Long,
  val kind: HistoryKind,
  val app: String?,
  val source: String,
  val answer: String?,
  val pinyin: String? = null,
  val meaning: String? = null,
  /** Fill: the sentence as it reads with the answer in it. Explain: the translation. */
  val sentence: String? = null,
  /** Fill: alternatives. Check: each change. Explain: the other expressions. */
  val items: List<Candidate> = emptyList(),
  val register: String? = null,
  /** Nearby chat messages that informed the answer; 0 when the screen wasn't read. */
  val contextMessages: Int = 0,
  val outcome: Outcome = Outcome.Shown,
  /** What was actually inserted, when it wasn't [answer]. */
  val used: String? = null,
  val saved: Boolean = false,
  val updatedAt: Long = createdAt,
)

/**
 * Langboard's own history, on this device only: every time it was invoked, what it answered and
 * whether the user used it. Screen text is never stored, only how many messages were read.
 * Shared by the keyboard and the app, which run in one process.
 */
class HistoryStore private constructor(context: Context) {
  private val helper = Helper(context.applicationContext)

  private val _version = MutableStateFlow(0)
  /** Bumped on every change, so screens can reload. */
  val version: StateFlow<Int> = _version.asStateFlow()

  suspend fun add(e: HistoryEntry): Long = io {
    helper.writableDatabase.insert(TABLE, null, values(e)).also { changed() }
  }

  suspend fun setOutcome(id: Long, outcome: Outcome, used: String? = null, sentence: String? = null) = io {
    update(id, ContentValues().apply {
      put("outcome", outcome.name)
      if (used != null) put("used", used)
      if (sentence != null) put("sentence", sentence)
    })
  }

  /** [answer], when given, becomes the entry's answer: the option the user saved, if not the first. */
  suspend fun setSaved(id: Long, saved: Boolean, answer: String? = null) = io {
    update(id, ContentValues().apply {
      put("saved", if (saved) 1 else 0)
      if (answer != null) { put("answer", answer); putNull("pinyin"); putNull("meaning") }
    })
  }

  suspend fun delete(id: Long) = io { helper.writableDatabase.delete(TABLE, "id = ?", arrayOf(id.toString())); changed() }

  /** Everything: history, review progress and the review log. */
  suspend fun clear() = io {
    helper.writableDatabase.run { delete(TABLE, null, null); delete(CARDS, null, null); delete(LOG, null, null) }
    changed()
  }

  /** Every logged review, oldest first, for tuning FSRS. */
  suspend fun reviewRecords(): List<ReviewRecord> = io {
    helper.readableDatabase.query(LOG, arrayOf("key", "reviewed_at", "rating"), null, null, null, null, "reviewed_at").use { c ->
      buildList {
        while (c.moveToNext()) {
          val rating = Fsrs.Rating.entries.firstOrNull { it.value == c.getInt(2) } ?: continue
          add(ReviewRecord(c.getString(0), c.getLong(1), rating))
        }
      }
    }
  }

  /** Since [start] of the review day: new cards seen for the first time, and reviews of learned (Review-state) cards. */
  suspend fun reviewedSince(start: Long): DailyCounts = io {
    val db = helper.readableDatabase
    val args = arrayOf(start.toString())
    val new = db.rawQuery("SELECT COUNT(*) FROM (SELECT MIN(reviewed_at) AS first FROM $LOG GROUP BY key) WHERE first >= ?", args)
      .use { it.moveToFirst(); it.getInt(0) }
    val reviews = db.rawQuery("SELECT COUNT(*) FROM $LOG WHERE reviewed_at >= ? AND state = '${Fsrs.State.Review.name}'", args)
      .use { it.moveToFirst(); it.getInt(0) }
    DailyCounts(new, reviews)
  }

  data class DailyCounts(val newCards: Int, val reviews: Int)

  /** Review progress for every card reviewed at least once, by [reviewKey]. */
  suspend fun reviewCards(): Map<String, Fsrs.Card> = io {
    helper.readableDatabase.query(CARDS, null, null, null, null, null, null).use { c ->
      fun d(col: String) = c.getColumnIndexOrThrow(col).let { if (c.isNull(it)) null else c.getDouble(it) }
      fun l(col: String) = c.getColumnIndexOrThrow(col).let { if (c.isNull(it)) null else c.getLong(it) }
      buildMap {
        while (c.moveToNext()) {
          val state = runCatching { Fsrs.State.valueOf(c.getString(c.getColumnIndexOrThrow("state"))) }.getOrNull() ?: continue
          put(
            c.getString(c.getColumnIndexOrThrow("key")),
            Fsrs.Card(state, l("step")?.toInt(), d("stability"), d("difficulty"), l("due")!!, l("last_review")),
          )
        }
      }
    }
  }

  /**
   * Stores [card] as it is after being rated [rating], and logs the review with the state the card
   * was in before it ([before]): the log is what FSRS is tuned on and what daily limits count.
   */
  suspend fun saveReview(key: String, before: Fsrs.State?, card: Fsrs.Card, rating: Fsrs.Rating, durationMs: Long) = io {
    helper.writableDatabase.run {
      beginTransaction()
      try {
        insertWithOnConflict(CARDS, null, ContentValues().apply {
          put("key", key)
          put("state", card.state.name)
          put("step", card.step)
          put("stability", card.stability)
          put("difficulty", card.difficulty)
          put("due", card.due)
          put("last_review", card.lastReview)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        insert(LOG, null, ContentValues().apply {
          put("key", key)
          put("reviewed_at", card.lastReview)
          put("rating", rating.value)
          put("duration_ms", durationMs)
          put("state", before?.name)
        })
        setTransactionSuccessful()
      } finally {
        endTransaction()
      }
    }
    changed()
  }

  suspend fun get(id: Long): HistoryEntry? = io {
    helper.readableDatabase.query(TABLE, null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
      if (c.moveToFirst()) read(c) else null
    }
  }

  suspend fun list(filter: Filter = Filter.All, limit: Int = 500): List<HistoryEntry> = io {
    val where = when (filter) {
      Filter.All -> null
      Filter.Used -> "outcome = '${Outcome.Inserted.name}'"
      Filter.Saved -> "saved = 1"
    }
    helper.readableDatabase.query(TABLE, null, where, null, null, null, "created_at DESC", limit.toString()).use { c ->
      buildList { while (c.moveToNext()) add(read(c)) }
    }
  }

  enum class Filter { All, Used, Saved }

  private fun update(id: Long, v: ContentValues) {
    v.put("updated_at", System.currentTimeMillis())
    helper.writableDatabase.update(TABLE, v, "id = ?", arrayOf(id.toString()))
    changed()
  }

  private fun changed() { _version.value++ }

  private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

  private class Helper(context: Context) : SQLiteOpenHelper(context, FILE, null, SCHEMA) {
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(
        """CREATE TABLE $TABLE(
          id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
          kind TEXT NOT NULL, app TEXT, source TEXT NOT NULL, answer TEXT, pinyin TEXT, meaning TEXT,
          sentence TEXT, items TEXT NOT NULL, register TEXT, context_messages INTEGER NOT NULL,
          outcome TEXT NOT NULL, used TEXT, saved INTEGER NOT NULL)"""
      )
      db.execSQL("CREATE INDEX history_created ON $TABLE(created_at)")
      createReview(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      if (oldVersion < 2) createReview(db)
      else if (oldVersion < 3) db.execSQL("ALTER TABLE $LOG ADD COLUMN state TEXT")
    }

    /** Review: one row per card with its FSRS state, and every review in the order it happened. */
    private fun createReview(db: SQLiteDatabase) {
      db.execSQL(
        """CREATE TABLE $CARDS(
          key TEXT PRIMARY KEY, state TEXT NOT NULL, step INTEGER, stability REAL, difficulty REAL,
          due INTEGER NOT NULL, last_review INTEGER)"""
      )
      db.execSQL(
        """CREATE TABLE $LOG(
          id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL, reviewed_at INTEGER NOT NULL,
          rating INTEGER NOT NULL, duration_ms INTEGER NOT NULL, state TEXT)"""
      )
      db.execSQL("CREATE INDEX review_log_key ON $LOG(key)")
    }
  }

  companion object {
    const val FILE = "history.db"
    private const val TABLE = "history"
    private const val CARDS = "review_cards"
    private const val LOG = "review_log"
    private const val SCHEMA = 3

    /** One review card per Chinese answer and kind, however many times it was looked up. */
    fun reviewKey(e: HistoryEntry) = "${e.kind.name}|${e.answer}"

    @Volatile private var instance: HistoryStore? = null

    fun get(context: Context): HistoryStore = instance ?: synchronized(this) {
      instance ?: HistoryStore(context).also { instance = it }
    }

    private fun values(e: HistoryEntry) = ContentValues().apply {
      put("created_at", e.createdAt)
      put("updated_at", e.updatedAt)
      put("kind", e.kind.name)
      put("app", e.app)
      put("source", e.source)
      put("answer", e.answer)
      put("pinyin", e.pinyin)
      put("meaning", e.meaning)
      put("sentence", e.sentence)
      put("items", HistoryCodec.encode(e.items))
      put("register", e.register)
      put("context_messages", e.contextMessages)
      put("outcome", e.outcome.name)
      put("used", e.used)
      put("saved", if (e.saved) 1 else 0)
    }

    private fun read(c: Cursor): HistoryEntry {
      fun s(col: String) = c.getColumnIndexOrThrow(col).let { if (c.isNull(it)) null else c.getString(it) }
      fun l(col: String) = c.getLong(c.getColumnIndexOrThrow(col))
      return HistoryEntry(
        id = l("id"),
        createdAt = l("created_at"),
        updatedAt = l("updated_at"),
        kind = runCatching { HistoryKind.valueOf(s("kind")!!) }.getOrDefault(HistoryKind.Fill),
        app = s("app"),
        source = s("source").orEmpty(),
        answer = s("answer"),
        pinyin = s("pinyin"),
        meaning = s("meaning"),
        sentence = s("sentence"),
        items = HistoryCodec.decode(s("items").orEmpty()),
        register = s("register"),
        contextMessages = l("context_messages").toInt(),
        outcome = runCatching { Outcome.valueOf(s("outcome")!!) }.getOrDefault(Outcome.Shown),
        used = s("used"),
        saved = l("saved") == 1L,
      )
    }
  }
}

/** Candidates as text: one per line, fields separated by tabs. Tabs and newlines inside fields become spaces. */
object HistoryCodec {
  fun encode(items: List<Candidate>): String = items.joinToString("\n") { c ->
    listOf(c.text, c.pinyin, c.meaning, c.nuance).joinToString("\t") { it.orEmpty().replace('\t', ' ').replace('\n', ' ') }
  }

  fun decode(s: String): List<Candidate> = if (s.isEmpty()) emptyList() else s.split('\n').map { line ->
    val f = line.split('\t')
    fun at(i: Int) = f.getOrNull(i)?.takeIf { it.isNotEmpty() }
    Candidate(f[0], at(1), at(2), at(3))
  }
}
