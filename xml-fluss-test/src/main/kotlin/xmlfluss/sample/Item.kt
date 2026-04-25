package xmlfluss.sample

import xmlfluss.Converter
import xmlfluss.Location
import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlConverter
import xmlfluss.XmlFormat
import xmlfluss.XmlParseException
import xmlfluss.XmlRecord
import xmlfluss.XmlText
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

@XmlRecord("//item")
data class Item(
    @XmlAttr("id") val id: Int,
    @XmlChild("name") val name: String,
    @XmlChild("tag") val tags: List<String>,
    @XmlChild("meta/lang") val lang: String?,
    @XmlChild("info/@kind") val kind: String?,
    @XmlChild("published") @XmlFormat("yyyy-MM-dd") val published: LocalDate,
    @XmlChild("price") @XmlConverter(MoneyConverter::class) val price: Money,
    @XmlText val raw: String,
)
