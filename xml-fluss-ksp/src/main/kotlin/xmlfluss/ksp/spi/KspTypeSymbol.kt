package xmlfluss.ksp.spi

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import xmlfluss.codegen.model.TypeRef
import xmlfluss.codegen.spi.TypeSymbol

/**
 * KSP-side [TypeSymbol] backed by a live [KSClassDeclaration]. Mirrors the APT analog
 * `xmlfluss.apt.spi.AptTypeSymbol`: surfaces the public no-arg constructor check and the
 * supertype walk that resolves a parameterized supertype's type argument (e.g. the `T`
 * of `Converter<T>`). Used by `@XmlConverter` validation in `CoreClassifier`.
 */
class KspTypeSymbol(
    private val decl: KSClassDeclaration,
) : TypeSymbol {

    override fun qualifiedName(): String =
        decl.qualifiedName?.asString() ?: error("KSClassDeclaration without qualifiedName")

    /**
     * Mirrors `AptTypeSymbol.hasPublicNoArgConstructor` (lines 46-55) but adapted to Kotlin
     * semantics:
     *   * interfaces and abstract classes are rejected up-front (APT relies on `Modifier.PUBLIC`
     *     being present on the ctor; abstract types' synthesized ctors don't help us anyway).
     *   * Kotlin's synthetic primary constructor for a class with no explicit ctor carries the
     *     declaring class's visibility, which `Resolver.getDeclaredConstructors`/`getConstructors`
     *     surfaces with an empty `modifiers` set. Treat empty-modifiers as public (Kotlin's
     *     default visibility), matching what the user wrote when they declared `class Foo`.
     *   * Explicit ctors must carry `Modifier.PUBLIC` — `private`, `internal`, or `protected`
     *     ctors are not callable by the runtime via `KClass.createInstance`, so they fail the
     *     same parity bar AptTypeSymbol enforces.
     */
    override fun hasPublicNoArgConstructor(): Boolean {
        if (decl.classKind == ClassKind.INTERFACE) return false
        if (Modifier.ABSTRACT in decl.modifiers) return false
        return decl.getConstructors().any { ctor ->
            if (ctor.parameters.isNotEmpty()) return@any false
            val mods = ctor.modifiers
            // No-modifier ctor on a Kotlin class is public-by-default; explicit visibility
            // modifiers must be exactly Modifier.PUBLIC for the ctor to be callable.
            mods.isEmpty() || Modifier.PUBLIC in mods
        }
    }

    /**
     * BFS walk over the supertype graph mirroring `AptTypeSymbol.typeArgumentOf` (lines 58-79).
     * Resolves each supertype's parameterized form so that a `class Foo : Converter<String>`
     * yields a `TypeRef` for `java.lang.String` at index 0. Returns `null` when the supertype is
     * not (transitively) implemented or when `index` is out of range — same contract APT honours.
     */
    override fun typeArgumentOf(supertypeFqn: String, index: Int): TypeRef? {
        val visited = HashSet<String>()
        val queue: ArrayDeque<KSClassDeclaration> = ArrayDeque()
        queue.add(decl)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val curFq = cur.qualifiedName?.asString() ?: continue
            if (!visited.add(curFq)) continue
            for (sup in cur.superTypes) {
                val resolved = sup.resolve()
                val supDecl = resolved.declaration as? KSClassDeclaration ?: continue
                val fq = supDecl.qualifiedName?.asString() ?: continue
                if (fq == supertypeFqn) {
                    val args = resolved.arguments
                    if (index < 0 || index >= args.size) return null
                    val argType = args[index].type?.resolve() ?: return null
                    return KspModelToCore.toTypeRef(argType)
                }
                queue.add(supDecl)
            }
        }
        return null
    }

    override fun nativeHandle(): Any = decl
}
