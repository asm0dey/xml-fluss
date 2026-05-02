package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.PolyDispatch;

import java.util.List;
import java.util.Map;

/**
 * One record type in source. Reports its package, simple name, components, namespace
 * declarations, and (optional) polymorphic dispatch.
 */
public interface RecordSymbol {

    String packageName();

    String simpleName();

    /** {@code packageName + "." + simpleName} when package is non-empty, else just simpleName. */
    String qualifiedName();

    /** Components in canonical-constructor order. */
    List<ComponentSymbol> components();

    /**
     * Namespace prefixes declared on this record via {@code @XmlNs}. Maps prefix → URI.
     * Iteration order matches declaration order. Empty when no declarations.
     */
    Map<String, String> declaredNamespaces();

    /** Path declared via {@code @XmlRecord}, or {@code null} when unset. */
    @Nullable String declaredPath();

    /**
     * Dispatch shape when the record is annotated with {@code @XmlPolymorphic}.
     * {@code null} otherwise.
     */
    @Nullable PolyDispatch polymorphic();

    /**
     * Direct sealed subtypes when the record is sealed (Java sealed records, Kotlin
     * sealed classes). Used to enumerate polymorphic variants. Empty when the record
     * is not sealed.
     */
    List<RecordSymbol> sealedSubtypes();

    /** Annotations on the record itself. */
    AnnotationView annotations();

    /** Opaque handle for diagnostics. May be null for fakes. */
    @Nullable Object nativeHandle();
}
