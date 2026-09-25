package app.langboard.dictionary

import android.content.Context
import android.util.Log
import android.util.LruCache
import app.langboard.cedict.Pinyin
import app.langboard.core.Ruby
import app.langboard.core.RubyAligner
import app.langboard.core.Segmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pinyin for any Chinese text, per character, the way pypinyin does it: split the text into words
 * (longest match), read each word from a word table, and each remaining character by its everyday
 * reading. Both tables are bundled and built from CC-CEDICT by shared/dictionary/build_char_readings.py:
 * - word_pinyin.txt: the 8,000 common words whose reading isn't their characters' own (银行 yín háng,
 *   睡觉 shuì jiào, 便宜 pián yi),
 * - char_pinyin.txt: every character's most common reading.
 *
 * 96% of characters right on shared/eval/pinyin_gold.tsv, about what pypinyin gets, with or without
 * the dictionary download. In memory (about 300 KB of text), so a sentence takes well under a
 * millisecond; results are cached anyway.
 */
class Readings private constructor(private val context: Context) {
  private val chars: Map<Char, String> by lazy {
    context.assets.open("char_pinyin.txt").bufferedReader().useLines { lines ->
      lines.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 && it[0].length == 1 }?.let { it[0][0] to it[1] } }.toMap()
    }
  }
  private val words: Map<String, String> by lazy {
    context.assets.open("word_pinyin.txt").bufferedReader().useLines { lines ->
      lines.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }
  }
  private val cache = LruCache<String, List<Ruby>>(512)

  fun cached(text: String): List<Ruby>? = cache.get(text)

  suspend fun of(text: String): List<Ruby> {
    cache.get(text)?.let { return it }
    val out = withContext(Dispatchers.Default) { read(text) }
    cache.put(text, out)
    return out
  }

  /** Loads both tables; call off the main thread before the first lookup is needed. */
  fun warm() {
    val started = System.nanoTime()
    chars.size + words.size
    Log.d("LangboardReadings", "tables loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
  }

  private fun read(text: String): List<Ruby> = Segmenter.segment(text) { it in words }.flatMap { w ->
    words[w]?.let { RubyAligner.align(w, it, Pinyin::toToneMarks) }
      ?: RubyAligner.byCharacter(w, chars::get, Pinyin::toToneMarks)
  }

  companion object {
    @Volatile private var instance: Readings? = null

    fun get(context: Context): Readings = instance ?: synchronized(this) {
      instance ?: Readings(context.applicationContext).also { instance = it }
    }
  }
}
