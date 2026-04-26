package xmlfluss.sample.japt

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase-5 coverage for the APT-generated [JItemParser]: namespace-prefixed paths, custom
 * date format, custom converter, lists, and a captured raw text body.
 */
class JItemParserTest {

    @Test
    fun parsesAllPhase5Surfaces() {
        // Programmatic fixture — no external file dependency.
        val xml = """
            <root xmlns:dc="http://purl.org/dc/elements/1.1/">
              <item id="1">
                <name>Book A</name>
                <tag>alpha</tag>
                <tag>beta</tag>
                <published>15/01/2026</published>
                <price>USD 12.50</price>
              </item>
              <item id="2">
                <name>Book B</name>
                <published>02/02/2026</published>
                <price>EUR 9.99</price>
              </item>
            </root>
        """.trimIndent()

        val items = JItemParser.parse(xml.byteInputStream())
            .toList()
        assertEquals(2, items.size)

        val a = items[0]
        assertEquals(1, a.id())
        assertEquals("Book A", a.name())
        assertEquals(listOf("alpha", "beta"), a.tags())
        assertEquals(LocalDate.of(2026, 1, 15), a.published())
        assertEquals(Money("USD", BigDecimal("12.50")), a.price())

        val b = items[1]
        assertEquals(2, b.id())
        assertEquals("Book B", b.name())
        assertEquals(emptyList<String>(), b.tags())
        assertEquals(LocalDate.of(2026, 2, 2), b.published())
        assertEquals(Money("EUR", BigDecimal("9.99")), b.price())
    }

    @Test
    fun ignoreNamespaceOverloadAcceptsDocumentInOtherNamespace() {
        // Same record, but the document lives in a default namespace not declared on the type.
        // ignoreNamespace=true matches by local-name only.
        val xml = """
            <root xmlns="urn:foreign">
              <item id="42">
                <name>Solo</name>
                <published>03/03/2026</published>
                <price>GBP 7.10</price>
              </item>
            </root>
        """.trimIndent()
        val items = JItemParser.parse(xml.byteInputStream(), true)
            .toList()
        assertEquals(1, items.size)
        assertEquals(42, items[0].id())
        assertEquals("Solo", items[0].name())
        assertEquals(LocalDate.of(2026, 3, 3), items[0].published())
        assertEquals(Money("GBP", BigDecimal("7.10")), items[0].price())
    }
}
