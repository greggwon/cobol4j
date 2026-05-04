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
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CALL with LINKAGE SECTION — inter-program communication.
 * <p>
 * In COBOL, CALL ... USING passes data items BY REFERENCE: the called program
 * operates on the caller's storage. This is implemented by passing Field
 * references that point directly into the caller's Record byte buffer.
 */
class LinkageTest {

    @Test
    void callWithLinkageSection() {
        Record callerRec = Record.define("CALLER-WS")
            .pic("WS-AMOUNT", "S9(7)V99")
            .pic("WS-RATE", "S9V9999")
            .pic("WS-RESULT", "S9(7)V99")
            .build();

        // Define the called subprogram
        Program calcTax = Program.define("CALC-TAX")
            .linkage("LS-AMOUNT", "S9(7)V99")
            .linkage("LS-RATE", "S9V9999")
            .linkage("LS-RESULT", "S9(7)V99")
            .paragraph("CALC", ctx -> {
                Decimal amount = ctx.linkageField("LS-AMOUNT").get();
                Decimal rate = ctx.linkageField("LS-RATE").get();
                ctx.linkageField("LS-RESULT").move(amount.multiply(rate));
            })
            .build();

        // Caller sets values and calls
        callerRec.move("WS-AMOUNT", Decimal.of("1000.00"));
        callerRec.move("WS-RATE", Decimal.of("0.0800"));

        Program caller = Program.define("CALLER")
            .workingStorage(callerRec)
            .paragraph("MAIN", ctx -> {
                ctx.call(calcTax,
                    callerRec.field("WS-AMOUNT"),
                    callerRec.field("WS-RATE"),
                    callerRec.field("WS-RESULT"));
                ctx.stopRun();
            })
            .build();

        caller.run();

        // The called program wrote to the caller's field (by reference)
        assertTrue(callerRec.getDecimal("WS-RESULT").equalTo(Decimal.of("80.00")));
    }

    @Test
    void callModifiesCallerDataByReference() {
        Record rec = Record.define("WS")
            .pic("COUNTER", "9(5)")
            .build();

        Program incrementer = Program.define("INCR")
            .linkage("L-COUNT", "9(5)")
            .paragraph("DO-IT", ctx -> {
                Field count = ctx.linkageField("L-COUNT");
                count.add(Decimal.ONE);
            })
            .build();

        rec.move("COUNTER", Decimal.of("10"));

        // Call 3 times — each call modifies the same field
        Program main = Program.define("MAIN")
            .workingStorage(rec)
            .paragraph("GO", ctx -> {
                Field counter = rec.field("COUNTER");
                ctx.call(incrementer, counter);
                ctx.call(incrementer, counter);
                ctx.call(incrementer, counter);
                ctx.stopRun();
            })
            .build();

        main.run();
        assertEquals(13, rec.getInt("COUNTER")); // 10 + 1 + 1 + 1
    }

    @Test
    void linkageDecimalShortcut() {
        Record rec = Record.define("WS")
            .pic("VALUE-A", "S9(5)V99")
            .pic("VALUE-B", "S9(5)V99")
            .build();

        rec.move("VALUE-A", Decimal.of("100.00"));
        rec.move("VALUE-B", Decimal.of("200.00"));

        // Subprogram that uses the shortcut methods
        Program adder = Program.define("ADD-EM")
            .linkage("L-A", "S9(5)V99")
            .linkage("L-B", "S9(5)V99")
            .paragraph("DO-ADD", ctx -> {
                Decimal a = ctx.linkageDecimal("L-A");
                Decimal b = ctx.linkageDecimal("L-B");
                // Store sum back in L-A
                ctx.linkageMove("L-A", a.add(b));
            })
            .build();

        Program main = Program.define("MAIN")
            .workingStorage(rec)
            .paragraph("GO", ctx -> {
                ctx.call(adder, rec.field("VALUE-A"), rec.field("VALUE-B"));
                ctx.stopRun();
            })
            .build();

        main.run();
        assertTrue(rec.getDecimal("VALUE-A").equalTo(Decimal.of("300.00")));
        // VALUE-B unchanged
        assertTrue(rec.getDecimal("VALUE-B").equalTo(Decimal.of("200.00")));
    }

    @Test
    void linkageWithMultipleParagraphs() {
        // Called program has multiple paragraphs that all access linkage
        Record rec = Record.define("WS")
            .pic("AMOUNT", "S9(7)V99")
            .build();

        rec.move("AMOUNT", Decimal.of("100.00"));

        Program doubleAndAdd = Program.define("DBL-ADD")
            .linkage("L-AMT", "S9(7)V99")
            .paragraph("DOUBLE-IT", ctx -> {
                Field amt = ctx.linkageField("L-AMT");
                amt.multiply(Decimal.of("2"));
            })
            .paragraph("ADD-TEN", ctx -> {
                Field amt = ctx.linkageField("L-AMT");
                amt.add(Decimal.of("10.00"));
            })
            .build();

        Program main = Program.define("MAIN")
            .workingStorage(rec)
            .paragraph("GO", ctx -> {
                ctx.call(doubleAndAdd, rec.field("AMOUNT"));
                ctx.stopRun();
            })
            .build();

        main.run();
        // 100 * 2 = 200, then + 10 = 210
        assertTrue(rec.getDecimal("AMOUNT").equalTo(Decimal.of("210.00")));
    }

