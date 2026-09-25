package app.langboard.core

/**
 * A downloadable language pack: the exact file to fetch, pinned to a revision and checksum, and
 * what it becomes once prepared on the phone. A later model (fine-tuned, distilled) is a new spec
 * with a higher [version]; nothing else changes.
 */
data class ModelSpec(
  val id: String,
  val version: Int,
  /** Shown in the app. Never "GGUF", bit widths or quantization. */
  val title: String,
  val description: String,
  val downloadUrl: String,
  val downloadBytes: Long,
  val downloadSha256: String,
  /** Tencent's STQ1_0 file is rewritten to TQ2_0 after download; see [GgufRepack]. */
  val repack: Boolean,
  val installedName: String,
  val installedBytes: Long,
  val installedSha256: String,
) {
  companion object {
    /**
     * Hy-MT2 1.8B, Tencent's 1.25-bit build, from the official repository at a fixed commit. On a
     * Pixel 6a it answered as well as the 4-bit build and better than the 2-bit one, loads in
     * 0.5 s once cached, and needs about 600 MB of file-backed memory (the 4-bit build needs 2.2 GB).
     */
    val CHINESE = ModelSpec(
      id = "zh_en",
      version = 1,
      title = "Chinese offline intelligence",
      description = "Runs entirely on your phone. Used for natural English → Chinese suggestions and for explaining messages.",
      downloadUrl = "https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/" +
        "9df5c824a00a744fb0512a29c640466f4d97dfb0/Hy-MT2-1.8B-1.25Bit.gguf",
      downloadBytes = 461_860_800,
      downloadSha256 = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
      repack = true,
      installedName = "hymt2-1.8b-1.25bit-v1.gguf",
      installedBytes = 606_564_288,
      installedSha256 = "283e3160c2b5cb64a0a579e8d568730677e9ea15985a05c9bd5beb2ad4371add",
    )
  }
}

/** One state for the pack, shared by the app and the keyboard, that drives all model UI. */
sealed interface ModelState {
  data object NotInstalled : ModelState
  /** [progress] is 0..1, or null before the size is known. */
  data class Downloading(val progress: Float?, val downloadedBytes: Long, val totalBytes: Long) : ModelState
  /** Checking the download and preparing it for this phone. */
  data class Verifying(val progress: Float?) : ModelState
  /** On the phone, not in memory. */
  data object Installed : ModelState
  data object Loading : ModelState
  data object Ready : ModelState
  data class Error(val message: String) : ModelState

  /** The file is on the phone, whether or not it's loaded. */
  val installed: Boolean get() = this is Installed || this is Loading || this is Ready
}
