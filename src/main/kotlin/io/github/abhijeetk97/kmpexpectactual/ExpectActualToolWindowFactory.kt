package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Entry point for the "Expect/Actual" tool window.
 *
 * HOW INTELLIJ PLUGINS WORK (the mental model)
 * ---------------------------------------------
 * A plugin is NOT an app with a main(). It's a bundle of "extensions" — classes
 * that plug into pre-defined platform hooks called Extension Points.
 *
 * You declare what you're providing in plugin.xml:
 *
 *   <toolWindow
 *       id="Expect/Actual"
 *       factoryClass="...ExpectActualToolWindowFactory" />
 *
 * The platform reads that, and when the user opens (or the IDE loads) the tool window,
 * it instantiates THIS class and calls createToolWindowContent(). You never new() it
 * yourself. That's the inversion of control that makes plugin dev feel different.
 *
 * STATELESS BY DESIGN
 * -------------------
 * ToolWindowFactory is a singleton shared across all open projects. Any instance field
 * here would be shared (and corrupted) between two simultaneously-open projects. So
 * this class holds no state: all per-project state and UI lives in [ExpectActualPanel],
 * which is instantiated fresh for each project window.
 */
class ExpectActualToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ExpectActualPanel(project)

        // Create the Content first so it can serve as the parent Disposable for the
        // auto-refresh machinery. When the tool window content is removed (project
        // close, plugin unload), the platform disposes the Content, which in turn
        // disposes the Alarm and unregisters the PSI listener — no leaks.
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)

        panel.installAutoRefresh(content)
    }
}
