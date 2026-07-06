package io.github.abhijeetk97.kmpexpectactual

import com.intellij.psi.SmartPsiElementPointer

/**
 * UI-only data class representing a single platform row under an expect node.
 *
 * WHY IT'S SEPARATE FROM Coverage
 * ---------------------------------
 * Coverage holds the full analytical model (FqNames, all actuals, known platforms).
 * PlatformNode is just enough data to paint one row in the tree:
 *   - which platform this row is for
 *   - a pointer to jump to the `actual` on double-click (null if the actual is missing)
 *
 * Keeping this UI-only avoids coupling the renderer to Coverage internals, and makes
 * the renderer's `when (obj)` dispatch clean: Coverage = parent row, PlatformNode = child row.
 */
data class PlatformNode(
    val platform: String,
    // Non-null when this platform has a matching `actual` declaration.
    // Null when the platform is "missing" — i.e., no actual was found.
    // Stored as a SmartPsiElementPointer for the same reason as ExpectEntry.pointer:
    // it stays valid even after the user edits the file.
    val pointer: SmartPsiElementPointer<*>?,
) {
    val isCovered: Boolean get() = pointer != null
}
