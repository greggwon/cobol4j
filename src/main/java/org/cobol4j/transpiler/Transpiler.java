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
 * Top-level entry point: COBOL source → Java source using cobol4j API.
 * <p>
 * Usage:
 * <pre>{@code
 * String javaSource = Transpiler.transpile(cobolSource);
 * }</pre>
 */
public final class Transpiler {

    private Transpiler() {}

    /** Transpile COBOL source text to Java source text. */
    public static String transpile(String cobolSource) {
        CobolProgram program = Parser.parse(cobolSource);
        return JavaEmitter.emit(program);
    }

    /** Transpile with explicit format specification. */
    public static String transpile(String cobolSource, boolean fixedFormat) {
        var tokens = Lexer.tokenize(cobolSource, fixedFormat);
        CobolProgram program = Parser.parse(tokens);
        return JavaEmitter.emit(program);
    }

    /** Parse only — return the AST without generating Java. */
    public static CobolProgram parse(String cobolSource) {
        return Parser.parse(cobolSource);
    }
}
