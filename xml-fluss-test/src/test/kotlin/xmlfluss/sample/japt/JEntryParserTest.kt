package xmlfluss.sample.japt

import xmlfluss.XmlParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Coverage for the APT-generated [JEntryParser]. Exercises a namespace-prefixed record path
 * plus a nested record (`JAtomAuthor`) that inherits the parent's namespace bindings.
 */
class JEntryParserTest {

    @Test
    fun parsesNamespaceQualifiedAtomEntry() {
        val xml = """
            <feed xmlns:atom="http://www.w3.org/2005/Atom">
              <atom:entry id="e1">
                <atom:title>One</atom:title>
                <atom:author>
                  <atom:name>Ada</atom:name>
                  <atom:email>ada@example.org</atom:email>
                </atom:author>
              </atom:entry>
              <atom:entry id="e2">
                <atom:title>Two</atom:title>
                <atom:author>
                  <atom:name>Grace</atom:name>
                </atom:author>
              </atom:entry>
            </feed>
        """.trimIndent()
        val entries = JEntryParser.parse(xml.byteInputStream())
            .toList()
        assertEquals(2, entries.size)
        assertEquals("e1", entries[0].id())
        assertEquals("One", entries[0].title())
        assertEquals("Ada", entries[0].author().name())
        assertEquals("ada@example.org", entries[0].author().email())
        assertEquals("e2", entries[1].id())
        assertEquals("Two", entries[1].title())
        assertEquals("Grace", entries[1].author().name())
        assertNull(entries[1].author().email())
    }

    @Test
    fun bareEntryWithoutAtomPrefixIsIgnored() {
        // Records use //atom:entry — null-namespace <entry> does NOT match.
        val xml = """
            <feed xmlns:atom="http://www.w3.org/2005/Atom">
              <entry id="bare"><title>Skip</title></entry>
              <atom:entry id="real">
                <atom:title>Keep</atom:title>
                <atom:author><atom:name>Z</atom:name></atom:author>
              </atom:entry>
            </feed>
        """.trimIndent()
        val entries = JEntryParser.parse(xml.byteInputStream())
            .toList()
        assertEquals(1, entries.size)
        assertEquals("real", entries[0].id())
        assertEquals("Keep", entries[0].title())
    }

    @Test
    fun missingRequiredTitleThrows() {
        val xml = """
            <feed xmlns:atom="http://www.w3.org/2005/Atom">
              <atom:entry id="e1">
                <atom:author><atom:name>Z</atom:name></atom:author>
              </atom:entry>
            </feed>
        """.trimIndent()
        kotlin.test.assertFailsWith<XmlParseException.Missing> {
            JEntryParser.parse(xml.byteInputStream())
                .toList()
        }
    }
}
