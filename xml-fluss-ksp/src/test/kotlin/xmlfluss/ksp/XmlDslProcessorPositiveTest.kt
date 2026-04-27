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

            @XmlRecord(path = "//doc")
            data class Inner(@XmlText val body: String)

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlAttr(name = "id") val id: String,
                @XmlAttr val href: String,
                @XmlChild(path = "title") val title: String,
                @XmlChild val name: String,
                @XmlChild(path = "inner") val inner: Inner,
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "i") val i: Int,
                @XmlChild(path = "l") val l: Long,
                @XmlChild(path = "d") val d: Double,
                @XmlChild(path = "b") val b: Boolean,
                @XmlChild(path = "ld") @XmlFormat(pattern = "yyyy-MM-dd") val ld: LocalDate,
                @XmlChild(path = "ldt") @XmlFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val ldt: LocalDateTime,
                @XmlChild(path = "inst") val inst: Instant,
                @XmlChild(path = "amount") @XmlFormat(pattern = "#,##0.00") val amount: BigDecimal,
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "c") @XmlConverter(cls = CurrencyConverter::class) val c: Currency,
                @XmlChild(path = "c2") @XmlConverter(cls = CurrencyConverter::class) val c2: Currency,
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "addr/@code") val code: String,
                @XmlChild(path = "nested/inner/@id") val id: String,
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

            @XmlRecord(path = "//book")
            data class Book(
                @XmlChild(path = "title") val title: String,
                @XmlChild(path = "author") val authors: List<Author>,
                @XmlChild(path = "tag") val tags: List<String>,
                @XmlChild(path = "opt") val opt: String?,
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

            @XmlRecord(path = "//feed")
            data class Feed(
                @XmlChild(path = "//title") val deepTitle: String,
                @XmlChild(path = "//entry") val entries: List<String>,
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "shape") val shape: Shape,
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlMap(entry = "score", key = "@k", value = ".") val s: Map<String, String>,
                @XmlMap(entry = "tag", key = "@k", value = "@v") val t: Map<String, String>,
                @XmlMap(entry = "list", key = "@k", value = "v") val l: Map<String, List<String>>,
                @XmlMap(entry = "alist", key = "@k", value = "@v") val a: Map<String, List<String>>,
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
            @XmlRecord(path = "//atom:entry")
            data class Entry(
                @XmlAttr(name = "x:id") val xid: String,
                @XmlChild(path = "atom:title") val title: String,
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlAttr(name = "opt") val opt: String?,
                @XmlAttr(name = "n") val n: Int?,
                @XmlChild(path = "title") val title: String?,
                @XmlChild(path = "ld") @XmlFormat(pattern = "yyyy-MM-dd") val ld: LocalDate?,
                @XmlChild(path = "amt") val amt: BigDecimal?,
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "//author") val authors: List<Author>,
                @XmlChild(path = "//meta/timestamp") val ts: String,
                @XmlChild(path = "//note") val note: Author?,
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

            @XmlRecord(path = "//doc")
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

            @XmlRecord(path = "//doc")
            data class Doc(
                @XmlChild(path = "a") val a: Inner,
                @XmlChild(path = "b") val b: Inner?,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun directPositionalChild() {
        val src = SourceFile.kotlin(
            "DirectPos.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            data class ItemSummary(@XmlAttr(name = "id") val id: String)

            @XmlRecord(path = "//feed")
            data class Doc(@XmlChild(path = "item[2]") val secondItem: ItemSummary)
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun chainedAttrThenPositional() {
        val src = SourceFile.kotlin(
            "ChainedAttrPos.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "meta[@kind='post'][2]/published") val published: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun positionalThenAttrSuffixOnAttrLeaf() {
        val src = SourceFile.kotlin(
            "PosThenAttr.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//entry")
            data class Doc(
                @XmlChild(path = "link[@type='epub'][2]/@href") val href: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun descendantHeadWithAttrThenDirectPositional() {
        val src = SourceFile.kotlin(
            "DescAttrDirectPos.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//root")
            data class Doc(
                @XmlChild(path = "//x[@a='b']/y[2]") val ys: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun andCombinedIndexAndAttrInSingleBracket() {
        val src = SourceFile.kotlin(
            "AndIndexAttr.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[2 and @kind='post']/title") val title: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun negatedAttrPredicate() {
        val src = SourceFile.kotlin(
            "NegatedAttr.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[@kind!='draft']/title") val titles: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun positionalThenAttrSuffixOnDirectChild() {
        val src = SourceFile.kotlin(
            "PosThenAttrDirect.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[2][@kind='post']/title") val title: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun andCombinedIndexAndTwoAttrs() {
        val src = SourceFile.kotlin(
            "AndIndexTwoAttrs.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[2 and @kind='post' and @lang='en']/title") val title: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun directChildBothPlainAndPositional() {
        val src = SourceFile.kotlin(
            "PlainAndPositional.kt",
            """
            package sample
            import xmlfluss.XmlAttr
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            data class ItemSummary(@XmlAttr(name = "id") val id: String)

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item") val items: List<ItemSummary>,
                @XmlChild(path = "item[2]") val secondItem: ItemSummary,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun descendantHeadGuardedAndUnguardedSibling() {
        val src = SourceFile.kotlin(
            "DescGuardedUnguarded.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//root")
            data class Doc(
                @XmlChild(path = "//x[@a='b']/y") val ys: List<String>,
                @XmlChild(path = "//x/z") val zs: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun orPredicateWithoutIndex() {
        val src = SourceFile.kotlin(
            "OrPredicate.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[@kind='post' or @kind='page']/title") val titles: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun andCombinedAttrThenIndexInSingleBracket() {
        val src = SourceFile.kotlin(
            "AndAttrThenIndex.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[@kind='post' and 2]/title") val title: String,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun chainedNonIndexBracketsFolded() {
        val src = SourceFile.kotlin(
            "ChainedNonIndex.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[@kind='post'][@lang='en']/title") val titles: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun andCombinedTwoAttrsNoIndex() {
        val src = SourceFile.kotlin(
            "AndTwoAttrsNoIndex.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlRecord

            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[@kind='post' and @lang='en']/title") val titles: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }

    @Test
    fun nsQualifiedAttrInPredicate() {
        val src = SourceFile.kotlin(
            "NsAttrPred.kt",
            """
            package sample
            import xmlfluss.XmlChild
            import xmlfluss.XmlNs
            import xmlfluss.XmlRecord

            @XmlNs(prefix = "x", uri = "urn:x")
            @XmlRecord(path = "//feed")
            data class Doc(
                @XmlChild(path = "item[@x:kind='post']/title") val titles: List<String>,
            )
            """.trimIndent(),
        )
        assertCompilesOk(src)
    }
}
