package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlMap
import xmlfluss.XmlNs
import xmlfluss.XmlRecord

@XmlRecord("//note")
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
@XmlNs("x", "http://example.com/x")
data class Note(
    @XmlAttr("id") val id: String,
    @XmlAttr("xml:lang") val lang: String,
    @XmlAttr("x:tag") val tag: String?,
    @XmlChild("body") val body: String,
)

data class Para(
    @XmlAttr("xml:lang") val lang: String,
    @XmlAttr("x:role") val role: String?,
    @XmlChild("text") val text: String,
)

// Root of this nested type lives in the 'x' namespace inherited from Doc.
// Children stay in null NS unless prefixed.
data class Sticker(
    @XmlAttr("id") val id: String,
    @XmlChild("x:label") val label: String,
)

@XmlRecord("//x:feed")
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
@XmlNs("x", "http://example.com/x")
data class Feed(
    @XmlAttr("id") val id: String,
)

@XmlRecord("//doc")
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
@XmlNs("x", "http://example.com/x")
data class Doc(
    @XmlAttr("id") val id: String,
    @XmlChild("para") val paras: List<Para>,
    @XmlChild("x:sticker") val stickers: List<Sticker>,
    @XmlChild("title/@xml:lang") val titleLang: String,
    @XmlChild("meta/inner/@x:flag") val innerFlag: String?,
    @XmlMap(entry = "trans", key = "@xml:lang", value = "@x:val") val translations: Map<String, String>,
)
