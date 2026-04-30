package org.cobol4j;

/**
 * Callback for COBOL's ON SIZE ERROR / NOT ON SIZE ERROR.
 * <p>
 * In COBOL, arithmetic operations can optionally specify an imperative statement
 * to execute when the result exceeds the receiving field's capacity. This interface
 * lets the transpiled code (or hand-written code) supply that behavior as a lambda:
 * <pre>{@code
 *   record.add("BALANCE", amount,
 *       SizeErrorHandler.of(
 *           () -> record.move("ERROR-FLAG", "Y"),     // ON SIZE ERROR
 *           () -> record.move("ERROR-FLAG", "N")      // NOT ON SIZE ERROR
 *       ));
 * }</pre>
 */
public interface SizeErrorHandler {

    /** Called when the result overflows the receiving field's PIC capacity. */
    void onSizeError();

    /** Called when the operation completes without overflow. Default: no-op. */
    default void onSuccess() {}

    /**
     * Create a handler with both error and success callbacks.
     */
    static SizeErrorHandler of(Runnable onError, Runnable onSuccess) {
        return new SizeErrorHandler() {
            @Override public void onSizeError() { onError.run(); }
            @Override public void onSuccess()   { onSuccess.run(); }
        };
    }

    /**
     * Create a handler with only an error callback.
     */
    static SizeErrorHandler onError(Runnable onError) {
        return onError::run;
    }

    /**
     * No error handling — overflow silently truncates (COBOL default behavior).
     */
    static SizeErrorHandler silent() {
        return () -> {};
    }
}
