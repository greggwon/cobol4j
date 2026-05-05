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

import org.cobol4j.Decimal;
import org.cobol4j.Record;
import org.cobol4j.Program;
import org.cobol4j.ProgramContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that expressions produce CORRECT RESULTS at runtime —
 * not just that the generated Java contains certain strings.
 * <p>
 * These tests exercise the Decimal arithmetic engine directly with
 * the same patterns the transpiler generates.
 */
class ExpressionCorrectnessTest {

    // ═══════════════════════════════════════════════════════════════
    //  ARITHMETIC PRECEDENCE — runtime verification
    // ═══════════════════════════════════════════════════════════════

    @Test
    void multiplyBeforeAdd_2_plus_3_times_4_equals_14() {
        // COMPUTE R = 2 + 3 * 4
        // Must be 14, not 20
        Decimal result = Decimal.of("2").add(Decimal.of("3").multiply(Decimal.of("4")));
        assertTrue(result.equalTo(Decimal.of("14")));
    }

    @Test
    void parensOverride_2_plus_3_times_4_equals_20() {
        // COMPUTE R = (2 + 3) * 4
        Decimal result = Decimal.of("2").add(Decimal.of("3")).multiply(Decimal.of("4"));
        assertTrue(result.equalTo(Decimal.of("20")));
    }

    @Test
    void divideBeforeSubtract_100_minus_50_div_2_equals_75() {
        // COMPUTE R = 100 - 50 / 2
        // Must be 75, not 25
        Decimal result = Decimal.of("100").subtract(Decimal.of("50").divide(Decimal.of("2"), 10));
        assertTrue(result.equalTo(Decimal.of("75.0000000000")));
    }

    @Test
    void multipleOperatorsSamePrecedence_leftToRight() {
        // COMPUTE R = 100 - 30 - 20
        // Must be 50, not 90 (left to right: (100-30)-20 = 50)
        Decimal result = Decimal.of("100").subtract(Decimal.of("30")).subtract(Decimal.of("20"));
        assertTrue(result.equalTo(Decimal.of("50")));
    }

    @Test
    void divisionLeftToRight() {
        // COMPUTE R = 1000 / 10 / 5
        // Must be 20, not 200 (left to right: (1000/10)/5 = 20)
        Decimal result = Decimal.of("1000").divide(Decimal.of("10"), 10).divide(Decimal.of("5"), 10);
        assertTrue(result.equalTo(Decimal.of("20.0000000000")));
    }

    @Test
    void complexExpression() {
        // COMPUTE R = (A + B) * (C - D) / E
        // A=10, B=20, C=50, D=20, E=3
        // (10+20) * (50-20) / 3 = 30 * 30 / 3 = 300
        Decimal a = Decimal.of("10"), b = Decimal.of("20");
        Decimal c = Decimal.of("50"), d = Decimal.of("20");
        Decimal e = Decimal.of("3");
        Decimal result = a.add(b).multiply(c.subtract(d)).divide(e, 10);
        assertTrue(result.equalTo(Decimal.of("300.0000000000")));
    }

    @Test
    void decimalPrecision() {
        // COMPUTE R = 1 / 3
        // Must not lose precision within the computation
        Decimal result = Decimal.of("1").divide(Decimal.of("3"), 10);
        // 0.3333333333 (10 decimal places)
        assertTrue(result.greaterThan(Decimal.of("0.333")));
        assertTrue(result.lessThan(Decimal.of("0.334")));
    }

    @Test
    void moneyMathExact() {
        // Financial: $19.99 * 5 + $4.95 shipping
        Decimal price = Decimal.of("19.99");
        Decimal qty = Decimal.of("5");
        Decimal shipping = Decimal.of("4.95");
        Decimal total = price.multiply(qty).add(shipping);
        assertTrue(total.equalTo(Decimal.of("104.90")));
    }