    @Test
    void linkageWithAlphanumericFields() {
        Record rec = Record.define("WS")
            .pic("GREETING", "X(30)")
            .pic("NAME", "X(10)")
            .build();

        rec.move("NAME", "WORLD");

        Program greeter = Program.define("GREET")
            .linkage("L-GREETING", "X(30)")
            .linkage("L-NAME", "X(10)")
            .paragraph("BUILD", ctx -> {
                String name = ctx.linkageField("L-NAME").trimmed();
                ctx.linkageField("L-GREETING").move("HELLO " + name);
            })
            .build();

        Program main = Program.define("MAIN")
            .workingStorage(rec)
            .paragraph("GO", ctx -> {
                ctx.call(greeter, rec.field("GREETING"), rec.field("NAME"));
                ctx.stopRun();
            })
            .build();

        main.run();
        assertEquals("HELLO WORLD", rec.getString("GREETING").trim());
    }

    @Test
    void linkageParameterCountMismatchThrows() {
        Program sub = Program.define("SUB")
            .linkage("L-A", "9(5)")
            .linkage("L-B", "9(5)")
            .paragraph("DO", ctx -> {})
            .build();

        Record rec = Record.define("WS")
            .pic("X", "9(5)")
            .build();

        // Passing 1 field when 2 are expected
        assertThrows(IllegalArgumentException.class, () ->
            sub.runWithLinkage(rec.field("X")));
    }

    @Test
    void linkageFieldNotBoundThrows() {
        Program sub = Program.define("SUB")
            .linkage("L-A", "9(5)")
            .paragraph("DO", ctx -> {
                ctx.linkageField("L-NONEXISTENT"); // wrong name
            })
            .build();

        Record rec = Record.define("WS")
            .pic("X", "9(5)")
            .build();

        assertThrows(IllegalArgumentException.class, () ->
            sub.runWithLinkage(rec.field("X")));
    }

    @Test
    void linkageNotBoundOutsideCallThrows() {
        // A program that tries to access linkage when not called via USING
        Program sub = Program.define("SUB")
            .paragraph("DO", ctx -> {
                ctx.linkageField("ANYTHING");
            })
            .build();

        assertThrows(IllegalStateException.class, sub::run);
    }

    @Test
    void nestedCallsWithLinkage() {
        // Program A calls Program B, which calls Program C
        Record rec = Record.define("WS")
            .pic("VALUE", "9(5)")
            .build();

        rec.move("VALUE", Decimal.of("1"));

        Program addTwo = Program.define("ADD-TWO")
            .linkage("L-V", "9(5)")
            .paragraph("GO", ctx -> {
                ctx.linkageField("L-V").add(Decimal.of("2"));
            })
            .build();

        Program multiplyThree = Program.define("MUL-THREE")
            .linkage("L-V", "9(5)")
            .paragraph("GO", ctx -> {
                // First add 2, then multiply by 3
                ctx.call(addTwo, ctx.linkageField("L-V"));
                ctx.linkageField("L-V").multiply(Decimal.of("3"));
            })
            .build();

        Program main = Program.define("MAIN")
            .workingStorage(rec)
            .paragraph("GO", ctx -> {
                ctx.call(multiplyThree, rec.field("VALUE"));
                ctx.stopRun();
            })
            .build();

        main.run();
        // (1 + 2) * 3 = 9
        assertEquals(9, rec.getInt("VALUE"));
    }

    @Test
    void calledProgramWithStopRunTerminatesCleanly() {
        // STOP RUN in a called program terminates that program only
        // (in real COBOL it terminates the entire run unit, but for composability
        // we treat it as terminating the called program's execution)
        Record rec = Record.define("WS")
            .pic("A", "9(3)")
            .pic("B", "9(3)")
            .build();

        Program sub = Program.define("SUB")
            .linkage("L-A", "9(3)")
            .linkage("L-B", "9(3)")
            .paragraph("SET-A", ctx -> {
                ctx.linkageField("L-A").move(Decimal.of("42"));
                ctx.stopRun(); // terminates sub
            })
            .paragraph("SET-B", ctx -> {
                // This should NOT execute
                ctx.linkageField("L-B").move(Decimal.of("99"));
            })
            .build();

        Program main = Program.define("MAIN")
            .workingStorage(rec)
            .paragraph("GO", ctx -> {
                ctx.call(sub, rec.field("A"), rec.field("B"));
                ctx.stopRun();
            })
            .build();

        main.run();
        assertEquals(42, rec.getInt("A"));
        assertEquals(0, rec.getInt("B")); // never set
    }
}
