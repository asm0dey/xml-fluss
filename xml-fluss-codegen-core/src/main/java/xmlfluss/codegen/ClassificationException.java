package xmlfluss.codegen;

/**
 * Thrown by the classifier when a record cannot be classified at all (unrecoverable
 * input error). Callers should report the message via their native diagnostic channel
 * and skip the record, then continue with the next.
 *
 * <p>For recoverable errors, the classifier reports them via
 * {@code DiagnosticReporter} (introduced in PR 2) without throwing.
 */
public final class ClassificationException extends RuntimeException {

    public ClassificationException(String message) {
        super(message);
    }

    public ClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
