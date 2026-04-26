package xmlfluss.sample

import xmlfluss.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@XmlRecord(path = "//book[@featured='true']")
@XmlNs(prefix = "dc", uri = "http://purl.org/dc/elements/1.1/")
data class Book(
    @XmlAttr(name = "id") val id: Int,
    @XmlAttr(name = "featured") val featured: Boolean,
    @XmlAttr(name = "isbn") val isbn: String?,
    @XmlChild(path = "title") val title: String,
    @XmlChild(path = "subtitle") val subtitle: String?,
    @XmlChild(path = "pages") val pages: Long,
    @XmlChild(path = "rating") val rating: Double,
    @XmlChild(path = "inPrint") val inPrint: Boolean,
    @XmlChild(path = "dc:tags/dc:tag") val tags: List<String>,
    @XmlChild(path = "meta/published") @XmlFormat(pattern = "yyyy-MM-dd") val published: LocalDate,
    @XmlChild(path = "meta/updatedAt") val updatedAt: LocalDateTime,
    @XmlChild(path = "meta/indexedAt") val indexedAt: Instant,
    @XmlChild(path = "price") @XmlConverter(cls = MoneyConverter::class) val price: Money,
    @XmlChild(path = "price/@currency") val currency: String,
    @XmlChild(path = "totalSales") val totalSales: BigDecimal,
    @XmlText val raw: String,
)
