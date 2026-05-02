package xmlfluss.codegen.plan;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.QKey;
import xmlfluss.path.Predicate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure helpers analysing path predicate brackets. Lifted verbatim from the APT and KSP
 * emitters so both processors share a single canonical implementation.
 */
public final class PredicateAnalysis {

    private PredicateAnalysis() {}

    /** True if {@code p} contains an {@link Predicate.Index} anywhere in its tree. */
    public static boolean containsIndex(Predicate p) {
        if (p instanceof Predicate.Index) return true;
        if (p instanceof Predicate.AttrEq) return false;
        if (p instanceof Predicate.And and) return containsIndex(and.getL()) || containsIndex(and.getR());
        if (p instanceof Predicate.Or or) return containsIndex(or.getL()) || containsIndex(or.getR());
        return false;
    }

    /** True if any bracket in {@code brackets} contains an Index sub-predicate. */
    public static boolean bracketsHaveIndex(List<Predicate> brackets) {
        for (Predicate b : brackets) if (containsIndex(b)) return true;
        return false;
    }

    /** Brackets up to (but not including) the first Index-bearing bracket. */
    public static List<Predicate> prefixOfFirstIndex(List<Predicate> brackets) {
        List<Predicate> out = new ArrayList<>();
        for (Predicate b : brackets) {
            if (containsIndex(b)) break;
            out.add(b);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Walks {@code p} left-to-right and returns the first Index value encountered. The grammar
     * permits {@code [2 and @x='y']} which yields {@code And(Index(2), AttrEq(...))} — Index can
     * appear anywhere.
     */
    public static int firstIndexValue(Predicate p) {
        if (p instanceof Predicate.Index idx) return idx.getN();
        if (p instanceof Predicate.And and) {
            return containsIndex(and.getL()) ? firstIndexValue(and.getL()) : firstIndexValue(and.getR());
        }
        if (p instanceof Predicate.Or) {
            throw new IllegalStateException("Index inside Or predicate is not supported (predicate=" + p + ")");
        }
        throw new IllegalStateException("firstIndexValue: predicate has no Index (" + p + ")");
    }

    /**
     * Returns {@code p} with all Index sub-predicates removed, or {@code null} if removal leaves
     * nothing. Only defined for And-shaped composites — Or with embedded Index is rejected.
     */
    public static @Nullable Predicate stripIndex(Predicate p) {
        if (p instanceof Predicate.Index) return null;
        if (p instanceof Predicate.AttrEq) return p;
        if (p instanceof Predicate.And and) {
            Predicate l = stripIndex(and.getL());
            Predicate r = stripIndex(and.getR());
            if (l == null) return r;
            if (r == null) return l;
            return new Predicate.And(l, r);
        }
        if (p instanceof Predicate.Or) {
            throw new IllegalStateException("Index inside Or predicate is not supported (predicate=" + p + ")");
        }
        return p;
    }

    /** Slot-variable suffix derived from a QKey local name and an ordinal. */
    public static String slotName(QKey qkey, int ordinal) {
        String safe = qkey.local().replaceAll("[^A-Za-z0-9_]", "_");
        return safe + "_" + ordinal;
    }
}
