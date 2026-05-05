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

import java.util.List;

/**
 * Expression AST — structured representation of COBOL values and computations.
 * <p>
 * Used everywhere the parser encounters an operand: MOVE source, COMPUTE RHS,
 * condition left/right, ADD sources, DISPLAY items, etc. The emitter walks
 * this tree to produce Java code — no string guessing or pattern matching.
 *
 * <pre>
 * Grammar:
 *   expression    := field-ref | literal | function-call | arithmetic | figurative
 *   field-ref     := IDENTIFIER [ (subscript) | (pos:len) ]
 *   literal       := NUMBER | STRING
 *   figurative    := SPACES | ZEROS | HIGH-VALUES | LOW-VALUES
 *   function-call := FUNCTION name [ (args) ]
 *   arithmetic    := expression op expression
 * </pre>
 */
public sealed interface Expr {

    /** A field reference — possibly subscripted or reference-modified. */
    record FieldRef(String name, Expr subscript, Expr refModPos, Expr refModLen) implements Expr {
        /** Simple field reference with no subscript or ref-mod. */
        public FieldRef(String name) { this(name, null, null, null); }
        /** Subscripted: FIELD(idx). */
        public static FieldRef subscripted(String name, Expr subscript) {
            return new FieldRef(name, subscript, null, null);
        }
        /** Reference-modified: FIELD(pos:len). */
        public static FieldRef refMod(String name, Expr pos, Expr len) {
            return new FieldRef(name, null, pos, len);
        }
        public boolean isSubscripted() { return subscript != null; }
        public boolean isRefMod() { return refModPos != null; }
        public boolean isSimple() { return subscript == null && refModPos == null; }
    }

    /** A numeric literal. */
    record NumericLit(String value) implements Expr {}

    /** A string literal. */
    record StringLit(String value) implements Expr {}

    /** A figurative constant: SPACES, ZEROS, HIGH-VALUES, LOW-VALUES. */
    record Figurative(String name) implements Expr {
        public static final Figurative SPACES = new Figurative("SPACES");
        public static final Figurative ZEROS = new Figurative("ZEROS");
        public static final Figurative HIGH_VALUES = new Figurative("HIGH-VALUES");
        public static final Figurative LOW_VALUES = new Figurative("LOW-VALUES");
    }

    /** A FUNCTION call: FUNCTION CURRENT-DATE, FUNCTION LENGTH(field), etc. */
    record FunctionCall(String name, List<Expr> args) implements Expr {
        public FunctionCall(String name) { this(name, List.of()); }
    }

    /** Binary arithmetic operation. */
    record BinaryOp(Expr left, String operator, Expr right) implements Expr {}

    /** Unary negation. */
    record Negate(Expr operand) implements Expr {}
}
