package io.github.abhijeetk97.kmpexpectactual

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.psi.KtFile
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

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

/**
 * Builds and populates the "Expect/Actual" tool window panel.
 *
 * HOW INTELLIJ PLUGINS WORK (the mental model)
 * ---------------------------------------------
 * A plugin is NOT an app with a main(). It's a bundle of "extensions" — classes
 * that plug into pre-defined platform hooks called Extension Points.
 *
 * You declare what you're providing in plugin.xml:
 *
 *   <toolWindow
 *       id="Expect/Actual"
 *       factoryClass="...ExpectActualToolWindowFactory" />
 *
 * The platform reads that, and when the user opens (or the IDE loads) the tool window,
 * it instantiates THIS class and calls createToolWindowContent(). You never new() it
 * yourself. That's the inversion of control that makes plugin dev feel different.
 *
 * THREADING MODEL (critical — read this once)
 * -------------------------------------------
 * IntelliJ has two important threads:
 *
 *   EDT (Event Dispatch Thread) — the UI thread. ALL Swing rendering and state changes
 *   MUST happen here. You implicitly have read access to PSI here. You must NOT do
 *   slow work here (network, disk, scanning 500 Kotlin files) or the IDE freezes.
 *
 *   Background threads — where slow work goes. You CAN do PSI reads here but only
 *   inside a ReadAction so the platform can lock PSI properly. You must NOT touch
 *   any Swing component from here.
 *
 * Our pattern:
 *   1. On the EDT, set up the UI (tree, renderer, click listener, toolbar).
 *   2. Kick off a non-blocking read action on a background thread to run the scanner.
 *   3. When the scanner finishes, the platform marshals the result back to the EDT
 *      via finishOnUiThread(), where we safely update the Swing tree.
 *
 * LAYOUT STRUCTURE
 * ----------------
 *   JPanel (BorderLayout)
 *   ├─ NORTH: ActionToolbar  ← Refresh + "Show incomplete only" filter
 *   └─ CENTER: JBLoadingPanel          ← shows spinner while scanning
 *                └─ JBScrollPane
 *                     └─ Tree
 *                          ├─ Coverage (parent) — "platformName()  [2/3 platforms]"
 *                          │    ├─ PlatformNode — "Android  ✓"
 *                          │    ├─ PlatformNode — "iOS  ✓"
 *                          │    └─ PlatformNode — "JVM  ✗ missing"   ← red
 *                          └─ ...
 *
 * LOADING / EMPTY STATES
 * -----------------------
 * The plugin handles four distinct states:
 *
 *   1. Scanning    — JBLoadingPanel spinner is active (startLoading)
 *   2. Gradle sync — only build scripts visible; show "Gradle sync required" in tree root
 *   3. Not KMP     — no commonMain source set; show "No KMP modules detected"
 *   4. KMP results — coverage tree populated (possibly with "all covered" or per-expect nodes)
 *
 * MUTABLE STATE IN LOCAL FUNCTIONS
 * ----------------------------------
 * All state for this tool window instance (coverage list, filter flag) is held as local
 * `var` variables inside createToolWindowContent(). Local helper functions and action
 * lambdas capture them as closures.
 *
 * This is intentional: ToolWindowFactory is a singleton shared across all open projects.
 * Putting state in instance fields would mean two simultaneously-open projects share
 * (and corrupt) each other's coverage data. Local vars in createToolWindowContent are
 * created fresh per project window, so each project gets independent state.
 */
class ExpectActualToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        // ─────────────────────────────────────────────────────────────────────────
        // 1. TREE SETUP
        // ─────────────────────────────────────────────────────────────────────────
        val root = DefaultMutableTreeNode("Expect declarations")
        val model = DefaultTreeModel(root)

        // com.intellij.ui.treeStructure.Tree is JetBrains' drop-in for javax.swing.JTree.
        // It respects the IDE theme and keyboard shortcuts. Prefer it over raw JTree.
        val tree = Tree(model)

        // ─────────────────────────────────────────────────────────────────────────
        // 2. LOADING PANEL
        // ─────────────────────────────────────────────────────────────────────────
        // JBLoadingPanel is an IntelliJ component that wraps any other component and
        // can overlay it with an animated spinner. Two key calls:
        //   startLoading() — shows the spinner on top of the wrapped content
        //   stopLoading()  — hides the spinner, revealing the content underneath
        //
        // The second constructor argument is a Disposable — the platform uses it to
        // clean up the panel's internal timer when the project closes. Project itself
        // implements Disposable, so passing `project` here is the standard pattern.
        //
        // We start in loading state immediately so the user sees the spinner from the
        // very first moment the tool window opens, not a blank panel.
        val loadingPanel = JBLoadingPanel(BorderLayout(), project)
        loadingPanel.add(JBScrollPane(tree), BorderLayout.CENTER)
        loadingPanel.startLoading()

        // ─────────────────────────────────────────────────────────────────────────
        // 3. MUTABLE STATE — captured by local functions below
        // ─────────────────────────────────────────────────────────────────────────
        var coverageList: List<Coverage> = emptyList()
        var showIncompleteOnly = false
        // The current search text. Empty means "no filter" — show everything.
        // Captured by applyToTree() below and updated by the search field's listener.
        var searchQuery = ""

        // ─────────────────────────────────────────────────────────────────────────
        // 4. applyToTree() — rebuild the tree from coverageList + filter state
        // ─────────────────────────────────────────────────────────────────────────
        // Only called when the project IS KMP (ProjectState.KMP). The non-KMP and
        // Gradle-sync states are handled directly in refresh() before reaching here.
        fun applyToTree() {
            // Both filters compose: first the "incomplete only" toggle, then the
            // search text. Search is a case-insensitive substring match against the
            // expect's display name, its fully-qualified name (so package fragments
            // match too), and its platform labels (Android, iOS, JVM, …).
            val query = searchQuery.trim()
            val toShow = coverageList
                .asSequence()
                .filter { !showIncompleteOnly || !it.isComplete }
                .filter { query.isEmpty() || it.matchesQuery(query) }
                .toList()

            root.removeAllChildren()
            root.userObject = when {
                coverageList.isEmpty()        -> "No expect declarations found in this project"
                toShow.isEmpty() && query.isNotEmpty() -> "No expect declarations match \"$query\""
                toShow.isEmpty()              -> "All expects are fully covered 🎉"
                else                          -> "Expect declarations"
            }

            toShow.sortedBy { it.expect.fqName.asString() }.forEach { coverage ->
                val parentNode = DefaultMutableTreeNode(coverage)
                coverage.knownPlatforms.sorted().forEach { platform ->
                    val ptr = coverage.actualsByPlatform[platform]
                    parentNode.add(DefaultMutableTreeNode(PlatformNode(platform, ptr)))
                }
                root.add(parentNode)
            }

            model.reload()

            // Expand all parent nodes so platform children are visible immediately.
            // Walking forward while rowCount grows handles dynamically added rows.
            var i = 0
            while (i < tree.rowCount) {
                tree.expandRow(i)
                i++
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 5. refresh() — detect project state, fetch coverage, update UI
        // ─────────────────────────────────────────────────────────────────────────
        fun refresh() {
            // Show the spinner immediately while the background work runs.
            loadingPanel.startLoading()
            root.removeAllChildren()
            model.reload()

            // The background lambda returns a Pair so we can pass both the project
            // state (for the empty-state message) and the coverage list (for the tree)
            // back to the EDT in a single finishOnUiThread call.
            //
            // We only call CoverageService when the project is actually KMP —
            // no point scanning for expects in a non-KMP or unsynced project.
            ReadAction.nonBlocking<Pair<ProjectState, List<Coverage>>> {
                val state = ExpectActualScanner.detectProjectState(project)
                val coverage = if (state == ProjectState.KMP) {
                    CoverageService.getInstance(project).getCoverage()
                } else {
                    emptyList()
                }
                Pair(state, coverage)
            }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState()) { (state, list) ->
                    // Scan complete — hide the spinner regardless of outcome.
                    loadingPanel.stopLoading()

                    when (state) {
                        // ── Not synced: Gradle modules not imported yet ───────────
                        // The user opened a project but hasn't run Gradle sync, so only
                        // build scripts are visible to our file index. Guide them to fix it.
                        ProjectState.GRADLE_SYNC_REQUIRED -> {
                            root.removeAllChildren()
                            root.userObject = "Gradle sync required — open the Gradle panel and click ↺"
                            model.reload()
                        }

                        // ── Not KMP: no commonMain source set found ───────────────
                        // Kotlin source files exist but none live under commonMain.
                        // This is either a pure Android/JVM project, or a KMP project
                        // where Gradle sync ran but the KMP modules weren't created
                        // (e.g. missing Kotlin Multiplatform plugin in build.gradle.kts).
                        ProjectState.NOT_KMP -> {
                            root.removeAllChildren()
                            root.userObject = "No Kotlin Multiplatform modules detected"
                            model.reload()
                        }

                        // ── KMP project: populate the coverage tree ───────────────
                        ProjectState.KMP -> {
                            coverageList = list
                            applyToTree()
                        }
                    }
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 6. RENDERER — controls how each row looks
        // ─────────────────────────────────────────────────────────────────────────
        // customizeCellRenderer is called for EVERY visible row on EVERY repaint.
        // Keep it fast: no PSI access, no IO, just append() calls.
        //
        // JBColor(lightColor, darkColor) picks the right colour for the active theme.
        val coveredAttrs = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN,
            JBColor(Color(0, 128, 0), Color(98, 150, 85)),
        )

        tree.cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                val node = value as? DefaultMutableTreeNode ?: return

                when (val obj = node.userObject) {

                    // ── PARENT ROW: one expect declaration ──────────────────────
                    is Coverage -> {
                        val e = obj.expect
                        append("[${e.kind}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                        // Name turns red when any platform is missing.
                        val nameAttrs = if (obj.isComplete) {
                            SimpleTextAttributes.REGULAR_ATTRIBUTES
                        } else {
                            SimpleTextAttributes.ERROR_ATTRIBUTES
                        }
                        append(e.displayName, nameAttrs)

                        // Package path in small grey
                        append("  ${e.fqName.parent().asString()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

                        // Coverage badge — only shown when platforms are known
                        if (obj.knownPlatforms.isNotEmpty()) {
                            append("  [${obj.coverageSummary}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }

                    // ── CHILD ROW: one platform under an expect ──────────────────
                    is PlatformNode -> {
                        append(obj.platform, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        if (obj.isCovered) {
                            append("  ✓", coveredAttrs)
                        } else {
                            append("  ✗ missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        }
                    }

                    // ── ROOT / PLACEHOLDER strings (including empty-state messages) ──
                    else -> append(obj.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 7. NAVIGATION ON DOUBLE-CLICK
        // ─────────────────────────────────────────────────────────────────────────
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return

                val pointer: SmartPsiElementPointer<*>? = when (val obj = node.userObject) {
                    is Coverage     -> obj.expect.pointer
                    is PlatformNode -> obj.pointer // null when missing — handled below
                    else            -> null
                }
                pointer ?: return

                ReadAction.nonBlocking<NavigatablePsiElement?> {
                    pointer.element as? NavigatablePsiElement
                }.finishOnUiThread(ModalityState.defaultModalityState()) { el ->
                    el?.navigate(true)
                }.submit(AppExecutorUtil.getAppExecutorService())
            }
        })

        // ─────────────────────────────────────────────────────────────────────────
        // 8. TOOLBAR ACTIONS
        // ─────────────────────────────────────────────────────────────────────────

        val refreshAction = object : AnAction(
            "Refresh",
            "Invalidate cache and re-scan the project for expect declarations",
            AllIcons.Actions.Refresh,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                CoverageService.getInstance(project).invalidate()
                refresh()
            }
        }

        // ToggleAction is IntelliJ's two-state toolbar button. The platform renders it
        // with a "pressed" highlight when isSelected() returns true — no extra UI needed.
        // setSelected() just flips the flag and re-renders from the cached list in memory.
        val filterAction = object : ToggleAction(
            "Show incomplete only",
            "Hide expects that are fully covered on all platforms",
            AllIcons.General.Filter,
        ) {
            override fun isSelected(e: AnActionEvent): Boolean = showIncompleteOnly

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showIncompleteOnly = state
                applyToTree()
            }
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ExpectActualToolbar",
            DefaultActionGroup(refreshAction, filterAction),
            true,
        )
        toolbar.targetComponent = tree

        // ─────────────────────────────────────────────────────────────────────────
        // 9. SEARCH FIELD
        // ─────────────────────────────────────────────────────────────────────────
        // SearchTextField is IntelliJ's themed search box — it ships with the magnifier
        // icon, a clear (×) button, and search history out of the box. We listen for any
        // document change and re-run applyToTree() so filtering is live as the user types.
        //
        // The listener only touches in-memory state (searchQuery) and rebuilds the tree
        // from the already-cached coverageList — no scan, no PSI, so it's cheap to run on
        // every keystroke directly on the EDT.
        val searchField = SearchTextField().apply {
            textEditor.emptyText.text = "Search expect declarations by name"
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    searchQuery = text
                    applyToTree()
                }
            })
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 10. PANEL ASSEMBLY
        // ─────────────────────────────────────────────────────────────────────────
        // The NORTH region stacks the action toolbar above the search field so both are
        // always visible regardless of the tool window's width.
        val northPanel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(searchField, BorderLayout.SOUTH)
        }
        val panel = JPanel(BorderLayout()).apply {
            add(northPanel, BorderLayout.NORTH)
            add(loadingPanel, BorderLayout.CENTER)  // loadingPanel wraps the scroll+tree
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 11. INITIAL SCAN
        // ─────────────────────────────────────────────────────────────────────────
        // DumbService.runWhenSmart defers the scan until indexing is complete.
        // The spinner (startLoading above) stays visible during this wait, so the
        // user always sees feedback rather than a blank panel.
        DumbService.getInstance(project).runWhenSmart { refresh() }

        // ─────────────────────────────────────────────────────────────────────────
        // 12. ADD TO TOOL WINDOW
        // ─────────────────────────────────────────────────────────────────────────
        // Create the Content up front so it can serve as the parent Disposable for the
        // auto-refresh machinery below. When the tool window content is removed (project
        // close, plugin unload), the platform disposes the Content, which in turn disposes
        // the Alarm and unregisters the PSI listener — no manual cleanup, no leaks.
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)

        // ─────────────────────────────────────────────────────────────────────────
        // 13. AUTO-REFRESH ON PSI EDITS (debounced)
        // ─────────────────────────────────────────────────────────────────────────
        // Without this, the tree shows stale coverage until the user clicks Refresh.
        // We listen for PSI (parsed-code) changes and re-scan automatically.
        //
        // WHY DEBOUNCE
        // ------------
        // PSI change events fire on essentially every keystroke. Re-scanning the whole
        // project on each one would mean constant background work and flickering,
        // half-updated trees. Instead we coalesce: every change cancels the previously
        // scheduled refresh and re-schedules one AUTO_REFRESH_DELAY_MS later. A burst of
        // edits therefore collapses into a single scan once typing pauses.
        //
        // Alarm is IntelliJ's debouncing scheduler. SWING_THREAD makes its requests run
        // on the EDT, which is required because refresh() touches Swing components. The
        // `content` parent Disposable ties the Alarm's lifetime to this tool window.
        val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, content)

        fun scheduleAutoRefresh() {
            // Drop the cached coverage now so the (debounced) re-scan recomputes it.
            CoverageService.getInstance(project).invalidate()
            // Collapse rapid edits: cancel any pending refresh and queue a fresh one.
            refreshAlarm.cancelAllRequests()
            refreshAlarm.addRequest({ refresh() }, AUTO_REFRESH_DELAY_MS)
        }

        // PsiTreeChangeAdapter gives empty defaults for every event; we override the ones
        // that signal a real edit. We ignore changes to non-Kotlin files (e.g. editing a
        // README) to avoid pointless project scans — but treat a null file as relevant,
        // since file add/remove events surface that way and can introduce new declarations.
        PsiManager.getInstance(project).addPsiTreeChangeListener(
            object : PsiTreeChangeAdapter() {
                private fun onChange(event: PsiTreeChangeEvent) {
                    val file = event.file
                    if (file == null || file is KtFile) scheduleAutoRefresh()
                }

                override fun childAdded(event: PsiTreeChangeEvent) = onChange(event)
                override fun childRemoved(event: PsiTreeChangeEvent) = onChange(event)
                override fun childReplaced(event: PsiTreeChangeEvent) = onChange(event)
                override fun childMoved(event: PsiTreeChangeEvent) = onChange(event)
                override fun childrenChanged(event: PsiTreeChangeEvent) = onChange(event)
                override fun propertyChanged(event: PsiTreeChangeEvent) = onChange(event)
            },
            content,
        )
    }

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
    private fun Coverage.matchesQuery(query: String): Boolean {
        val needle = query.lowercase()
        if (expect.displayName.lowercase().contains(needle)) return true
        if (expect.fqName.asString().lowercase().contains(needle)) return true
        return knownPlatforms.any { it.lowercase().contains(needle) }
    }

    companion object {
        // Debounce window for auto-refresh: a burst of edits within this interval
        // collapses into a single re-scan once the user pauses. ~500 ms is short enough
        // to feel instant but long enough to coalesce continuous typing.
        private const val AUTO_REFRESH_DELAY_MS = 500
    }
}
