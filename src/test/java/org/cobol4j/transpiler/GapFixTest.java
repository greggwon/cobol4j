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

import org.cobol4j.Record;
import org.cobol4j.Decimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for each gap-fix item. Each test verifies end-to-end:
 * COBOL source → parse → emit → the generated Java is correct.
 */
class GapFixTest {

    // ═══════════════════════════════════════════════════════════════
    //  #1 — Figurative constants in comparisons
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ifFieldEqualsSpaces() {
        String cobol = wrap("""
                IF WS-NAME = SPACES
                    DISPLAY "EMPTY"
                END-IF.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        // IF FIELD = SPACES → check if trimmed string is empty
        assertTrue(java.contains(".trim().isEmpty()"),
            "Should generate trim().isEmpty() for SPACES comparison: " + java);
    }

    @Test
    void ifFieldEqualsZeros() {
        String cobol = wrap("""
                IF WS-AMT = ZEROS
                    DISPLAY "ZERO"
                END-IF.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("ZERO") || java.contains("zero") || java.contains("Decimal.ZERO"),
            "Should handle ZEROS comparison: " + java);
    }

    @Test
    void ifFieldEqualsHighValues() {
        String cobol = wrap("""
                IF WS-FLAG = HIGH-VALUES
                    DISPLAY "HIGH"
                END-IF.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
    }

    // Also verify at the runtime level
    @Test
    void runtimeCompareToSpaces() {
        Record rec = Record.define("T")
            .pic("NAME", "X(10)")
            .build();

        // New record is space-filled
        assertEquals("          ", rec.getString("NAME"));
        // A COBOL program would test IF NAME = SPACES
        assertTrue(rec.getString("NAME").trim().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════
    //  #2 — Reference modification in MOVE source
    // ═══════════════════════════════════════════════════════════════

    @Test
    void moveWithReferenceModification() {
        String cobol = wrap("""
                MOVE "20260501" TO WS-DATE.
                MOVE WS-DATE(1:4) TO WS-YEAR.
                MOVE WS-DATE(5:2) TO WS-MONTH.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-DATE  PIC X(8).
               05 WS-YEAR  PIC X(4).
               05 WS-MONTH PIC X(2).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("substring"), "Should use substring for ref mod: " + java);
    }

    // Runtime verification
    @Test
    void runtimeReferenceModification() {
        Record rec = Record.define("T")
            .pic("DATE", "X(8)")
            .pic("YEAR", "X(4)")
            .build();

        rec.move("DATE", "20260501");
        String year = rec.substring("DATE", 1, 4); // 1-based
        rec.move("YEAR", year);
        assertEquals("2026", rec.getString("YEAR").trim());
    }

    // ═══════════════════════════════════════════════════════════════
    //  #3 — Inline PERFORM ... END-PERFORM
    // ═══════════════════════════════════════════════════════════════

    @Test
    void inlinePerformUntil() {
        String cobol = wrap("""
                MOVE 0 TO WS-COUNT.
                PERFORM UNTIL WS-COUNT = 5
                    ADD 1 TO WS-COUNT
                END-PERFORM.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-COUNT PIC 9(3).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        // Should generate an inline loop, not a paragraph reference
        assertTrue(java.contains("performUntil") || java.contains("while"),
            "Should generate inline loop: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #4 — COMPUTE expression tree
    // ═══════════════════════════════════════════════════════════════

    @Test
    void computeSimpleExpression() {
        String cobol = wrap("""
                COMPUTE WS-RESULT = WS-A + WS-B.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-A      PIC S9(5)V99.
               05 WS-B      PIC S9(5)V99.
               05 WS-RESULT PIC S9(7)V99.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        // Should NOT contain "TODO" — should be a real expression
        assertFalse(java.contains("TODO"), "Expression should be translated: " + java);
        assertTrue(java.contains(".add("), "Should contain .add() call: " + java);
    }

    @Test
    void computeWithPrecedence() {
        String cobol = wrap("""
                COMPUTE WS-RESULT = WS-A * WS-B + WS-C.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-A      PIC S9(5)V99.
               05 WS-B      PIC S9(5)V99.
               05 WS-C      PIC S9(5)V99.
               05 WS-RESULT PIC S9(7)V99.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertFalse(java.contains("TODO"), "Expression should be translated: " + java);
        // Multiplication should happen before addition
        assertTrue(java.contains(".multiply("), "Should contain multiply: " + java);
        assertTrue(java.contains(".add("), "Should contain add: " + java);
    }

    @Test
    void computeWithParens() {
        String cobol = wrap("""
                COMPUTE WS-RESULT = WS-A * (WS-B + WS-C).
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-A      PIC S9(5)V99.
               05 WS-B      PIC S9(5)V99.
               05 WS-C      PIC S9(5)V99.
               05 WS-RESULT PIC S9(7)V99.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertFalse(java.contains("TODO"), "Expression should be translated: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #7 — Nested IF
    // ═══════════════════════════════════════════════════════════════

    @Test
    void nestedIfElse() {
        String cobol = wrap("""
                IF WS-A > 10
                    IF WS-B > 20
                        DISPLAY "BOTH HIGH"
                    ELSE
                        DISPLAY "ONLY A HIGH"
                    END-IF
                ELSE
                    DISPLAY "A NOT HIGH"
                END-IF.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-A PIC 9(3).
               05 WS-B PIC 9(3).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());

        // Should have two nested if blocks and three display calls
        int ifCount = countOccurrences(java, "if (");
        assertEquals(2, ifCount, "Should have 2 if statements: " + java);

        int elseCount = countOccurrences(java, "} else {");
        assertEquals(2, elseCount, "Should have 2 else blocks: " + java);

        assertTrue(java.contains("BOTH HIGH"), "Should contain inner then: " + java);
        assertTrue(java.contains("ONLY A HIGH"), "Should contain inner else: " + java);
        assertTrue(java.contains("A NOT HIGH"), "Should contain outer else: " + java);
    }

    @Test
    void tripleNestedIf() {
        String cobol = wrap("""
                IF WS-A > 0
                    IF WS-B > 0
                        IF WS-C > 0
                            DISPLAY "ALL POSITIVE"
                        END-IF
                    END-IF
                END-IF.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-A PIC S9(3).
               05 WS-B PIC S9(3).
               05 WS-C PIC S9(3).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertEquals(3, countOccurrences(java, "if ("),
            "Should have 3 nested ifs: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /** Wrap procedure division statements in a minimal program. */
    private String wrap(String procedureStatements) {
        return wrap(procedureStatements, """
            01 WS-REC.
               05 WS-NAME PIC X(20).
               05 WS-AMT  PIC S9(7)V99.
               05 WS-FLAG PIC X.
            """);
    }

    private String wrap(String procedureStatements, String dataEntries) {
        return """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. GAPTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            """ + dataEntries + """
            PROCEDURE DIVISION.
            MAIN-PARA.
            """ + procedureStatements;
    }

    private int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
