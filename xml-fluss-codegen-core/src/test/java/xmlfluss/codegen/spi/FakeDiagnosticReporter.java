package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** In-memory {@link DiagnosticReporter} for tests. Captures messages by severity. */
public final class FakeDiagnosticReporter implements DiagnosticReporter {

    public record Entry(Severity severity, @Nullable Object handle, String message) {}

    public enum Severity { ERROR, WARN }

    public final List<Entry> entries = new ArrayList<>();

    @Override
    public void error(@Nullable Object nativeHandle, String message) {
        entries.add(new Entry(Severity.ERROR, nativeHandle, message));
    }

    @Override
    public void warn(@Nullable Object nativeHandle, String message) {
        entries.add(new Entry(Severity.WARN, nativeHandle, message));
    }

    @Override
    public boolean hasErrors() {
        return entries.stream().anyMatch(e -> e.severity == Severity.ERROR);
    }
}
