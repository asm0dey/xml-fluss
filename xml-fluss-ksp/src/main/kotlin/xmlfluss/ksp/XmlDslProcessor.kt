package xmlfluss.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import java.time.LocalDate
import javax.annotation.processing.Generated
import xmlfluss.path.PathParser
import xmlfluss.path.Predicate as PathPredicate
import xmlfluss.path.QName as PathQName
import xmlfluss.path.Step as PathStep

private const val XMLFLUSS_RUNTIME = "xmlfluss.runtime"
private const val SKIP_CHILD = "c.skipChild()\n"
private const val ELSE_SKIP_CHILD = "else -> $SKIP_CHILD"
private const val KOTLIN_BOOLEAN = "kotlin.Boolean"
private const val KOTLIN_DOUBLE = "kotlin.Double"
private const val KOTLIN_LONG = "kotlin.Long"
private const val KOTLIN_INT = "kotlin.Int"
private const val KOTLIN_STRING = "kotlin.String"
private const val KOTLIN_COLLECTIONS_MAP = "kotlin.collections.Map"
private const val KOTLIN_COLLECTIONS_LIST = "kotlin.collections.List"

/**
 * KSP processor that turns `@XmlRecord` data classes into streaming parsers.
 *
 * For each annotated class the processor:
 *
 * 1. Resolves `@XmlNs` declarations and compiles the record path with
 *    [xmlfluss.runtime.Paths.compile].
 * 2. Walks the primary constructor parameters, classifying each as `@XmlAttr`, `@XmlChild`,
 *    `@XmlText`, `@XmlMap`, or polymorphic (sealed parent annotated with `@XmlPolymorphic` +
 *    `@XmlSubtype` data-class variants), and resolves the field's coercion (scalar, temporal,
 *    decimal, custom converter, nested data class, map aggregate, or sealed dispatch).
 * 3. Emits a `${ClassName}Parser` Kotlin object via KotlinPoet, exposing
 *    `parse(InputStream): Flow<T>` plus private helpers for nested types.
 *
 * Generated parsers drive [xmlfluss.runtime.XmlReadCursor]. No reflection is used at runtime.
 */
