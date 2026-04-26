package xmlfluss.sample.japt;

import org.jspecify.annotations.NonNull;
import xmlfluss.XmlAttr;
import xmlfluss.XmlChild;
import xmlfluss.XmlNs;
import xmlfluss.XmlRecord;

/**
 * Atom-style record exercising prefix-bound paths plus a nested record that inherits the
 * parent's namespace bindings.
 */
@XmlRecord(path = "//atom:entry")
@XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
public record JEntry(
        @XmlAttr(name = "id") @NonNull String id,
        @XmlChild(path = "atom:title") @NonNull String title,
        @XmlChild(path = "atom:author") JAtomAuthor author
) {
    public record JAtomAuthor(
            @XmlChild(path = "atom:name") @NonNull String name,
            @XmlChild(path = "atom:email") String email
    ) {}
}
