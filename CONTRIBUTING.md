# Contributing to KMP Expect/Actual Tracker

Thanks for your interest in contributing. This document covers everything you need to get the project building locally, understand the architecture, and submit a change.

---

## Prerequisites

| Tool | Version |
|------|---------|
| JDK (JetBrains Runtime) | 17 |
| IntelliJ IDEA | 2024.3 or later (Community or Ultimate) |
| Gradle | Managed by the Gradle wrapper — no separate install needed |

---

## Local setup

```bash
# 1. Clone the repo
git clone https://github.com/abhijeetk97/kmp-expect-actual-tracker.git
cd kmp-expect-actual-tracker

# 2. Open in IntelliJ IDEA
# File → Open → select the cloned directory
# IntelliJ will import the Gradle project automatically.

# 3. Launch the plugin sandbox
./gradlew runIde
```

`runIde` starts a sandboxed IntelliJ instance with the plugin installed. Open any Kotlin Multiplatform project there to test. You will need to run **Gradle sync** inside the sandbox on the target project for the file index to populate.

### Useful Gradle tasks

| Task | Description |
|------|-------------|
| `./gradlew runIde` | Launch the sandbox IDE |
| `./gradlew buildPlugin` | Build the installable `.zip` |
| `./gradlew check` | Run all checks (compile + verify) |
| `./gradlew verifyPlugin` | Run the JetBrains Plugin Verifier |

---

## Architecture overview

```
src/main/kotlin/…/kmpexpectactual/
├── ExpectEntry.kt              Data class: one expect declaration (FqName + SmartPointer)
├── Coverage.kt                 Data class: one expect + its actuals by platform
├── ExpectActualScanner.kt      PSI traversal — finds expects and actuals, detects project state
├── CoverageService.kt          Project-level cache (@Service Level.PROJECT)
└── ExpectActualToolWindowFactory.kt   Swing UI — tree, renderer, toolbar, loading states
```

**Threading contract:**  
All PSI reads happen inside `ReadAction.nonBlocking().inSmartMode()` on a background thread. UI updates happen in `finishOnUiThread()`. Never touch Swing from a background thread; never touch PSI from the EDT without a read action.

**How expect/actual pairing works:**  
The scanner does two passes over all `.kt` files in `projectScope`:
1. Collect all declarations with `hasModifier(KtTokens.EXPECT_KEYWORD)` → `List<ExpectEntry>`
2. Collect all declarations with `hasModifier(KtTokens.ACTUAL_KEYWORD)`, group by FQ name and platform label → `Map<FqName, Map<platform, Pointer>>`

Platform labels come from the module name each file belongs to (`ModuleUtilCore.findModuleForPsiElement`), matched against name-substring heuristics (`androidMain` → `"Android"`, `iosMain` → `"iOS"`, etc.).

---

## Making a change

1. **Pick or file an issue** — check the [issue tracker](https://github.com/abhijeetk97/kmp-expect-actual-tracker/issues) first
2. **Branch from `main`** — use a descriptive name like `issue-5-missing-actual-inspection`
3. **Write code** — follow the existing commenting style (explain *why*, not *what*)
4. **Test manually** — run `./gradlew runIde` and verify in the sandbox
5. **Open a PR** — title format: `Issue #N: short description`

### PR checklist

- [ ] Compiles without warnings (`./gradlew compileKotlin`)
- [ ] Tested in the sandbox against a real KMP project
- [ ] Code comments explain non-obvious decisions
- [ ] `CHANGELOG.md` updated under `[Unreleased]`

---

## Reporting bugs

Use the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml). Please include:
- IDE and plugin version
- Whether you're using K1 or K2 Kotlin plugin mode (Settings → Languages & Frameworks → Kotlin)
- Relevant output from **Help → Show Log in Finder** (filter for `KMP Scanner`)

---

## Questions

Open a [GitHub Discussion](https://github.com/abhijeetk97/kmp-expect-actual-tracker/discussions) or file an issue tagged `question`.
