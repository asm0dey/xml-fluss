package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FakeSymbolProvider implements SymbolProvider {

    private final Map<String, RecordSymbol> records = new LinkedHashMap<>();
    private final Map<String, TypeSymbol> types = new LinkedHashMap<>();
    private final DiagnosticReporter diagnostics;

    public FakeSymbolProvider(DiagnosticReporter diagnostics) {
        this.diagnostics = diagnostics;
    }

    public FakeSymbolProvider() {
        this(new FakeDiagnosticReporter());
    }

    public FakeSymbolProvider register(RecordSymbol record) {
        records.put(record.qualifiedName(), record);
        return this;
    }

    public FakeSymbolProvider register(TypeSymbol type) {
        types.put(type.qualifiedName(), type);
        return this;
    }

    @Override
    public @Nullable RecordSymbol lookupRecord(String fqn) {
        return records.get(fqn);
    }

    @Override
    public @Nullable TypeSymbol lookupType(String fqn) {
        return types.get(fqn);
    }

    @Override
    public DiagnosticReporter diagnostics() {
        return diagnostics;
    }
}
