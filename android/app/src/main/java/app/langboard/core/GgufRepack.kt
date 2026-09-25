package app.langboard.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Rewrites Tencent's 1.25-bit Hy-MT2 GGUF so stock llama.cpp can run it. Tencent's file stores its
 * ternary weights as STQ1_0 under tensor type 42, which mainline llama.cpp uses for Q2_0, so
 * mainline refuses the file. STQ1_0 maps exactly onto mainline TQ2_0 (ternary, one fp16 scale per
 * 256 weights), so the rewrite is lossless: every weight dequantizes to the same value. Metadata,
 * the tokenizer and all other tensors are copied unchanged, and the output is byte-for-byte what
 * model/convert_hymt_gguf.py writes.
 */
object GgufRepack {
  private const val SRC_STQ1_0 = 42
  private const val TQ2_0 = 35
  private const val FTYPE_TQ2_0 = 37
  private const val STQ_BLOCK = 42
  private const val TQ2_BLOCK = 66

  /** (weights per block, bytes per block) for the tensor types in Hy-MT2 files. */
  private val TYPE_SIZE = mapOf(0 to (1 to 4), 1 to (1 to 2), 12 to (256 to 144), 14 to (256 to 210), SRC_STQ1_0 to (256 to STQ_BLOCK), TQ2_0 to (256 to TQ2_BLOCK))

  /** Index (sign << 4) | slot -> four 2-bit lanes (0 = -1, 1 = 0, 2 = +1), lane 0 lowest. */
  private val CODEBOOK = intArrayOf(
    0xA9, 0x89, 0x29, 0x09, 0xA6, 0x86, 0x26, 0x06, 0x9A, 0x92, 0x1A, 0x12, 0x6A, 0x62, 0x4A, 0x42,
    0x01, 0x21, 0x81, 0xA1, 0x04, 0x24, 0x84, 0xA4, 0x10, 0x18, 0x90, 0x98, 0x40, 0x48, 0x60, 0x68,
  )

  class FormatException(message: String) : IOException(message)

  private class Tensor(val name: ByteArray, val dims: LongArray, val type: Int, val offset: Long) {
    val count: Long get() = dims.fold(1L) { a, b -> a * b }
  }

  /** Converts [input] into [output]; [onProgress] gets (tensor bytes done, total). Throws on a file it doesn't expect. */
  fun stq1ToTq2(input: File, output: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
    RandomAccessFile(input, "r").use { raf ->
      val ch = raf.channel
      val header = ch.map(FileChannel.MapMode.READ_ONLY, 0, minOf(ch.size(), 64L shl 20)).order(ByteOrder.LITTLE_ENDIAN)
      if (header.int != 0x46554747) throw FormatException("not a GGUF file") // "GGUF"
      val version = header.int
      val nTensors = header.long
      val nKv = header.long
      val kvStart = header.position()
      var alignment = 32
      var fileTypeAt = -1
      repeat(nKv.toInt()) {
        val key = String(bytes(header), Charsets.UTF_8)
        val t = header.int
        if (key == "general.alignment") alignment = header.getInt(header.position())
        if (key == "general.file_type") fileTypeAt = header.position() - kvStart
        skipValue(header, t)
      }
      val kv = ByteArray(header.position() - kvStart).also { header.position(kvStart); header.get(it) }
      val tensors = List(nTensors.toInt()) {
        val name = bytes(header)
        val dims = LongArray(header.int) { header.long }
        Tensor(name, dims, header.int, header.long)
      }
      val dataStart = align(header.position().toLong(), alignment)
      if (tensors.none { it.type == SRC_STQ1_0 }) throw FormatException("no STQ1_0 tensors")
      tensors.forEach { if (it.type !in TYPE_SIZE) throw FormatException("unexpected tensor type ${it.type}") }
      if (fileTypeAt >= 0) ByteBuffer.wrap(kv).order(ByteOrder.LITTLE_ENDIAN).putInt(fileTypeAt, FTYPE_TQ2_0)

      val outTypes = tensors.map { if (it.type == SRC_STQ1_0) TQ2_0 else it.type }
      val offsets = LongArray(tensors.size)
      var off = 0L
      tensors.forEachIndexed { i, t ->
        offsets[i] = off
        off = align(off + nbytes(outTypes[i], t.count), alignment)
      }

      val out = ByteBuffer.allocate(24 + kv.size + tensors.sumOf { 8 + it.name.size + 4 + 8 * it.dims.size + 12 } + alignment)
        .order(ByteOrder.LITTLE_ENDIAN)
      out.putInt(0x46554747).putInt(version).putLong(nTensors).putLong(nKv).put(kv)
      tensors.forEachIndexed { i, t ->
        out.putLong(t.name.size.toLong()).put(t.name).putInt(t.dims.size)
        t.dims.forEach { out.putLong(it) }
        out.putInt(outTypes[i]).putLong(offsets[i])
      }
      out.position(align(out.position().toLong(), alignment).toInt())

      val total = tensors.sumOf { nbytes(it.type, it.count) }
      var done = 0L
      BufferedOutputStream(FileOutputStream(output), 1 shl 20).use { os ->
        os.write(out.array(), 0, out.position())
        var written = 0L
        tensors.forEachIndexed { i, t ->
          val pad = offsets[i] - written
          repeat(pad.toInt()) { os.write(0) }
          written += pad
          val size = nbytes(t.type, t.count)
          var pos = dataStart + t.offset
          val end = pos + size
          while (pos < end) {
            val len = minOf(end - pos, CHUNK_BLOCKS.toLong() * STQ_BLOCK * 256)  // whole blocks for either type
            val src = ch.map(FileChannel.MapMode.READ_ONLY, pos, len).order(ByteOrder.LITTLE_ENDIAN)
            val chunk = ByteArray(len.toInt()).also { src.get(it) }
            val converted = if (t.type == SRC_STQ1_0) stqBlocksToTq2(chunk) else chunk
            os.write(converted)
            written += converted.size
            pos += len
            done += len
            onProgress(done, total)
          }
        }
        val tail = align(written, alignment) - written
        repeat(tail.toInt()) { os.write(0) }
      }
    }
  }

