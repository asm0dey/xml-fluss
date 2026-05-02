package xmlfluss.apt.spi;

import com.palantir.javapoet.TypeName;
import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;
import xmlfluss.codegen.spi.AnnotationView;
import xmlfluss.codegen.spi.ComponentSymbol;
import xmlfluss.codegen.spi.RecordSymbol;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * APT-side {@link ComponentSymbol} for a Java record component.
 *
 * <p>Nullability follows the JSpecify convention: primitives and {@code List<>} fields are
 * always non-null; explicit {@code @NonNull} / {@code @Nullable} override; otherwise the
 * {@code @NullMarked} / {@code @NullUnmarked} scope walk (type → enclosing types → package)
 * determines the default (non-null inside {@code @NullMarked}, null outside).
 */
public final class AptComponentSymbol implements ComponentSymbol {

    private static final String FQ_NON_NULL     = "org.jspecify.annotations.NonNull";
    private static final String FQ_NULLABLE     = "org.jspecify.annotations.Nullable";
    private static final String FQ_NULL_MARKED  = "org.jspecify.annotations.NullMarked";
    private static final String FQ_NULL_UNMARKED = "org.jspecify.annotations.NullUnmarked";
    private static final String FQ_LIST = "java.util.List";

    private final RecordComponentElement element;
    private final Elements elementUtils;
    private final Types typeUtils;
    private final AptAnnotationView annotations;

    public AptComponentSymbol(RecordComponentElement element, Elements elementUtils, Types typeUtils) {
        this.element = element;
        this.elementUtils = elementUtils;
        this.typeUtils = typeUtils;
        this.annotations = new AptAnnotationView(element, elementUtils);
    }

    @Override
    public String name() {
        return element.getSimpleName().toString();
    }

    @Override
    public TypeRef type() {
        return AptModelToCore.toTypeRef(TypeName.get(element.asType()));
    }

    @Override
    public boolean nullable() {
        TypeMirror declaredType = element.asType();
        boolean primitive = declaredType.getKind().isPrimitive();
        if (primitive || isList()) return false;

        VariableElement ctorParam = canonicalCtorParam();

        // Explicit @NonNull wins: not nullable. Walk every surface APT can land it on —
        // record-component element, its type-use, the accessor + return type, and the
        // canonical-constructor parameter + its type-use. Mirrors KspComponentSymbol's
        // hasExplicitAnnotation walk: a developer-written explicit canonical ctor
        // `public R(@NonNull String x)` lands the annotation on the ctor parameter, and
        // JLS 8.10.3 propagation to the component is not always honoured by APT impls —
        // checking the parameter directly closes that gap.
        if (hasAnn(element, FQ_NON_NULL) || hasTypeUseAnn(declaredType, FQ_NON_NULL)) return false;
        if (ctorParam != null
                && (hasAnn(ctorParam, FQ_NON_NULL) || hasTypeUseAnn(ctorParam.asType(), FQ_NON_NULL))) {
            return false;
        }

        // Explicit @Nullable wins: nullable
        if (hasAnn(element, FQ_NULLABLE) || hasTypeUseAnn(declaredType, FQ_NULLABLE)) return true;
        if (ctorParam != null
                && (hasAnn(ctorParam, FQ_NULLABLE) || hasTypeUseAnn(ctorParam.asType(), FQ_NULLABLE))) {
            return true;
        }

        // Check accessor method (rc.getAccessor())
        ExecutableElement acc = element.getAccessor();
        if (acc != null) {
            if (hasAnn(acc, FQ_NON_NULL) || hasTypeUseAnn(acc.getReturnType(), FQ_NON_NULL)) return false;
            if (hasAnn(acc, FQ_NULLABLE) || hasTypeUseAnn(acc.getReturnType(), FQ_NULLABLE)) return true;
        }

        // No explicit annotation — consult @NullMarked / @NullUnmarked scope.
        //   @NullMarked scope  → non-null by default (return false)
        //   @NullUnmarked scope → nullable by default (return true)
        //   no scope annotation → nullable (return true) — matches original Classifier.java
        //   semantics so that unannotated reference-type components in plain packages keep
        //   the lenient "missing element/attr → null" behaviour the integration suite relies on.
        //   NOTE: commit 6043dc1 ("port @XmlPolymorphic; restore @NullMarked nullability walk")
        //   originally chose the opposite default; reverted here in Task 9 wiring (5313784) for
        //   integration-suite parity. The walk machinery is intact; only the no-scope branch flipped.
        TypeElement owner = element.getEnclosingElement() instanceof TypeElement te ? te : null;
        return !isNullMarkedScope(owner);
    }

