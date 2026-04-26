package xmlfluss.sample.showcase

import xmlfluss.*
import java.math.BigDecimal
import java.time.LocalDate

/**
 * KSP showcase exercising every advanced feature on one record:
 *   - two namespaces (lib + m) with prefixes
 *   - multi-segment, descendant axis, and attribute-leaf @XmlChild paths
 *   - predicate-filtered @XmlChild (epubHref / epubRel / atomHref) — pick attributes off
 *     the <link> whose type matches a specific MIME, ignore siblings
 *   - polymorphic tag-mode (Format) with three subtypes
 *   - nested record (Money) and List<nested> (Author)
 *   - @XmlMap with a nested-record key and List<nested-record> value
 *   - @XmlMap with attribute key and multi-segment value
 *   - @XmlConverter (rating) and @XmlFormat (LocalDate)
 *   - nullable scalar (lang)
 */

data class Author(
    @XmlAttr val name: String,
    @XmlAttr val country: String?,
)

data class Money(
    @XmlAttr val currency: String,
    @XmlText val amount: BigDecimal,
)

@XmlPolymorphic
sealed interface Format {
    @XmlSubtype(name = "paperback")
    data class Paperback(@XmlAttr val pages: Int) : Format

    @XmlSubtype(name = "ebook")
    data class Ebook(@XmlAttr val sizeMb: Double) : Format

    @XmlSubtype(name = "audio")
    data class Audio(@XmlAttr(name = "durationMin") val duration: Int) : Format
}

class StarRatingConverter : Converter<Int> {
    override fun convert(raw: String, loc: Location): Int = raw.trim().count { it == '*' }
}

@XmlRecord(path = "//lib:catalog/lib:book")
@XmlNs(prefix = "lib", uri = "https://lib.example.com/v1")
@XmlNs(prefix = "m", uri = "https://lib.example.com/money")
data class Book(
    @XmlAttr val id: String,
    @XmlChild val title: String,
    @XmlChild(path = "bio/@lang") val lang: String?,
    @XmlChild(path = "authors/author") val authors: List<Author>,
    @XmlChild(path = "//isbn") val isbn: String,
    @XmlChild val format: Format,
    @XmlChild(path = "m:price") val price: Money,
    @XmlChild(path = "published") @XmlFormat(pattern = "yyyy-MM-dd") val published: LocalDate,
    @XmlChild(path = "rating") @XmlConverter(cls = StarRatingConverter::class) val rating: Int,
    @XmlMap(entry = "m:royalty", key = "price", value = "share/author")
    val royalties: Map<Money, List<Author>>,
    @XmlMap(entry = "regional", key = "@region", value = "amount")
    val regionalPrice: Map<String, BigDecimal>,
    @XmlChild(path = "link[@type='application/epub+zip']/@href") val epubHref: String?,
    @XmlChild(path = "link[@type='application/epub+zip']/@rel") val epubRel: String?,
    @XmlChild(path = "link[@type='application/atom+xml']/@href") val atomHref: String?,
    // Chained-bracket predicate: implicit AND of two attribute checks.
    @XmlChild(path = "link[@type='application/epub+zip'][@rel='http://opds-spec.org/acquisition']/@href")
    val acquisitionEpubHref: String?,
)
