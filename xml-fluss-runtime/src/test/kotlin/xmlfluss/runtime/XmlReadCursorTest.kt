package xmlfluss.runtime

import xmlfluss.XmlParseException
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmlReadCursorTest {
    private fun cursor(xml: String, path: String = "//item"): XmlReadCursor =
        XmlReadCursor(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), Paths.compile(path))

    @Test fun normal_iteratesRecords() {
        val xml = """<root><item id="1"><name>a</name></item><item id="2"><name>b</name></item></root>"""
        val ids = mutableListOf<String>()
        val names = mutableListOf<String>()
        cursor(xml).use { c ->
            while (c.findNextRecord()) {
                c.recordAttr(null, "id")?.let { ids += it }
                c.forEachRecordChild { local, _ ->
                    if (local == "name") names += c.childText(false) else c.skipChild()
                }
            }
        }
        assertEquals(listOf("1", "2"), ids)
        assertEquals(listOf("a", "b"), names)
    }

    @Test fun emptyInput_findNextRecordReturnsFalse() {
        // Aalto requires a root; use a well-formed but empty doc.
        val xml = "<root/>"
        cursor(xml).use { c ->
            assertFalse(c.findNextRecord())
        }
    }

    @Test fun truncatedMidRecord_throwsMalformed() {
        // Truncate inside the record body so forEachRecordChild's EOF guard fires.
        val xml = """<root><item><a>hi</a>"""
        assertThrowsMalformed {
            cursor(xml).use { c ->
                assertTrue(c.findNextRecord())
                c.forEachRecordChild { _, _ -> c.skipChild() }
            }
        }
    }

    @Test fun truncatedMidForEachChild_throwsMalformed() {
        // //item/wrap/leaf — wrap's forEachChild iterates; truncate mid-wrap.
        val xml = """<root><item><wrap><leaf>hi</leaf>"""
        assertThrowsMalformed {
            cursor(xml).use { c ->
                assertTrue(c.findNextRecord())
                c.forEachRecordChild { local, _ ->
                    if (local == "wrap") {
                        c.forEachChild { _, _ -> c.skipChild() }
                    } else c.skipChild()
                }
            }
        }
    }

    @Test fun truncatedMidForEachSubrecordChild_throwsMalformed() {
        val xml = """<root><item><sub><a>hi</a>"""
        assertThrowsMalformed {
            cursor(xml).use { c ->
                assertTrue(c.findNextRecord())
                c.forEachRecordChild { local, _ ->
                    if (local == "sub") {
                        c.forEachSubrecordChild { _, _ -> c.skipChild() }
                    } else c.skipChild()
                }
            }
        }
    }

    @Test fun truncatedMidChildText_throwsMalformed() {
        val xml = """<root><item><name>hi"""
        assertThrowsMalformed {
            cursor(xml).use { c ->
                assertTrue(c.findNextRecord())
                c.forEachRecordChild { local, _ ->
                    if (local == "name") c.childText(false) else c.skipChild()
                }
            }
        }
    }

    @Test fun truncatedMidForEachDescendantInChild_throwsMalformed() {
        val xml = """<root><item><wrap><deep><leaf>hi</leaf>"""
        assertThrowsMalformed {
            cursor(xml).use { c ->
                assertTrue(c.findNextRecord())
                c.forEachRecordChild { local, _ ->
                    if (local == "wrap") {
                        c.forEachDescendantInChild { _, _ -> false }
                    } else c.skipChild()
                }
            }
        }
    }

    private fun assertThrowsMalformed(block: () -> Unit) {
        val ex = try {
            block()
            null
        } catch (t: Throwable) { t }
        requireNotNull(ex) { "expected an exception on truncated input" }
        assertTrue(
            ex is XmlParseException.Malformed,
            "expected XmlParseException.Malformed, got ${ex::class.qualifiedName}: ${ex.message}",
        )
    }
}
