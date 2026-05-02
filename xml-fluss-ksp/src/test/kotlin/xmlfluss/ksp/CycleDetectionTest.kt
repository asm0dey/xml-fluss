@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package xmlfluss.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CycleDetectionTest {

    @Test
    fun selfReferentialNestedDataClassReportsCycle() {
        val source = SourceFile.kotlin(
            "Cycle.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            data class A(@XmlChild(path = "b") val b: B)
            data class B(@XmlChild(path = "a") val a: A)

            @XmlRecord(path = "//root")
            data class Root(@XmlChild(path = "a") val a: A)
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
        // CoreClassifier (single source of truth post-PR4) reports cycles as
        // "circular nested record reference at '<field>' (cycle involves '<fqn>')".
        assertTrue("circular nested record reference" in result.messages)
        assertTrue("cycle involves" in result.messages)
    }
}