    /**
     * Returns the canonical-constructor parameter that corresponds to this record component, or
     * {@code null} if no canonical constructor is visible (defensive — every record has one). The
     * canonical ctor is the constructor whose parameter list (count + names, in order) matches the
     * record components 1:1.
     */
    private @Nullable VariableElement canonicalCtorParam() {
        Element enc = element.getEnclosingElement();
        if (!(enc instanceof TypeElement owner)) return null;
        var components = owner.getRecordComponents();
        if (components.isEmpty()) return null;
        String compName = element.getSimpleName().toString();
        for (Element e : owner.getEnclosedElements()) {
            if (e.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ctor = (ExecutableElement) e;
            var params = ctor.getParameters();
            if (params.size() != components.size()) continue;
            boolean canonical = true;
            for (int i = 0; i < components.size(); i++) {
                if (!params.get(i).getSimpleName().contentEquals(components.get(i).getSimpleName())) {
                    canonical = false;
                    break;
                }
            }
            if (!canonical) continue;
            for (VariableElement p : params) {
                if (p.getSimpleName().contentEquals(compName)) return p;
            }
        }
        return null;
    }

    // ---- nullability helpers (mirrors Classifier.java computeNullable / isNullMarkedScope) ----

    private boolean hasAnn(Element e, String fq) {
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (annotationFq(am).equals(fq)) return true;
        }
        return false;
    }

    private boolean hasTypeUseAnn(TypeMirror tm, String fq) {
        for (AnnotationMirror am : tm.getAnnotationMirrors()) {
            if (annotationFq(am).equals(fq)) return true;
        }
        return false;
    }

    private static String annotationFq(AnnotationMirror am) {
        return ((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().toString();
    }

    /**
     * Walks element → enclosing types → package for {@code @NullMarked} / {@code @NullUnmarked}.
     * Returns a tri-state: {@code Boolean.TRUE} = NullMarked, {@code Boolean.FALSE} = NullUnmarked,
     * {@code null} = no scope annotation found.
     */
    private @Nullable Boolean nullScope(@Nullable TypeElement type) {
        if (type == null) return null;
        Deque<Element> chain = new ArrayDeque<>();
        Element cur = type;
        while (cur != null) {
            chain.addFirst(cur);
            cur = cur.getEnclosingElement();
        }
        Boolean state = null;
        for (Element e : chain) {
            if (hasAnn(e, FQ_NULL_MARKED)) state = true;
            else if (hasAnn(e, FQ_NULL_UNMARKED)) state = false;
        }
        if (state != null) return state;
        PackageElement pkg = elementUtils.getPackageOf(type);
        if (hasAnn(pkg, FQ_NULL_MARKED)) return true;
        if (hasAnn(pkg, FQ_NULL_UNMARKED)) return false;
        return null;
    }

    /**
     * Returns {@code true} if inside a {@code @NullMarked} scope.
     * Mirrors {@code Classifier.isNullMarkedScope}.
     */
    private boolean isNullMarkedScope(@Nullable TypeElement type) {
        Boolean s = nullScope(type);
        return s != null && s;
    }

    @Override
    public boolean isList() {
        TypeMirror t = element.asType();
        if (t.getKind() != TypeKind.DECLARED) return false;
        DeclaredType dt = (DeclaredType) t;
        Element raw = dt.asElement();
        if (!(raw instanceof TypeElement te)) return false;
        return te.getQualifiedName().contentEquals(FQ_LIST);
    }

    @Override
    public TypeRef elementType() {
        if (!isList()) return type();
        DeclaredType dt = (DeclaredType) element.asType();
        if (dt.getTypeArguments().isEmpty()) {
            return TypeRef.of("java.lang", "Object");
        }
        TypeMirror arg = dt.getTypeArguments().get(0);
        return AptModelToCore.toTypeRef(TypeName.get(arg));
    }

    @Override
    public @Nullable RecordSymbol asNestedRecord() {
        TypeMirror declared = element.asType();
        TypeMirror et;
        if (isList()) {
            DeclaredType dt = (DeclaredType) declared;
            if (dt.getTypeArguments().isEmpty()) return null;
            et = dt.getTypeArguments().get(0);
        } else {
            et = declared;
        }
        if (et.getKind() != TypeKind.DECLARED) return null;
        Element raw = ((DeclaredType) et).asElement();
        if (!(raw instanceof TypeElement te)) return null;
        if (te.getKind() != javax.lang.model.element.ElementKind.RECORD) return null;
        return new AptRecordSymbol(te, elementUtils, typeUtils);
    }

    @Override
    public AnnotationView annotations() {
        return annotations;
    }

    @Override
    public RecordComponentElement nativeHandle() {
        return element;
    }
}
