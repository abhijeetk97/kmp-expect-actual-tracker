package io.github.abhijeetk97.kmpexpectactual

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import java.awt.Color
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Paints one row of the coverage tree.
 *
 * customizeCellRenderer is called for EVERY visible row on EVERY repaint.
 * Keep it fast: no PSI access, no IO, just append() calls.
 *
 * Row types (dispatched on the node's userObject):
 *   Coverage     → parent row: "[kind] name  package  [2/3 platforms]"
 *   PlatformNode → child row:  "Android  ✓"  or  "JVM  ✗ missing"
 *   anything else → root / placeholder strings (empty-state messages)
 */
class CoverageTreeCellRenderer : ColoredTreeCellRenderer() {

    // JBColor(lightColor, darkColor) picks the right colour for the active theme.
    private val coveredAttrs = SimpleTextAttributes(
        SimpleTextAttributes.STYLE_PLAIN,
        JBColor(Color(0, 128, 0), Color(98, 150, 85)),
    )

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

            // ── PARENT ROW: one expect declaration ──────────────────────
            is Coverage -> {
                val e = obj.expect
                append("[${e.kind}] ", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                // Name turns red when any platform is missing.
                val nameAttrs = if (obj.isComplete) {
                    SimpleTextAttributes.REGULAR_ATTRIBUTES
                } else {
                    SimpleTextAttributes.ERROR_ATTRIBUTES
                }
                append(e.displayName, nameAttrs)

                // Package path in small grey
                append("  ${e.fqName.parent().asString()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

                // Coverage badge — only shown when platforms are known
                if (obj.knownPlatforms.isNotEmpty()) {
                    append("  [${obj.coverageSummary}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            // ── CHILD ROW: one platform under an expect ──────────────────
            is PlatformNode -> {
                append(obj.platform, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (obj.isCovered) {
                    append("  ✓", coveredAttrs)
                } else {
                    append("  ✗ missing", SimpleTextAttributes.ERROR_ATTRIBUTES)
                }
            }

            // ── ROOT / PLACEHOLDER strings (including empty-state messages) ──
            else -> append(obj.toString(), SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }
}
