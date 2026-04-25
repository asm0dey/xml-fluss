package xmlfluss.sample.full

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlConverter
import xmlfluss.XmlFormat
import xmlfluss.XmlRecord
import xmlfluss.XmlText
import xmlfluss.sample.Money
import xmlfluss.sample.MoneyConverter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class Royalty(
    @XmlAttr("currency") val currency: String,
    @XmlText val amount: BigDecimal,
)

data class Agent(
    @XmlAttr("id") val id: Int,
    @XmlChild("name") val name: String,
)

data class Book(
    @XmlAttr("isbn") val isbn: String,
    @XmlAttr("featured") val featured: Boolean,
    @XmlChild("title") val title: String,
    @XmlChild("subtitle") val subtitle: String?,
    @XmlChild("pages") val pages: Long,
    @XmlChild("rating") val rating: Double,
    @XmlChild("published") @XmlFormat("MM/dd/yyyy") val published: LocalDate,
    @XmlChild("updated") val updated: LocalDateTime,
    @XmlChild("indexed") val indexed: Instant,
    @XmlChild("listPrice") @XmlConverter(MoneyConverter::class) val listPrice: Money,
    @XmlChild("listPrice/@currency") val priceCurrency: String,
    @XmlChild("totalSales") @XmlFormat("#,##0.00") val totalSales: BigDecimal,
    @XmlChild("tag") val tags: List<String>,
    @XmlChild("royalty") val royalty: Royalty,
)

data class Author(
    @XmlAttr("id") val id: Int,
    @XmlChild("name") val name: String,
    @XmlChild("country") val country: String?,
    @XmlChild("agent") val agent: Agent?,
    @XmlChild("//book") val books: List<Book>,
)

@XmlRecord("//catalog")
data class Catalog(
    @XmlAttr("version") val version: Int,
    @XmlChild("//author") val authors: List<Author>,
)
