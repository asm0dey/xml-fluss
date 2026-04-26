package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord(path = "//author")
data class Author(
    @XmlAttr(name = "id") val id: Int,
    @XmlAttr(name = "role") val role: String?,
    @XmlChild(path = "name") val name: String,
    @XmlChild(path = "bio") val bio: String?,
)
