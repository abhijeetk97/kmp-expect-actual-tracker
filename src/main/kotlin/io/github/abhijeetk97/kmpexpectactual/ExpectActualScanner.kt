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

/**
 * Scans all Kotlin files in the project and returns every `expect` declaration.
 *
 * IMPORTANT THREADING CONTRACT
 * ----------------------------
 * Every method in this object MUST be called inside a read action. PSI (the parsed
 * code model) is protected by a read/write lock. Touching it without holding the
 * read lock throws "Read access is allowed from inside read-action only".
 *
 * On the EDT (UI thread) you implicitly have read access. On a background thread
 * (which is where we run this, to avoid freezing the UI) you must wrap the call in
 * ReadAction.nonBlocking { ... }. That's done in the tool window, not here.
 *
 * PSI CRASH COURSE
 * ----------------
 * PSI = Program Structure Interface. It's IntelliJ's parsed, semantic representation
 * of source code — basically a tree of typed nodes. Every source file is a PsiFile.
 * For Kotlin files specifically, it's a KtFile. Inside it you find KtDeclaration
 * nodes: KtClass, KtFunction, KtProperty, etc.
 *
 * The `expect` and `actual` keywords are modifiers on those declarations, the same way
 * `private` or `suspend` are. So to find all expects, we walk every declaration in
 * every Kotlin file and check if it carries the EXPECT_KEYWORD modifier.
 */
object ExpectActualScanner {

    /**
     * Entry point. Returns all `expect` declarations found across the whole project.
     *
     * Steps:
     *  1. Use the platform's file index to enumerate all Kotlin files — we never
     *     grep the filesystem directly; the index is always faster and more correct.
     *  2. For each file, convert the VirtualFile handle to a KtFile (the PSI tree).
     *  3. Walk its top-level declarations, then recursively walk into classes.
     *  4. For each declaration that has the `expect` modifier, build an ExpectEntry.
     */
    fun findExpects(project: Project): List<ExpectEntry> {
        val pointerManager = SmartPointerManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val result = mutableListOf<ExpectEntry>()

        // FileTypeIndex is the platform's index of every file by type.
        // GlobalSearchScope.projectScope limits results to THIS project's source,
        // excluding libraries and the JDK — we only care about our own declares.
        val ktFiles = FileTypeIndex.getFiles(
            KotlinFileType.INSTANCE,
            GlobalSearchScope.projectScope(project),
        )

        for (vf in ktFiles) {
            // VirtualFile is the platform's filesystem abstraction. It doesn't give us
            // the parsed tree; we need PsiManager to convert it to a KtFile (the PSI).
            // The `as? KtFile` cast can return null if the file isn't actually Kotlin
            // (shouldn't happen with KotlinFileType, but defensive is good).
            val ktFile = psiManager.findFile(vf) as? KtFile ?: continue
            collectExpects(ktFile.declarations, pointerManager, result)
        }
        return result
    }

    /**
     * Recursively walks a list of declarations and appends every `expect` to [out].
     *
     * We recurse into KtClassOrObject because:
     *   - An `expect class Foo` can itself contain `expect fun bar()` members.
     *   - Nested classes can appear at any depth.
     * We do NOT recurse into functions — Kotlin doesn't allow expect declarations
     * inside function bodies.
     */
    private fun collectExpects(
        declarations: List<KtDeclaration>,
        pointerManager: SmartPointerManager,
        out: MutableList<ExpectEntry>,
    ) {
        for (decl in declarations) {
            // hasModifier checks the declaration's modifier list for a specific keyword token.
            // KtTokens.EXPECT_KEYWORD is the token that represents the `expect` keyword.
            // This is the same modifier system used for `private`, `suspend`, `inline`, etc.
            if (decl.hasModifier(KtTokens.EXPECT_KEYWORD)) {
                // KtNamedDeclaration is the subset of declarations that have a name —
                // essentially everything except anonymous objects. The `as?` cast filters
                // out any unnamed declarations (extremely rare, but safe to skip).
                val named = decl as? KtNamedDeclaration ?: continue

                // fqName gives us the fully-qualified name, e.g. "com.example.Platform.name".
                // It can be null for declarations that don't have a stable FQ name (e.g.
                // local declarations), which we skip.
                val fq = named.fqName ?: continue

                out += ExpectEntry(
                    fqName = fq,
                    displayName = named.name.orEmpty() + kindSuffix(decl),
                    kind = kindOf(decl),
                    // SmartPointerManager.createSmartPsiElementPointer wraps the live PSI
                    // element in a stable handle. See ExpectEntry for why this matters.
                    pointer = pointerManager.createSmartPsiElementPointer(named),
                )
            }

            // Always recurse into classes regardless of whether they're `expect` themselves,
            // because a non-expect class can contain expect member functions or properties.
            if (decl is KtClassOrObject) {
                collectExpects(decl.declarations, pointerManager, out)
            }
        }
    }

    private fun kindOf(d: KtDeclaration) = when (d) {
        is KtClassOrObject -> "class"
        is KtNamedFunction -> "function"
        is KtProperty      -> "property"
        else               -> "declaration"
    }

    // Appends "()" to function names so "platformName()" is immediately recognisable
    // as a function rather than a property in the tree.
    private fun kindSuffix(d: KtDeclaration) = if (d is KtNamedFunction) "()" else ""
}
