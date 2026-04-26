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
            data class Inner(@XmlChild(path = "a:x") val x: String)

            @XmlNs(prefix = "a", uri = "urn:two")
            @XmlRecord(path = "//root")
            data class Outer(@XmlChild(path = "inner") val inner: Inner)
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
            data class Circle(@XmlChild(path = "r") val r: Int) : Shape()
            @XmlSubtype(name = "circle")
            data class AnotherCircle(@XmlChild(path = "r") val r: Int) : Shape()

            @XmlRecord(path = "//doc")
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
            data class C1(@XmlChild(path = "r") val r: Int) : Shape()
            @XmlSubtype(name = "circle")
            data class C2(@XmlChild(path = "r") val r: Int) : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "shape") val shape: Shape)
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
            data class C(@XmlChild(path = "r") val r: Int) : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "shape") val shape: Shape)
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
            data class C(@XmlChild(path = "r") val r: Int) : Shape()

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
            class Doc(@XmlChild(path = "x") val x: String)
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "d")
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "x") val x: String,
                val y: String,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "has no @XmlAttr/@XmlChild/@XmlText/@XmlMap")
    }

    @Test
    fun trieMixesTextAndNestedAtSameElement() {
        val src = SourceFile.kotlin(
            "MixTextNested.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Inner(@XmlText val body: String)

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "x") val x: String,
                @XmlChild(path = "x") val xn: Inner,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "cannot mix text/nested/descend at same element")
    }

    @Test
    fun trieMultipleNonListTextFieldsAtSameElement() {
        val src = SourceFile.kotlin(
            "TwoNonListTexts.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "x") val a: String,
                @XmlChild(path = "x") val b: String,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "multiple non-list text fields")
    }

    @Test
    fun trieMultipleNonListNestedFieldsAtSameElement() {
        val src = SourceFile.kotlin(
            "TwoNonListNested.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class A(@XmlText val v: String)
            data class B(@XmlText val v: String)

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "x") val a: A,
                @XmlChild(path = "x") val b: B,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "multiple non-list nested fields")
    }

    @Test
    fun trieMixListAndNonListNested() {
        val src = SourceFile.kotlin(
            "MixListNested.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class A(@XmlText val v: String)

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "x") val a: A,
                @XmlChild(path = "x") val xs: List<A>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "cannot mix list and non-list nested fields")
    }

    @Test
    fun unboundNamespacePrefixInChildPath() {
        val src = SourceFile.kotlin(
            "BadPrefix.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "foo:bar") val v: String)
            """.trimIndent(),
        )
        assertFailsWith(src, "unbound NS prefix 'foo'")
    }

    @Test
    fun emptyLocalInQNameRejected() {
        val src = SourceFile.kotlin(
            "EmptyLocal.kt",
            """
            package sample
            import xmlfluss.XmlNs
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlNs(prefix = "p", uri = "urn:p")
            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "p:") val v: String)
            """.trimIndent(),
        )
        assertFailsWith(src, "bad qname")
    }

    @Test
    fun descendantHeadCollidesWithDirectChild() {
        val src = SourceFile.kotlin(
            "DescendVsDirect.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "x") val a: String,
                @XmlChild(path = "//x") val b: String,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "target the same head element")
    }

    @Test
    fun mapEntryClashesWithChildHead() {
        val src = SourceFile.kotlin(
            "MapVsChild.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "e") val s: String,
                @XmlMap(entry = "e", key = "@k", value = ".") val m: Map<String, String>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "clashes with another @XmlChild")
    }

    @Test
    fun listFieldNullableRejected() {
        val src = SourceFile.kotlin(
            "NullableList.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "x") val xs: List<String>?)
            """.trimIndent(),
        )
        assertFailsWith(src, "must not be nullable")
    }

    @Test
    fun multipleBindingsOnOneField() {
        val src = SourceFile.kotlin(
            "TwoBindings.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(@XmlAttr @XmlChild(path = "x") val v: String)
            """.trimIndent(),
        )
        assertFailsWith(src, "multiple xml bindings")
    }

    @Test
    fun mapWithoutXmlMapAnnotation() {
        val src = SourceFile.kotlin(
            "BareMap.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "m") val m: Map<String, String>)
            """.trimIndent(),
        )
        assertFailsWith(src, "lacks @XmlMap")
    }

    @Test
    fun unsupportedFieldTypeRejected() {
        val src = SourceFile.kotlin(
            "BadType.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            class NotData(val v: String)

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "v") val v: NotData)
            """.trimIndent(),
        )
        assertFailsWith(src, "Unsupported type")
    }

    @Test
    fun xmlFormatOnPolymorphicFieldRejected() {
        val src = SourceFile.kotlin(
            "FormatOnPoly.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlFormat
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class Circle(@XmlAttr val r: Int) : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild @XmlFormat(pattern = "x") val s: Shape,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "@XmlFormat / @XmlConverter not supported on polymorphic field")
    }

    @Test
    fun nestedDataClassWithXmlAttrRejected() {
        val src = SourceFile.kotlin(
            "NestedAsAttr.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Inner(@XmlText val v: String)

            @XmlRecord(path = "//doc")
            data class Doc(@XmlAttr(name = "x") val x: Inner)
            """.trimIndent(),
        )
        assertFailsWith(src, "Nested data-class field 'x' must use @XmlChild")
    }

    @Test
    fun mapValueListWithNullableElementRejected() {
        val src = SourceFile.kotlin(
            "MapNullableListElem.kt",
            """
            package sample
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlMap(entry = "e", key = "@k", value = "v")
                val m: Map<String, List<String?>>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "nullable element inside List")
    }

    @Test
    fun mapValueAsNestedDataClassViaAttrPathRejected() {
        val src = SourceFile.kotlin(
            "MapNestedViaAttr.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Detail(@XmlAttr val k: String, @XmlText val v: String)

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlMap(entry = "e", key = "@k", value = "@v")
                val m: Map<String, Detail>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "nested data-class type requires an element path")
    }

    @Test
    fun mapUnsupportedValueTypeRejected() {
        val src = SourceFile.kotlin(
            "MapUnsupportedVal.kt",
            """
            package sample
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            class NotData(val v: String)

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlMap(entry = "e", key = "@k", value = ".")
                val m: Map<String, NotData>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "unsupported type")
    }

    @Test
    fun nestedDataClassMultipleXmlTextRejected() {
        val src = SourceFile.kotlin(
            "NestedMultiText.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Inner(
                @XmlText val a: String,
                @XmlText val b: String,
            )

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "inner") val inner: Inner)
            """.trimIndent(),
        )
        assertFailsWith(src, "multiple @XmlText fields not allowed")
    }

    @Test
    fun childPathInvalidSyntaxLeadingSlashRejected() {
        val src = SourceFile.kotlin(
            "BadPathLeadingSlash.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "/foo") val x: String)
            """.trimIndent(),
        )
        assertFailsWith(src, "invalid syntax")
    }

    @Test
    fun descendantHeadIsAttributeRejected() {
        val src = SourceFile.kotlin(
            "DescendAttrHead.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "//@x") val x: String)
            """.trimIndent(),
        )
        assertFailsWith(src, "descendant axis head must be an element")
    }

    @Test
    fun sealedPolymorphicWithoutSubclassesRejected() {
        val src = SourceFile.kotlin(
            "PolyEmpty.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord

            @XmlPolymorphic
            sealed class Shape

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild val s: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "has no subclasses")
    }

    @Test
    fun polySubtypeNotDataClassRejected() {
        val src = SourceFile.kotlin(
            "PolyNonData.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic
            sealed class Shape
            @XmlSubtype(name = "circle")
            class Circle : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild val s: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "must be a data class")
    }

    @Test
    fun polySubtypeMissingXmlSubtypeRejected() {
        val src = SourceFile.kotlin(
            "PolyNoSubtype.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class Circle(@XmlChild(path = "r") val r: Int) : Shape()
            data class Naked(@XmlChild(path = "n") val n: Int) : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild val s: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "missing @XmlSubtype")
    }

    @Test
    fun polyTagModeWithNonEmptyPathRejected() {
        val src = SourceFile.kotlin(
            "PolyTagWithPath.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class Circle(@XmlAttr val r: Int) : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "shape") val s: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "tag-mode @XmlChild path must be empty")
    }

    @Test
    fun polyDiscriminatorContainsSlashRejected() {
        val src = SourceFile.kotlin(
            "PolyBadDiscSlash.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic(discriminator = "@a/b")
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class Circle(@XmlAttr val r: Int) : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "shape") val s: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "bad discriminator")
    }

    @Test
    fun polyAttrModePathNotDirectChildRejected() {
        val src = SourceFile.kotlin(
            "PolyAttrBadPath.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlPolymorphic
            import xmlfluss.XmlRecord
            import xmlfluss.XmlSubtype

            @XmlPolymorphic(discriminator = "@kind")
            sealed class Shape
            @XmlSubtype(name = "circle")
            data class Circle(@XmlAttr val r: Int) : Shape()

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "wrap/shape") val s: Shape)
            """.trimIndent(),
        )
        assertFailsWith(src, "attr-mode @XmlChild path must be a single direct-child element")
    }

    @Test
    fun positionalPredicateInsideXmlChildRejectedWithFixSuggestion() {
        // `x/i[@k='v'][2]` exercises the user's exact shape: chained attr + positional inside
        // an @XmlChild path. Validator must reject AND surface a fix hint.
        val src = SourceFile.kotlin(
            "IndexInChild.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(@XmlChild(path = "x/i[@k='v'][2]") val s: String?)
            """.trimIndent(),
        )
        assertFailsWith(
            src,
            "positional predicate [2] is not supported inside @XmlChild",
            "Move the positional filter to @XmlRecord",
            "collect siblings into a List<T>",
        )
    }

    @Test
    fun xmlFormatOnConverterFieldRejectedViaMap() {
        val src = SourceFile.kotlin(
            "MapWithFormat.kt",
            """
            package sample
            import xmlfluss.XmlFormat
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlMap(entry = "e", key = "@k", value = ".")
                @XmlFormat(pattern = "yyyy")
                val m: Map<String, String>,
            )
            """.trimIndent(),
        )
        assertFailsWith(src, "@XmlFormat / @XmlConverter not supported on @XmlMap field")
    }
}
