package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Tracks nested record types encountered while classifying a top-level record.
 * The emitter generates one private static helper method per nested type; this
 * registry assigns stable, collision-free helper names and detects classification
 * cycles. Lifetime: one classification round.
 */
public final class NestedRegistry {

    private final Map<String, RecordSpec> byFq = new LinkedHashMap<>();
    private final Map<String, String> helperByFq = new LinkedHashMap<>();
    private final Set<String> inProgress = new HashSet<>();

    public String helperName(String fq) {
        String existing = helperByFq.get(fq);
        if (existing != null) return existing;

        int dot = fq.lastIndexOf('.');
        String simple = dot < 0 ? fq : fq.substring(dot + 1);
        String base = "__parseNested_" + simple;
        String chosen = base;
        int n = 1;
        while (helperByFq.containsValue(chosen)) {
            chosen = base + "_" + n;
            n++;
        }
        helperByFq.put(fq, chosen);
        return chosen;
    }

    public void put(String fq, RecordSpec spec) {
        byFq.put(fq, spec);
    }

    public @Nullable RecordSpec get(String fq) {
        return byFq.get(fq);
    }

    public Map<String, RecordSpec> byFq() {
        return Map.copyOf(byFq);
    }

    public Map<String, String> helpersByFq() {
        return Map.copyOf(helperByFq);
    }

    public boolean isInProgress(String fq) {
        return inProgress.contains(fq);
    }

    public void markInProgress(String fq) {
        inProgress.add(fq);
    }

    public void unmarkInProgress(String fq) {
        inProgress.remove(fq);
    }
}