class XmlDslProcessor(env: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGen: CodeGenerator = env.codeGenerator
    private val logger: KSPLogger = env.logger

    /**
     * Sealed marker for diagnostics that originate from user input (annotation
     * misuse, malformed paths, type-shape rules). These render as concise KSP
     * errors. Anything else escaping `generate` is treated as an internal bug
     * and reported with a full stacktrace.
     */
    private sealed class ProcessorValidationException(message: String) : RuntimeException(message)
    private class ValidationError(message: String) : ProcessorValidationException(message)

    private fun vError(msg: String): Nothing = throw ValidationError(msg)
    private inline fun vRequire(cond: Boolean, msg: () -> String) {
        if (!cond) throw ValidationError(msg())
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(XML_RECORD_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        for (cls in symbols) {
            try {
                // Build the field model and emit `<Cls>Parser.kt` for this record.
                generate(cls)
            } catch (e: ProcessorValidationException) {
                logger.error("xml-fluss-ksp: ${e.message}", cls)
            } catch (e: Throwable) {
                logger.error("xml-fluss-ksp: ${e.stackTraceToString()}", cls)
            }
        }
        return emptyList()
    }

    private fun generate(cls: KSClassDeclaration) {
        if (Modifier.DATA !in cls.modifiers) {
            logger.error("@XmlRecord requires data class", cls)
            return
        }
        // cls came from resolver.getSymbolsWithAnnotation(XML_RECORD_FQ); annotation is guaranteed
        // present. XmlRecord.path is a non-null String. data classes always carry a primary ctor.
        // Pull the @XmlRecord annotation mirror and read its `path = "..."` argument.
        val recordAnn = annotationOf(cls, XML_RECORD_FQ)!!
        val recordPath = stringArg(recordAnn, "path")!!
        // Collect every @XmlNs(prefix=, uri=) declared on the class into a prefix→uri map.
        val nsMap = collectNs(cls)
        val ctor = cls.primaryConstructor!!

        val registry = NestedTypeRegistry()
        // Classify each ctor parameter into a FieldSpec (Attr/Child/Text/Map source, target type, converter, etc.).
        val fields = ctor.parameters.map { classifyParam(cls, it, nsMap, registry) }
        if (fields.count { it.source is Source.Text } > 1) {
            vError("${cls.qualifiedName?.asString()}: multiple @XmlText fields not allowed")
        }
        // Cross-field path checks: ambiguous overlaps, descendant-leaf vs subpath conflicts, predicate sanity.
        validateChildPaths(cls, fields)

        // Generate `<Cls>Parser.kt` from the FieldSpec list and write it via the KSP code generator.
        emitFile(cls, recordPath, nsMap, fields, registry)
    }

    private fun collectNs(cls: KSClassDeclaration): Map<String, String> =
        cls.annotations
            .filter { fq(it) == XML_NS_FQ }
            .associate {
                // XmlNs.prefix / .uri are declared as non-null String on the annotation.
                val prefix = stringArg(it, "prefix")!!
                val uri = stringArg(it, "uri")!!
                prefix to uri
            }

    private fun classifyParam(
        cls: KSClassDeclaration,
        p: KSValueParameter,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
    ): FieldSpec {
        // primary-constructor parameters of a data class are always named.
        val name = p.name!!.asString()
        val typeRef = p.type.resolve()
        val typeFq = typeRef.declaration.qualifiedName?.asString() ?: typeRef.toString()
        val nullable = typeRef.isMarkedNullable

        val isList = typeFq == KOTLIN_COLLECTIONS_LIST
        val isMap = typeFq == KOTLIN_COLLECTIONS_MAP
        if (isList && nullable) vError("List field '$name' must not be nullable; use empty list")

        val elemKsTypeEarly: KSType? = when {
            isMap -> null
            isList -> typeRef.arguments.firstOrNull()?.type?.resolve()
            else -> typeRef
        }
        val sealedPolyDecl: KSClassDeclaration? = elemKsTypeEarly
            ?.let { it.declaration as? KSClassDeclaration }
            ?.takeIf { Modifier.SEALED in it.modifiers && annotationOf(it, XML_POLYMORPHIC_FQ) != null }

        var source: Source? = null
        var formatPattern: String? = null
        var converterFq: String? = null
        var converterCls: ClassName? = null
        var mapAnnot: KSAnnotation? = null

        for (a in p.annotations) {
            when (fq(a)) {
                XML_ATTR_FQ -> {
                    vRequire(source == null) { "multiple xml bindings on '$name'" }
                    val raw = stringArg(a, "name").orEmpty()
                    val n = raw.ifEmpty { name }
                    val (ans, alocal) = resolveQName(
                        n,
                        nsMap,
                        defaultNs = null,
                        path = n,
                        field = name,
                        ctx = "@XmlAttr"
                    )
                    source = Source.Attr(ans, alocal)
                }

                XML_CHILD_FQ -> {
                    vRequire(source == null) { "multiple xml bindings on '$name'" }
                    val raw = stringArg(a, "path").orEmpty()
                    if (sealedPolyDecl != null) {
                        source = buildPolyChild(sealedPolyDecl, raw, name, nsMap, registry)
                    } else {
                        val pathStr = raw.ifEmpty { name }
                        source = parseChildPath(pathStr, name, nsMap)
                    }
                }

                XML_TEXT_FQ -> {
                    vRequire(source == null) { "multiple xml bindings on '$name'" }
                    if (isList) vError("@XmlText on List unsupported for '$name'")
                    source = Source.Text(
                        a.arguments.firstOrNull { it.name?.asString() == "preserveWhitespace" }?.value as? Boolean
                            ?: false)
                }

                XML_MAP_FQ -> {
                    vRequire(source == null && mapAnnot == null) { "multiple xml bindings on '$name'" }
                    vRequire(isMap) { "@XmlMap requires Map<K, V> type for '$name'" }
                    mapAnnot = a
                }

                XML_FORMAT_FQ -> {
                    // XmlFormat.pattern is a non-null String on the annotation.
                    formatPattern = stringArg(a, "pattern")!!
                }

                XML_CONVERTER_FQ -> {
                    val ksType = a.arguments.firstOrNull { it.name?.asString() == "cls" }?.value as? KSType
                        ?: vError("@XmlConverter missing cls on '$name'")
                    val decl = ksType.declaration as? KSClassDeclaration
                        ?: vError("@XmlConverter cls must be a class on '$name'")
                    converterFq = decl.qualifiedName?.asString()
                        ?: vError("@XmlConverter cls qualified name missing on '$name'")
                    converterCls = decl.toClassName()
                }
            }
        }

        vRequire(formatPattern == null || converterCls == null) {
            "Field '$name' has both @XmlFormat and @XmlConverter; pick one"
        }

        if (mapAnnot != null) {
            vRequire(formatPattern == null && converterCls == null) {
                "@XmlFormat / @XmlConverter not supported on @XmlMap field '$name'"
            }
            return classifyMapParam(name, typeRef, mapAnnot, nsMap, registry)
        }

        vRequire(!isMap) { "Field '$name' is Map<K, V> but lacks @XmlMap" }

        // elemKsTypeEarly is non-null when isMap is false: scalar/nested types resolve to typeRef,
        // and List<E> always carries its element type under KSP (raw List doesn't compile).
        val elemKsType: KSType = elemKsTypeEarly!!
        val elemFq = elemKsType.declaration.qualifiedName?.asString() ?: elemKsType.toString()
        val elemNullable = if (isList) elemKsType.isMarkedNullable else nullable

        if (source == null) vError("Field '$name' in ${cls.qualifiedName?.asString()} has no @XmlAttr/@XmlChild/@XmlText/@XmlMap")

        val coerce: Coerce = when {
            converterCls != null && converterFq != null -> Coerce.Custom(converterCls, converterFq)
            source is Source.PolyChild -> {
                vRequire(formatPattern == null && converterCls == null) {
                    "@XmlFormat / @XmlConverter not supported on polymorphic field '$name'"
                }
                Coerce.Nested(elemFq)
            }
            elemFq in SCALAR_TEMPORAL_FQS -> coerceForType(elemFq, formatPattern, name)
            isNestedDataClass(elemKsType) -> {
                if (source !is Source.Child) {
                    vError("Nested data-class field '$name' must use @XmlChild")
                }
                val elemDecl = elemKsType.declaration as KSClassDeclaration
                ensureNested(elemDecl, nsMap, registry, terminating = isList || elemNullable)
                Coerce.Nested(elemFq)
            }

            else -> vError("Unsupported type '$elemFq' for field '$name'. Use a scalar, supported temporal, BigDecimal, @XmlConverter, or a nested data class.")
        }

        val typeName = typeNameFor(elemKsType, isList, elemNullable)
        val elemTypeName = typeNameFor(elemKsType, isList = false, elemNullable)
        return FieldSpec(
            name = name,
            typeName = typeName,
            nullable = nullable,
            isList = isList,
            elemNullable = elemNullable,
            elemTypeName = elemTypeName,
            elemTypeFq = elemFq,
            source = source,
            coerce = coerce,
        )
    }

    private fun classifyMapParam(
        name: String,
        typeRef: KSType,
        mapAnnot: KSAnnotation,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
    ): FieldSpec {
        // XmlMap.entry / .key / .value are non-null Strings on the annotation.
        val entry = stringArg(mapAnnot, "entry")!!
        val keyPath = stringArg(mapAnnot, "key")!!
        val valPath = stringArg(mapAnnot, "value")!!
        vRequire(entry.isNotBlank() && '/' !in entry && !entry.startsWith("@")) {
            "@XmlMap entry '$entry' for '$name' must be a single element name (optional 'prefix:local')"
        }
        val (entryNs, entryLocal) = resolveQName(entry, nsMap, defaultNs = nsMap[""], path = entry, field = name)

        // typeRef is Map<K, V> per isMap check upstream; both type args are present.
        val keyKsType = typeRef.arguments[0].type!!.resolve()
        val valKsType = typeRef.arguments[1].type!!.resolve()

        val keyField = buildSyntheticMapKvField("mk", keyKsType, keyPath, nsMap, registry, owner = name, kind = "key")
        val valField = buildSyntheticMapKvField("mv", valKsType, valPath, nsMap, registry, owner = name, kind = "value")

        val nullable = typeRef.isMarkedNullable
        val mapTypeName = MAP.parameterizedBy(keyField.typeName, valField.typeName).copy(nullable = nullable)

        return FieldSpec(
            name = name,
            typeName = mapTypeName,
            nullable = nullable,
            isList = false,
            elemNullable = false,
            elemTypeName = valField.typeName,
            elemTypeFq = KOTLIN_COLLECTIONS_MAP,
            source = Source.MapEntry(entryNs, entryLocal),
            coerce = Coerce.MapAggregate,
            mapKeyField = keyField,
            mapValueField = valField,
        )
    }

    private fun buildSyntheticMapKvField(
        syntheticName: String,
        type: KSType,
        pathStr: String,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
        owner: String,
        kind: String,
    ): FieldSpec {
        val typeFq = type.declaration.qualifiedName?.asString() ?: type.toString()
        vRequire(typeFq != KOTLIN_COLLECTIONS_MAP) { "@XmlMap '$kind' of '$owner': nested Map<,> not supported" }
        val isList = typeFq == KOTLIN_COLLECTIONS_LIST
        val nullable = if (isList) false else type.isMarkedNullable
        // List<E> always exposes a single type argument under KSP.
        val elemKsType: KSType = if (isList) type.arguments[0].type!!.resolve() else type
        vRequire(!(isList && elemKsType.isMarkedNullable)) {
            "@XmlMap '$kind' of '$owner': nullable element inside List<…> not supported"
        }
        val elemFq = elemKsType.declaration.qualifiedName?.asString() ?: elemKsType.toString()
        vRequire(elemFq != KOTLIN_COLLECTIONS_LIST) { "@XmlMap '$kind' of '$owner': List<List<?>> not supported" }
        vRequire(elemFq != KOTLIN_COLLECTIONS_MAP) { "@XmlMap '$kind' of '$owner': List<Map<?, ?>> / Map element not supported" }

        val source: Source = if (pathStr.startsWith("@")) {
            val rest = pathStr.substring(1)
            vRequire(rest.isNotEmpty()) { "@XmlMap '$kind' of '$owner': empty attribute name" }
            vRequire('/' !in rest) { "@XmlMap '$kind' of '$owner': '@' path must be a single attribute name" }
            val (ans, alocal) = resolveQName(
                rest,
                nsMap,
                defaultNs = null,
                path = pathStr,
                field = syntheticName,
                ctx = "@XmlMap '$kind'"
            )
            Source.Attr(ans, alocal)
        } else {
            parseChildPath(pathStr, syntheticName, nsMap)
        }

        val coerce: Coerce = when {
            elemFq in SCALAR_TEMPORAL_FQS -> coerceForType(elemFq, null, syntheticName)
            isNestedDataClass(elemKsType) -> {
                if (source !is Source.Child) {
                    vError("@XmlMap '$kind' of '$owner': nested data-class type requires an element path, not '@attr'")
                }
                val elemDecl = elemKsType.declaration as KSClassDeclaration
                ensureNested(elemDecl, nsMap, registry, terminating = isList || nullable)
                Coerce.Nested(elemFq)
            }

            else -> vError("@XmlMap '$kind' of '$owner': unsupported type '$elemFq'")
        }

        val typeName = typeNameFor(elemKsType, isList, elemNullable = nullable)
        val elemTypeName = typeNameFor(elemKsType, isList = false, elemNullable = nullable)
        return FieldSpec(
            name = syntheticName,
            typeName = typeName,
            nullable = nullable,
            isList = isList,
            elemNullable = nullable,
            elemTypeName = elemTypeName,
            elemTypeFq = elemFq,
            source = source,
            coerce = coerce,
        )
    }

    private fun isNestedDataClass(t: KSType): Boolean {
        val decl = t.declaration as? KSClassDeclaration ?: return false
        return Modifier.DATA in decl.modifiers
    }

    private fun ensureNested(
        cls: KSClassDeclaration,
        parentNs: Map<String, String>,
        registry: NestedTypeRegistry,
        terminating: Boolean,
    ): NestedTypeSpec {
        // ensureNested is only called for declared nested data classes; qualified name is present.
        val fq = cls.qualifiedName!!.asString()
        if (!terminating && registry.inProgress.contains(fq)) {
            val chain = registry.inProgress.toList()
            val start = chain.indexOf(fq).let { if (it < 0) 0 else it }
            val cycle = (chain.drop(start) + fq).joinToString(" -> ")
            vError("recursive nested data class $fq not supported (cycle: $cycle)")
        }
        val own = collectNs(cls)
        for ((p, u) in own) {
            val pu = parentNs[p] ?: continue
            if (pu != u) {
                vError("Nested data class $fq redeclares @XmlNs prefix '$p' as '$u' but enclosing record binds it to '$pu'")
            }
        }
        val ns = parentNs + own
        registry.byFq[fq]?.let { existing ->
            if (existing.nsMap != ns) {
                vError("Nested data class $fq used with conflicting @XmlNs scopes: ${existing.nsMap} vs $ns")
            }
            return existing
        }
        registry.inProgress.add(fq)
        try {
            val typeName = cls.toClassName()
            val helperName = "__parseNested_${typeName.simpleName}_${registry.byFq.size}"
            // ensureNested is gated by isNestedDataClass; data classes always have a primary ctor.
            val ctor = cls.primaryConstructor!!
            val stub = NestedTypeSpec(cls, typeName, helperName, ns, emptyList())
            registry.byFq[fq] = stub
            val fields = ctor.parameters.map { classifyParam(cls, it, ns, registry) }
            if (fields.count { it.source is Source.Text } > 1) {
                vError("$fq: multiple @XmlText fields not allowed")
            }
            validateChildPaths(cls, fields)
            val complete = stub.copy(fields = fields)
            registry.byFq[fq] = complete
            return complete
        } finally {
            registry.inProgress.remove(fq)
        }
    }

    private fun coerceForType(typeFq: String, pattern: String?, fieldName: String): Coerce = when (typeFq) {
        KOTLIN_STRING -> Coerce.AsString
        KOTLIN_INT -> Coerce.Scalar(ScalarKind.INT)
        KOTLIN_LONG -> Coerce.Scalar(ScalarKind.LONG)
        KOTLIN_DOUBLE -> Coerce.Scalar(ScalarKind.DOUBLE)
        KOTLIN_BOOLEAN -> Coerce.Scalar(ScalarKind.BOOLEAN)
        "java.time.LocalDate" -> Coerce.Temporal(TemporalKind.LOCAL_DATE, pattern ?: "")
        "java.time.LocalDateTime" -> Coerce.Temporal(TemporalKind.LOCAL_DATE_TIME, pattern ?: "")
        "java.time.Instant" -> Coerce.Temporal(TemporalKind.INSTANT, pattern ?: "")
        "java.math.BigDecimal" -> Coerce.Decimal(pattern ?: "")
        else -> vError("Unsupported type '$typeFq' for field '$fieldName'.")
    }

    private fun typeNameFor(t: KSType, isList: Boolean, elemNullable: Boolean): TypeName {
        val elemFq = t.declaration.qualifiedName?.asString() ?: t.toString()
        val base: TypeName = when (elemFq) {
            KOTLIN_STRING -> STRING
            KOTLIN_INT -> INT
            KOTLIN_LONG -> LONG
            KOTLIN_DOUBLE -> DOUBLE
            KOTLIN_BOOLEAN -> BOOLEAN
            else -> {
                val cn = (t.declaration as? KSClassDeclaration)?.toClassName()
                    ?: ClassName.bestGuess(elemFq)
                cn
            }
        }
        val elem = base.copy(nullable = elemNullable)
        return if (isList) LIST.parameterizedBy(elem).copy(nullable = false) else elem
    }

    private fun parseChildPath(path: String, fieldName: String, nsMap: Map<String, String>): Source.Child {
        vRequire(path.isNotBlank()) { "@XmlChild path empty for '$fieldName'" }
        // Self-step shorthand. Historically accepted by the loose splitter; treat as a
        // single literal segment so existing @XmlMap value="." paths keep compiling.
        if (path == ".") {
            return Source.Child(listOf(PathSeg.Element(nsMap[""], ".")), descendant = false)
        }
        // Manually classify the leading axis — PathParser auto-prepends a descendant step to every
        // relative path, which would erase the direct/descendant distinction we need here.
        val descendant = path.startsWith("//")
        // Reject absolute non-descendant paths up front (`/foo`); PathParser would parse them but
        // they have no anchor inside @XmlChild.
        vRequire(!(path.startsWith("/") && !descendant)) {
            "@XmlChild path '$path' for '$fieldName': invalid syntax (absolute paths are not supported)"
        }
        val defaultNs: String? = nsMap[""]
        val parser = PathParser(
            nsResolve = { prefix -> nsMap[prefix] },
            defaultNs = defaultNs,
        )
        val compiled = try {
            parser.parse(path)
        } catch (e: xmlfluss.path.PathParseException) {
            vError("@XmlChild path '$path' for '$fieldName': ${rewriteParseError(e.message ?: "")}")
        }
        // PathParser auto-prepends a descendant step for relative paths and emits one for `//`;
        // strip the leading descendant either way and rely on our own `descendant` flag.
        val rawSteps = compiled.steps
        val steps = if (rawSteps.firstOrNull() is PathStep.Descendant) rawSteps.drop(1) else rawSteps
        vRequire(steps.isNotEmpty()) { "@XmlChild path '$path' for '$fieldName': empty after axis" }
        if (descendant && steps.firstOrNull() is PathStep.AttrLeaf) {
            vError("@XmlChild path '$path' for '$fieldName': descendant axis head must be an element")
        }
        // Reject internal descendant axes (e.g. wrapper//leaf) — current trie only supports
        // a single optional descendant prefix at the head.
        for ((i, s) in steps.withIndex()) {
            if (s is PathStep.Descendant) {
                vError("@XmlChild path '$path' for '$fieldName': '//' is only allowed at the head of the path")
            }
            if (s is PathStep.AttrLeaf) {
                vRequire(i == steps.lastIndex) { "@XmlChild path '$path' for '$fieldName': '@' segment must be last" }
                vRequire(i > 0) { "@XmlChild path '$path' for '$fieldName': use @XmlAttr for record-level attributes" }
            }
        }
        val out = mutableListOf<PathSeg>()
        for ((idx, s) in steps.withIndex()) {
            when (s) {
                is PathStep.Named -> {
                    vRequire(s.name.local != "*") {
                        "@XmlChild path '$path' for '$fieldName': wildcard local-name '*' is not supported"
                    }
                    vRequire(s.name.ns != PathParser.WILDCARD) {
                        "@XmlChild path '$path' for '$fieldName': wildcard namespace '{*}' is not supported"
                    }
                    val brackets = s.brackets
                    val onDescendantHead = descendant && idx == 0
                    for (b in brackets) validateChildPredicate(b, path, fieldName, onDescendantHead)
                    val indexBracketCount = brackets.count { containsIndex(it) }
                    vRequire(indexBracketCount <= 1) {
                        "@XmlChild path '$path' for '$fieldName': only one positional predicate is allowed per segment (found multiple in '${s.name.local}'). Express the second positional via @XmlRecord, or restructure your XML."
                    }
                    out += PathSeg.Element(s.name.ns, s.name.local, brackets)
                }
                is PathStep.AttrLeaf -> {
                    out += PathSeg.AttrLeaf(s.name.ns, s.name.local)
                }
                PathStep.Descendant -> error("unreachable: descendant filtered above")
            }
        }
        return Source.Child(out, descendant)
    }

    private fun rewriteParseError(msg: String): String {
        // Normalize PathParser wording into the historical messages we expose to users.
        var out = msg
        out = out.replace("unbound namespace prefix", "unbound NS prefix")
        if (out.startsWith("expected local-name after ':'") || out.startsWith("expected local-name after '}'")) {
            out = "malformed (bad qname): $out"
        }
        return out
    }

    private fun validateChildPredicate(
        p: PathPredicate,
        path: String,
        fieldName: String,
        onDescendantHead: Boolean,
    ) {
        when (p) {
            is PathPredicate.Index -> if (onDescendantHead) vError(
                "@XmlChild path '$path' for '$fieldName': positional predicate [${p.n}] is not supported on the descendant-axis segment ('//<name>[N]'). Move the positional filter to a direct-axis segment (e.g. '//parent/item[${p.n}]') or to @XmlRecord."
            )
            is PathPredicate.AttrEq -> {
                vRequire(p.name.ns != PathParser.WILDCARD) {
                    "@XmlChild path '$path' for '$fieldName': wildcard namespace in attribute predicate is not supported"
                }
            }
            is PathPredicate.And -> {
                validateChildPredicate(p.l, path, fieldName, onDescendantHead)
                validateChildPredicate(p.r, path, fieldName, onDescendantHead)
            }
            is PathPredicate.Or -> {
                vRequire(!containsIndex(p.l) && !containsIndex(p.r)) {
                    "@XmlChild path '$path' for '$fieldName': positional predicate inside 'or' is not supported. Use chained brackets ('[@x=\"v\"][N]') or 'and' to combine filters."
                }
                validateChildPredicate(p.l, path, fieldName, onDescendantHead)
                validateChildPredicate(p.r, path, fieldName, onDescendantHead)
            }
        }
    }

    private fun buildPolyChild(
        sealedDecl: KSClassDeclaration,
        rawPath: String,
        fieldName: String,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
    ): Source.PolyChild {
        // sealedDecl is selected upstream via takeIf { ... annotationOf(it, XML_POLYMORPHIC_FQ) != null };
        // the annotation is guaranteed present here.
        val polyAnnot = annotationOf(sealedDecl, XML_POLYMORPHIC_FQ)!!
        val discriminator = stringArg(polyAnnot, "discriminator").orEmpty()

        val subtypes = sealedDecl.getSealedSubclasses().toList()
        vRequire(subtypes.isNotEmpty()) {
            "polymorphic field '$fieldName': sealed type ${sealedDecl.qualifiedName?.asString()} has no subclasses"
        }
        for (s in subtypes) {
            vRequire(Modifier.DATA in s.modifiers) {
                "polymorphic field '$fieldName': subtype ${s.qualifiedName?.asString()} must be a data class"
            }
            vRequire(annotationOf(s, XML_SUBTYPE_FQ) != null) {
                "polymorphic field '$fieldName': subtype ${s.qualifiedName?.asString()} missing @XmlSubtype"
            }
        }

        for (s in subtypes) ensureNested(s, nsMap, registry, terminating = true)

        if (discriminator.isEmpty()) {
            vRequire(rawPath.isEmpty()) {
                "polymorphic field '$fieldName': tag-mode @XmlChild path must be empty (got '$rawPath'); subtype tags drive dispatch"
            }
            val variants = subtypes.map { sub ->
                val subAnnot = annotationOf(sub, XML_SUBTYPE_FQ)!!
                val subName = stringArg(subAnnot, "name")!!
                val (ns, local) = resolveQName(
                    subName, nsMap,
                    defaultNs = nsMap[""],
                    path = subName, field = fieldName,
                    ctx = "@XmlSubtype",
                )
                TagVariant(ns, local, sub.qualifiedName!!.asString())
            }
            val keys = variants.map { QKey(it.ns, it.local) }
            vRequire(keys.toSet().size == keys.size) {
                "polymorphic field '$fieldName': duplicate @XmlSubtype tags across variants"
            }
            return Source.PolyChild(PolyDispatch.Tag(variants))
        } else {
            vRequire(discriminator.startsWith("@")) {
                "polymorphic field '$fieldName': @XmlPolymorphic.discriminator must start with '@' (got '$discriminator')"
            }
            val attrRaw = discriminator.substring(1)
            vRequire(attrRaw.isNotEmpty() && '/' !in attrRaw) {
                "polymorphic field '$fieldName': bad discriminator '$discriminator'"
            }
            val (attrNs, attrLocal) = resolveQName(
                attrRaw, nsMap, defaultNs = null,
                path = discriminator, field = fieldName, ctx = "@XmlPolymorphic discriminator",
            )
            vRequire(rawPath.isNotBlank()) {
                "polymorphic field '$fieldName': attr-mode @XmlChild requires the wrapping element path"
            }
            vRequire(!rawPath.startsWith("//") && '/' !in rawPath && !rawPath.startsWith("@")) {
                "polymorphic field '$fieldName': attr-mode @XmlChild path must be a single direct-child element (got '$rawPath')"
            }
            val (wrapNs, wrapLocal) = resolveQName(
                rawPath, nsMap, defaultNs = nsMap[""],
                path = rawPath, field = fieldName,
            )
            val variants = subtypes.map { sub ->
                val subAnnot = annotationOf(sub, XML_SUBTYPE_FQ)!!
                val value = stringArg(subAnnot, "name")!!
                AttrVariant(value, sub.qualifiedName!!.asString())
            }
            vRequire(variants.map { it.value }.toSet().size == variants.size) {
                "polymorphic field '$fieldName': duplicate @XmlSubtype values across variants"
            }
            return Source.PolyChild(PolyDispatch.Attr(wrapNs, wrapLocal, attrNs, attrLocal, variants))
        }
    }

    private fun resolveQName(
        s: String,
        nsMap: Map<String, String>,
        defaultNs: String?,
        path: String,
        field: String,
        ctx: String = "@XmlChild",
    ): Pair<String?, String> {
        val ci = s.indexOf(':')
        if (ci < 0) return defaultNs to s
        val prefix = s.substring(0, ci)
        val local = s.substring(ci + 1)
        vRequire(prefix.isNotEmpty() && local.isNotEmpty()) {
            "$ctx path '$path' for '$field': bad qname '$s'"
        }
        val ns = nsMap[prefix]
            ?: vError("$ctx path '$path' for '$field': unbound NS prefix '$prefix' (declare via @XmlNs)")
        return ns to local
    }

    private fun validateChildPaths(cls: KSClassDeclaration, fields: List<FieldSpec>) {
        val root = TrieNode()
        val directKeys = mutableSetOf<QKey>()
        for (f in fields) {
            val src = f.source as? Source.Child ?: continue
            if (src.descendant) continue
            insertIntoTrie(root, src.segments, f)
            (src.segments.firstOrNull() as? PathSeg.Element)?.let { directKeys += QKey(it.ns, it.name) }
        }
        validateTrie(root, cls)

        val descendantHeads = mutableSetOf<QKey>()
        for (f in fields) {
            val src = f.source as? Source.Child ?: continue
            if (!src.descendant) continue
            (src.segments.firstOrNull() as? PathSeg.Element)?.let { descendantHeads += QKey(it.ns, it.name) }
        }
        val collision = directKeys intersect descendantHeads
        if (collision.isNotEmpty()) {
            val k = collision.first()
            vError("${cls.qualifiedName?.asString()}: @XmlChild('${k.local}') and @XmlChild('//${k.local}') target the same head element '${k.local}'; pick one")
        }

        val seenMapEntries = mutableSetOf<QKey>()
        for (f in fields) {
            val src = f.source as? Source.MapEntry ?: continue
            val key = QKey(src.entryNs, src.entryLocal)
            if (key in directKeys) {
                vError("${cls.qualifiedName?.asString()}: @XmlMap entry '${src.entryLocal}' on field '${f.name}' clashes with another @XmlChild's first segment")
            }
            if (key in descendantHeads) {
                vError("${cls.qualifiedName?.asString()}: @XmlMap entry '${src.entryLocal}' on field '${f.name}' clashes with a descendant @XmlChild('//${key.local}') head")
            }
            if (!seenMapEntries.add(key)) {
                vError("${cls.qualifiedName?.asString()}: duplicate @XmlMap entry '${src.entryLocal}' on field '${f.name}'")
            }
        }

        val seenPolyKeys = mutableSetOf<QKey>()
        var sawTagMode = false
        for (f in fields) {
            val src = f.source as? Source.PolyChild ?: continue
            when (val d = src.dispatch) {
                is PolyDispatch.Tag -> {
                    if (sawTagMode) {
                        vError("${cls.qualifiedName?.asString()}: more than one tag-mode polymorphic @XmlChild field at the same scope (field '${f.name}')")
                    }
                    sawTagMode = true
                    for (v in d.variants) {
                        val k = QKey(v.ns, v.local)
                        if (k in directKeys || k in seenMapEntries || !seenPolyKeys.add(k)) {
                            vError("${cls.qualifiedName?.asString()}: polymorphic subtype tag '${v.local}' on field '${f.name}' clashes with another @XmlChild / @XmlMap / subtype")
                        }
                        if (k in descendantHeads) {
                            vError("${cls.qualifiedName?.asString()}: polymorphic subtype tag '${v.local}' on field '${f.name}' clashes with a descendant @XmlChild('//${k.local}') head")
                        }
                    }
                }
                is PolyDispatch.Attr -> {
                    val k = QKey(d.wrapNs, d.wrapLocal)
                    if (k in directKeys || k in seenMapEntries || !seenPolyKeys.add(k)) {
                        vError("${cls.qualifiedName?.asString()}: polymorphic wrap tag '${d.wrapLocal}' on field '${f.name}' clashes with another @XmlChild / @XmlMap / subtype")
                    }
                    if (k in descendantHeads) {
                        vError("${cls.qualifiedName?.asString()}: polymorphic wrap tag '${d.wrapLocal}' on field '${f.name}' clashes with a descendant @XmlChild('//${k.local}') head")
                    }
                }
            }
        }
    }

    private fun insertIntoTrie(root: TrieNode, segments: List<PathSeg>, f: FieldSpec) {
        var node = root
        val elements = segments.takeWhile { it is PathSeg.Element }
            .map { it as PathSeg.Element }
        val tail = segments.drop(elements.size)
        for (e in elements) {
            val edge = EdgeKey(QKey(e.ns, e.name), e.brackets)
            node = node.children.getOrPut(edge) { TrieNode() }
        }
        when {
            tail.isEmpty() -> {
                if (f.coerce is Coerce.Nested) node.nestedEntries += f
                else node.textEntries += f
            }

            tail.size == 1 && tail[0] is PathSeg.AttrLeaf -> {
                val al = tail[0] as PathSeg.AttrLeaf
                node.attrEntries += Triple(al.ns, al.name, f)
            }

            else -> vError("invalid path tail for field '${f.name}'")
        }
    }

    private fun validateTrie(node: TrieNode, cls: KSClassDeclaration) {
        val mixCount = listOf(
            node.textEntries.isNotEmpty(),
            node.nestedEntries.isNotEmpty(),
            node.children.isNotEmpty(),
        ).count { it }
        if (mixCount > 1) {
            val texts = node.textEntries.joinToString { it.name }
            val nested = node.nestedEntries.joinToString { it.name }
            vError("${cls.qualifiedName?.asString()}: cannot mix text/nested/descend at same element [text=$texts nested=$nested children=${node.children.size}]")
        }
        val nonListText = node.textEntries.count { !it.isList }
        if (nonListText > 1) {
            val names = node.textEntries.filter { !it.isList }.joinToString { it.name }
            vError("${cls.qualifiedName?.asString()}: multiple non-list text fields [$names] target same element")
        }
        val nonListNested = node.nestedEntries.count { !it.isList }
        if (nonListNested > 1) {
            val names = node.nestedEntries.filter { !it.isList }.joinToString { it.name }
            vError("${cls.qualifiedName?.asString()}: multiple non-list nested fields [$names] target same element")
        }
        if (node.nestedEntries.size > 1 &&
            node.nestedEntries.any { it.isList } && node.nestedEntries.any { !it.isList }
        ) {
            vError("${cls.qualifiedName?.asString()}: cannot mix list and non-list nested fields at same element")
        }
        for (c in node.children.values) validateTrie(c, cls)
    }

    private fun emitFile(
        cls: KSClassDeclaration,
        recordPath: String,
        nsMap: Map<String, String>,
        fields: List<FieldSpec>,
        registry: NestedTypeRegistry,
    ) {
        val pkg = cls.packageName.asString()
        val recordTypeName = ClassName(pkg, cls.simpleName.asString())
        val parserName = "${cls.simpleName.asString()}Parser"

        val flowOfRecord = FLOW.parameterizedBy(recordTypeName)

        val nsProp = PropertySpec.builder("NS", MAP.parameterizedBy(STRING, STRING))
            .addModifiers(KModifier.PRIVATE)
            .initializer(buildNsInitializer(nsMap))
            .build()

        val pathProp = PropertySpec.builder("PATH", COMPILED_PATH)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%M(%S, NS)", PATHS_COMPILE, recordPath)
            .build()

        val convVarFor = mutableMapOf<String, String>()
        val convProps = mutableListOf<PropertySpec>()
        fun registerConverter(c: Coerce.Custom) {
            val key = c.cls.canonicalName
            if (key in convVarFor) return
            val varName = "__conv_${convVarFor.size}"
            convVarFor[key] = varName
            convProps += PropertySpec.builder(varName, c.cls)
                .addModifiers(KModifier.PRIVATE)
                .initializer("%T()", c.cls)
                .build()
        }
        for (f in fields) (f.coerce as? Coerce.Custom)?.let(::registerConverter)
        for (n in registry.byFq.values) for (f in n.fields) (f.coerce as? Coerce.Custom)?.let(::registerConverter)

        val parseFun = FunSpec.builder("parse")
            // @JvmOverloads lets Java callers omit `ignoreNamespace` and call parse(input)
            .addAnnotation(JvmOverloads::class)
            .addParameter(ParameterSpec("input", INPUT_STREAM))
            .addParameter(
                ParameterSpec.builder("ignoreNamespace", BOOLEAN)
                    .defaultValue("false")
                    .build()
            )
            .returns(flowOfRecord)
            .addCode(buildParseBody(recordTypeName, fields, convVarFor, registry))
            .build()

        val nestedHelpers = registry.byFq.values.map { spec ->
            FunSpec.builder(spec.helperName)
                .addModifiers(KModifier.PRIVATE)
                .addParameter("c", XML_READ_CURSOR)
                .returns(spec.typeName)
                .addCode(buildSubrecordBody(spec, convVarFor, registry))
                .build()
        }

        val parserBuilder = TypeSpec.objectBuilder(parserName)
            .addProperty(nsProp)
            .addProperty(pathProp)
        for (p in convProps) parserBuilder.addProperty(p)
        for (h in nestedHelpers) parserBuilder.addFunction(h)
        parserBuilder.addFunction(parseFun)
        parserBuilder.addOriginatingKSFile(cls.containingFile!!)

        val file = FileSpec.builder(pkg, parserName)
            .addAnnotation(
                AnnotationSpec.builder(Generated::class)
                    .addMember("%S", "xml-fluss-ksp")
                    .addMember("date = %S", LocalDate.now().toString())
                    .build()
            )
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("\"FunctionName\", \"RedundantExplicitType\", \"RedundantVisibilityModifier\", \"LocalVariableName\", \"IfThenToSafeAccess\"")
                    .build()
            )
            .addType(parserBuilder.build())
            .build()

        file.writeTo(codeGen, aggregating = false)
    }

    private fun buildNsInitializer(nsMap: Map<String, String>): CodeBlock {
        if (nsMap.isEmpty()) return CodeBlock.of("emptyMap()")
        val cb = CodeBlock.builder().add("mapOf(\n")
        nsMap.forEach { (k, v) -> cb.add("    %S to %S,\n", k, v) }
        cb.add(")")
        return cb.build()
    }

    private fun buildParseBody(
        recordType: ClassName,
        fields: List<FieldSpec>,
        convVarFor: Map<String, String>,
        registry: NestedTypeRegistry,
    ): CodeBlock {
        val cb = CodeBlock.builder()
        cb.beginControlFlow("return·%M", FLOW_BUILDER)
        cb.beginControlFlow("%T(input,·PATH,·ignoreNamespace).use·{ c ->\n", XML_READ_CURSOR)
        cb.beginControlFlow("while (c.findNextRecord())")
        emitInstanceBody(cb, recordType, fields, convVarFor, registry, ctx = Ctx.RECORD)
        cb.endControlFlow()
        cb.endControlFlow()
        cb.endControlFlow()
        return cb.build()
    }

    private fun buildSubrecordBody(
        spec: NestedTypeSpec,
        convVarFor: Map<String, String>,
        registry: NestedTypeRegistry,
    ): CodeBlock {
        val cb = CodeBlock.builder()
        emitInstanceBody(cb, spec.typeName, spec.fields, convVarFor, registry, ctx = Ctx.SUBRECORD)
        return cb.build()
    }

    private fun emitInstanceBody(
        cb: CodeBlock.Builder,
        type: ClassName,
        fields: List<FieldSpec>,
        convVarFor: Map<String, String>,
        registry: NestedTypeRegistry,
        ctx: Ctx,
    ) {
        val attrFields = fields.filter { it.source is Source.Attr }
        val textField = fields.firstOrNull { it.source is Source.Text }
        val childFields = fields.filter { it.source is Source.Child }
        val mapFields = fields.filter { it.source is Source.MapEntry }
        val polyFields = fields.filter { it.source is Source.PolyChild }

        val attrFn = if (ctx == Ctx.RECORD) "recordAttr" else "childAttr"
        val forEachFn = if (ctx == Ctx.RECORD) "forEachRecordChild" else "forEachSubrecordChild"
        val textFn = if (ctx == Ctx.RECORD) "recordText" else "subrecordText"
        val locFn = if (ctx == Ctx.RECORD) "recordLocation" else "childLocation"

        cb.add("val __loc: %T = c.${locFn}()\n", LOCATION)

        for (f in attrFields) {
            val src = f.source as Source.Attr
            val nsLit: CodeBlock = if (src.ns == null) CodeBlock.of("null") else CodeBlock.of("%S", src.ns)
            cb.add("val __raw_${f.name}: %T = c.${attrFn}(%L, %S)\n", STRING_NULLABLE, nsLit, src.name)
        }

        for (f in childFields + polyFields + listOfNotNull(textField)) emitFieldStateInit(cb, f)
        for (mf in mapFields) emitMapStateInit(cb, mf)

        val descendantFields = childFields.filter { (it.source as Source.Child).descendant }
        val directFields = childFields.filter { !(it.source as Source.Child).descendant }

        // Invariant: textField != null  ⇒  needTraverse == true (textField is one of the
        // disjuncts below). coerceField below reads `__raw_${textField.name}` unconditionally,
        // so the declaration emitted inside this block is always reached when textField != null.
        val needTraverse = childFields.isNotEmpty() || textField != null || mapFields.isNotEmpty() || polyFields.isNotEmpty()
        if (needTraverse) {
            val root = TrieNode()
            for (f in directFields) {
                val src = f.source as Source.Child
                insertIntoTrie(root, src.segments, f)
            }
            // Counter slots must be declared OUTSIDE the per-sibling lambda so that ++__cnt[0]
            // accumulates across siblings rather than resetting per iteration.
            val slots = declareCounterSlots(cb, groupChildrenByQKey(root))
            cb.beginControlFlow("c.${forEachFn}·{ ln, ns ->\n")
            emitTopLevelChildSwitch(cb, root, slots, descendantFields, mapFields, polyFields, registry, convVarFor)
            cb.endControlFlow()
            if (textField != null) {
                val preserve = (textField.source as Source.Text).preserveWhitespace
                cb.add("val __raw_${textField.name}: %T = c.${textFn}($preserve)\n", STRING)
            }
        }

        for (f in fields) cb.add(coerceField(f, convVarFor))

        val emitVerb = if (ctx == Ctx.RECORD) "emit" else "return"
        cb.add("$emitVerb(%T(\n", type)
        cb.indent()
        for (f in fields) cb.add("${f.name} = __final_${f.name},\n")
        cb.unindent()
        cb.add("))\n")
    }

    private fun emitFieldStateInit(cb: CodeBlock.Builder, f: FieldSpec) {
        // Source.Text is declared and assigned in one go at the recordText/subrecordText call
        // site (see emitParseBody). No state needed up front — the cursor always provides a
        // String, so the early `var __raw_X: String? = null` would just be a dead initializer.
        if (f.source is Source.Text) return
        when {
            f.coerce is Coerce.Nested && f.isList -> {
                cb.add(
                    "val __list_${f.name}: %T = mutableListOf()\n",
                    MUTABLE_LIST.parameterizedBy(f.elemTypeName)
                )
            }

            f.coerce is Coerce.Nested -> {
                cb.add("var __set_${f.name}: %T = false\n", BOOLEAN)
                cb.add(
                    "var __nested_${f.name}: %T = null\n",
                    f.elemTypeName.copy(nullable = true)
                )
            }

            f.isList -> {
                cb.add(
                    "val __list_${f.name}: %T = mutableListOf()\n",
                    MUTABLE_LIST.parameterizedBy(STRING)
                )
            }

            else -> {
                cb.add("var __set_${f.name}: %T = false\n", BOOLEAN)
                cb.add("var __raw_${f.name}: %T = null\n", STRING_NULLABLE)
                if (needsChildLoc(f)) {
                    cb.add("var __loc_${f.name}: %T = null\n", LOCATION_NULLABLE)
                }
            }
        }
    }

    /**
     * `__loc_X` is read only by [coerceField] when the child field's `coerceRaw` consumes the
     * `lc` argument — i.e. for Scalar/Temporal/Decimal/Custom coercions on a single (non-list)
     * `Source.Child`. `AsString`, `Nested`, and list paths never reference `__loc_X`, so emitting
     * it would be dead code.
     */
    private fun needsChildLoc(f: FieldSpec): Boolean =
        f.source is Source.Child && !f.isList &&
            f.coerce !is Coerce.AsString && f.coerce !is Coerce.Nested

    private fun emitMapStateInit(cb: CodeBlock.Builder, f: FieldSpec) {
        // emitMapStateInit only runs for Coerce.MapAggregate fields built by classifyMapParam,
        // which always sets mapKeyField / mapValueField.
        val keyF = f.mapKeyField!!
        val valF = f.mapValueField!!
        val storedValueType: TypeName =
            if (valF.isList) MUTABLE_LIST.parameterizedBy(valF.elemTypeName) else valF.typeName
        val storeType = MUTABLE_MAP.parameterizedBy(keyF.typeName, storedValueType)
        cb.add("val __map_${f.name}: %T = %M()\n", storeType, LINKED_MAP_OF)
        if (f.nullable) cb.add("var __set_${f.name}: %T = false\n", BOOLEAN)
    }

    private fun emitMapEntryCase(
        cb: CodeBlock.Builder,
        f: FieldSpec,
        registry: NestedTypeRegistry,
        convVarFor: Map<String, String>,
    ) {
        // Same invariant as emitMapStateInit: MapAggregate fields always carry both synthetic
        // key/value FieldSpecs.
        val keyF = f.mapKeyField!!
        val valF = f.mapValueField!!
        val synthetic = listOf(keyF, valF)

        for (sf in synthetic) {
            val src = sf.source
            if (src is Source.Attr) {
                val nsLit: CodeBlock = if (src.ns == null) CodeBlock.of("null") else CodeBlock.of("%S", src.ns)
                if (sf.isList) {
                    cb.add(
                        "val __list_${sf.name}: %T = mutableListOf()\n",
                        MUTABLE_LIST.parameterizedBy(STRING)
                    )
                    cb.add("c.childAttr(%L, %S)?.let { __list_${sf.name}.add(it) }\n", nsLit, src.name)
                } else {
                    cb.add(
                        "val __raw_${sf.name}: %T = c.childAttr(%L, %S)\n",
                        STRING_NULLABLE, nsLit, src.name
                    )
                }
            } else {
                emitFieldStateInit(cb, sf)
            }
        }

        val childSyn = synthetic.filter { it.source is Source.Child }
        if (childSyn.isNotEmpty()) {
            val descendF = childSyn.filter { (it.source as Source.Child).descendant }
            val directF = childSyn.filter { !(it.source as Source.Child).descendant }
            val root = TrieNode()
            for (df in directF) insertIntoTrie(root, (df.source as Source.Child).segments, df)
            val slots = declareCounterSlots(cb, groupChildrenByQKey(root))
            cb.beginControlFlow("c.forEachSubrecordChild·{ ln, ns ->\n")
            emitTopLevelChildSwitch(cb, root, slots, descendF, emptyList(), emptyList(), registry, convVarFor)
            cb.endControlFlow()
        }

        cb.add(coerceField(keyF, convVarFor))
        cb.add(coerceField(valF, convVarFor))

        if (valF.isList) {
            cb.add(
                "__map_${f.name}.getOrPut(__final_${keyF.name}) { mutableListOf() }.addAll(__final_${valF.name})\n"
            )
        } else {
            cb.add("__map_${f.name}[__final_${keyF.name}] = __final_${valF.name}\n")
        }
        if (f.nullable) cb.add("__set_${f.name} = true\n")
    }

    /**
     * Emits the inside-lambda body of a `forEachChild { ln, ns -> ... }` switch over the direct
     * children of [node]. Counter slot declarations live OUTSIDE the lambda — callers obtain them
     * via [declareCounterSlots] and pass them in via [slots] so the IntArray persists across
     * sibling iterations.
     */
    private fun emitChildrenSwitch(
        cb: CodeBlock.Builder,
        node: TrieNode,
        slots: Map<PrefixKey, String>,
        registry: NestedTypeRegistry,
    ) {
        if (node.children.isEmpty()) {
            cb.add(SKIP_CHILD)
            return
        }
        val grouped = groupChildrenByQKey(node)
        cb.beginControlFlow("when(ln)")
        for ((qk, branches) in grouped) {
            val cond = qnameCond(qk.ns, qk.local)
            cb.beginControlFlow("%L ->", cond)
            emitPredicateBranches(cb, qk, branches, slots, registry)
            cb.endControlFlow()
        }
        cb.add(ELSE_SKIP_CHILD)
        cb.endControlFlow()
    }

    private fun groupChildrenByQKey(node: TrieNode): LinkedHashMap<QKey, MutableList<Pair<List<PathPredicate>, TrieNode>>> {
        val grouped = LinkedHashMap<QKey, MutableList<Pair<List<PathPredicate>, TrieNode>>>()
        for ((edge, child) in node.children) {
            grouped.getOrPut(edge.qkey) { mutableListOf() }.add(edge.brackets to child)
        }
        return grouped
    }

    /**
     * Emits `IntArray(1)` declarations for each `(qkey, prefix)` slot needed by direct edges in
     * [grouped] that contain a positional predicate. Returns a map from `PrefixKey` to the slot
     * variable name so call sites can reference `__cnt_<name>`/`__pre_<name>`/`__pos_<name>`.
     */
    private fun declareCounterSlots(
        cb: CodeBlock.Builder,
        grouped: Map<QKey, List<Pair<List<PathPredicate>, TrieNode>>>,
    ): Map<PrefixKey, String> {
        val slots = LinkedHashMap<PrefixKey, String>()
        for ((qk, branches) in grouped) {
            for ((brackets, _) in branches) {
                if (!bracketsHaveIndex(brackets)) continue
                val prefix = prefixOfFirstIndex(brackets)
                val key = PrefixKey(qk, prefix)
                if (key in slots) continue
                val name = slotName(qk, slots.size)
                slots[key] = name
                cb.add("val __cnt_%L: %T = intArrayOf(0)\n", name, INT_ARRAY)
            }
        }
        return slots
    }

    private fun emitPredicateBranches(
        cb: CodeBlock.Builder,
        qkey: QKey,
        branches: List<Pair<List<PathPredicate>, TrieNode>>,
        slots: Map<PrefixKey, String>,
        registry: NestedTypeRegistry,
    ) {
        // Per-element pre-compute: increment any counters that key on this qname BEFORE the
        // two-pass dispatch. Each element produces exactly one increment per slot, regardless of
        // how many attr-only / body branches reference that slot.
        val slotsAtQName: Map<PrefixKey, String> = slots.filterKeys { it.qkey == qkey }
        for ((key, name) in slotsAtQName) {
            if (key.prefix.isEmpty()) {
                // No prefix guard — unconditionally increment.
                cb.add("val __pos_%L: %T = ++__cnt_%L[0]\n", name, INT, name)
            } else {
                val preExpr: CodeBlock = plainPredicateExpr(key.prefix.reduce { a, b -> PathPredicate.And(a, b) })
                cb.add("val __pre_%L: %T = %L\n", name, BOOLEAN, preExpr)
                cb.add("val __pos_%L: %T = if (__pre_%L) ++__cnt_%L[0] else 0\n", name, INT, name, name)
            }
        }

        if (branches.size == 1 && branches[0].first.isEmpty()) {
            emitChildBody(cb, branches[0].second, registry)
            return
        }
        // Two-pass dispatch when predicate variants overlap on the same element:
        //  1. attr-only reads run for EVERY matching predicate (childAttr is a pure lookup, no
        //     element consumption). Without this, two paths like
        //       link[@type='epub']/@href
        //       link[@type='epub'][@rel='acq']/@href
        //     would race and only the first matching arm would fire.
        //  2. Body-consuming variants (text / nested / descend) dispatch first-match-wins —
        //     a single element body can only be consumed once.
        for ((brackets, child) in branches) {
            if (child.attrEntries.isEmpty()) continue
            val expr = predicateExpr(brackets, qkey, slots)
            cb.beginControlFlow("if (%L)", expr)
            emitAttrEntries(cb, child)
            cb.endControlFlow()
        }
        val bodyBranches = branches.filter { (_, n) -> nodeHasBodyContent(n) }
        if (bodyBranches.isEmpty()) {
            cb.add(SKIP_CHILD)
            return
        }
        cb.beginControlFlow("when")
        for ((brackets, child) in bodyBranches) {
            val expr = predicateExpr(brackets, qkey, slots)
            cb.beginControlFlow("%L ->", expr)
            emitChildBodyContent(cb, child, registry)
            cb.endControlFlow()
        }
        cb.add("else -> $SKIP_CHILD")
        cb.endControlFlow()
    }

    private fun nodeHasBodyContent(node: TrieNode): Boolean =
        node.textEntries.isNotEmpty() || node.nestedEntries.isNotEmpty() || node.children.isNotEmpty()

    private fun emitAttrEntries(cb: CodeBlock.Builder, node: TrieNode) {
        for ((attrNs, attrName, f) in node.attrEntries) {
            val nsLit: CodeBlock = if (attrNs == null) CodeBlock.of("null") else CodeBlock.of("%S", attrNs)
            if (f.isList) {
                cb.add("c.childAttr(%L, %S)?.let { __list_${f.name}.add(it) }\n", nsLit, attrName)
            } else if (needsChildLoc(f)) {
                cb.add(
                    "c.childAttr(%L, %S)?.let { __raw_${f.name} = it; __loc_${f.name} = c.childLocation(); __set_${f.name} = true }\n",
                    nsLit, attrName
                )
            } else {
                cb.add(
                    "c.childAttr(%L, %S)?.let { __raw_${f.name} = it; __set_${f.name} = true }\n",
                    nsLit, attrName
                )
            }
        }
    }

    private fun emitChildBodyContent(cb: CodeBlock.Builder, node: TrieNode, registry: NestedTypeRegistry) {
        val hasText = node.textEntries.isNotEmpty()
        val hasNested = node.nestedEntries.isNotEmpty()
        val hasDescend = node.children.isNotEmpty()
        when {
            hasNested -> {
                for (f in node.nestedEntries) {
                    val spec = registry.byFq.getValue(f.elemTypeFq)
                    cb.add("val __n_${f.name}·=·${spec.helperName}(c)\n")
                    if (f.isList) {
                        cb.add("__list_${f.name}.add(__n_${f.name})\n")
                    } else {
                        cb.add("__nested_${f.name} = __n_${f.name}\n")
                        cb.add("__set_${f.name} = true\n")
                    }
                }
            }
            hasText -> {
                val needLoc = node.textEntries.any { needsChildLoc(it) }
                if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
                cb.add("val __t = c.childText(false)\n")
                for (f in node.textEntries) {
                    if (f.isList) cb.add("__list_${f.name}.add(__t)\n")
                    else {
                        cb.add("__raw_${f.name} = __t\n")
                        if (needsChildLoc(f)) cb.add("__loc_${f.name} = __t_loc\n")
                        cb.add("__set_${f.name} = true\n")
                    }
                }
            }
            hasDescend -> {
                // Counter slots for direct edges under `node` must outlive the per-sibling lambda
                // — declare them here, BEFORE entering forEachChild, so increments accumulate
                // across siblings of the same parent.
                val grouped = groupChildrenByQKey(node)
                val slots = declareCounterSlots(cb, grouped)
                cb.beginControlFlow("c.forEachChild·{ ln, ns ->\n")
                emitChildrenSwitch(cb, node, slots, registry)
                cb.endControlFlow()
            }
            else -> cb.add(SKIP_CHILD)
        }
    }

    /**
     * Emit a boolean expression for [brackets] in the context of [qkey], optionally referencing
     * counter slots from [slots]. When any bracket contains a positional [PathPredicate.Index],
     * the corresponding `__pre_<slot>`/`__pos_<slot>` references are emitted; everything before
     * the first Index becomes part of the slot's prefix expression and everything after is
     * appended as plain expressions.
     */
    private fun predicateExpr(
        brackets: List<PathPredicate>,
        qkey: QKey,
        slots: Map<PrefixKey, String>,
    ): CodeBlock {
        if (brackets.isEmpty()) return CodeBlock.of("true")
        if (!bracketsHaveIndex(brackets)) {
            val folded = brackets.reduce { a, b -> PathPredicate.And(a, b) }
            return plainPredicateExpr(folded)
        }
        val firstIdxIndex = brackets.indexOfFirst { containsIndex(it) }
        // unreachable: bracketsHaveIndex returned true, so at least one bracket contains Index.
        require(firstIdxIndex >= 0) { "predicateExpr called with no Index in brackets — bug in bracketsHaveIndex" }
        val prefix = brackets.subList(0, firstIdxIndex)
        val firstIdxBracket = brackets[firstIdxIndex]
        val suffix = brackets.subList(firstIdxIndex + 1, brackets.size)
        val n = firstIndexValue(firstIdxBracket)
        val slotKey = PrefixKey(qkey, prefix)
        val slotName = slots[slotKey]
            // unreachable: declareCounterSlots populates every (qkey, prefix) pair we encounter.
            ?: error("missing counter slot for $slotKey at qkey=$qkey — bug in counter detection")
        val parts = mutableListOf<CodeBlock>()
        // When the prefix is empty there is no __pre_ variable — the counter is always incremented.
        if (prefix.isNotEmpty()) parts += CodeBlock.of("__pre_%L", slotName)
        parts += CodeBlock.of("(__pos_%L == %L)", slotName, n)
        // If the first-index bracket also contains non-Index predicates (e.g. `[2 and @x='y']`),
        // emit those alongside the position check.
        val residual = stripIndex(firstIdxBracket)
        if (residual != null) parts += plainPredicateExpr(residual)
        // Suffix: every bracket after the one that introduced the Index. These are evaluated as
        // ordinary attribute predicates against the current element — they refine the position
        // match but do not affect counter incrementing.
        for (s in suffix) parts += plainPredicateExpr(s)
        return parts.reduce { a, b -> CodeBlock.of("(%L && %L)", a, b) }
    }

    /**
     * Emit a boolean expression for an Index-free predicate. Equivalent to the legacy
     * `predicateExpr` minus the Index arm; descending into And/Or recurses through this same
     * function. Callers must guarantee [p] contains no [PathPredicate.Index].
     */
    private fun plainPredicateExpr(p: PathPredicate): CodeBlock = when (p) {
        is PathPredicate.AttrEq -> {
            val ns = p.name.ns
            val nsLit: CodeBlock = if (ns == null) CodeBlock.of("null") else CodeBlock.of("%S", ns)
            if (p.negate) {
                CodeBlock.of("(c.childAttr(%L, %S).let { it == null || it != %S })", nsLit, p.name.local, p.value)
            } else {
                CodeBlock.of("(c.childAttr(%L, %S) == %S)", nsLit, p.name.local, p.value)
            }
        }
        is PathPredicate.And -> CodeBlock.of("(%L && %L)", plainPredicateExpr(p.l), plainPredicateExpr(p.r))
        is PathPredicate.Or -> CodeBlock.of("(%L || %L)", plainPredicateExpr(p.l), plainPredicateExpr(p.r))
        // unreachable: caller routes Index-bearing brackets through predicateExpr; stripIndex removes Index nodes from And residuals before recursion.
        is PathPredicate.Index -> error("plainPredicateExpr called on Index — counter logic should have stripped this")
    }

    private fun containsIndex(p: PathPredicate): Boolean = when (p) {
        is PathPredicate.Index -> true
        is PathPredicate.AttrEq -> false
        is PathPredicate.And -> containsIndex(p.l) || containsIndex(p.r)
        is PathPredicate.Or -> containsIndex(p.l) || containsIndex(p.r)
    }

    private fun bracketsHaveIndex(brackets: List<PathPredicate>): Boolean =
        brackets.any { containsIndex(it) }

    private fun prefixOfFirstIndex(brackets: List<PathPredicate>): List<PathPredicate> =
        brackets.takeWhile { !containsIndex(it) }

    /**
     * Walks [p] left-to-right and returns the first Index value encountered. The grammar permits
     * `[2 and @x='y']` which yields `And(Index(2), AttrEq(...))` — Index can appear anywhere.
     */
    private fun firstIndexValue(p: PathPredicate): Int = when (p) {
        is PathPredicate.Index -> p.n
        is PathPredicate.And -> if (containsIndex(p.l)) firstIndexValue(p.l) else firstIndexValue(p.r)
        // unreachable: validateChildPredicate rejects Or that contains Index, so no Or reaches this point.
        is PathPredicate.Or -> error("Index inside Or predicate is not supported (predicate=$p)")
        // unreachable: invoked only on brackets where containsIndex returned true; the And arm walks toward the Index so a pure-AttrEq leaf is never the direct argument.
        is PathPredicate.AttrEq -> error("firstIndexValue: predicate has no Index ($p)")
    }

    /**
     * Returns [p] with all Index sub-predicates removed, or null if removal leaves nothing. Only
     * defined for And-shaped composites — Or with an embedded Index is rejected upstream via
     * [firstIndexValue].
     */
    private fun stripIndex(p: PathPredicate): PathPredicate? = when (p) {
        is PathPredicate.Index -> null
        is PathPredicate.AttrEq -> p
        is PathPredicate.And -> {
            val l = stripIndex(p.l)
            val r = stripIndex(p.r)
            when {
                l == null -> r
                r == null -> l
                else -> PathPredicate.And(l, r)
            }
        }
        // unreachable: stripIndex is only called on Index-bearing brackets, and validateChildPredicate rejects Or that contains Index upstream.
        is PathPredicate.Or -> error("Index inside Or predicate is not supported (predicate=$p)")
    }

    private fun slotName(qkey: QKey, ordinal: Int): String {
        val safe = qkey.local.replace(Regex("[^A-Za-z0-9_]"), "_")
        return "${safe}_$ordinal"
    }

    data class PrefixKey(val qkey: QKey, val prefix: List<PathPredicate>)

    private fun emitTopLevelChildSwitch(
        cb: CodeBlock.Builder,
        directRoot: TrieNode,
        slots: Map<PrefixKey, String>,
        descendantFields: List<FieldSpec>,
        mapFields: List<FieldSpec>,
        polyFields: List<FieldSpec>,
        registry: NestedTypeRegistry,
        convVarFor: Map<String, String>,
    ) {
        if (directRoot.children.isEmpty() && descendantFields.isEmpty() && mapFields.isEmpty() && polyFields.isEmpty()) {
            cb.add(SKIP_CHILD)
            return
        }
        val byHead = LinkedHashMap<QKey, MutableList<Pair<List<PathPredicate>, FieldSpec>>>()
        for (f in descendantFields) {
            val seg = (f.source as Source.Child).segments[0] as PathSeg.Element
            byHead.getOrPut(QKey(seg.ns, seg.name)) { mutableListOf() }.add(seg.brackets to f)
        }
        val groupedDirect = groupChildrenByQKey(directRoot)
        cb.beginControlFlow("when(ln)")
        for ((qk, branches) in groupedDirect) {
            val cond = qnameCond(qk.ns, qk.local)
            cb.beginControlFlow("%L ->", cond)
            emitPredicateBranches(cb, qk, branches, slots, registry)
            cb.endControlFlow()
        }
        for (mf in mapFields) {
            val src = mf.source as Source.MapEntry
            val cond = qnameCond(src.entryNs, src.entryLocal)
            cb.beginControlFlow("%L ->", cond)
            emitMapEntryCase(cb, mf, registry, convVarFor)
            cb.endControlFlow()
        }
        for (pf in polyFields) {
            when (val d = (pf.source as Source.PolyChild).dispatch) {
                is PolyDispatch.Tag -> {
                    for (v in d.variants) {
                        val cond = qnameCond(v.ns, v.local)
                        cb.beginControlFlow("%L ->", cond)
                        emitPolyAssign(cb, pf, v.subtypeFq, registry)
                        cb.endControlFlow()
                    }
                }
                is PolyDispatch.Attr -> {
                    val cond = qnameCond(d.wrapNs, d.wrapLocal)
                    cb.beginControlFlow("%L ->", cond)
                    emitPolyAttrSwitch(cb, pf, d, registry)
                    cb.endControlFlow()
                }
            }
        }
        for ((head, branches) in byHead) {
            val cond = qnameCond(head.ns, head.local)
            cb.beginControlFlow("%L ->", cond)
            emitDescendantArm(cb, head, branches, registry, terminating = false)
            cb.endControlFlow()
        }
        if (byHead.isEmpty()) {
            cb.add(ELSE_SKIP_CHILD)
        } else {
            cb.beginControlFlow("else ->")
            cb.beginControlFlow("c.forEachDescendantInChild·{ dln, dns ->\n")
            cb.beginControlFlow("when(dln)")
            for ((head, branches) in byHead) {
                val cond = qnameCond(head.ns, head.local, nsVar = "dns")
                cb.beginControlFlow("%L ->", cond)
                emitDescendantArm(cb, head, branches, registry, terminating = true)
                cb.endControlFlow()
            }
            cb.add("else -> false\n")
            cb.endControlFlow()
            cb.endControlFlow()
            cb.endControlFlow()
        }
        cb.endControlFlow()
    }

    private fun emitDescendantArm(
        cb: CodeBlock.Builder,
        head: QKey,
        branches: List<Pair<List<PathPredicate>, FieldSpec>>,
        registry: NestedTypeRegistry,
        terminating: Boolean,
    ) {
        // The descendant-axis head segment cannot carry a positional predicate (rejected by
        // validateChildPredicate), so brackets here are guaranteed Index-free. We can still have
        // attribute-equality predicates that select among descendant heads.
        val unguarded = branches.filter { it.first.isEmpty() }.map { it.second }
        val guarded = branches.filter { it.first.isNotEmpty() }
        if (guarded.isEmpty()) {
            emitDescendantArmBody(cb, head, unguarded, registry)
            if (terminating) cb.add("true\n")
            return
        }
        cb.beginControlFlow("when")
        for ((brackets, f) in guarded) {
            cb.beginControlFlow("%L ->", predicateExpr(brackets, head, emptyMap()))
            emitDescendantArmBody(cb, head, listOf(f), registry)
            if (terminating) cb.add("true\n")
            cb.endControlFlow()
        }
        if (unguarded.isNotEmpty()) {
            cb.beginControlFlow("else ->")
            emitDescendantArmBody(cb, head, unguarded, registry)
            if (terminating) cb.add("true\n")
            cb.endControlFlow()
        } else {
            cb.add("else -> ")
            if (terminating) cb.add("false\n") else cb.add(SKIP_CHILD)
        }
        cb.endControlFlow()
    }

    private fun emitDescendantArmBody(
        cb: CodeBlock.Builder,
        head: QKey,
        headFields: List<FieldSpec>,
        registry: NestedTypeRegistry,
    ) {
        val emptyTail = headFields.filter { (it.source as Source.Child).segments.size == 1 }
        val nonEmptyTail = headFields.filter { (it.source as Source.Child).segments.size > 1 }
        vRequire(!(emptyTail.isNotEmpty() && nonEmptyTail.isNotEmpty())) {
            "cannot mix '//${head.local}' with '//${head.local}/...' on the same head element"
        }
        if (emptyTail.isNotEmpty()) {
            vRequire(emptyTail.size == 1) {
                "multiple descendant fields targeting '//${head.local}' (single-segment); at most one allowed"
            }
            emitLeafReadInline(cb, emptyTail[0], registry)
        } else {
            val tailTrie = TrieNode()
            for (f in nonEmptyTail) {
                val tail = (f.source as Source.Child).segments.drop(1)
                insertIntoTrie(tailTrie, tail, f)
            }
            emitChildBody(cb, tailTrie, registry)
        }
    }

    private fun qnameCond(ns: String?, local: String, nsVar: String = "ns"): CodeBlock =
        if (ns == null) CodeBlock.of("%S if ($nsVar == null || c.ignoreNamespace)", local)
        else CodeBlock.of("%S if ($nsVar == %S || c.ignoreNamespace)", local, ns)

    private fun emitLeafReadInline(cb: CodeBlock.Builder, f: FieldSpec, registry: NestedTypeRegistry) {
        when (f.coerce) {
            is Coerce.Nested -> {
                // ensureNested registers every nested data-class type before emit; lookup is total.
                val spec = registry.byFq.getValue(f.elemTypeFq)
                cb.add("val __n_${f.name}·=·${spec.helperName}(c)\n")
                if (f.isList) {
                    cb.add("__list_${f.name}.add(__n_${f.name})\n")
                } else {
                    cb.add("__nested_${f.name} = __n_${f.name}\n")
                    cb.add("__set_${f.name} = true\n")
                }
            }

            else -> {
                val needLoc = needsChildLoc(f)
                if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
                cb.add("val __t = c.childText(false)\n")
                if (f.isList) {
                    cb.add("__list_${f.name}.add(__t)\n")
                } else {
                    cb.add("__raw_${f.name} = __t\n")
                    if (needLoc) cb.add("__loc_${f.name} = __t_loc\n")
                    cb.add("__set_${f.name} = true\n")
                }
            }
        }
    }

    private fun emitPolyAssign(
        cb: CodeBlock.Builder,
        f: FieldSpec,
        subtypeFq: String,
        registry: NestedTypeRegistry,
    ) {
        // buildPolyChild calls ensureNested(sub) for every subtype, so the registry has every spec.
        val spec = registry.byFq.getValue(subtypeFq)
        cb.add("val __n_${f.name}·=·${spec.helperName}(c)\n")
        if (f.isList) {
            cb.add("__list_${f.name}.add(__n_${f.name})\n")
        } else {
            cb.add("__nested_${f.name} = __n_${f.name}\n")
            cb.add("__set_${f.name} = true\n")
        }
    }

    private fun emitPolyAttrSwitch(
        cb: CodeBlock.Builder,
        f: FieldSpec,
        d: PolyDispatch.Attr,
        registry: NestedTypeRegistry,
    ) {
        val nsLit: CodeBlock = if (d.attrNs == null) CodeBlock.of("null") else CodeBlock.of("%S", d.attrNs)
        cb.add("val __disc_${f.name}: %T = c.childAttr(%L, %S)\n", STRING_NULLABLE, nsLit, d.attrLocal)
        cb.beginControlFlow("when (__disc_${f.name})")
        for (v in d.variants) {
            cb.beginControlFlow("%S ->", v.value)
            emitPolyAssign(cb, f, v.subtypeFq, registry)
            cb.endControlFlow()
        }
        cb.add(ELSE_SKIP_CHILD)
        cb.endControlFlow()
    }

    private fun emitChildBody(cb: CodeBlock.Builder, node: TrieNode, registry: NestedTypeRegistry) {
        emitAttrEntries(cb, node)
        emitChildBodyContent(cb, node, registry)
    }

    private fun coerceField(f: FieldSpec, convVarFor: Map<String, String>): CodeBlock {
        val cb = CodeBlock.builder()
        cb.add("val __final_${f.name}: %T = ", f.typeName)

        if (f.coerce is Coerce.MapAggregate) {
            if (f.nullable) cb.add("if (!__set_${f.name}) null else __map_${f.name}\n")
            else cb.add("__map_${f.name}\n")
            return cb.build()
        }

        if (f.coerce is Coerce.Nested) {
            if (f.isList) {
                cb.add("__list_${f.name}\n")
            } else {
                val nameLit = CodeBlock.of("%S", f.name)
                val missingThrow = CodeBlock.of("throw %T(%L, __loc)", MISSING_EX, nameLit)
                if (f.nullable) {
                    cb.add("if (!__set_${f.name}) null else __nested_${f.name}\n")
                } else {
                    cb.add("if (!__set_${f.name}) %L else __nested_${f.name}!!\n", missingThrow)
                }
            }
            return cb.build()
        }

        if (f.isList) {
            cb.add("__list_${f.name}.map { __r -> ")
            cb.add(coerceRaw(f, CodeBlock.of("__r"), convVarFor))
            cb.add(" }\n")
            return cb.build()
        }

        val rawVar = CodeBlock.of("__raw_${f.name}")
        val nameLit = CodeBlock.of("%S", f.name)
        val missingThrow = CodeBlock.of("throw %T(%L, __loc)", MISSING_EX, nameLit)
        val orMissing = CodeBlock.of("(%L ?: %L)", rawVar, missingThrow)
        val orEmpty = CodeBlock.of("(%L ?: \"\")", rawVar)

        // Source.MapEntry filtered above via Coerce.MapAggregate; Source.PolyChild filtered via
        // Coerce.Nested. Remaining sources: Attr, Text, Child.
        when (f.source) {
            is Source.Attr -> {
                if (f.nullable) {
                    cb.add("if (%L == null) null else ", rawVar)
                    cb.add(coerceRaw(f, rawVar, convVarFor))
                } else {
                    cb.add(coerceRaw(f, orMissing, convVarFor))
                }
            }

            is Source.Text -> {
                // __raw_X is declared as non-null String at the recordText/subrecordText call,
                // and that call runs unconditionally for any record carrying an @XmlText field.
                // No __set_X gate needed: nullable @XmlText still binds whatever the cursor
                // produced (empty string for an empty body).
                cb.add(coerceRaw(f, rawVar, convVarFor))
            }

            is Source.Child -> {
                val effLoc =
                    if (needsChildLoc(f)) CodeBlock.of("(__loc_${f.name} ?: __loc)")
                    else CodeBlock.of("__loc")
                if (f.nullable) {
                    cb.add("if (!__set_${f.name}) null else ")
                    cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
                } else {
                    cb.add("if (!__set_${f.name}) %L else ", missingThrow)
                    cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
                }
            }

            else -> Unit
        }
        cb.add("\n")
        return cb.build()
    }

    private fun coerceRaw(
        f: FieldSpec,
        raw: CodeBlock,
        convVarFor: Map<String, String>,
        lcExpr: CodeBlock = CodeBlock.of("__loc"),
    ): CodeBlock {
        val nl = CodeBlock.of("%S", f.name)
        // Coerce.MapAggregate and Coerce.Nested are filtered upstream in coerceField; reaching
        // them here is impossible, so they're not enumerated in this when.
        return when (val coerce = f.coerce) {
            Coerce.AsString -> raw
            is Coerce.Scalar -> CodeBlock.of("%M(%L, %L, %L)", scalarMember(coerce.kind), nl, raw, lcExpr)
            is Coerce.Temporal -> {
                val m = when (coerce.kind) {
                    TemporalKind.LOCAL_DATE -> COERCE_LOCAL_DATE
                    TemporalKind.LOCAL_DATE_TIME -> COERCE_LOCAL_DATE_TIME
                    TemporalKind.INSTANT -> COERCE_INSTANT
                }
                CodeBlock.of("%M(%L, %L, %S, %L)", m, nl, raw, coerce.pattern, lcExpr)
            }

            is Coerce.Decimal -> CodeBlock.of("%M(%L, %L, %S, %L)", COERCE_BIG_DECIMAL, nl, raw, coerce.pattern, lcExpr)
            is Coerce.Custom -> {
                // registerConverter pre-populates convVarFor for every Coerce.Custom field; lookup
                // is total here.
                val varName = convVarFor.getValue(coerce.cls.canonicalName)
                CodeBlock.of("$varName.convert(%L, %L)", raw, lcExpr)
            }

            else -> error("unreachable: Coerce.MapAggregate / Coerce.Nested filtered before coerceRaw")
        }
    }

    private fun scalarMember(kind: ScalarKind): MemberName = when (kind) {
        ScalarKind.INT -> COERCE_INT
        ScalarKind.LONG -> COERCE_LONG
        ScalarKind.DOUBLE -> COERCE_DOUBLE
        ScalarKind.BOOLEAN -> COERCE_BOOLEAN
    }

    private fun annotationOf(cls: KSClassDeclaration, fqcn: String): KSAnnotation? =
        cls.annotations.firstOrNull { fq(it) == fqcn }

    private fun fq(a: KSAnnotation): String? =
        a.annotationType.resolve().declaration.qualifiedName?.asString()

    private fun stringArg(a: KSAnnotation, name: String): String? =
        a.arguments.firstOrNull { it.name?.asString() == name }?.value as? String

    enum class ScalarKind { INT, LONG, DOUBLE, BOOLEAN }
    enum class TemporalKind { LOCAL_DATE, LOCAL_DATE_TIME, INSTANT }
    enum class Ctx { RECORD, SUBRECORD }

    sealed class PathSeg {
        data class Element(
            val ns: String?,
            val name: String,
            val brackets: List<PathPredicate> = emptyList(),
        ) : PathSeg()
        data class AttrLeaf(val ns: String?, val name: String) : PathSeg()
    }

    sealed class Source {
        data class Attr(val ns: String?, val name: String) : Source()
        data class Text(val preserveWhitespace: Boolean) : Source()
        data class Child(val segments: List<PathSeg>, val descendant: Boolean) : Source()
        data class MapEntry(val entryNs: String?, val entryLocal: String) : Source()
        data class PolyChild(val dispatch: PolyDispatch) : Source()
    }

    sealed class PolyDispatch {
        data class Tag(val variants: List<TagVariant>) : PolyDispatch()
        data class Attr(
            val wrapNs: String?,
            val wrapLocal: String,
            val attrNs: String?,
            val attrLocal: String,
            val variants: List<AttrVariant>,
        ) : PolyDispatch()
    }

    data class TagVariant(val ns: String?, val local: String, val subtypeFq: String)
    data class AttrVariant(val value: String, val subtypeFq: String)

    sealed class Coerce {
        data object AsString : Coerce()
        data class Scalar(val kind: ScalarKind) : Coerce()
        data class Temporal(val kind: TemporalKind, val pattern: String) : Coerce()
        data class Decimal(val pattern: String) : Coerce()
        data class Custom(val cls: ClassName, val fq: String) : Coerce()
        data class Nested(val typeFq: String) : Coerce()
        data object MapAggregate : Coerce()
    }

    data class FieldSpec(
        val name: String,
        val typeName: TypeName,
        val nullable: Boolean,
        val isList: Boolean,
        val elemNullable: Boolean,
        val elemTypeName: TypeName,
        val elemTypeFq: String,
        val source: Source,
        val coerce: Coerce,
        val mapKeyField: FieldSpec? = null,
        val mapValueField: FieldSpec? = null,
    )

    data class QKey(val ns: String?, val local: String)

    data class EdgeKey(val qkey: QKey, val brackets: List<PathPredicate>)

    class TrieNode {
        val children: MutableMap<EdgeKey, TrieNode> = LinkedHashMap()
        val attrEntries: MutableList<Triple<String?, String, FieldSpec>> = mutableListOf()
        val textEntries: MutableList<FieldSpec> = mutableListOf()
        val nestedEntries: MutableList<FieldSpec> = mutableListOf()
    }

    data class NestedTypeSpec(
        val cls: KSClassDeclaration,
        val typeName: ClassName,
        val helperName: String,
        val nsMap: Map<String, String>,
        val fields: List<FieldSpec>,
    )

    class NestedTypeRegistry {
        val byFq: MutableMap<String, NestedTypeSpec> = LinkedHashMap()
        val inProgress: MutableSet<String> = LinkedHashSet()
    }

    private companion object {
        const val XML_RECORD_FQ = "xmlfluss.XmlRecord"
        const val XML_NS_FQ = "xmlfluss.XmlNs"
        const val XML_ATTR_FQ = "xmlfluss.XmlAttr"
        const val XML_CHILD_FQ = "xmlfluss.XmlChild"
        const val XML_TEXT_FQ = "xmlfluss.XmlText"
        const val XML_FORMAT_FQ = "xmlfluss.XmlFormat"
        const val XML_CONVERTER_FQ = "xmlfluss.XmlConverter"
        const val XML_MAP_FQ = "xmlfluss.XmlMap"
        const val XML_POLYMORPHIC_FQ = "xmlfluss.XmlPolymorphic"
        const val XML_SUBTYPE_FQ = "xmlfluss.XmlSubtype"

        val SCALAR_TEMPORAL_FQS: Set<String> = setOf(
            KOTLIN_STRING, KOTLIN_INT, KOTLIN_LONG, KOTLIN_DOUBLE, KOTLIN_BOOLEAN,
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.Instant",
            "java.math.BigDecimal",
        )

        val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
        val FLOW_BUILDER = MemberName("kotlinx.coroutines.flow", "flow")
        val INPUT_STREAM = ClassName("java.io", "InputStream")
        val XML_READ_CURSOR = ClassName(XMLFLUSS_RUNTIME, "XmlReadCursor")
        val COMPILED_PATH = ClassName("xmlfluss.path", "CompiledPath")
        val PATHS_COMPILE = MemberName(ClassName(XMLFLUSS_RUNTIME, "Paths"), "compile")
        val MISSING_EX = ClassName("xmlfluss", "XmlParseException", "Missing")
        val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
        val LINKED_MAP_OF = MemberName("kotlin.collections", "linkedMapOf")
        val LOCATION = ClassName("xmlfluss", "Location")
        val LOCATION_NULLABLE = LOCATION.copy(nullable = true)

        val COERCE_INT = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toInt")
        val COERCE_LONG = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toLong")
        val COERCE_DOUBLE = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toDouble")
        val COERCE_BOOLEAN = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toBoolean")
        val COERCE_LOCAL_DATE = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toLocalDate")
        val COERCE_LOCAL_DATE_TIME = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toLocalDateTime")
        val COERCE_INSTANT = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toInstant")
        val COERCE_BIG_DECIMAL = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toBigDecimal")
        val STRING_NULLABLE = STRING.copy(nullable = true)
    }
}
