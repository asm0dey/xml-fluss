package xmlfluss.apt;

import xmlfluss.apt.spi.AptSymbolProvider;
import xmlfluss.codegen.classify.CoreClassifier;
import xmlfluss.codegen.model.NestedRegistry;
import xmlfluss.codegen.model.RecordSpec;
import xmlfluss.codegen.plan.DispatchPlan;
import xmlfluss.codegen.plan.DispatchPlanBuilder;
import xmlfluss.codegen.spi.RecordSymbol;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * APT processor that generates a streaming parser for every record annotated with
 * {@code @xmlfluss.XmlRecord}. The generated parser exposes
 * {@code public static java.util.stream.Stream<T> parse(InputStream)} and
 * {@code public static Stream<T> parse(InputStream, boolean ignoreNamespace)}.
 *
 * <p>The MVP supports {@code @XmlRecord}, {@code @XmlAttr}, {@code @XmlChild}, and
 * {@code @XmlText}. The remaining annotations from the runtime are detected and rejected
 * with a clear ERROR diagnostic.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes("xmlfluss.XmlRecord")
public final class XmlDslProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) return false;
        annotations
                .stream()
                .flatMap(anno -> roundEnv.getElementsAnnotatedWith(anno).stream())
                .forEach(target -> {
                            if (!(target instanceof TypeElement typeElement)) {
                                processingEnv.getMessager().printMessage(
                                        Diagnostic.Kind.ERROR,
                                        "xml-fluss-apt: @XmlRecord must annotate a record type",
                                        target);
                                return;
                            }
                            if (typeElement.getKind() != javax.lang.model.element.ElementKind.RECORD
                                    && !(typeElement.getModifiers()
                                    .contains(javax.lang.model.element.Modifier.SEALED))) {
                                processingEnv.getMessager().printMessage(
                                        Diagnostic.Kind.ERROR,
                                        "@XmlRecord requires a record type, got " + typeElement.getKind(),
                                        typeElement);
                                return;
                            }
                            RecordSpec spec;
                            NestedRegistry registry;
                            DispatchPlan plan;
                            try {
                                // Walk the record's components via the shared codegen-core classifier and consume the
                                // neutral RecordSpec / NestedRegistry directly — the emitter is now language-neutral
                                // at the model layer and renders JavaPoet types on demand via TypeRefs.
                                AptSymbolProvider sp = new AptSymbolProvider(processingEnv);
                                RecordSymbol symbol = sp.lookupRecord(typeElement.getQualifiedName().toString());
                                if (symbol == null) {
                                    // Diagnostic already emitted (e.g. @XmlNs conflict in AptSymbolProvider).
                                    return;
                                }
                                CoreClassifier core = new CoreClassifier(sp);
                                spec = core.classify(symbol);
                                if (spec == null) {
                                    // Validation diagnostics already emitted via sp.diagnostics(); skip this record.
                                    return;
                                }
                                registry = core.registry();
                                plan = DispatchPlanBuilder.build(spec, registry);
                            } catch (RuntimeException unexpected) {
                                processingEnv.getMessager().printMessage(
                                        Diagnostic.Kind.ERROR,
                                        "xml-fluss-apt: internal error processing " + typeElement + ": " + unexpected,
                                        typeElement);
                                return;
                            }
                            try {
                                // Code-generate `<Cls>Parser.java` from the RecordSpec — emits the Stream<T> parse(InputStream) entry point
                                // plus per-field state machines for attr/child/text/map handling, and writes through the Filer.
                                new Emitter(processingEnv).emit(spec, plan, registry);
                            } catch (RuntimeException unexpected) {
                                processingEnv.getMessager().printMessage(
                                        Diagnostic.Kind.ERROR,
                                        "xml-fluss-apt: failed to emit parser for " + typeElement + ": " + unexpected,
                                        typeElement);
                            }
                        }

                );
        return true;
    }
}
