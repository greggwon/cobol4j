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

import java.util.*;
import java.util.function.*;

/**
 * The actor/context for a running COBOL program.
 * <p>
 * ProgramContext is what every paragraph receives — it holds the program's state
 * and provides all control-flow, file I/O, and communication operations. Records
 * are accessed directly (they're captured in the paragraph lambdas); the context
 * provides program-level verbs.
 * <p>
 * Designed for fluent chaining — every mutating method returns {@code this}:
 * <pre>{@code
 * ctx.open(custFile, OpenMode.INPUT)
 *    .performUntil("READ-LOOP", () -> custRec.is("END-OF-FILE"))
 *    .close(custFile)
 *    .display("Processing complete: " + custRec.getInt("COUNT") + " records")
 *    .stopRun();
 * }</pre>
 */
public final class ProgramContext {

    private final Map<String, Consumer<ProgramContext>> paragraphs;
    private final List<String> paragraphOrder;
    private Consumer<String> displayHandler;
    private Supplier<String> acceptHandler;
    private final List<String> displayOutput;

    ProgramContext(Map<String, Consumer<ProgramContext>> paragraphs,
                   List<String> paragraphOrder) {
        this.paragraphs = paragraphs;
        this.paragraphOrder = paragraphOrder;
        this.displayHandler = System.out::println;
        this.acceptHandler = () -> "";
        this.displayOutput = new ArrayList<>();
    }

    // ═══════════════════════════════════════════════════════════════
    //  RECORD CONTEXT — switch active record for fluent operations
    // ═══════════════════════════════════════════════════════════════

