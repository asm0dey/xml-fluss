package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord(path = "//item[2]")
data class SecondItem(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "name") val name: String
)
