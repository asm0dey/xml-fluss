package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EntryParserTest {

    @Test
    fun parsesNsPrefixedRecord() = runTest {
        val xml = """
            <atom:feed xmlns:atom="http://www.w3.org/2005/Atom">
              <atom:entry id="e1">
                <atom:title>Hello</atom:title>
                <atom:summary>World</atom:summary>
              </atom:entry>
              <atom:entry id="e2">
                <atom:title>Bye</atom:title>
              </atom:entry>
            </atom:feed>
        """.trimIndent()
        val entries = EntryParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, entries.size)
        assertEquals(Entry(id = "e1", title = "Hello", summary = "World"), entries[0])
        assertEquals("e2", entries[1].id)
        assertEquals("Bye", entries[1].title)
        assertNull(entries[1].summary)
    }

    @Test
    fun nsPrefixedRecordIgnoresWrongNamespace() = runTest {
        val xml = """
            <feed xmlns="http://example.com/other">
              <entry id="e1">
                <title>Wrong NS</title>
              </entry>
            </feed>
        """.trimIndent()
        val entries = EntryParser.parse(xml.byteInputStream()).toList()
        assertEquals(0, entries.size)
    }

    @Test
    fun defaultNsBareNamesMatch() = runTest {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry id="e1">
                <title>Hello</title>
              </entry>
              <entry id="e2">
                <title>World</title>
              </entry>
            </feed>
        """.trimIndent()
        val entries = DefaultNsEntryParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, entries.size)
        assertEquals(DefaultNsEntry(id = "e1", title = "Hello"), entries[0])
        assertEquals(DefaultNsEntry(id = "e2", title = "World"), entries[1])
    }

    @Test
    fun malformedXmlThrows() = runTest {
        val xml = "<root><atom:entry xmlns:atom=\"http://www.w3.org/2005/Atom\" id=\"x\"><atom:title>unclosed"
        assertFailsWith<Exception> {
            EntryParser.parse(xml.byteInputStream()).toList()
        }
    }
}
