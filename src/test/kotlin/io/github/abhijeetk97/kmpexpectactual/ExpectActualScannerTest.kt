package io.github.abhijeetk97.kmpexpectactual

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform tests for the scanner: real Kotlin PSI in an in-memory project
 * (issue #8). BasePlatformTestCase runs on the EDT with read access, so the
 * scanner's read-action contract is satisfied implicitly.
 *
 * The light fixture puts files under a single source root, so platform
 * detection exercises the PATH heuristic (`/androidMain/`, `/iosMain/`, …),
 * which is also the primary signal in real projects.
 */
class ExpectActualScannerTest : BasePlatformTestCase() {

    fun `test project with no kotlin files needs gradle sync`() {
        assertEquals(ProjectState.GRADLE_SYNC_REQUIRED, ExpectActualScanner.detectProjectState(project))
    }

    fun `test kotlin project without commonMain is not kmp`() {
        myFixture.addFileToProject("src/A.kt", "fun main() {}")
        assertEquals(ProjectState.NOT_KMP, ExpectActualScanner.detectProjectState(project))
    }

    fun `test commonMain source set marks project as kmp`() {
        myFixture.addFileToProject("commonMain/kotlin/A.kt", "fun shared() {}")
        assertEquals(ProjectState.KMP, ExpectActualScanner.detectProjectState(project))
    }

    fun `test expects are paired with actuals by fq name and platform`() {
        myFixture.addFileToProject(
            "commonMain/kotlin/com/example/Platform.kt",
            """
            package com.example

            expect fun platformName(): String
            expect class Clock
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "androidMain/kotlin/com/example/Platform.kt",
            """
            package com.example

            actual fun platformName(): String = "Android"
            actual class Clock
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "iosMain/kotlin/com/example/Platform.kt",
            """
            package com.example

            actual fun platformName(): String = "iOS"
            """.trimIndent(),
        )

        val coverage = ExpectActualScanner.computeCoverage(project)
            .associateBy { it.expect.fqName.asString() }

        assertEquals(setOf("com.example.platformName", "com.example.Clock"), coverage.keys)

        val platformName = coverage.getValue("com.example.platformName")
        assertEquals(setOf("Android", "iOS"), platformName.knownPlatforms)
        assertTrue(platformName.isComplete)

        val clock = coverage.getValue("com.example.Clock")
        assertEquals(setOf("iOS"), clock.missingPlatforms)
        assertFalse(clock.isComplete)
        assertEquals("class", clock.expect.kind)
    }

    fun `test expect members nested in classes are found`() {
        myFixture.addFileToProject(
            "commonMain/kotlin/com/example/Outer.kt",
            """
            package com.example

            class Outer {
                expect fun inner(): Int
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "androidMain/kotlin/com/example/Anchor.kt",
            """
            package com.example

            actual fun anchor(): Int = 1
            """.trimIndent(),
        )

        val expects = ExpectActualScanner.findExpects(project).map { it.fqName.asString() }
        assertContainsElements(expects, "com.example.Outer.inner")
    }

    fun `test generated files are excluded from the scan`() {
        myFixture.addFileToProject(
            "commonMain/kotlin/com/example/Real.kt",
            "package com.example\n\nexpect fun real(): Int",
        )
        myFixture.addFileToProject(
            "build/generated/compose/Res.kt",
            "package com.example.gen\n\nexpect fun fake(): Int",
        )

        val expects = ExpectActualScanner.findExpects(project).map { it.fqName.asString() }
        assertContainsElements(expects, "com.example.real")
        assertDoesntContain(expects, "com.example.gen.fake")
    }

    fun `test user-configured exclusions are honoured`() {
        ExpectActualSettings.getInstance(project).state.excludedPathPatterns =
            mutableListOf("/sandbox/")
        try {
            myFixture.addFileToProject(
                "commonMain/kotlin/com/example/Real.kt",
                "package com.example\n\nexpect fun real(): Int",
            )
            myFixture.addFileToProject(
                "commonMain/kotlin/sandbox/Playground.kt",
                "package sandbox\n\nexpect fun playground(): Int",
            )

            val expects = ExpectActualScanner.findExpects(project).map { it.fqName.asString() }
            assertContainsElements(expects, "com.example.real")
            assertDoesntContain(expects, "sandbox.playground")
        } finally {
            ExpectActualSettings.getInstance(project).state.excludedPathPatterns = mutableListOf()
        }
    }

    fun `test unknown source sets never pollute known platforms`() {
        myFixture.addFileToProject(
            "commonMain/kotlin/com/example/P.kt",
            "package com.example\n\nexpect fun p(): Int",
        )
        // An actual in an unrecognisable location: no known source-set directory.
        myFixture.addFileToProject(
            "somewhere/else/P.kt",
            "package com.example\n\nactual fun p(): Int = 1",
        )
        myFixture.addFileToProject(
            "androidMain/kotlin/com/example/P2.kt",
            "package com.example\n\nactual fun p2(): Int = 2",
        )

        val coverage = ExpectActualScanner.computeCoverage(project).single()
        // Only "Android" (from the recognised androidMain path) may appear;
        // the unrecognised location must be dropped, not invent a platform.
        assertEquals(setOf("Android"), coverage.knownPlatforms)
    }
}
