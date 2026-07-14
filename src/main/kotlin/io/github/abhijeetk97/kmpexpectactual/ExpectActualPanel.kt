package io.github.abhijeetk97.kmpexpectactual

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.PopupHandler
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import org.jetbrains.kotlin.psi.KtFile
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The complete UI of the "Expect/Actual" tool window for one project.
 *
 * One instance is created per project window by [ExpectActualToolWindowFactory], so all
 * mutable state (coverage list, filter flags, search query) lives in instance fields and
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
 *   │    ├─ ActionToolbar   ← Refresh, incomplete-only, group-by, platform filter, export
 *   │    ├─ SearchTextField ← live substring filter
 *   │    └─ JBLabel         ← summary stats ("42 expects · 36 complete (86%) · 6 incomplete")
 *   └─ CENTER: JBLoadingPanel          ← shows spinner while scanning
 *                └─ JBScrollPane
 *                     └─ Tree
 *                          ├─ [GroupNode]  — only in grouped modes
 *                          │    └─ Coverage — "platformName()  [2/3 platforms]"
 *                          │         ├─ PlatformNode — "Android  ✓"
 *                          │         └─ PlatformNode — "JVM  ✗ missing"   ← red
 *                          └─ ...
 *
 * VIEW STATE
 * ----------
 * The incomplete-only toggle, group-by mode, and platform filter persist across IDE
 * restarts via [ExpectActualUiState] (workspace.xml). The search text is deliberately
 * ephemeral — searches are momentary, filters are a configuration.
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
    private val loadingPanel = JBLoadingPanel(BorderLayout(), project)

    private val statsLabel = JBLabel().apply {
        border = JBUI.Borders.empty(2, 8, 4, 8)
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }

    private val searchField = createSearchField()

    // ── Mutable per-project state ────────────────────────────────────────────
    private var coverageList: List<Coverage> = emptyList()
    // The current search text. Empty means "no filter" — show everything.
    private var searchQuery = ""
    // Persisted view configuration (incomplete-only, group-by, platform filter).
    private val uiState = ExpectActualUiState.getInstance(project)

    init {
        tree.cellRenderer = CoverageTreeCellRenderer()
        installNavigationOnDoubleClick()
        installContextMenu()

        loadingPanel.add(JBScrollPane(tree), BorderLayout.CENTER)
        // Start in loading state immediately so the user sees the spinner from the
        // very first moment the tool window opens, not a blank panel.
        loadingPanel.startLoading()

        // The NORTH region stacks toolbar, search field, and stats bar so all three
        // are always visible regardless of the tool window's width.
        val northPanel = JPanel(BorderLayout()).apply {
            add(createToolbar(), BorderLayout.NORTH)
            add(searchField, BorderLayout.CENTER)
            add(statsLabel, BorderLayout.SOUTH)
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
     * scheduled refresh and re-schedules one debounce-interval later. A burst of
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
            // (CoverageInvalidationListener also does this project-wide; doing it here
            // too keeps the tool window correct even if that listener is unregistered.)
            CoverageService.getInstance(project).invalidate()
            // Collapse rapid edits: cancel any pending refresh and queue a fresh one.
            refreshAlarm.cancelAllRequests()
            val delay = ExpectActualSettings.getInstance(project).state.autoRefreshDelayMs
            refreshAlarm.addRequest({ refresh() }, delay)
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
                    ProjectState.GRADLE_SYNC_REQUIRED -> {
                        root.removeAllChildren()
                        root.userObject = "Gradle sync required — open the Gradle panel and click ↺"
                        statsLabel.text = ""
                        treeModel.reload()
                    }

                    // ── Not KMP: no commonMain source set found ───────────────
                    ProjectState.NOT_KMP -> {
                        root.removeAllChildren()
                        root.userObject = "No Kotlin Multiplatform modules detected"
                        statsLabel.text = ""
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
    // TREE REBUILD — from coverageList + filter + grouping state
    // ─────────────────────────────────────────────────────────────────────────

    // Only called when the project IS KMP (ProjectState.KMP). The non-KMP and
    // Gradle-sync states are handled directly in refresh() before reaching here.
    private fun applyToTree() {
        // All filters compose: platform scope, then the incomplete-only toggle,
        // then the search text. When a platform filter is active, "incomplete"
        // means "missing on THAT platform" — the most useful reading when the
        // user is auditing a single target.
        val query = searchQuery.trim()
        val platformFilter = uiState.platformFilter.takeIf { it.isNotEmpty() }
        val toShow = coverageList
            .asSequence()
            .filter { platformFilter == null || platformFilter in it.knownPlatforms }
            .filter {
                when {
                    !uiState.showIncompleteOnly -> true
                    platformFilter != null      -> platformFilter in it.missingPlatforms
                    else                        -> !it.isComplete
                }
            }
            .filter { query.isEmpty() || it.matchesQuery(query) }
            .toList()

        root.removeAllChildren()
        root.userObject = when {
            coverageList.isEmpty()                 -> "No expect declarations found in this project"
            toShow.isEmpty() && query.isNotEmpty() -> "No expect declarations match \"$query\""
            toShow.isEmpty()                       -> "All expects are fully covered 🎉"
            else                                   -> "Expect declarations"
        }

        when (uiState.groupMode) {
            GroupMode.FLAT             -> buildFlat(toShow, platformFilter)
            GroupMode.PACKAGE          -> buildGrouped(toShow, platformFilter) { it.expect.fqName.parent().asString().ifEmpty { "(default package)" } }
            GroupMode.MODULE           -> buildGrouped(toShow, platformFilter) { it.expect.module ?: "(unknown module)" }
            GroupMode.MISSING_PLATFORM -> buildByMissingPlatform(toShow, platformFilter)
        }

        treeModel.reload()

        // Expand all rows so platform children are visible immediately.
        // Walking forward while rowCount grows handles dynamically added rows.
        var i = 0
        while (i < tree.rowCount) {
            tree.expandRow(i)
            i++
        }

        // The stats bar always reflects the WHOLE project, not the filtered view —
        // it's a dashboard number, and filters shouldn't make totals jump around.
        val stats = CoverageStats.from(coverageList)
        statsLabel.text = if (coverageList.isEmpty()) "" else stats.summaryLine
        statsLabel.toolTipText = stats.perPlatform.entries
            .sortedBy { it.key }
            .joinToString("<br>", prefix = "<html>", postfix = "</html>") { (platform, s) ->
                "$platform: ${s.covered}/${s.total} covered"
            }
    }

    private fun buildFlat(toShow: List<Coverage>, platformFilter: String?) {
        toShow.sortedBy { it.expect.fqName.asString() }
            .forEach { root.add(expectNode(it, platformFilter)) }
    }

    private fun buildGrouped(
        toShow: List<Coverage>,
        platformFilter: String?,
        keyOf: (Coverage) -> String,
    ) {
        toShow.groupBy(keyOf).entries.sortedBy { it.key }.forEach { (label, members) ->
            val groupNode = DefaultMutableTreeNode(
                GroupNode(label, covered = members.count { it.isComplete }, total = members.size),
            )
            members.sortedBy { it.expect.fqName.asString() }
                .forEach { groupNode.add(expectNode(it, platformFilter)) }
            root.add(groupNode)
        }
    }

    /**
     * One group per platform, listing every expect that is missing an actual
     * there — the "what do I need to write to ship the iOS target?" view.
     * Fully-covered platforms show as an empty group only if they had missing
     * members before search filtering; platforms with nothing missing are omitted.
     */
    private fun buildByMissingPlatform(toShow: List<Coverage>, platformFilter: String?) {
        val platforms = toShow.flatMap { it.missingPlatforms }.toSet()
            .filter { platformFilter == null || it == platformFilter }
            .sorted()
        platforms.forEach { platform ->
            val missing = toShow.filter { platform in it.missingPlatforms }
            val relevant = coverageList.count { platform in it.knownPlatforms }
            val groupNode = DefaultMutableTreeNode(
                GroupNode(platform, covered = relevant - missing.size, total = relevant),
            )
            missing.sortedBy { it.expect.fqName.asString() }
                .forEach { groupNode.add(expectNode(it, platform)) }
            root.add(groupNode)
        }
        if (platforms.isEmpty() && toShow.isNotEmpty()) {
            root.userObject = "No missing actuals with the current filters 🎉"
        }
    }

    private fun expectNode(coverage: Coverage, platformFilter: String?): DefaultMutableTreeNode {
        val parentNode = DefaultMutableTreeNode(coverage)
        coverage.knownPlatforms.sorted()
            .filter { platformFilter == null || it == platformFilter }
            .forEach { platform ->
                val ptr = coverage.actualsByPlatform[platform]
                parentNode.add(DefaultMutableTreeNode(PlatformNode(platform, ptr)))
            }
        return parentNode
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SELECTION FROM OUTSIDE — "Reveal in Expect/Actual Tracker"
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Selects and scrolls to the expect with the given fully-qualified name.
     * If active filters hide it, they are cleared first so the reveal always
     * lands somewhere visible. Called by [RevealInTrackerAction].
     */
    fun selectExpect(fqName: String) {
        var node = findExpectNode(fqName)
        if (node == null) {
            // Clear anything that could hide the target, rebuild, and retry.
            searchField.text = ""
            searchQuery = ""
            uiState.showIncompleteOnly = false
            uiState.platformFilter = ""
            applyToTree()
            node = findExpectNode(fqName)
        }
        node ?: return
        val path = TreePath(node.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    private fun findExpectNode(fqName: String): DefaultMutableTreeNode? {
        val e = root.depthFirstEnumeration()
        while (e.hasMoreElements()) {
            val n = e.nextElement() as DefaultMutableTreeNode
            val obj = n.userObject
            if (obj is Coverage && obj.expect.fqName.asString() == fqName) return n
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NAVIGATION — double-click and context menu share this
    // ─────────────────────────────────────────────────────────────────────────

    private fun selectedNode(): DefaultMutableTreeNode? =
        tree.lastSelectedPathComponent as? DefaultMutableTreeNode

    /** The pointer a tree node navigates to: the expect itself, or one actual. */
    private fun pointerOf(node: DefaultMutableTreeNode?): SmartPsiElementPointer<*>? =
        when (val obj = node?.userObject) {
            is Coverage     -> obj.expect.pointer
            is PlatformNode -> obj.pointer // null when missing
            else            -> null
        }

    private fun navigateTo(pointer: SmartPsiElementPointer<*>) {
        ReadAction.nonBlocking<NavigatablePsiElement?> {
            pointer.element as? NavigatablePsiElement
        }.finishOnUiThread(ModalityState.defaultModalityState()) { el ->
            el?.navigate(true)
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun installNavigationOnDoubleClick() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                pointerOf(selectedNode())?.let(::navigateTo)
            }
        })
    }

    private fun installContextMenu() {
        val jumpToSource = object : AnAction("Jump to Source", "Navigate to this declaration", AllIcons.Actions.EditSource) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = pointerOf(selectedNode()) != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                pointerOf(selectedNode())?.let(::navigateTo)
            }
        }

        val copyFqName = object : AnAction("Copy Qualified Name", "Copy the expect's fully-qualified name", AllIcons.Actions.Copy) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = coverageOf(selectedNode()) != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val coverage = coverageOf(selectedNode()) ?: return
                CopyPasteManager.copyTextToClipboard(coverage.expect.fqName.asString())
            }
        }

        PopupHandler.installPopupMenu(
            tree,
            DefaultActionGroup(jumpToSource, copyFqName),
            "ExpectActualTreePopup",
        )
    }

    /** The Coverage a node belongs to: the node itself, or its parent for platform rows. */
    private fun coverageOf(node: DefaultMutableTreeNode?): Coverage? =
        when (val obj = node?.userObject) {
            is Coverage     -> obj
            is PlatformNode -> (node.parent as? DefaultMutableTreeNode)?.userObject as? Coverage
            else            -> null
        }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOLBAR + SEARCH FIELD
    // ─────────────────────────────────────────────────────────────────────────

    private fun createToolbar(): JComponent {
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
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = uiState.showIncompleteOnly

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                uiState.showIncompleteOnly = state
                applyToTree()
            }
        }

        val exportAction = object : AnAction(
            "Export Report",
            "Export the coverage matrix as HTML or CSV",
            AllIcons.ToolbarDecorator.Export,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = coverageList.isNotEmpty()
            }

            override fun actionPerformed(e: AnActionEvent) = exportReport()
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ExpectActualToolbar",
            DefaultActionGroup(
                refreshAction,
                filterAction,
                GroupModeComboAction(),
                PlatformFilterComboAction(),
                exportAction,
            ),
            true,
        )
        toolbar.targetComponent = tree
        return toolbar.component
    }

    /** Dropdown that switches how the tree is grouped. Shows the active mode as its text. */
    private inner class GroupModeComboAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = uiState.groupMode.displayName
            e.presentation.description = "Choose how expect declarations are grouped"
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            GroupMode.entries.forEach { mode ->
                group.add(object : ToggleAction(mode.displayName) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = uiState.groupMode == mode
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state) {
                            uiState.groupMode = mode
                            applyToTree()
                        }
                    }
                })
            }
            return group
        }
    }

    /** Dropdown that scopes the tree to a single platform. Populated from the last scan. */
    private inner class PlatformFilterComboAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = uiState.platformFilter.ifEmpty { "All platforms" }
            e.presentation.description = "Scope the tree to a single platform"
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
            val group = DefaultActionGroup()
            val platforms = listOf("") + coverageList.flatMap { it.knownPlatforms }.toSet().sorted()
            platforms.forEach { platform ->
                group.add(object : ToggleAction(platform.ifEmpty { "All platforms" }) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun isSelected(e: AnActionEvent) = uiState.platformFilter == platform
                    override fun setSelected(e: AnActionEvent, state: Boolean) {
                        if (state) {
                            uiState.platformFilter = platform
                            applyToTree()
                        }
                    }
                })
            }
            return group
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // EXPORT — HTML / CSV report (issue #11)
    // ─────────────────────────────────────────────────────────────────────────

    private fun exportReport() {
        val descriptor = FileSaverDescriptor(
            "Export Coverage Report",
            "Choose where to save the report. The format follows the extension (.html or .csv).",
            "html", "csv",
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save("expect-actual-coverage.html")
            ?: return // user cancelled

        // Snapshot on the EDT; the generator only reads plain value fields, no PSI.
        val snapshot = coverageList
        val file = wrapper.file
        val content = if (file.extension.equals("csv", ignoreCase = true)) {
            CoverageReportGenerator.toCsv(snapshot)
        } else {
            CoverageReportGenerator.toHtml(snapshot, project.name)
        }

        try {
            file.writeText(content)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("KMP Expect/Actual Tracker")
                .createNotification("Coverage report exported to ${file.path}", NotificationType.INFORMATION)
                .notify(project)
        } catch (ex: java.io.IOException) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("KMP Expect/Actual Tracker")
                .createNotification("Failed to export coverage report: ${ex.message}", NotificationType.ERROR)
                .notify(project)
        }
    }
}
