package xmlfluss.sample

import xmlfluss.*

@XmlRecord(path = "//note")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
@XmlNs(prefix = "x", uri = "http://example.com/x")
data class Note(
    @XmlAttr(name = "id") val id: String,
    @XmlAttr(name = "xml:lang") val lang: String,
    @XmlAttr(name = "x:tag") val tag: String?,
    @XmlChild(path = "body") val body: String,
)

data class Para(
    @XmlAttr(name = "xml:lang") val lang: String,
    @XmlAttr(name = "x:role") val role: String?,
    @XmlChild(path = "text") val text: String,
)

// Root of this nested type lives in the 'x' namespace inherited from Doc.
// Children stay in null NS unless prefixed.
data class Sticker(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "x:label") val label: String,
)

@XmlRecord(path = "//x:feed")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
@XmlNs(prefix = "x", uri = "http://example.com/x")
data class Feed(
    @XmlAttr(name = "id") val id: String,
)

@XmlRecord(path = "//doc")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
@XmlNs(prefix = "x", uri = "http://example.com/x")
data class Doc(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "para") val paras: List<Para>,
    @XmlChild(path = "x:sticker") val stickers: List<Sticker>,
    @XmlChild(path = "title/@xml:lang") val titleLang: String,
    @XmlChild(path = "meta/inner/@x:flag") val innerFlag: String?,
    @XmlMap(entry = "trans", key = "@xml:lang", value = "@x:val") val translations: Map<String, String>,
)
