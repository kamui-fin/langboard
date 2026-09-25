package app.langboard.core

/**
 * A verified edit: the text before the caret must end with [expectBeforeSuffix] (trailing mode)
 * or the selection must equal [expectSelected] (selection mode) before anything is deleted.
 */
data class EditPlan(
  val expectBeforeSuffix: String?,
  val expectSelected: String?,
  val deleteBeforeCodePoints: Int,
  val commit: String,
) {
  /** The inverse edit, valid only while the caret sits right after [commit]. */
  fun undo(): EditPlan {
    val original = expectSelected ?: expectBeforeSuffix!!
    return EditPlan(
      expectBeforeSuffix = commit,
      expectSelected = null,
      deleteBeforeCodePoints = commit.codePointCount(0, commit.length),
      commit = original,
    )
  }

  fun matches(before: CharSequence?, selected: CharSequence?): Boolean = when {
    expectSelected != null -> selected?.toString() == expectSelected
    else -> selected.isNullOrEmpty() && before != null && before.endsWith(expectBeforeSuffix!!)
  }

  /**
   * The resulting field text when the caret splits it into [before] | selection | [after],
   * or null when the plan doesn't match. Used where we own the text (Try It) and in tests.
   */
  fun applyTo(before: String, selected: String, after: String): String? {
    if (!matches(before, selected)) return null
    val head = if (expectSelected != null) before else before.dropLast(expectBeforeSuffix!!.length)
    return head + commit + after
  }

  companion object {
    fun replace(d: Detection, replacement: String): EditPlan =
      if (d.fromSelection) {
        EditPlan(null, d.span, 0, replacement)
      } else {
        val target = d.span + d.tail
        EditPlan(target, null, target.codePointCount(0, target.length), replacement + d.tail)
      }

    /** Rewrites the sentence before the caret (Naturalize), keeping its trailing punctuation. */
    fun rewrite(s: SentenceSpan, sentence: String): EditPlan =
      EditPlan(s.full, null, s.full.codePointCount(0, s.full.length), sentence + s.tail)

    /**
     * Inserts [text] at the caret. The last few characters before the caret are re-committed as an
     * anchor, so the edit (and its Undo) still fails safely if the caret moved in the meantime.
     */
    fun insertAtCaret(before: String, text: String): EditPlan {
      var cut = maxOf(0, before.length - ANCHOR_CHARS)
      if (cut > 0 && Character.isLowSurrogate(before[cut])) cut--
      val anchor = before.substring(cut)
      return EditPlan(anchor, null, anchor.codePointCount(0, anchor.length), anchor + text)
    }

    private const val ANCHOR_CHARS = 8
  }
}
