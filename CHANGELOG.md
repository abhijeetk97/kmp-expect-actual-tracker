# Changelog

All notable changes to **KMP Expect/Actual Tracker** are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

- **MissingActualInspection** — `expect` declarations missing an `actual` on one or more platforms are now flagged directly in the editor with a warning, using the same project-wide scan as the tool window. Toggle/severity under Settings → Editor → Inspections → Kotlin Multiplatform.
- **Generate 'actual' stub quick fix** — the inspection offers a fix per missing platform that creates a TODO-bodied `actual` stub in the platform's source set, in the same package and file name as the `expect`. Handles functions, properties, classes/objects, interfaces, and enums.
- **Coverage gutter icons** — `expect` declarations get a coverage-aware line marker (green = all platforms covered, red = something missing) that navigates to the actual implementations.
- **Coverage statistics bar** — the tool window now shows a summary line ("42 expects · 36 complete (86%) · 6 incomplete") with a per-platform breakdown tooltip.
- **Group-by modes** — group the coverage tree by package, by module, or by missing platform ("what do I need to write to ship the iOS target?"), with per-group coverage badges.
- **Platform filter** — scope the whole tree to a single platform; combined with the incomplete-only toggle it shows exactly what's missing on that target.
- **Export report** — save the coverage matrix as a standalone HTML page or CSV file from the tool window toolbar.
- **Right-click context menu** — Jump to Source and Copy Qualified Name on tree rows.
- **Reveal in Expect/Actual Tracker** — editor context-menu action that selects the expect under the caret in the tool window.
- **Settings page** (Settings → Tools → KMP Expect/Actual Tracker) — custom excluded path patterns, auto-refresh debounce interval, and a toggle for treating `src/main` as Android.
- **Persistent view state** — the incomplete-only toggle, group-by mode, and platform filter survive IDE restarts.
- **Unit tests + CI** — tests for the coverage model, statistics, report generators, platform detection heuristics, scanner, and stub generator; the Build workflow now runs on pushes to `main` and on pull requests.

### Changed

- Coverage cache invalidation now happens project-wide on any Kotlin PSI change (not just while the tool window is open), keeping the inspection and gutter icons fresh.

---

## [0.2.0] - 2026-06-30

### Added

- **Search by name** — a live filter box in the tool window that narrows the coverage tree as you type. Matches case-insensitively against the expect's display name, fully-qualified name, and platform labels, and composes with the incomplete-only filter.
- **Auto-refresh on edits** — the tool window now re-scans automatically when you add, edit, or delete declarations, instead of requiring a manual Refresh. A `PsiTreeChangeListener` invalidates the cache and a 500 ms `Alarm` debounce coalesces rapid edits (e.g. continuous typing) into a single re-scan.
- Custom SVG plugin icon (`pluginIcon.svg` / `pluginIcon_dark.svg`) shown in the Plugin Manager and Marketplace
- Custom tool window strip icon (`toolWindowExpectActual.svg`) replacing the generic structure icon

### Fixed

- **Multi-module platform detection** — in projects with multiple Gradle modules (e.g. `:core:analytics`, `:core:common`), the tool window was showing every module's source set as a separate "platform" and marking every expect as missing on all of them. Root cause: the platform-detection fallback used the raw IntelliJ module name when no known platform pattern matched. Fix: detect platform from the **file path** first (`/androidMain/`, `/iosMain/`, `/src/main/` for AGP-style Android libraries, etc.); fall back to the module-name suffix only if the path gives no match; drop the entry entirely if the platform is still unrecognised. This keeps `knownPlatforms` clean and prevents fake platform rows.

---

## [0.1.0] - 2026-06-11

First public release.

### Added

- **Expect/Actual tool window** — lists every `expect` declaration in the project as a tree, with one child row per known platform showing ✓ (covered) or ✗ missing
- **Coverage model** (`Coverage`, `ExpectEntry`) — pairs `expect` declarations with their `actual` implementations by fully-qualified name; detects platform from module name heuristics
- **CoverageService** — project-level Light Service that caches the scan result; shared by the tool window and future inspection
- **Refresh toolbar button** — invalidates the cache and re-scans the project on demand
- **Incomplete-only filter toggle** — hides fully-covered expects in memory without re-scanning
- **Double-click navigation** — jumps to the `expect` declaration (parent row) or the `actual` (child row)
- **JBLoadingPanel spinner** — animated loading state while the initial scan runs
- **Smart empty states** — distinct messages for: Gradle sync not run, non-KMP project, KMP project with no expects
- **Generated-file exclusion** — Compose Multiplatform resource accessors and other `/generated/` output are filtered out of the scan
- **K1 + K2 compatibility** — declared via `supportsKotlinPluginMode` in `plugin.xml`; uses syntactic PSI only (no Analysis API dependency)

### Compatibility

- IntelliJ IDEA 2024.3 (build 243) and later
- Android Studio Ladybug (2024.2) and later
- Kotlin plugin K1 and K2 modes
