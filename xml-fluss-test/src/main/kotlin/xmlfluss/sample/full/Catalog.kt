package xmlfluss.sample.full

import xmlfluss.*
import xmlfluss.sample.Money
import xmlfluss.sample.MoneyConverter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class Royalty(
    @XmlAttr(name = "currency") val currency: String,
    @XmlText val amount: BigDecimal,
)

data class Agent(
    @XmlAttr(name = "id") val id: Int,
    @XmlChild(path = "name") val name: String,
)

data class Book(
    @XmlAttr(name = "isbn") val isbn: String,
    @XmlAttr(name = "featured") val featured: Boolean,
    @XmlChild(path = "title") val title: String,
    @XmlChild(path = "subtitle") val subtitle: String?,
    @XmlChild(path = "pages") val pages: Long,
    @XmlChild(path = "rating") val rating: Double,
    @XmlChild(path = "published") @XmlFormat(pattern = "MM/dd/yyyy") val published: LocalDate,
    @XmlChild(path = "updated") val updated: LocalDateTime,
    @XmlChild(path = "indexed") val indexed: Instant,
    @XmlChild(path = "listPrice") @XmlConverter(cls = MoneyConverter::class) val listPrice: Money,
    @XmlChild(path = "listPrice/@currency") val priceCurrency: String,
    @XmlChild(path = "totalSales") @XmlFormat(pattern = "#,##0.00") val totalSales: BigDecimal,
    @XmlChild(path = "tag") val tags: List<String>,
    @XmlChild(path = "royalty") val royalty: Royalty,
)

data class Author(
    @XmlAttr(name = "id") val id: Int,
    @XmlChild(path = "name") val name: String,
    @XmlChild(path = "country") val country: String?,
    @XmlChild(path = "agent") val agent: Agent?,
    @XmlChild(path = "//book") val books: List<Book>,
)

@XmlRecord(path = "//catalog")
data class Catalog(
    @XmlAttr(name = "version") val version: Int,
    @XmlChild(path = "//author") val authors: List<Author>,
)
