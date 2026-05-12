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

    record Move(Expr source, List<String> targets) implements Statement {}
    record MoveCorresponding(String source, String target) implements Statement {}
    record Initialize(String target) implements Statement {}

    // ── Arithmetic ──────────────────────────────────────────────────

    record Add(List<Expr> sources, List<String> targets, List<String> givingTargets, boolean rounded,
               List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {
        /** Backward-compatible: single GIVING target. */
        public Add(List<Expr> sources, List<String> targets, String giving, boolean rounded,
                   List<Statement> onSizeError, List<Statement> notOnSizeError) {
            this(sources, targets,
                 giving == null ? List.of() : List.of(giving),
                 rounded, onSizeError, notOnSizeError);
        }
        /** First GIVING target, or null if none. */
        public String giving() { return givingTargets.isEmpty() ? null : givingTargets.get(0); }
    }

    record Subtract(List<Expr> subtrahends, List<String> targets, List<String> givingTargets, boolean rounded,
                    List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {
        public Subtract(List<Expr> subtrahends, List<String> targets, String giving, boolean rounded,
                        List<Statement> onSizeError, List<Statement> notOnSizeError) {
            this(subtrahends, targets,
                 giving == null ? List.of() : List.of(giving),
                 rounded, onSizeError, notOnSizeError);
        }
        public String giving() { return givingTargets.isEmpty() ? null : givingTargets.get(0); }
    }

    record Multiply(Expr a, Expr by, List<String> givingTargets, boolean rounded,
                    List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {
        public Multiply(Expr a, Expr by, String giving, boolean rounded,
                        List<Statement> onSizeError, List<Statement> notOnSizeError) {
            this(a, by,
                 giving == null ? List.of() : List.of(giving),
                 rounded, onSizeError, notOnSizeError);
        }
        public String giving() { return givingTargets.isEmpty() ? null : givingTargets.get(0); }
    }

    record Divide(Expr dividend, Expr divisor, List<String> givingTargets, String remainder,
                  boolean rounded, List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {
        public Divide(Expr dividend, Expr divisor, String giving, String remainder,
                      boolean rounded, List<Statement> onSizeError, List<Statement> notOnSizeError) {
            this(dividend, divisor,
                 giving == null ? List.of() : List.of(giving),
                 remainder, rounded, onSizeError, notOnSizeError);
        }
        public String giving() { return givingTargets.isEmpty() ? null : givingTargets.get(0); }
    }

    record Compute(String target, Expr expression, boolean rounded,
                   List<Statement> onSizeError, List<Statement> notOnSizeError) implements Statement {}

    // ── Control flow ────────────────────────────────────────────────

    record If(Condition condition, List<Statement> thenBlock, List<Statement> elseBlock) implements Statement {}

    record Evaluate(String subject, List<WhenClause> whenClauses, List<Statement> whenOther) implements Statement {}
    record WhenClause(List<WhenValue> values, List<Statement> body) implements Statement {}
    /** A single WHEN match — value, range (THRU), or condition name. */
    record WhenValue(String value, String thruEnd) implements Statement {
        /** Single value. */
        public WhenValue(String value) { this(value, null); }
        public boolean isRange() { return thruEnd != null; }
    }

    record Perform(String paragraph, String thru, PerformType type,
                   String varying, String from, String by,
                   Condition until) implements Statement {}
    enum PerformType { SIMPLE, THRU, TIMES, UNTIL, VARYING }

    record InlinePerform(Condition until, List<Statement> body,
                         int times,
                         String varying, String from, String by) implements Statement {
        /** PERFORM UNTIL ... END-PERFORM */
        public InlinePerform(Condition until, List<Statement> body) {
            this(until, body, 0, null, null, null);
        }
        /** PERFORM n TIMES ... END-PERFORM */
        public static InlinePerform times(int n, List<Statement> body) {
            return new InlinePerform(null, body, n, null, null, null);
        }
        /** PERFORM VARYING field FROM x BY y UNTIL cond ... END-PERFORM */
        public static InlinePerform varying(String field, String from, String by,
                                             Condition until, List<Statement> body) {
            return new InlinePerform(until, body, 0, field, from, by);
        }
        public boolean isTimes() { return times > 0; }
        public boolean isVarying() { return varying != null; }
    }
    record GoTo(String paragraph) implements Statement {}
    record StopRun() implements Statement {}
    record ExitParagraph() implements Statement {}
    record Continue() implements Statement {}

    // ── I/O ─────────────────────────────────────────────────────────

    record Display(List<Expr> items, String upon) implements Statement {
        /** Backward-compatible constructor without UPON. */
        public Display(List<Expr> items) { this(items, null); }
    }
    record Accept(String target, String from) implements Statement {
        /** Terminal accept (no FROM). */
        public Accept(String target) { this(target, null); }
        public boolean isFromSystem() { return from != null; }
    }

    record Open(String mode, String fileName) implements Statement {}
    record Close(String fileName) implements Statement {}
    record Read(String fileName, String into, List<Statement> atEnd,
                List<Statement> notAtEnd) implements Statement {}
    record Write(String recordName, String from, int advanceLines) implements Statement {
        /** Backward-compatible constructor without ADVANCING. */
        public Write(String recordName, String from) { this(recordName, from, 0); }
    }

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
                     boolean hasOutputProc, String outputProc,
                     boolean duplicatesInOrder) implements Statement {
        /** Backward-compatible constructor without duplicatesInOrder. */
        public SortStmt(String sortFile, List<SortKey> keys,
                        String using, String giving,
                        boolean hasInputProc, String inputProc,
                        boolean hasOutputProc, String outputProc) {
            this(sortFile, keys, using, giving, hasInputProc, inputProc,
                 hasOutputProc, outputProc, false);
        }
    }
    record SortKey(String field, boolean ascending) implements Statement {}

    // ── File operations (additional) ────────────────────────────

    record Rewrite(String recordName, String from) implements Statement {}
    record Delete(String fileName) implements Statement {}

    // ── XML/JSON GENERATE/PARSE ───────────────────────────────────

    /** XML GENERATE / JSON GENERATE / XML PARSE / JSON PARSE */
    record CodecVerb(String format, String action, String record, String target) implements Statement {
        // format: "XML" or "JSON"
        // action: "GENERATE" or "PARSE"
        // record: the record to serialize/deserialize
        // target: the field to write to (GENERATE) or read from (PARSE)
    }

    // ── OO COBOL — INVOKE ─────────────────────────────────────────

    /** INVOKE object "method" USING args RETURNING result */
    record Invoke(String object, String method, List<String> args, String returning) implements Statement {}

    // ── CALL — subprogram or system call ──────────────────────────

    record Call(String target, List<CallParam> params, String returning,
                List<Statement> onException, List<Statement> notOnException) implements Statement {
        /** Backward-compatible constructor without exception handlers. */
        public Call(String target, List<CallParam> params, String returning) {
            this(target, params, returning, List.of(), List.of());
        }
    }
    record CallParam(String value, PassMode mode) implements Statement {}
    enum PassMode { BY_REFERENCE, BY_CONTENT, BY_VALUE }

    // ── SQL ─────────────────────────────────────────────────────────

    record ExecSql(String sqlText) implements Statement {}

    // ── START — keyed file positioning ──────────────────────────────

    /** START file-name KEY IS condition key-name */
    record Start(String fileName, String keyName, String condition) implements Statement {}

    // ── SORT verbs — RELEASE / RETURN ────────────────────────────────

    /** RELEASE sort-record [FROM identifier] — write to sort input */
    record Release(String recordName, String from) implements Statement {}

    /** RETURN sort-file INTO identifier — read from sort output */
    record ReturnStmt(String fileName, String into,
                      List<Statement> atEnd, List<Statement> notAtEnd) implements Statement {}

    // ── No-op verbs (logged at runtime) ──────────────────────────────

    /** CANCEL program-name — release called program. No-op in Java. */
    record Cancel(String programName) implements Statement {}

    /** ALTER paragraph-name TO PROCEED TO target — runtime GO TO redirect. */
    record Alter(String paragraph, String target, int line) implements Statement {}

    /** ENTER language-name — dialect-specific, always a no-op. */
    record Enter(String language, int line) implements Statement {}

    // ── Report Writer stubs (delegate to ReportService) ──────────────

    /** GENERATE report-name / detail-name */
    record Generate(String reportOrDetail) implements Statement {}

    /** TERMINATE report-name */
    record Terminate(String reportName) implements Statement {}

    /** SUPPRESS PRINTING */
    record Suppress() implements Statement {}

    /** INITIATE report-name */
    record Initiate(String reportName) implements Statement {}

    // ── USE declarative ──────────────────────────────────────────────

    /** USE AFTER STANDARD EXCEPTION/ERROR PROCEDURE ON file-name */
    record UseDeclarative(String scope, String fileName, int line) implements Statement {}

    // ── SET index TO literal ─────────────────────────────────────────

    /** SET index-name UP BY / DOWN BY value */
    record SetIndex(String indexName, String direction,
                    Expr value) implements Statement {}

    /** SET index-name TO value */
    record SetIndexTo(String indexName, Expr value) implements Statement {}

    // ── Unsupported — generates informative but uncompilable Java ───

    /**
     * Placeholder for a COBOL construct that the transpiler cannot handle.
     * The emitter generates a deliberate compile error with the COBOL source
     * and a hint, so the user sees exactly where the gap is and can fill it in.
     */
    record Unsupported(String verb, String rawCobol, int line,
                       String hint) implements Statement {}

    // ── Condition (used within IF, PERFORM UNTIL, etc.) ─────────────
    // Conditions form a tree: AND/OR combine sub-conditions.

    sealed interface Condition {
        record Simple(Expr left, String operator, Expr right, boolean negated) implements Condition {}
        record And(Condition left, Condition right) implements Condition {}
        record Or(Condition left, Condition right) implements Condition {}
        record Not(Condition inner) implements Condition {}
        record ConditionName(String name, boolean negated) implements Condition {}
    }
}
