package io.github.abhijeetk97.kmpexpectactual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for the [Coverage] model's derived properties (issue #8). */
class CoverageTest {

    @Test
    fun `missing platforms are known minus covered`() {
        val c = coverage("com.example.foo", setOf("Android", "iOS"), setOf("Android", "iOS", "JVM"))
        assertEquals(setOf("JVM"), c.missingPlatforms)
        assertFalse(c.isComplete)
    }

    @Test
    fun `complete when every known platform is covered`() {
        val c = coverage("com.example.foo", setOf("Android", "iOS"), setOf("Android", "iOS"))
        assertTrue(c.missingPlatforms.isEmpty())
        assertTrue(c.isComplete)
    }

    @Test
    fun `no actuals at all means everything missing`() {
        val c = coverage("com.example.foo", emptySet(), setOf("Android", "iOS", "JVM"))
        assertEquals(setOf("Android", "iOS", "JVM"), c.missingPlatforms)
    }

    @Test
    fun `coverage summary counts covered over known`() {
        val c = coverage("com.example.foo", setOf("Android", "iOS"), setOf("Android", "iOS", "JVM"))
        assertEquals("2/3 platforms", c.coverageSummary)
    }

    @Test
    fun `query matches display name case-insensitively`() {
        val c = coverage("com.example.platformName", setOf("Android"), setOf("Android"))
        assertTrue(c.matchesQuery("PLATFORM"))
    }

    @Test
    fun `query matches package fragment of fq name`() {
        val c = coverage("com.example.util.helper", setOf("Android"), setOf("Android"))
        assertTrue(c.matchesQuery("example.util"))
    }

    @Test
    fun `query matches platform label`() {
        val c = coverage("com.example.foo", setOf("Android"), setOf("Android", "iOS"))
        assertTrue(c.matchesQuery("ios"))
    }

    @Test
    fun `query with no match returns false`() {
        val c = coverage("com.example.foo", setOf("Android"), setOf("Android"))
        assertFalse(c.matchesQuery("windows"))
    }
}
