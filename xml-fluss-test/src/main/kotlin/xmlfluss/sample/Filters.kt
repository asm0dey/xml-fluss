package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlNs
import xmlfluss.XmlRecord

@XmlRecord(path = "//author[@role!='main']")
data class NotMainAuthor(
    @XmlAttr(name = "role") val role: String,
    @XmlChild(path = "name") val name: String,
)

@XmlRecord(path = "//author[@role='main' and @active='true']")
data class ActiveMainAuthor(
    @XmlChild(path = "name") val name: String,
)

@XmlRecord(path = "//author[@role='main' or @role='editor']")
data class MainOrEditor(
    @XmlAttr(name = "role") val role: String,
    @XmlChild(path = "name") val name: String,
)

@XmlRecord(path = "//author[2]")
data class SecondAuthor(
    @XmlChild(path = "name") val name: String,
)

@XmlRecord(path = "//note[@xml:lang='en']")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
data class EnglishNote(
    @XmlChild(path = "text") val text: String,
)

@XmlRecord(path = "//note[@xml:lang!='en']")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
data class NonEnglishNote(
    @XmlChild(path = "text") val text: String,
)
