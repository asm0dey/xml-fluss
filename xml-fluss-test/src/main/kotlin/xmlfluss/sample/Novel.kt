package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord
import xmlfluss.XmlText

data class Person(
    @XmlAttr(name = "id") val id: Int,
    @XmlChild(path = "name") val name: String,
    @XmlChild(path = "country") val country: String?,
)

data class Chapter(
    @XmlAttr(name = "n") val n: Int,
    @XmlChild(path = "title") val title: String,
    @XmlText val excerpt: String,
)

@XmlRecord(path = "//novel")
data class Novel(
    @XmlAttr(name = "id") val id: Int,
    @XmlChild(path = "title") val title: String,
    @XmlChild(path = "author") val author: Person,
    @XmlChild(path = "editor") val editor: Person?,
    @XmlChild(path = "chapter") val chapters: List<Chapter>,
)
