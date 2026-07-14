package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → KMP Expect/Actual Tracker.
 *
 * Edits [ExpectActualSettings] — the shared, per-project scanner configuration.
 * Applying invalidates the coverage cache so the next scan (tool window
 * refresh, inspection pass) immediately reflects the new configuration.
 */
class ExpectActualSettingsConfigurable(private val project: Project) : Configurable {

    private var panel: JPanel? = null
    private val excludedPatternsField = JBTextField()
    private val debounceField = JBTextField()
    private val srcMainAsAndroidCheckbox =
        JBCheckBox("Treat src/main source roots as the Android platform (AGP-style modules)")

    override fun getDisplayName(): String = "KMP Expect/Actual Tracker"

    override fun createComponent(): JComponent {
        val hint = JBLabel(
            "Files whose path contains any of these substrings are skipped, in addition to the built-in /generated/ rule.",
        ).apply {
            font = UIUtil.getFont(UIUtil.FontSize.SMALL, font)
            foreground = UIUtil.getContextHelpForeground()
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Excluded path patterns (comma-separated):", excludedPatternsField, 1, false)
            .addComponentToRightColumn(hint)
            .addLabeledComponent("Auto-refresh debounce (ms):", debounceField, 1, false)
            .addComponent(srcMainAsAndroidCheckbox)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { panel = it }
    }

    override fun isModified(): Boolean {
        val state = ExpectActualSettings.getInstance(project).state
        return excludedPatternsField.text != state.excludedPathPatterns.joinToString(", ") ||
            debounceField.text != state.autoRefreshDelayMs.toString() ||
            srcMainAsAndroidCheckbox.isSelected != state.treatSrcMainAsAndroid
    }

    override fun apply() {
        val state = ExpectActualSettings.getInstance(project).state
        state.excludedPathPatterns = excludedPatternsField.text
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        state.autoRefreshDelayMs = debounceField.text.trim().toIntOrNull()
            ?.coerceIn(0, 60_000)
            ?: ExpectActualSettings.DEFAULT_AUTO_REFRESH_DELAY_MS
        state.treatSrcMainAsAndroid = srcMainAsAndroidCheckbox.isSelected

        // Scanner behaviour changed — force the next getCoverage() to re-scan.
        CoverageService.getInstance(project).invalidate()
    }

    override fun reset() {
        val state = ExpectActualSettings.getInstance(project).state
        excludedPatternsField.text = state.excludedPathPatterns.joinToString(", ")
        debounceField.text = state.autoRefreshDelayMs.toString()
        srcMainAsAndroidCheckbox.isSelected = state.treatSrcMainAsAndroid
    }

    override fun disposeUIResources() {
        panel = null
    }
}
