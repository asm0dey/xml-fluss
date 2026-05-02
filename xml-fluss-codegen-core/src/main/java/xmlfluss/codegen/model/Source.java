package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * What XML thing a record component reads from. {@code ns} fields use {@code null}
 * to denote "no namespace" per XML spec.
 */
public sealed interface Source
        permits Source.Attr, Source.Child, Source.Text, Source.MapEntry, Source.PolyChild {

    /** XML attribute. */
    record Attr(@Nullable String ns, String name) implements Source {
        public Attr {
            Objects.requireNonNull(name, "name");
        }
    }

    /** Element child path. */
    record Child(List<PathSeg> segments, boolean descendant) implements Source {
        public Child {
            Objects.requireNonNull(segments, "segments");
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("child path must have at least one segment");
            }
            if (!(segments.get(0) instanceof PathSeg.Element)) {
                throw new IllegalArgumentException("child path must start with an Element segment");
            }
            segments = List.copyOf(segments);
        }

        public PathSeg.Element head() {
            return (PathSeg.Element) segments.get(0);
        }
    }

    /** XML text content of the enclosing element. */
    record Text(boolean preserveWhitespace) implements Source {}

    /** {@code @XmlMap} repeating entry element. */
    record MapEntry(@Nullable String entryNs, String entryLocal) implements Source {
        public MapEntry {
            Objects.requireNonNull(entryLocal, "entryLocal");
        }
    }

    /** {@code @XmlPolymorphic} sealed-parent dispatch. */
    record PolyChild(PolyDispatch dispatch) implements Source {
        public PolyChild {
            Objects.requireNonNull(dispatch, "dispatch");
        }
    }
}
