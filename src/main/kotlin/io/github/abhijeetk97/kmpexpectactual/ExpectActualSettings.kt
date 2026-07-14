package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * User-configurable behaviour of the tracker, exposed in
 * Settings → Tools → KMP Expect/Actual Tracker and persisted per project.
 *
 * PERSISTENCE MODEL
 * -----------------
 * PersistentStateComponent is the platform's serialization hook: the IDE calls
 * [getState] on save and [loadState] on project open, (de)serializing the [State]
 * bean to `.idea/kmpExpectActualTracker.xml`. Only public var properties with
 * default-constructible types are serialized, which is why State is a plain bean
 * rather than a data class with constructor parameters.
 *
 * The file lives in `.idea/` (not workspace.xml) so teams can commit shared
 * scanner configuration — e.g. extra generated-code exclusions — to VCS.
 */
@Service(Service.Level.PROJECT)
@State(name = "KmpExpectActualSettings", storages = [Storage("kmpExpectActualTracker.xml")])
class ExpectActualSettings : PersistentStateComponent<ExpectActualSettings.State> {

    class State {
        /**
         * Path substrings that exclude a file from scanning, in addition to the
         * built-in `/generated/` rule. Matched case-insensitively against the
         * full file path.
         */
        var excludedPathPatterns: MutableList<String> = mutableListOf()

        /** Debounce window for auto-refresh after a PSI edit, in milliseconds. */
        var autoRefreshDelayMs: Int = DEFAULT_AUTO_REFRESH_DELAY_MS

        /**
         * Whether `/src/main/` source roots count as the Android platform.
         * True matches AGP-style Android library modules; turn it off in
         * projects where `src/main` is plain JVM code.
         */
        var treatSrcMainAsAndroid: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        const val DEFAULT_AUTO_REFRESH_DELAY_MS = 500

        fun getInstance(project: Project): ExpectActualSettings =
            project.getService(ExpectActualSettings::class.java)
    }
}
