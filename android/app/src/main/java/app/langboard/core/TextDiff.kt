package app.langboard.core

/** Character-level differences, for highlighting what a suggestion changes. */
object TextDiff {
  /**
   * The ranges of [new] that aren't in [old], by longest common subsequence. Texts here are a
   * sentence long, so the quadratic table is small.
   */
  fun inserted(old: String, new: String): List<IntRange> {
    val n = old.length
    val m = new.length
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
      lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val kept = BooleanArray(m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
      when {
        old[i] == new[j] -> { kept[j] = true; i++; j++ }
        lcs[i + 1][j] >= lcs[i][j + 1] -> i++
        else -> j++
      }
    }
    val out = ArrayList<IntRange>()
    var start = -1
    for (k in 0..m) {
      val changed = k < m && !kept[k]
      if (changed && start < 0) start = k
      if (!changed && start >= 0) { out += start until k; start = -1 }
    }
    return out
  }
}
