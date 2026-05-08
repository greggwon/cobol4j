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

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-argument arithmetic statements.
 * Verifies that ADD, SUBTRACT, and other arithmetic verbs
 * correctly handle multiple sources, targets, and GIVING forms.
 */
class MultiArgArithmeticTest {

    // ═══════════════════════════════════════════════════════════════
    //  ADD — multiple sources with GIVING
    // ═══════════════════════════════════════════════════════════════

    @Test
    void addTwoSourcesGiving() {
        // ADD a b GIVING c → c = a + b
        String java = transpile("""
                ADD WS-A WS-B GIVING WS-RESULT.
                STOP RUN.
            """);
        // Must reference both source fields, not Decimal.of("0") for the second
        assertTrue(java.contains("getDecimal(\"WS-A\")"), "Must read WS-A: " + java);
        assertTrue(java.contains("getDecimal(\"WS-B\")"), "Must read WS-B: " + java);
        assertTrue(java.contains(".giving("), "Must have GIVING clause: " + java);
        // For 2 sources, should use Arithmetic.add(a, b) directly, not add(sum, 0)
        assertTrue(java.contains("Arithmetic.add(") && !java.contains("Decimal.of(\"0\")"),
            "Should use Arithmetic.add(a, b) directly for 2 sources, not fold with zero: " + java);
    }

    @Test
    void addThreeSourcesGiving() {
        // ADD a b c GIVING d → d = a + b + c
        String java = transpile("""
                ADD WS-A WS-B WS-C GIVING WS-RESULT.
                STOP RUN.
            """);
        assertTrue(java.contains("getDecimal(\"WS-A\")"), "Must read WS-A: " + java);
        assertTrue(java.contains("getDecimal(\"WS-B\")"), "Must read WS-B: " + java);
        assertTrue(java.contains("getDecimal(\"WS-C\")"), "Must read WS-C: " + java);
        assertTrue(java.contains(".add("), "Must chain .add() for third source: " + java);
        assertTrue(java.contains(".giving("), "Must have GIVING clause: " + java);
    }

    @Test
    void addSingleSourceGiving() {
        // ADD a GIVING b → b = a + 0 (store a into b)
        String java = transpile("""
                ADD WS-A GIVING WS-RESULT.
                STOP RUN.
            """);
        assertTrue(java.contains("getDecimal(\"WS-A\")"), "Must read WS-A: " + java);
        assertTrue(java.contains(".giving("), "Must have GIVING clause: " + java);
    }

    @Test
    void addMultipleSourcesToMultipleTargets() {
        // ADD a b TO c d → c = c + a + b; d = d + a + b
        String java = transpile("""
                ADD WS-A WS-B TO WS-C WS-RESULT.
                STOP RUN.
            """);
        assertTrue(java.contains("getDecimal(\"WS-A\")"), "Must read WS-A: " + java);
        assertTrue(java.contains("getDecimal(\"WS-B\")"), "Must read WS-B: " + java);
        // Both targets should get the add
        assertTrue(java.contains("\"WS-C\""), "Must target WS-C: " + java);
        assertTrue(java.contains("\"WS-RESULT\""), "Must target WS-RESULT: " + java);
    }