    /**
     * Switch to a record for fluent chaining. Returns the record itself —
     * since Record's methods are already fluent, this is a pass-through
     * that reads naturally in transpiled code.
     * <pre>{@code
     * ctx.on(customerRecord)
     *    .move("NAME", "JOHN")
     *    .move("BALANCE", Decimal.of("100.00"))
     *    .set("ACTIVE");
     * }</pre>
     */
    public Record on(Record record) {
        return record;
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERFORM — paragraph execution with all COBOL variants
    // ═══════════════════════════════════════════════════════════════

    /** PERFORM paragraph — execute once. */
    public ProgramContext perform(String paragraphName) {
        executeParagraph(paragraphName);
        return this;
    }

    /** PERFORM paragraph THRU paragraph — execute all paragraphs in source order. */
    public ProgramContext perform(String from, String thru) {
        int startIdx = requireParagraphIndex(from);
        int endIdx = requireParagraphIndex(thru);
        if (endIdx < startIdx) {
            throw new IllegalArgumentException(
                "PERFORM THRU: '" + thru + "' precedes '" + from + "' in paragraph order");
        }
        for (int i = startIdx; i <= endIdx; i++) {
            try {
                executeParagraph(paragraphOrder.get(i));
            } catch (GoToException e) {
                // GO TO within THRU range: redirect
                int targetIdx = paragraphOrder.indexOf(e.target);
                if (targetIdx >= startIdx && targetIdx <= endIdx) {
                    i = targetIdx - 1; // loop increments
                } else {
                    throw e; // outside range — propagate
                }
            }
        }
        return this;
    }

    /** PERFORM paragraph n TIMES. */
    public ProgramContext performTimes(String paragraphName, int times) {
        for (int i = 0; i < times; i++) {
            executeParagraph(paragraphName);
        }
        return this;
    }

    /** PERFORM paragraph UNTIL condition is true (test-before). */
    public ProgramContext performUntil(String paragraphName, BooleanSupplier until) {
        while (!until.getAsBoolean()) {
            executeParagraph(paragraphName);
        }
        return this;
    }

    /**
     * PERFORM paragraph WITH TEST AFTER UNTIL condition.
     * Executes at least once, tests condition after each iteration.
     */
    public ProgramContext performUntilAfter(String paragraphName, BooleanSupplier until) {
        do {
            executeParagraph(paragraphName);
        } while (!until.getAsBoolean());
        return this;
    }

    /**
     * PERFORM paragraph VARYING field FROM start BY increment UNTIL condition.
     * <pre>{@code
     * // PERFORM PROCESS-ITEM VARYING IDX FROM 1 BY 1 UNTIL IDX > 100
     * ctx.performVarying("PROCESS-ITEM", rec, "IDX", 1, 1,
     *     () -> rec.getInt("IDX") > 100);
     * }</pre>
     */
    public ProgramContext performVarying(String paragraphName,
                                         Record record, String field,
                                         long from, long by,
                                         BooleanSupplier until) {
        record.move(field, from);
        while (!until.getAsBoolean()) {
            executeParagraph(paragraphName);
            long current = record.getLong(field);
            record.move(field, current + by);
        }
        return this;
    }

    /**
     * PERFORM paragraph VARYING with Decimal (for decimal fields).
     */
    public ProgramContext performVarying(String paragraphName,
                                         Record record, String field,
                                         Decimal from, Decimal by,
                                         BooleanSupplier until) {
        record.move(field, from);
        while (!until.getAsBoolean()) {
            executeParagraph(paragraphName);
            Decimal current = record.getDecimal(field);
            record.move(field, current.add(by));
        }
        return this;
    }

    /**
     * PERFORM VARYING...AFTER — nested loop.
     * <pre>{@code
     * // PERFORM PROCESS VARYING I FROM 1 BY 1 UNTIL I > 10
     * //                   AFTER J FROM 1 BY 1 UNTIL J > 5
     * ctx.performVaryingAfter("PROCESS",
     *     rec, "I", 1, 1, () -> rec.getInt("I") > 10,
     *     rec, "J", 1, 1, () -> rec.getInt("J") > 5);
     * }</pre>
     */
    public ProgramContext performVaryingAfter(String paragraphName,
                                              Record outerRec, String outerField,
                                              long outerFrom, long outerBy,
                                              BooleanSupplier outerUntil,
                                              Record innerRec, String innerField,
                                              long innerFrom, long innerBy,
                                              BooleanSupplier innerUntil) {
        outerRec.move(outerField, outerFrom);
        while (!outerUntil.getAsBoolean()) {
            innerRec.move(innerField, innerFrom);
            while (!innerUntil.getAsBoolean()) {
                executeParagraph(paragraphName);
                long innerCurrent = innerRec.getLong(innerField);
                innerRec.move(innerField, innerCurrent + innerBy);
            }
            long outerCurrent = outerRec.getLong(outerField);
            outerRec.move(outerField, outerCurrent + outerBy);
        }
        return this;
    }

    /**
     * Inline PERFORM — execute a block of code as if it were a paragraph,
     * without defining a named paragraph. Useful for simple loops.
     */
    public ProgramContext performUntil(BooleanSupplier until, Runnable body) {
        while (!until.getAsBoolean()) {
            body.run();
        }
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  EXIT PARAGRAPH / EXIT SECTION
    // ═══════════════════════════════════════════════════════════════

    /**
     * EXIT PARAGRAPH — return from the current paragraph immediately.
     * Code after this call in the same paragraph will not execute.
     */
    public void exitParagraph() {
        throw new ExitParagraphException();
    }

    static final class ExitParagraphException extends RuntimeException {
        ExitParagraphException() { super(null, null, true, false); }
    }

    // ═══════════════════════════════════════════════════════════════
    //  EVALUATE — COBOL's case/switch as a fluent builder
    // ═══════════════════════════════════════════════════════════════

    /**
     * Begin an EVALUATE expression.
     * <pre>{@code
     * ctx.evaluate(rec.getString("STATUS"))
     *    .when("A", () -> ctx.perform("ACTIVE-LOGIC"))
     *    .when("I", () -> ctx.perform("INACTIVE-LOGIC"))
     *    .whenOther(() -> ctx.perform("DEFAULT-LOGIC"))
     *    .execute();
     * }</pre>
     */
    public EvaluateBuilder evaluate(Object subject) {
        return new EvaluateBuilder(this, subject);
    }

    /** EVALUATE TRUE — condition-based branching. */
    public EvaluateBuilder evaluateTrue() {
        return new EvaluateBuilder(this, Boolean.TRUE);
    }

    // ═══════════════════════════════════════════════════════════════
    //  FILE I/O — operations through the program context
    // ═══════════════════════════════════════════════════════════════

    /** OPEN file. */
    public ProgramContext open(CobolFile file, CobolFile.OpenMode mode) {
        file.open(mode);
        return this;
    }

    /** CLOSE file. */
    public ProgramContext close(CobolFile file) {
        file.close();
        return this;
    }

    /**
     * READ — begin a fluent read operation.
     * <pre>{@code
     * ctx.read(custFile).into(custRecord)
     *    .atEnd(() -> custRecord.set("END-OF-FILE"))
     *    .execute();
     * }</pre>
     */
    public ReadBuilder read(CobolFile file) {
        return new ReadBuilder(this, file);
    }

    /** WRITE record TO file. */
    public ProgramContext write(CobolFile file, Record record) {
        file.write(record.rawBuffer());
        return this;
    }

    /** REWRITE record in file. */
    public ProgramContext rewrite(CobolFile file, Record record) {
        file.rewrite(record.rawBuffer());
        return this;
    }

    /** DELETE current record from file. */
    public ProgramContext delete(CobolFile file) {
        file.delete();
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  DISPLAY / ACCEPT — I/O verbs
    // ═══════════════════════════════════════════════════════════════

    /** DISPLAY — output one or more values. */
    public ProgramContext display(Object... values) {
        StringBuilder sb = new StringBuilder();
        for (Object v : values) {
            sb.append(v);
        }
        String line = sb.toString();
        displayOutput.add(line);
        displayHandler.accept(line);
        return this;
    }

    /** ACCEPT — read input into a field. */
    public ProgramContext accept(Record record, String fieldName) {
        String input = acceptHandler.get();
        record.move(fieldName, input);
        return this;
    }

    /** ACCEPT — read input and return it. */
    public String accept() {
        return acceptHandler.get();
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONTROL FLOW
    // ═══════════════════════════════════════════════════════════════

    /**
     * STOP RUN — terminate the program.
     * Throws an internal exception caught by {@link Program#run()}.
     */
    public ProgramContext stopRun() {
        throw new StopRunException();
    }

    /**
     * GO TO — transfer control to another paragraph.
     * Throws an internal exception caught by the paragraph dispatcher.
     * <p>
     * Note: code after goTo() in the same paragraph will not execute.
     */
    public void goTo(String paragraphName) {
        throw new GoToException(paragraphName);
    }

    // ═══════════════════════════════════════════════════════════════
    //  CALL — inter-program communication
    // ═══════════════════════════════════════════════════════════════

    /**
     * CALL subprogram USING records.
     * The called program shares the passed records (by reference, like COBOL).
     */
    public ProgramContext call(Program subprogram, Record... using) {
        subprogram.run();
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONFIGURATION — pluggable I/O handlers
    // ═══════════════════════════════════════════════════════════════

    /** Override DISPLAY output (e.g., for testing or logging). */
    public ProgramContext onDisplay(Consumer<String> handler) {
        this.displayHandler = handler;
        return this;
    }

    /** Override ACCEPT input (e.g., for testing). */
    public ProgramContext onAccept(Supplier<String> handler) {
        this.acceptHandler = handler;
        return this;
    }

    /** Retrieve all DISPLAY output captured during execution. */
    public List<String> displayOutput() {
        return Collections.unmodifiableList(displayOutput);
    }

    // ═══════════════════════════════════════════════════════════════
    //  INTERNAL — paragraph dispatch and control-flow exceptions
    // ═══════════════════════════════════════════════════════════════

    void executeParagraph(String name) {
        Consumer<ProgramContext> para = paragraphs.get(name);
        if (para == null) {
            throw new IllegalArgumentException("No paragraph named: " + name);
        }
        try {
            para.accept(this);
        } catch (ExitParagraphException e) {
            // EXIT PARAGRAPH — normal early return, execution continues
        }
        // GoToException and StopRunException propagate up to Program.run()
        // or perform(from, thru) which handle the redirect at dispatch level.
    }

    private int requireParagraphIndex(String name) {
        int idx = paragraphOrder.indexOf(name);
        if (idx < 0) {
            throw new IllegalArgumentException("No paragraph named: " + name);
        }
        return idx;
    }

    // ── Control-flow exceptions (package-private, no stack trace) ────

    static final class StopRunException extends RuntimeException {
        StopRunException() { super(null, null, true, false); }
    }

    static final class GoToException extends RuntimeException {
        final String target;
        GoToException(String target) {
            super(null, null, true, false);
            this.target = target;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  READ BUILDER — fluent READ ... INTO ... AT END
    // ═══════════════════════════════════════════════════════════════

    public static final class ReadBuilder {
        private final ProgramContext ctx;
        private final CobolFile file;
        private Record intoRecord;
        private Runnable atEnd;
        private Runnable notAtEnd;

        ReadBuilder(ProgramContext ctx, CobolFile file) {
            this.ctx = ctx;
            this.file = file;
        }

        /** READ ... INTO record. */
        public ReadBuilder into(Record record) {
            this.intoRecord = record;
            return this;
        }

        /** AT END — handler when end-of-file is reached. */
        public ReadBuilder atEnd(Runnable handler) {
            this.atEnd = handler;
            return this;
        }

        /** NOT AT END — handler when a record is successfully read. */
        public ReadBuilder notAtEnd(Runnable handler) {
            this.notAtEnd = handler;
            return this;
        }

        /** Execute the READ operation. */
        public ProgramContext execute() {
            boolean ok;
            if (intoRecord != null) {
                ok = file.read(intoRecord.rawBuffer());
            } else {
                ok = file.read(new byte[0]);
            }

            if (!ok && atEnd != null) {
                atEnd.run();
            } else if (ok && notAtEnd != null) {
                notAtEnd.run();
            }
            return ctx;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  EVALUATE BUILDER — fluent EVALUATE / WHEN / WHEN OTHER
    // ═══════════════════════════════════════════════════════════════

    public static final class EvaluateBuilder {
        private final ProgramContext ctx;
        private final Object subject;
        private final List<WhenClause> clauses = new ArrayList<>();
        private Runnable whenOther;

        EvaluateBuilder(ProgramContext ctx, Object subject) {
            this.ctx = ctx;
            this.subject = subject;
        }

        /** WHEN value — execute action if subject equals value. */
        public EvaluateBuilder when(Object value, Runnable action) {
            clauses.add(new WhenClause(v -> Objects.equals(v, value), action));
            return this;
        }

        /** WHEN condition — execute action if the predicate matches. */
        public EvaluateBuilder when(Predicate<Object> test, Runnable action) {
            clauses.add(new WhenClause(test, action));
            return this;
        }

        /**
         * WHEN TRUE — for EVALUATE TRUE, match when the boolean supplier is true.
         * <pre>{@code
         * ctx.evaluateTrue()
         *    .whenTrue(() -> rec.getDecimal("BAL").compareTo(Decimal.ZERO) < 0,
         *              () -> ctx.perform("NEGATIVE-BALANCE"))
         *    .whenTrue(() -> rec.is("ACTIVE"),
         *              () -> ctx.perform("ACTIVE-LOGIC"))
         *    .whenOther(() -> ctx.perform("DEFAULT"))
         *    .execute();
         * }</pre>
         */
        public EvaluateBuilder whenTrue(BooleanSupplier condition, Runnable action) {
            clauses.add(new WhenClause(v -> condition.getAsBoolean(), action));
            return this;
        }

        /** WHEN OTHER — default case. */
        public EvaluateBuilder whenOther(Runnable action) {
            this.whenOther = action;
            return this;
        }

        /** Execute the EVALUATE. */
        public ProgramContext execute() {
            for (WhenClause clause : clauses) {
                if (clause.test.test(subject)) {
                    clause.action.run();
                    return ctx;
                }
            }
            if (whenOther != null) {
                whenOther.run();
            }
            return ctx;
        }

        private record WhenClause(Predicate<Object> test, Runnable action) {}
    }
}
