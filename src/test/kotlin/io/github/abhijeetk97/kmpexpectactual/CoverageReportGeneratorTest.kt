package io.github.abhijeetk97.kmpexpectactual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageReportGeneratorTest {

    private val sample = listOf(
        coverage("com.example.platformName", setOf("Android", "iOS"), setOf("Android", "iOS", "JVM"), module = "app.shared"),
        coverage("com.example.Clock", setOf("Android", "iOS", "JVM"), setOf("Android", "iOS", "JVM"), kind = "class"),
    )

    @Test
    fun `csv has a header and one row per expect, sorted by fq name`() {
        val lines = CoverageReportGenerator.toCsv(sample).trim().lines()
        assertEquals(3, lines.size)
        assertEquals(
            "Fully-qualified name,Kind,Module,Covered,Known platforms,Status,Missing platforms",
            lines[0],
        )
        // Sorted: Clock before platformName
        assertEquals("com.example.Clock,class,,3,3,complete,", lines[1])
        assertEquals("com.example.platformName,function,app.shared,2,3,incomplete,JVM", lines[2])
    }

    @Test
    fun `csv escapes fields containing commas and quotes`() {
        val tricky = listOf(
            coverage("com.example.f", emptySet(), setOf("iOS (arm64)", "iOS (simulator)")),
        )
        val row = CoverageReportGenerator.toCsv(tricky).trim().lines()[1]
        // The ;-joined missing list contains no comma, but platform labels with
        // parens stay unquoted; a quote/comma in any cell must be escaped.
        assertTrue(row.endsWith("iOS (arm64);iOS (simulator)"))

        val quoted = CoverageReportGenerator.toCsv(
            listOf(coverage("com.example.g", emptySet(), setOf("A,B"))),
        ).trim().lines()[1]
        assertTrue(quoted.endsWith("\"A,B\""))
    }

    @Test
    fun `html contains summary, matrix cells, and escapes markup`() {
        val html = CoverageReportGenerator.toHtml(sample, "My<Project>")
        assertTrue("project name must be escaped", "My&lt;Project&gt;" in html)
        assertTrue("summary line present", "2 expects · 1 complete (50%) · 1 incomplete" in html)
        assertTrue("covered cell present", "<td class=\"ok\">✓</td>" in html)
        assertTrue("missing cell present", "<td class=\"miss\">✗</td>" in html)
        assertFalse("raw project name must not leak", "My<Project>" in html)
    }

    @Test
    fun `html marks platforms unknown to an expect as not applicable`() {
        val mixed = listOf(
            coverage("com.example.a", setOf("Android"), setOf("Android")),
            coverage("com.example.b", setOf("iOS"), setOf("iOS")),
        )
        val html = CoverageReportGenerator.toHtml(mixed, "p")
        assertTrue("<td class=\"na\">–</td>" in html)
    }
}
