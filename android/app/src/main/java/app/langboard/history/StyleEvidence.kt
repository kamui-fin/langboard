package app.langboard.history

import app.langboard.core.MyStyle
import app.langboard.core.StyleRule
import app.langboard.core.Verbosity

/**
 * Learned from you, read from history alone (handoff A.6): what the user actually chose, not what
 * they were shown. Accepting the first option is a weak signal and teaches nothing here; picking
 * another option or saving one is a medium signal; nothing is learned from a single time.
 */
class StyleEvidence(entries: List<HistoryEntry>) {
  private val fills = entries.filter { it.kind == HistoryKind.Fill && it.answer != null }

  /** What went into the message, when something did and it wasn't undone. */
  private fun chosen(e: HistoryEntry): String? = if (e.outcome == Outcome.Inserted) e.used ?: e.answer else null

  /**
   * For each English phrase (normalized), the Chinese the user settled on: one they picked over the
   * first option or saved, or the same answer sent on two separate occasions. The latest wins a tie.
   */
  val usual: Map<String, String> = buildMap {
    fills.groupBy { key(it.source) }.forEach { (k, list) ->
      val votes = HashMap<String, MutableList<Long>>()
      var strong: String? = null
      for (e in list.sortedBy { it.createdAt }) {
        val c = chosen(e)
        if (c != null) votes.getOrPut(c) { mutableListOf() } += e.createdAt
        if (e.used != null && e.outcome == Outcome.Inserted) strong = e.used
        if (e.saved) strong = e.answer
      }
      val repeated = votes.filter { Insights.occasions(it.value) >= 2 }.maxByOrNull { it.value.max() }?.key
      (strong ?: repeated)?.let { put(k, it) }
    }
  }

  fun usualFor(english: String): String? = usual[key(english)]

  /**
   * One swap the user made when picking another option: the Chinese they took in, the Chinese
   * they turned down (很 → 挺 in 挺好的 over 很好的). Only short, single swaps count; two
   * unrelated options say nothing about a word.
   */
  data class Swap(val chose: String, val over: String)

  private val picks: List<Pair<HistoryEntry, Swap?>> = fills.mapNotNull { e ->
    val used = e.used?.takeIf { e.outcome == Outcome.Inserted && it != e.answer } ?: return@mapNotNull null
    e to swap(e.answer!!, used)
  }

  /** Things Langboard could add to My Style, each seen on enough separate occasions. Nothing already there or turned down. */
  fun proposals(style: MyStyle): List<Proposal> {
    val out = ArrayList<Proposal>()
    picks.mapNotNull { (e, s) -> s?.let { it to e.createdAt } }
      .groupBy({ it.first }, { it.second })
      .forEach { (s, times) ->
        val n = Insights.occasions(times)
        val rule = StyleRule(StyleRule.Kind.Prefer, s.chose, s.over, learned = true)
        if (n >= MIN_OCCASIONS && style.rules.none { it.sameAs(rule) || (it.text == s.over && it.over == s.chose) } && rule.line !in style.dismissed) {
          out += Proposal.Rule(rule, n)
        }
      }
    // Shorter: most of the times they picked another option, it was a shorter one.
    val shorter = picks.filter { (e, _) -> e.used!!.length < e.answer!!.length }
    val shorterOccasions = Insights.occasions(shorter.map { it.first.createdAt })
    if (style.verbosity != Verbosity.Concise && MyStyle.VERBOSITY_KEY !in style.dismissed &&
      shorterOccasions >= MIN_OCCASIONS + 1 && shorter.size >= picks.size * 3 / 4
    ) {
      out += Proposal.Shorter(shorterOccasions)
    }
    return out.sortedByDescending { it.occasions }
  }

  sealed interface Proposal {
    val occasions: Int

    data class Rule(val rule: StyleRule, override val occasions: Int) : Proposal
    data class Shorter(override val occasions: Int) : Proposal
  }

  /** Sentences the user sent with Langboard's help, or saved, newest first: their own Chinese. */
  private val sentences: List<String> = fills
    .filter { (it.outcome == Outcome.Inserted || it.saved) && !it.sentence.isNullOrBlank() }
    .sortedByDescending { it.createdAt }
    .mapNotNull { it.sentence?.trim() }
    .filter { s -> s.count(Insights::hasChineseChar) >= MIN_SENTENCE_CJK }
    .distinct()

  /**
   * Up to [k] of the user's own sentences most like the draft (shared Chinese pairs of characters),
   * with the ones they pasted into My Style counted as a little closer. None that share nothing.
   */
  fun examples(draft: String, pasted: List<String>, k: Int = MyStyle.MAX_EXAMPLES): List<String> {
    val want = bigrams(draft)
    if (want.isEmpty()) return emptyList()
    return (pasted.map { it to 1 } + sentences.map { it to 0 })
      .asSequence()
      .filter { (s, _) -> s != draft.trim() }
      .map { (s, bonus) -> Triple(s, bigrams(s).count { it in want }, bonus) }
      .filter { it.second > 0 }
      .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second + it.third })
      .map { it.first }
      .distinct()
      .take(k)
      .toList()
  }

  companion object {
    const val MIN_OCCASIONS = 3
    private const val MAX_SWAP = 3
    private const val MIN_SENTENCE_CJK = 4

    fun key(english: String) = english.trim().lowercase().split(Regex("\\s+")).joinToString(" ")

    /** The single short Chinese swap that turns [from] into [to], if that's all that changed. */
    fun swap(from: String, to: String): Swap? {
      var start = 0
      while (start < from.length && start < to.length && from[start] == to[start]) start++
      var end = 0
      while (end < from.length - start && end < to.length - start && from[from.length - 1 - end] == to[to.length - 1 - end]) end++
      val removed = from.substring(start, from.length - end)
      val added = to.substring(start, to.length - end)
      if (removed.isEmpty() || added.isEmpty() || removed.length > MAX_SWAP || added.length > MAX_SWAP) return null
      if (!removed.all(Insights::hasChineseChar) || !added.all(Insights::hasChineseChar)) return null
      return Swap(added, removed)
    }

    private fun bigrams(s: String): Set<String> {
      val han = s.filter(Insights::hasChineseChar)
      return (0 until han.length - 1).map { han.substring(it, it + 2) }.toSet()
    }
  }
}
