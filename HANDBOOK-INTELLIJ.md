# Building an IntelliJ Plugin From Zero: KMP Expect/Actual Tracker

A complete, opinionated handbook that takes you from an empty folder to a published,
open-source IntelliJ plugin. Written for an experienced Kotlin/Android/KMP engineer who
has never built an IDE plugin before. We skip Kotlin and Gradle basics and spend our time
on the parts that are genuinely new: the IntelliJ Platform, PSI, the threading model, tool
windows, and the Kotlin plugin's APIs.

By the end you will have:

- A working plugin that scans a project and shows every `expect` declaration and which
  platforms supply an `actual`, with missing actuals flagged.
- A clean GitHub repo with CI, a README with screenshots, a license, and a release.
- A listing on the JetBrains Marketplace (optional but recommended).
- A genuinely defensible talking point for senior interviews.

---

## 0. What you are building, and why it is a good project

### The product in one sentence

A tool window that, for a Kotlin Multiplatform project, lists all `expect` declarations and
shows their `actual` coverage across targets, highlighting any `expect` that is missing an
`actual` for one or more platforms.

### Be honest about the "novelty" (this matters in interviews)

The Kotlin IDE plugin **already** does two things you might think you're inventing:

1. It draws gutter icons that link an `expect` to its `actual`(s).
2. It reports a missing `actual` as a hard compiler error (`NO_ACTUAL_FOR_EXPECT`) when a
   target is fully configured.

So your differentiation is **not** "detect missing actuals." It is:

- **Aggregation**: a single project-wide panel — a coverage dashboard — instead of
  jumping file to file. On a real KMP codebase with dozens of `expect`s across `commonMain`
  and several platform source sets, that overview does not exist out of the box.
- **Coverage at a glance**: per-declaration, which targets are covered vs. missing,
  including the "I added a target last week and forgot to implement three things" case.
- **Fast navigation and filtering**: jump to any `expect` or any of its `actual`s; filter
  to "incomplete only."

State this framing in your README and your resume. "I understood what the platform already
provided and deliberately built the layer it didn't" is a stronger signal than pretending
you built something that already exists.

### The mental model you need

An IntelliJ plugin is not an app with a `main()`. It is a **bundle of extensions** that the
platform discovers and calls at the right moments. You register *what* you provide (a tool
window, an inspection, a line-marker provider) in a manifest (`plugin.xml`), and the
platform instantiates and calls your classes when relevant. Your job is mostly: implement a
few interfaces, read the code model (PSI), and render results.

---

## 1. Prerequisites and environment

You already have most of this as an Android dev. Confirm:

- **JDK 17** (or 21). Recent IntelliJ Platform versions require JDK 17+ to compile. Prefer
  the **JetBrains Runtime (JBR)** as your Gradle JVM — the plugin warns if you use a stock
  JDK. In IntelliJ: `Settings → Build, Execution, Deployment → Build Tools → Gradle →
  Gradle JVM`.
- **IntelliJ IDEA** (Community is fine for development). Make sure the bundled **Gradle**
  and **Plugin DevKit** plugins are enabled (they are by default).
- **Git** and a **GitHub account**.
- A small **KMP sample project** to test against. Either generate one from the Kotlin
  Multiplatform wizard, or clone any open-source KMP library. You want one with
  `commonMain` plus at least two platform source sets (e.g. `androidMain`, `iosMain`,
  `jvmMain`) and a few `expect`/`actual` pairs — including, deliberately, one `expect` with
  a missing `actual` so you can see your warning fire.

A note on terminology you'll see everywhere:

- **K1 / K2**: two generations of the Kotlin compiler frontend. The IDE has a matching
  "Kotlin plugin mode." Since IntelliJ 2025.3, **K2 mode is the default**. This matters
  because a third-party plugin that touches Kotlin will refuse to load under K2 unless it
  declares compatibility. We handle this in `plugin.xml` (Section 4). Our v1 uses only
  syntactic PSI, which works in both modes, so this is a one-line declaration, not a
  research project.

---

## 2. Core concepts crash course

Read this once. You'll reread parts as you implement. These five concepts are 80% of what's
unfamiliar.

### 2.1 PSI — the Program Structure Interface

