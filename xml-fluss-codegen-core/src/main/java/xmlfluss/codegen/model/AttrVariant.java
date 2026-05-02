package xmlfluss.codegen.model;

import java.util.Objects;

/** Attribute-discriminator polymorphic variant: attribute value maps to subtype. */
public record AttrVariant(String value, String subtypeFq) {
    public AttrVariant {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(subtypeFq, "subtypeFq");
    }
}