    @Test
    void taxCalculation() {
        // $1000.00 * 8.25% = $82.50
        Decimal amount = Decimal.of("1000.00");
        Decimal rate = Decimal.of("0.0825");
        Decimal tax = amount.multiply(rate);
        assertTrue(tax.equalTo(Decimal.of("82.5000")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONDITION LOGIC — runtime verification
    // ═════════════════════════════════════════════��═════════════════

    @Test
    void andBothTrue() {
        Record rec = Record.define("T")
            .pic("A", "9(3)").pic("B", "9(3)").build();
        rec.move("A", 15L).move("B", 5L);

        // IF A > 10 AND B < 20
        boolean result = rec.getDecimal("A").greaterThan(Decimal.of("10"))
                      && rec.getDecimal("B").lessThan(Decimal.of("20"));
        assertTrue(result);
    }

    @Test
    void andOneFalse() {
        Record rec = Record.define("T")
            .pic("A", "9(3)").pic("B", "9(3)").build();
        rec.move("A", 5L).move("B", 5L);

        // IF A > 10 AND B < 20 — A is NOT > 10
        boolean result = rec.getDecimal("A").greaterThan(Decimal.of("10"))
                      && rec.getDecimal("B").lessThan(Decimal.of("20"));
        assertFalse(result);
    }

    @Test
    void orOneTrue() {
        Record rec = Record.define("T")
            .pic("STATUS", "X")
                .value88("ACTIVE", "A")
                .value88("PENDING", "P")
            .build();
        rec.move("STATUS", "P");

        // IF ACTIVE OR PENDING
        boolean result = rec.is("ACTIVE") || rec.is("PENDING");
        assertTrue(result);
    }

    @Test
    void orNeitherTrue() {
        Record rec = Record.define("T")
            .pic("STATUS", "X")
                .value88("ACTIVE", "A")
                .value88("PENDING", "P")
            .build();
        rec.move("STATUS", "X");

        boolean result = rec.is("ACTIVE") || rec.is("PENDING");
        assertFalse(result);
    }

    @Test
    void andOrPrecedence() {
        // A > 10 AND B < 20 OR C = 0
        // AND binds tighter: (A>10 AND B<20) OR C=0
        // With A=15, B=25, C=0: (true AND false) OR true = false OR true = true
        Record rec = Record.define("T")
            .pic("A", "9(3)").pic("B", "9(3)").pic("C", "9(3)").build();
        rec.move("A", 15L).move("B", 25L).move("C", 0L);

        boolean result = (rec.getDecimal("A").greaterThan(Decimal.of("10"))
                       && rec.getDecimal("B").lessThan(Decimal.of("20")))
                      || rec.getDecimal("C").equalTo(Decimal.of("0"));
        assertTrue(result); // false OR true = true
    }

    @Test
    void notGreaterThan() {
        Record rec = Record.define("T").pic("A", "9(3)").build();
        rec.move("A", 5L);

        // IF A NOT GREATER THAN 10 — should be true (5 is not > 10)
        boolean result = !rec.getDecimal("A").greaterThan(Decimal.of("10"));
        assertTrue(result);
    }

    @Test
    void notGreaterThanWhenEqual() {
        Record rec = Record.define("T").pic("A", "9(3)").build();
        rec.move("A", 10L);

        // IF A NOT GREATER THAN 10 — should be true (10 is not > 10)
        boolean result = !rec.getDecimal("A").greaterThan(Decimal.of("10"));
        assertTrue(result);
    }

    @Test
    void notGreaterThanWhenGreater() {
        Record rec = Record.define("T").pic("A", "9(3)").build();
        rec.move("A", 15L);

        // IF A NOT GREATER THAN 10 — should be false (15 IS > 10)
        boolean result = !rec.getDecimal("A").greaterThan(Decimal.of("10"));
        assertFalse(result);
    }

    @Test
    void fieldToFieldComparison() {
        Record rec = Record.define("T")
            .pic("BALANCE", "S9(7)V99")
            .pic("LIMIT", "S9(7)V99")
            .build();
        rec.move("BALANCE", Decimal.of("5000.00"));
        rec.move("LIMIT", Decimal.of("10000.00"));

        // IF BALANCE > LIMIT
        boolean overLimit = rec.getDecimal("BALANCE").greaterThan(rec.getDecimal("LIMIT"));
        assertFalse(overLimit);

        // IF BALANCE <= LIMIT
        boolean withinLimit = rec.getDecimal("BALANCE").lessOrEqual(rec.getDecimal("LIMIT"));
        assertTrue(withinLimit);
    }

    // ═════���═════════════════════════════════════════════════════════
    //  TRANSPILER OUTPUT — verify generated code is correct patterns
    // ════════════════════════════════════════════════════���══════════

    @Test
    void transpilerAndOrGeneratesCorrectJava() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS.
               05 WS-A PIC 9(3).
               05 WS-B PIC 9(3).
            PROCEDURE DIVISION.
            MAIN-PARA.
                IF WS-A > 10 AND WS-B < 20
                    DISPLAY "YES"
                END-IF.
                STOP RUN.
            """;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Failed: " + diag.errors());

        // Must have && (not just the word AND)
        assertTrue(java.contains("&&"), "Should generate && operator");
        // Must have proper Decimal comparisons
        assertTrue(java.contains(".greaterThan("), "Should use greaterThan");
        assertTrue(java.contains(".lessThan("), "Should use lessThan");
        // Must NOT have raw compareTo
        assertFalse(java.contains(".compareTo("), "Should NOT use compareTo");
    }

    @Test
    void transpilerComputeGeneratesChainedDecimal() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS.
               05 WS-A PIC S9(5)V99.
               05 WS-B PIC S9(5)V99.
               05 WS-R PIC S9(7)V99.
            PROCEDURE DIVISION.
            MAIN-PARA.
                COMPUTE WS-R = WS-A * WS-B + 100.
                STOP RUN.
            """;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Failed: " + diag.errors());

        // Must have proper Decimal method calls
        assertTrue(java.contains(".multiply("), "Should have multiply");
        assertTrue(java.contains(".add("), "Should have add");
        assertTrue(java.contains("Decimal.of(\"100\")"), "Should have literal as Decimal");
        // Must NOT have TODO
        assertFalse(java.contains("TODO"), "Should be fully translated");
        // Must NOT have BigDecimal
        assertFalse(java.contains("BigDecimal"), "Should not reference BigDecimal");
    }
}
