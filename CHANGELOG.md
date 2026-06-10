# Changelog

All notable changes to **KMP Expect/Actual Tracker** are documented here.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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
