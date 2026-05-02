package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;
import xmlfluss.path.Predicate;

import java.util.List;
import java.util.Objects;

/**
 * One segment of a multi-segment {@code @XmlChild} path. Either an element step
 * (possibly with positional or attribute brackets) or a trailing attribute leaf.
 */
public sealed interface PathSeg permits PathSeg.Element, PathSeg.AttrLeaf {

    record Element(@Nullable String ns, String name, List<Predicate> brackets) implements PathSeg {
        public Element {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(brackets, "brackets");
            brackets = List.copyOf(brackets);
        }

        public Element(@Nullable String ns, String name) {
            this(ns, name, List.of());
        }
    }

    record AttrLeaf(@Nullable String ns, String name) implements PathSeg {
        public AttrLeaf {
            Objects.requireNonNull(name, "name");
        }
    }
}
