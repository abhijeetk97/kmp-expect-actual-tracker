package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Segment
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.name.FqName

/**
 * Shared builders for the pure unit tests (coverage model, stats, reports).
 *
 * The model classes carry a SmartPsiElementPointer so the UI can navigate, but
 * none of the derived logic under test ever dereferences it — [fakePointer]
 * satisfies the constructor and fails loudly if that assumption breaks.
 */
internal fun fakePointer(): SmartPsiElementPointer<PsiElement> =
    object : SmartPsiElementPointer<PsiElement> {
        override fun getElement(): PsiElement? = null
        override fun getContainingFile(): PsiFile? = null
        override fun getProject(): Project =
            throw UnsupportedOperationException("Pure unit tests must not touch the pointer")
        override fun getVirtualFile(): VirtualFile? = null
        override fun getRange(): Segment? = null
        override fun getPsiRange(): Segment? = null
    }

internal fun expectEntry(
    fqName: String,
    displayName: String = fqName.substringAfterLast('.'),
    kind: String = "function",
    module: String? = null,
): ExpectEntry = ExpectEntry(
    fqName = FqName(fqName),
    displayName = displayName,
    kind = kind,
    pointer = fakePointer(),
    module = module,
)

internal fun coverage(
    fqName: String,
    coveredPlatforms: Set<String>,
    knownPlatforms: Set<String>,
    kind: String = "function",
    module: String? = null,
): Coverage = Coverage(
    expect = expectEntry(fqName, kind = kind, module = module),
    actualsByPlatform = coveredPlatforms.associateWith { fakePointer() },
    knownPlatforms = knownPlatforms,
)
