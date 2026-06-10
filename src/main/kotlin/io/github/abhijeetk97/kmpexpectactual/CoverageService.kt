package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level cache for the expect/actual coverage data.
 *
 * WHY THIS EXISTS
 * ---------------
 * Computing coverage requires reading and walking every Kotlin file in the project
 * (via PSI). That's fast enough to run on a background thread, but it's still
 * non-trivial work — dozens of files, hundreds of declarations. Two features need
 * this data: the tool window (issue #2) and the MissingActualInspection (issue #5).
 * Running a full scan independently in each would be wasteful, especially when the
 * inspection runs on every keystroke.
 *
 * CoverageService solves this by acting as a shared, invalidatable cache:
 *   1. First caller computes and stores the result.
 *   2. Subsequent callers get the cached list instantly.
 *   3. When something changes (file edit, manual Refresh), `invalidate()` is called
 *      and the NEXT `getCoverage()` call re-computes from scratch.
 *
 * WHAT "PROJECT-LEVEL SERVICE" MEANS
 * ------------------------------------
 * IntelliJ plugins can register "services" — singletons managed by the platform that
 * are tied to a specific scope. There are three scopes:
 *
 *   Application  — one instance for the entire IDE process (across all open projects)
 *   Project      — one instance per open project  ← this is what we want
 *   Module       — one instance per module (rare)
 *
 * We use PROJECT scope because coverage data is per-project: opening two KMP projects
 * simultaneously should give each its own independent cache.
 *
 * HOW REGISTRATION WORKS (Light Services)
 * ----------------------------------------
 * Older IntelliJ plugin development required you to list every service in plugin.xml:
 *
 *   <projectService serviceImplementation="...CoverageService"/>
 *
 * Since IntelliJ Platform 2021.1, you can use the @Service annotation instead — the
 * platform finds and registers it automatically. This is called a "Light Service."
 * We target sinceBuild = 243 (2024.3), so annotation-only is fine. No plugin.xml
 * entry needed.
 *
 * THREADING MODEL
 * ---------------
 * The cache field is marked @Volatile. Here's why that matters:
 *
 * Modern CPUs have per-core caches (L1/L2). Without a visibility guarantee, a write
 * on thread A might sit in that core's cache and not be seen by thread B for an
 * indeterminate amount of time — even after the write "completes" from A's perspective.
 *
 * @Volatile tells the JVM to bypass the CPU cache for this field: every read goes
 * straight to main memory, and every write is immediately flushed there. This gives
 * us cross-thread visibility at low cost.
 *
 * There IS a subtle race condition: two threads can both observe `cache == null` and
 * both compute coverage simultaneously. This is intentional — the computation is
 * idempotent (same result either way) and making it fully thread-safe would require
 * a mutex that blocks the second thread while the first computes. The double-compute
 * race is far cheaper than that in practice.
 *
 * CALLER CONTRACT
 * ---------------
 * `getCoverage()` must be called inside a read action — it delegates to the scanner
 * which touches PSI. See ExpectActualScanner for details on read action requirements.
 */
@Service(Service.Level.PROJECT)
class CoverageService(private val project: Project) {

    // The cached result. null means "not yet computed" or "invalidated".
    // @Volatile ensures every thread sees the latest written value immediately.
    @Volatile private var cache: List<Coverage>? = null

    /**
     * Returns the coverage list, computing it on first call (or after invalidation).
     *
     * The `?: ...also { cache = it }` idiom:
     *   - `cache ?:` returns `cache` if non-null (the common/fast path)
     *   - `computeCoverage(...).also { cache = it }` runs only when cache is null:
     *     computes coverage, stores it in cache, then returns it. `also` is Kotlin's
     *     "do something with the value and return the same value" function.
     *
     * Must be called inside a read action.
     */
    fun getCoverage(): List<Coverage> =
        cache ?: ExpectActualScanner.computeCoverage(project).also { cache = it }

    /**
     * Drops the cached result so the next `getCoverage()` call re-computes.
     *
     * Called from two places:
     *   - The Refresh toolbar button (user explicitly requests a re-scan)
     *   - The PSI change listener (issue #6) when source files are edited
     */
    fun invalidate() {
        cache = null
    }

    companion object {

        /**
         * The standard IntelliJ pattern for accessing a project service.
         *
         * `project.getService(CoverageService::class.java)` is the platform API that
         * retrieves the singleton instance for this project. Wrapping it in a companion
         * object function gives callers a clean one-liner:
         *
         *   CoverageService.getInstance(project).getCoverage()
         *
         * instead of the verbose platform call every time.
         */
        fun getInstance(project: Project): CoverageService =
            project.getService(CoverageService::class.java)
    }
}
