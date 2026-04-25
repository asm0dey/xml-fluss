package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xmlfluss.XmlParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ErrorMessageTest {

    @Test
    fun missingCarriesFieldNameAndLocation() = runTest {
        val xml = """
            <root>
              <author id="3">
                <bio>no name</bio>
              </author>
            </root>
        """.trimIndent()
        val ex = assertFailsWith<XmlParseException.Missing> {
            AuthorParser.parse(xml.byteInputStream()).toList()
        }
        assertEquals("name", ex.field)
        assertEquals(2, ex.loc.line)
        assertTrue(ex.loc.path.endsWith("/author"), "path was ${ex.loc.path}")
        val msg = ex.message ?: ""
        assertTrue("Missing required field 'name'" in msg, "msg: $msg")
        assertTrue("/root/author" in msg, "msg: $msg")
        assertTrue("line 2" in msg, "msg: $msg")
    }

    @Test
    fun missingRequiredAttrCarriesAttrFieldName() = runTest {
        val xml = """
            <root>
              <author><name>n</name></author>
            </root>
        """.trimIndent()
        val ex = assertFailsWith<XmlParseException.Missing> {
            AuthorParser.parse(xml.byteInputStream()).toList()
        }
        assertEquals("id", ex.field)
        assertTrue(ex.loc.path.endsWith("/author"))
    }

    @Test
    fun coercionOnRecordAttrPointsAtRecordElement() = runTest {
        val xml = """
            <root>
              <author id="not-a-number"><name>n</name></author>
            </root>
        """.trimIndent()
        val ex = assertFailsWith<XmlParseException.Coercion> {
            AuthorParser.parse(xml.byteInputStream()).toList()
        }
        assertEquals("id", ex.field)
        assertEquals("not-a-number", ex.raw)
        assertEquals("Int", ex.type)
        // Attribute lives on the record element, so loc points at the record.
        assertEquals(2, ex.loc.line)
        assertTrue(ex.loc.path.endsWith("/author"), "path was ${ex.loc.path}")
        val msg = ex.message ?: ""
        assertTrue("Cannot coerce 'not-a-number' to Int" in msg, "msg: $msg")
        assertTrue("for 'id'" in msg, "msg: $msg")
        assertTrue(ex.cause != null, "underlying cause should propagate")
    }

    @Test
    fun coercionFromCustomConverterPointsAtChildElement() = runTest {
        val xml = """
            <root>
              <item id="1">
                <name>n</name>
                <published>2026-01-01</published>
                <price>USDxxx</price>
              </item>
            </root>
        """.trimIndent()
        val ex = assertFailsWith<XmlParseException.Coercion> {
            ItemParser.parse(xml.byteInputStream()).toList()
        }
        assertEquals("price", ex.field)
        assertEquals("USDxxx", ex.raw)
        assertEquals("Money", ex.type)
        // Child-element coercion error pinpoints the offending element's own line + path,
        // not the enclosing record's.
        assertEquals(5, ex.loc.line)
        assertEquals("/root/item/price", ex.loc.path)
    }

    @Test
    fun coercionOnDateChildPointsAtChildElement() = runTest {
        val xml = """
            <root>
              <item id="1">
                <name>n</name>
                <published>not-a-date</published>
                <price>USD 1.00</price>
              </item>
            </root>
        """.trimIndent()
        val ex = assertFailsWith<XmlParseException.Coercion> {
            ItemParser.parse(xml.byteInputStream()).toList()
        }
        assertEquals("published", ex.field)
        assertEquals("not-a-date", ex.raw)
        assertEquals("LocalDate", ex.type)
        assertEquals(4, ex.loc.line)
        assertEquals("/root/item/published", ex.loc.path)
    }

    @Test
    fun malformedXmlSurfacesPositionInformation() = runTest {
        // Truncated mid-element: Aalto WFCException is currently surfaced raw (cursor only
        // wraps post-Aalto EOF). The point of this test is the *positional information* —
        // any failure reaching the user must let them locate the bad spot.
        val xml = """
            <root>
              <author id="1"><name>Open
        """.trimIndent()
        val ex = assertFailsWith<Exception> {
            AuthorParser.parse(xml.byteInputStream()).toList()
        }
        val msg = (ex.message ?: "") + " | " + (ex.cause?.message ?: "")
        assertTrue(
            Regex("\\[\\d+,\\d+]|line \\d+").containsMatchIn(msg),
            "error must embed [row,col] or 'line N': $msg",
        )
    }
}
