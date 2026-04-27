package xmlfluss.apt;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
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
        if (annotations.isEmpty()) {
            return false;
        }
        for (TypeElement anno : annotations) {
            for (Element target : roundEnv.getElementsAnnotatedWith(anno)) {
                if (!(target instanceof TypeElement typeElement)) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "xml-fluss-apt: @XmlRecord must annotate a record type",
                            target);
                    continue;
                }
                Model.NestedRegistry registry = new Model.NestedRegistry();
                Classifier classifier = new Classifier(processingEnv, registry);
                Model.RecordSpec spec;
                try {
                    // Walk the record's components, resolve @XmlAttr/@XmlChild/@XmlText/@XmlMap into a RecordSpec,
                    // collect @XmlNs prefixes, and register nested data-record types into the shared NestedRegistry.
                    spec = classifier.classifyTopLevel(typeElement);
                } catch (Classifier.ClassifierException ex) {
                    // Validation diagnostics already emitted; skip this record.
                    continue;
                } catch (RuntimeException unexpected) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "xml-fluss-apt: internal error processing " + typeElement + ": " + unexpected,
                            typeElement);
                    continue;
                }
                try {
                    // Code-generate `<Cls>Parser.java` from the RecordSpec — emits the Stream<T> parse(InputStream) entry point
                    // plus per-field state machines for attr/child/text/map handling, and writes through the Filer.
                    new Emitter(processingEnv).emit(spec, registry);
                } catch (RuntimeException unexpected) {
                    processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "xml-fluss-apt: failed to emit parser for " + typeElement + ": " + unexpected,
                            typeElement);
                }
            }
        }
        return true;
    }
}
