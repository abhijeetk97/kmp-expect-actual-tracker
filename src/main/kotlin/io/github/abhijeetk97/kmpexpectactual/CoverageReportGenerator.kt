package io.github.abhijeetk97.kmpexpectactual

/**
 * Renders the coverage model as standalone HTML or CSV for the Export action
 * (issue #11).
 *
 * Uses only the plain value fields of [Coverage] (names, platform sets) — no
 * PSI pointers are dereferenced — so it can run anywhere with a snapshot of the
 * coverage list, and it's trivially unit-testable.
 */
object CoverageReportGenerator {

    /**
     * CSV with one row per expect declaration:
     *
     *   Fully-qualified name, Kind, Module, Covered, Known platforms, Status, Missing platforms
     *
     * Missing platforms are `;`-joined inside one cell so the column count is
     * stable regardless of how many platforms the project has.
     */
    fun toCsv(coverage: List<Coverage>): String {
        val header = "Fully-qualified name,Kind,Module,Covered,Known platforms,Status,Missing platforms"
        val rows = coverage.sortedBy { it.expect.fqName.asString() }.map { c ->
            listOf(
                c.expect.fqName.asString(),
                c.expect.kind,
                c.expect.module.orEmpty(),
                c.actualsByPlatform.size.toString(),
                c.knownPlatforms.size.toString(),
                if (c.isComplete) "complete" else "incomplete",
                c.missingPlatforms.sorted().joinToString(";"),
            ).joinToString(",") { csvEscape(it) }
        }
        return (listOf(header) + rows).joinToString("\n") + "\n"
    }

    /**
     * Self-contained HTML report: a summary header plus a coverage matrix with
     * one row per expect and one column per platform. No external assets, so
     * the file can be attached to a PR or opened from disk as-is.
     */
    fun toHtml(coverage: List<Coverage>, projectName: String): String {
        val stats = CoverageStats.from(coverage)
        val platforms = coverage.flatMap { it.knownPlatforms }.toSet().sorted()
        val sorted = coverage.sortedBy { it.expect.fqName.asString() }

        val platformHeaders = platforms.joinToString("") { "<th>${htmlEscape(it)}</th>" }
        val bodyRows = sorted.joinToString("\n") { c ->
            val cells = platforms.joinToString("") { platform ->
                when {
                    platform !in c.knownPlatforms          -> "<td class=\"na\">–</td>"
                    platform in c.actualsByPlatform        -> "<td class=\"ok\">✓</td>"
                    else                                   -> "<td class=\"miss\">✗</td>"
                }
            }
            val rowClass = if (c.isComplete) "complete" else "incomplete"
            "<tr class=\"$rowClass\">" +
                "<td class=\"name\">${htmlEscape(c.expect.fqName.asString())}</td>" +
                "<td>${htmlEscape(c.expect.kind)}</td>" +
                "<td>${htmlEscape(c.expect.module.orEmpty())}</td>" +
                cells +
                "<td>${c.actualsByPlatform.size}/${c.knownPlatforms.size}</td>" +
                "</tr>"
        }

        val perPlatformSummary = stats.perPlatform.entries
            .sortedBy { it.key }
            .joinToString(" · ") { (platform, s) -> "${htmlEscape(platform)}: ${s.covered}/${s.total}" }

        return """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |<meta charset="utf-8">
            |<title>Expect/Actual coverage — ${htmlEscape(projectName)}</title>
            |<style>
            |  body { font-family: -apple-system, "Segoe UI", Roboto, sans-serif; margin: 2rem; color: #1f2328; }
            |  h1 { font-size: 1.4rem; }
            |  .summary { margin: 0.5rem 0 1.5rem; color: #57606a; }
            |  table { border-collapse: collapse; width: 100%; }
            |  th, td { border: 1px solid #d0d7de; padding: 6px 10px; text-align: center; font-size: 0.9rem; }
            |  td.name { text-align: left; font-family: ui-monospace, monospace; }
            |  td.ok { color: #1a7f37; font-weight: bold; }
            |  td.miss { color: #cf222e; font-weight: bold; background: #fff1f0; }
            |  td.na { color: #8c959f; }
            |  tr.incomplete td.name { color: #cf222e; }
            |  th { background: #f6f8fa; }
            |</style>
            |</head>
            |<body>
            |<h1>Expect/Actual coverage — ${htmlEscape(projectName)}</h1>
            |<p class="summary">${htmlEscape(stats.summaryLine)}<br>$perPlatformSummary</p>
            |<table>
            |<thead><tr><th>Expect declaration</th><th>Kind</th><th>Module</th>$platformHeaders<th>Coverage</th></tr></thead>
            |<tbody>
            |$bodyRows
            |</tbody>
            |</table>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun htmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
