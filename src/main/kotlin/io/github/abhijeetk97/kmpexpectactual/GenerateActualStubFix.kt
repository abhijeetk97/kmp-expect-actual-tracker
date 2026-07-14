package io.github.abhijeetk97.kmpexpectactual

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Quick fix on [MissingActualInspection]: generates an `actual` stub for one
 * missing platform (issue #16).
 *
 * WHERE THE STUB GOES
 * -------------------
 * The fix needs a target source root for the platform (e.g. `iosMain/kotlin`).
 * There is no direct API for "the iOS source root" that works across module
 * layouts, so we anchor on what the scanner already proved: any EXISTING
 * `actual` on that platform. Its source root is the target — preferring an
 * anchor in the same module as the expect when there are several.
 *
 * This means the fix is only offered usefully when the platform already has at
 * least one actual somewhere — which is guaranteed, because a platform only
 * appears in `missingPlatforms` if some other expect has an actual there
 * (knownPlatforms is the union of observed actuals).
 *
 * Inside the source root, the stub goes into the expect's package, in a file
 * with the same name as the expect's file (the common KMP layout: commonMain's
 * `Platform.kt` pairs with `Platform.kt` in each platform source set). If that
 * file already exists, the stub is appended to it.
 */
class GenerateActualStubFix(private val platform: String) : LocalQuickFix {

    override fun getName(): String = "Generate 'actual' stub for $platform"

    override fun getFamilyName(): String = "Generate 'actual' stub"

    // We orchestrate the write ourselves (WriteCommandAction below) because the
    // target-directory lookup before it only needs read access.
    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val declaration =
            PsiTreeUtil.getParentOfType(element, KtNamedDeclaration::class.java, false) ?: return

        val sourceRoot = findTargetSourceRoot(project, declaration)
        if (sourceRoot == null) {
            notify(
                project,
                "Could not locate a source root for $platform — write the first 'actual' for this platform manually.",
                NotificationType.WARNING,
            )
            return
        }

        val containingFile = declaration.containingFile as? KtFile ?: return
        val packageFq = containingFile.packageFqName
        val fileName = containingFile.name
        val stubText = ActualStubGenerator.generate(declaration)

        var fileToOpen: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, name, null, {
            val packagePath = packageFq.asString().replace('.', '/')
            val targetDir = VfsUtil.createDirectoryIfMissing(sourceRoot, packagePath) ?: return@runWriteCommandAction
            val psiDir = PsiManager.getInstance(project).findDirectory(targetDir) ?: return@runWriteCommandAction

            val existing = psiDir.findFile(fileName) as? KtFile
            if (existing != null) {
                // Append to the platform file that already exists — the least
                // surprising place for the new actual to land.
                val document = PsiDocumentManager.getInstance(project).getDocument(existing)
                    ?: return@runWriteCommandAction
                document.insertString(document.textLength, "\n$stubText\n")
                PsiDocumentManager.getInstance(project).commitDocument(document)
                fileToOpen = existing.virtualFile
            } else {
                val content = buildString {
                    if (!packageFq.isRoot) append("package ${packageFq.asString()}\n\n")
                    append(stubText).append("\n")
                }
                val newFile = PsiFileFactory.getInstance(project)
                    .createFileFromText(fileName, KotlinFileType.INSTANCE, content)
                val added = psiDir.add(newFile)
                fileToOpen = added.containingFile?.virtualFile
            }
        })

        fileToOpen?.let { vf ->
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
            notify(
                project,
                "Generated 'actual' stub for $platform in ${vf.name}",
                NotificationType.INFORMATION,
            )
        }
    }

    /**
     * The source root that hosts the platform's actuals, preferring one in the
     * same module as [declaration]. Must be called with read access (applyFix
     * runs on the EDT, which has it implicitly).
     */
    private fun findTargetSourceRoot(project: Project, declaration: KtNamedDeclaration): VirtualFile? {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val expectModule = fileIndex
            .getModuleForFile(declaration.containingFile.virtualFile ?: return null)
            ?.name?.let(ExpectActualScanner::moduleDisplayName)

        data class Candidate(val root: VirtualFile, val module: String?)

        val candidates = CoverageService.getInstance(project).getCoverage()
            .mapNotNull { it.actualsByPlatform[platform]?.element?.containingFile?.virtualFile }
            .mapNotNull { anchor ->
                fileIndex.getSourceRootForFile(anchor)?.let { root ->
                    Candidate(root, fileIndex.getModuleForFile(anchor)?.name?.let(ExpectActualScanner::moduleDisplayName))
                }
            }
            .distinctBy { it.root }

        return (candidates.firstOrNull { it.module != null && it.module == expectModule }
            ?: candidates.firstOrNull())?.root
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("KMP Expect/Actual Tracker")
            .createNotification(message, type)
            .notify(project)
    }
}
