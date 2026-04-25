package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord("//item[2]")
data class SecondItem(
    @XmlAttr("id") val id: String,
    @XmlChild("name") val name: String
)
