package xmlfluss.apt.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.spi.DiagnosticReporter;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Bridges {@link DiagnosticReporter} to APT's {@link Messager}. The
 * {@code nativeHandle} is expected to be an {@link Element} (or null) from one of
 * the {@link AptRecordSymbol} / {@link AptComponentSymbol} symbols.
 */
public final class AptDiagnosticReporter implements DiagnosticReporter {

    private final Messager messager;
    private boolean errored;

    public AptDiagnosticReporter(Messager messager) {
        this.messager = messager;
    }

    @Override
    public void error(@Nullable Object nativeHandle, String message) {
        errored = true;
        if (nativeHandle instanceof Element e) {
            messager.printMessage(Diagnostic.Kind.ERROR, message, e);
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, message);
        }
    }

    @Override
    public void warn(@Nullable Object nativeHandle, String message) {
        if (nativeHandle instanceof Element e) {
            messager.printMessage(Diagnostic.Kind.WARNING, message, e);
        } else {
            messager.printMessage(Diagnostic.Kind.WARNING, message);
        }
    }

    @Override
    public boolean hasErrors() {
        return errored;
    }
}
