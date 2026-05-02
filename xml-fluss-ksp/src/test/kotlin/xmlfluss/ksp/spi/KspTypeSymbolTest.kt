@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package xmlfluss.ksp.spi

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors `xmlfluss.apt.spi.AptTypeSymbolTest.publicNoArgConverter_resolvesProducedTypeArg`.
 * Validates the KSP-side TypeSymbol behaves identically to the APT side for the three
 * cases CoreClassifier needs (Task 5):
 *   - public no-arg ctor + Converter<String> → resolves the produced TypeRef
 *   - private/parameterized ctor → hasPublicNoArgConstructor() is false
 *   - unknown FQN → lookupType returns null
 */
class KspTypeSymbolTest {

    @Test
    fun `public no-arg converter resolves produced type arg`() {
        val errors = mutableListOf<String>()

        val source = SourceFile.kotlin(
            "Converters.kt",
            """
            package p

            import xmlfluss.Converter
            import xmlfluss.Location
            import xmlfluss.XmlRecord

            class OkConverter : Converter<String> {
                override fun convert(raw: String, loc: Location): String = raw
            }

            class NoCtorConverter(val seed: String) : Converter<Int> {
                override fun convert(raw: String, loc: Location): Int = raw.toInt()
            }

            // A trivial annotated record so the processor's annotation trigger fires
            // and KSP runs at least once. Mirrors the Anchor record in AptTypeSymbolTest.
            @XmlRecord(path = "a")
            data class Anchor(val ignored: String)
            """.trimIndent()
        )

        val provider = SymbolProcessorProvider { env ->
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val sp = KspSymbolProvider(resolver, env.logger)

                    val ok = sp.lookupType("p.OkConverter")
                    if (ok == null) { errors += "OkConverter lookup returned null"; return emptyList() }
                    if (ok.qualifiedName() != "p.OkConverter")
                        errors += "OkConverter qualifiedName: ${ok.qualifiedName()}"
                    if (!ok.hasPublicNoArgConstructor())
                        errors += "OkConverter should have public no-arg ctor"
                    val produced = ok.typeArgumentOf("xmlfluss.Converter", 0)
                    if (produced == null) errors += "Converter<T> arg should resolve"
                    else if (produced.qualifiedName() != "java.lang.String")
                        errors += "produced type: ${produced.qualifiedName()}"

                    if (ok.typeArgumentOf("xmlfluss.Converter", 1) != null)
                        errors += "out-of-range index should return null"
                    if (ok.typeArgumentOf("java.lang.Runnable", 0) != null)
                        errors += "unrelated supertype should return null"

                    val noCtor = sp.lookupType("p.NoCtorConverter")
                    if (noCtor == null) { errors += "NoCtorConverter lookup returned null"; return emptyList() }
                    if (noCtor.hasPublicNoArgConstructor())
                        errors += "NoCtorConverter ctor takes a parameter; should be false"
                    val noCtorArg = noCtor.typeArgumentOf("xmlfluss.Converter", 0)
                    if (noCtorArg == null) errors += "NoCtorConverter Converter<T> arg should resolve"
                    // KspModelToCore maps `kotlin.Int` to a primitive `int` TypeRef. APT's
                    // analog test asserts `java.lang.Integer` because Java's `Converter<Integer>`
                    // is reified through the boxed type. The divergence is by design — both
                    // sides preserve the host language's natural numeric encoding; CoreClassifier
                    // operates on the resulting TypeRef without caring which side produced it.
                    else if (noCtorArg.qualifiedName() != "int")
                        errors += "NoCtorConverter produced: ${noCtorArg.qualifiedName()}"

                    if (sp.lookupType("p.DoesNotExist") != null)
                        errors += "unknown FQN should return null"

                    return emptyList()
                }
            }
        }

        val result = KotlinCompilation().apply {
            sources = listOf(source)
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += provider
            }
            messageOutputStream = System.out
        }.compile()

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue(errors.isEmpty(), "Assertion failures inside process():\n${errors.joinToString("\n")}")
    }
}
