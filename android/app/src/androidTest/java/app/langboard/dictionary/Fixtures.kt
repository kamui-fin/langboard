package app.langboard.dictionary

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real CC-CEDICT lines (header + 37 entries) shipped as a test asset. */
object Fixtures {
  val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  val targetContext get() = instrumentation.targetContext

  fun text(): String = instrumentation.context.assets.open("cedict_fixture.u8").bufferedReader().readText()

  val ENTRY_COUNT get() = text().lines().count { it.isNotBlank() && !it.startsWith("#") }

  /** A fresh, empty directory under the app's cache, removed by the caller. */
  fun tempDir(name: String): File =
    File(targetContext.cacheDir, "test-$name-${System.nanoTime()}").apply { deleteRecursively(); mkdirs() }

  /** A zip laid out like MDBG's download. */
  fun zip(dir: File, entryName: String = "cedict_ts.u8", content: String = text()): File =
    File(dir, "cedict.zip").apply {
      ZipOutputStream(outputStream()).use { z ->
        z.putNextEntry(ZipEntry(entryName))
        z.write(content.toByteArray(Charsets.UTF_8))
        z.closeEntry()
      }
    }
}
