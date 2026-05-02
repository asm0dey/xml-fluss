package xmlfluss.apt.spi;

import com.palantir.javapoet.TypeName;
import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;
import xmlfluss.codegen.spi.AnnotationView;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import java.util.Map;

/** APT-side {@link AnnotationView} backed by {@link Element#getAnnotationMirrors()}. */
public final class AptAnnotationView implements AnnotationView {

    private final Element element;
    private final Elements elementUtils;

    public AptAnnotationView(Element element, Elements elementUtils) {
        this.element = element;
        this.elementUtils = elementUtils;
    }

    @Override
    public boolean has(String fqn) {
        return findMirror(fqn) != null;
    }

    @Override
    public @Nullable String stringValue(String fqn, String attr) {
        Object v = readAttr(fqn, attr);
        return v instanceof String s ? s : null;
    }

    @Override
    public @Nullable TypeRef classValue(String fqn, String attr) {
        Object v = readAttr(fqn, attr);
        if (v instanceof TypeMirror tm) {
            return AptModelToCore.toTypeRef(TypeName.get(tm));
        }
        return null;
    }

    @Override
    public @Nullable Boolean booleanValue(String fqn, String attr) {
        Object v = readAttr(fqn, attr);
        return v instanceof Boolean b ? b : null;
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

    private @Nullable Object readAttr(String fqn, String attr) {
        AnnotationMirror m = findMirror(fqn);
        if (m == null) return null;
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
