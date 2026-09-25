package app.langboard.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.langboard.core.QuickLookup
import java.io.File

/** One insertable Chinese rendering of an English headword, with its part of speech. */
data class QuickChip(val zh: String, val pos: String)

/** The dictionary row for a phrase: which headword matched, and its chips best first. */
data class QuickMatch(val headword: String, val chips: List<QuickChip>)

/**
 * English → Chinese quick matches from Open Dictionary (CC BY-SA 4.0, derived from Wiktionary),
 * bundled as `assets/opendict.db` and built by `shared/dictionary/build_open_dictionary.py`. Read-only and offline.
 */
class OpenDictionary private constructor(private val db: SQLiteDatabase) {

  /**
   * An instant answer, only for a single word the dictionary can translate on its own (concert,
   * deadline). Null for phrases and for words whose meaning depends on register or context
   * (insane, awkward), which are left to the model. The build script decides which words qualify.
   */
  fun lookup(phrase: String): QuickMatch? {
    if (!QuickLookup.isSingleWord(phrase)) return null
    for (k in QuickLookup.candidateKeys(phrase, ::lemmaOf)) {
      column("quick", k)?.let { return QuickMatch(k, it) }
      // A known headword without a quick answer is deliberately silent; don't fall through to its lemma.
      if (column("chips", k) != null) return null
    }
    return null
  }

  /**
   * Each content word on its own, for the "Words" breakdown. Words with no entry are left out.
   * A single word (the "Look it up" case) gets all of its meanings rather than the top few.
   */
  fun words(phrase: String): List<QuickMatch> {
    val words = QuickLookup.wordsOf(phrase)
    val limit = if (words.size == 1) Int.MAX_VALUE else QuickLookup.WORD_CHIPS
    return words.mapNotNull { w ->
      val k = column("chips", w)?.let { w } ?: lemmaOf(w) ?: QuickLookup.naiveSingular(w)
      k?.let { key -> column("chips", key)?.let { QuickMatch(w, it.take(limit)) } }
    }
  }

  private fun column(name: String, key: String): List<QuickChip>? =
    db.rawQuery("SELECT $name FROM entry WHERE key = ?", arrayOf(key)).use { c ->
      if (!c.moveToFirst() || c.isNull(0)) return null
      c.getString(0).split(RS).mapNotNull { part ->
        val (zh, pos) = part.split(US).let { it.getOrNull(0) to it.getOrNull(1) }
        zh?.let { QuickChip(it, pos.orEmpty()) }
      }
    }

  private fun lemmaOf(key: String): String? =
    db.rawQuery("SELECT lemma FROM form WHERE form = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

  companion object {
    private const val ASSET = "opendict.db"
    private const val US = '\u001f'
    private const val RS = '\u001e'
    @Volatile private var instance: OpenDictionary? = null

    /**
     * Opens the bundled index, copying it out of the APK once per app version (SQLite can't read
     * compressed assets in place). Blocking; call off the main thread.
     */
    fun get(context: Context): OpenDictionary = instance ?: synchronized(this) {
      instance ?: open(context.applicationContext).also { instance = it }
    }

    private fun open(context: Context): OpenDictionary {
      val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
      val file = File(context.noBackupFilesDir, "opendict-$version.db")
      if (!file.exists()) {
        context.noBackupFilesDir.listFiles { f -> f.name.startsWith("opendict") }?.forEach { it.delete() }
        val tmp = File(file.path + ".tmp")
        context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(file)
      }
      return OpenDictionary(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))
    }
  }
}