  /** Converts whole STQ1_0 blocks to TQ2_0 blocks. */
  fun stqBlocksToTq2(src: ByteArray): ByteArray {
    val n = src.size / STQ_BLOCK
    require(n * STQ_BLOCK == src.size)
    val dst = ByteArray(n * TQ2_BLOCK)
    for (b in 0 until n) {
      val s = b * STQ_BLOCK
      val o = b * TQ2_BLOCK
      for (g in 0 until 64) {
        val code = (src[s + g / 2].toInt() shr (4 * (g and 1))) and 0xF
        val sign = (src[s + 32 + g / 8].toInt() shr (g % 8)) and 1
        val qpack = CODEBOOK[(sign shl 4) or code]
        val chunkBase = (g / 16) * 64 + g % 16
        for (p in 0 until 4) {
          // Group g covers weights chunk*64 + gloc + p*16; TQ2_0 keeps weight w at byte
          // (w / 128) * 32 + w % 32, shift 2 * ((w % 128) / 32).
          val w = chunkBase + p * 16
          val lane = (qpack shr (2 * p)) and 3
          val at = o + (w / 128) * 32 + w % 32
          dst[at] = (dst[at].toInt() or (lane shl (2 * ((w % 128) / 32)))).toByte()
        }
      }
      dst[o + 64] = src[s + 40]
      dst[o + 65] = src[s + 41]
    }
    return dst
  }

  private const val CHUNK_BLOCKS = 16

  private fun align(n: Long, a: Int) = (n + a - 1) / a * a

  private fun nbytes(type: Int, count: Long): Long {
    val (blk, size) = TYPE_SIZE.getValue(type)
    if (count % blk != 0L) throw FormatException("tensor size $count not a multiple of $blk")
    return count / blk * size
  }

  private fun bytes(b: ByteBuffer): ByteArray = ByteArray(b.long.toInt()).also { b.get(it) }

  private fun skipValue(b: ByteBuffer, t: Int) {
    when (t) {
      0, 1, 7 -> b.position(b.position() + 1)
      2, 3 -> b.position(b.position() + 2)
      4, 5, 6 -> b.position(b.position() + 4)
      10, 11, 12 -> b.position(b.position() + 8)
      8 -> bytes(b)
      9 -> {
        val at = b.int
        val n = b.long
        repeat(n.toInt()) { skipValue(b, at) }
      }
      else -> throw FormatException("unknown metadata type $t")
    }
  }
}
