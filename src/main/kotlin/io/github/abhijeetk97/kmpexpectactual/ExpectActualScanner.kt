package io.github.abhijeetk97.kmpexpectactual

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Scans all Kotlin files in the project and returns every `expect` declaration, and now
 * also all `actual` declarations matched against their expects, producing a [Coverage] list.
 *
 * IMPORTANT THREADING CONTRACT
 * ----------------------------
 * Every public method in this object MUST be called inside a read action. PSI (the parsed
 * code model) is protected by a read/write lock. Touching it without holding the
 * read lock throws "Read access is allowed from inside read-action only".
 *
 * On the EDT (UI thread) you implicitly have read access. On a background thread
 * (which is where we run this, to avoid freezing the UI) you must wrap the call in
 * ReadAction.nonBlocking { ... }. That's done in the tool window, not here.
 *
 * PSI CRASH COURSE
 * ----------------
 * PSI = Program Structure Interface. It's IntelliJ's parsed, semantic representation
 * of source code — basically a tree of typed nodes. Every source file is a PsiFile.
 * For Kotlin files specifically, it's a KtFile. Inside it you find KtDeclaration
 * nodes: KtClass, KtFunction, KtProperty, etc.
 *
 * The `expect` and `actual` keywords are modifiers on those declarations, the same way
 * `private` or `suspend` are. So to find all expects, we walk every declaration in
 * every Kotlin file and check if it carries the EXPECT_KEYWORD modifier.
 *
 * FQ-NAME MATCHING STRATEGY (v1)
 * --------------------------------
 * We match an `expect` to its `actual`s by fully-qualified name (FqName) — e.g.
 * "com.example.Platform.name". This is simple, requires no semantic analysis, and
 * works identically in both K1 and K2 mode.
 *
 * Known limitation: if two `expect` functions share the same FQ name but have different
 * signatures (overloads), they collide in our map and one will shadow the other. This
 * is rare in real KMP code (overloaded expects are uncommon) but worth noting. The
 * Kotlin Analysis API (stretch goal, issue #14) would resolve this precisely.
 */
/**
 * Describes what kind of project the scanner sees when it inspects the file index.
 * Used by the tool window to choose the right empty/error state message.
 *
 * The three values form a decision tree:
 *
 *   projectScope has no .kt files?
 *     └─ YES → GRADLE_SYNC_REQUIRED  (only .kts scripts visible; KMP modules not imported)
 *     └─ NO →
 *          projectScope has a file under /commonMain/?
 *            └─ NO  → NOT_KMP   (plain Kotlin project, or KMP without commonMain)
 *            └─ YES → KMP       (proceed to scan for expects)
 */
enum class ProjectState {
    /** Only .kts build scripts are in the project scope — Gradle sync hasn't been run yet. */
    GRADLE_SYNC_REQUIRED,

    /** .kt source files are visible but none live under a commonMain source set. */
    NOT_KMP,

    /** At least one file under commonMain is present — this is a KMP project. */
    KMP,
}

object ExpectActualScanner {

    private val LOG = Logger.getInstance(ExpectActualScanner::class.java)

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Inspects the project's file index to determine what kind of project this is.
     *
     * Why three states instead of a simple boolean?
     * - GRADLE_SYNC_REQUIRED and NOT_KMP both produce an empty coverage list, but they
     *   need completely different messages in the UI. Bundling them into "isEmpty" would
     *   lose that distinction.
     *
     * The `/commonMain/` path check is reliable because:
     * - The KMP Gradle plugin always creates a source set named exactly `commonMain`.
     * - IntelliJ registers the source directory with that name in the file path.
     * - It's a directory name check, not a substring match on package names, so false
     *   positives are extremely unlikely.
     *
     * Must be called inside a read action.
     */
    fun detectProjectState(project: Project): ProjectState {
        val ktFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.projectScope(project))
        return when {
            // No .kt files at all — only build scripts are registered; need Gradle sync.
            ktFiles.none { it.extension == "kt" }          -> ProjectState.GRADLE_SYNC_REQUIRED
            // .kt files present but none under commonMain — not a KMP project.
            ktFiles.none { "/commonMain/" in it.path }     -> ProjectState.NOT_KMP
            // commonMain source set found — this is a KMP project.
            else                                            -> ProjectState.KMP
        }
    }

    /**
     * The main entry point for the coverage model. Returns one [Coverage] record per
     * `expect` declaration found in the project, with actuals matched by FQ name.
     *
     * Must be called inside a read action.
     *
     * Algorithm:
     *  1. Scan for all `expect` declarations → List<ExpectEntry>
     *  2. Scan for all `actual` declarations in one pass → grouped by FqName and platform
     *  3. knownPlatforms = every distinct platform label seen across all actuals
     *  4. For each expect, join with the actuals map by FqName → Coverage
     */
    fun computeCoverage(project: Project): List<Coverage> {
        val expects = findExpects(project)
        if (expects.isEmpty()) return emptyList()

        // One pass over all Kotlin files for actuals — not a separate full scan.
        val actualsMap = findActualsGrouped(project)

        // The union of every platform we saw an `actual` for, anywhere in the project.
        // Used as the baseline "what should be covered" set. See Coverage.kt for the
        // known limitation of this approach.
        val knownPlatforms: Set<String> = actualsMap.values
            .flatMap { it.keys }
            .toSet()

        return expects.map { expectEntry ->
            Coverage(
                expect = expectEntry,
                // If no actuals exist for this FQ name at all, default to empty map
                // (all platforms will be "missing").
                actualsByPlatform = actualsMap[expectEntry.fqName] ?: emptyMap(),
                knownPlatforms = knownPlatforms,
            )
        }
    }

    /**
     * Returns all `expect` declarations found across the whole project.
     *
     * Steps:
     *  1. Use the platform's file index to enumerate all Kotlin files — we never
     *     grep the filesystem directly; the index is always faster and more correct.
     *  2. For each file, convert the VirtualFile handle to a KtFile (the PSI tree).
     *  3. Walk its top-level declarations, then recursively walk into classes.
     *  4. For each declaration that has the `expect` modifier, build an ExpectEntry.
     */
    fun findExpects(project: Project): List<ExpectEntry> {
        val pointerManager = SmartPointerManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val result = mutableListOf<ExpectEntry>()

        // FileTypeIndex is the platform's index of every file by type.
        // GlobalSearchScope.projectScope limits results to THIS project's source,
        // excluding libraries and the JDK — we only care about our own declarations.
        val ktFiles = FileTypeIndex.getFiles(
            KotlinFileType.INSTANCE,
            GlobalSearchScope.projectScope(project),
        )

        LOG.info("KMP Scanner: scanning ${ktFiles.size} Kotlin source files")

        for (vf in ktFiles) {
            // Skip files under any directory named "generated" — Compose Multiplatform and
            // other code-gen tools produce `expect` declarations there (e.g. Res accessors)
            // that are implementation details, not user-authored declarations.
            if (vf.path.contains("/generated/")) continue

            // VirtualFile is the platform's filesystem abstraction. It doesn't give us
            // the parsed tree; we need PsiManager to convert it to a KtFile (the PSI).
            // The `as? KtFile` cast can return null if the file isn't actually Kotlin
            // (shouldn't happen with KotlinFileType, but defensive is good).
            val ktFile = psiManager.findFile(vf) as? KtFile ?: continue
            collectExpects(ktFile.declarations, pointerManager, result)
        }

        LOG.info("KMP Scanner: found ${result.size} expect declarations")
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACTUAL SCANNING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scans the whole project for `actual` declarations and returns them grouped by
     * their FQ name, then by their platform label.
     *
     * Return type: Map<FqName, Map<platformLabel, SmartPointer>>
     *
     * Example:
     *   "com.example.platformName" → {
     *       "Android" → pointer to androidMain/Platform.kt
     *       "iOS"     → pointer to iosMain/Platform.kt
     *   }
     *
     * This structure is exactly what computeCoverage() needs: given an expect's FqName,
     * one lookup tells us which platforms already have an actual.
     */
    private fun findActualsGrouped(
        project: Project,
    ): Map<FqName, Map<String, SmartPsiElementPointer<*>>> {
        val pointerManager = SmartPointerManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        // Outer key: FqName of the actual (matches the expect's FqName)
        // Inner key: platform label derived from the module the file lives in
        val result = mutableMapOf<FqName, MutableMap<String, SmartPsiElementPointer<*>>>()

        val ktFiles = FileTypeIndex.getFiles(
            KotlinFileType.INSTANCE,
            GlobalSearchScope.projectScope(project),
        )

        for (vf in ktFiles) {
            if (vf.path.contains("/generated/")) continue
            val ktFile = psiManager.findFile(vf) as? KtFile ?: continue
            collectActuals(ktFile.declarations, pointerManager, result)
        }
        return result
    }

    /**
     * Recursively walks declarations in one file and records every `actual`.
     *
     * For each `actual` found:
     *  - We resolve the module it belongs to via ModuleUtilCore — the module is the
     *    Gradle source set compiled as a separate unit (e.g. "shared.androidMain").
     *  - We pass the module to platformOf() to get a human-readable label ("Android").
     *  - We store a SmartPsiElementPointer (stable handle) rather than the raw element.
     *
     * The [out] map is mutated in-place: getOrPut creates a new inner map on first
     * encounter of a given FqName.
     */
    private fun collectActuals(
        declarations: List<KtDeclaration>,
        pointerManager: SmartPointerManager,
        out: MutableMap<FqName, MutableMap<String, SmartPsiElementPointer<*>>>,
    ) {
        for (decl in declarations) {
            if (decl.hasModifier(KtTokens.ACTUAL_KEYWORD)) {
                val named = decl as? KtNamedDeclaration ?: continue
                val fq = named.fqName ?: continue

                // ModuleUtilCore.findModuleForPsiElement walks the module structure to find
                // which Gradle module (source set) this PSI element belongs to.
                // Returns null for files not attached to any module (e.g. scratch files).
                val module = ModuleUtilCore.findModuleForPsiElement(decl) ?: continue
                val platform = platformOf(module)

                // getOrPut: if we haven't seen this FQ name before, insert a fresh map.
                // Then store the pointer under the platform key. If two actuals exist for
                // the same FQ name on the same platform (a compile error in valid Kotlin),
                // the second one wins — acceptable for our purposes.
                out.getOrPut(fq) { mutableMapOf() }[platform] =
                    pointerManager.createSmartPsiElementPointer(named)
            }

            // Recurse into classes just as we do for expects — actual members of an
            // actual class each need to be recorded too.
            if (decl is KtClassOrObject) {
                collectActuals(decl.declarations, pointerManager, out)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PLATFORM DETECTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classifies a Gradle module into a human-readable platform label by inspecting
     * the module name.
     *
     * WHY MODULE NAMES WORK
     * ----------------------
     * In a KMP Gradle project, the Kotlin Multiplatform plugin creates one IntelliJ
     * "module" per source set. The module is named after the Gradle project + source set,
     * e.g. "shared.androidMain", "shared.iosArm64Main", "composeApp.jvmMain".
     *
     * The source set naming conventions are defined by the KMP Gradle plugin and are
     * consistent across virtually all KMP projects, so substring matching on the
     * lowercased module name is a reliable heuristic.
     *
     * WHY NOT THE KOTLIN FACET?
     * -------------------------
     * Each module carries a KotlinFacet that contains the TargetPlatform (JvmPlatform,
     * JsPlatform, NativePlatform, etc.). This would be more precise and handle unusual
     * naming. We use the name heuristic for v1 because:
     *   - It requires no additional API calls
     *   - The KotlinFacet API has shifted between plugin versions
     *   - It's transparent and easy to debug
     * The Kotlin facet approach is the correct v2 upgrade once the heuristic is validated.
     *
     * ORDER MATTERS: more specific patterns must come before generic ones.
     * ("wasmjs" must be checked before "js"; "ios" before "native".)
     */
    private fun platformOf(module: Module): String {
        val name = module.name.lowercase()
        return when {
            "android" in name  -> "Android"
            "iosarm64" in name -> "iOS (arm64)"
            "iossimulator" in name || "iosx64" in name -> "iOS (simulator)"
            "ios" in name      -> "iOS"
            "wasmjs" in name   -> "WasmJS"
            "js" in name       -> "JS"
            "jvm" in name      -> "JVM"
            "macos" in name    -> "macOS"
            "tvos" in name     -> "tvOS"
            "watchos" in name  -> "watchOS"
            "mingw" in name || "windows" in name -> "Windows"
            "linux" in name    -> "Linux"
            "native" in name   -> "Native"
            // Fallback: use the raw module name so no actual is ever silently dropped.
            // The label will look odd in the tree but is better than losing the data.
            else -> module.name
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXPECT HELPERS (unchanged from before)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recursively walks a list of declarations and appends every `expect` to [out].
     *
     * We recurse into KtClassOrObject because:
     *   - An `expect class Foo` can itself contain `expect fun bar()` members.
     *   - Nested classes can appear at any depth.
     * We do NOT recurse into functions — Kotlin doesn't allow expect declarations
     * inside function bodies.
     */
    private fun collectExpects(
        declarations: List<KtDeclaration>,
        pointerManager: SmartPointerManager,
        out: MutableList<ExpectEntry>,
    ) {
        for (decl in declarations) {
            // hasModifier checks the declaration's modifier list for a specific keyword token.
            // KtTokens.EXPECT_KEYWORD is the token that represents the `expect` keyword.
            // This is the same modifier system used for `private`, `suspend`, `inline`, etc.
            if (decl.hasModifier(KtTokens.EXPECT_KEYWORD)) {
                // KtNamedDeclaration is the subset of declarations that have a name —
                // essentially everything except anonymous objects. The `as?` cast filters
                // out any unnamed declarations (extremely rare, but safe to skip).
                val named = decl as? KtNamedDeclaration ?: continue

                // fqName gives us the fully-qualified name, e.g. "com.example.Platform.name".
                // It can be null for declarations that don't have a stable FQ name (e.g.
                // local declarations), which we skip.
                val fq = named.fqName ?: continue

                out += ExpectEntry(
                    fqName = fq,
                    displayName = named.name.orEmpty() + kindSuffix(decl),
                    kind = kindOf(decl),
                    // SmartPointerManager.createSmartPsiElementPointer wraps the live PSI
                    // element in a stable handle. See ExpectEntry for why this matters.
                    pointer = pointerManager.createSmartPsiElementPointer(named),
                )
            }

            // Always recurse into classes regardless of whether they're `expect` themselves,
            // because a non-expect class can contain expect member functions or properties.
            if (decl is KtClassOrObject) {
                collectExpects(decl.declarations, pointerManager, out)
            }
        }
    }

    private fun kindOf(d: KtDeclaration) = when (d) {
        is KtClassOrObject -> "class"
        is KtNamedFunction -> "function"
        is KtProperty      -> "property"
        else               -> "declaration"
    }

    // Appends "()" to function names so "platformName()" is immediately recognisable
    // as a function rather than a property in the tree.
    private fun kindSuffix(d: KtDeclaration) = if (d is KtNamedFunction) "()" else ""
}
