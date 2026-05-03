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
 * AST nodes for COBOL PROCEDURE DIVISION statements.
 * Each variant represents a specific COBOL verb.
 */
public sealed interface Statement {

    // ── Data movement ───────────────────────────────────────────────

    record Move(String source, List<String> targets) implements Statement {}
    record MoveCorresponding(String source, String target) implements Statement {}
    record Initialize(String target) implements Statement {}

    // ── Arithmetic ──────────────────────────────────────────────────

    record Add(List<String> sources, String to, String giving, boolean rounded,
               List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {}

    record Subtract(List<String> subtrahends, String from, String giving, boolean rounded,
                    List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {}

    record Multiply(String a, String by, String giving, boolean rounded,
                    List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {}

    record Divide(String dividend, String divisor, String giving, String remainder,
                  boolean rounded, List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {}

    record Compute(String target, String expression, boolean rounded,
                   List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {}

    // ── Control flow ────────────────────────────────────────────────

    record If(Condition condition, List<Statement> thenBlock, List<Statement> elseBlock) implements Statement {}

    record Evaluate(String subject, List<WhenClause> whenClauses, List<Statement> whenOther) implements Statement {}
    record WhenClause(String value, List<Statement> body) implements Statement {}

    record Perform(String paragraph, String thru, PerformType type,
                   String varying, String from, String by,
                   Condition until) implements Statement {}
    enum PerformType { SIMPLE, THRU, TIMES, UNTIL, VARYING }

    record GoTo(String paragraph) implements Statement {}
    record StopRun() implements Statement {}
    record ExitParagraph() implements Statement {}

    // ── I/O ─────────────────────────────────────────────────────────

    record Display(List<String> items) implements Statement {}
    record Accept(String target) implements Statement {}

    record Open(String mode, String fileName) implements Statement {}
    record Close(String fileName) implements Statement {}
    record Read(String fileName, String into, List<Statement> atEnd,
                List<Statement> notAtEnd) implements Statement {}
    record Write(String recordName, String from) implements Statement {}

    // ── String operations ───────────────────────────────────────────

    record SetCondition(String conditionName) implements Statement {}

    // ── String operations ────────────────────────────────────────

    record InspectTallying(String target, String tallyField,
                            String tallyType, String tallyArg,
                            String before, String after) implements Statement {}
    record InspectReplacing(String target, String replaceType,
                             String from, String to,
                             String before, String after) implements Statement {}
    record InspectConverting(String target, String from, String to,
                              String before, String after) implements Statement {}

    record StringStmt(String into, List<StringSource> sources,
                       String pointer) implements Statement {}
    record StringSource(String value, String delimiter) implements Statement {}

    record UnstringStmt(String source, List<String> delimiters,
                         List<String> into, String pointer,
                         String tallyField) implements Statement {}

    // ── Table operations ────────────────────────────────────────

    record SearchStmt(String table, List<SearchWhen> whenClauses,
                       List<Statement> atEnd) implements Statement {}
    record SearchWhen(String condition, String conditionRight,
                       List<Statement> body) implements Statement {}

    record SortStmt(String sortFile, List<SortKey> keys,
                     String using, String giving,
                     boolean hasInputProc, String inputProc,
                     boolean hasOutputProc, String outputProc) implements Statement {}
    record SortKey(String field, boolean ascending) implements Statement {}

    // ── File operations (additional) ────────────────────────────

    record Rewrite(String recordName, String from) implements Statement {}
    record Delete(String fileName) implements Statement {}

    // ── CALL — subprogram or system call ──────────────────────────

    record Call(String target, List<CallParam> params, String returning) implements Statement {}
    record CallParam(String value, PassMode mode) implements Statement {}
    enum PassMode { BY_REFERENCE, BY_CONTENT, BY_VALUE }

    // ── SQL ─────────────────────────────────────────────────────────

    record ExecSql(String sqlText) implements Statement {}

    // ── Condition (used within IF, PERFORM UNTIL, etc.) ─────────────

    record Condition(String left, String operator, String right, boolean negated) {}
}
