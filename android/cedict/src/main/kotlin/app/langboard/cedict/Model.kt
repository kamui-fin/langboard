package app.langboard.cedict

/**
 * A Chinese form plus reading, used for both "variant of" references and classifiers.
 * In CC-CEDICT these are written `traditional|simplified[pinyin]`; when only one form is given,
 * both fields hold it. [pinyin] is null when the source omits it.
 */
data class HanziRef(val traditional: String, val simplified: String, val pinyin: String?)

/** One parsed CC-CEDICT line. */
data class CedictEntry(
  val traditional: String,
  val simplified: String,
  /** Numbered pinyin exactly as in the source, e.g. `Zhong1 guo2`. */
  val pinyin: String,
  val english: List<String>,
  val classifiers: List<HanziRef>,
  val variantOf: List<HanziRef>,
)

/** A lookup result: an entry plus whether it was reached as a variant of the searched word. */
data class DictionaryEntry(
  val traditional: String,
  val simplified: String,
  val pinyin: String,
  val english: List<String>,
  val classifiers: List<HanziRef>,
  val variantOf: List<HanziRef>,
  val isVariant: Boolean,
)

enum class Script { Traditional, Simplified }

/** Entry ids for one headword + pinyin, split into direct entries and entries that are variants of it. */
class IndexEntry(val base: IntArray, val variants: IntArray)

data class SearchConfig(
  /** Whether pinyin filtering is case-sensitive (`zhang1` vs `Zhang1`). */
  val caseSensitiveSearch: Boolean = true,
  /**
   * Merge pinyin keys that differ only by case (`Zhang1` + `zhang1` → `zhang1`). Keys sort by code unit,
   * so senses of the capitalized reading come first.
   */
  val mergeCases: Boolean = false,
  /** Include entries that are variants of the searched word. */
  val allowVariants: Boolean = true,
)

/** Metadata from the `#! key=value` header lines of a CC-CEDICT file. */
data class CedictHeader(val values: Map<String, String>) {
  val entries: Int? get() = values["entries"]?.toIntOrNull()
  /** ISO-8601 publication time, e.g. `2026-09-23T10:03:34Z`. */
  val date: String? get() = values["date"]
  val license: String? get() = values["license"]
}
