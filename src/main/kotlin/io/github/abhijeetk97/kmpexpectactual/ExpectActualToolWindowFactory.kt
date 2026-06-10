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
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

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
 *   └─ CENTER: JBScrollPane
 *                └─ Tree
 *                     ├─ Coverage (parent) — "platformName()  [2/3 platforms]"
 *                     │    ├─ PlatformNode — "Android  ✓"
 *                     │    ├─ PlatformNode — "iOS  ✓"
 *                     │    └─ PlatformNode — "JVM  ✗ missing"   ← red
 *                     └─ Coverage (parent) — "UserAgent  [1/2 platforms]"  ← red name
 *                          ├─ PlatformNode — "Android  ✓"
 *                          └─ PlatformNode — "iOS  ✗ missing"
 *
 * MUTABLE STATE IN LOCAL FUNCTIONS
 * ----------------------------------
 * All state for this tool window instance (the coverage list and filter flag) is held
 * as local `var` variables inside createToolWindowContent(). The local helper functions
 * (applyToTree, refresh) and the action lambdas all capture those vars as closures.
 *
 * This is intentional: ToolWindowFactory is a singleton shared across all open projects.
 * Putting state in instance fields would mean two simultaneously open projects would
 * share (and corrupt) each other's coverage data. Local vars in createToolWindowContent
 * are created fresh for each project window, so each project gets its own independent state.
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
        // 2. MUTABLE STATE — captured by local functions below
        // ─────────────────────────────────────────────────────────────────────────
        // The last coverage list fetched from CoverageService. Starts empty; populated
        // by the first refresh() call. Retained so the filter can re-render without
        // re-scanning when the user toggles "show incomplete only".
        var coverageList: List<Coverage> = emptyList()

        // Whether the "show incomplete only" filter is currently active.
        var showIncompleteOnly = false

        // ─────────────────────────────────────────────────────────────────────────
        // 3. applyToTree() — rebuild the tree from the current coverageList + filter
        // ─────────────────────────────────────────────────────────────────────────
        // This is a LOCAL FUNCTION (a Kotlin feature: a function defined inside another
        // function). It captures `coverageList`, `showIncompleteOnly`, `root`, `model`,
        // and `tree` from the enclosing scope.
        //
        // Separating this from refresh() is the key to the "filter without re-scan"
        // behaviour: refresh() fetches data and updates `coverageList`, then calls
        // applyToTree(). The filter toggle action only calls applyToTree() — no fetch.
        fun applyToTree() {
            val toShow = if (showIncompleteOnly) {
                coverageList.filter { !it.isComplete }
            } else {
                coverageList
            }

            root.removeAllChildren()
            root.userObject = when {
                coverageList.isEmpty() -> "No expect declarations found"
                toShow.isEmpty()       -> "All expects are fully covered 🎉"
                else                   -> "Expect declarations"
            }

            toShow.sortedBy { it.expect.fqName.asString() }.forEach { coverage ->
                val parentNode = DefaultMutableTreeNode(coverage)

                // Add one child row per known platform, sorted alphabetically so the
                // order is stable and easy to scan visually.
                coverage.knownPlatforms.sorted().forEach { platform ->
                    // Look up whether this platform has a matching `actual`.
                    // actualsByPlatform[platform] is non-null iff an actual was found.
                    val ptr = coverage.actualsByPlatform[platform]
                    parentNode.add(DefaultMutableTreeNode(PlatformNode(platform, ptr)))
                }

                root.add(parentNode)
            }

            model.reload()

            // Expand all parent (Coverage) rows so the user immediately sees the
            // platform children without having to click each one open.
            // We walk forward while rowCount grows: expandRow(i) makes child rows
            // visible, increasing rowCount, so the loop naturally expands everything.
            var i = 0
            while (i < tree.rowCount) {
                tree.expandRow(i)
                i++
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 4. refresh() — fetch from cache (or re-scan) then apply to tree
        // ─────────────────────────────────────────────────────────────────────────
        fun refresh() {
            root.removeAllChildren()
            root.userObject = "Scanning…"
            model.reload()

            // Ask the service for coverage data. CoverageService returns the cached
            // list if available, or runs the full scanner on first call / after invalidate().
            // Either way this is PSI work, so it must stay inside a read action.
            ReadAction.nonBlocking<List<Coverage>> {
                CoverageService.getInstance(project).getCoverage()
            }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState()) { list ->
                    // Back on the EDT — safe to touch Swing state.
                    coverageList = list
                    applyToTree()
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 5. RENDERER — controls how each row looks
        // ─────────────────────────────────────────────────────────────────────────
        // customizeCellRenderer is called for EVERY visible row on EVERY repaint.
        // Keep it fast: no PSI access, no IO, just append() calls.
        //
        // COVERED_ATTRS: a muted green that reads well in both light and dark themes.
        //   JBColor(lightThemeColor, darkThemeColor) — the platform picks the right one.
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

                        // [kind] badge in dim grey
                        append("[${e.kind}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                        // Declaration name: red if any platform is missing, normal otherwise.
                        // ERROR_ATTRIBUTES is the platform's standard "problem" colour — it
                        // automatically adapts to dark/light theme and editor colour schemes.
                        val nameAttrs = if (obj.isComplete) {
                            SimpleTextAttributes.REGULAR_ATTRIBUTES
                        } else {
                            SimpleTextAttributes.ERROR_ATTRIBUTES
                        }
                        append(e.displayName, nameAttrs)

                        // Package path in small grey after the name
                        append("  ${e.fqName.parent().asString()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

                        // Coverage badge: "  [2/3 platforms]" — always grey regardless of
                        // completeness (the red name already draws attention to problems).
                        // Only shown when there are known platforms; hides for expect-only
                        // declarations with no actuals anywhere yet.
                        if (obj.knownPlatforms.isNotEmpty()) {
                            append("  [${obj.coverageSummary}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }

                    // ── CHILD ROW: one platform under an expect ──────────────────
                    is PlatformNode -> {
                        append(obj.platform, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        if (obj.isCovered) {
                            // ✓ in green — the actual exists
                            append("  ✓", coveredAttrs)
                        } else {
                            // ✗ missing in red — no actual for this platform
                            append("  ✗ missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        }
                    }

                    // ── ROOT / PLACEHOLDER strings ────────────────────────────────
                    else -> append(obj.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 6. NAVIGATION ON DOUBLE-CLICK
        // ─────────────────────────────────────────────────────────────────────────
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return

                // Determine which pointer to navigate to based on node type:
                //   Coverage (parent) → jump to the `expect` declaration
                //   PlatformNode (child, covered) → jump to the `actual` declaration
                //   PlatformNode (child, missing) → nothing to navigate to
                val pointer: SmartPsiElementPointer<*>? = when (val obj = node.userObject) {
                    is Coverage    -> obj.expect.pointer
                    is PlatformNode -> obj.pointer  // null if missing — handled below
                    else           -> null
                }
                pointer ?: return

                // Reading PSI must happen inside a read action (even just dereferencing
                // the pointer). nonBlocking keeps us off the EDT during the read.
                ReadAction.nonBlocking<NavigatablePsiElement?> {
                    pointer.element as? NavigatablePsiElement
                }.finishOnUiThread(ModalityState.defaultModalityState()) { el ->
                    // navigate(true) opens the file and moves the caret to the declaration.
                    // `true` = request focus (bring editor to front).
                    el?.navigate(true)
                }.submit(AppExecutorUtil.getAppExecutorService())
            }
        })

        // ─────────────────────────────────────────────────────────────────────────
        // 7. TOOLBAR ACTIONS
        // ─────────────────────────────────────────────────────────────────────────

        // ── Refresh ──────────────────────────────────────────────────────────────
        val refreshAction = object : AnAction(
            "Refresh",
            "Invalidate cache and re-scan the project for expect declarations",
            AllIcons.Actions.Refresh,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                // Drop the cached coverage so getCoverage() runs the scanner again.
                CoverageService.getInstance(project).invalidate()
                refresh()
            }
        }

        // ── "Show incomplete only" filter toggle ──────────────────────────────────
        // ToggleAction is IntelliJ's built-in action for a two-state (on/off) toolbar
        // button. The platform automatically renders it with a "pressed" visual state
        // when isSelected() returns true — no extra UI code needed.
        //
        // isSelected() is called by the platform on every toolbar repaint to decide
        // whether to draw the button as pressed/highlighted. Keep it fast.
        //
        // setSelected() is called when the user clicks the button. We flip the flag
        // and call applyToTree() — which re-renders from the already-loaded coverageList
        // without triggering a new background scan. This is the "filter without re-scan"
        // behaviour described in the issue.
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

        // DefaultActionGroup is a container for one or more AnActions. The toolbar
        // renders each action as a button in the order they appear in the group.
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ExpectActualToolbar",
            DefaultActionGroup(refreshAction, filterAction),
            true, // true = horizontal toolbar
        )
        toolbar.targetComponent = tree

        // ─────────────────────────────────────────────────────────────────────────
        // 8. PANEL ASSEMBLY
        // ─────────────────────────────────────────────────────────────────────────
        // BorderLayout divides a container into 5 zones. CENTER expands to fill all
        // remaining space (the tree). NORTH stays at its natural height (toolbar strip).
        val panel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }

        // ─────────────────────────────────────────────────────────────────────────
        // 9. INITIAL SCAN
        // ─────────────────────────────────────────────────────────────────────────
        // DumbService.runWhenSmart defers the first scan until the IDE has finished
        // indexing. Without this, FileTypeIndex returns 0 files and the tree shows
        // "No expect declarations found" permanently.
        // See the long comment in the previous commit for full details on the timing.
        DumbService.getInstance(project).runWhenSmart { refresh() }

        // ─────────────────────────────────────────────────────────────────────────
        // 10. ADD TO TOOL WINDOW
        // ─────────────────────────────────────────────────────────────────────────
        toolWindow.contentManager.addContent(
            toolWindow.contentManager.factory.createContent(panel, null, false),
        )
    }
}