PSI is IntelliJ's parsed, semantic tree of source code. Every file is a `PsiFile`; for
Kotlin, a `KtFile`. Inside it are `PsiElement`s; for Kotlin, types like `KtClass`,
`KtFunction`, `KtProperty`, all of which are `KtDeclaration`s.

What you'll do with PSI:

- **Find declarations**: walk a `KtFile`'s `declarations`, recursing into classes.
- **Inspect modifiers**: `expect` and `actual` are modifier keywords. A `KtDeclaration` is a
  `KtModifierListOwner`, so `decl.hasModifier(KtTokens.EXPECT_KEYWORD)` tells you it's an
  expect. (There are also convenience extensions `hasExpectModifier()` /
  `hasActualModifier()` in `org.jetbrains.kotlin.psi.psiUtil` — let autocomplete confirm the
  exact import in your platform version.)
- **Get the fully-qualified name**: `KtNamedDeclaration.fqName` returns an `FqName?`. This is
  how we'll match an `expect` to its `actual`s in v1.
- **Navigate**: declarations are `NavigatablePsiElement`s, so `decl.navigate(true)` opens
  them in the editor.

PSI is **read-only data that can change at any time**, which leads directly to:

### 2.2 The threading model and read actions

This is the single most important platform rule, and the one most likely to bite an
Android dev used to `Dispatchers`. The rules:

- **Never touch PSI off a read action.** Reading the PSI tree requires holding a *read
  lock*. On the EDT (UI thread) you implicitly have read access; on a background thread you
  must wrap reads in `ReadAction.compute { ... }` or, better for anything slow, a
  cancellable `ReadAction.nonBlocking { ... }`.
- **Never do slow work on the EDT.** Scanning a whole project for `expect`s is slow. So the
  pattern is: compute the model on a background thread inside a non-blocking read action,
  then hand the result back to the EDT to update Swing.

The canonical pattern you'll use repeatedly:

```kotlin
ReadAction.nonBlocking<List<ExpectEntry>> { computeModel(project) }
    .inSmartMode(project)                 // wait until indexing is done
    .expireWith(project)                  // cancel if the project closes
    .finishOnUiThread(ModalityState.defaultModalityState()) { entries ->
        updateTree(entries)               // touch Swing only here, on the EDT
    }
    .submit(AppExecutorUtil.getAppExecutorService())
```

If you ignore this, you'll get `Read access is allowed from inside read-action only`
exceptions or UI freezes. Internalize it now.

### 2.3 Indexes and `GlobalSearchScope`

You don't grep files. The platform maintains indexes. To enumerate all Kotlin files:

```kotlin
FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.projectScope(project))
```

This returns `VirtualFile`s, which you convert to `KtFile` via
`PsiManager.getInstance(project).findFile(vf) as? KtFile`. Indexes are only reliable in
"smart mode" (after indexing finishes) — hence `.inSmartMode(project)` above.

### 2.4 Extension points and the plugin manifest

`plugin.xml` is your manifest. You declare:

- Plugin metadata (id, name, vendor, version, compatibility range).
- Dependencies on other plugins (we depend on the Kotlin plugin).
- **Extensions**: each one plugs your class into a platform extension point. We'll use
  `<toolWindow>`, `<localInspection>`, and `<codeInsight.lineMarkerProvider>`.

The platform reads this, finds your classes, and calls them. You rarely instantiate your own
extension classes — the platform does.

### 2.5 Services, the project model, and Disposable

- A **Service** is a lazily-created singleton scoped to the application or a project. You'll
  put your "scan and cache the model" logic in a project-level service so the tool window and
  the inspection can share it.
- A **`Project`** is the open project. Almost every API is reached through it.
- A **`Module`** is a build module; in a KMP project, source sets map to modules. We use the
  module (and later the Kotlin facet) to figure out *which platform* a declaration belongs
  to.
- **`Disposable`**: the platform's lifecycle/cleanup mechanism. UI and listeners get tied to
  a `Disposable` so they're cleaned up when the tool window or project closes. You'll pass
  one around; don't fight it.

---

## 3. Create the project

Two routes. **Use the template.** It wires up CI, signing, the changelog, and a sane
`build.gradle.kts` for you, all of which you'd otherwise assemble by hand and get subtly
wrong.

### Route A (recommended): the IntelliJ Platform Plugin Template

