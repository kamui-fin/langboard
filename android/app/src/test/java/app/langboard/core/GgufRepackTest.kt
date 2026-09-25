package app.langboard.core

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class GgufRepackTest {
  private val codebook = intArrayOf(
    0xA9, 0x89, 0x29, 0x09, 0xA6, 0x86, 0x26, 0x06, 0x9A, 0x92, 0x1A, 0x12, 0x6A, 0x62, 0x4A, 0x42,
    0x01, 0x21, 0x81, 0xA1, 0x04, 0x24, 0x84, 0xA4, 0x10, 0x18, 0x90, 0x98, 0x40, 0x48, 0x60, 0x68,
  )

  /** Port of dequantize_row_stq1_0 (llama.cpp PR #22836), as ternary values without the scale. */
  private fun stqValues(block: ByteArray, at: Int): IntArray {
    val y = IntArray(256)
    for (g in 0 until 64) {
      val code = (block[at + g / 2].toInt() shr (4 * (g and 1))) and 0xF
      val sign = (block[at + 32 + g / 8].toInt() shr (g % 8)) and 1
      val q = codebook[(sign shl 4) or code]
      for (p in 0 until 4) y[(g / 16) * 64 + g % 16 + p * 16] = ((q shr (2 * p)) and 3) - 1
    }
    return y
  }

  /** Port of ggml's dequantize_row_tq2_0, without the scale. */
  private fun tq2Values(block: ByteArray, at: Int): IntArray {
    val y = IntArray(256)
    var i = 0
    for (j in 0 until 64 step 32) for (l in 0 until 4) for (m in 0 until 32) {
      y[i++] = ((block[at + j + m].toInt() shr (l * 2)) and 3) - 1
    }
    return y
  }

  @Test fun everyWeightKeepsItsValue() {
    val src = Random(7).nextBytes(42 * 200)
    val dst = GgufRepack.stqBlocksToTq2(src)
    assertEquals(66 * 200, dst.size)
    for (b in 0 until 200) {
      assertArrayEquals("block $b", stqValues(src, b * 42), tq2Values(dst, b * 66))
      assertEquals(src[b * 42 + 40], dst[b * 66 + 64])
      assertEquals(src[b * 42 + 41], dst[b * 66 + 65])
    }
  }

  @Test fun everyGroupHasOneZero() {
    // STQ1_0 is 3:4 sparse: each group of four has exactly one 0, which checks the codebook copy.
    for (q in codebook) assertEquals(1, (0 until 4).count { (q shr (2 * it)) and 3 == 1 })
  }

  /** HYMT_125_GGUF=/path/Hy-MT2-1.8B-1.25Bit.gguf ./gradlew :app:testDebugUnitTest */
  @Test fun matchesThePythonToolOnTheRealFile() {
    val path = System.getenv("HYMT_125_GGUF")
    assumeTrue(path != null)
    val out = File.createTempFile("hymt", ".gguf")
    try {
      GgufRepack.stq1ToTq2(File(path!!), out)
      assertEquals(ModelSpec.CHINESE.installedSha256, sha256(out))
    } finally {
      out.delete()
    }
  }

  private fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s ->
      val buf = ByteArray(1 shl 20)
      while (true) {
        val n = s.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}
