package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlNs
import xmlfluss.XmlRecord

@XmlRecord("//atom:entry")
@XmlNs("atom", "http://www.w3.org/2005/Atom")
data class Entry(
    @XmlAttr("id") val id: String,
    @XmlChild("atom:title") val title: String,
    @XmlChild("atom:summary") val summary: String?,
)

@XmlRecord("//entry")
@XmlNs("", "http://www.w3.org/2005/Atom")
data class DefaultNsEntry(
    @XmlAttr("id") val id: String,
    @XmlChild("title") val title: String,
)
