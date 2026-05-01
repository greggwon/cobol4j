/*
 * cobol4j - COBOL Runtime Semantics as a Java DSL
 * Copyright (C) 2026 Gregg Wonderly
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
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
