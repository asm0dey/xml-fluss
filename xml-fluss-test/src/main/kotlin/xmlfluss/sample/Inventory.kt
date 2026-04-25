package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlMap
import xmlfluss.XmlRecord

data class StockItem(
    @XmlAttr("id") val id: Int,
    @XmlChild("name") val name: String,
    @XmlChild("qty") val qty: Int,
)

@XmlRecord("//inventory")
data class Inventory(
    @XmlAttr("region") val region: String,
    @XmlMap(entry = "label", key = "@k", value = "@v") val labels: Map<String, String>,
    @XmlMap(entry = "count", key = "@sku", value = "n") val counts: Map<String, Int>,
    @XmlMap(entry = "tag", key = "@cat", value = "name") val tags: Map<String, List<String>>,
    @XmlMap(entry = "rule", key = "input", value = "@out") val rules: Map<List<Int>, String>,
    @XmlMap(entry = "item", key = "@sku", value = "stock") val stock: Map<String, StockItem>,
    @XmlMap(entry = "combo", key = "member", value = "score") val combos: Map<List<String>, List<Int>>,
    @XmlMap(entry = "tally", key = "@bucket", value = "@n") val tallies: Map<List<String>, List<Int>>,
    @XmlMap(entry = "opt", key = "@k", value = "v") val optionals: Map<String, Int?>,
    @XmlMap(entry = "rev", key = "@k", value = "v") val nullableKeys: Map<String?, Int>,
    @XmlMap(entry = "slot", key = "@k", value = "stock") val nullableNested: Map<String, StockItem?>,
    @XmlMap(entry = "extra", key = "@k", value = "@v") val extras: Map<String, String>?,
    @XmlChild("//report/title") val reportTitles: List<String>,
    @XmlChild("//report/@code") val reportCodes: List<String>,
    @XmlMap(entry = "summary", key = "//report/@code", value = "//report/title")
    val summaries: Map<List<String>, List<String>>,
)
