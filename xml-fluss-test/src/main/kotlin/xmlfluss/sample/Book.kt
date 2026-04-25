package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlConverter
import xmlfluss.XmlFormat
import xmlfluss.XmlNs
import xmlfluss.XmlRecord
import xmlfluss.XmlText
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@XmlRecord("//book[@featured='true']")
@XmlNs("dc", "http://purl.org/dc/elements/1.1/")
data class Book(
    @XmlAttr("id") val id: Int,
    @XmlAttr("featured") val featured: Boolean,
    @XmlAttr("isbn") val isbn: String?,
    @XmlChild("title") val title: String,
    @XmlChild("subtitle") val subtitle: String?,
    @XmlChild("pages") val pages: Long,
    @XmlChild("rating") val rating: Double,
    @XmlChild("inPrint") val inPrint: Boolean,
    @XmlChild("dc:tags/dc:tag") val tags: List<String>,
    @XmlChild("meta/published") @XmlFormat("yyyy-MM-dd") val published: LocalDate,
    @XmlChild("meta/updatedAt") val updatedAt: LocalDateTime,
    @XmlChild("meta/indexedAt") val indexedAt: Instant,
    @XmlChild("price") @XmlConverter(MoneyConverter::class) val price: Money,
    @XmlChild("price/@currency") val currency: String,
    @XmlChild("totalSales") val totalSales: BigDecimal,
    @XmlText val raw: String,
)
