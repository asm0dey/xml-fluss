package xmlfluss.sample.full

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xmlfluss.sample.Money
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CatalogParserTest {

    @Test
    fun parsesCatalogWithIntermediateWrappersAndAllSupportedTypes() = runTest {
        // Wrappers `<authors>` (between catalog and author) and `<works>` (between author
        // and book) are not modeled as fields — the multi-segment `@XmlChild` paths
        // (`authors/author`, `works/book`) traverse them. Each book exercises every
        // currently-supported scalar/temporal/decimal type, attr-leaf, custom converter,
        // formatted parse, list-of-scalar, nested-with-text, single-nested + nullable-
        // nested, and list-of-nested at two levels (catalog→authors, author→books).
        val xml = """
            <root>
              <catalog version="2">
                <authors>
                  <author id="1">
                    <name>Alice</name>
                    <country>CA</country>
                    <agent id="100"><name>AgencyOne</name></agent>
                    <works>
                      <book isbn="978-0-1" featured="true">
                        <title>Book One</title>
                        <subtitle>An Adventure</subtitle>
                        <pages>300</pages>
                        <rating>4.5</rating>
                        <published>05/01/2023</published>
                        <updated>2024-01-02T03:04:05</updated>
                        <indexed>2024-01-03T00:00:00Z</indexed>
                        <listPrice currency="USD">USD 19.99</listPrice>
                        <totalSales>1,234,567.89</totalSales>
                        <tag>fiction</tag>
                        <tag>classic</tag>
                        <royalty currency="USD">12.50</royalty>
                      </book>
                      <book isbn="978-0-2" featured="false">
                        <title>Book Two</title>
                        <pages>150</pages>
                        <rating>3.2</rating>
                        <published>03/15/2024</published>
                        <updated>2024-04-01T10:00:00</updated>
                        <indexed>2024-04-02T00:00:00Z</indexed>
                        <listPrice currency="EUR">EUR 9.99</listPrice>
                        <totalSales>5,000.00</totalSales>
                        <royalty currency="EUR">2.00</royalty>
                      </book>
                    </works>
                  </author>
                  <author id="2">
                    <name>Bob</name>
                    <works>
                      <book isbn="978-0-3" featured="true">
                        <title>Solo</title>
                        <pages>50</pages>
                        <rating>5.0</rating>
                        <published>01/01/2025</published>
                        <updated>2025-01-01T12:00:00</updated>
                        <indexed>2025-01-01T12:00:00Z</indexed>
                        <listPrice currency="USD">USD 1.00</listPrice>
                        <totalSales>0.00</totalSales>
                        <royalty currency="USD">0.05</royalty>
                      </book>
                    </works>
                  </author>
                </authors>
              </catalog>
            </root>
        """.trimIndent()

        val catalogs = CatalogParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, catalogs.size)
        val catalog = catalogs[0]
        assertEquals(2, catalog.version)
        assertEquals(2, catalog.authors.size)

        val alice = catalog.authors[0]
        assertEquals(1, alice.id)
        assertEquals("Alice", alice.name)
        assertEquals("CA", alice.country)
        assertNotNull(alice.agent)
        assertEquals(Agent(100, "AgencyOne"), alice.agent)
        assertEquals(2, alice.books.size)

        val b1 = alice.books[0]
        assertEquals("978-0-1", b1.isbn)
        assertEquals(true, b1.featured)
        assertEquals("Book One", b1.title)
        assertEquals("An Adventure", b1.subtitle)
        assertEquals(300L, b1.pages)
        assertEquals(4.5, b1.rating)
        assertEquals(LocalDate.of(2023, 5, 1), b1.published)
        assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5), b1.updated)
        assertEquals(Instant.parse("2024-01-03T00:00:00Z"), b1.indexed)
        assertEquals(Money("USD", BigDecimal("19.99")), b1.listPrice)
        assertEquals("USD", b1.priceCurrency)
        assertEquals(BigDecimal("1234567.89"), b1.totalSales)
        assertEquals(listOf("fiction", "classic"), b1.tags)
        assertEquals(Royalty("USD", BigDecimal("12.50")), b1.royalty)

        val b2 = alice.books[1]
        assertEquals("978-0-2", b2.isbn)
        assertEquals(false, b2.featured)
        assertNull(b2.subtitle)
        assertEquals(emptyList(), b2.tags)
        assertEquals(Money("EUR", BigDecimal("9.99")), b2.listPrice)
        assertEquals("EUR", b2.priceCurrency)
        assertEquals(Royalty("EUR", BigDecimal("2.00")), b2.royalty)

        val bob = catalog.authors[1]
        assertEquals(2, bob.id)
        assertEquals("Bob", bob.name)
        assertNull(bob.country)
        assertNull(bob.agent)
        assertEquals(1, bob.books.size)
        val b3 = bob.books[0]
        assertEquals("978-0-3", b3.isbn)
        assertEquals(true, b3.featured)
        assertEquals(BigDecimal("0.00"), b3.totalSales)
        assertEquals(Royalty("USD", BigDecimal("0.05")), b3.royalty)
    }
}
