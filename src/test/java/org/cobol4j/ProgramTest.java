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

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProgramTest {

    // ── Basic paragraph execution ───────────────────────────────────

    @Test
    void simpleParagraphExecution() {
        Record rec = Record.define("WS")
            .pic("NAME", "X(10)")
            .build();

        Program program = Program.define("TEST")
            .workingStorage(rec)
            .paragraph("MAIN", ctx -> {
                rec.move("NAME", "HELLO");
                ctx.stopRun();
            })
            .build();

        program.run();
        assertEquals("HELLO     ", rec.getString("NAME"));
    }

    @Test
    void paragraphsExecuteInOrder() {
        Record rec = Record.define("WS")
            .pic("RESULT", "X(20)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("STEP-1", ctx ->
                rec.move("RESULT", "ONE"))
            .paragraph("STEP-2", ctx ->
                rec.move("RESULT", rec.getString("RESULT").trim() + "-TWO"))
            .paragraph("STEP-3", ctx ->
                rec.move("RESULT", rec.getString("RESULT").trim() + "-THREE"))
            .build();

        program.run();
        assertEquals("ONE-TWO-THREE       ", rec.getString("RESULT"));
    }

    @Test
    void stopRunHaltsMidExecution() {
        Record rec = Record.define("WS")
            .pic("STEP", "9")
            .build();

        Program program = Program.define("TEST")
            .paragraph("STEP-1", ctx -> rec.move("STEP", 1L))
            .paragraph("STEP-2", ctx -> ctx.stopRun())
            .paragraph("STEP-3", ctx -> rec.move("STEP", 3L)) // should not execute
            .build();

        program.run();
        assertEquals(1, rec.getInt("STEP"));
    }

    // ── PERFORM ─────────────────────────────────────────────────────

    @Test
    void performSimple() {
        Record rec = Record.define("WS")
            .pic("COUNT", "9(3)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.perform("INCREMENT")
                   .perform("INCREMENT")
                   .perform("INCREMENT")
                   .stopRun();
            })
            .paragraph("INCREMENT", ctx ->
                rec.add("COUNT", BigDecimal.ONE))
            .build();

        program.run();
        assertEquals(3, rec.getInt("COUNT"));
    }

    @Test
    void performThru() {
        List<String> order = new ArrayList<>();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.perform("STEP-A", "STEP-C").stopRun();
            })
            .paragraph("STEP-A", ctx -> order.add("A"))
            .paragraph("STEP-B", ctx -> order.add("B"))
            .paragraph("STEP-C", ctx -> order.add("C"))
            .build();

        program.run();
        assertEquals(List.of("A", "B", "C"), order);
    }

    @Test
    void performTimes() {
        Record rec = Record.define("WS")
            .pic("COUNT", "9(3)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.performTimes("ADD-ONE", 5).stopRun();
            })
            .paragraph("ADD-ONE", ctx ->
                rec.add("COUNT", BigDecimal.ONE))
            .build();

        program.run();
        assertEquals(5, rec.getInt("COUNT"));
    }

    @Test
    void performUntil() {
        Record rec = Record.define("WS")
            .pic("TOTAL", "S9(5)V99")
            .pic("COUNT", "9(3)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.performUntil("ACCUMULATE",
                    () -> rec.getInt("COUNT") >= 10)
                   .stopRun();
            })
            .paragraph("ACCUMULATE", ctx ->
                rec.add("TOTAL", new BigDecimal("100.00"))
                   .add("COUNT", BigDecimal.ONE))
            .build();

        program.run();
        assertEquals(10, rec.getInt("COUNT"));
        assertEquals(0, new BigDecimal("1000.00").compareTo(rec.getDecimal("TOTAL")));
    }

    @Test
    void performVarying() {
        Record rec = Record.define("WS")
            .pic("IDX",   "9(3)")
            .pic("TOTAL", "9(5)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                // SUM 1+2+3+...+10 = 55
                ctx.performVarying("ADD-IDX", rec, "IDX", 1, 1,
                    () -> rec.getInt("IDX") > 10)
                   .stopRun();
            })
            .paragraph("ADD-IDX", ctx -> {
                long idx = rec.getLong("IDX");
                rec.add("TOTAL", BigDecimal.valueOf(idx));
            })
            .build();

        program.run();
        assertEquals(55, rec.getInt("TOTAL"));
    }

    @Test
    void performUntilAfter() {
        Record rec = Record.define("WS")
            .pic("COUNT", "9(3)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                // Test-after: executes at least once even though condition is true
                rec.move("COUNT", 99L);
                ctx.performUntilAfter("INCREMENT",
                    () -> rec.getInt("COUNT") >= 100)
                   .stopRun();
            })
            .paragraph("INCREMENT", ctx ->
                rec.add("COUNT", BigDecimal.ONE))
            .build();

        program.run();
        // Started at 99, incremented once to 100, then condition true → stop
        assertEquals(100, rec.getInt("COUNT"));
    }

    @Test
    void inlinePerformUntil() {
        Record rec = Record.define("WS")
            .pic("N", "9(3)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.performUntil(() -> rec.getInt("N") >= 5, () ->
                    rec.add("N", BigDecimal.ONE))
                   .stopRun();
            })
            .build();

        program.run();
        assertEquals(5, rec.getInt("N"));
    }

    // ── EVALUATE ────────────────────────────────────────────────────

    @Test
    void evaluateWithValues() {
        Record rec = Record.define("WS")
            .pic("STATUS", "X")
            .pic("RESULT", "X(10)")
            .build();

        rec.move("STATUS", "B");

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.evaluate(rec.getString("STATUS").trim())
                   .when("A", () -> rec.move("RESULT", "ACTIVE"))
                   .when("B", () -> rec.move("RESULT", "BLOCKED"))
                   .when("C", () -> rec.move("RESULT", "CLOSED"))
                   .whenOther(() -> rec.move("RESULT", "UNKNOWN"))
                   .execute();
                ctx.stopRun();
            })
            .build();

        program.run();
        assertEquals("BLOCKED   ", rec.getString("RESULT"));
    }

    @Test
    void evaluateTrue() {
        Record rec = Record.define("WS")
            .pic("SCORE", "9(3)")
            .pic("GRADE", "X")
            .build();

        rec.move("SCORE", 85L);

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.evaluateTrue()
                   .whenTrue(() -> rec.getInt("SCORE") >= 90,
                             () -> rec.move("GRADE", "A"))
                   .whenTrue(() -> rec.getInt("SCORE") >= 80,
                             () -> rec.move("GRADE", "B"))
                   .whenTrue(() -> rec.getInt("SCORE") >= 70,
                             () -> rec.move("GRADE", "C"))
                   .whenOther(() -> rec.move("GRADE", "F"))
                   .execute();
                ctx.stopRun();
            })
            .build();

        program.run();
        assertEquals("B", rec.getString("GRADE"));
    }

    @Test
    void evaluateWhenOther() {
        Record rec = Record.define("WS")
            .pic("CODE", "X")
            .pic("DESC", "X(10)")
            .build();

        rec.move("CODE", "Z");

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.evaluate(rec.getString("CODE").trim())
                   .when("A", () -> rec.move("DESC", "ALPHA"))
                   .whenOther(() -> rec.move("DESC", "OTHER"))
                   .execute();
                ctx.stopRun();
            })
            .build();

        program.run();
        assertEquals("OTHER     ", rec.getString("DESC"));
    }

    // ── GO TO ───────────────────────────────────────────────────────

    @Test
    void goToTransfersControl() {
        List<String> executed = new ArrayList<>();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                executed.add("MAIN");
                ctx.goTo("STEP-C");
            })
            .paragraph("STEP-A", ctx -> executed.add("A"))   // skipped
            .paragraph("STEP-B", ctx -> executed.add("B"))   // skipped
            .paragraph("STEP-C", ctx -> executed.add("C"))
            .paragraph("STEP-D", ctx -> executed.add("D"))
            .build();

        program.run();
        // MAIN executes, GO TO jumps to STEP-C, then falls through to STEP-D
        assertEquals(List.of("MAIN", "C", "D"), executed);
    }

    // ── DISPLAY / ACCEPT ────────────────────────────────────────────

    @Test
    void displayCapturesOutput() {
        Record rec = Record.define("WS")
            .pic("NAME", "X(10)")
            .pic("BAL", "S9(5)V99")
            .build();

        rec.move("NAME", "ALICE").move("BAL", new BigDecimal("1234.56"));

        Program program = Program.define("TEST")
            .onDisplay(s -> {}) // suppress stdout
            .paragraph("MAIN", ctx -> {
                ctx.display("Customer: ", rec.getString("NAME").trim())
                   .display("Balance: ", rec.getDecimal("BAL"))
                   .stopRun();
            })
            .build();

        program.run();

        List<String> output = program.context().displayOutput();
        assertEquals(2, output.size());
        assertEquals("Customer: ALICE", output.get(0));
        assertEquals("Balance: 1234.56", output.get(1));
    }

    @Test
    void acceptReadsInput() {
        Record rec = Record.define("WS")
            .pic("INPUT-NAME", "X(20)")
            .build();

        List<String> inputs = List.of("JOHN DOE");
        int[] idx = {0};

        Program program = Program.define("TEST")
            .onAccept(() -> inputs.get(idx[0]++))
            .onDisplay(s -> {})
            .paragraph("MAIN", ctx -> {
                ctx.display("Enter name:")
                   .accept(rec, "INPUT-NAME")
                   .display("Hello, ", rec.getString("INPUT-NAME").trim())
                   .stopRun();
            })
            .build();

        program.run();
        assertEquals("JOHN DOE            ", rec.getString("INPUT-NAME"));
        assertEquals("Hello, JOHN DOE", program.context().displayOutput().get(1));
    }

    // ── Fluent chaining through context ─────────────────────────────

    @Test
    void fluentContextChaining() {
        Record cust = Record.define("CUSTOMER")
            .pic("CUST-NAME", "X(20)")
            .pic("CUST-BAL",  "S9(7)V99").comp3()
            .pic("CUST-STATUS", "X")
                .value88("ACTIVE",   "A")
                .value88("INACTIVE", "I")
            .build();

        Record report = Record.define("REPORT")
            .pic("RPT-LINE", "X(40)")
            .build();

        Program program = Program.define("TEST")
            .workingStorage(cust, report)
            .onDisplay(s -> {})
            .paragraph("MAIN", ctx -> {
                // One fluent chain through the context
                ctx.on(cust)
                   .move("CUST-NAME", "ACME CORP")
                   .move("CUST-BAL", new BigDecimal("50000.00"))
                   .set("ACTIVE");

                // Arithmetic chaining
                ctx.on(cust)
                   .add("CUST-BAL", new BigDecimal("5000.00"))
                   .subtract("CUST-BAL", new BigDecimal("1500.00"));

                // Context operations chain too
                ctx.display("Name: ", cust.getString("CUST-NAME").trim())
                   .display("Balance: ", cust.getDecimal("CUST-BAL"))
                   .stopRun();
            })
            .build();

        program.run();

        assertTrue(cust.is("ACTIVE"));
        assertEquals(0, new BigDecimal("53500.00").compareTo(cust.getDecimal("CUST-BAL")));

        List<String> output = program.context().displayOutput();
        assertEquals("Name: ACME CORP", output.get(0));
        assertEquals("Balance: 53500.00", output.get(1));
    }

    // ── Full program simulation ─────────────────────────────────────

    @Test
    void fullProgramWithLoopAndAccumulation() {
        // Simulates a COBOL program that processes a table of items
        Record ws = Record.define("WORKING-STORAGE")
            .pic("WS-TOTAL",     "S9(7)V99")
            .pic("WS-COUNT",     "9(3)")
            .pic("WS-IDX",       "9(3)")
            .pic("WS-ITEM-AMT",  "S9(5)V99")
            .build();

        // Preloaded amounts to process
        BigDecimal[] amounts = {
            new BigDecimal("100.00"), new BigDecimal("250.50"),
            new BigDecimal("75.25"),  new BigDecimal("1000.00"),
            new BigDecimal("325.75")
        };

        Program program = Program.define("ITEM-TOTAL")
            .workingStorage(ws)
            .onDisplay(s -> {})
            .paragraph("MAIN-LOGIC", ctx -> {
                ws.move("WS-TOTAL", BigDecimal.ZERO)
                  .move("WS-COUNT", (long) amounts.length);

                ctx.performVarying("PROCESS-ITEM", ws, "WS-IDX", 1, 1,
                    () -> ws.getInt("WS-IDX") > ws.getInt("WS-COUNT"));

                ctx.display("Processed ", ws.getInt("WS-COUNT"), " items")
                   .display("Total: ", ws.getDecimal("WS-TOTAL"))
                   .stopRun();
            })
            .paragraph("PROCESS-ITEM", ctx -> {
                int idx = ws.getInt("WS-IDX");
                ws.move("WS-ITEM-AMT", amounts[idx - 1]); // 1-based
                ws.add("WS-TOTAL", ws.getDecimal("WS-ITEM-AMT"));
            })
            .build();

        program.run();

        assertEquals(5, ws.getInt("WS-COUNT"));
        // 100 + 250.50 + 75.25 + 1000 + 325.75 = 1751.50
        assertEquals(0, new BigDecimal("1751.50").compareTo(ws.getDecimal("WS-TOTAL")));

        List<String> output = program.context().displayOutput();
        assertEquals("Processed 5 items", output.get(0));
        assertEquals("Total: 1751.50", output.get(1));
    }

    // ── Run from specific paragraph ─────────────────────────────────

    @Test
    void runFromSpecificParagraph() {
        List<String> executed = new ArrayList<>();

        Program program = Program.define("TEST")
            .paragraph("INIT",    ctx -> executed.add("INIT"))
            .paragraph("PROCESS", ctx -> executed.add("PROCESS"))
            .paragraph("CLEANUP", ctx -> executed.add("CLEANUP"))
            .build();

        // Skip INIT, start from PROCESS
        program.run("PROCESS");
        assertEquals(List.of("PROCESS", "CLEANUP"), executed);
    }
}
