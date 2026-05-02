package xmlfluss.apt.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.spi.DiagnosticReporter;
import xmlfluss.codegen.spi.RecordSymbol;
import xmlfluss.codegen.spi.SymbolProvider;
import xmlfluss.codegen.spi.TypeSymbol;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AptSymbolProvider implements SymbolProvider {

    private static final String FQ_XML_NS = "xmlfluss.XmlNs";
    private static final String FQ_XML_NAMESPACES = "xmlfluss.XmlNamespaces";

    private final Elements elementUtils;
    private final Types typeUtils;
    private final DiagnosticReporter diagnostics;

    public AptSymbolProvider(ProcessingEnvironment env) {
        this.elementUtils = env.getElementUtils();
        this.typeUtils = env.getTypeUtils();
        this.diagnostics = new AptDiagnosticReporter(env.getMessager());
    }

    @Override
    public @Nullable RecordSymbol lookupRecord(String fqn) {
        TypeElement te = elementUtils.getTypeElement(fqn);
        if (te == null) return null;
        // Accept records directly, and also sealed interfaces/classes that carry @XmlPolymorphic.
        boolean isRecord = te.getKind() == ElementKind.RECORD;
        boolean isSealedParent = (te.getKind() == ElementKind.INTERFACE
                || te.getKind() == ElementKind.CLASS)
                && te.getModifiers().contains(Modifier.SEALED);
        if (!isRecord && !isSealedParent) return null;
        // Reject conflicting @XmlNs declarations on the same record. Mirrors original
        // Classifier.addNs ("@XmlNs prefix 'x' bound to two URIs: ..."). The merged-map view
        // returned by AptRecordSymbol.declaredNamespaces() silently keeps the last entry, so
        // we have to inspect the raw mirrors here while we still have access to the diagnostic
        // reporter.
        if (!validateOwnNamespaces(te)) return null;
        return new AptRecordSymbol(te, elementUtils, typeUtils);
    }

    @Override
    public @Nullable TypeSymbol lookupType(String fqn) {
        TypeElement te = elementUtils.getTypeElement(fqn);
        if (te == null) return null;
        return new AptTypeSymbol(te, typeUtils);
    }

    @Override
    public DiagnosticReporter diagnostics() {
        return diagnostics;
    }

    private boolean validateOwnNamespaces(TypeElement element) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (AnnotationMirror am : element.getAnnotationMirrors()) {
            String fq = ((TypeElement) am.getAnnotationType().asElement())
                    .getQualifiedName().toString();
            if (FQ_XML_NS.equals(fq)) {
                if (!checkNs(element, am, seen)) return false;
            } else if (FQ_XML_NAMESPACES.equals(fq)) {
                Object raw = readAttr(am, "value");
                if (raw instanceof List<?> entries) {
                    for (Object e : entries) {
                        AnnotationMirror inner = e instanceof AnnotationValue av
                                ? (av.getValue() instanceof AnnotationMirror m ? m : null)
                                : (e instanceof AnnotationMirror m ? m : null);
                        if (inner == null) continue;
                        if (!checkNs(element, inner, seen)) return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean checkNs(TypeElement owner, AnnotationMirror am, Map<String, String> sink) {
        Object pRaw = readAttr(am, "prefix");
        Object uRaw = readAttr(am, "uri");
        if (!(pRaw instanceof String prefix) || !(uRaw instanceof String uri)) return true;
        String prior = sink.get(prefix);
        if (prior != null && !prior.equals(uri)) {
            diagnostics.error(owner, "@XmlNs prefix '" + prefix + "' bound to two URIs: '"
                    + prior + "' and '" + uri + "'");
            return false;
        }
        sink.put(prefix, uri);
        return true;
    }

    private static @Nullable Object readAttr(AnnotationMirror am, String key) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e
                : am.getElementValues().entrySet()) {
            if (e.getKey().getSimpleName().contentEquals(key)) {
                return e.getValue().getValue();
            }
        }
        return null;
    }
}
