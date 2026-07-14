package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/** How the coverage tree arranges its expect rows. */
enum class GroupMode(val displayName: String) {
    FLAT("No grouping"),
    PACKAGE("Group by package"),
    MODULE("Group by module"),
    MISSING_PLATFORM("Group by missing platform"),
}

/**
 * Remembers the tool window's view configuration across IDE restarts:
 * the incomplete-only toggle, the group-by mode, and the platform filter.
 *
 * Stored in workspace.xml (per-developer, not committed to VCS) because view
 * preferences are personal — unlike [ExpectActualSettings], which holds shared
 * scanner configuration.
 */
@Service(Service.Level.PROJECT)
@State(name = "KmpExpectActualUiState", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ExpectActualUiState : PersistentStateComponent<ExpectActualUiState.State> {

    class State {
        var showIncompleteOnly: Boolean = false

        // Persisted as the enum name so an unknown value (e.g. after a plugin
        // downgrade) degrades to FLAT instead of failing deserialization.
        var groupMode: String = GroupMode.FLAT.name

        /** Platform label the tree is scoped to; empty = all platforms. */
        var platformFilter: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var showIncompleteOnly: Boolean
        get() = state.showIncompleteOnly
        set(value) {
            state.showIncompleteOnly = value
        }

    var groupMode: GroupMode
        get() = GroupMode.entries.firstOrNull { it.name == state.groupMode } ?: GroupMode.FLAT
        set(value) {
            state.groupMode = value.name
        }

    var platformFilter: String
        get() = state.platformFilter
        set(value) {
            state.platformFilter = value
        }

    companion object {
        fun getInstance(project: Project): ExpectActualUiState =
            project.getService(ExpectActualUiState::class.java)
    }
}
