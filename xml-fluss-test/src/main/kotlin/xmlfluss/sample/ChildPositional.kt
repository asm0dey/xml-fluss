package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord(path = "//feed")
data class FeedSecondItem(
    @XmlChild(path = "item[2]") val secondItem: ItemSummary,
)

@XmlRecord(path = "//item")
data class ItemSummary(
    @XmlAttr(name = "id") val id: String,
)

@XmlRecord(path = "//feed")
data class FeedSecondPostMetaPublished(
    @XmlChild(path = "meta[@kind='post'][2]/published") val published: String,
)

@XmlRecord(path = "//doc")
data class DocSecondSectionSecondParaFirstSpan(
    @XmlChild(path = "section[2]/para[2]/span[1]") val text: String,
)

@XmlRecord(path = "//entry")
data class EntrySecondEpubHref(
    @XmlChild(path = "link[@type='epub'][2]/@href") val href: String,
)

@XmlRecord(path = "//root")
data class RootSecondYUnderMatchingX(
    @XmlChild(path = "//x[@a='b']/y[2]") val ys: List<String>,
)

@XmlRecord(path = "//doc")
data class DocSecondItemPerSection(
    @XmlChild(path = "section/item[2]") val items: List<String>,
)
