package io.github.abhijeetk97.kmpexpectactual

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageStatsTest {

    @Test
    fun `stats over a mixed project`() {
        val stats = CoverageStats.from(
            listOf(
                coverage("com.example.a", setOf("Android", "iOS"), setOf("Android", "iOS")),
                coverage("com.example.b", setOf("Android"), setOf("Android", "iOS")),
                coverage("com.example.c", emptySet(), setOf("Android", "iOS")),
            ),
        )

        assertEquals(3, stats.totalExpects)
        assertEquals(1, stats.completeExpects)
        assertEquals(2, stats.incompleteExpects)
        assertEquals(33, stats.completePercent)
        assertEquals(CoverageStats.PlatformStat(covered = 2, total = 3), stats.perPlatform["Android"])
        assertEquals(CoverageStats.PlatformStat(covered = 1, total = 3), stats.perPlatform["iOS"])
    }

    @Test
    fun `platform totals only count expects that know the platform`() {
        val stats = CoverageStats.from(
            listOf(
                coverage("com.example.a", setOf("Android"), setOf("Android")),
                coverage("com.example.b", setOf("iOS"), setOf("iOS", "JVM")),
            ),
        )

        assertEquals(CoverageStats.PlatformStat(covered = 1, total = 1), stats.perPlatform["Android"])
        assertEquals(CoverageStats.PlatformStat(covered = 0, total = 1), stats.perPlatform["JVM"])
    }

    @Test
    fun `empty project reads as fully covered`() {
        val stats = CoverageStats.from(emptyList())
        assertEquals(0, stats.totalExpects)
        assertEquals(100, stats.completePercent)
    }

    @Test
    fun `summary line format`() {
        val stats = CoverageStats.from(
            listOf(
                coverage("com.example.a", setOf("Android"), setOf("Android")),
                coverage("com.example.b", emptySet(), setOf("Android")),
            ),
        )
        assertEquals("2 expects · 1 complete (50%) · 1 incomplete", stats.summaryLine)
    }
}
