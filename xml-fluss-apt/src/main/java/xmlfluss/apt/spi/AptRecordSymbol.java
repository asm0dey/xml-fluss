package xmlfluss.apt.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.PolyDispatch;
import xmlfluss.codegen.spi.AnnotationView;
import xmlfluss.codegen.spi.ComponentSymbol;
import xmlfluss.codegen.spi.RecordSymbol;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;

/**
 * APT-side {@link RecordSymbol}. {@link #polymorphic()} returns {@code null} by design:
 * {@code CoreClassifier} decodes {@code @XmlPolymorphic} directly from the {@link AnnotationView}
 * surface, so the SPI never needed to populate the convenience accessor. Removing it would touch
 * the cross-language interface contract; leaving it as a stable null is cheaper than churn.
 */
public final class AptRecordSymbol implements RecordSymbol {

    private static final String FQ_XML_NS = "xmlfluss.XmlNs";
    private static final String FQ_XML_NAMESPACES = "xmlfluss.XmlNamespaces";
    private static final String FQ_XML_RECORD = "xmlfluss.XmlRecord";

    private final TypeElement element;
    private final Elements elementUtils;
    private final Types typeUtils;
    private final AptAnnotationView annotations;

    /**
     * Wraps a record (or sealed parent) {@link TypeElement} as a {@link RecordSymbol}.
     *
     * @param element       type element to expose
     * @param elementUtils  utilities for reading annotation default values
     * @param typeUtils     utilities used when materialising components
     */
    public AptRecordSymbol(TypeElement element, Elements elementUtils, Types typeUtils) {
        this.element = element;
        this.elementUtils = elementUtils;
        this.typeUtils = typeUtils;
        this.annotations = new AptAnnotationView(element, elementUtils);
    }

    @Override
    public String packageName() {
        Element enclosing = element.getEnclosingElement();
        while (enclosing != null
                && enclosing.getKind() != javax.lang.model.element.ElementKind.PACKAGE) {
            enclosing = enclosing.getEnclosingElement();
        }
        if (enclosing instanceof PackageElement pe) {
            return pe.getQualifiedName().toString();
        }
        return "";
    }

    @Override
    public String simpleName() {
        return element.getSimpleName().toString();
    }

    @Override
    public String qualifiedName() {
        return element.getQualifiedName().toString();
    }

    @Override
    public List<ComponentSymbol> components() {
        List<ComponentSymbol> out = new ArrayList<>();
        for (RecordComponentElement c : element.getRecordComponents()) {
            out.add(new AptComponentSymbol(c, elementUtils, typeUtils));
        }
        return List.copyOf(out);
    }

    @Override
    public Map<String, String> declaredNamespaces() {
        Map<String, String> ns = new LinkedHashMap<>();
        AnnotationMirror single = findMirror(FQ_XML_NS);
        if (single != null) {
            String prefix = (String) attrValue(single, "prefix");
            String uri = (String) attrValue(single, "uri");
            if (prefix != null && uri != null) ns.put(prefix, uri);
        }
        AnnotationMirror multi = findMirror(FQ_XML_NAMESPACES);
        if (multi != null) {
            Object raw = attrValue(multi, "value");
            if (raw instanceof List<?> entries) {
                for (Object e : entries) {
                    AnnotationMirror am = e instanceof AnnotationValue av
                            ? (av.getValue() instanceof AnnotationMirror m ? m : null)
                            : (e instanceof AnnotationMirror m ? m : null);
                    if (am == null) continue;
                    String prefix = (String) attrValue(am, "prefix");
                    String uri = (String) attrValue(am, "uri");
                    if (prefix != null && uri != null) ns.put(prefix, uri);
                }
            }
        }
        return Collections.unmodifiableMap(ns);
    }

    @Override
    public @Nullable String declaredPath() {
        return annotations.stringValue(FQ_XML_RECORD, "path");
    }

    @Override
    public @Nullable PolyDispatch polymorphic() {
        // CoreClassifier decodes @XmlPolymorphic directly from AnnotationView; this accessor
        // stays as a stable null so the SPI contract still satisfies RecordSymbol.
        return null;
    }

    @Override
    public List<RecordSymbol> sealedSubtypes() {
        List<RecordSymbol> out = new ArrayList<>();
        for (TypeMirror sub : element.getPermittedSubclasses()) {
            if (sub instanceof DeclaredType dt && dt.asElement() instanceof TypeElement te) {
                out.add(new AptRecordSymbol(te, elementUtils, typeUtils));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public AnnotationView annotations() {
        return annotations;
    }

    @Override
    public TypeElement nativeHandle() {
        return element;
    }

    private @Nullable AnnotationMirror findMirror(String fqn) {
        for (AnnotationMirror m : element.getAnnotationMirrors()) {
            DeclaredType t = m.getAnnotationType();
            if (((TypeElement) t.asElement()).getQualifiedName().contentEquals(fqn)) {
                return m;
            }
        }
        return null;
    }

    private @Nullable Object attrValue(AnnotationMirror m, String attr) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                elementUtils.getElementValuesWithDefaults(m);
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e : values.entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(attr)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }
}
