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
package org.cobol4j.transpiler;

/**
 * Top-level entry point: COBOL source to Java source using cobol4j API.
 * <p>
 * Transpilation either succeeds completely or fails with diagnostics.
 * Partial output with silently dropped statements is never produced.
 * <pre>{@code
 * // Simple — throws on error
 * String javaSource = Transpiler.transpile(cobolSource);
 *
 * // With diagnostics — inspect warnings and errors
 * TranspileDiagnostics diag = new TranspileDiagnostics();
 * String javaSource = Transpiler.transpile(cobolSource, diag);
 * if (diag.hasErrors()) {
 *     diag.printAll();
 *     // javaSource is null — transpilation failed
 * }
 * diag.warnings().forEach(w -> System.err.println(w));
 * }</pre>
 */
public final class Transpiler {

    private Transpiler() {}

    /**
     * Transpile COBOL source to Java source.
     * Throws {@link TranspileException} if any construct cannot be translated.
     */
    public static String transpile(String cobolSource) {
        TranspileDiagnostics diag = new TranspileDiagnostics();
        String result = doTranspile(cobolSource, diag);
        if (diag.hasErrors()) {
            throw new TranspileException(diag);
        }
        return result;
    }

    /**
     * Transpile with diagnostics collection.
     * Returns null if errors occurred (check {@code diag.hasErrors()}).
     * Warnings are collected but do not prevent output.
     */
    public static String transpile(String cobolSource, TranspileDiagnostics diag) {
        String result = doTranspile(cobolSource, diag);
        if (diag.hasErrors()) return null;
        return result;
    }

    /** Transpile with explicit format specification. */
    public static String transpile(String cobolSource, boolean fixedFormat) {
        TranspileDiagnostics diag = new TranspileDiagnostics();
        var tokens = Lexer.tokenize(cobolSource, fixedFormat);
        CobolProgram program = Parser.parse(tokens, diag);
        String result = JavaEmitter.emit(program, diag);
        if (diag.hasErrors()) {
            throw new TranspileException(diag);
        }
        return result;
    }

    /** Parse only — return the AST without generating Java. */
    public static CobolProgram parse(String cobolSource) {
        return Parser.parse(cobolSource);
    }

    /** Parse with diagnostics. */
    public static CobolProgram parse(String cobolSource, TranspileDiagnostics diag) {
        return Parser.parse(cobolSource, diag);
    }

    private static String doTranspile(String cobolSource, TranspileDiagnostics diag) {
        CobolProgram program = Parser.parse(cobolSource, diag);
        return JavaEmitter.emit(program, diag);
    }

    /**
     * Thrown when transpilation encounters constructs that cannot be translated.
     */
    public static class TranspileException extends RuntimeException {
        private final TranspileDiagnostics diagnostics;

        public TranspileException(TranspileDiagnostics diag) {
            super(buildMessage(diag));
            this.diagnostics = diag;
        }

        public TranspileDiagnostics diagnostics() { return diagnostics; }

        private static String buildMessage(TranspileDiagnostics diag) {
            StringBuilder sb = new StringBuilder("Transpilation failed with ")
                .append(diag.errors().size()).append(" error(s):\n");
            diag.errors().forEach(e -> sb.append("  ").append(e).append("\n"));
            if (diag.hasWarnings()) {
                sb.append("Additionally, ").append(diag.warnings().size())
                  .append(" warning(s) were generated.\n");
            }
            return sb.toString();
        }
    }
}
