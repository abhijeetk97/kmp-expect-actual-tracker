package io.github.abhijeetk97.kmpexpectactual

/**
 * UI-only data class for an intermediate grouping row in the coverage tree.
 *
 * Appears only when the user picks a group-by mode other than "flat":
 *
 *   Group by package         → one GroupNode per package, expects underneath
 *   Group by module          → one GroupNode per Gradle module
 *   Group by missing platform → one GroupNode per platform, listing every
 *                               expect that is missing an actual on it
 *
 * [covered] / [total] drive the "(5/7 covered)" badge painted by the renderer,
 * which doubles as the per-module breakdown asked for in issue #17.
 */
data class GroupNode(
    val label: String,
    val covered: Int,
    val total: Int,
) {
    val badge: String get() = "$covered/$total covered"
}
