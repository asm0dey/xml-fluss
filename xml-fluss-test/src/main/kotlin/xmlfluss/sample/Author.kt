package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord("//author")
data class Author(
    @XmlAttr("id") val id: Int,
    @XmlAttr("role") val role: String?,
    @XmlChild("name") val name: String,
    @XmlChild("bio") val bio: String?,
)
