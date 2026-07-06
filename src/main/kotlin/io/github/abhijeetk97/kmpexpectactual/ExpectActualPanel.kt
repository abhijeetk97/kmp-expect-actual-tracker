package io.github.abhijeetk97.kmpexpectactual

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.kotlin.psi.KtFile
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The complete UI of the "Expect/Actual" tool window for one project.
 *
 * One instance is created per project window by [ExpectActualToolWindowFactory], so all
 * mutable state (coverage list, filter flag, search query) lives in instance fields and
 * is naturally isolated between simultaneously-open projects. (The factory itself is a
 * platform singleton and must stay stateless — see its class doc.)
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
 *   this (BorderLayout)
 *   ├─ NORTH: JPanel
 *   │    ├─ ActionToolbar   ← Refresh + "Show incomplete only" filter
 *   │    └─ SearchTextField ← live substring filter
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
 * The panel handles four distinct states:
 *
 *   1. Scanning    — JBLoadingPanel spinner is active (startLoading)
 *   2. Gradle sync — only build scripts visible; show "Gradle sync required" in tree root
 *   3. Not KMP     — no commonMain source set; show "No KMP modules detected"
 *   4. KMP results — coverage tree populated (possibly with "all covered" or per-expect nodes)
 */
class ExpectActualPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode("Expect declarations")
    private val treeModel = DefaultTreeModel(root)

    // com.intellij.ui.treeStructure.Tree is JetBrains' drop-in for javax.swing.JTree.
    // It respects the IDE theme and keyboard shortcuts. Prefer it over raw JTree.
    private val tree = Tree(treeModel)

    // JBLoadingPanel wraps any component and can overlay it with an animated spinner:
    //   startLoading() — shows the spinner on top of the wrapped content
    //   stopLoading()  — hides the spinner, revealing the content underneath
    // The Disposable argument cleans up the panel's internal timer when the project
    // closes; Project implements Disposable, so passing it is the standard pattern.
    private val loadingPanel = JBLoadingPanel(BorderLayout(), project)

    // ── Mutable per-project state ────────────────────────────────────────────
    private var coverageList: List<Coverage> = emptyList()
    private var showIncompleteOnly = false
    // The current search text. Empty means "no filter" — show everything.
    private var searchQuery = ""

    init {
        tree.cellRenderer = CoverageTreeCellRenderer()
        installNavigationOnDoubleClick()

        loadingPanel.add(JBScrollPane(tree), BorderLayout.CENTER)
        // Start in loading state immediately so the user sees the spinner from the
        // very first moment the tool window opens, not a blank panel.
        loadingPanel.startLoading()

        // The NORTH region stacks the action toolbar above the search field so both
        // are always visible regardless of the tool window's width.
        val northPanel = JPanel(BorderLayout()).apply {
            add(createToolbar(), BorderLayout.NORTH)
            add(createSearchField(), BorderLayout.SOUTH)
        }
        add(northPanel, BorderLayout.NORTH)
        add(loadingPanel, BorderLayout.CENTER)

        // DumbService.runWhenSmart defers the initial scan until indexing is complete.
        // The spinner (startLoading above) stays visible during this wait, so the
        // user always sees feedback rather than a blank panel.
        DumbService.getInstance(project).runWhenSmart { refresh() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUTO-REFRESH ON PSI EDITS (debounced)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Re-scans automatically when Kotlin source changes, so the tree never shows
     * stale coverage until the user clicks Refresh.
     *
     * WHY DEBOUNCE
     * ------------
     * PSI change events fire on essentially every keystroke. Re-scanning the whole
     * project on each one would mean constant background work and flickering,
     * half-updated trees. Instead we coalesce: every change cancels the previously
     * scheduled refresh and re-schedules one AUTO_REFRESH_DELAY_MS later. A burst of
     * edits therefore collapses into a single scan once typing pauses.
     *
     * [parentDisposable] ties the Alarm's lifetime and the PSI listener registration
     * to the tool window content: when the content is removed (project close, plugin
     * unload), both are cleaned up automatically — no manual cleanup, no leaks.
     */
    fun installAutoRefresh(parentDisposable: Disposable) {
        // Alarm is IntelliJ's debouncing scheduler. SWING_THREAD makes its requests
        // run on the EDT, which is required because refresh() touches Swing components.
        val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parentDisposable)

        fun scheduleAutoRefresh() {
            // Drop the cached coverage now so the (debounced) re-scan recomputes it.
            CoverageService.getInstance(project).invalidate()
            // Collapse rapid edits: cancel any pending refresh and queue a fresh one.
            refreshAlarm.cancelAllRequests()
            refreshAlarm.addRequest({ refresh() }, AUTO_REFRESH_DELAY_MS)
        }

        // PsiTreeChangeAdapter gives empty defaults for every event; we override the
        // ones that signal a real edit. We ignore changes to non-Kotlin files (e.g.
        // editing a README) to avoid pointless project scans — but treat a null file
        // as relevant, since file add/remove events surface that way and can introduce
        // new declarations.
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
            parentDisposable,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REFRESH — detect project state, fetch coverage, update UI
    // ─────────────────────────────────────────────────────────────────────────

    private fun refresh() {
        // Show the spinner immediately while the background work runs.
        loadingPanel.startLoading()
        root.removeAllChildren()
        treeModel.reload()

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
                        treeModel.reload()
                    }

                    // ── Not KMP: no commonMain source set found ───────────────
                    // Kotlin source files exist but none live under commonMain.
                    // This is either a pure Android/JVM project, or a KMP project
                    // where Gradle sync ran but the KMP modules weren't created
                    // (e.g. missing Kotlin Multiplatform plugin in build.gradle.kts).
                    ProjectState.NOT_KMP -> {
                        root.removeAllChildren()
                        root.userObject = "No Kotlin Multiplatform modules detected"
                        treeModel.reload()
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
    // TREE REBUILD — from coverageList + filter state
    // ─────────────────────────────────────────────────────────────────────────

    // Only called when the project IS KMP (ProjectState.KMP). The non-KMP and
    // Gradle-sync states are handled directly in refresh() before reaching here.
    private fun applyToTree() {
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

        treeModel.reload()

        // Expand all parent nodes so platform children are visible immediately.
        // Walking forward while rowCount grows handles dynamically added rows.
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NAVIGATION ON DOUBLE-CLICK
    // ─────────────────────────────────────────────────────────────────────────

    private fun installNavigationOnDoubleClick() {
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOLBAR + SEARCH FIELD
    // ─────────────────────────────────────────────────────────────────────────

    private fun createToolbar(): javax.swing.JComponent {
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
        return toolbar.component
    }

    // SearchTextField is IntelliJ's themed search box — it ships with the magnifier
    // icon, a clear (×) button, and search history out of the box. We listen for any
    // document change and re-run applyToTree() so filtering is live as the user types.
    //
    // The listener only touches in-memory state (searchQuery) and rebuilds the tree
    // from the already-cached coverageList — no scan, no PSI, so it's cheap to run on
    // every keystroke directly on the EDT.
    private fun createSearchField(): SearchTextField =
        SearchTextField().apply {
            textEditor.emptyText.text = "Search expect declarations by name"
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    searchQuery = text
                    applyToTree()
                }
            })
        }

    companion object {
        // Debounce window for auto-refresh: a burst of edits within this interval
        // collapses into a single re-scan once the user pauses. ~500 ms is short enough
        // to feel instant but long enough to coalesce continuous typing.
        private const val AUTO_REFRESH_DELAY_MS = 500
    }
}
