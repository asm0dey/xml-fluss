package xmlfluss.sample.showcase

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

/**
 * End-to-end showcase covering every advanced feature in one document. Runs the same XML
 * through both the KSP-generated [BookParser] and the APT-generated [JBookParser] and
 * asserts the parsed structures match.
 */
class ShowcaseTest {

    private val xml = """
        <lib:catalog xmlns:lib="https://lib.example.com/v1" xmlns:m="https://lib.example.com/money">
          <lib:book id="b-100">
            <title>Compilers</title>
            <bio lang="en">First edition.</bio>
            <authors>
              <author name="Alfred" country="US"/>
              <author name="Monica" country="US"/>
            </authors>
            <misc><meta><isbn>978-0321486813</isbn></meta></misc>
            <paperback pages="1009"/>
            <m:price currency="USD">79.95</m:price>
            <published>2006-09-10</published>
            <rating>****</rating>
            <m:royalty>
              <price currency="USD">5.00</price>
              <share>
                <author name="Alfred" country="US"/>
                <author name="Monica" country="US"/>
              </share>
            </m:royalty>
            <m:royalty>
              <price currency="EUR">4.50</price>
              <share>
                <author name="Alfred" country="US"/>
              </share>
            </m:royalty>
            <regional region="US"><amount>79.95</amount></regional>
            <regional region="EU"><amount>75.00</amount></regional>
          </lib:book>
          <lib:book id="b-200">
            <title>Algorithms</title>
            <authors>
              <author name="Robert"/>
            </authors>
            <misc><isbn>978-0132314527</isbn></misc>
            <ebook sizeMb="12.4"/>
            <m:price currency="USD">59.00</m:price>
            <published>2011-03-19</published>
            <rating>***</rating>
          </lib:book>
        </lib:catalog>
    """.trimIndent()

    @Test
    fun kspShowcase() = runTest {
        val books = BookParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, books.size)

        val b1 = books[0]
        assertEquals("b-100", b1.id)
        assertEquals("Compilers", b1.title)
        assertEquals("en", b1.lang)
        assertEquals(listOf("Alfred", "Monica"), b1.authors.map { it.name })
        assertEquals(listOf("US", "US"), b1.authors.map { it.country })
        assertEquals("978-0321486813", b1.isbn)
        val pb = assertIs<Format.Paperback>(b1.format)
        assertEquals(1009, pb.pages)
        assertEquals(Money("USD", BigDecimal("79.95")), b1.price)
        assertEquals(LocalDate.of(2006, 9, 10), b1.published)
        assertEquals(4, b1.rating)

        // Map<Money, List<Author>>
        assertEquals(2, b1.royalties.size)
        val usdShare = b1.royalties.getValue(Money("USD", BigDecimal("5.00")))
        assertEquals(listOf("Alfred", "Monica"), usdShare.map { it.name })
        val eurShare = b1.royalties.getValue(Money("EUR", BigDecimal("4.50")))
        assertEquals(listOf("Alfred"), eurShare.map { it.name })

        assertEquals(BigDecimal("79.95"), b1.regionalPrice["US"])
        assertEquals(BigDecimal("75.00"), b1.regionalPrice["EU"])

        val b2 = books[1]
        assertEquals("b-200", b2.id)
        assertNull(b2.lang)
        assertEquals(listOf("Robert"), b2.authors.map { it.name })
        assertEquals(listOf<String?>(null), b2.authors.map { it.country })
        assertEquals("978-0132314527", b2.isbn)
        val eb = assertIs<Format.Ebook>(b2.format)
        assertEquals(12.4, eb.sizeMb)
        assertTrue(b2.royalties.isEmpty())
        assertTrue(b2.regionalPrice.isEmpty())
        assertEquals(3, b2.rating)
    }

    @Test
    fun aptShowcase() {
        val books = JBookParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, books.size)

        val b1 = books[0]
        assertEquals("b-100", b1.id())
        assertEquals("Compilers", b1.title())
        assertEquals("en", b1.lang())
        assertEquals(listOf("Alfred", "Monica"), b1.authors().map { it.name() })
        assertEquals(listOf("US", "US"), b1.authors().map { it.country() })
        assertEquals("978-0321486813", b1.isbn())
        val pb = assertIs<JPaperback>(b1.format())
        assertEquals(1009, pb.pages())
        assertEquals(JMoney("USD", BigDecimal("79.95")), b1.price())
        assertEquals(LocalDate.of(2006, 9, 10), b1.published())
        assertEquals(4, b1.rating())

        assertEquals(2, b1.royalties().size)
        val usdShare = assertNotNull(b1.royalties()[JMoney("USD", BigDecimal("5.00"))])
        assertEquals(listOf("Alfred", "Monica"), usdShare.map { it.name() })
        val eurShare = assertNotNull(b1.royalties()[JMoney("EUR", BigDecimal("4.50"))])
        assertEquals(listOf("Alfred"), eurShare.map { it.name() })

        assertEquals(BigDecimal("79.95"), b1.regionalPrice()["US"])
        assertEquals(BigDecimal("75.00"), b1.regionalPrice()["EU"])

        val b2 = books[1]
        assertEquals("b-200", b2.id())
        assertNull(b2.lang())
        assertEquals(listOf("Robert"), b2.authors().map { it.name() })
        assertEquals(listOf<String?>(null), b2.authors().map { it.country() })
        assertEquals("978-0132314527", b2.isbn())
        val eb = assertIs<JEbook>(b2.format())
        assertEquals(12.4, eb.sizeMb())
        assertTrue(b2.royalties().isEmpty())
        assertTrue(b2.regionalPrice().isEmpty())
        assertEquals(3, b2.rating())
    }
}
