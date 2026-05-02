package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;

import java.util.List;

/**
 * Read-only view over the annotations on a symbol. The classifier consults this view to
 * read {@code @XmlAttr}, {@code @XmlChild}, {@code @XmlText}, etc. without depending on
 * any reflection or processor-specific annotation API.
 *
 * <p>All accessors take the annotation's fully-qualified name (FQN) as a string so the
 * classifier and adapters share the same key. Attribute name is the annotation method name.
 *
 * <p>Returns {@code null} when the annotation is absent or the requested attribute is not
 * present (or has the annotation's default value, depending on the host runtime).
 */
public interface AnnotationView {

    /** True iff the symbol carries an annotation with the given FQN. */
    boolean has(String fqn);

    /**
     * String-valued annotation attribute, e.g. {@code @XmlChild("foo")} → {@code "foo"}
     * for {@code stringValue("xmlfluss.XmlChild", "value")}.
     */
    @Nullable String stringValue(String fqn, String attr);

    /** Repeated string attribute (annotation arrays). */
    @Nullable List<String> stringArrayValue(String fqn, String attr);

    /**
     * {@code Class<?>}-valued attribute (e.g. {@code @XmlConverter(MyConv.class)}).
     * Returned as a {@link TypeRef} so the classifier remains language-independent.
     */
    @Nullable TypeRef classValue(String fqn, String attr);

    /**
     * {@code boolean}-valued annotation attribute. Returns {@code null} when the annotation
     * is absent, or the attribute is not present / not boolean.
     */
    @Nullable Boolean booleanValue(String fqn, String attr);
}
