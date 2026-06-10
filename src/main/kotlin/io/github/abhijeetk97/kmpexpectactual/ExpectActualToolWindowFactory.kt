package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.NavigatablePsiElement
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
 *   1. On the EDT, set up the UI (tree, renderer, click listener).
 *   2. Kick off a non-blocking read action on a background thread to run the scanner.
 *   3. When the scanner finishes, the platform marshals the result back to the EDT
 *      via finishOnUiThread(), where we safely update the Swing tree.
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
                    is ExpectEntry -> {
                        // SimpleTextAttributes controls colour and style (bold, italic, strikethrough).
                        // GRAYED = dim grey, REGULAR = normal foreground colour.
                        append("[${obj.kind}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append(obj.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        // The package name in small grey after the declaration name.
                        append("  ${obj.fqName.parent().asString()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
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
                val entry = node.userObject as? ExpectEntry ?: return

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
        // 4. KICK OFF THE INITIAL SCAN
        // -------------------------------------------------------------------------
        refresh(project, root, model)

        // -------------------------------------------------------------------------
        // 5. ADD THE PANEL TO THE TOOL WINDOW
        // -------------------------------------------------------------------------
        // ContentManager manages the tab(s) inside the tool window strip.
        // We wrap our tree in a scroll pane and create a single unnamed tab (null title).
        val content = toolWindow.contentManager.factory
            .createContent(JBScrollPane(tree), null, false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Runs the scanner on a background thread and updates the tree on the EDT.
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

        ReadAction.nonBlocking<List<ExpectEntry>> { ExpectActualScanner.findExpects(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { entries ->
                // We're back on the EDT — safe to modify the Swing tree.
                root.removeAllChildren()
                root.userObject = if (entries.isEmpty()) "No expect declarations found" else "Expect declarations"
                entries.sortedBy { it.fqName.asString() }.forEach { entry ->
                    root.add(DefaultMutableTreeNode(entry))
                }
                // Tell the JTree the model has changed so it repaints with the new nodes.
                model.reload()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
