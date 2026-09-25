package app.langboard.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.langboard.MainActivity
import app.langboard.core.GgufRepack
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloads the Chinese pack as a foreground job: fetch to `.partial` (resuming where a previous
 * attempt stopped), check the SHA-256, prepare it for llama.cpp ([GgufRepack]), check that result's
 * SHA-256, and only then move it into place. A killed or corrupted download can never pass for an
 * installed model.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  private val spec = ModelManager.spec
  private val dir = File(applicationContext.filesDir, "models/${spec.id}")
  private val partial = File(dir, "download.partial")
  private val preparing = File(dir, "${spec.installedName}.preparing")
  private val installed = File(dir, spec.installedName)
  private var lastProgressAt = 0L

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    try {
      setForeground(foregroundInfo(null))
      dir.mkdirs()
      if (installed.exists()) return@withContext Result.success()
      val needed = spec.downloadBytes - partial.length() + spec.installedBytes + MARGIN_BYTES
      if (dir.usableSpace < needed) return@withContext fail("Not enough storage. The Chinese model needs about ${mb(needed)} free.")

      download()
      progress(PHASE_VERIFY, 0, 1)
      if (sha256(partial, spec.downloadBytes) != spec.downloadSha256) {
        partial.delete()
        return@withContext fail("The download was damaged. Try again.")
      }
      if (spec.repack) {
        GgufRepack.stq1ToTq2(partial, preparing) { done, total ->
          if (isStopped) throw CancellationException("stopped")
          progressThrottled(PHASE_VERIFY, done, total * 2)
        }
        if (sha256(preparing, spec.installedBytes, offset = spec.installedBytes) != spec.installedSha256) {
          preparing.delete()
          partial.delete()
          return@withContext fail("The Chinese model couldn't be prepared. Try again.")
        }
      } else if (!partial.renameTo(preparing)) {
        throw IOException("rename failed")
      }
      if (!preparing.renameTo(installed)) throw IOException("could not move the model into place")
      partial.delete()
      // Earlier versions of the pack.
      dir.listFiles()?.filter { it.name.endsWith(".gguf") && it != installed }?.forEach { it.delete() }
      Log.i(TAG, "installed ${installed.length() / 1_000_000} MB")
      notifyReady()
      Result.success()
    } catch (e: CancellationException) {
      preparing.delete()  // the partial download stays, so the next attempt resumes
      throw e
    } catch (e: IOException) {
      Log.w(TAG, "download attempt ${runAttemptCount + 1} failed", e)
      preparing.delete()
      if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry()
      else fail("Couldn't download the Chinese model. Check your connection and try again.")
    }
  }

  private suspend fun download() {
    if (partial.length() == spec.downloadBytes) return
    if (partial.length() > spec.downloadBytes) partial.delete()
    val conn = URL(spec.downloadUrl).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    val from = partial.length()
    if (from > 0) conn.setRequestProperty("Range", "bytes=$from-")
    try {
      val append = when (conn.responseCode) {
        HttpURLConnection.HTTP_PARTIAL -> true
        HttpURLConnection.HTTP_OK -> false  // the server ignored the range; start over
        else -> throw IOException("HTTP ${conn.responseCode}")
      }
      var done = if (append) from else 0L
      conn.inputStream.use { input ->
        FileOutputStream(partial, append).use { output ->
          val buf = ByteArray(1 shl 16)
          while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            done += n
            if (done > spec.downloadBytes) throw IOException("download larger than expected")
            progressThrottled(PHASE_DOWNLOAD, done, spec.downloadBytes)
          }
        }
      }
      if (done != spec.downloadBytes) throw IOException("download ended early at $done bytes")
    } finally {
      conn.disconnect()
    }
  }

  /**
   * SHA-256 of [file], reporting verify progress. [offset] shifts progress so checking the prepared
   * file continues the bar where preparing left it.
   */
  private suspend fun sha256(file: File, size: Long, offset: Long = 0): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { s ->
      val buf = ByteArray(1 shl 20)
      var done = 0L
      while (true) {
        coroutineContext.ensureActive()
        val n = s.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
        done += n
        if (spec.repack && offset > 0) progressThrottled(PHASE_VERIFY, offset + done, size * 2)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  private fun progressThrottled(phase: String, done: Long, total: Long) {
    val now = System.currentTimeMillis()
    if (now - lastProgressAt < 500 && done < total) return
    lastProgressAt = now
    progress(phase, done, total)
    setForegroundAsync(foregroundInfo(done.toFloat() / total, preparing = phase == PHASE_VERIFY))
  }

  private fun progress(phase: String, done: Long, total: Long) {
    setProgressAsync(workDataOf(KEY_PHASE to phase, KEY_DONE to done, KEY_TOTAL to total))
  }

  private fun fail(message: String): Result {
    notifyDone("Chinese model not installed", message)
    return Result.failure(workDataOf(KEY_ERROR to message))
  }

  private fun notifyReady() = notifyDone("Chinese model ready", "Switch to Langboard in any app to use it.")

  /** A dismissable notification once the job is over, since the ongoing one goes away with it. */
  private fun notifyDone(title: String, text: String) {
    val n = NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(openApp())
      .setAutoCancel(true)
      .build()
    runCatching { channel().notify(DONE_NOTIFICATION_ID, n) }  // no permission: the in-app card says the same
  }

  private fun channel(): NotificationManager {
    val nm = applicationContext.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chinese model download", NotificationManager.IMPORTANCE_LOW))
    }
    return nm
  }

  /** Tapping any of these notifications opens the app on its Chinese model card. */
  private fun openApp(): PendingIntent = PendingIntent.getActivity(
    applicationContext, 0,
    Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE,
  )

  private fun foregroundInfo(fraction: Float?, preparing: Boolean = false): ForegroundInfo {
    channel()
    val percent = ((fraction ?: 0f) * 100).toInt()
    val n = NotificationCompat.Builder(applicationContext, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(if (preparing) "Preparing Chinese model" else "Downloading Chinese model")
      .setContentText(
        when {
          fraction == null -> "Starting…"
          preparing -> "Checking and preparing it for this phone · $percent%"
          else -> "$percent%  ·  ${mb((spec.downloadBytes * fraction).toLong())} / ${mb(spec.downloadBytes)}"
        }
      )
      .setContentIntent(openApp())
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setProgress(100, percent, fraction == null)
      .build()
    return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(NOTIFICATION_ID, n)
  }

  companion object {
    private const val TAG = "LangboardModel"
    const val KEY_PHASE = "phase"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    const val KEY_ERROR = "error"
    const val PHASE_DOWNLOAD = "download"
    const val PHASE_VERIFY = "verify"
    private const val CHANNEL = "model_download"
    private const val NOTIFICATION_ID = 7301
    private const val DONE_NOTIFICATION_ID = 7302
    private const val MAX_ATTEMPTS = 5
    private const val MARGIN_BYTES = 100L shl 20

    private fun mb(bytes: Long) = "${(bytes + 999_999) / 1_000_000} MB"

    fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()
  }
}
