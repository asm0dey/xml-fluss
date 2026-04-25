package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

@XmlRecord("//envelope")
data class Envelope(
    @XmlAttr("id") val id: String,
    @XmlChild("payload") val payload: String,
)

@XmlRecord("//msg")
data class EmbeddedMsg(
    @XmlAttr("id") val id: String,
    @XmlChild("body") val body: String,
)
