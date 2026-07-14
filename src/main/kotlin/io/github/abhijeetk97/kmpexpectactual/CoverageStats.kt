package io.github.abhijeetk97.kmpexpectactual

/**
 * Aggregate statistics over the whole coverage list — the numbers behind the
 * summary bar at the top of the tool window and the per-group badges.
 *
 * Everything here is derived from plain value data (no PSI), so it is safe to
 * compute and hold on any thread.
 */
data class CoverageStats(
    val totalExpects: Int,
    val completeExpects: Int,
    // platform label → (covered count, total expects that should cover it)
    val perPlatform: Map<String, PlatformStat>,
) {
    data class PlatformStat(val covered: Int, val total: Int)

    val incompleteExpects: Int get() = totalExpects - completeExpects

    // Whole-project percentage, e.g. 86. Complete expects over all expects;
    // an empty project counts as 100% so the summary bar never divides by zero.
    val completePercent: Int
        get() = if (totalExpects == 0) 100 else completeExpects * 100 / totalExpects

    // One-line human-readable summary, e.g.
    // "42 expects · 36 complete (86%) · 6 incomplete"
    val summaryLine: String
        get() = "$totalExpects expects · $completeExpects complete ($completePercent%) · $incompleteExpects incomplete"

    companion object {
        fun from(coverage: List<Coverage>): CoverageStats {
            val platforms = coverage.flatMap { it.knownPlatforms }.toSet()
            val perPlatform = platforms.associateWith { platform ->
                val relevant = coverage.filter { platform in it.knownPlatforms }
                PlatformStat(
                    covered = relevant.count { platform in it.actualsByPlatform },
                    total = relevant.size,
                )
            }
            return CoverageStats(
                totalExpects = coverage.size,
                completeExpects = coverage.count { it.isComplete },
                perPlatform = perPlatform,
            )
        }
    }
}
