package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord("//author[@role='main']")
data class MainAuthor(
    @XmlAttr("role") val role: String,
    @XmlChild("name") val name: String,
)
