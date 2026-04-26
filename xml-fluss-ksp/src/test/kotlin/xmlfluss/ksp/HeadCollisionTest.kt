@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package xmlfluss.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadCollisionTest {

    @Test
    fun directAndDescendantOnSameHeadFails() {
        val source = SourceFile.kotlin(
            "Foo.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//foo")
            data class Foo(
                @XmlChild(path = "bar") val a: String,
                @XmlChild(path = "//bar") val b: String,
            )
            """.trimIndent(),
        )

        val result = KotlinCompilation().apply {
            sources = listOf(source)
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += XmlDslProcessorProvider()
            }
            messageOutputStream = System.out
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue(
            "target the same head element" in result.messages,
            "expected 'target the same head element' in output, got:\n${result.messages}",
        )
    }
}
