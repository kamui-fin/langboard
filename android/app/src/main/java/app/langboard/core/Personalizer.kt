package app.langboard.core

/**
 * The user's style applied to what the model already offered, so it works with any model. It
 * never writes an option, only orders and filters the model's own, and only among options the model
 * itself finds plausible: meaning comes before voice.
 *
 * - An option the user chose before for the same English goes first ("your usual").
 * - An option with a word they prefer goes ahead of one with the word it's preferred over.
 * - An option with a word they avoid goes last, and is dropped while anything else is left.
 */
object Personalizer {
  /**
   * Options with less than this share of the top option's probability aren't promoted for style: the
   * model thinks they're a worse fit for the sentence. A remembered choice is stronger evidence.
   */
  const val STYLE_FLOOR = 0.2f
  const val USUAL_FLOOR = 0.05f

  fun fill(result: FillGapResult, rules: List<StyleRule>, usual: String?): FillGapResult {
    val options = result.candidates
    if (options.size < 2 && rules.none { it.kind == StyleRule.Kind.Avoid }) return result
    val top = options.first().share
    fun plausible(c: Candidate, floor: Float) = top == null || c.share == null || c.share >= top * floor
    fun tier(c: Candidate): Int = when {
      usual != null && c.text == usual && plausible(c, USUAL_FLOOR) -> 0
      rules.any { it.kind == StyleRule.Kind.Avoid && c.text.contains(it.text) } -> 4
      rules.any { it.kind == StyleRule.Kind.Prefer && it.over != null && c.text.contains(it.over) && !c.text.contains(it.text) } -> 3
      rules.any { it.kind == StyleRule.Kind.Prefer && c.text.contains(it.text) } && plausible(c, STYLE_FLOOR) -> 1
      else -> 2
    }
    val tiers = options.associateWith(::tier)
    val kept = options.filter { tiers.getValue(it) < 4 }.ifEmpty { options }
    // sortedBy is stable: within a tier the model's order stands.
    val ordered = kept.sortedBy { tiers.getValue(it) }
    if (ordered == options) return result
    val first = ordered.first()
    return result.copy(
      replacement = first.text, pinyin = first.pinyin, meaning = first.meaning, share = first.share,
      alternatives = ordered.drop(1),
    )
  }

  /**
   * A Check rewrite that brings in a word the user avoids, or swaps a word they prefer for the one
   * it's preferred over (挺 → 很), isn't offered. Null when nothing is left.
   */
  fun check(result: NaturalizeResult, rules: List<StyleRule>): NaturalizeResult? {
    if (rules.isEmpty()) return result
    val original = result.original
    fun ok(rewrite: String) = rules.none { r ->
      when (r.kind) {
        StyleRule.Kind.Avoid -> rewrite.contains(r.text) && !original.contains(r.text)
        StyleRule.Kind.Prefer -> r.over != null && original.contains(r.text) && !rewrite.contains(r.text) && rewrite.contains(r.over)
        StyleRule.Kind.Note -> false
      }
    }
    val kept = (listOf(result.natural) + result.alternatives).filter(::ok)
    if (kept.isEmpty()) return null
    return NaturalizeResult(original, kept.first(), kept.drop(1))
  }
}
