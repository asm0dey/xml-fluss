package xmlfluss.runtime;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * Java-friendly facade over the Kotlin lambda call sites on {@link XmlReadCursor}.
 *
 * <p>The cursor's {@code forEachRecordChild}, {@code forEachChild}, and
 * {@code forEachSubrecordChild} methods are declared in Kotlin and compile to
 * {@code Function2<String, String, Unit>}. Generated Java parsers call this helper
 * to keep their generated source readable — the helper is the only place that
 * imports Kotlin's lambda types.
 *
 * <p>Not for direct human use; the API is stable with respect to xml-fluss-apt's
 * generated output but offers no other compatibility guarantees.
 */
public final class JavaCursorAdapter {

    private JavaCursorAdapter() {}

    /** Drive {@link XmlReadCursor#forEachRecordChild}. */
    public static void forEachRecordChild(XmlReadCursor c, BiConsumer<String, String> body) {
        c.forEachRecordChild(asLambda(body));
    }

    /** Drive {@link XmlReadCursor#forEachChild}. */
    public static void forEachChild(XmlReadCursor c, BiConsumer<String, String> body) {
        c.forEachChild(asLambda(body));
    }

    /** Drive {@link XmlReadCursor#forEachSubrecordChild}. */
    public static void forEachSubrecordChild(XmlReadCursor c, BiConsumer<String, String> body) {
        c.forEachSubrecordChild(asLambda(body));
    }

    /** Drive {@link XmlReadCursor#forEachDescendantInChild}. */
    public static void forEachDescendantInChild(XmlReadCursor c, BiPredicate<String, String> body) {
        c.forEachDescendantInChild(body::test);
    }

    private static Function2<String, String, Unit> asLambda(BiConsumer<String, String> body) {
        return (localName, namespaceURI) -> {
            body.accept(localName, namespaceURI);
            return Unit.INSTANCE;
        };
    }
}
