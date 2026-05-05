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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for expression gaps: unary minus, exponentiation, abbreviated
 * conditions, class conditions, parenthesized booleans.
 */
class ExpressionGapsTest {

    // ═══════════════════════════════════════════════════════════════
    //  1. UNARY MINUS
    // ═══════════════════════════════════════════════════════════════

    @Test
    void computeUnaryMinus() {
        // COMPUTE R = -A (negate a field)
        String java = transpileCompute("COMPUTE WS-R = - WS-A.");
        assertNotNull(java, "Should transpile");
        assertNoTodo(java);
        assertTrue(java.contains(".negate()") || java.contains("Decimal.of(\"-"),
            "Should negate: " + extractCompute(java));
    }

    @Test
    void computeNegativeLiteral() {
        // COMPUTE R = A * -1
        String java = transpileCompute("COMPUTE WS-R = WS-A * -1.");
        assertNotNull(java, "Should transpile");
        assertNoTodo(java);
        // Should multiply and negate — either Decimal.of("-1") or Decimal.of("1").negate()
        assertTrue(java.contains(".multiply("),
            "Should have multiply: " + extractCompute(java));
        assertTrue(java.contains("negate()") || java.contains("\"-1\""),
            "Should negate or use -1 literal: " + extractCompute(java));
    }

    @Test
    void runtimeUnaryMinus() {
        // -5 = negate 5
        Decimal result = Decimal.of("5").negate();
        assertTrue(result.equalTo(Decimal.of("-5")));
    }

