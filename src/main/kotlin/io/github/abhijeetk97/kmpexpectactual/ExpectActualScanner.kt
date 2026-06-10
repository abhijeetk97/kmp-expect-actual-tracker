package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

object ExpectActualScanner {

    // MUST be called inside a read action.
    fun findExpects(project: Project): List<ExpectEntry> {
        val pointerManager = SmartPointerManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val result = mutableListOf<ExpectEntry>()

        val ktFiles = FileTypeIndex.getFiles(
            KotlinFileType.INSTANCE,
            GlobalSearchScope.projectScope(project),
        )

        for (vf in ktFiles) {
            val ktFile = psiManager.findFile(vf) as? KtFile ?: continue
            collectExpects(ktFile.declarations, pointerManager, result)
        }
        return result
    }

    private fun collectExpects(
        declarations: List<KtDeclaration>,
        pointerManager: SmartPointerManager,
        out: MutableList<ExpectEntry>,
    ) {
        for (decl in declarations) {
            if (decl.hasModifier(KtTokens.EXPECT_KEYWORD)) {
                val named = decl as? KtNamedDeclaration ?: continue
                val fq = named.fqName ?: continue
                out += ExpectEntry(
                    fqName = fq,
                    displayName = named.name.orEmpty() + kindSuffix(decl),
                    kind = kindOf(decl),
                    pointer = pointerManager.createSmartPsiElementPointer(named),
                )
            }
            // Recurse: an expect class can contain expect members; nested classes too.
            if (decl is KtClassOrObject) {
                collectExpects(decl.declarations, pointerManager, out)
            }
        }
    }

    private fun kindOf(d: KtDeclaration) = when (d) {
        is KtClassOrObject -> "class"
        is KtNamedFunction -> "function"
        is KtProperty -> "property"
        else -> "declaration"
    }

    private fun kindSuffix(d: KtDeclaration) = if (d is KtNamedFunction) "()" else ""
}
