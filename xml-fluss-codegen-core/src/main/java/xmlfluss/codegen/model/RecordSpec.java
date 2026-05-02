package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Full description of a record type to be emitted.
 *
 * @param packageName       Java package
 * @param simpleName        simple type name
 * @param recordPath        path declared via {@code @XmlRecord} value, or empty string
 * @param nsMap             effective prefix→URI map (parent merged with own {@code @XmlNs});
 *                          empty when there are no namespace declarations
 * @param fields            ordered as declared in the record (canonical-constructor order)
 * @param originatingHandle opaque handle to the source-language symbol (for emitter
 *                          {@code originatingElement} hints and diagnostics).
 */
public record RecordSpec(
        String packageName,
        String simpleName,
        String recordPath,
        Map<String, String> nsMap,
        List<FieldSpec> fields,
        @Nullable Object originatingHandle
) {
    public RecordSpec {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(simpleName, "simpleName");
        Objects.requireNonNull(recordPath, "recordPath");
        Objects.requireNonNull(nsMap, "nsMap");
        Objects.requireNonNull(fields, "fields");
        // Use unmodifiableMap over a defensive LinkedHashMap copy to preserve insertion
        // order. Map.copyOf does NOT guarantee iteration order — its javadoc explicitly
        // states "iteration order is unspecified", which would lose declaration-order
        // determinism that downstream codegen relies on.
        nsMap = Collections.unmodifiableMap(new LinkedHashMap<>(nsMap));
        fields = List.copyOf(fields);
    }
}