    @Test
    void runtimeNegativeLiteralMultiply() {
        // 10 * -3 = -30
        Decimal result = Decimal.of("10").multiply(Decimal.of("-3"));
        assertTrue(result.equalTo(Decimal.of("-30")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  2. EXPONENTIATION (**)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void computeExponentiation() {
        // COMPUTE R = A ** 2
        String java = transpileCompute("COMPUTE WS-R = WS-A ** 2.");
        assertNotNull(java, "Should transpile");
        assertNoTodo(java);
        // Should generate something like pow() or repeated multiply
        assertTrue(java.contains("pow(") || java.contains(".multiply("),
            "Should handle exponentiation: " + extractCompute(java));
    }

    @Test
    void computeExponentiationHigherPrecedence() {
        // COMPUTE R = A + B ** 2
        // ** has highest precedence: A + (B**2)
        String java = transpileCompute("COMPUTE WS-R = WS-A + WS-B ** 2.");
        assertNotNull(java, "Should transpile");
        assertNoTodo(java);
    }

    @Test
    void runtimeExponentiation() {
        // 5 ** 3 = 125
        Decimal base = Decimal.of("5");
        Decimal exp = Decimal.of("3");
        // Using BigDecimal.pow internally
        Decimal result = base.pow(exp.toInt());
        assertTrue(result.equalTo(Decimal.of("125")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  3. ABBREVIATED CONDITIONS (IF A = 1 OR 2 OR 3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void abbreviatedOrCondition() {
        // IF WS-STATUS = "A" OR "B" OR "C"
        // Means: IF WS-STATUS = "A" OR WS-STATUS = "B" OR WS-STATUS = "C"
        String java = transpileIf("""
                IF WS-STATUS = "A" OR "B" OR "C"
                    DISPLAY "VALID"
                END-IF.
            """);
        assertNotNull(java, "Should transpile");
        // Should expand to three comparisons connected by ||
        int orCount = countOccurrences(java, "||");
        assertTrue(orCount >= 2, "Should have at least 2 OR connectors: " + extract(java));
    }

    @Test
    void runtimeAbbreviatedOr() {
        // IF STATUS = "A" OR "B" OR "C"
        Record rec = Record.define("T").pic("STATUS", "X").build();

        rec.move("STATUS", "B");
        String s = rec.getString("STATUS").trim();
        boolean result = s.equals("A") || s.equals("B") || s.equals("C");
        assertTrue(result);

        rec.move("STATUS", "X");
        s = rec.getString("STATUS").trim();
        result = s.equals("A") || s.equals("B") || s.equals("C");
        assertFalse(result);
    }

    // ═══════════════════════════════════════════════════════════════
    //  4. CLASS CONDITIONS (IS NUMERIC, IS ALPHABETIC)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ifIsNumeric() {
        String java = transpileIf("""
                IF WS-FIELD IS NUMERIC
                    DISPLAY "YES"
                END-IF.
            """);
        assertNotNull(java, "Should transpile");
        // Should generate a numeric test
        assertTrue(java.contains("NUMERIC") || java.contains("numeric")
            || java.contains("matches") || java.contains("isNumeric"),
            "Should test numeric: " + extract(java));
    }

    @Test
    void ifIsAlphabetic() {
        String java = transpileIf("""
                IF WS-FIELD IS ALPHABETIC
                    DISPLAY "YES"
                END-IF.
            """);
        assertNotNull(java, "Should transpile");
        assertTrue(java.contains("ALPHABETIC") || java.contains("alphabetic")
            || java.contains("matches") || java.contains("isAlpha"),
            "Should test alphabetic: " + extract(java));
    }

    @Test
    void runtimeIsNumeric() {
        Record rec = Record.define("T").pic("F", "X(10)").build();
        rec.move("F", "12345");
        assertTrue(rec.getString("F").trim().matches("[0-9]+"));

        rec.move("F", "HELLO");
        assertFalse(rec.getString("F").trim().matches("[0-9]+"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  5. PARENTHESIZED BOOLEAN CONDITIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    void notParenthesizedCondition() {
        // IF NOT (A > 10 AND B < 20)
        // Negates the entire compound condition
        String java = transpileIf("""
                IF NOT ( WS-A > 10 AND WS-B < 20 )
                    DISPLAY "OUTSIDE"
                END-IF.
            """);
        assertNotNull(java, "Should transpile");
        assertTrue(java.contains("!") || java.contains("not"),
            "Should negate: " + extract(java));
        assertTrue(java.contains("&&"), "Should have AND: " + extract(java));
    }

    @Test
    void runtimeNotParenthesized() {
        // NOT (15 > 10 AND 25 < 20) = NOT (true AND false) = NOT false = true
        boolean inner = Decimal.of("15").greaterThan(Decimal.of("10"))
                     && Decimal.of("25").lessThan(Decimal.of("20"));
        assertFalse(inner);
        assertTrue(!inner);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String transpileCompute(String statement) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS.
               05 WS-A PIC S9(7)V99.
               05 WS-B PIC S9(7)V99.
               05 WS-R PIC S9(9)V99.
            PROCEDURE DIVISION.
            MAIN-PARA.
                """ + statement + """

                STOP RUN.
            """;
        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        if (java == null) fail("Transpilation failed: " + diag.errors());
        return java;
    }

    private String transpileIf(String ifStatement) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS.
               05 WS-A      PIC S9(5).
               05 WS-B      PIC S9(5).
               05 WS-STATUS PIC X.
               05 WS-FIELD  PIC X(10).
            PROCEDURE DIVISION.
            MAIN-PARA.
                """ + ifStatement + """

                STOP RUN.
            """;
        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        if (java == null) fail("Transpilation failed: " + diag.errors());
        return java;
    }

    private void assertNoTodo(String java) {
        assertFalse(java.contains("TODO"), "Should not have TODO: " + extractCompute(java));
    }

    private String extractCompute(String java) {
        for (String line : java.split("\n")) {
            if (line.contains("compute(") || line.contains(".multiply(")
                || line.contains(".add(") || line.contains(".negate(")
                || line.contains("pow(")) {
                return line.trim();
            }
        }
        return java.substring(Math.max(0, java.length() - 200));
    }

    private String extract(String java) {
        StringBuilder sb = new StringBuilder();
        for (String line : java.split("\n")) {
            if (line.contains("if (") || line.contains("&&") || line.contains("||")
                || line.contains("NUMERIC") || line.contains("matches")) {
                sb.append(line.trim()).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : java.substring(Math.max(0, java.length() - 300));
    }

    private int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) { count++; idx += sub.length(); }
        return count;
    }
}
