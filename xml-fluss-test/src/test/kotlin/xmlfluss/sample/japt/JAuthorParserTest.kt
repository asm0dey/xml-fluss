package xmlfluss.sample.japt

import xmlfluss.XmlParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Basic attr + child binding coverage for the APT-generated [JAuthorParser].
 */
class JAuthorParserTest {

    @Test
    fun parsesAttrsAndChildren() {
        val xml = """
            <root>
              <author id="1" role="primary">
                <name>Ada Lovelace</name>
                <bio>Pioneer.</bio>
              </author>
              <author id="2">
                <name>Grace Hopper</name>
              </author>
            </root>
        """.trimIndent()
        val authors = JAuthorParser.parse(xml.byteInputStream())
            .toList()
        assertEquals(2, authors.size)
        assertEquals(1, authors[0].id())
        assertEquals("primary", authors[0].role())
        assertEquals("Ada Lovelace", authors[0].name())
        assertEquals("Pioneer.", authors[0].bio())
        assertEquals(2, authors[1].id())
        assertNull(authors[1].role())
        assertEquals("Grace Hopper", authors[1].name())
        assertNull(authors[1].bio())
    }

    @Test
    fun missingRequiredNameThrows() {
        val xml = "<root><author id=\"1\"/></root>"
        assertFailsWith<XmlParseException.Missing> {
            JAuthorParser.parse(xml.byteInputStream())
                .toList()
        }
    }
}
