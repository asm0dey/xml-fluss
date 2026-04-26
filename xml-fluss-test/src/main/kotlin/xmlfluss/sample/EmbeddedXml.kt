package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord(path = "//envelope")
data class Envelope(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "payload") val payload: String,
)

@XmlRecord(path = "//msg")
data class EmbeddedMsg(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "body") val body: String,
)
