package xmlfluss.runtime

import com.fasterxml.aalto.UncheckedStreamException
import com.fasterxml.aalto.stax.InputFactoryImpl
import xmlfluss.Location
import xmlfluss.XmlParseException
import xmlfluss.path.CompiledPath
import xmlfluss.path.PathMatcher
import xmlfluss.path.QName
import java.io.InputStream
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Streaming cursor wrapping an Aalto-XML StAX reader and a [PathMatcher] for the record path.
 *
 * Generated parsers drive the cursor through a small set of operations:
 *
 * - [findNextRecord] advances until the record path matches, then leaves the reader positioned
 *   at the matching `START_ELEMENT`.
 * - At a record element, [recordAttr] reads attributes, [forEachRecordChild] iterates direct
 *   children, [forEachDescendantInChild] walks an unmatched child's subtree, and [recordText]
 *   returns the element's accumulated text.
 * - [forEachSubrecordChild] / [subrecordText] / [childAttr] do the same job for a nested element
 *   (a non-record sub-instance).
 * - [forEachChild] iterates direct children of a non-record element (used for intermediate
 *   wrappers like `meta/published`).
 * - [childText] reads a leaf element's text content. [skipChild] discards an unwanted subtree.
 *
 * Element memory is `O(depth)`. Only frame metadata (qname, location, attribute snapshot) is
 * retained on the stack, and text is buffered only when a call is actively collecting it.
 *
 * @param input the document to parse.
 * @param recordPath the compiled `@XmlRecord` path.
 * @param ignoreNamespace when `true`, every element and attribute is treated as if it lived in
 *   the null namespace — both for the record-path matcher and for `recordAttr` / `childAttr` /
 *   the `forEach*` body's `ns` argument. Use to parse documents whose authors omitted the
 *   declared namespace (or used a different one) without rewriting the data classes' `@XmlNs`
 *   bindings.
 */
