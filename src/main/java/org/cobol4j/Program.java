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
 * A COBOL program — the top-level actor/container.
 * <p>
 * A Program holds its data (records, files), defines its behavior (paragraphs),
 * and provides a runtime context that paragraphs operate within. It's the
 * containing actor that knows what's happening.
 * <p>
 * <b>Defining and running a program:</b>
 * <pre>{@code
 * Record wsRec = Record.define("WS-RECORD")
 *     .pic("WS-NAME", "X(20)")
 *     .pic("WS-TOTAL", "S9(7)V99")
 *     .pic("WS-EOF", "X")
 *         .value88("END-OF-FILE", "Y")
 *     .build();
 *
 * Program program = Program.define("CUSTOMER-REPORT")
 *     .workingStorage(wsRec)
 *     .paragraph("MAIN-LOGIC", ctx -> {
 *         ctx.open(custFile, OpenMode.INPUT)
 *            .performUntil("READ-LOOP",
 *                () -> wsRec.is("END-OF-FILE"))
 *            .close(custFile)
 *            .display("Total: ", wsRec.getDecimal("WS-TOTAL"))
 *            .stopRun();
 *     })
 *     .paragraph("READ-LOOP", ctx -> {
 *         ctx.read(custFile).into(wsRec)
 *            .atEnd(() -> wsRec.set("END-OF-FILE"))
 *            .notAtEnd(() -> ctx.perform("PROCESS"))
 *            .execute();
 *     })
 *     .paragraph("PROCESS", ctx -> {
 *         wsRec.add("WS-TOTAL", wsRec.getDecimal("CUST-BALANCE"));
 *     })
 *     .build();
 *
 * program.run();
 * }</pre>
 */
public final class Program {

    private final String name;
    private final ProgramContext context;
    private final List<String> paragraphOrder;
    private final List<Record> records;

    private Program(String name, ProgramContext context,
                    List<String> paragraphOrder, List<Record> records) {
        this.name = name;
        this.context = context;
        this.paragraphOrder = paragraphOrder;
        this.records = records;
    }

    // ── Factory ─────────────────────────────────────────────────────

    /** Begin defining a program. */
    public static Builder define(String name) {
        return new Builder(name);
    }

    // ── Execution ───────────────────────────────────────────────────

    /**
     * Run the program starting from the first paragraph.
     * Executes paragraphs in order until STOP RUN or the last paragraph completes.
     */
    public void run() {
        if (paragraphOrder.isEmpty()) return;
        run(paragraphOrder.get(0));
    }

    /**
     * Run the program starting from a specific paragraph.
     * Continues through subsequent paragraphs in source order until STOP RUN
     * or the last paragraph completes (fall-through execution, like COBOL).
     */
    public void run(String startParagraph) {
        int startIdx = paragraphOrder.indexOf(startParagraph);
        if (startIdx < 0) {
            throw new IllegalArgumentException("No paragraph named: " + startParagraph);
        }
        try {
            for (int i = startIdx; i < paragraphOrder.size(); i++) {
                try {
                    context.executeParagraph(paragraphOrder.get(i));
                } catch (ProgramContext.GoToException e) {
                    // GO TO redirects: find target and continue from there
                    int targetIdx = paragraphOrder.indexOf(e.target);
                    if (targetIdx < 0) {
                        throw new IllegalArgumentException(
                            "GO TO target not found: " + e.target);
                    }
                    i = targetIdx - 1; // loop increments
                }
            }
        } catch (ProgramContext.StopRunException e) {
            // Normal termination via STOP RUN
        }
    }

    // ── Accessors ───────────────────────────────────────────────────

    public String name() { return name; }

    /** Access the program context (for post-run inspection, testing, etc.). */
    public ProgramContext context() { return context; }

    /** All records registered in working storage. */
    public List<Record> records() { return Collections.unmodifiableList(records); }

    // ═══════════════════════════════════════════════════════════════
    //  BUILDER
    // ═══════════════════════════════════════════════════════════════

    public static final class Builder {

        private final String name;
        private final Map<String, Consumer<ProgramContext>> paragraphs = new LinkedHashMap<>();
        private final List<String> paragraphOrder = new ArrayList<>();
        private final List<Record> records = new ArrayList<>();
        private Consumer<String> displayHandler;
        private Supplier<String> acceptHandler;

        Builder(String name) {
            this.name = name;
        }

        /** Register records in working storage. */
        public Builder workingStorage(Record... records) {
            Collections.addAll(this.records, records);
            return this;
        }

        /**
         * Define a paragraph — a named block of executable logic.
         * Paragraphs execute in the order they are defined (source order),
         * just like COBOL's PROCEDURE DIVISION.
         */
        public Builder paragraph(String name, Consumer<ProgramContext> body) {
            if (paragraphs.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate paragraph: " + name);
            }
            paragraphs.put(name, body);
            paragraphOrder.add(name);
            return this;
        }

        /**
         * Define a section — semantically identical to a paragraph in this model,
         * but signals intent (sections contain paragraphs in COBOL; here it's a
         * named entry point).
         */
        public Builder section(String name, Consumer<ProgramContext> body) {
            return paragraph(name, body);
        }

        /** Override DISPLAY output handler (default: System.out.println). */
        public Builder onDisplay(Consumer<String> handler) {
            this.displayHandler = handler;
            return this;
        }

        /** Override ACCEPT input handler (default: empty string). */
        public Builder onAccept(Supplier<String> handler) {
            this.acceptHandler = handler;
            return this;
        }

        /** Build the Program. */
        public Program build() {
            ProgramContext context = new ProgramContext(
                Collections.unmodifiableMap(new LinkedHashMap<>(paragraphs)),
                Collections.unmodifiableList(new ArrayList<>(paragraphOrder))
            );
            if (displayHandler != null) context.onDisplay(displayHandler);
            if (acceptHandler != null) context.onAccept(acceptHandler);

            return new Program(name, context, paragraphOrder, List.copyOf(records));
        }
    }
}
