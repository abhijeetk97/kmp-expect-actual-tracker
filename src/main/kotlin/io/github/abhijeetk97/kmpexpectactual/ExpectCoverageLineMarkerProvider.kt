package io.github.abhijeetk97.kmpexpectactual

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Coverage-aware gutter icon on `expect` declarations: green when every known
 * platform has an actual, red when at least one is missing. Clicking navigates
 * to the actual implementations.
 *
 * HOW THIS DIFFERS FROM THE KOTLIN PLUGIN'S OWN EXPECT/ACTUAL MARKERS
 * -------------------------------------------------------------------
 * The bundled markers only say "actuals exist, here they are". Ours answers the
 * coverage question — is anything MISSING — which the compiler only checks for
 * the target you're currently building.
 *
 * PERFORMANCE CONTRACT
 * --------------------
 * Line marker providers run for every element of every visible file on each
 * highlighting pass. Two rules keep this cheap:
 *   1. Anchor on the LEAF identifier token and bail out fast for everything
 *      else (the platform's documented best practice — markers on composite
 *      elements cause spurious repaints).
 *   2. Only expect declarations ever reach CoverageService, and the service
 *      caches the project scan between edits.
 */
class ExpectCoverageLineMarkerProvider : RelatedItemLineMarkerProvider() {

    override fun collectNavigationMarkers(
        element: PsiElement,
        result: MutableCollection<in RelatedItemLineMarkerInfo<*>>,
    ) {
        // Fast bail-outs first: we only mark the identifier leaf of a named
        // declaration that carries the `expect` modifier.
        if (element !is LeafPsiElement) return
        if (element.elementType != KtTokens.IDENTIFIER) return
        val declaration = element.parent as? KtNamedDeclaration ?: return
        if (!declaration.hasModifier(KtTokens.EXPECT_KEYWORD)) return
        val fq = declaration.fqName?.asString() ?: return

        val coverage = CoverageService.getInstance(element.project).getCoverage()
            .firstOrNull { it.expect.fqName.asString() == fq }
            ?: return
        if (coverage.knownPlatforms.isEmpty()) return

        val icon = if (coverage.isComplete) {
            AllIcons.RunConfigurations.TestPassed
        } else {
            AllIcons.RunConfigurations.TestFailed
        }
        val tooltip = if (coverage.isComplete) {
            "Actual implementations on all ${coverage.knownPlatforms.size} platforms"
        } else {
            "Covered on ${coverage.coverageSummary} — missing: ${coverage.missingPlatforms.sorted().joinToString(", ")}"
        }

        // Dereferencing the pointers is safe here: line markers are collected
        // inside a read action on a background highlighting thread.
        val targets = coverage.actualsByPlatform.values.mapNotNull { it.element }

        result.add(
            NavigationGutterIconBuilder.create(icon)
                .setTargets(targets)
                .setTooltipText(tooltip)
                .setPopupTitle("Actual Implementations")
                .createLineMarkerInfo(element),
        )
    }
}
