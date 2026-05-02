package xmlfluss.apt.spi;

import com.palantir.javapoet.TypeName;
import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;
import xmlfluss.codegen.spi.TypeSymbol;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.*;

/**
 * APT-side {@link TypeSymbol} backed by a live {@link TypeElement}. Used by
 * {@code @XmlConverter} validation: surfaces the public no-arg constructor check and the
 * supertype-walk that resolves a parameterized supertype's type argument (e.g.,
 * {@code Converter<T>}'s {@code T}).
 */
public final class AptTypeSymbol implements TypeSymbol {

    private final TypeElement element;
    private final Types types;

    /**
     * Wraps a {@link TypeElement} as a {@link TypeSymbol}.
     *
     * @param element type element to expose
     * @param types   utilities used for the supertype walk
     */
    public AptTypeSymbol(TypeElement element, Types types) {
        this.element = element;
        this.types = types;
    }

    @Override
    public String qualifiedName() {
        return element.getQualifiedName().toString();
    }

    @Override
    public boolean hasPublicNoArgConstructor() {
        for (Element enc : element.getEnclosedElements()) {
            if (enc.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ctor = (ExecutableElement) enc;
            if (!ctor.getParameters().isEmpty()) continue;
            if (!ctor.getModifiers().contains(Modifier.PUBLIC)) continue;
            return true;
        }
        return false;
    }

    @Override
    public @Nullable TypeRef typeArgumentOf(String supertypeFqn, int index) {
        Deque<TypeMirror> queue = new ArrayDeque<>();
        queue.add(element.asType());
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeMirror cur = queue.removeFirst();
            if (cur.getKind() != TypeKind.DECLARED) continue;
            DeclaredType dt = (DeclaredType) cur;
            TypeElement te = (TypeElement) dt.asElement();
            String fq = te.getQualifiedName().toString();
            if (!seen.add(fq)) continue;
            if (fq.equals(supertypeFqn)) {
                List<? extends TypeMirror> args = dt.getTypeArguments();
                if (index < 0 || index >= args.size()) return null;
                return AptModelToCore.toTypeRef(TypeName.get(args.get(index)));
            }
            for (TypeMirror sup : types.directSupertypes(cur)) {
                queue.addLast(sup);
            }
        }
        return null;
    }

    /**
     * The {@link TypeSymbol#nativeHandle()} contract is {@code @Nullable} because synthetic / fake
     * implementations may not have a native handle to surface. This concrete APT impl is always
     * backed by a non-null {@link TypeElement}, so the inspector flags the {@code @Nullable}
     * return type as redundant — we suppress narrowly here rather than weaken the interface.
     */
    @SuppressWarnings("DataFlowIssue")
    @Override
    public @Nullable Object nativeHandle() {
        return element;
    }
}
