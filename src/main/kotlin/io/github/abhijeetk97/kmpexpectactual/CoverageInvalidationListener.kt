package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import org.jetbrains.kotlin.psi.KtFile

/**
 * Project-wide cache invalidation on Kotlin source edits.
 *
 * WHY THIS EXISTS (separate from the tool window's own listener)
 * --------------------------------------------------------------
 * The tool window registers its own PSI listener to schedule a debounced UI
 * refresh — but that listener only exists while the tool window is open. The
 * inspection and the line marker provider read [CoverageService] too, and they
 * run even when the tool window has never been opened. Without this listener
 * their view of the project would be frozen at the first scan.
 *
 * Registered via the `com.intellij.psi.treeChangeListener` extension point in
 * plugin.xml, which instantiates one listener per project and disposes it with
 * the project — no manual lifecycle management.
 *
 * Invalidation is just a volatile null-out (see CoverageService), so doing it
 * on every relevant PSI event is cheap; the expensive re-scan happens lazily on
 * the next getCoverage() call.
 */
class CoverageInvalidationListener(private val project: Project) : PsiTreeChangeAdapter() {

    private fun onChange(event: PsiTreeChangeEvent) {
        val file = event.file
        // Null file covers file add/remove events, which can introduce or delete
        // whole declarations; non-Kotlin file edits can't affect coverage.
        if (file == null || file is KtFile) {
            CoverageService.getInstance(project).invalidate()
        }
    }

    override fun childAdded(event: PsiTreeChangeEvent) = onChange(event)
    override fun childRemoved(event: PsiTreeChangeEvent) = onChange(event)
    override fun childReplaced(event: PsiTreeChangeEvent) = onChange(event)
    override fun childMoved(event: PsiTreeChangeEvent) = onChange(event)
    override fun childrenChanged(event: PsiTreeChangeEvent) = onChange(event)
    override fun propertyChanged(event: PsiTreeChangeEvent) = onChange(event)
}
