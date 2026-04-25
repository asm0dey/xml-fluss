package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord
import xmlfluss.XmlText

data class Person(
    @XmlAttr("id") val id: Int,
    @XmlChild("name") val name: String,
    @XmlChild("country") val country: String?,
)

data class Chapter(
    @XmlAttr("n") val n: Int,
    @XmlChild("title") val title: String,
    @XmlText val excerpt: String,
)

@XmlRecord("//novel")
data class Novel(
    @XmlAttr("id") val id: Int,
    @XmlChild("title") val title: String,
    @XmlChild("author") val author: Person,
    @XmlChild("editor") val editor: Person?,
    @XmlChild("chapter") val chapters: List<Chapter>,
)
