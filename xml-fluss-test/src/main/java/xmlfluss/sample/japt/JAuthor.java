package xmlfluss.sample.japt;

import org.jspecify.annotations.NonNull;
import xmlfluss.XmlAttr;
import xmlfluss.XmlChild;
import xmlfluss.XmlRecord;

/**
 * Mirror of {@code xmlfluss.sample.Author}. Tests basic attr + child binding through APT.
 */
@XmlRecord(path = "//author")
public record JAuthor(
        @XmlAttr(name = "id") int id,
        @XmlAttr(name = "role") String role,
        @XmlChild(path = "name") @NonNull String name,
        @XmlChild(path = "bio") String bio
) {}
