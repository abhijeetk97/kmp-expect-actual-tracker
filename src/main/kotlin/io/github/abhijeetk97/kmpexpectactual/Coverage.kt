package io.github.abhijeetk97.kmpexpectactual

import com.intellij.psi.SmartPsiElementPointer

/**
 * The full coverage picture for one `expect` declaration.
 *
 * WHAT THIS REPRESENTS
 * --------------------
 * In Kotlin Multiplatform, every `expect` declaration in `commonMain` must have a
 * matching `actual` in EACH platform source set (androidMain, iosMain, jvmMain, etc.).
 * This class captures that relationship for a single `expect`:
 *
 *   expect fun platformName(): String          ← one ExpectEntry
 *       actual (Android)  → pointer to androidMain/Platform.kt
 *       actual (iOS)      → pointer to iosMain/Platform.kt
 *       actual (JVM)      → MISSING  ← knownPlatforms has JVM but actualsByPlatform doesn't
 *
 * HOW IT'S BUILT
 * --------------
 * ExpectActualScanner.computeCoverage() does a single pass over the project:
 *   1. Collects all `expect` declarations → List<ExpectEntry>
 *   2. Collects all `actual` declarations, groups them by FQ name and platform label
 *   3. For each ExpectEntry, looks up its FQ name in the actuals map
 *   4. `knownPlatforms` = the union of ALL platform labels seen across ALL actuals in the
 *      project (v1 simplification — see the "known limitation" note below)
 *
 * KNOWN LIMITATION (v1)
 * ----------------------
 * `knownPlatforms` is the project-wide union of every platform that has at least one
 * `actual` declaration anywhere. This means:
 *
 *   - If you ADD a new platform but haven't written any `actual`s yet, it won't appear
 *     as "missing" — the scanner can't see what doesn't exist.
 *   - If you have `expect`s that are scoped to a subset of platforms (e.g. only
 *     Android + iOS), the JVM platform from other `expect`s will still show as "missing"
 *     for this one.
 *
 * The correct fix is to walk the Kotlin source-set dependency graph and compute the
 * REQUIRED platforms per `expect`. That's documented as a stretch goal (issue #15).
 * For v1, the union heuristic is simple, fast, and correct for the most common case:
 * a flat KMP project where every `expect` in commonMain should be covered everywhere.
 */
data class Coverage(

    // The `expect` declaration this coverage record is about.
    val expect: ExpectEntry,

    // Maps platform label → stable pointer to the `actual` declaration on that platform.
    // e.g. { "Android" -> ptr, "iOS" -> ptr }
    // Empty if no actuals were found for this expect at all.
    // Values are SmartPsiElementPointers (not raw PSI) for the same reason as ExpectEntry —
    // they survive file edits without becoming stale. Dereference inside a read action.
    val actualsByPlatform: Map<String, SmartPsiElementPointer<*>>,

    // The full set of platform labels seen across ALL actuals in the project.
    // Used to compute missingPlatforms: any platform in this set that is NOT in
    // actualsByPlatform is "missing" for this expect.
    val knownPlatforms: Set<String>,
) {
    // Derived properties — no storage, computed on demand.

    // The platforms this expect is NOT yet covered on.
    // e.g. if knownPlatforms = {Android, iOS, JVM} and actualsByPlatform has {Android, iOS},
    // then missingPlatforms = {JVM}.
    val missingPlatforms: Set<String>
        get() = knownPlatforms - actualsByPlatform.keys

    // True only when every known platform has an actual.
    val isComplete: Boolean
        get() = missingPlatforms.isEmpty()

    // e.g. "2/3 platforms" — used as a badge label in the tree node.
    val coverageSummary: String
        get() = "${actualsByPlatform.size}/${knownPlatforms.size} platforms"

    /**
     * Case-insensitive substring match used by the tool window's search field.
     *
     * Matches when [query] appears in any of:
     *   - the expect's display name        (e.g. "platformName()")
     *   - its fully-qualified name         (so package fragments match too)
     *   - any of its platform labels       (e.g. "Android", "iOS", "JVM")
     *
     * [query] is assumed already trimmed and non-empty (callers guard for that).
     */
    fun matchesQuery(query: String): Boolean {
        val needle = query.lowercase()
        if (expect.displayName.lowercase().contains(needle)) return true
        if (expect.fqName.asString().lowercase().contains(needle)) return true
        return knownPlatforms.any { it.lowercase().contains(needle) }
    }
}
