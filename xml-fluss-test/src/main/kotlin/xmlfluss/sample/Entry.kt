package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlNs
import xmlfluss.XmlRecord

@XmlRecord(path = "//atom:entry")
@XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
data class Entry(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "atom:title") val title: String,
    @XmlChild(path = "atom:summary") val summary: String?,
)

@XmlRecord(path = "//entry")
@XmlNs(prefix = "", uri = "http://www.w3.org/2005/Atom")
data class DefaultNsEntry(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "title") val title: String,
)
