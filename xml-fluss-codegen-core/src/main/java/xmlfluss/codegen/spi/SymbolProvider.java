package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;

/**
 * Entry point into the symbol model. The classifier obtains a {@link SymbolProvider} from
 * each processor, looks up records by FQN, and reports diagnostics through it.
 */
public interface SymbolProvider {

    /**
     * Resolve a record by its fully-qualified name. Returns {@code null} when the type
     * is not visible in the current compilation round (e.g., referenced from another
     * module not yet on the classpath).
     */
    @Nullable RecordSymbol lookupRecord(String fqn);

    /**
     * Resolves a fully-qualified type name to a non-record {@link TypeSymbol}, or {@code null}
     * when the type is unknown or is itself a record. Records flow through {@link #lookupRecord}.
     *
     * <p>Default impl returns {@code null} so SPI implementations that do not yet support
     * non-record type lookup keep compiling. Implementations participating in
     * {@code @XmlConverter} validation must override this.
     */
    default @Nullable TypeSymbol lookupType(String fqn) {
        return null;
    }

    /** The reporter used by classifier and adapters to surface diagnostics. */
    DiagnosticReporter diagnostics();
}
