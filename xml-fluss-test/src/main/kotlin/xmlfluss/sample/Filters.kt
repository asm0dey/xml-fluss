package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlNs
import xmlfluss.XmlRecord

@XmlRecord("//author[@role!='main']")
data class NotMainAuthor(
    @XmlAttr("role") val role: String,
    @XmlChild("name") val name: String,
)

@XmlRecord("//author[@role='main' and @active='true']")
data class ActiveMainAuthor(
    @XmlChild("name") val name: String,
)

@XmlRecord("//author[@role='main' or @role='editor']")
data class MainOrEditor(
    @XmlAttr("role") val role: String,
    @XmlChild("name") val name: String,
)

@XmlRecord("//author[2]")
data class SecondAuthor(
    @XmlChild("name") val name: String,
)

@XmlRecord("//note[@xml:lang='en']")
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
data class EnglishNote(
    @XmlChild("text") val text: String,
)

@XmlRecord("//note[@xml:lang!='en']")
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
data class NonEnglishNote(
    @XmlChild("text") val text: String,
)
