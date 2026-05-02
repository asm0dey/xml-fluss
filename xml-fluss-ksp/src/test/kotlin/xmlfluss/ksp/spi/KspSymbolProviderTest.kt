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

class KspSymbolProviderTest {

    // All assertions run inside process() while KSP lifetime is still valid.
    // After compile() returns the Analysis API PSI lifetime expires, so
    // we capture pass/fail as a single Boolean rather than a RecordSymbol.
    @Test
    fun `roundTrip data class with String and List components`() {
        val errors = mutableListOf<String>()

        val source = SourceFile.kotlin(
            "Demo.kt",
            """
            package p

            import xmlfluss.XmlRecord
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild

            @XmlRecord(path = "demo")
            data class Demo(
                @XmlAttr(name = "id") val id: String,
                @XmlChild(path = "item") val items: List<String>,
            )
            """.trimIndent()
        )

        val provider = SymbolProcessorProvider { env ->
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val sp = KspSymbolProvider(resolver, env.logger)
                    val r = sp.lookupRecord("p.Demo")
                    if (r == null) { errors += "lookupRecord returned null"; return emptyList() }

                    if (r.packageName() != "p") errors += "packageName: ${r.packageName()}"
                    if (r.simpleName() != "Demo") errors += "simpleName: ${r.simpleName()}"
                    if (r.declaredPath() != "demo") errors += "declaredPath: ${r.declaredPath()}"
                    if (r.components().size != 2) {
                        errors += "components.size: ${r.components().size}"; return emptyList()
                    }

                    val id = r.components()[0]
                    if (id.name() != "id") errors += "id.name: ${id.name()}"
                    if (id.isList()) errors += "id.isList should be false"
                    // Kotlin's type system is the source of truth for nullability:
                    // `val id: String` (no `?`) is unambiguously non-null regardless of
                    // any surrounding `@NullMarked` scope. Replaying the APT scope walk
                    // would override Kotlin's own answer (PR 4 Task 8 drift fix).
                    if (id.nullable()) errors += "id.nullable should be false (Kotlin String is non-null)"
                    if (id.type().qualifiedName() != "java.lang.String")
                        errors += "id.type: ${id.type().qualifiedName()}"
                    if (!id.annotations().has("xmlfluss.XmlAttr"))
                        errors += "id missing XmlAttr"
                    if (id.annotations().stringValue("xmlfluss.XmlAttr", "name") != "id")
                        errors += "id attr name: ${id.annotations().stringValue("xmlfluss.XmlAttr", "name")}"

                    val items = r.components()[1]
                    if (items.name() != "items") errors += "items.name: ${items.name()}"
                    if (!items.isList()) errors += "items.isList should be true"
                    if (items.type().qualifiedName() != "java.util.List")
                        errors += "items.type: ${items.type().qualifiedName()}"
                    if (items.elementType().qualifiedName() != "java.lang.String")
                        errors += "items.elementType: ${items.elementType().qualifiedName()}"
                    if (items.annotations().stringValue("xmlfluss.XmlChild", "path") != "item")
                        errors += "items path: ${items.annotations().stringValue("xmlfluss.XmlChild", "path")}"

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

    /**
     * Mirrors `AptComponentSymbol.nullable()` lines 70-73: an explicit
     * `@org.jspecify.annotations.NonNull` overrides the surrounding `@NullMarked`
     * scope rule and the Kotlin `?` rule alike. Inside a `@NullMarked` package the
     * default is already non-null, but the explicit annotation must still take
     * precedence so the SPI behaves identically when callers strip the package
     * annotation and rely on the field-level signal.
     */
    @Test
    fun `explicit @NonNull on field inside @NullMarked stays non-null`() {
        val errors = mutableListOf<String>()

        val source = SourceFile.kotlin(
            "Marked.kt",
            """
            @file:org.jspecify.annotations.NullMarked
            package marked

            import xmlfluss.XmlRecord
            import xmlfluss.XmlAttr
            import org.jspecify.annotations.NonNull

            @XmlRecord(path = "doc")
            data class Marked(
                @XmlAttr(name = "id") val id: @NonNull String,
            )
            """.trimIndent()
        )

        val provider = SymbolProcessorProvider { env ->
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val sp = KspSymbolProvider(resolver, env.logger)
                    val r = sp.lookupRecord("marked.Marked")
                    if (r == null) { errors += "lookupRecord returned null"; return emptyList() }
                    val id = r.components().firstOrNull { it.name() == "id" }
                    if (id == null) { errors += "id not found"; return emptyList() }
                    if (id.nullable()) errors += "id should be non-null (explicit @NonNull)"
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

    /**
     * Mirrors the inverse APT branch (line 73): explicit
     * `@org.jspecify.annotations.Nullable` reports nullable even when neither the
     * Kotlin `?` rule nor the scope walk would produce that result. Outside any
     * `@NullMarked` scope the default is already nullable, so the test pins down
     * that the explicit annotation does not flip the answer in the wrong direction.
     */
    @Test
    fun `explicit @Nullable on field outside @NullMarked stays nullable`() {
        val errors = mutableListOf<String>()

        val source = SourceFile.kotlin(
            "Plain.kt",
            """
            package plain

            import xmlfluss.XmlRecord
            import xmlfluss.XmlAttr
            import org.jspecify.annotations.Nullable

            @XmlRecord(path = "doc")
            data class Plain(
                @XmlAttr(name = "id") val id: @Nullable String,
            )
            """.trimIndent()
        )

        val provider = SymbolProcessorProvider { env ->
            object : SymbolProcessor {
                override fun process(resolver: Resolver): List<KSAnnotated> {
                    val sp = KspSymbolProvider(resolver, env.logger)
                    val r = sp.lookupRecord("plain.Plain")
                    if (r == null) { errors += "lookupRecord returned null"; return emptyList() }
                    val id = r.components().firstOrNull { it.name() == "id" }
                    if (id == null) { errors += "id not found"; return emptyList() }
                    if (!id.nullable()) errors += "id should be nullable (explicit @Nullable)"
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
