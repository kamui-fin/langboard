package app.langboard.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.langboard.cedict.DictionaryEntry
import app.langboard.core.FragmentDetector
import app.langboard.core.Segmenter
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DictionaryInfo(val entries: Int, val sourceDate: String?, val sizeBytes: Long)

sealed interface DictionaryStatus {
  data object NotInstalled : DictionaryStatus
  data class Downloading(val bytes: Long, val totalBytes: Long?) : DictionaryStatus
  data class Building(val entries: Int, val totalEntries: Int?) : DictionaryStatus
  data class Installed(val info: DictionaryInfo) : DictionaryStatus
  data class Failed(val message: String, val installed: DictionaryInfo?) : DictionaryStatus
}

/**
 * Owns the downloaded CC-CEDICT database for the whole process (companion app and keyboard).
 *
 * Install: download the MDBG zip → stream-parse into a scratch SQLite file → verify → rename over the
 * current database. The previous dictionary stays usable until the new one is complete. Only the app
 * UI starts downloads; the keyboard only reads.
 */
class DictionaryManager internal constructor(
  /** Holds the installed database. */
  private val dir: File,
  /** Holds the transient download. */
  cacheDir: File,
  private val sourceUrl: String = SOURCE_URL,
  private val minEntries: Int = DictionaryBuilder.MIN_ENTRIES,
) {
  private val current = File(dir, "cedict.db")
  private val scratch = File(dir, "cedict.db.building")
  private val download = File(cacheDir, "cedict-download.zip")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = ReentrantReadWriteLock()
  private var db: SQLiteDatabase? = null
  private var search: DictionarySearch? = null
  private var job: Job? = null

  private val _status = MutableStateFlow<DictionaryStatus>(DictionaryStatus.NotInstalled)
  val status: StateFlow<DictionaryStatus> = _status.asStateFlow()

  init {
    // Leftovers from a killed install are never loaded.
    scratch.delete()
    download.delete()
    _status.value = lock.write { open() }?.let { DictionaryStatus.Installed(it) } ?: DictionaryStatus.NotInstalled
  }

  val isInstalled get() = lock.read { search != null }

  /**
   * [text] split into dictionary words, each with its everyday entry (null for punctuation and
   * unknown characters). Null when no dictionary is installed.
   */
  suspend fun breakdown(text: String): List<Pair<String, DictionaryEntry?>>? = withContext(Dispatchers.IO) {
    lock.read {
      val s = search ?: return@read null
      Segmenter.segment(text, s::isHeadword).map { w -> w to (if (w.any(FragmentDetector::isCjkIdeograph)) s.best(w) else null) }
    }
  }

  /** Null when no dictionary is installed. Safe to call from any process component; never touches the network. */
  suspend fun search(query: String, limit: Int = 50, wholePhrase: Boolean = false): List<DictionaryHit>? =
    withContext(Dispatchers.IO) {
      lock.read { search?.search(query, limit, wholePhrase) }
    }

  /** Starts (or returns the running) download + install. The returned job is for tests to await. */
  fun install(): Job {
    job?.takeIf { it.isActive }?.let { return it }
    return scope.launch {
      val previous = installedInfo()
      try {
        dir.mkdirs()
        if (dir.usableSpace < REQUIRED_FREE_BYTES) {
          _status.value = DictionaryStatus.Failed("Not enough storage. The dictionary needs about 80 MB free.", previous)
          return@launch
        }
        _status.value = DictionaryStatus.Downloading(0, null)
        fetch()
        _status.value = DictionaryStatus.Building(0, null)
        val built = ZipInputStream(download.inputStream().buffered()).use { zip ->
          generateSequence { zip.nextEntry }.firstOrNull { it.name == SOURCE_FILE }
            ?: throw IOException("$SOURCE_FILE missing from archive")
          val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8), 1 shl 16)
          DictionaryBuilder.build(reader, scratch, isActive = { isActive }, minEntries = minEntries) { done, total ->
            _status.value = DictionaryStatus.Building(done, total)
          }
        }
        ensureActive()
        Log.i(TAG, "built ${built.entries} entries, ${scratch.length() / 1024} KB")
        val info = lock.write {
          close()
          if (!scratch.renameTo(current)) throw IOException("could not move dictionary into place")
          open()
        } ?: throw IOException("installed dictionary failed to open")
        _status.value = DictionaryStatus.Installed(info)
      } catch (e: CancellationException) {
        _status.value = previous?.let { DictionaryStatus.Installed(it) } ?: DictionaryStatus.NotInstalled
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "install failed", e)
        val message = when (e) {
          is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Couldn't download the dictionary. Check your connection and try again."
          is HttpException -> "The dictionary server isn't responding. Try again later."
          else -> "Couldn't set up the dictionary. Try again."
        }
        _status.value = DictionaryStatus.Failed(message, previous)
      } finally {
        download.delete()
        scratch.delete()
      }
    }.also { job = it }
  }

  fun cancel() {
    job?.cancel()
  }

  fun remove() {
    cancel()
    lock.write {
      close()
      current.delete()
    }
    _status.value = DictionaryStatus.NotInstalled
  }

  private suspend fun fetch() = withContext(Dispatchers.IO) {
    val conn = URL(sourceUrl).openConnection().apply {
      connectTimeout = 15_000
      readTimeout = 30_000
    }
    try {
      // file:// sources (tests) have no status code.
      if (conn is HttpURLConnection && conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
      val total = conn.contentLengthLong.takeIf { it > 0 }
      if (total != null && total > MAX_DOWNLOAD_BYTES) throw IOException("unexpected size $total")
      conn.inputStream.use { input ->
        download.outputStream().use { output ->
          val buf = ByteArray(1 shl 16)
          var bytes = 0L
          while (true) {
            ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            bytes += n
            if (bytes > MAX_DOWNLOAD_BYTES) throw IOException("download too large")
            _status.value = DictionaryStatus.Downloading(bytes, total)
          }
        }
      }
    } finally {
      (conn as? HttpURLConnection)?.disconnect()
    }
  }

  /** Caller holds the write lock. */
  private fun open(): DictionaryInfo? {
    if (!current.exists()) return null
    return try {
      val opened = SQLiteDatabase.openDatabase(
        current.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
      )
      val meta = HashMap<String, String>()
      opened.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
      if (meta["schema"] != DictionarySchema.VERSION) {
        opened.close()
        current.delete()
        return null
      }
      db = opened
      search = DictionarySearch(opened)
      DictionaryInfo(meta["entries"]?.toIntOrNull() ?: 0, meta["source_date"]?.ifEmpty { null }, current.length())
    } catch (e: Exception) {
      Log.w(TAG, "dictionary unreadable; removing", e)
      close()
      current.delete()
      null
    }
  }

  /** Caller holds the write lock. */
  private fun close() {
    search = null
    db?.close()
    db = null
  }

  private fun installedInfo(): DictionaryInfo? = when (val s = _status.value) {
    is DictionaryStatus.Installed -> s.info
    is DictionaryStatus.Failed -> s.installed
    else -> null
  }

  private class HttpException(code: Int) : IOException("HTTP $code")

  companion object {
    const val SOURCE_URL = "https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.zip"
    private const val SOURCE_FILE = "cedict_ts.u8"
    private const val REQUIRED_FREE_BYTES = 80L * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
    private const val TAG = "LangboardDict"

    @Volatile private var instance: DictionaryManager? = null

    fun get(context: Context): DictionaryManager = instance ?: synchronized(this) {
      instance ?: context.applicationContext.let { app ->
        DictionaryManager(File(app.filesDir, "dictionary"), app.cacheDir)
      }.also { instance = it }
    }
  }
}
