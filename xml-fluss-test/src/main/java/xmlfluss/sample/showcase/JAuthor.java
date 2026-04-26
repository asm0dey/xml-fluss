package xmlfluss.sample.showcase;

import org.jspecify.annotations.Nullable;
import xmlfluss.XmlAttr;

/**
 * {@code name} inherits @NullMarked → required. {@code country} carries an explicit
 * {@code @Nullable}, demonstrating an in-package override of the package default.
 */
public record JAuthor(
        @XmlAttr String name,
        @XmlAttr @Nullable String country
) {}
