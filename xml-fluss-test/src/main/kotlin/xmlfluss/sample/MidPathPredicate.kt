package xmlfluss.sample

import xmlfluss.XmlChild
import xmlfluss.XmlRecord

/**
 * Predicate sits on the middle segment of a multi-segment `@XmlChild` path. Confirms that
 * predicate dispatch isn't limited to the head — the trie carries predicates per edge.
 */
@XmlRecord(path = "//doc")
data class MidPathDoc(
    @XmlChild(path = "wrapper/section[@kind='intro']/title") val introTitle: String?,
    @XmlChild(path = "wrapper/section[@kind='body']/title") val bodyTitle: String?,
    @XmlChild(path = "wrapper/section[@kind='body']/note") val bodyNotes: List<String>,
)
