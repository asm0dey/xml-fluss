package xmlfluss.sample.opds

import xmlfluss.*
import java.math.BigDecimal
import java.time.Instant

data class AtomPerson(
    @XmlChild(path = "atom:name") val name: String,
    @XmlChild(path = "atom:uri") val uri: String?,
    @XmlChild(path = "atom:email") val email: String?,
)

data class OpdsCategory(
    @XmlAttr val term: String,
    @XmlAttr val label: String?,
    @XmlAttr val scheme: String?,
)

data class OpdsPrice(
    @XmlAttr(name = "currencycode") val currencyCode: String,
    @XmlText val amount: BigDecimal,
)

data class OpdsIndirectAcquisition(
    @XmlAttr val type: String,
    @XmlChild(path = "opds:indirectAcquisition") val children: List<OpdsIndirectAcquisition>,
)

data class OpdsLink(
    @XmlAttr val href: String,
    @XmlAttr val rel: String?,
    @XmlAttr val type: String?,
    @XmlAttr val hreflang: String?,
    @XmlAttr val title: String?,
    @XmlAttr val length: Long?,
    @XmlChild(path = "opds:price") val prices: List<OpdsPrice>,
    @XmlChild(path = "opds:indirectAcquisition") val indirectAcquisitions: List<OpdsIndirectAcquisition>,
)

data class OpdsTextNode(
    @XmlAttr val type: String?,
    @XmlAttr val src: String?,
    @XmlText val text: String,
)

@XmlRecord(path = "//atom:entry")
@XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
@XmlNs(prefix = "opds", uri = "http://opds-spec.org/2010/catalog")
@XmlNs(prefix = "dc", uri = "http://purl.org/dc/terms/")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
data class OpdsEntry(
    @XmlChild(path = "atom:id") val id: String,
    @XmlChild(path = "atom:title") val title: String,
    @XmlChild(path = "atom:updated") val updated: Instant,
    @XmlChild(path = "atom:published") val published: Instant?,
    @XmlChild(path = "atom:rights") val rights: String?,
    @XmlChild(path = "atom:summary") val summary: OpdsTextNode?,
    @XmlChild(path = "atom:content") val content: OpdsTextNode?,
    @XmlChild(path = "atom:author") val authors: List<AtomPerson>,
    @XmlChild(path = "atom:contributor") val contributors: List<AtomPerson>,
    @XmlChild(path = "atom:category") val categories: List<OpdsCategory>,
    @XmlChild(path = "atom:link") val links: List<OpdsLink>,
    @XmlChild(path = "dc:language") val language: String?,
    @XmlChild(path = "dc:publisher") val publisher: String?,
    @XmlChild(path = "dc:identifier") val identifiers: List<String>,
    @XmlChild(path = "dc:issued") val issued: String?,
    @XmlChild(path = "dc:relation") val relation: String?,
    @XmlChild(path = "dc:source") val dcSource: String?,
    @XmlAttr(name = "xml:lang") val lang: String?,
)

@XmlRecord(path = "//atom:feed")
@XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
@XmlNs(prefix = "opds", uri = "http://opds-spec.org/2010/catalog")
@XmlNs(prefix = "dc", uri = "http://purl.org/dc/terms/")
@XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
@XmlNs(prefix = "os", uri = "http://a9.com/-/spec/opensearch/1.1/")
data class OpdsFeed(
    @XmlChild(path = "atom:id") val id: String,
    @XmlChild(path = "atom:title") val title: String,
    @XmlChild(path = "atom:subtitle") val subtitle: String?,
    @XmlChild(path = "atom:updated") val updated: Instant,
    @XmlChild(path = "atom:icon") val icon: String?,
    @XmlChild(path = "atom:logo") val logo: String?,
    @XmlChild(path = "atom:rights") val rights: String?,
    @XmlChild(path = "atom:author") val authors: List<AtomPerson>,
    @XmlChild(path = "atom:contributor") val contributors: List<AtomPerson>,
    @XmlChild(path = "atom:category") val categories: List<OpdsCategory>,
    @XmlChild(path = "atom:link") val links: List<OpdsLink>,
    @XmlChild(path = "atom:entry") val entries: List<OpdsEntry>,
    @XmlChild(path = "os:totalResults") val totalResults: Int?,
    @XmlChild(path = "os:itemsPerPage") val itemsPerPage: Int?,
    @XmlChild(path = "os:startIndex") val startIndex: Int?,
    @XmlAttr(name = "xml:lang") val lang: String?,
)