1. Go to `github.com/JetBrains/intellij-platform-plugin-template`.
2. Click **Use this template → Create a new repository**. Name it, e.g.,
   `expect-actual-tracker`. Make it public (you're open-sourcing it).
3. Clone it locally and open it in IntelliJ. Let Gradle sync (first sync downloads an IDE
   distribution to build against — it's large and slow once).
4. The template ships a GitHub Actions workflow that builds, runs the plugin verifier, and
   can publish. You'll customize it later.

### Route B: New Project wizard

`File → New → Project → IDE Plugin`. Pick Gradle, Kotlin. This produces a minimal project
without the template's CI niceties. Fine for learning; you'll end up re-adding what the
template gives you, so only do this if you want to assemble it yourself once for
understanding.

### What the important files are

```
expect-actual-tracker/
├─ build.gradle.kts          # build config: platform version, deps, tasks
├─ settings.gradle.kts       # project name, repositories (2.x style)
├─ gradle.properties         # versions and plugin metadata as properties
├─ gradle/
│  └─ libs.versions.toml     # version catalog (template uses this)
├─ src/main/kotlin/...        # your Kotlin source
├─ src/main/resources/
│  ├─ META-INF/plugin.xml    # the manifest
│  └─ messages/              # i18n bundles (optional)
├─ .github/workflows/         # CI (build, verify, release)
└─ README.md
```

---

## 4. Configure the build for our needs

The template's defaults are close. You need to: target a recent IDE, depend on the Kotlin
plugin, and declare K2 compatibility.

### `gradle.properties` (key entries)

```properties
pluginGroup = dev.abhijeet.expectactual
pluginName = ExpectActual Tracker
pluginVersion = 0.1.0

# Compatibility range. sinceBuild 243 == 2024.3. Keep untilBuild open-ended
# unless the verifier tells you otherwise.
pluginSinceBuild = 243

# The IDE you build/run against during development.
platformType = IC
platformVersion = 2025.2.3

# Bundled plugins we depend on. The Kotlin plugin gives us KtFile, KtDeclaration, etc.
platformBundledPlugins = org.jetbrains.kotlin

gradleVersion = 8.13
```

### `build.gradle.kts` (the parts that matter)

With the 2.x plugin you no longer write a top-level `repositories {}` for the platform; you
configure everything through the `intellijPlatform {}` extension and a dedicated
`dependencies { intellijPlatform { ... } }` block.

```kotlin
plugins {
    id("java")
    alias(libs.plugins.kotlin)                 // org.jetbrains.kotlin.jvm
    alias(libs.plugins.intelliJPlatform)       // org.jetbrains.intellij.platform, 2.x
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        bundledPlugins(
            providers.gradleProperty("platformBundledPlugins").map { it.split(',') },
        )
        // Test frameworks if/when you add tests:
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // untilBuild intentionally omitted -> open-ended forward compatibility
        }
    }
    // signing { ... } and publishing { ... } configured in Section 10.
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(17)
        vendor = JvmVendorSpec.JETBRAINS
    }
}
```

> If your template uses `libs.versions.toml`, set the IntelliJ Platform Gradle Plugin to a
> recent 2.x (2.15.0 at time of writing) and Kotlin to a version compatible with your target
> IDE's bundled Kotlin.

### `plugin.xml` (the manifest)

```xml
<idea-plugin>
    <id>dev.abhijeet.expectactual</id>
    <name>ExpectActual Tracker</name>
    <vendor email="you@example.com" url="https://github.com/yourname">Abhijeet</vendor>

    <description><![CDATA[
        A project-wide coverage dashboard for Kotlin Multiplatform expect/actual
        declarations. Lists every expect and shows which targets supply an actual,
        flagging incomplete declarations.
    ]]></description>

    <!-- Required: platform + the Kotlin plugin -->
    <depends>com.intellij.modules.platform</depends>
    <depends>org.jetbrains.kotlin</depends>

    <!-- Declare K2 compatibility. Our v1 uses only syntactic PSI, so it works in
         both K1 and K2. Without this, the plugin won't load under K2 (default since 2025.3). -->
    <extensions defaultExtensionNs="org.jetbrains.kotlin">
        <supportsKotlinPluginMode supportsK1="true" supportsK2="true" />
    </extensions>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow
            id="Expect/Actual"
            anchor="right"
            factoryClass="dev.abhijeet.expectactual.ExpectActualToolWindowFactory"
            icon="AllIcons.Toolwindows.ToolWindowStructure" />
    </extensions>
</idea-plugin>
```

Sync Gradle. You now have a buildable, do-nothing plugin.

### Run it

Use the `runIde` Gradle task (the template adds a run configuration too). This launches a
**sandboxed IDE instance** with your plugin installed. Open your KMP sample project inside it.
You'll see an empty "Expect/Actual" tool window on the right once you implement the factory.
This sandbox is your entire dev loop: edit code → `runIde` → poke at it.

---

## 5. Milestone 1 — MVP: list every `expect` in a tool window

Goal: a tool window that, on open and on refresh, scans the project and shows each `expect`
declaration. Clicking one navigates to it. No actual-matching yet. This alone is a complete,
demoable plugin — ship a screenshot of it.

### 5.1 The data model

```kotlin
package dev.abhijeet.expectactual

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.name.FqName

data class ExpectEntry(
    val fqName: FqName,
    val displayName: String,        // e.g. "Platform.name: String"
    val kind: String,               // "class" | "function" | "property"
    val pointer: SmartPsiElementPointer<*>,   // stable handle to the PSI element
)
```

Why `SmartPsiElementPointer` instead of holding the `PsiElement` directly? PSI elements get
invalidated when files change. A smart pointer survives edits and re-resolves to the current
element. Create them with `SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)`.
Always dereference inside a read action.

### 5.2 The scanner (runs in a read action, off the EDT)

```kotlin
package dev.abhijeet.expectactual

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FileTypeIndex
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

object ExpectActualScanner {

    // MUST be called inside a read action.
    fun findExpects(project: Project): List<ExpectEntry> {
        val pointerManager = SmartPointerManager.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val result = mutableListOf<ExpectEntry>()

        val ktFiles = FileTypeIndex.getFiles(
            KotlinFileType.INSTANCE,
            GlobalSearchScope.projectScope(project),
        )

        for (vf in ktFiles) {
            val ktFile = psiManager.findFile(vf) as? KtFile ?: continue
            collectExpects(ktFile.declarations, pointerManager, result)
        }
        return result
    }

    private fun collectExpects(
        declarations: List<KtDeclaration>,
        pointerManager: SmartPointerManager,
        out: MutableList<ExpectEntry>,
    ) {
        for (decl in declarations) {
            if (decl.hasModifier(KtTokens.EXPECT_KEYWORD)) {
                val named = decl as? KtNamedDeclaration ?: continue
                val fq = named.fqName ?: continue
                out += ExpectEntry(
                    fqName = fq,
                    displayName = named.name.orEmpty() + kindSuffix(decl),
                    kind = kindOf(decl),
                    pointer = pointerManager.createSmartPsiElementPointer(named),
                )
            }
            // Recurse: an expect class can contain expect members; nested classes too.
            if (decl is KtClassOrObject) {
                collectExpects(decl.declarations, pointerManager, out)
            }
        }
    }

    private fun kindOf(d: KtDeclaration) = when (d) {
        is KtClassOrObject -> "class"
        is KtNamedFunction -> "function"
        is KtProperty -> "property"
        else -> "declaration"
    }

    private fun kindSuffix(d: KtDeclaration) = if (d is KtNamedFunction) "()" else ""
}
```

> Exact import paths (e.g. `KotlinFileType`) occasionally move between platform versions.
> If one doesn't resolve, type the simple name and let IntelliJ's auto-import suggest the
> right package — that's faster than guessing.

### 5.3 The tool window

A `ToolWindowFactory` builds the panel. We'll use a `Tree` for grouping later; for the MVP a
simple list is enough, but starting with a tree saves a rewrite.

```kotlin
package dev.abhijeet.expectactual

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.NavigatablePsiElement
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class ExpectActualToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = DefaultMutableTreeNode("Expect declarations")
        val model = DefaultTreeModel(root)
        val tree = Tree(model)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val entry = node.userObject as? ExpectEntry ?: return
                // Navigation reads PSI -> read action, then navigate on EDT.
                ReadAction.nonBlocking<NavigatablePsiElement?> {
                    entry.pointer.element as? NavigatablePsiElement
                }.finishOnUiThread(ModalityState.defaultModalityState()) { el ->
                    el?.navigate(true)
                }.submit(AppExecutorUtil.getAppExecutorService())
            }
        })

        refresh(project, root, model)

        val content = toolWindow.contentManager.factory
            .createContent(JBScrollPane(tree), null, false)
        toolWindow.contentManager.addContent(content)
    }

    private fun refresh(project: Project, root: DefaultMutableTreeNode, model: DefaultTreeModel) {
        ReadAction.nonBlocking<List<ExpectEntry>> { ExpectActualScanner.findExpects(project) }
            .inSmartMode(project)
            .expireWith(project)
            .finishOnUiThread(ModalityState.defaultModalityState()) { entries ->
                root.removeAllChildren()
                entries.sortedBy { it.fqName.asString() }.forEach { entry ->
                    root.add(DefaultMutableTreeNode(entry).apply { /* leaf */ })
                }
                model.reload()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }
}
```

To make the tree show `displayName` instead of the raw `toString()`, set a simple
`ColoredTreeCellRenderer` (you'll want this anyway for coloring missing actuals in Milestone
3).

Run `runIde`, open your KMP project, open the tool window. You should see your `expect`s.
Double-click navigates. **This is your first checkpoint — commit it.**

---

## 6. Milestone 2 — match `actual`s per platform; detect missing ones

Now the actual value. For each `expect`, find the `actual`s and figure out which platforms
are covered.

### 6.1 Strategy: FQ-name matching (v1) vs. semantic (v3)

- **v1 (do this now): FQ-name matching.** Collect all `actual` declarations the same way you
  collect `expect`s, keyed by `FqName`. For each `expect`, look up actuals with the matching
  `FqName`. This is correct for the overwhelming majority of real code. Its limitation:
  overloaded functions with the same FQ name but different signatures can collide — note this
  as a known limitation. It needs no semantic analysis, works in K1 and K2, and keeps your
  compatibility range wide.
- **v3 (later, optional): the Kotlin Analysis API.** `analyze(decl) { ... }` gives you
  resolved symbols and precise expect↔actual mapping, handling overloads and typealias
  actuals correctly. It requires declaring K2-only support and opting into some annotations.
  Treat it as an accuracy upgrade, documented in your roadmap — not a v1 requirement.

### 6.2 Determine a declaration's platform

You need to label each `actual` with a platform. Two approaches, in increasing fidelity:

1. **Module-name heuristic (quick):** get the module via
   `ModuleUtilCore.findModuleForPsiElement(decl)`, then classify by name substring
   (`androidMain`/`android` → Android, `ios`/`apple` → iOS, `jvm` → JVM, `js` → JS,
   `native`/`linux`/`mingw` → Native, etc.). Cheap, good enough for a first cut, and easy to
   explain.
2. **Kotlin facet (accurate):** the module carries a Kotlin facet describing its
   `targetPlatform`. Roughly:
   `KotlinFacet.get(module)?.configuration?.settings?.targetPlatform` yields a
   `TargetPlatform` you can render. Use this once the heuristic works, and fall back to the
   heuristic if the facet is absent.

Wrap this in a small `fun platformOf(decl: KtDeclaration): String`.

### 6.3 Build the coverage model

```kotlin
data class Coverage(
    val expect: ExpectEntry,
    val actualsByPlatform: Map<String, SmartPsiElementPointer<*>>, // platform -> actual
    val knownPlatforms: Set<String>,    // all platforms that exist in this project
) {
    val missingPlatforms: Set<String> get() = knownPlatforms - actualsByPlatform.keys
    val isComplete: Boolean get() = missingPlatforms.isEmpty()
}
```

`knownPlatforms` is the set of leaf (platform) source sets present in the project. The
cleanest "what should be covered" definition is: the platform source sets that depend on the
`commonMain` where the `expect` lives. A pragmatic v1: collect the distinct platforms seen
across all `actual`s in the project, and treat that as the expected set. Document the
simplification; refine later by walking the source-set dependency graph.

### 6.4 Put it in a project service (shared cache)

Both the tool window and the inspection (Milestone 3) need this model. Compute it once,
cache it, invalidate on changes.

```kotlin
package dev.abhijeet.expectactual

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class CoverageService(private val project: Project) {

    @Volatile private var cache: List<Coverage>? = null

    // Call inside a read action (it touches PSI).
    fun getCoverage(): List<Coverage> =
        cache ?: computeCoverage(project).also { cache = it }

    fun invalidate() { cache = null }

    companion object {
        fun getInstance(project: Project): CoverageService =
            project.getService(CoverageService::class.java)
    }
}
```

Register nothing in XML for a `@Service` — the annotation is enough. To invalidate on edits,
subscribe to PSI change events (`PsiManager.addPsiTreeChangeListener`, tied to a
`Disposable`) and call `invalidate()`, then refresh the tool window. For v1 you can start
with a manual "Refresh" toolbar button and add auto-invalidation as polish.

### 6.5 Render coverage in the tree

Make each `expect` a parent node; children are `Platform: ✓` or `Platform: missing`. Use a
`ColoredTreeCellRenderer` to paint missing platforms in the error color
(`SimpleTextAttributes.ERROR_ATTRIBUTES`) and add a count badge on the parent
("3/4 platforms"). Add a filter toggle "Show incomplete only."

**Second checkpoint — commit it.** You now have the actual product.

---

## 7. Milestone 3 — surface missing actuals in the editor

The tool window is the dashboard. Now add two editor-level touches that make the plugin feel
native.

### 7.1 A local inspection

A `LocalInspectionTool` runs as you type and lets you flag a problem on a specific element
with a description and (optionally) a quick fix.

```kotlin
package dev.abhijeet.expectactual

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtVisitorVoid

class MissingActualInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : KtVisitorVoid() {
            override fun visitNamedDeclaration(decl: KtNamedDeclaration) {
                if (!decl.hasModifier(KtTokens.EXPECT_KEYWORD)) return
                val project = decl.project
                val coverage = CoverageService.getInstance(project).getCoverage()
                    .firstOrNull { it.expect.fqName == decl.fqName } ?: return
                if (!coverage.isComplete) {
                    val nameId = decl.nameIdentifier ?: return
                    holder.registerProblem(
                        nameId,
                        "Missing actual for: " + coverage.missingPlatforms.joinToString(),
                    )
                }
            }
        }
}
```

Register it:

```xml
<extensions defaultExtensionNs="com.intellij">
    <localInspection
        language="kotlin"
        displayName="Missing actual declaration"
        groupName="Kotlin Multiplatform"
        enabledByDefault="true"
        level="WARNING"
        implementationClass="dev.abhijeet.expectactual.MissingActualInspection" />
</extensions>
```

> Inspections already run in the correct read context, so you don't manage threading here —
> but `getCoverage()` touches PSI and may be slow; make sure your service caching is in place
> so the inspection isn't recomputing the whole project on every keystroke.

### 7.2 A gutter line marker (optional)

A `RelatedItemLineMarkerProvider` draws a clickable gutter icon next to an `expect` that
jumps to its `actual`s. The native Kotlin plugin already provides expect/actual gutter icons,
so consider instead a marker whose target is *your tool window filtered to this declaration*,
or skip it to avoid duplicating built-in behavior. Mention the decision in your README — it
shows judgment.

**Third checkpoint — commit it.**

---

## 8. Milestone 4 — polish

These are what separate "demo" from "I'd install this."

- **Auto-refresh**: invalidate the cache on PSI changes (debounced — don't recompute on every
  keystroke; coalesce with a short alarm/timer). Refresh the open tool window.
- **Performance**: keep all scanning inside `ReadAction.nonBlocking` with `.inSmartMode`.
  For large projects, consider iterating only `expect`-bearing files. A `commonMain`-only
  scope for expects narrows the search.
- **Empty/loading states**: show "No KMP modules detected" or "Indexing…" instead of a blank
  tree.
- **Toolbar**: a `ToolbarDecorator` or an `ActionToolbar` with Refresh and the
  "incomplete only" filter.
- **Icons**: a custom 13×13 tool-window icon (SVG, light/dark variants) instead of the
  borrowed `AllIcons` one.
- **Settings (optional)**: a configurable list of platform name mappings via a
  `Configurable`.

---

## 9. Testing

Plugin tests run against a headless platform fixture. Add the test framework dependency
(Section 4), then write a "light" test that loads Kotlin fixtures and asserts your scanner
finds the right expects.

```kotlin
class ScannerTest : BasePlatformTestCase() {
    fun `test finds expect function`() {
        myFixture.configureByText(
            "Platform.kt",
            "expect fun platformName(): String",
        )
        val expects = runReadAction { ExpectActualScanner.findExpects(project) }
        assertEquals(1, expects.size)
        assertEquals("platformName", expects.first().fqName.shortName().asString())
    }
}
```

- Use `BasePlatformTestCase` (from the Platform test framework) for fixture-based tests.
- Since 2025.3, tests run in K2 mode by default. If you specifically need K1,
  pass `-Didea.kotlin.plugin.use.k1=true` to the test task; your v1 doesn't depend on either
  mode's analysis, so you generally don't need to.
- Run via the `test` Gradle task; wire it into CI.

A handful of scanner/coverage tests is plenty for a portfolio project and demonstrates you
test IDE code, which most candidates can't claim.

---

## 10. Build, verify, sign, and publish

### Build a distributable

`./gradlew buildPlugin` produces a ZIP in `build/distributions/`. Anyone can install it via
`Settings → Plugins → ⚙ → Install Plugin from Disk…`. That's enough to share on GitHub
Releases even if you never touch the Marketplace.

### Verify compatibility

`./gradlew verifyPlugin` runs JetBrains' Plugin Verifier against your declared compatibility
range and reports API misuses or incompatibilities. Fix what it flags. CI should run this.

### Publishing to the JetBrains Marketplace (optional but recommended)

1. **Create a Marketplace account** at `plugins.jetbrains.com` and get a **permanent
   upload token** (Marketplace → your profile → tokens).
2. **Generate a signing certificate.** The Marketplace requires signed plugins. The platform
   docs walk through generating a chain + private key with `openssl`. You'll end up with
   three secrets: the certificate chain, the private key, and the private key password.
3. **Configure signing and publishing** in `build.gradle.kts`, reading from environment
   variables (never commit secrets):

   ```kotlin
   intellijPlatform {
       signing {
           certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
           privateKey = providers.environmentVariable("PRIVATE_KEY")
           password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
       }
       publishing {
           token = providers.environmentVariable("PUBLISH_TOKEN")
       }
   }
   ```

4. **First upload is manual**: build the signed ZIP and upload it on the website so a human
   reviews the first version. Subsequent releases can go through `./gradlew publishPlugin`
   from CI.
5. **CI secrets**: store `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, and
   `PUBLISH_TOKEN` as GitHub Actions repository secrets. The template's `release` workflow
   already references these names.

> The exact `openssl` commands for the certificate are documented under "Plugin Signing" in
> the IntelliJ Platform SDK docs (Section 15). Follow them verbatim; it's a one-time setup.

---

## 11. Make it a good open-source repo

This is where a side project becomes a *credential*. Invest a couple of hours here.

- **README** with, in order: a one-line description, an animated GIF or screenshot of the
  tool window flagging a missing actual, the problem it solves (the honest framing from
  Section 0), install instructions (Marketplace link + install-from-disk), a short
  architecture note, known limitations (the FQ-name matching caveat), and a roadmap.
- **LICENSE**: MIT or Apache-2.0. Pick one and add the file; "open source" without a license
  isn't actually open source.
- **Screenshots/GIF**: record the sandbox IDE with a missing actual lighting up. This is the
  single highest-leverage asset for both the README and the Marketplace listing.
- **CI badges**: build status, latest release, Marketplace version/downloads.
- **CHANGELOG.md**: the template manages this; keep it updated per release.
- **CONTRIBUTING.md** (short) and issue templates: signals maturity even with zero
  contributors.
- **Topics/tags** on the GitHub repo: `intellij-plugin`, `kotlin-multiplatform`, `kmp`,
  `jetbrains`, so it's discoverable.
- **Tag releases** (`v0.1.0`) and attach the built ZIP to the GitHub Release.

---

## 12. Putting it on your resume

Frame it by impact and by what it demonstrates, not by feature list.

> **ExpectActual Tracker — IntelliJ/Android Studio plugin (Kotlin, open source).**
> Built a project-wide coverage dashboard for Kotlin Multiplatform `expect`/`actual`
> declarations: surfaces missing platform implementations across source sets that the IDE
> otherwise reports only file-by-file. Implemented PSI traversal and FQ-name resolution over
> indexed sources, a cached project-level service, a custom tool window, and a local
> inspection; respected the platform's read-action threading model and K1/K2 compatibility.
> Published to the JetBrains Marketplace with signed CI releases. [link]

Why this lands for senior mobile/KMP roles:

- It's adjacent to your actual job (KMP, the exact pain a multiplatform team feels).
- It shows depth most app devs lack: PSI, the platform threading model, IDE tooling.
- "Published, signed, CI, open source" demonstrates you finish and ship.
- It gives you concrete interview stories: the threading model, why you chose FQ-name
  matching over the Analysis API for v1, how you scoped against built-in behavior.

In interviews, be ready to discuss: read actions vs. EDT, why PSI pointers must be smart
pointers, the K1→K2 migration and why you declared dual compatibility, and the trade-off in
your matching strategy. Those answers signal seniority.

---

## 13. Roadmap / stretch goals (also good README + interview material)

- **Analysis API upgrade**: precise expect↔actual resolution handling overloads and typealias
  actuals; declare K2-only support.
- **Source-set-aware expected platforms**: compute the *required* platforms per `expect` by
  walking the source-set dependency graph, instead of the project-wide union heuristic.
- **Quick fix**: generate `actual` stubs for missing platforms from the inspection.
- **Statistics**: total coverage %, per-module breakdown.
- **Android Studio**: verify and list compatibility (it's IntelliJ-based; usually works with
  the right since/until build).

---

## 14. Common pitfalls and troubleshooting

- **`Read access is allowed from inside read-action only`** — you touched PSI off the EDT
  without a read action. Wrap it.
- **UI freezes / "not responding"** — you did the scan on the EDT. Move it into
  `ReadAction.nonBlocking { }.submit(...)`.
- **Plugin doesn't load under a recent IDE** — you forgot `<supportsKotlinPluginMode>` or
  your since/until range excludes the IDE. Check the IDE's `Help → About` build number
  against `pluginSinceBuild`.
- **`KtFile` cast returns null** — the file isn't a Kotlin file, or you queried before
  indexing finished. Use `.inSmartMode(project)`.
- **Stale results after edits** — your cache isn't invalidated. Add the PSI change listener
  or a Refresh button.
- **Import doesn't resolve** (`KotlinFileType`, psiUtil extensions) — use auto-import; exact
  packages vary by platform version.
- **First Gradle sync is enormous/slow** — it's downloading a full IDE to build against.
  Normal, one-time.
- **Verifier complains about API usage** — you used an internal/deprecated API; the message
  links the replacement. Fix before publishing.

---

## 15. Reference links

- IntelliJ Platform SDK docs (start here): `plugins.jetbrains.com/docs/intellij/`
- IntelliJ Platform Gradle Plugin (2.x): `plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html`
- Plugin Template: `github.com/JetBrains/intellij-platform-plugin-template`
- Tool windows: `plugins.jetbrains.com/docs/intellij/tool-windows.html`
- Code inspections: `plugins.jetbrains.com/docs/intellij/code-inspections.html`
- PSI / navigating PSI: `plugins.jetbrains.com/docs/intellij/psi.html`
- Threading model: `plugins.jetbrains.com/docs/intellij/threading-model.html`
- Kotlin Analysis API (for the v3 upgrade): `kotlin.github.io/analysis-api/`
- Declaring K2 compatibility: `kotlin.github.io/analysis-api/declaring-k2-compatibility.html`
- Plugin signing & publishing: `plugins.jetbrains.com/docs/intellij/plugin-signing.html`
- The official sample plugins repo (read real code): `github.com/JetBrains/intellij-sdk-code-samples`

---

### Suggested build order (checklist)

1. [ ] Template repo created, builds, `runIde` launches a sandbox.
2. [ ] `plugin.xml` declares Kotlin dependency + K2 compatibility; empty tool window appears.
3. [ ] Scanner finds all `expect`s; tool window lists them; double-click navigates. **(commit)**
4. [ ] Coverage model matches actuals by FQ name; platforms labeled; missing detected. **(commit)**
5. [ ] Tree shows per-platform ✓/missing; "incomplete only" filter. **(commit)**
6. [ ] Inspection flags missing actuals in the editor. **(commit)**
7. [ ] Auto-refresh on edits; empty/loading states; custom icon.
8. [ ] A few scanner/coverage tests; CI runs build + verify + test.
9. [ ] README with GIF, LICENSE, CHANGELOG, badges; tagged release with ZIP.
10. [ ] (Optional) Signed, published to the Marketplace.

Each numbered step is independently demoable. Stop wherever your time runs out — even step 5
is a legitimate, resume-worthy, open-source plugin.
