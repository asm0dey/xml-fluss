package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NovelParserTest {

    @Test
    fun parsesSingleNestedListNestedAndNullableNested() = runTest {
        val xml = """
            <library>
              <novel id="42">
                <title>Bookname</title>
                <author id="1"><name>Alice</name><country>CA</country></author>
                <chapter n="1"><title>One</title>Chapter 1 excerpt.</chapter>
                <chapter n="2"><title>Two</title>Chapter 2 excerpt.</chapter>
              </novel>
              <novel id="43">
                <title>Other</title>
                <author id="2"><name>Bob</name></author>
                <editor id="3"><name>Carol</name><country>UK</country></editor>
              </novel>
            </library>
        """.trimIndent()

        val novels = NovelParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, novels.size)

        val n0 = novels[0]
        assertEquals(42, n0.id)
        assertEquals("Bookname", n0.title)
        assertEquals(Person(1, "Alice", "CA"), n0.author)
        assertNull(n0.editor)
        assertEquals(2, n0.chapters.size)
        assertEquals(1, n0.chapters[0].n)
        assertEquals("One", n0.chapters[0].title)
        assertTrue(n0.chapters[0].excerpt.contains("Chapter 1 excerpt."))
        assertEquals("Two", n0.chapters[1].title)

        val n1 = novels[1]
        assertEquals(43, n1.id)
        assertEquals(Person(2, "Bob", null), n1.author)
        assertNotNull(n1.editor)
        assertEquals(Person(3, "Carol", "UK"), n1.editor)
        assertEquals(emptyList(), n1.chapters)
    }
}
