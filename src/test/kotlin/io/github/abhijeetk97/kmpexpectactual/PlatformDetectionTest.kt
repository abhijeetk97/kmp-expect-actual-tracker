package io.github.abhijeetk97.kmpexpectactual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the scanner's pure heuristics (issue #8). */
class PlatformDetectionTest {

    // ── platformFromPath ─────────────────────────────────────────────────────

    @Test
    fun `standard kmp source sets resolve from path`() {
        assertEquals("Android", ExpectActualScanner.platformFromPath("/p/src/androidMain/kotlin/A.kt"))
        assertEquals("iOS", ExpectActualScanner.platformFromPath("/p/src/iosMain/kotlin/A.kt"))
        assertEquals("iOS (arm64)", ExpectActualScanner.platformFromPath("/p/src/iosArm64Main/kotlin/A.kt"))
        assertEquals("iOS (simulator)", ExpectActualScanner.platformFromPath("/p/src/iosSimulatorArm64Main/kotlin/A.kt"))
        assertEquals("JVM", ExpectActualScanner.platformFromPath("/p/src/jvmMain/kotlin/A.kt"))
        assertEquals("JS", ExpectActualScanner.platformFromPath("/p/src/jsMain/kotlin/A.kt"))
        assertEquals("WasmJS", ExpectActualScanner.platformFromPath("/p/src/wasmJsMain/kotlin/A.kt"))
        assertEquals("Desktop", ExpectActualScanner.platformFromPath("/p/src/desktopMain/kotlin/A.kt"))
        assertEquals("macOS", ExpectActualScanner.platformFromPath("/p/src/macosArm64Main/kotlin/A.kt"))
        assertEquals("Windows", ExpectActualScanner.platformFromPath("/p/src/mingwX64Main/kotlin/A.kt"))
        assertEquals("Linux", ExpectActualScanner.platformFromPath("/p/src/linuxX64Main/kotlin/A.kt"))
        assertEquals("Native", ExpectActualScanner.platformFromPath("/p/src/nativeMain/kotlin/A.kt"))
    }

    @Test
    fun `agp style src main resolves to android only when enabled`() {
        assertEquals("Android", ExpectActualScanner.platformFromPath("/p/lib/src/main/kotlin/A.kt", srcMainIsAndroid = true))
        assertNull(ExpectActualScanner.platformFromPath("/p/lib/src/main/kotlin/A.kt", srcMainIsAndroid = false))
    }

    @Test
    fun `commonMain and unknown paths resolve to null`() {
        assertNull(ExpectActualScanner.platformFromPath("/p/src/commonMain/kotlin/A.kt"))
        assertNull(ExpectActualScanner.platformFromPath("/p/whatever/A.kt"))
    }

    @Test
    fun `package names containing platform words do not confuse path detection`() {
        // "ios" appears in the package path but not as a source-set directory.
        assertNull(ExpectActualScanner.platformFromPath("/p/src/commonMain/kotlin/com/example/iosstuff/A.kt"))
    }

    // ── platformFromModule ───────────────────────────────────────────────────

    @Test
    fun `module suffixes resolve to platforms`() {
        assertEquals("Android", ExpectActualScanner.platformFromModule("MyApp.core.androidMain"))
        assertEquals("Android", ExpectActualScanner.platformFromModule("MyApp.core.main"))
        assertEquals("iOS", ExpectActualScanner.platformFromModule("MyApp.core.iosMain"))
        assertEquals("iOS (arm64)", ExpectActualScanner.platformFromModule("MyApp.core.iosArm64Main"))
        assertEquals("JVM", ExpectActualScanner.platformFromModule("MyApp.core.jvmMain"))
    }

    @Test
    fun `commonMain and unrecognised modules resolve to null`() {
        assertNull(ExpectActualScanner.platformFromModule("MyApp.core.commonMain"))
        assertNull(ExpectActualScanner.platformFromModule("MyApp.core.analytics"))
    }

    // ── moduleDisplayName ────────────────────────────────────────────────────

    @Test
    fun `module display name strips source-set suffix`() {
        assertEquals("MyApp.core.analytics", ExpectActualScanner.moduleDisplayName("MyApp.core.analytics.commonMain"))
        assertEquals("MyApp.core.analytics", ExpectActualScanner.moduleDisplayName("MyApp.core.analytics.main"))
        assertEquals("MyApp.core.analytics", ExpectActualScanner.moduleDisplayName("MyApp.core.analytics.unitTest"))
        assertEquals("MyApp.core.analytics", ExpectActualScanner.moduleDisplayName("MyApp.core.analytics"))
    }

    // ── isExcluded ───────────────────────────────────────────────────────────

    @Test
    fun `generated directories are always excluded`() {
        val settings = ExpectActualSettings.State()
        assertTrue(ExpectActualScanner.isExcluded("/p/build/generated/Res.kt", settings))
        assertFalse(ExpectActualScanner.isExcluded("/p/src/commonMain/kotlin/A.kt", settings))
    }

    @Test
    fun `user patterns extend the exclusion list case-insensitively`() {
        val settings = ExpectActualSettings.State().apply {
            excludedPathPatterns = mutableListOf("/Sample/", "  ") // blank entries ignored
        }
        assertTrue(ExpectActualScanner.isExcluded("/p/src/sample/kotlin/A.kt", settings))
        assertFalse(ExpectActualScanner.isExcluded("/p/src/commonMain/kotlin/A.kt", settings))
    }
}
