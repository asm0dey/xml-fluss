package xmlfluss.sample.opds

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OpdsEntryParserTest {

    private val syntheticFeed: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom"
              xmlns:opds="http://opds-spec.org/2010/catalog"
              xmlns:dc="http://purl.org/dc/terms/"
              xmlns:os="http://a9.com/-/spec/opensearch/1.1/"
              xml:lang="en">
          <id>urn:uuid:opds-catalog</id>
          <title>Sample OPDS Catalog</title>
          <subtitle>Streaming demo</subtitle>
          <updated>2026-04-25T09:00:00Z</updated>
          <icon>/favicon.ico</icon>
          <rights>CC-BY-SA</rights>
          <author><name>Catalog Bot</name><uri>https://example.com/bot</uri></author>
          <link rel="self" href="/opds" type="application/atom+xml;profile=opds-catalog"/>
          <os:totalResults>2</os:totalResults>
          <os:itemsPerPage>50</os:itemsPerPage>
          <os:startIndex>1</os:startIndex>

          <entry xml:lang="en">
            <id>urn:isbn:9780000000001</id>
            <title>The Streaming Manual</title>
            <updated>2026-04-20T12:00:00Z</updated>
            <published>2025-12-01T00:00:00Z</published>
            <rights>(c) 2026 Example Press</rights>
            <author>
              <name>Ada Lovelace</name>
              <uri>https://example.com/ada</uri>
              <email>ada@example.com</email>
            </author>
            <author><name>Grace Hopper</name></author>
            <contributor><name>Editor X</name></contributor>
            <dc:language>en</dc:language>
            <dc:publisher>Example Press</dc:publisher>
            <dc:identifier>urn:isbn:9780000000001</dc:identifier>
            <dc:identifier>doi:10.0000/streaming</dc:identifier>
            <dc:issued>2025</dc:issued>
            <dc:relation>urn:isbn:9780000000003</dc:relation>
            <dc:source>https://example.com/originals</dc:source>
            <summary type="text">Hands-on guide to streaming XML.</summary>
            <content type="text">Long-form description here.</content>
            <category term="fiction" label="Fiction" scheme="http://example.com/cat"/>
            <category term="programming" label="Programming"/>
            <link rel="http://opds-spec.org/acquisition/buy"
                  href="/d/9780000000001.epub"
                  type="application/epub+zip"
                  title="EPUB"
                  hreflang="en"
                  length="1048576">
              <opds:price currencycode="USD">3.99</opds:price>
              <opds:price currencycode="EUR">3.49</opds:price>
              <opds:indirectAcquisition type="application/vnd.adobe.adept+xml">
                <opds:indirectAcquisition type="application/epub+zip"/>
              </opds:indirectAcquisition>
            </link>
            <link rel="http://opds-spec.org/image"
                  href="/cover/1.jpg"
                  type="image/jpeg"/>
          </entry>

          <entry>
            <id>urn:isbn:9780000000002</id>
            <title>KSP for Mortals</title>
            <updated>2026-04-22T09:30:00Z</updated>
            <dc:language>de</dc:language>
            <category term="programming"/>
            <link rel="alternate"
                  href="/book/2"
                  type="application/atom+xml;type=entry;profile=opds-catalog"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun parsesAllEntriesAsRecords() = runTest {
        val entries = OpdsEntryParser.parse(syntheticFeed.byteInputStream()).toList()
        assertEquals(2, entries.size)
    }

    @Test
    fun firstEntryFullyPopulated() = runTest {
        val e = OpdsEntryParser.parse(syntheticFeed.byteInputStream()).toList()[0]
        assertEquals("urn:isbn:9780000000001", e.id)
        assertEquals("The Streaming Manual", e.title)
        assertEquals(Instant.parse("2026-04-20T12:00:00Z"), e.updated)
        assertEquals(Instant.parse("2025-12-01T00:00:00Z"), e.published)
        assertEquals("(c) 2026 Example Press", e.rights)
        assertEquals("en", e.lang)
        assertEquals("en", e.language)
        assertEquals("Example Press", e.publisher)
        assertEquals("2025", e.issued)
        assertEquals("urn:isbn:9780000000003", e.relation)
        assertEquals("https://example.com/originals", e.dcSource)
        assertEquals(OpdsTextNode("text", null, "Hands-on guide to streaming XML."), e.summary)
        assertEquals(OpdsTextNode("text", null, "Long-form description here."), e.content)

        assertEquals(2, e.authors.size)
        assertEquals(AtomPerson("Ada Lovelace", "https://example.com/ada", "ada@example.com"), e.authors[0])
        assertEquals(AtomPerson("Grace Hopper", null, null), e.authors[1])
        assertEquals(listOf(AtomPerson("Editor X", null, null)), e.contributors)

        assertEquals(listOf("urn:isbn:9780000000001", "doi:10.0000/streaming"), e.identifiers)
        assertEquals(
            listOf(
                OpdsCategory("fiction", "Fiction", "http://example.com/cat"),
                OpdsCategory("programming", "Programming", null),
            ),
            e.categories,
        )

        assertEquals(2, e.links.size)
        val buy = e.links[0]
        assertEquals("/d/9780000000001.epub", buy.href)
        assertEquals("http://opds-spec.org/acquisition/buy", buy.rel)
        assertEquals("application/epub+zip", buy.type)
        assertEquals("EPUB", buy.title)
        assertEquals("en", buy.hreflang)
        assertEquals(1048576L, buy.length)
        assertEquals(
            listOf(
                OpdsPrice("USD", BigDecimal("3.99")),
                OpdsPrice("EUR", BigDecimal("3.49")),
            ),
            buy.prices,
        )
        assertEquals(
            listOf(
                OpdsIndirectAcquisition(
                    type = "application/vnd.adobe.adept+xml",
                    children = listOf(OpdsIndirectAcquisition("application/epub+zip", emptyList())),
                ),
            ),
            buy.indirectAcquisitions,
        )

        val cover = e.links[1]
        assertEquals("/cover/1.jpg", cover.href)
        assertEquals("http://opds-spec.org/image", cover.rel)
        assertNull(cover.length)
        assertNull(cover.title)
        assertEquals(emptyList(), cover.prices)
        assertEquals(emptyList(), cover.indirectAcquisitions)
    }

    @Test
    fun secondEntryMinimal() = runTest {
        val e = OpdsEntryParser.parse(syntheticFeed.byteInputStream()).toList()[1]
        assertEquals("urn:isbn:9780000000002", e.id)
        assertEquals("KSP for Mortals", e.title)
        assertNull(e.lang)
        assertNull(e.published)
        assertNull(e.rights)
        assertNull(e.summary)
        assertNull(e.content)
        assertEquals(emptyList(), e.authors)
        assertEquals(emptyList(), e.contributors)
        assertNull(e.publisher)
        assertEquals("de", e.language)
        assertEquals(emptyList(), e.identifiers)
        assertNull(e.issued)
        assertNull(e.relation)
        assertNull(e.dcSource)
        assertEquals(listOf(OpdsCategory("programming", null, null)), e.categories)
        assertEquals(1, e.links.size)
        val l = e.links.single()
        assertEquals("/book/2", l.href)
        assertEquals("alternate", l.rel)
        assertNotNull(l.type)
        assertNull(l.length)
        assertNull(l.hreflang)
        assertEquals(emptyList(), l.prices)
        assertEquals(emptyList(), l.indirectAcquisitions)
    }

    @Test
    fun parsesWholeFeed() = runTest {
        val feed = OpdsFeedParser.parse(syntheticFeed.byteInputStream()).toList().single()
        assertEquals("urn:uuid:opds-catalog", feed.id)
        assertEquals("Sample OPDS Catalog", feed.title)
        assertEquals("Streaming demo", feed.subtitle)
        assertEquals(Instant.parse("2026-04-25T09:00:00Z"), feed.updated)
        assertEquals("/favicon.ico", feed.icon)
        assertNull(feed.logo)
        assertEquals("CC-BY-SA", feed.rights)
        assertEquals("en", feed.lang)
        assertEquals(2, feed.totalResults)
        assertEquals(50, feed.itemsPerPage)
        assertEquals(1, feed.startIndex)
        assertEquals(listOf(AtomPerson("Catalog Bot", "https://example.com/bot", null)), feed.authors)
        assertEquals(emptyList(), feed.contributors)
        assertEquals(emptyList(), feed.categories)
        assertEquals(1, feed.links.size)
        assertEquals("/opds", feed.links.single().href)
        assertEquals(2, feed.entries.size)
        assertEquals("urn:isbn:9780000000001", feed.entries[0].id)
        assertEquals("urn:isbn:9780000000002", feed.entries[1].id)
        // Nested OpdsEntry under OpdsFeed reuses the same field machinery.
        val nestedFirst = feed.entries[0]
        assertEquals(2, nestedFirst.authors.size)
        assertEquals(2, nestedFirst.links[0].prices.size)
    }

    @Test
    fun parsesFlibustaRootCatalog() = runTest {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("flibusta-opds.xml")) {
            "fixture flibusta-opds.xml not found on test classpath"
        }
        val feed = OpdsFeedParser.parse(stream).toList().single()

        assertEquals("tag:root", feed.id)
        assertEquals("Flibusta catalog", feed.title)
        assertNull(feed.subtitle)
        assertEquals(Instant.parse("2026-04-24T13:31:46Z"), feed.updated)
        assertEquals("/favicon.ico", feed.icon)
        assertNull(feed.logo)
        assertNull(feed.rights)
        assertNull(feed.lang)
        assertEquals(emptyList(), feed.authors)
        assertEquals(emptyList(), feed.contributors)
        assertEquals(emptyList(), feed.categories)
        assertNull(feed.totalResults)
        assertNull(feed.itemsPerPage)
        assertNull(feed.startIndex)

        assertEquals(4, feed.links.size)
        assertEquals(
            listOf("/opds-opensearch.xml", "/opds/search?searchTerm={searchTerms}", "/opds", "/opds"),
            feed.links.map { it.href },
        )
        assertEquals(listOf("search", "search", "start", "self"), feed.links.map { it.rel })
        assertEquals(
            listOf(
                "application/opensearchdescription+xml",
                "application/atom+xml",
                "application/atom+xml;profile=opds-catalog",
                "application/atom+xml;profile=opds-catalog",
            ),
            feed.links.map { it.type },
        )
        for (l in feed.links) {
            assertNull(l.title)
            assertNull(l.hreflang)
            assertNull(l.length)
            assertEquals(emptyList(), l.prices)
            assertEquals(emptyList(), l.indirectAcquisitions)
        }

        assertEquals(5, feed.entries.size)
        assertEquals(
            listOf("tag:root:new", "tag:root:authors", "tag:root:sequences", "tag:root:genre", "tag:root:shelf"),
            feed.entries.map { it.id },
        )
        assertEquals(
            listOf("Новинки", "По авторам", "По сериям", "По жанрам", "Моя полка"),
            feed.entries.map { it.title },
        )

        val first = feed.entries[0]
        assertEquals(Instant.parse("2026-04-24T13:31:46Z"), first.updated)
        assertEquals(OpdsTextNode("text", null, "Новые поступления за неделю"), first.content)
        assertNull(first.summary)
        assertEquals(emptyList(), first.authors)
        assertEquals(emptyList(), first.contributors)
        assertEquals(emptyList(), first.categories)
        assertEquals(emptyList(), first.identifiers)
        assertNull(first.language)
        assertNull(first.publisher)
        assertNull(first.published)
        assertNull(first.rights)
        assertEquals(2, first.links.size)
        assertEquals("/opds/new", first.links[0].href)
        assertEquals("http://opds-spec.org/sort/new", first.links[0].rel)
        assertNull(first.links[1].rel)
        for (e in feed.entries) {
            assertNotNull(e.title)
            assertNotNull(e.updated)
            assertEquals(emptyList(), e.authors)
            assertEquals(emptyList(), e.categories)
            assertEquals(emptyList(), e.identifiers)
            assert(e.links.isNotEmpty()) { "entry ${e.id} has no links" }
        }
    }

    @Test
    fun streamingEntriesMatchesNestedEntries() = runTest {
        // OpdsEntryParser streams each <atom:entry> directly; OpdsFeedParser builds the same entries
        // via nested codegen. Both must agree.
        val viaEntry = OpdsEntryParser.parse(syntheticFeed.byteInputStream()).toList()
        val viaFeed = OpdsFeedParser.parse(syntheticFeed.byteInputStream()).toList().single().entries
        assertEquals(viaEntry, viaFeed)
    }
}
