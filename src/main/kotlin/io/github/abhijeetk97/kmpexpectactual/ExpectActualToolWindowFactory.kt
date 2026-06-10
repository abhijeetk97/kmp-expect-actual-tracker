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

class ExpectActualToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = DefaultMutableTreeNode("Expect declarations")
        val model = DefaultTreeModel(root)
        val tree = Tree(model)

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
                        append("[${obj.kind}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append(obj.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("  ${obj.fqName.parent().asString()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    else -> append(obj.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val entry = node.userObject as? ExpectEntry ?: return
                ReadAction.nonBlocking<NavigatablePsiElement?> {
                    entry.pointer.element as? NavigatablePsiElement
                }.finishOnUiThread(ModalityState.defaultModalityState()) { el ->
                    el?.navigate(true)
                }.submit(AppExecutorUtil.getAppExecutorService())
            }
        })

        refresh(project, root, model)

        val content = toolWindow.contentManager.factory
            .createContent(JBScrollPane(tree), null, false)
        toolWindow.contentManager.addContent(content)
    }

    private fun refresh(project: Project, root: DefaultMutableTreeNode, model: DefaultTreeModel) {
        root.removeAllChildren()
        root.userObject = "Scanning…"
        model.reload()

        ReadAction.nonBlocking<List<ExpectEntry>> { ExpectActualScanner.findExpects(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { entries ->
                root.removeAllChildren()
                root.userObject = if (entries.isEmpty()) "No expect declarations found" else "Expect declarations"
                entries.sortedBy { it.fqName.asString() }.forEach { entry ->
                    root.add(DefaultMutableTreeNode(entry))
                }
                model.reload()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
