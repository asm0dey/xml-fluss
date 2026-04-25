package xmlfluss.sample.opds

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlNs
import xmlfluss.XmlRecord
import xmlfluss.XmlText
import java.math.BigDecimal
import java.time.Instant

data class AtomPerson(
    @XmlChild("atom:name") val name: String,
    @XmlChild("atom:uri") val uri: String?,
    @XmlChild("atom:email") val email: String?,
)

data class OpdsCategory(
    @XmlAttr val term: String,
    @XmlAttr val label: String?,
    @XmlAttr val scheme: String?,
)

data class OpdsPrice(
    @XmlAttr("currencycode") val currencyCode: String,
    @XmlText val amount: BigDecimal,
)

data class OpdsIndirectAcquisition(
    @XmlAttr val type: String,
    @XmlChild("opds:indirectAcquisition") val children: List<OpdsIndirectAcquisition>,
)

data class OpdsLink(
    @XmlAttr val href: String,
    @XmlAttr val rel: String?,
    @XmlAttr val type: String?,
    @XmlAttr val hreflang: String?,
    @XmlAttr val title: String?,
    @XmlAttr val length: Long?,
    @XmlChild("opds:price") val prices: List<OpdsPrice>,
    @XmlChild("opds:indirectAcquisition") val indirectAcquisitions: List<OpdsIndirectAcquisition>,
)

data class OpdsTextNode(
    @XmlAttr val type: String?,
    @XmlAttr val src: String?,
    @XmlText val text: String,
)

@XmlRecord("//atom:entry")
@XmlNs("atom", "http://www.w3.org/2005/Atom")
@XmlNs("opds", "http://opds-spec.org/2010/catalog")
@XmlNs("dc", "http://purl.org/dc/terms/")
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
data class OpdsEntry(
    @XmlChild("atom:id") val id: String,
    @XmlChild("atom:title") val title: String,
    @XmlChild("atom:updated") val updated: Instant,
    @XmlChild("atom:published") val published: Instant?,
    @XmlChild("atom:rights") val rights: String?,
    @XmlChild("atom:summary") val summary: OpdsTextNode?,
    @XmlChild("atom:content") val content: OpdsTextNode?,
    @XmlChild("atom:author") val authors: List<AtomPerson>,
    @XmlChild("atom:contributor") val contributors: List<AtomPerson>,
    @XmlChild("atom:category") val categories: List<OpdsCategory>,
    @XmlChild("atom:link") val links: List<OpdsLink>,
    @XmlChild("dc:language") val language: String?,
    @XmlChild("dc:publisher") val publisher: String?,
    @XmlChild("dc:identifier") val identifiers: List<String>,
    @XmlChild("dc:issued") val issued: String?,
    @XmlChild("dc:relation") val relation: String?,
    @XmlChild("dc:source") val dcSource: String?,
    @XmlAttr("xml:lang") val lang: String?,
)

@XmlRecord("//atom:feed")
@XmlNs("atom", "http://www.w3.org/2005/Atom")
@XmlNs("opds", "http://opds-spec.org/2010/catalog")
@XmlNs("dc", "http://purl.org/dc/terms/")
@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")
@XmlNs("os", "http://a9.com/-/spec/opensearch/1.1/")
data class OpdsFeed(
    @XmlChild("atom:id") val id: String,
    @XmlChild("atom:title") val title: String,
    @XmlChild("atom:subtitle") val subtitle: String?,
    @XmlChild("atom:updated") val updated: Instant,
    @XmlChild("atom:icon") val icon: String?,
    @XmlChild("atom:logo") val logo: String?,
    @XmlChild("atom:rights") val rights: String?,
    @XmlChild("atom:author") val authors: List<AtomPerson>,
    @XmlChild("atom:contributor") val contributors: List<AtomPerson>,
    @XmlChild("atom:category") val categories: List<OpdsCategory>,
    @XmlChild("atom:link") val links: List<OpdsLink>,
    @XmlChild("atom:entry") val entries: List<OpdsEntry>,
    @XmlChild("os:totalResults") val totalResults: Int?,
    @XmlChild("os:itemsPerPage") val itemsPerPage: Int?,
    @XmlChild("os:startIndex") val startIndex: Int?,
    @XmlAttr("xml:lang") val lang: String?,
)
