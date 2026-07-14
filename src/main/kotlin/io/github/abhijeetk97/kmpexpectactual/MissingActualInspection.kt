package io.github.abhijeetk97.kmpexpectactual

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Highlights `expect` declarations that are missing an `actual` on one or more
 * platforms — right in the editor, without opening the tool window (issue #5).
 *
 * WHY AN INSPECTION AND NOT AN ANNOTATOR
 * --------------------------------------
 * Inspections can be toggled, severity-configured, run in batch mode
 * (Analyze → Inspect Code), and carry quick fixes. All of that comes free from
 * the platform; an Annotator would need each piece hand-built.
 *
 * DATA SOURCE
 * -----------
 * Reuses [CoverageService]'s cached scan — the same data the tool window shows,
 * so the two never disagree. The cache is invalidated on any Kotlin PSI change
 * by [CoverageInvalidationListener], and inspections re-run on the same events,
 * so a newly written `actual` clears the warning on the next highlighting pass.
 *
 * Inspection visitors run on a background thread inside a read action, so
 * calling the scanner from here is thread-safe. The first pass after an edit
 * pays the scan cost; subsequent declarations in the same pass hit the cache.
 */
class MissingActualInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // One coverage lookup per inspection pass, resolved lazily so files with
        // no expect declarations never trigger a scan at all.
        val coverageByFq by lazy {
            CoverageService.getInstance(holder.file.project).getCoverage()
                .associateBy { it.expect.fqName.asString() }
        }

        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is KtNamedDeclaration) return
                if (!element.hasModifier(KtTokens.EXPECT_KEYWORD)) return
                val fq = element.fqName?.asString() ?: return

                val coverage = coverageByFq[fq] ?: return
                if (coverage.knownPlatforms.isEmpty() || coverage.isComplete) return

                val missing = coverage.missingPlatforms.sorted()
                holder.registerProblem(
                    element.nameIdentifier ?: element,
                    "Missing 'actual' implementation on: ${missing.joinToString(", ")}",
                    *missing.map { GenerateActualStubFix(it) }.toTypedArray(),
                )
            }
        }
    }
}
