package xmlfluss.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo
import xmlfluss.codegen.plan.*
import java.time.LocalDate
import javax.annotation.processing.Generated
import xmlfluss.codegen.model.Coerce as CoreCoerce
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.NestedRegistry as CoreNestedRegistry
import xmlfluss.codegen.model.PolyDispatch as CorePolyDispatch
import xmlfluss.codegen.model.QKey as CoreQKey
import xmlfluss.codegen.model.RecordSpec as CoreRecordSpec
import xmlfluss.codegen.model.ScalarKind as CoreScalarKind
import xmlfluss.codegen.model.Source as CoreSource
import xmlfluss.codegen.model.TypeRef as CoreTypeRef
import xmlfluss.path.Predicate as PathPredicate

private const val XMLFLUSS_RUNTIME = "xmlfluss.runtime"
private const val SKIP_CHILD = "c.skipChild()\n"
private const val ELSE_SKIP_CHILD = "else -> $SKIP_CHILD"

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

    private inline fun vRequire(cond: Boolean, msg: () -> String) {
        if (!cond) throw ValidationError(msg())
    }

    /**
     * Local enum tracking record vs subrecord context so the emitter can pick the right
     * cursor accessor name (`recordAttr` vs `childAttr`, etc.). No neutral equivalent —
     * this is a pure emit-side switch.
     */
    private enum class Ctx { RECORD, SUBRECORD }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(XML_RECORD_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val symbolProvider = xmlfluss.ksp.spi.KspSymbolProvider(resolver, logger)
        for (cls in symbols) {
            try {
                // Drive the field model through the shared codegen-core classifier and walk
                // the neutral RecordSpec directly — TypeRefs handles JavaPoet/KotlinPoet
                // conversions on demand at emit sites.
                generate(cls, symbolProvider)
            } catch (e: ProcessorValidationException) {
                logger.error("xml-fluss-ksp: ${e.message}", cls)
            } catch (e: Throwable) {
                logger.error("xml-fluss-ksp: ${e.stackTraceToString()}", cls)
            }
        }
        return emptyList()
    }

    private fun generate(cls: KSClassDeclaration, symbolProvider: xmlfluss.ksp.spi.KspSymbolProvider) {
        // The SPI's KspSymbolProvider.lookupRecord() owns the kind check (data class OR sealed
        // parent for @XmlPolymorphic); a non-matching kind returns null after emitting the
        // diagnostic. Widen the local guard accordingly so sealed-root @XmlRecord cases don't
        // get rejected here before reaching CoreClassifier.
        if (Modifier.DATA !in cls.modifiers && Modifier.SEALED !in cls.modifiers) {
            logger.error("@XmlRecord requires data class", cls)
            return
        }
        val fqn = cls.qualifiedName?.asString() ?: return
        val symbol = symbolProvider.lookupRecord(fqn) ?: return
        val core = xmlfluss.codegen.classify.CoreClassifier(symbolProvider)
        val coreSpec = core.classify(symbol) ?: return
        val plan = DispatchPlanBuilder.build(coreSpec, core.registry())
        emitFile(coreSpec, plan, core.registry())
    }

    private fun emitFile(
        coreSpec: CoreRecordSpec,
        plan: DispatchPlan,
        registry: CoreNestedRegistry,
    ) {
        val origin = coreSpec.originatingHandle() as? KSClassDeclaration
            ?: error(
                "KSP originatingHandle must be a KSClassDeclaration for ${coreSpec.packageName()}.${coreSpec.simpleName()}, got " +
                    (coreSpec.originatingHandle()?.javaClass?.name ?: "null"),
            )
        val pkg = coreSpec.packageName()
        val recordTypeName = TypeRefs.toClassName(CoreTypeRef.of(pkg, coreSpec.simpleName()))
        val parserName = "${coreSpec.simpleName()}Parser"

        val flowOfRecord = FLOW.parameterizedBy(recordTypeName)

        val nsProp = PropertySpec.builder("NS", MAP.parameterizedBy(STRING, STRING))
            .addModifiers(KModifier.PRIVATE)
            .initializer(buildNsInitializer(coreSpec.nsMap()))
            .build()

        val pathProp = PropertySpec.builder("PATH", COMPILED_PATH)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%M(%S, NS)", PATHS_COMPILE, coreSpec.recordPath())
            .build()

        val convVarFor = mutableMapOf<String, String>()
        val convProps = mutableListOf<PropertySpec>()
        fun registerConverter(c: CoreCoerce.Custom) {
            val cls = TypeRefs.toClassName(c.converterClass())
            val key = cls.canonicalName
            if (key in convVarFor) return
            val varName = "__conv_${convVarFor.size}"
            convVarFor[key] = varName
            convProps += PropertySpec.builder(varName, cls)
                .addModifiers(KModifier.PRIVATE)
                .initializer("%T()", cls)
                .build()
        }
        for (f in coreSpec.fields()) (f.coerce() as? CoreCoerce.Custom)?.let(::registerConverter)
        for (n in registry.byFq().values) for (f in n.fields()) (f.coerce() as? CoreCoerce.Custom)?.let(::registerConverter)

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
            .addCode(buildParseBody(recordTypeName, coreSpec.fields(), convVarFor, registry, plan))
            .build()

        val nestedHelpers = registry.byFq().entries.map { (fq, spec) ->
            val nestedPlan = plan.byFq()[fq]
                ?: error("DispatchPlan missing for nested record $fq")
            val nestedType = TypeRefs.toClassName(
                CoreTypeRef.of(spec.packageName(), spec.simpleName())
            )
            FunSpec.builder(registry.helperName(fq))
                .addModifiers(KModifier.PRIVATE)
                .addParameter("c", XML_READ_CURSOR)
                .returns(nestedType)
                .addCode(buildSubrecordBody(nestedType, spec, convVarFor, registry, nestedPlan))
                .build()
        }

        val parserBuilder = TypeSpec.objectBuilder(parserName)
            .addProperty(nsProp)
            .addProperty(pathProp)
        for (p in convProps) parserBuilder.addProperty(p)
        for (h in nestedHelpers) parserBuilder.addFunction(h)
        parserBuilder.addFunction(parseFun)
        parserBuilder.addOriginatingKSFile(origin.containingFile!!)

        val file = FileSpec.builder(pkg, parserName)
            .addAnnotation(
                AnnotationSpec.builder(Generated::class)
                    .addMember("%S", "xml-fluss-ksp")
                    .addMember("date = %S", LocalDate.now().toString())
                    .build()
            )
            .addAnnotation(
                // Surviving suppressions:
                //  - RedundantExplicitType: PropertySpec always emits a type for object members
                //    (NS, PATH, __conv_<N>); we can't drop it without a different KotlinPoet API.
                //  - RedundantVisibilityModifier: KotlinPoet emits `public` on top-level objects
                //    and member functions by default; the only alternative is to change visibility.
                //  - LocalVariableName: locals are prefixed with `__` to guarantee no collision
                //    with user-declared record fields (e.g. a record field named `t` would shadow
                //    the synthetic `__t`). Renaming would require name-mangling logic.
                AnnotationSpec.builder(Suppress::class)
                    .addMember("\"RedundantExplicitType\", \"RedundantVisibilityModifier\", \"LocalVariableName\"")
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
        fields: List<CoreFieldSpec>,
        convVarFor: Map<String, String>,
        registry: CoreNestedRegistry,
        plan: DispatchPlan,
    ): CodeBlock {
        val cb = CodeBlock.builder()
        cb.beginControlFlow("return·%M", FLOW_BUILDER)
        cb.beginControlFlow("%T(input,·PATH,·ignoreNamespace).use·{ c ->\n", XML_READ_CURSOR)
        cb.beginControlFlow("while (c.findNextRecord())")
        emitInstanceBody(cb, recordType, fields, convVarFor, registry, plan, ctx = Ctx.RECORD)
        cb.endControlFlow()
        cb.endControlFlow()
        cb.endControlFlow()
        return cb.build()
    }

    private fun buildSubrecordBody(
        type: ClassName,
        spec: CoreRecordSpec,
        convVarFor: Map<String, String>,
        registry: CoreNestedRegistry,
        plan: DispatchPlan,
    ): CodeBlock {
        val cb = CodeBlock.builder()
        emitInstanceBody(cb, type, spec.fields(), convVarFor, registry, plan, ctx = Ctx.SUBRECORD)
        return cb.build()
    }

    private fun emitInstanceBody(
        cb: CodeBlock.Builder,
        type: ClassName,
        fields: List<CoreFieldSpec>,
        convVarFor: Map<String, String>,
        registry: CoreNestedRegistry,
        plan: DispatchPlan,
        ctx: Ctx,
    ) {
        val attrFields = fields.filter { it.source() is CoreSource.Attr }
        val textField = fields.firstOrNull { it.source() is CoreSource.Text }
        val childFields = fields.filter { it.source() is CoreSource.Child }
        val mapFields = fields.filter { it.source() is CoreSource.MapEntry }
        val polyFields = fields.filter { it.source() is CoreSource.PolyChild }

        val attrFn = if (ctx == Ctx.RECORD) "recordAttr" else "childAttr"
        val forEachFn = if (ctx == Ctx.RECORD) "forEachRecordChild" else "forEachSubrecordChild"
        val textFn = if (ctx == Ctx.RECORD) "recordText" else "subrecordText"
        val locFn = if (ctx == Ctx.RECORD) "recordLocation" else "childLocation"

        cb.add("val __loc: %T = c.${locFn}()\n", LOCATION)

        for (f in attrFields) {
            val src = f.source() as CoreSource.Attr
            val nsLit: CodeBlock = if (src.ns() == null) CodeBlock.of("null") else CodeBlock.of("%S", src.ns())
            cb.add("val __raw_${f.name()}: %T = c.${attrFn}(%L, %S)\n", STRING_NULLABLE, nsLit, src.name())
        }

        for (f in childFields + polyFields + listOfNotNull(textField)) emitFieldStateInit(cb, f)
        for (mf in mapFields) emitMapStateInit(cb, mf)

        // Invariant: textField != null  ⇒  needTraverse == true (textField is one of the
        // disjuncts below). coerceField below reads `__raw_${textField.name}` unconditionally,
        // so the declaration emitted inside this block is always reached when textField != null.
        val needTraverse = childFields.isNotEmpty() || textField != null || mapFields.isNotEmpty() || polyFields.isNotEmpty()
        if (needTraverse) {
            // Counter slots must be declared OUTSIDE the per-sibling lambda so that ++__cnt[0]
            // accumulates across siblings rather than resetting per iteration.
            declareSlotsCode(cb, plan.slots())
            cb.beginControlFlow("c.${forEachFn}·{ ln, ns ->\n")
            emitTopLevelChildSwitch(cb, plan, registry, convVarFor)
            cb.endControlFlow()
            if (textField != null) {
                val preserve = (textField.source() as CoreSource.Text).preserveWhitespace()
                cb.add("val __raw_${textField.name()}: %T = c.${textFn}($preserve)\n", STRING)
            }
        }

        for (f in fields) cb.add(coerceField(f, convVarFor))

        val emitVerb = if (ctx == Ctx.RECORD) "emit" else "return"
        cb.add("$emitVerb(%T(\n", type)
        cb.indent()
        for (f in fields) cb.add("${f.name()} = __final_${f.name()},\n")
        cb.unindent()
        cb.add("))\n")
    }

    /**
     * KSP-side "is this field nullable in Kotlin source"? Lists are always non-null on
     * the Kotlin side (the runtime contract is "no matches → empty list"); CoreFieldSpec
     * stores `required = !nullable && !isList`, so a List field reports `required = false`
     * even though Lists never carry the `?` marker. Treat list fields as non-nullable to
     * keep the emitted KotlinPoet type stable; only scalar/nested fields honour the
     * required flag for their `?` marker.
     */
    private fun fieldNullable(f: CoreFieldSpec): Boolean = !f.required() && !f.isList

    private fun fieldTypeName(f: CoreFieldSpec): TypeName {
        val base = TypeRefs.toTypeName(f.fieldType())
        return if (fieldNullable(f)) base.copy(nullable = true) else base
    }

    private fun elemTypeName(f: CoreFieldSpec): TypeName = TypeRefs.toTypeName(f.elemType())

    private fun emitFieldStateInit(cb: CodeBlock.Builder, f: CoreFieldSpec) {
        // Source.Text is declared and assigned in one go at the recordText/subrecordText call
        // site (see emitParseBody). No state needed up front — the cursor always provides a
        // String, so the early `var __raw_X: String? = null` would just be a dead initializer.
        if (f.source() is CoreSource.Text) return
        when {
            f.coerce() is CoreCoerce.Nested && f.isList -> {
                cb.add(
                    "val __list_${f.name()}: %T = mutableListOf()\n",
                    MUTABLE_LIST.parameterizedBy(elemTypeName(f))
                )
            }

            f.coerce() is CoreCoerce.Nested -> {
                cb.add("var __set_${f.name()}: %T = false\n", BOOLEAN)
                cb.add(
                    "var __nested_${f.name()}: %T = null\n",
                    elemTypeName(f).copy(nullable = true)
                )
            }

            f.isList -> {
                cb.add(
                    "val __list_${f.name()}: %T = mutableListOf()\n",
                    MUTABLE_LIST.parameterizedBy(STRING)
                )
            }

            else -> {
                cb.add("var __set_${f.name()}: %T = false\n", BOOLEAN)
                cb.add("var __raw_${f.name()}: %T = null\n", STRING_NULLABLE)
                if (needsChildLoc(f)) {
                    cb.add("var __loc_${f.name()}: %T = null\n", LOCATION_NULLABLE)
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
    private fun needsChildLoc(f: CoreFieldSpec): Boolean =
        f.source() is CoreSource.Child && !f.isList &&
            f.coerce() !is CoreCoerce.AsString && f.coerce() !is CoreCoerce.Nested

    private fun emitMapStateInit(cb: CodeBlock.Builder, f: CoreFieldSpec) {
        // emitMapStateInit only runs for Coerce.MapAggregate fields built by classifyMapParam,
        // which always sets mapKeyField / mapValueField.
        val keyF = requireNotNull(f.mapKeyField()) {
            "MapAggregate field ${f.name()} missing synthetic key spec — bug in CoreClassifier"
        }
        val valF = requireNotNull(f.mapValueField()) {
            "MapAggregate field ${f.name()} missing synthetic value spec — bug in CoreClassifier"
        }
        val storedValueType: TypeName =
            if (valF.isList) MUTABLE_LIST.parameterizedBy(elemTypeName(valF)) else fieldTypeName(valF)
        val storeType = MUTABLE_MAP.parameterizedBy(fieldTypeName(keyF), storedValueType)
        cb.add("val __map_${f.name()}: %T = %M()\n", storeType, LINKED_MAP_OF)
        if (fieldNullable(f)) cb.add("var __set_${f.name()}: %T = false\n", BOOLEAN)
    }

    private fun emitMapEntryCase(
        cb: CodeBlock.Builder,
        f: CoreFieldSpec,
        mp: MapPlan,
        registry: CoreNestedRegistry,
        convVarFor: Map<String, String>,
    ) {
        // Same invariant as emitMapStateInit: MapAggregate fields always carry both synthetic
        // key/value FieldSpecs.
        val keyF = requireNotNull(f.mapKeyField()) {
            "MapAggregate field ${f.name()} missing synthetic key spec — bug in CoreClassifier"
        }
        val valF = requireNotNull(f.mapValueField()) {
            "MapAggregate field ${f.name()} missing synthetic value spec — bug in CoreClassifier"
        }
        val synthetic = listOf(keyF, valF)

        for (sf in synthetic) {
            val src = sf.source()
            if (src is CoreSource.Attr) {
                val nsLit: CodeBlock = if (src.ns() == null) CodeBlock.of("null") else CodeBlock.of("%S", src.ns())
                if (sf.isList) {
                    cb.add(
                        "val __list_${sf.name()}: %T = mutableListOf()\n",
                        MUTABLE_LIST.parameterizedBy(STRING)
                    )
                    cb.add("c.childAttr(%L, %S)?.let { __list_${sf.name()}.add(it) }\n", nsLit, src.name())
                } else {
                    cb.add(
                        "val __raw_${sf.name()}: %T = c.childAttr(%L, %S)\n",
                        STRING_NULLABLE, nsLit, src.name()
                    )
                }
            } else {
                emitFieldStateInit(cb, sf)
            }
        }

        if (mp.directRoot().children().isNotEmpty() || mp.descendantByHead().isNotEmpty()) {
            declareSlotsCode(cb, mp.slots())
            cb.beginControlFlow("c.forEachSubrecordChild·{ ln, ns ->\n")
            emitMapEntrySwitch(cb, mp, registry, convVarFor)
            cb.endControlFlow()
        }

        cb.add(coerceField(keyF, convVarFor))
        cb.add(coerceField(valF, convVarFor))

        if (valF.isList) {
            cb.add(
                "__map_${f.name()}.getOrPut(__final_${keyF.name()}) { mutableListOf() }.addAll(__final_${valF.name()})\n"
            )
        } else {
            cb.add("__map_${f.name()}[__final_${keyF.name()}] = __final_${valF.name()}\n")
        }
        if (fieldNullable(f)) cb.add("__set_${f.name()} = true\n")
    }

    /**
     * Emits the inside-lambda body of a `forEachChild { ln, ns -> ... }` switch over the direct
     * children of [node]. Counter slot declarations live OUTSIDE the lambda — callers obtain the
     * pre-built [SlotTable] from the plan and pass it in via [slots] so the IntArray persists
     * across sibling iterations.
     */
    private fun emitChildrenSwitch(
        cb: CodeBlock.Builder,
        node: TrieNode,
        slots: SlotTable,
        registry: CoreNestedRegistry,
    ) {
        if (node.children().isEmpty()) {
            cb.add(SKIP_CHILD)
            return
        }
        cb.beginControlFlow("when(ln)")
        emitGroupedChildArms(cb, node.groupChildrenByQKey(), slots, registry)
        cb.add(ELSE_SKIP_CHILD)
        cb.endControlFlow()
    }

    /** Emit `local -> { … }` arms over a [TrieNode.groupChildrenByQKey] map inside an open `when(ln)` block. */
    private fun emitGroupedChildArms(
        cb: CodeBlock.Builder,
        grouped: Map<CoreQKey, List<Map.Entry<List<PathPredicate>, TrieNode>>>,
        slots: SlotTable,
        registry: CoreNestedRegistry,
    ) {
        for ((qk, branches) in grouped) {
            val cond = qnameCond(qk.ns(), qk.local())
            cb.beginControlFlow("%L ->", cond)
            emitPredicateBranches(cb, qk, branches, slots, registry)
            cb.endControlFlow()
        }
    }

    private fun emitPredicateBranches(
        cb: CodeBlock.Builder,
        qkey: CoreQKey,
        branches: List<Map.Entry<List<PathPredicate>, TrieNode>>,
        slots: SlotTable,
        registry: CoreNestedRegistry,
    ) {
        // Per-element pre-compute: increment any counters that key on this qname BEFORE the
        // two-pass dispatch. Each element produces exactly one increment per slot, regardless of
        // how many attr-only / body branches reference that slot.
        for (slotEntry in slots) {
            val key = slotEntry.key
            if (key.qkey() != qkey) continue
            val name = slotEntry.value
            if (key.prefix().isEmpty()) {
                // No prefix guard — unconditionally increment.
                cb.add("val __pos_%L: %T = ++__cnt_%L[0]\n", name, INT, name)
            } else {
                val folded = key.prefix().reduce { a, b -> PathPredicate.And(a, b) }
                val preExpr: CodeBlock = plainPredicateExpr(folded)
                cb.add("val __pre_%L: %T = %L\n", name, BOOLEAN, preExpr)
                cb.add("val __pos_%L: %T = if (__pre_%L) ++__cnt_%L[0] else 0\n", name, INT, name, name)
            }
        }

        if (branches.size == 1 && branches[0].key.isEmpty()) {
            emitChildBody(cb, branches[0].value, registry)
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
        for (entry in branches) {
            val child = entry.value
            if (child.attrEntries().isEmpty()) continue
            val expr = predicateExpr(entry.key, qkey, slots)
            cb.beginControlFlow("if (%L)", expr)
            emitAttrEntries(cb, child)
            cb.endControlFlow()
        }
        val bodyBranches = branches.filter { it.value.hasBodyContent() }
        if (bodyBranches.isEmpty()) {
            cb.add(SKIP_CHILD)
            return
        }
        cb.beginControlFlow("when")
        for (entry in bodyBranches) {
            val expr = predicateExpr(entry.key, qkey, slots)
            cb.beginControlFlow("%L ->", expr)
            emitChildBodyContent(cb, entry.value, registry)
            cb.endControlFlow()
        }
        cb.add("else -> $SKIP_CHILD")
        cb.endControlFlow()
    }

    private fun emitAttrEntries(cb: CodeBlock.Builder, node: TrieNode) {
        for (ae: AttrEntry in node.attrEntries()) {
            val f = ae.field()
            val nsLit: CodeBlock = if (ae.ns() == null) CodeBlock.of("null") else CodeBlock.of("%S", ae.ns())
            if (f.isList) {
                cb.add("c.childAttr(%L, %S)?.let { __list_${f.name()}.add(it) }\n", nsLit, ae.name())
            } else if (needsChildLoc(f)) {
                cb.add(
                    "c.childAttr(%L, %S)?.let { __raw_${f.name()} = it; __loc_${f.name()} = c.childLocation(); __set_${f.name()} = true }\n",
                    nsLit, ae.name()
                )
            } else {
                cb.add(
                    "c.childAttr(%L, %S)?.let { __raw_${f.name()} = it; __set_${f.name()} = true }\n",
                    nsLit, ae.name()
                )
            }
        }
    }

    private fun emitChildBodyContent(
        cb: CodeBlock.Builder,
        node: TrieNode,
        registry: CoreNestedRegistry,
    ) {
        val hasText = node.textEntries().isNotEmpty()
        val hasNested = node.nestedEntries().isNotEmpty()
        val hasDescend = node.children().isNotEmpty()
        when {
            hasNested -> {
                for (f in node.nestedEntries()) {
                    emitNestedHelperAssign(cb, f, f.elemTypeFq(), registry)
                }
            }
            hasText -> {
                val textFields = node.textEntries()
                val needLoc = textFields.any { needsChildLoc(it) }
                if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
                cb.add("val __t = c.childText(false)\n")
                for (f in textFields) {
                    if (f.isList) cb.add("__list_${f.name()}.add(__t)\n")
                    else {
                        cb.add("__raw_${f.name()} = __t\n")
                        if (needsChildLoc(f)) cb.add("__loc_${f.name()} = __t_loc\n")
                        cb.add("__set_${f.name()} = true\n")
                    }
                }
            }
            hasDescend -> {
                // Counter slots for direct edges under `node` must outlive the per-sibling lambda
                // — declare them here, BEFORE entering forEachChild, so increments accumulate
                // across siblings of the same parent.
                val slots = node.allocateSlots()
                declareSlotsCode(cb, slots)
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
        qkey: CoreQKey,
        slots: SlotTable,
    ): CodeBlock {
        if (brackets.isEmpty()) return CodeBlock.of("true")
        if (!PredicateAnalysis.bracketsHaveIndex(brackets)) {
            val folded = brackets.reduce { a, b -> PathPredicate.And(a, b) }
            return plainPredicateExpr(folded)
        }
        val firstIdxIndex = brackets.indexOfFirst { PredicateAnalysis.containsIndex(it) }
        // unreachable: bracketsHaveIndex returned true, so at least one bracket contains Index.
        require(firstIdxIndex >= 0) { "predicateExpr called with no Index in brackets — bug in bracketsHaveIndex" }
        val prefix = brackets.subList(0, firstIdxIndex)
        val firstIdxBracket = brackets[firstIdxIndex]
        val suffix = brackets.subList(firstIdxIndex + 1, brackets.size)
        val n = PredicateAnalysis.firstIndexValue(firstIdxBracket)
        val slotKey = PrefixKey(qkey, PredicateAnalysis.prefixOfFirstIndex(brackets))
        // SlotTable.get returns null when the key is absent. DispatchPlanBuilder allocates every
        // (qkey, prefix) pair we encounter, so a null here signals a plan-builder bug.
        val slotName: String = slots.get(slotKey)
        val parts = mutableListOf<CodeBlock>()
        // When the prefix is empty there is no __pre_ variable — the counter is always incremented.
        if (prefix.isNotEmpty()) parts += CodeBlock.of("__pre_%L", slotName)
        parts += CodeBlock.of("(__pos_%L == %L)", slotName, n)
        // If the first-index bracket also contains non-Index predicates (e.g. `[2 and @x='y']`),
        // emit those alongside the position check.
        val residual = PredicateAnalysis.stripIndex(firstIdxBracket)
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
        // unreachable: caller routes Index-bearing brackets through predicateExpr; PredicateAnalysis.stripIndex removes Index nodes from And residuals before recursion.
        is PathPredicate.Index -> error("plainPredicateExpr called on Index — counter logic should have stripped this")
    }

    /** Emits `val __cnt_<slot>: IntArray = intArrayOf(0)` for each precomputed slot. */
    private fun declareSlotsCode(cb: CodeBlock.Builder, slots: SlotTable) {
        for ((_, name) in slots) {
            cb.add("val __cnt_%L: %T = intArrayOf(0)\n", name, INT_ARRAY)
        }
    }

    /**
     * Top-level child dispatch driven entirely by a pre-built [DispatchPlan]. The emitter does
     * not insert into tries, group by QKey, or allocate slots — every routing decision was
     * resolved by [DispatchPlanBuilder].
     */
    private fun emitTopLevelChildSwitch(
        cb: CodeBlock.Builder,
        plan: DispatchPlan,
        registry: CoreNestedRegistry,
        convVarFor: Map<String, String>,
    ) {
        emitChildSwitchCore(
            cb,
            plan.directRoot(),
            plan.slots(),
            plan.descendantByHead(),
            plan.tailTries(),
            plan.mapPlans(),
            plan.polyFields(),
            registry, convVarFor,
        )
    }

    /**
     * Core child-dispatch switch. Used both by record/nested-record bodies (via the full
     * [DispatchPlan]) and by `@XmlMap` entries (which only carry direct-child + descendant data —
     * no map-of-map or polymorphic dispatch).
     */
    private fun emitChildSwitchCore(
        cb: CodeBlock.Builder,
        directRoot: TrieNode,
        slots: SlotTable,
        byHead: Map<CoreQKey, List<DescendantBranch>>,
        tailTries: Map<CoreQKey, TailTrie>,
        mapPlans: Map<CoreFieldSpec, MapPlan>,
        polyFields: List<CoreFieldSpec>,
        registry: CoreNestedRegistry,
        convVarFor: Map<String, String>,
    ) {
        if (directRoot.children().isEmpty() && byHead.isEmpty() && mapPlans.isEmpty() && polyFields.isEmpty()) {
            cb.add(SKIP_CHILD)
            return
        }
        cb.beginControlFlow("when(ln)")
        emitGroupedChildArms(cb, directRoot.groupChildrenByQKey(), slots, registry)
        for ((mf, mp) in mapPlans) {
            val src = mf.source() as CoreSource.MapEntry
            val cond = qnameCond(src.entryNs(), src.entryLocal())
            cb.beginControlFlow("%L ->", cond)
            emitMapEntryCase(cb, mf, mp, registry, convVarFor)
            cb.endControlFlow()
        }
        for (pf in polyFields) {
            when (val d = (pf.source() as CoreSource.PolyChild).dispatch()) {
                is CorePolyDispatch.Tag -> {
                    for (v in d.variants()) {
                        val cond = qnameCond(v.ns(), v.local())
                        cb.beginControlFlow("%L ->", cond)
                        emitPolyAssign(cb, pf, v.subtypeFq(), registry)
                        cb.endControlFlow()
                    }
                }
                is CorePolyDispatch.Attr -> {
                    val cond = qnameCond(d.wrapNs(), d.wrapLocal())
                    cb.beginControlFlow("%L ->", cond)
                    emitPolyAttrSwitch(cb, pf, d, registry)
                    cb.endControlFlow()
                }
            }
        }
        for ((head, branches) in byHead) {
            val cond = qnameCond(head.ns(), head.local())
            cb.beginControlFlow("%L ->", cond)
            emitDescendantArm(cb, head, branches, tailTries, registry, terminating = false)
            cb.endControlFlow()
        }
        if (byHead.isEmpty()) {
            cb.add(ELSE_SKIP_CHILD)
        } else {
            cb.beginControlFlow("else ->")
            cb.beginControlFlow("c.forEachDescendantInChild·{ dln, dns ->\n")
            cb.beginControlFlow("when(dln)")
            for ((head, branches) in byHead) {
                val cond = qnameCond(head.ns(), head.local(), nsVar = "dns")
                cb.beginControlFlow("%L ->", cond)
                emitDescendantArm(cb, head, branches, tailTries, registry, terminating = true)
                cb.endControlFlow()
            }
            cb.add("else -> false\n")
            cb.endControlFlow()
            cb.endControlFlow()
            cb.endControlFlow()
        }
        cb.endControlFlow()
    }

    /**
     * Map-entry child dispatch: pure plan walker over the pre-built [MapPlan]. MapPlan has no
     * map-of-map or polymorphic children.
     */
    private fun emitMapEntrySwitch(
        cb: CodeBlock.Builder,
        mp: MapPlan,
        registry: CoreNestedRegistry,
        convVarFor: Map<String, String>,
    ) {
        emitChildSwitchCore(
            cb,
            mp.directRoot(),
            mp.slots(),
            mp.descendantByHead(),
            mp.tailTries(),
            emptyMap(),
            emptyList(),
            registry, convVarFor,
        )
    }

    private fun emitDescendantArm(
        cb: CodeBlock.Builder,
        head: CoreQKey,
        branches: List<DescendantBranch>,
        tailTries: Map<CoreQKey, TailTrie>,
        registry: CoreNestedRegistry,
        terminating: Boolean,
    ) {
        // The descendant-axis head segment cannot carry a positional predicate (rejected by
        // validateChildPredicate), so brackets here are guaranteed Index-free. We can still have
        // attribute-equality predicates that select among descendant heads.
        val unguarded = branches.filter { it.brackets().isEmpty() }.map { it.field() }
        val guarded = branches.filter { it.brackets().isNotEmpty() }
        val tail = tailTries[head]
        if (guarded.isEmpty()) {
            emitDescendantArmBody(cb, head, unguarded, tail, registry)
            if (terminating) cb.add("true\n")
            return
        }
        cb.beginControlFlow("when")
        for (b in guarded) {
            cb.beginControlFlow("%L ->", predicateExpr(b.brackets(), head, SlotTable()))
            emitDescendantArmBody(cb, head, listOf(b.field()), tail, registry)
            if (terminating) cb.add("true\n")
            cb.endControlFlow()
        }
        if (unguarded.isNotEmpty()) {
            cb.beginControlFlow("else ->")
            emitDescendantArmBody(cb, head, unguarded, tail, registry)
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
        head: CoreQKey,
        headFields: List<CoreFieldSpec>,
        tail: TailTrie?,
        registry: CoreNestedRegistry,
    ) {
        val anyEmpty = headFields.any {
            (it.source() as CoreSource.Child).segments().size == 1
        }
        val anyNonEmpty = headFields.any {
            (it.source() as CoreSource.Child).segments().size > 1
        }
        vRequire(!(anyEmpty && anyNonEmpty)) {
            "cannot mix '//${head.local()}' with '//${head.local()}/...' on the same head element"
        }
        if (anyEmpty) {
            vRequire(headFields.size == 1) {
                "multiple descendant fields targeting '//${head.local()}' (single-segment); at most one allowed"
            }
            emitLeafReadInline(cb, headFields[0], registry)
        } else {
            val tailTrie = tail
                ?: error("missing tail trie for descendant head $head — bug in DispatchPlanBuilder")
            emitChildBody(cb, tailTrie.trie(), registry)
        }
    }

    private fun qnameCond(ns: String?, local: String, nsVar: String = "ns"): CodeBlock =
        if (ns == null) CodeBlock.of("%S if ($nsVar == null || c.ignoreNamespace)", local)
        else CodeBlock.of("%S if ($nsVar == %S || c.ignoreNamespace)", local, ns)

    private fun emitLeafReadInline(cb: CodeBlock.Builder, f: CoreFieldSpec, registry: CoreNestedRegistry) {
        when (f.coerce()) {
            is CoreCoerce.Nested -> emitNestedHelperAssign(cb, f, f.elemTypeFq(), registry)

            else -> {
                val needLoc = needsChildLoc(f)
                if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
                cb.add("val __t = c.childText(false)\n")
                if (f.isList) {
                    cb.add("__list_${f.name()}.add(__t)\n")
                } else {
                    cb.add("__raw_${f.name()} = __t\n")
                    if (needLoc) cb.add("__loc_${f.name()} = __t_loc\n")
                    cb.add("__set_${f.name()} = true\n")
                }
            }
        }
    }

    private fun emitPolyAssign(
        cb: CodeBlock.Builder,
        f: CoreFieldSpec,
        subtypeFq: String,
        registry: CoreNestedRegistry,
    ) {
        // buildPolyChild calls ensureNested(sub) for every subtype, so the registry has every spec.
        emitNestedHelperAssign(cb, f, subtypeFq, registry)
    }

    /** Call the registry helper for [helperFq] and bind the result into the field's slot. */
    private fun emitNestedHelperAssign(
        cb: CodeBlock.Builder,
        f: CoreFieldSpec,
        helperFq: String,
        registry: CoreNestedRegistry,
    ) {
        val helper = registry.helperName(helperFq)
        cb.add("val __n_${f.name()}·=·${helper}(c)\n")
        if (f.isList) {
            cb.add("__list_${f.name()}.add(__n_${f.name()})\n")
        } else {
            cb.add("__nested_${f.name()} = __n_${f.name()}\n")
            cb.add("__set_${f.name()} = true\n")
        }
    }

    private fun emitPolyAttrSwitch(
        cb: CodeBlock.Builder,
        f: CoreFieldSpec,
        d: CorePolyDispatch.Attr,
        registry: CoreNestedRegistry,
    ) {
        val nsLit: CodeBlock = if (d.attrNs() == null) CodeBlock.of("null") else CodeBlock.of("%S", d.attrNs())
        cb.add("val __disc_${f.name()}: %T = c.childAttr(%L, %S)\n", STRING_NULLABLE, nsLit, d.attrLocal())
        cb.beginControlFlow("when (__disc_${f.name()})")
        for (v in d.variants()) {
            cb.beginControlFlow("%S ->", v.value())
            emitPolyAssign(cb, f, v.subtypeFq(), registry)
            cb.endControlFlow()
        }
        cb.add(ELSE_SKIP_CHILD)
        cb.endControlFlow()
    }

    private fun emitChildBody(
        cb: CodeBlock.Builder,
        node: TrieNode,
        registry: CoreNestedRegistry,
    ) {
        emitAttrEntries(cb, node)
        emitChildBodyContent(cb, node, registry)
    }

    private fun coerceField(f: CoreFieldSpec, convVarFor: Map<String, String>): CodeBlock {
        val cb = CodeBlock.builder()
        cb.add("val __final_${f.name()}: %T = ", fieldTypeName(f))

        val nullable = fieldNullable(f)

        if (f.coerce() is CoreCoerce.MapAggregate) {
            if (nullable) cb.add("if (!__set_${f.name()}) null else __map_${f.name()}\n")
            else cb.add("__map_${f.name()}\n")
            return cb.build()
        }

        if (f.coerce() is CoreCoerce.Nested) {
            if (f.isList) {
                cb.add("__list_${f.name()}\n")
            } else {
                val nameLit = CodeBlock.of("%S", f.name())
                val missingThrow = CodeBlock.of("throw %T(%L, __loc)", MISSING_EX, nameLit)
                if (nullable) {
                    cb.add("if (!__set_${f.name()}) null else __nested_${f.name()}\n")
                } else {
                    cb.add("if (!__set_${f.name()}) %L else __nested_${f.name()}!!\n", missingThrow)
                }
            }
            return cb.build()
        }

        if (f.isList) {
            cb.add("__list_${f.name()}.map { __r -> ")
            cb.add(coerceRaw(f, CodeBlock.of("__r"), convVarFor))
            cb.add(" }\n")
            return cb.build()
        }

        val rawVar = CodeBlock.of("__raw_${f.name()}")
        val nameLit = CodeBlock.of("%S", f.name())
        val missingThrow = CodeBlock.of("throw %T(%L, __loc)", MISSING_EX, nameLit)
        val orMissing = CodeBlock.of("(%L ?: %L)", rawVar, missingThrow)
        val orEmpty = CodeBlock.of("(%L ?: \"\")", rawVar)

        // Source.MapEntry filtered above via Coerce.MapAggregate; Source.PolyChild filtered via
        // Coerce.Nested. Remaining sources: Attr, Text, Child.
        when (f.source()) {
            is CoreSource.Attr -> {
                if (nullable) {
                    // For AsString the if/else collapses to a no-op pass-through (`raw ?: raw`);
                    // emit the raw nullable directly so kotlinc doesn't warn IfThenToSafeAccess.
                    if (f.coerce() is CoreCoerce.AsString) {
                        cb.add(rawVar)
                    } else {
                        cb.add("if (%L == null) null else ", rawVar)
                        cb.add(coerceRaw(f, rawVar, convVarFor))
                    }
                } else {
                    cb.add(coerceRaw(f, orMissing, convVarFor))
                }
            }

            is CoreSource.Text -> {
                // __raw_X is declared as non-null String at the recordText/subrecordText call,
                // and that call runs unconditionally for any record carrying an @XmlText field.
                // No __set_X gate needed: nullable @XmlText still binds whatever the cursor
                // produced (empty string for an empty body).
                cb.add(coerceRaw(f, rawVar, convVarFor))
            }

            is CoreSource.Child -> {
                val effLoc =
                    if (needsChildLoc(f)) CodeBlock.of("(__loc_${f.name()} ?: __loc)")
                    else CodeBlock.of("__loc")
                if (nullable) {
                    cb.add("if (!__set_${f.name()}) null else ")
                    cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
                } else {
                    cb.add("if (!__set_${f.name()}) %L else ", missingThrow)
                    cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
                }
            }

            else -> Unit
        }
        cb.add("\n")
        return cb.build()
    }

    private fun coerceRaw(
        f: CoreFieldSpec,
        raw: CodeBlock,
        convVarFor: Map<String, String>,
        lcExpr: CodeBlock = CodeBlock.of("__loc"),
    ): CodeBlock {
        val nl = CodeBlock.of("%S", f.name())
        // Coerce.MapAggregate and Coerce.Nested are filtered upstream in coerceField; reaching
        // them here is impossible, so they're not enumerated in this when.
        return when (val coerce = f.coerce()) {
            is CoreCoerce.AsString -> raw
            is CoreCoerce.Scalar -> CodeBlock.of("%M(%L, %L, %L)", scalarMember(coerce.kind()), nl, raw, lcExpr)
            is CoreCoerce.Temporal -> {
                val m = when (coerce.kind()) {
                    CoreScalarKind.LOCAL_DATE -> COERCE_LOCAL_DATE
                    CoreScalarKind.LOCAL_DATE_TIME -> COERCE_LOCAL_DATE_TIME
                    CoreScalarKind.INSTANT -> COERCE_INSTANT
                    else -> error("non-temporal ScalarKind in Coerce.Temporal: ${coerce.kind()}")
                }
                CodeBlock.of("%M(%L, %L, %S, %L)", m, nl, raw, coerce.pattern() ?: "", lcExpr)
            }

            is CoreCoerce.Decimal -> CodeBlock.of(
                "%M(%L, %L, %S, %L)", COERCE_BIG_DECIMAL, nl, raw, coerce.pattern() ?: "", lcExpr,
            )
            is CoreCoerce.Custom -> {
                // registerConverter pre-populates convVarFor for every Coerce.Custom field; lookup
                // is total here.
                val cls = TypeRefs.toClassName(coerce.converterClass())
                val varName = convVarFor.getValue(cls.canonicalName)
                CodeBlock.of("$varName.convert(%L, %L)", raw, lcExpr)
            }

            else -> error("unreachable: Coerce.MapAggregate / Coerce.Nested filtered before coerceRaw")
        }
    }

    /**
     * Map a neutral [CoreScalarKind] back to the runtime coercion member function. Mirrors the
     * APT side. Core's [CoreScalarKind.STRING] is unreachable through [CoreCoerce.Scalar] because
     * CoreClassifier routes string coercion through [CoreCoerce.AsString]; same for BIG_DECIMAL
     * (routed through [CoreCoerce.Decimal]) and the temporal kinds (routed through
     * [CoreCoerce.Temporal]). The else branch therefore signals a classifier bug rather than a
     * missing mapping.
     */
    private fun scalarMember(kind: CoreScalarKind): MemberName = when (kind) {
        CoreScalarKind.INT -> COERCE_INT
        CoreScalarKind.LONG -> COERCE_LONG
        CoreScalarKind.DOUBLE -> COERCE_DOUBLE
        CoreScalarKind.BOOLEAN -> COERCE_BOOLEAN
        else -> error("Coerce.Scalar carries non-numeric ScalarKind $kind; CoreClassifier should route this through Decimal/Temporal/AsString")
    }

    private companion object {
        const val XML_RECORD_FQ = "xmlfluss.XmlRecord"

        val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
        val FLOW_BUILDER = MemberName("kotlinx.coroutines.flow", "flow")
        val INPUT_STREAM = ClassName("java.io", "InputStream")
        val XML_READ_CURSOR = ClassName(XMLFLUSS_RUNTIME, "XmlReadCursor")
        val COMPILED_PATH = ClassName("xmlfluss.path", "CompiledPath")
        val PATHS_COMPILE = MemberName(ClassName(XMLFLUSS_RUNTIME, "Paths"), "compile")
        val MISSING_EX = ClassName("xmlfluss", "XmlParseException", "Missing")
        val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
        val MUTABLE_MAP = ClassName("kotlin.collections", "MutableMap")
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