    @Test
    void addSingleSourceToMultipleTargets() {
        // ADD a TO b c d → b += a; c += a; d += a
        String java = transpile("""
                ADD WS-A TO WS-B WS-C WS-RESULT.
                STOP RUN.
            """);
        // Should emit 3 separate .add() calls, one per target
        int addCount = countOccurrences(java, ".add(\"WS-");
        assertTrue(addCount >= 3,
            "Should emit add for each of 3 targets, found " + addCount + ": " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SUBTRACT — multiple subtrahends
    // ═══════════════════════════════════════════════════════════════

    @Test
    void subtractTwoFromGiving() {
        // SUBTRACT a b FROM c GIVING d → d = c - a - b = c - (a + b)
        String java = transpile("""
                SUBTRACT WS-A WS-B FROM WS-C GIVING WS-RESULT.
                STOP RUN.
            """);
        assertTrue(java.contains("getDecimal(\"WS-A\")"), "Must read WS-A: " + java);
        assertTrue(java.contains("getDecimal(\"WS-B\")"), "Must read WS-B: " + java);
        assertTrue(java.contains("getDecimal(\"WS-C\")"), "Must read WS-C (minuend): " + java);
        assertTrue(java.contains(".giving("), "Must have GIVING clause: " + java);
    }

    @Test
    void subtractMultipleFromMultipleTargets() {
        // SUBTRACT a b FROM c d → c = c - (a+b); d = d - (a+b)
        String java = transpile("""
                SUBTRACT WS-A WS-B FROM WS-C WS-RESULT.
                STOP RUN.
            """);
        assertTrue(java.contains("getDecimal(\"WS-A\")"), "Must read WS-A: " + java);
        assertTrue(java.contains("getDecimal(\"WS-B\")"), "Must read WS-B: " + java);
        // Both targets get subtraction
        assertTrue(java.contains("\"WS-C\""), "Must target WS-C: " + java);
        assertTrue(java.contains("\"WS-RESULT\""), "Must target WS-RESULT: " + java);
    }

    @Test
    void subtractSingleFromMultipleTargets() {
        // SUBTRACT a FROM b c → b -= a; c -= a
        String java = transpile("""
                SUBTRACT WS-A FROM WS-B WS-C.
                STOP RUN.
            """);
        int subCount = countOccurrences(java, ".subtract(\"WS-");
        assertTrue(subCount >= 2,
            "Should emit subtract for each of 2 targets, found " + subCount + ": " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIZE ERROR handlers — must emit actual statements, not stubs
    // ═══════════════════════════════════════════════════════════════

    @Test
    void addGivingWithSizeError() {
        // ADD a b GIVING c ON SIZE ERROR MOVE "Y" TO WS-FLAG
        String java = transpile("""
                ADD WS-A WS-B GIVING WS-RESULT
                    ON SIZE ERROR
                        MOVE "Y" TO WS-FLAG
                    NOT ON SIZE ERROR
                        MOVE "N" TO WS-FLAG
                END-ADD.
                STOP RUN.
            """);
        assertTrue(java.contains(".onSizeError("), "Must emit onSizeError: " + java);
        assertTrue(java.contains("SizeErrorHandler.of("), "Must use of() for both handlers: " + java);
        // The handler must contain actual MOVE statements, not just a comment
        assertFalse(java.contains("/* size error */"),
            "Must emit real handler, not stub comment: " + java);
        assertTrue(java.contains("\"WS-FLAG\""),
            "Handler must reference WS-FLAG: " + java);
    }

    @Test
    void subtractWithOnSizeErrorOnly() {
        String java = transpile("""
                SUBTRACT WS-A FROM WS-B
                    ON SIZE ERROR
                        MOVE "E" TO WS-FLAG
                END-SUBTRACT.
                STOP RUN.
            """);
        assertTrue(java.contains("SizeErrorHandler.onError("),
            "Should use onError() for error-only handler: " + java);
        assertTrue(java.contains("\"WS-FLAG\""),
            "Handler must reference WS-FLAG: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  MOVE — multiple targets
    // ═══════════════════════════════════════════════════════════════

    @Test
    void moveToMultipleTargets() {
        // MOVE x TO a b c → each target gets the value
        String java = transpile("""
                MOVE WS-A TO WS-B WS-C WS-RESULT.
                STOP RUN.
            """);
        int moveCount = countOccurrences(java, ".move(\"WS-");
        assertTrue(moveCount >= 3,
            "Should emit move for each of 3 targets, found " + moveCount + ": " + java);
    }

    @Test
    void moveLiteralToMultipleTargets() {
        String java = transpile("""
                MOVE ZEROS TO WS-A WS-B WS-C WS-RESULT.
                STOP RUN.
            """);
        int moveCount = countOccurrences(java, ".move(\"WS-");
        assertTrue(moveCount >= 4,
            "Should emit move for each of 4 targets, found " + moveCount + ": " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  REDEFINES — overlaid storage
    // ═══════════════════════════════════════════════════════════════

    @Test
    void redefinesEmitsOverlay() {
        String java = transpile("""
                MOVE "20260501" TO WS-DATE-FULL.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-DATE-FULL   PIC X(8).
               05 WS-DATE-PARTS REDEFINES WS-DATE-FULL.
                  10 WS-YEAR     PIC X(4).
                  10 WS-MONTH    PIC X(2).
                  10 WS-DAY      PIC X(2).
               05 WS-A           PIC S9(7)V99.
            """);
        assertTrue(java.contains(".redefines(\"WS-DATE-FULL\""),
            "Must emit redefines for overlay: " + java);
        assertFalse(java.contains(".group(\"WS-DATE-PARTS\""),
            "Should NOT emit as regular group: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Multiple GIVING targets
    // ═══════════════════════════════════════════════════════════════

    @Test
    void addGivingMultipleTargets() {
        // ADD a b GIVING c d → both c and d get a + b
        String java = transpile("""
                ADD WS-A WS-B GIVING WS-C WS-RESULT.
                STOP RUN.
            """);
        int givingCount = countOccurrences(java, ".giving(");
        assertTrue(givingCount >= 2,
            "Should emit giving for each of 2 targets, found " + givingCount + ": " + java);
        assertTrue(java.contains("\"WS-C\""), "Must target WS-C: " + java);
        assertTrue(java.contains("\"WS-RESULT\""), "Must target WS-RESULT: " + java);
    }

    @Test
    void subtractGivingMultipleTargets() {
        // SUBTRACT a FROM b GIVING c d → both c and d get b - a
        String java = transpile("""
                SUBTRACT WS-A FROM WS-B GIVING WS-C WS-RESULT.
                STOP RUN.
            """);
        int givingCount = countOccurrences(java, ".giving(");
        assertTrue(givingCount >= 2,
            "Should emit giving for each of 2 targets, found " + givingCount + ": " + java);
    }

    @Test
    void multiplyGivingMultipleTargets() {
        // MULTIPLY a BY b GIVING c d → both c and d get a * b
        String java = transpile("""
                MULTIPLY WS-A BY WS-B GIVING WS-C WS-RESULT.
                STOP RUN.
            """);
        int givingCount = countOccurrences(java, ".giving(");
        assertTrue(givingCount >= 2,
            "Should emit giving for each of 2 targets, found " + givingCount + ": " + java);
    }

    @Test
    void divideGivingMultipleTargets() {
        // DIVIDE a BY b GIVING c d → both c and d get a / b
        String java = transpile("""
                DIVIDE WS-A BY WS-B GIVING WS-C WS-RESULT.
                STOP RUN.
            """);
        int givingCount = countOccurrences(java, ".giving(");
        assertTrue(givingCount >= 2,
            "Should emit giving for each of 2 targets, found " + givingCount + ": " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADD with literal sources
    // ═══════════════════════════════════════════════════════════════

    @Test
    void addLiteralAndFieldGiving() {
        // ADD 100 WS-B GIVING WS-RESULT → result = 100 + WS-B
        String java = transpile("""
                ADD 100 WS-B GIVING WS-RESULT.
                STOP RUN.
            """);
        assertTrue(java.contains("Decimal.of(\"100\")"), "Must emit literal 100: " + java);
        assertTrue(java.contains("getDecimal(\"WS-B\")"), "Must read WS-B: " + java);
        assertTrue(java.contains(".giving("), "Must have GIVING: " + java);
    }

    @Test
    void addLiteralToField() {
        // ADD 1 TO WS-A WS-B → WS-A += 1; WS-B += 1
        String java = transpile("""
                ADD 1 TO WS-A WS-B.
                STOP RUN.
            """);
        assertTrue(java.contains("Decimal.of(\"1\")"), "Must emit literal 1: " + java);
        int addCount = countOccurrences(java, ".add(\"WS-");
        assertTrue(addCount >= 2,
            "Should emit add for both targets, found " + addCount + ": " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Unsupported verbs — generate informative uncompilable Java
    // ═══════════════════════════════════════════════════════════════

    @Test
    void startVerbEmitsFileStart() {
        // START is now a real verb — should emit a file.start() call
        String java = transpile("""
                START WS-A.
                STOP RUN.
            """);
        assertTrue(java.contains(".start("),
            "START must emit a file start call: " + java);
    }

    @Test
    void unknownVerbGeneratesMarker() {
        // FOOBAR is a completely unknown verb
        String java = transpile("""
                FOOBAR SOMETHING.
                STOP RUN.
            """);
        assertTrue(java.contains("COBOL4J_UNSUPPORTED_FOOBAR"),
            "Unknown verb must generate marker: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Newly implemented verbs — verify they generate compilable Java
    // ═══════════════════════════════════════════════════════════════

    @Test
    void cancelEmitsWarning() {
        String java = transpile("""
                CANCEL "SUBPROG".
                STOP RUN.
            """);
        assertTrue(java.contains("LOG.warning") && java.contains("CANCEL"),
            "CANCEL must emit a LOG.warning: " + java);
    }

    @Test
    void alterEmitsWarning() {
        String java = transpile("""
                ALTER WS-A TO PROCEED TO WS-B.
                STOP RUN.
            """);
        assertTrue(java.contains("LOG.warning") && java.contains("ALTER"),
            "ALTER must emit a LOG.warning: " + java);
    }

    @Test
    void generateEmitsWarning() {
        String java = transpile("""
                GENERATE WS-A.
                STOP RUN.
            """);
        assertTrue(java.contains("LOG.warning") && java.contains("GENERATE"),
            "GENERATE must emit LOG.warning for Report Writer: " + java);
    }

    @Test
    void terminateEmitsWarning() {
        String java = transpile("""
                TERMINATE WS-A.
                STOP RUN.
            """);
        assertTrue(java.contains("LOG.warning") && java.contains("TERMINATE"),
            "TERMINATE must emit LOG.warning for Report Writer: " + java);
    }

    @Test
    void setIndexToEmitsMove() {
        String java = transpile("""
                SET WS-A TO 5.
                STOP RUN.
            """);
        assertTrue(java.contains(".move(\"WS-A\""),
            "SET idx TO value must emit move: " + java);
    }

    @Test
    void releaseEmitsSort() {
        String java = transpile("""
                RELEASE WS-A.
                STOP RUN.
            """);
        assertTrue(java.contains("sortInput.release()"),
            "RELEASE must emit sortInput.release(): " + java);
    }

    @Test
    void returnStmtEmitsSort() {
        String java = transpile("""
                RETURN WS-A INTO WS-B.
                STOP RUN.
            """);
        assertTrue(java.contains("sortOutput.returnRecord()"),
            "RETURN must emit sortOutput.returnRecord(): " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  GOBACK / EXIT PROGRAM — synonyms for STOP RUN
    // ═══════════════════════════════════════════════════════════════

    @Test
    void gobackEmitsStopRun() {
        String java = transpile("""
                GOBACK.
            """);
        assertTrue(java.contains("ctx.stopRun()"),
            "GOBACK must emit ctx.stopRun(): " + java);
    }

    @Test
    void exitProgramEmitsStopRun() {
        String java = transpile("""
                EXIT PROGRAM.
            """);
        assertTrue(java.contains("ctx.stopRun()"),
            "EXIT PROGRAM must emit ctx.stopRun(): " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SET index UP/DOWN BY
    // ═══════════════════════════════════════════════════════════════

    @Test
    void setIndexUpBy() {
        String java = transpile("""
                SET WS-A UP BY 1.
                STOP RUN.
            """);
        assertTrue(java.contains(".add(\"WS-A\""),
            "SET UP BY must emit .add(): " + java);
    }

    @Test
    void setIndexDownBy() {
        String java = transpile("""
                SET WS-A DOWN BY 1.
                STOP RUN.
            """);
        assertTrue(java.contains(".subtract(\"WS-A\""),
            "SET DOWN BY must emit .subtract(): " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String transpile(String procedureStatements) {
        return transpile(procedureStatements, """
            01 WS-REC.
               05 WS-A       PIC S9(7)V99.
               05 WS-B       PIC S9(7)V99.
               05 WS-C       PIC S9(7)V99.
               05 WS-RESULT  PIC S9(9)V99.
               05 WS-FLAG    PIC X.
            """);
    }

    private String transpile(String procedureStatements, String dataEntries) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. ARITH-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            """ + dataEntries + """
            PROCEDURE DIVISION.
            MAIN-PARA.
            """ + procedureStatements;
        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Transpilation failed: " + diag.errors());
        return java;
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
