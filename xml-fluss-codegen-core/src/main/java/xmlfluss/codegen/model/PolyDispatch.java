package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Dispatch strategy for an {@code @XmlPolymorphic} sealed parent type.
 * Either tag-based (child element name picks subtype) or attribute-based
 * (a discriminator attribute on the wrapping element picks subtype).
 */
public sealed interface PolyDispatch permits PolyDispatch.Tag, PolyDispatch.Attr {

    record Tag(List<TagVariant> variants) implements PolyDispatch {
        public Tag {
            Objects.requireNonNull(variants, "variants");
            variants = List.copyOf(variants);
        }
    }

    record Attr(@Nullable String wrapNs, String wrapLocal,
                @Nullable String attrNs, String attrLocal,
                List<AttrVariant> variants) implements PolyDispatch {
        public Attr {
            Objects.requireNonNull(wrapLocal, "wrapLocal");
            Objects.requireNonNull(attrLocal, "attrLocal");
            Objects.requireNonNull(variants, "variants");
            variants = List.copyOf(variants);
        }
    }
}
