package io.github.abhijeetk97.kmpexpectactual

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.NavigatablePsiElement
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

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
 *   ├─ NORTH:  ActionToolbar  ← Refresh button lives here
 *   └─ CENTER: JBScrollPane
 *                └─ Tree      ← expect declarations listed here
 */
class ExpectActualToolWindowFactory : ToolWindowFactory {

    /**
     * Called once by the platform when the tool window is first opened.
     * Everything set up here lives for the lifetime of the open project.
     *
     * [project] — the currently open project. Almost every IntelliJ API is
     *             reached through a Project instance.
     * [toolWindow] — the container we're filling. We add our Swing panel to it
     *                via its ContentManager.
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {

        // -------------------------------------------------------------------------
        // 1. BUILD THE SWING TREE
        // -------------------------------------------------------------------------
        // IntelliJ's plugin UI is plain Swing (with some JetBrains wrapper components
        // prefixed "JB" that handle dark/light themes correctly).
        //
        // DefaultMutableTreeNode / DefaultTreeModel are standard Swing. The "root" node
        // is never shown directly (isRootVisible = false by default in Tree), but it
        // holds all the leaf nodes we'll add for each ExpectEntry.
        val root = DefaultMutableTreeNode("Expect declarations")
        val model = DefaultTreeModel(root)

        // com.intellij.ui.treeStructure.Tree is JetBrains' drop-in for javax.swing.JTree.
        // It respects the IDE theme and keyboard shortcuts. Prefer it over raw JTree.
        val tree = Tree(model)

        // -------------------------------------------------------------------------
        // 2. CUSTOM RENDERER — controls how each row looks
        // -------------------------------------------------------------------------
        // By default, Tree calls toString() on the node's userObject. We override
        // that with a ColoredTreeCellRenderer so we can paint each part of the label
        // in a different colour/style.
        //
        // customizeCellRenderer is called for EVERY visible row every time the tree
        // repaints. Keep it fast — no PSI access, no IO, just append() calls.
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
                    is Coverage -> {
                        // Unwrap the ExpectEntry from the Coverage wrapper for display.
                        // Issue #4 will extend this branch to also show per-platform nodes.
                        val e = obj.expect
                        // SimpleTextAttributes controls colour and style (bold, italic, strikethrough).
                        // GRAYED = dim grey, REGULAR = normal foreground colour.
                        append("[${e.kind}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append(e.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        // The package name in small grey after the declaration name.
                        append("  ${e.fqName.parent().asString()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    // Root / placeholder nodes just show their string label.
                    else -> append(obj.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        // -------------------------------------------------------------------------
        // 3. NAVIGATION ON DOUBLE-CLICK
        // -------------------------------------------------------------------------
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                // Tree nodes now hold Coverage objects; unwrap to reach the ExpectEntry.
                val entry = (node.userObject as? Coverage)?.expect ?: return

                // We need the live PsiElement to navigate. But reading PSI outside a
                // read action throws an exception. ReadAction.nonBlocking runs the
                // lambda on a background thread with the read lock held, then hands
                // the result back to the EDT in finishOnUiThread().
                ReadAction.nonBlocking<NavigatablePsiElement?> {
                    // Dereference the smart pointer here, safely inside the read action.
                    // .element returns null if the declaration was deleted since we scanned.
                    entry.pointer.element as? NavigatablePsiElement
                }.finishOnUiThread(ModalityState.defaultModalityState()) { el ->
                    // navigate(true) opens the file in the editor and moves the caret
                    // to this declaration. The `true` means "request focus" (bring the
                    // editor to front).
                    el?.navigate(true)
                }.submit(AppExecutorUtil.getAppExecutorService())
            }
        })

        // -------------------------------------------------------------------------
        // 4. TOOLBAR — Refresh button
        // -------------------------------------------------------------------------
        // IntelliJ actions (AnAction) are the platform's way of representing any
        // user-triggered operation — menu items, toolbar buttons, keyboard shortcuts
        // all go through the same AnAction system.
        //
        // Here we create an anonymous AnAction inline. The three constructor arguments
        // are: display text, description (shown in the status bar on hover), and icon.
        // AllIcons is the platform's built-in icon library — no image files needed.
        //
        // The lambda captures `project`, `root`, and `model` from the enclosing scope,
        // so when the button is clicked it has everything it needs to re-run the scan.
        val refreshAction = object : AnAction("Refresh", "Re-scan the project for expect declarations", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                // Invalidate the cache first so getCoverage() re-scans the project
                // rather than returning the stale cached result.
                CoverageService.getInstance(project).invalidate()
                refresh(project, root, model)
            }
        }

        // DefaultActionGroup is a container for one or more AnActions. The toolbar
        // renders each action in the group as a button.
        val actionGroup = DefaultActionGroup(refreshAction)

        // ActionManager is the platform singleton that owns all registered actions and
        // creates toolbars. The string "ExpectActualToolbar" is an ID used internally
        // by the platform for things like shortcut customisation — it just needs to be
        // unique, it doesn't appear in any UI.
        //
        // The boolean `true` = horizontal toolbar (false = vertical).
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ExpectActualToolbar", actionGroup, true)

        // targetComponent tells the toolbar which component's DataContext to use when
        // building AnActionEvents. Without this the toolbar logs a warning and some
        // context-sensitive actions won't work correctly.
        toolbar.targetComponent = tree

        // -------------------------------------------------------------------------
        // 5. ASSEMBLE THE PANEL
        // -------------------------------------------------------------------------
        // BorderLayout divides a container into 5 zones: NORTH, SOUTH, EAST, WEST,
        // CENTER. CENTER expands to fill all remaining space — which is what we want
        // for the tree. NORTH stays at its natural height — which is what we want for
        // the toolbar strip.
        val panel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }

        // -------------------------------------------------------------------------
        // 6. KICK OFF THE INITIAL SCAN
        // -------------------------------------------------------------------------
        // WHY DumbService.runWhenSmart instead of just calling refresh() directly:
        //
        // On project open the IDE goes through several phases:
        //   1. Project model loaded → smart mode declared briefly
        //   2. VFS refresh runs → 100s of file roots are mounted into memory
        //   3. VFS refresh triggers re-indexing → dumb mode again
        //   4. Indexing completes → smart mode, this time for real
        //
        // If we call refresh() immediately, our ReadAction.nonBlocking().inSmartMode()
        // fires during window (1) — before the VFS has any files. FileTypeIndex returns
        // 0 Kotlin files and we display "No expect declarations found."
        //
        // DumbService.runWhenSmart defers execution until smart mode is stable. More
        // importantly, it re-runs the callback every time the project re-enters smart
        // mode — so it naturally catches window (4) even if (1) fires first.
        //
        // The Refresh button bypasses this and calls refresh() directly, which is fine
        // because by the time the user can click it the project is fully loaded.
        DumbService.getInstance(project).runWhenSmart { refresh(project, root, model) }

        // -------------------------------------------------------------------------
        // 7. ADD THE PANEL TO THE TOOL WINDOW
        // -------------------------------------------------------------------------
        // ContentManager manages the tab(s) inside the tool window strip.
        // We create a single unnamed tab (null title) containing our whole panel.
        val content = toolWindow.contentManager.factory
            .createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Runs the scanner on a background thread and updates the tree on the EDT.
     * Called both on initial open and when the Refresh button is clicked.
     *
     * BREAKDOWN OF THE READ ACTION CHAIN
     * -----------------------------------
     * ReadAction.nonBlocking { ... }
     *   — declares that the lambda needs the PSI read lock and returns a result.
     *   — returns a NonBlockingReadAction builder; nothing runs yet.
     *
     * .inSmartMode(project)
     *   — "don't start until indexing is finished". The platform builds indexes of
     *     all project files (classes, symbols, file types) on startup and after
     *     changes. FileTypeIndex and other APIs only work reliably after indexing.
     *     Without this, we'd scan during indexing and find 0 files every time.
     *
     * .expireWith(project)
     *   — if the project is closed while we're scanning, cancel the action silently.
     *     Without this we'd crash trying to use a disposed Project object.
     *
     * .finishOnUiThread(ModalityState.defaultModalityState()) { result -> ... }
     *   — once the background lambda completes, run THIS lambda on the EDT with the
     *     result. This is where we're allowed to touch Swing.
     *   — ModalityState.defaultModalityState() means "run even if a dialog is open",
     *     which is the correct default for non-blocking refreshes.
     *
     * .submit(AppExecutorUtil.getAppExecutorService())
     *   — actually schedules the work on the platform's shared thread pool. This is
     *     the call that starts everything running.
     */
    private fun refresh(project: Project, root: DefaultMutableTreeNode, model: DefaultTreeModel) {
        // Show a placeholder immediately on the EDT before the background scan starts.
        root.removeAllChildren()
        root.userObject = "Scanning…"
        model.reload() // reload() tells the JTree the model changed; triggers a repaint.

        // Ask the service for coverage data. On first call (or after invalidate()) the
        // service runs the full scanner; on subsequent calls it returns the cached list
        // instantly. Either way, this is PSI work and must stay inside a read action.
        ReadAction.nonBlocking<List<Coverage>> { CoverageService.getInstance(project).getCoverage() }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { coverageList ->
                // We're back on the EDT — safe to modify the Swing tree.
                root.removeAllChildren()
                root.userObject = if (coverageList.isEmpty()) "No expect declarations found" else "Expect declarations"
                // Each tree node holds a Coverage object. The renderer reads coverage.expect
                // for now; issue #4 will expand nodes with per-platform ✓/✗ children.
                coverageList.sortedBy { it.expect.fqName.asString() }.forEach { coverage ->
                    root.add(DefaultMutableTreeNode(coverage))
                }
                // Tell the JTree the model has changed so it repaints with the new nodes.
                model.reload()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
