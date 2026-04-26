package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

/**
 * Chained-bracket predicates follow XPath semantics: brackets evaluate in order, and a
 * positional check inside a later bracket counts only same-name siblings under the parent
 * that already passed every earlier bracket. So `[@kind='post'][2]` matches "the 2nd
 * `<item>` carrying `kind='post'`" — not "the 2nd `<item>` overall, also a post".
 */
@XmlRecord(path = "//item[@kind='post'][2]")
data class SecondPostItem(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "title") val title: String,
)
