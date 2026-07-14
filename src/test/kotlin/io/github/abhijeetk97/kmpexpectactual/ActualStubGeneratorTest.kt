package io.github.abhijeetk97.kmpexpectactual

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Tests for the actual-stub text generation behind the inspection quick fix
 * (issue #16). Uses real Kotlin PSI parsed from text.
 */
class ActualStubGeneratorTest : BasePlatformTestCase() {

    private fun firstDeclaration(text: String): KtNamedDeclaration {
        val file = myFixture.configureByText("Expects.kt", text) as KtFile
        return file.declarations.first() as KtNamedDeclaration
    }

    fun `test top-level function`() {
        val stub = ActualStubGenerator.generate(
            firstDeclaration("expect fun platformName(name: String): String"),
        )
        assertEquals(
            "actual fun platformName(name: String): String = TODO(\"Not yet implemented\")",
            stub,
        )
    }

    fun `test suspend modifier is kept`() {
        val stub = ActualStubGenerator.generate(
            firstDeclaration("expect suspend fun load(id: Int): List<String>"),
        )
        assertEquals(
            "actual suspend fun load(id: Int): List<String> = TODO(\"Not yet implemented\")",
            stub,
        )
    }

    fun `test val property`() {
        val stub = ActualStubGenerator.generate(firstDeclaration("expect val version: Int"))
        assertEquals("actual val version: Int = TODO(\"Not yet implemented\")", stub)
    }

    fun `test var property`() {
        val stub = ActualStubGenerator.generate(firstDeclaration("expect var counter: Long"))
        assertEquals("actual var counter: Long = TODO(\"Not yet implemented\")", stub)
    }

    fun `test class with constructor and members`() {
        val stub = ActualStubGenerator.generate(
            firstDeclaration(
                """
                expect class Clock(zone: String) {
                    fun now(): Long
                    val zone: String
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            """
            actual class Clock actual constructor(zone: String) {
                actual fun now(): Long = TODO("Not yet implemented")

                actual val zone: String = TODO("Not yet implemented")
            }
            """.trimIndent(),
            stub,
        )
    }

    fun `test class without body`() {
        val stub = ActualStubGenerator.generate(firstDeclaration("expect class Marker"))
        assertEquals("actual class Marker", stub)
    }

    fun `test object`() {
        val stub = ActualStubGenerator.generate(
            firstDeclaration(
                """
                expect object Registry {
                    fun lookup(key: String): Int
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            """
            actual object Registry {
                actual fun lookup(key: String): Int = TODO("Not yet implemented")
            }
            """.trimIndent(),
            stub,
        )
    }

    fun `test interface members have no bodies`() {
        val stub = ActualStubGenerator.generate(
            firstDeclaration(
                """
                expect interface Api {
                    fun call(): Int
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            """
            actual interface Api {
                actual fun call(): Int
            }
            """.trimIndent(),
            stub,
        )
    }

    fun `test enum entries are re-declared`() {
        val stub = ActualStubGenerator.generate(
            firstDeclaration("expect enum class Color { RED, GREEN }"),
        )
        assertEquals(
            """
            actual enum class Color {
                RED,
                GREEN
            }
            """.trimIndent(),
            stub,
        )
    }

    fun `test internal visibility is kept`() {
        val stub = ActualStubGenerator.generate(
            firstDeclaration("internal expect fun secret(): Int"),
        )
        assertEquals("actual internal fun secret(): Int = TODO(\"Not yet implemented\")", stub)
    }
}
