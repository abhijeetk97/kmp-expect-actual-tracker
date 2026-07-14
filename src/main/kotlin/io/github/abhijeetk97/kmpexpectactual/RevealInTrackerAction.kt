package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Editor context-menu action: with the caret on an `expect` declaration, opens
 * the Expect/Actual tool window and selects that declaration in the tree.
 *
 * Registered in plugin.xml under EditorPopupMenu; hidden entirely unless the
 * caret is actually on an expect declaration, so it doesn't clutter the menu
 * in non-KMP code.
 */
class RevealInTrackerAction : AnAction() {

    // update() reads PSI, so run it on a background thread — the platform
    // snapshots the presentation back to the EDT for us.
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = findExpectAtCaret(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val fqName = findExpectAtCaret(e)?.fqName?.asString() ?: return

        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Expect/Actual") ?: return
        toolWindow.activate({
            val panel = toolWindow.contentManager.contents.firstOrNull()
                ?.component as? ExpectActualPanel
            panel?.selectExpect(fqName)
        }, true)
    }

    private fun findExpectAtCaret(e: AnActionEvent): KtNamedDeclaration? {
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file: PsiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val elementAtCaret = file.findElementAt(editor.caretModel.offset) ?: return null
        // Walk up from the caret to the nearest named declaration and require
        // the expect modifier on it (not on some enclosing class).
        val declaration = PsiTreeUtil
            .getParentOfType(elementAtCaret, KtNamedDeclaration::class.java, false)
            ?: return null
        return declaration.takeIf { it.hasModifier(KtTokens.EXPECT_KEYWORD) }
    }
}
