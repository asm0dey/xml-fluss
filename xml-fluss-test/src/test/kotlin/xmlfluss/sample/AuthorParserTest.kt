package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xmlfluss.XmlParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AuthorParserTest {

    @Test
    fun parsesAuthorsAtAnyDepth() = runTest {
        val xml = """
            <library>
              <section>
                <shelf>
                  <books>
                    <book>
                      <author id="1" role="lead">
                        <name>Jane Doe</name>
                        <bio>Writer.</bio>
                      </author>
                      <author id="2">
                        <name>John Roe</name>
                      </author>
                    </book>
                  </books>
                </shelf>
              </section>
            </library>
        """.trimIndent()
        val authors = AuthorParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, authors.size)
        assertEquals(Author(id = 1, role = "lead", name = "Jane Doe", bio = "Writer."), authors[0])
        assertEquals(Author(id = 2, role = null, name = "John Roe", bio = null), authors[1])
    }

    @Test
    fun missingRequiredFieldThrows() = runTest {
        val xml = """
            <root>
              <author id="3">
                <bio>no name</bio>
              </author>
            </root>
        """.trimIndent()
        assertFailsWith<XmlParseException.Missing> {
            AuthorParser.parse(xml.byteInputStream()).toList()
        }
    }

    @Test
    fun nullableAttrAbsentBecomesNull() = runTest {
        val xml = """<r><author id="9"><name>n</name></author></r>"""
        val a = AuthorParser.parse(xml.byteInputStream()).toList().single()
        assertNull(a.role)
        assertNull(a.bio)
    }

    @Test
    fun coercionFailureThrows() = runTest {
        val xml = """<r><author id="abc"><name>n</name></author></r>"""
        assertFailsWith<XmlParseException.Coercion> {
            AuthorParser.parse(xml.byteInputStream()).toList()
        }
    }
}
