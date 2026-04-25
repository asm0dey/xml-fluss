package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemParserTest {

    @Test
    fun parsesItemWithAllV1Features() = runTest {
        val xml = """
            <root>
              <item id="1">  important
                <name>Alpha</name>
                <tag>red</tag>
                <tag>fast</tag>
                <meta>
                  <lang>en</lang>
                </meta>
                <info kind="news"/>
                <published>2026-04-25</published>
                <price>USD 12.50</price>
              </item>
            </root>
        """.trimIndent()
        val items = ItemParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, items.size)
        val it = items[0]
        assertEquals(1, it.id)
        assertEquals("Alpha", it.name)
        assertEquals(listOf("red", "fast"), it.tags)
        assertEquals("en", it.lang)
        assertEquals("news", it.kind)
        assertEquals(LocalDate.of(2026, 4, 25), it.published)
        assertEquals(Money("USD", BigDecimal("12.50")), it.price)
        assertEquals("important", it.raw)
    }

    @Test
    fun multipleItemsAndOptionalAbsent() = runTest {
        val xml = """
            <root>
              <item id="2">
                <name>Beta</name>
                <published>2026-01-02</published>
                <price>EUR 9.00</price>
              </item>
              <item id="3">
                <name>Gamma</name>
                <tag>x</tag>
                <published>2026-02-03</published>
                <price>GBP 1.00</price>
              </item>
            </root>
        """.trimIndent()
        val items = ItemParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, items.size)
        assertEquals(emptyList(), items[0].tags)
        assertEquals(null, items[0].lang)
        assertEquals(null, items[0].kind)
        assertEquals(listOf("x"), items[1].tags)
    }
}
