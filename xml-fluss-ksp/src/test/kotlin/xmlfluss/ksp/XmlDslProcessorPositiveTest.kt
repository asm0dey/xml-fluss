@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package xmlfluss.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Positive-path coverage for [XmlDslProcessor]. Each test compiles a tiny annotated source and
 * asserts the compilation succeeds — exercising classifyParam, parseChildPath, buildPolyChild,
 * coerceForType, typeNameFor, ensureNested, and the emit* family. JaCoCo records the processor's
 * in-test-JVM execution; the parallel sample modules in :xml-fluss-test run KSP in the compiler
 * subprocess and are invisible to the agent.
 */
class XmlDslProcessorPositiveTest {

    private fun assertCompilesOk(source: SourceFile) {
        val result = KotlinCompilation().apply {
            sources = listOf(source)
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += XmlDslProcessorProvider()
            }
            messageOutputStream = System.out
        }.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun simpleAttrChildText() {
        val src = SourceFile.kotlin(
            "Simple.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            @XmlRecord("//doc")
            data class Inner(@XmlText val body: String)

            @XmlRecord("//doc")
            data class Doc(
                @XmlAttr("id") val id: String,
                @XmlAttr val href: String,
                @XmlChild("title") val title: String,
                @XmlChild val name: String,
                @XmlChild("inner") val inner: Inner,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun bigDecimalAndTemporalsAndScalars() {
        val src = SourceFile.kotlin(
            "Numbers.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlFormat
            import xmlfluss.XmlRecord
            import java.math.BigDecimal
            import java.time.Instant
            import java.time.LocalDate
            import java.time.LocalDateTime

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("i") val i: Int,
                @XmlChild("l") val l: Long,
                @XmlChild("d") val d: Double,
                @XmlChild("b") val b: Boolean,
                @XmlChild("ld") @XmlFormat(pattern = "yyyy-MM-dd") val ld: LocalDate,
                @XmlChild("ldt") @XmlFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val ldt: LocalDateTime,
                @XmlChild("inst") val inst: Instant,
                @XmlChild("amount") @XmlFormat(pattern = "#,##0.00") val amount: BigDecimal,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun customConverter() {
        val src = SourceFile.kotlin(
            "WithConverter.kt",
            """
            package sample
            import xmlfluss.Converter
            import xmlfluss.Location
            import xmlfluss.XmlChild
            import xmlfluss.XmlConverter
            import xmlfluss.XmlRecord

            data class Currency(val code: String)

            class CurrencyConverter : Converter<Currency> {
                override fun convert(raw: String, loc: Location): Currency = Currency(raw.trim())
            }

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("c") @XmlConverter(CurrencyConverter::class) val c: Currency,
                @XmlChild("c2") @XmlConverter(CurrencyConverter::class) val c2: Currency,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun attributeLeafInChildPath() {
        val src = SourceFile.kotlin(
            "AttrLeaf.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("addr/@code") val code: String,
                @XmlChild("nested/inner/@id") val id: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun nestedDataClassListAndScalar() {
        val src = SourceFile.kotlin(
            "Nested.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Author(@XmlAttr val name: String, @XmlText val bio: String)

            @XmlRecord("//book")
            data class Book(
                @XmlChild("title") val title: String,
                @XmlChild("author") val authors: List<Author>,
                @XmlChild("tag") val tags: List<String>,
                @XmlChild("opt") val opt: String?,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun descendantPath() {
        val src = SourceFile.kotlin(
            "Descend.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord("//feed")
            data class Feed(
                @XmlChild("//title") val deepTitle: String,
                @XmlChild("//entry") val entries: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun polymorphicTagMode() {
        val src = SourceFile.kotlin(
            "PolyTag.kt",
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
            @XmlSubtype(name = "square")
            data class Square(@XmlAttr val side: Int) : Shape()

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild val one: Shape,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun polymorphicTagModeList() {
        val src = SourceFile.kotlin(
            "PolyTagList.kt",
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
            @XmlSubtype(name = "square")
            data class Square(@XmlAttr val side: Int) : Shape()

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild val many: List<Shape>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun polymorphicAttrMode() {
        val src = SourceFile.kotlin(
            "PolyAttr.kt",
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
            @XmlSubtype(name = "square")
            data class Square(@XmlAttr val side: Int) : Shape()

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("shape") val shape: Shape,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun mapEntries() {
        val src = SourceFile.kotlin(
            "Maps.kt",
            """
            package sample
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord

            @XmlRecord("//doc")
            data class Doc(
                @XmlMap(entry = "score", key = "@k", value = ".") val s: Map<String, String>,
                @XmlMap(entry = "tag", key = "@k", value = "@v") val t: Map<String, String>,
                @XmlMap(entry = "list", key = "@k", value = "v") val l: Map<String, List<String>>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun namespaceBindings() {
        val src = SourceFile.kotlin(
            "Ns.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlNs
            import xmlfluss.XmlRecord

            @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
            @XmlNs(prefix = "x", uri = "urn:x")
            @XmlRecord("//atom:entry")
            data class Entry(
                @XmlAttr("x:id") val xid: String,
                @XmlChild("atom:title") val title: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun nullableAttributesAndChildren() {
        val src = SourceFile.kotlin(
            "Nullables.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlFormat
            import xmlfluss.XmlRecord
            import java.math.BigDecimal
            import java.time.LocalDate

            @XmlRecord("//doc")
            data class Doc(
                @XmlAttr("opt") val opt: String?,
                @XmlAttr("n") val n: Int?,
                @XmlChild("title") val title: String?,
                @XmlChild("ld") @XmlFormat(pattern = "yyyy-MM-dd") val ld: LocalDate?,
                @XmlChild("amt") val amt: BigDecimal?,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun descendantNestedDataClass() {
        val src = SourceFile.kotlin(
            "DescendNested.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Author(@XmlAttr val name: String, @XmlText val bio: String)

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("//author") val authors: List<Author>,
                @XmlChild("//meta/timestamp") val ts: String,
                @XmlChild("//note") val note: Author?,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun mapWithNestedValueAndListValue() {
        val src = SourceFile.kotlin(
            "MapNested.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlMap
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Detail(@XmlAttr val k: String, @XmlText val v: String)

            @XmlRecord("//doc")
            data class Doc(
                @XmlMap(entry = "tag", key = "@k", value = ".") val m: Map<String, Detail>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun nullableAndPreserveWhitespaceText() {
        val src = SourceFile.kotlin(
            "Pw.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord
            import xmlfluss.XmlText

            data class Inner(
                @XmlAttr val k: String,
                @XmlText(preserveWhitespace = true) val body: String,
            )

            @XmlRecord("//doc")
            data class Doc(
                @XmlChild("a") val a: Inner,
                @XmlChild("b") val b: Inner?,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }
}
