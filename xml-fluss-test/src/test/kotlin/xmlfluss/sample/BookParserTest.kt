package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookParserTest {

    @Test
    fun parsesAllSupportedTypesWithIntermediateWrappers() = runTest {
        // Notes:
        //   - Record element <book> sits four levels deep under //library/section/shelves/shelf —
        //     descendant axis (`//book`) skips the unrelated wrapper tags.
        //   - `<dc:tags>` and `<meta>` are intermediate wrappers NOT declared as fields on Book.
        //     They only appear inside multi-segment @XmlChild paths (`dc:tags/dc:tag`,
        //     `meta/published`, etc.) so the data-class shape stays flat.
        //   - The second <book> has featured="false" and is filtered out by the record-path
        //     predicate `[@featured='true']`.
        val xml = """
            <library>
              <section name="fiction">
                <shelves>
                  <shelf row="3">
                    <book id="1" featured="true" isbn="978-0-12-345678-9">
                      <title>Great Book</title>
                      <subtitle>An Adventure</subtitle>
                      <pages>432</pages>
                      <rating>4.5</rating>
                      <inPrint>yes</inPrint>
                      <dc:tags xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:tag>fiction</dc:tag>
                        <dc:tag>adventure</dc:tag>
                        <dc:tag>classic</dc:tag>
                      </dc:tags>
                      <meta>
                        <published>2025-01-15</published>
                        <updatedAt>2025-06-01T12:30:00</updatedAt>
                        <indexedAt>2025-06-02T08:00:00Z</indexedAt>
                      </meta>
                      <price currency="USD">USD 19.99</price>
                      <totalSales>1234567.89</totalSales>
                      Some raw text inside book.
                    </book>
                    <book id="2" featured="false">
                      <title>Skipped</title>
                    </book>
                    <book id="3" featured="true">
                      <title>Bare Book</title>
                      <pages>10</pages>
                      <rating>0.0</rating>
                      <inPrint>no</inPrint>
                      <meta>
                        <published>2024-12-31</published>
                        <updatedAt>2024-12-31T23:59:59</updatedAt>
                        <indexedAt>2025-01-01T00:00:00Z</indexedAt>
                      </meta>
                      <price currency="EUR">EUR 0.00</price>
                      <totalSales>0</totalSales>
                    </book>
                  </shelf>
                </shelves>
              </section>
            </library>
        """.trimIndent()

        val books = BookParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, books.size)

        val first = books[0]
        assertEquals(1, first.id)
        assertEquals(true, first.featured)
        assertEquals("978-0-12-345678-9", first.isbn)
        assertEquals("Great Book", first.title)
        assertEquals("An Adventure", first.subtitle)
        assertEquals(432L, first.pages)
        assertEquals(4.5, first.rating)
        assertEquals(true, first.inPrint)
        assertEquals(listOf("fiction", "adventure", "classic"), first.tags)
        assertEquals(LocalDate.of(2025, 1, 15), first.published)
        assertEquals(LocalDateTime.of(2025, 6, 1, 12, 30, 0), first.updatedAt)
        assertEquals(Instant.parse("2025-06-02T08:00:00Z"), first.indexedAt)
        assertEquals(Money("USD", BigDecimal("19.99")), first.price)
        assertEquals("USD", first.currency)
        assertEquals(BigDecimal("1234567.89"), first.totalSales)
        assertTrue(first.raw.contains("Some raw text inside book."))

        val second = books[1]
        assertEquals(3, second.id)
        assertEquals(true, second.featured)
        assertNull(second.isbn)
        assertNull(second.subtitle)
        assertEquals("Bare Book", second.title)
        assertEquals(emptyList(), second.tags)
        assertEquals(BigDecimal("0"), second.totalSales)
    }
}
