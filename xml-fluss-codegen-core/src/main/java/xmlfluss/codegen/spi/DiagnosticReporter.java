package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;

/**
 * Sink for classifier diagnostics. Each processor implements this over its native logger
 * ({@code Messager} on APT, {@code KSPLogger} on KSP).
 *
 * <p>{@code nativeHandle} is the opaque symbol returned from
 * {@link RecordSymbol#nativeHandle()} or {@link ComponentSymbol#nativeHandle()}. The
 * implementation may cast it back to its real type to attach the diagnostic to the
 * proper source location, or ignore it if no native handle is available.
 */
public interface DiagnosticReporter {

    void error(@Nullable Object nativeHandle, String message);

    void warn(@Nullable Object nativeHandle, String message);

    boolean hasErrors();
}