class XmlReadCursor(
    input: InputStream,
    recordPath: CompiledPath,
    val ignoreNamespace: Boolean = false,
) : AutoCloseable {

    private class Frame(
        val name: QName,
        val line: Int,
        val col: Int,
        val attrs: Map<QName, String>,
    )

    private val reader: XMLStreamReader = factory.createXMLStreamReader(input)
    private val stack = ArrayDeque<Frame>()
    private val matcher = PathMatcher(recordPath, ignoreNamespace)
    private var atRecord: Boolean = false
    private val recordTextSb = StringBuilder()
    private var lastSubrecordText: String? = null

    /** Closes the underlying StAX reader. Safe to call multiple times via `use { ... }`. */
    override fun close() { reader.close() }

    /**
     * Advances the reader until the record path matches, leaving the cursor at the matching
     * `START_ELEMENT`.
     *
     * @return `true` when a record was found, `false` when the document was exhausted.
     */
    fun findNextRecord(): Boolean {
        while (wrapStax { reader.hasNext() }) {
            when (wrapStax { reader.next() }) {
                XMLStreamConstants.START_ELEMENT -> {
                    if (handleStart()) return true
                }
                XMLStreamConstants.END_ELEMENT -> {
                    stack.removeLast()
                    matcher.popElement()
                }
            }
        }
        return false
    }

    private fun handleStart(): Boolean {
        val qn = currentQName()
        val attrs = captureAttrs()
        val (line, col) = wrapStax { reader.location.let { it.lineNumber to it.columnNumber } }
        stack.addLast(Frame(qn, line, col, attrs))
        val m = matcher.pushElement(
            qn,
        ) { name -> attrs[if (ignoreNamespace) QName(null, name.local) else name] }
        if (m) atRecord = true
        return m
    }

    /**
     * Reads an attribute on the current record element.
     *
     * Returns the attribute value as Aalto's stax reader provides it; per XML 1.0 §3.3.3 attr
     * values are normalized — leading/trailing whitespace is preserved for CDATA-typed attrs (the
     * default) and collapsed for tokenized types. No additional `trim()` is applied, so a CDATA
     * attr like `id=" 1 "` returns `" 1 "` verbatim.
     *
     * @param ns the namespace URI, or `null` for the null namespace.
     * @param name the attribute's local-name.
     * @return the attribute value, or `null` if it is absent.
     */
    fun recordAttr(ns: String?, name: String): String? =
        stack.last().attrs[QName(if (ignoreNamespace) null else ns, name)]

    /** Source position of the current record element. */
    fun recordLocation(): Location = locationAt(stack.last())

    /**
     * Iterates the direct children of the current record element. The body fires once per child
     * `START_ELEMENT` and should consume the child before returning, via [childText],
     * [forEachChild], [forEachSubrecordChild], or [skipChild]. Anything left unconsumed is
     * skipped automatically. Text nodes between children accumulate into the buffer that
     * [recordText] later returns.
     */
    fun forEachRecordChild(body: (localName: String, namespaceURI: String?) -> Unit) {
        check(atRecord) { "not positioned at a record element" }
        val recordDepth = stack.size
        recordTextSb.setLength(0)
        while (wrapStax { reader.hasNext() }) {
            when (wrapStax { reader.next() }) {
                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE -> {
                    if (stack.size == recordDepth) recordTextSb.append(wrapStax { reader.text })
                }
                XMLStreamConstants.START_ELEMENT -> {
                    val qn = currentQName()
                    val attrs = captureAttrs()
                    val (line, col) = wrapStax { reader.location.let { it.lineNumber to it.columnNumber } }
                    stack.addLast(Frame(qn, line, col, attrs))
                    body(qn.local, qn.ns)
                    if (stack.size > recordDepth) {
                        skipToDepth(recordDepth)
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (stack.size == recordDepth) {
                        stack.removeLast()
                        matcher.popElement()
                        atRecord = false
                        return
                    }
                    stack.removeLast()
                }
            }
        }
        throw XmlParseException.Malformed("unexpected EOF inside record", recordLocation())
    }

    /**
     * Iterates the direct children of the current non-record element. Same contract as
     * [forEachRecordChild], without the record-text bookkeeping. Used to walk intermediate
     * wrapper elements such as `meta` in a `meta/published` path.
     */
    fun forEachChild(body: (localName: String, namespaceURI: String?) -> Unit) {
        val parentDepth = stack.size
        while (wrapStax { reader.hasNext() }) {
            when (wrapStax { reader.next() }) {
                XMLStreamConstants.START_ELEMENT -> {
                    val qn = currentQName()
                    val attrs = captureAttrs()
                    val (line, col) = wrapStax { reader.location.let { it.lineNumber to it.columnNumber } }
                    stack.addLast(Frame(qn, line, col, attrs))
                    body(qn.local, qn.ns)
                    if (stack.size > parentDepth) {
                        skipToDepth(parentDepth)
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (stack.size == parentDepth) {
                        stack.removeLast()
                        return
                    }
                    stack.removeLast()
                }
            }
        }
        throw XmlParseException.Malformed("unexpected EOF in forEachChild", stack.lastOrNull()?.let(::locationAt) ?: Location(-1, -1, "/"))
    }

    /**
     * Returns the text accumulated inside the most recent [forEachRecordChild] call.
     *
     * @param preserveWhitespace when `true`, returns the raw buffer. Otherwise the result is
     *   trimmed.
     */
    fun recordText(preserveWhitespace: Boolean): String =
        if (preserveWhitespace) recordTextSb.toString() else recordTextSb.toString().trim()

    /**
     * Like [forEachRecordChild], but for a nested non-record element. Use this when building a
     * sub-instance from a child element. Read attributes via [childAttr] before iterating, then
     * call [subrecordText] afterwards if the type also has an `@XmlText` field.
     */
    fun forEachSubrecordChild(body: (localName: String, namespaceURI: String?) -> Unit) {
        val parentDepth = stack.size
        val sb = StringBuilder()
        while (wrapStax { reader.hasNext() }) {
            when (wrapStax { reader.next() }) {
                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE -> {
                    if (stack.size == parentDepth) sb.append(wrapStax { reader.text })
                }
                XMLStreamConstants.START_ELEMENT -> {
                    val qn = currentQName()
                    val attrs = captureAttrs()
                    val (line, col) = wrapStax { reader.location.let { it.lineNumber to it.columnNumber } }
                    stack.addLast(Frame(qn, line, col, attrs))
                    body(qn.local, qn.ns)
                    if (stack.size > parentDepth) {
                        skipToDepth(parentDepth)
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (stack.size == parentDepth) {
                        stack.removeLast()
                        lastSubrecordText = sb.toString()
                        return
                    }
                    stack.removeLast()
                }
            }
        }
        throw XmlParseException.Malformed("unexpected EOF in forEachSubrecordChild", stack.lastOrNull()?.let(::locationAt) ?: Location(-1, -1, "/"))
    }

    /**
     * Returns the text accumulated inside the most recent [forEachSubrecordChild] call. Every
     * nested call overwrites the buffer, so always read the value before starting another nested
     * iteration.
     *
     * @param preserveWhitespace when `true`, returns the raw buffer. Otherwise the result is
     *   trimmed.
     */
    fun subrecordText(preserveWhitespace: Boolean): String {
        val s = lastSubrecordText ?: ""
        return if (preserveWhitespace) s else s.trim()
    }

    /**
     * Walks the entire subtree of the current element, firing [body] at every `START_ELEMENT`
     * inside it. Implements the descendant axis (`//name`) on `@XmlChild`.
     *
     * @param body returns `true` when it has consumed the element (including its end tag, for
     *   example by calling a nested-helper that drains the subtree). Returning `false` lets the
     *   walker descend into the element's children. Returning `true` without consuming corrupts
     *   cursor state.
     */
    fun forEachDescendantInChild(body: (localName: String, namespaceURI: String?) -> Boolean) {
        val rootDepth = stack.size
        while (wrapStax { reader.hasNext() }) {
            when (wrapStax { reader.next() }) {
                XMLStreamConstants.START_ELEMENT -> {
                    val qn = currentQName()
                    val attrs = captureAttrs()
                    val (line, col) = wrapStax { reader.location.let { it.lineNumber to it.columnNumber } }
                    stack.addLast(Frame(qn, line, col, attrs))
                    val depthAfterPush = stack.size
                    val consumed = body(qn.local, qn.ns)
                    if (consumed && stack.size > depthAfterPush - 1) {
                        skipToDepth(depthAfterPush - 1)
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (stack.size == rootDepth) {
                        stack.removeLast()
                        return
                    }
                    stack.removeLast()
                }
            }
        }
        throw XmlParseException.Malformed("unexpected EOF in forEachDescendantInChild", locationAt(stack.last()))
    }

    /**
     * Reads an attribute on the current child element (the element pushed by the surrounding
     * iterator before invoking the body).
     *
     * Same whitespace contract as [recordAttr]: returns the value as Aalto's stax reader provides
     * it; per XML 1.0 §3.3.3 attr values are normalized — leading/trailing whitespace is preserved
     * for CDATA-typed attrs (the default) and collapsed for tokenized types. No additional
     * `trim()` is applied.
     */
    fun childAttr(ns: String?, name: String): String? =
        stack.last().attrs[QName(if (ignoreNamespace) null else ns, name)]

    /** Source position of the current child element. */
    fun childLocation(): Location = locationAt(stack.last())

    /**
     * Reads the text content of the current child element and consumes its `END_ELEMENT`.
     * Nested elements inside the child are tolerated (their text is concatenated into the
     * result) but not navigated.
     *
     * @param preserveWhitespace when `true`, returns the raw text. Otherwise trims.
     */
    fun childText(preserveWhitespace: Boolean): String {
        val depth0 = stack.size
        val sb = StringBuilder()
        while (wrapStax { reader.hasNext() }) {
            when (wrapStax { reader.next() }) {
                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE -> sb.append(wrapStax { reader.text })
                XMLStreamConstants.START_ELEMENT -> {
                    val qn = currentQName()
                    val (line, col) = wrapStax { reader.location.let { it.lineNumber to it.columnNumber } }
                    stack.addLast(Frame(qn, line, col, emptyMap()))
                }
                XMLStreamConstants.END_ELEMENT -> {
                    if (stack.size == depth0) {
                        stack.removeLast()
                        return if (preserveWhitespace) sb.toString() else sb.toString().trim()
                    }
                    stack.removeLast()
                }
            }
        }
        throw XmlParseException.Malformed("unexpected EOF in childText", childLocation())
    }

    /** Discards the current child element's subtree and consumes its `END_ELEMENT`. */
    fun skipChild() {
        skipToDepth(stack.size - 1)
    }

    private fun skipToDepth(targetDepth: Int) {
        while (stack.size > targetDepth && wrapStax { reader.hasNext() }) {
            when (wrapStax { reader.next() }) {
                XMLStreamConstants.START_ELEMENT -> {
                    val qn = currentQName()
                    val (line, col) = wrapStax { reader.location.let { it.lineNumber to it.columnNumber } }
                    stack.addLast(Frame(qn, line, col, emptyMap()))
                }
                XMLStreamConstants.END_ELEMENT -> {
                    stack.removeLast()
                }
            }
        }
    }

    private fun captureAttrs(): Map<QName, String> = wrapStax {
        val n = reader.attributeCount
        if (n == 0) return@wrapStax emptyMap()
        val m = HashMap<QName, String>(n)
        for (i in 0 until n) {
            val ns = if (ignoreNamespace) null
            else reader.getAttributeNamespace(i)?.takeIf { it.isNotEmpty() }
            val local = reader.getAttributeLocalName(i)
            m[QName(ns, local)] = reader.getAttributeValue(i)
        }
        m
    }

    private fun currentQName(): QName = wrapStax {
        val ns = if (ignoreNamespace) null
        else reader.namespaceURI?.takeIf { it.isNotEmpty() }
        QName(ns, reader.localName)
    }

    private fun locationAt(f: Frame): Location {
        val pathStr = stack.joinToString("/") { it.name.local }
        return Location(f.line, f.col, "/$pathStr")
    }

    /**
     * Best-effort current source location, used when wrapping raw stax errors. Walks a known
     * frame when available; falls back to the reader's last known location, then to a sentinel.
     * Never throws — even location lookup itself is guarded.
     */
    private fun currentLocationSafe(): Location {
        stack.lastOrNull()?.let { return locationAt(it) }
        return try {
            val l = reader.location
            Location(l?.lineNumber ?: -1, l?.columnNumber ?: -1, "/")
        } catch (_: Throwable) {
            Location(-1, -1, "/")
        }
    }

    /**
     * Wraps a stax-touching block, converting Aalto's unchecked `UncheckedStreamException` and
     * the standard `XMLStreamException` into [XmlParseException.Malformed] with a best-effort
     * [Location]. Aalto's `WFCException` is a subclass of `XMLStreamException` and is caught by
     * the same handler.
     */
    private inline fun <T> wrapStax(block: () -> T): T =
        try {
            block()
        } catch (e: XMLStreamException) {
            throw XmlParseException.Malformed(e.message ?: "stax error", currentLocationSafe(), e)
        } catch (e: UncheckedStreamException) {
            throw XmlParseException.Malformed(e.message ?: "stax error", currentLocationSafe(), e)
        }

    private companion object {
        val factory: InputFactoryImpl = InputFactoryImpl().apply {
            setProperty(javax.xml.stream.XMLInputFactory.IS_COALESCING, true)
            setProperty(javax.xml.stream.XMLInputFactory.IS_NAMESPACE_AWARE, true)
            setProperty(javax.xml.stream.XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true)
        }
    }
}
