package xmlfluss.sample

import xmlfluss.XmlAttr
import xmlfluss.XmlChild
import xmlfluss.XmlRecord

/**
 * Chained-bracket predicates: `step[a][b]` is sugar for `step[a and b]`. Useful when
 * combining an attribute filter with a positional one (`[@kind='post'][2]`).
 *
 * Note: `[N]` counts ALL same-named siblings under the parent, not just those passing the
 * attribute predicate. So `[@kind='post'][2]` matches the element that is BOTH the 2nd
 * `<item>` sibling AND carries `kind="post"` — not "the 2nd post".
 */
@XmlRecord(path = "//item[@kind='post'][2]")
data class SecondPostItem(
    @XmlAttr(name = "id") val id: String,
    @XmlChild(path = "title") val title: String,
)
