@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package xmlfluss.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for [XmlDslProcessor]'s vError / vRequire validation sites. Each test
 * compiles a tiny Kotlin source that should be rejected, and asserts both that compilation fails
 * and that the diagnostic substring associated with the rule appears in the messages stream.
 */
class XmlDslProcessorErrorTest {

    private fun assertFailsWith(source: SourceFile, vararg needles: String) {
        val result = KotlinCompilation().apply {
            sources = listOf(source)
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += XmlDslProcessorProvider()
            }
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (n in needles) {
            assertTrue(n in result.messages, "expected '$n' in output, got:\n${result.messages}")
        }
    }

    @Test
    fun nestedRedeclaresXmlNsPrefixWithDifferentUri() {
        val src = SourceFile.kotlin(
            "NestedNsConflict.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlNs
            import xmlfluss.XmlRecord

            @XmlNs(prefix = "a", uri = "urn:one")
            data class Inner(@XmlChild("a:x") val x: String)

            @XmlNs(prefix = "a", uri = "urn:two")
            @XmlRecord("//root")
            data class Outer(@XmlChild("inner") val inner: Inner)
            """.trimIndent(),
        )
        assertFailsWith(src, "redeclares @XmlNs prefix")
    }

    @Test
    fun polymorphicTagModeDuplicateSubtypeNames() {
        val src = SourceFile.kotlin(
            "PolyDupTag.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class Circle(@XmlChild("r") val r: Int) : Shape()
            @XmlSubtype(name = "circle")
            data class AnotherCircle(@XmlChild("r") val r: Int) : Shape()

            @XmlRecord("//doc")
            data class Doc(@XmlChild val shape: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "duplicate @XmlSubtype tags")
    }

    @Test
    fun polymorphicAttrModeDuplicateValues() {
        val src = SourceFile.kotlin(
            "PolyDupAttr.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic(discriminator = "@kind")
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class C1(@XmlChild("r") val r: Int) : Shape()
            @XmlSubtype(name = "circle")
            data class C2(@XmlChild("r") val r: Int) : Shape()

            @XmlRecord("//doc")
            data class Doc(@XmlChild("shape") val shape: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "duplicate @XmlSubtype values")
    }

    @Test
    fun polymorphicDiscriminatorMissingAtSign() {
        val src = SourceFile.kotlin(
            "PolyBadDiscriminator.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic(discriminator = "kind")
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class C(@XmlChild("r") val r: Int) : Shape()

            @XmlRecord("//doc")
            data class Doc(@XmlChild("shape") val shape: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "discriminator must start with '@'")
    }

    @Test
    fun polymorphicAttrModeMissingWrapPath() {
        val src = SourceFile.kotlin(
            "PolyAttrNoWrap.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic(discriminator = "@kind")
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class C(@XmlChild("r") val r: Int) : Shape()

            @XmlRecord("//doc")
            data class Doc(@XmlChild val shape: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "attr-mode @XmlChild requires the wrapping element path")
    }

    @Test
    fun xmlMapEntryMultiSegmentRejected() {
        val src = SourceFile.kotlin(
            "MapMultiSegEntry.kt",
            """
            package sample
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Doc(
                @XmlMap(entry = "wrap/item", key = "@k", value = ".")
                val m: Map<String, String>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "must be a single element name")
    }

    @Test
    fun xmlMapValueListOfListRejected() {
        val src = SourceFile.kotlin(
            "MapListOfList.kt",
            """
            package sample
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Doc(
                @XmlMap(entry = "e", key = "@k", value = "v")
                val m: Map<String, List<List<String>>>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "List<List<?>> not supported")
    }

    @Test
    fun multipleXmlTextFieldsRejected() {
        val src = SourceFile.kotlin(
            "TwoTexts.kt",
            """
            package sample
            import xmlfluss.XmlText
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Doc(
                @XmlText val a: String,
                @XmlText val b: String,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "multiple @XmlText fields not allowed")
    }

    @Test
    fun xmlTextOnListRejected() {
        val src = SourceFile.kotlin(
            "TextOnList.kt",
            """
            package sample
            import xmlfluss.XmlText
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Doc(@XmlText val xs: List<String>)
            """.trimIndent(),
        )
        assertFailsWith(src, "@XmlText on List unsupported")
    }

    @Test
    fun nonDataClassWithXmlRecordRejected() {
        val src = SourceFile.kotlin(
            "NotData.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            class Doc(@XmlChild("x") val x: String)
            """.trimIndent(),
        )
        assertFailsWith(src, "@XmlRecord requires data class")
    }

    @Test
    fun xmlMapNestedMapAsKeyTypeRejected() {
        val src = SourceFile.kotlin(
            "MapKeyIsMap.kt",
            """
            package sample
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Foo(
                @XmlMap(entry = "e", key = ".", value = "v")
                val m: Map<Map<String, String>, String>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "nested Map<,> not supported")
    }

    @Test
    fun xmlFormatAndXmlConverterOnSameFieldRejected() {
        val src = SourceFile.kotlin(
            "FormatAndConverter.kt",
            """
            package sample
            import xmlfluss.Converter
            import xmlfluss.Location
            import xmlfluss.XmlChild
            import xmlfluss.XmlConverter
            import xmlfluss.XmlFormat
            import xmlfluss.XmlRecord

            class MyConv : Converter<String> {
                override fun convert(raw: String, loc: Location): String = raw
            }

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("d")
                @XmlFormat(pattern = "yyyy")
                @XmlConverter(cls = MyConv::class)
                val d: String,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "both @XmlFormat and @XmlConverter")
    }

    @Test
    fun fieldWithoutBindingAnnotationRejected() {
        val src = SourceFile.kotlin(
            "NoBinding.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("x") val x: String,
                val y: String,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "has no @XmlAttr/@XmlChild/@XmlText/@XmlMap")
    }
}
