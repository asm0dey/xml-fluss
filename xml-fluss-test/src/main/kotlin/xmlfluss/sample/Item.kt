package xmlfluss.sample

import xmlfluss.*
import java.math.BigDecimal
import java.time.LocalDate

data class Money(val currency: String, val amount: BigDecimal)

class MoneyConverter : Converter<Money> {
    override fun convert(raw: String, loc: Location): Money {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size != 2) throw XmlParseException.Coercion("price", raw, "Money", loc, IllegalArgumentException("expected '<CCY> <amount>'"))
        val amount = try { BigDecimal(parts[1]) }
        catch (e: Exception) { throw XmlParseException.Coercion("price", raw, "Money", loc, e) }
        return Money(parts[0], amount)
    }
}

@XmlRecord(path = "//item")
data class Item(
    @XmlAttr(name = "id") val id: Int,
    @XmlChild(path = "name") val name: String,
    @XmlChild(path = "tag") val tags: List<String>,
    @XmlChild(path = "meta/lang") val lang: String?,
    @XmlChild(path = "info/@kind") val kind: String?,
    @XmlChild(path = "published") @XmlFormat(pattern = "yyyy-MM-dd") val published: LocalDate,
    @XmlChild(path = "price") @XmlConverter(cls = MoneyConverter::class) val price: Money,
    @XmlText val raw: String,
)
