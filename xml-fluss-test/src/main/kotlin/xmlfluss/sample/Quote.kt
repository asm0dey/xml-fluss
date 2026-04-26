package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord(path = "//author[@role='main']")
data class MainAuthor(
    @XmlAttr(name = "role") val role: String,
    @XmlChild(path = "name") val name: String,
)
