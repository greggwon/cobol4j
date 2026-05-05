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
 * Tests for compound conditions: AND, OR, NOT, and combinations.
 */
class ConditionTest {

    // ═══════════════════════════════════════════════════════════════
    //  AND
    // ═══════���═══════════════════════════════════════════════════════

    @Test
    void ifWithAnd() {
        String java = transpile("""
                IF WS-A > 10 AND WS-B < 20
                    DISPLAY "BOTH"
                END-IF.
                STOP RUN.
            """);

        assertTrue(java.contains("&&"), "Should contain && for AND: " + extract(java));
        assertTrue(java.contains("greaterThan"), "Should have greaterThan: " + extract(java));
        assertTrue(java.contains("lessThan"), "Should have lessThan: " + extract(java));
    }

    @Test
    void ifWithMultipleAnd() {
        String java = transpile("""
                IF WS-A > 0 AND WS-B > 0 AND WS-C > 0
                    DISPLAY "ALL POSITIVE"
                END-IF.
                STOP RUN.
            """);

        // Should have two && operators (three conditions chained)
        int andCount = countOccurrences(java, "&&");
        assertEquals(2, andCount, "Should have 2 AND connectors: " + extract(java));
    }

    // ═══════════════════════════════════════════════════════════════
    //  OR
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ifWithOr() {
        String java = transpile("""
                IF WS-STATUS = "A" OR WS-STATUS = "B"
                    DISPLAY "VALID"
                END-IF.
                STOP RUN.
            """);

        assertTrue(java.contains("||"), "Should contain || for OR: " + extract(java));
    }

    @Test
    void ifWithMultipleOr() {
        String java = transpile("""
                IF WS-X = 1 OR WS-X = 2 OR WS-X = 3
                    DISPLAY "MATCH"
                END-IF.
                STOP RUN.
            """);

        // Count || only in lines that are actual IF conditions, not infrastructure
        int orCount = 0;
        for (String line : java.split("\n")) {
            if (line.trim().startsWith("if (")) {
                orCount += countOccurrences(line, "||");
            }
        }
        assertEquals(2, orCount, "Should have 2 OR connectors in IF condition: " + extract(java));
    }

    // ═══════════════════════════════════════════════════════════════
    //  AND + OR combined
    // ═══════════════════════════════════════════════════════════════

    @Test
    void andOrCombined() {
        // AND has higher precedence than OR:
        // A > 10 AND B < 20 OR C = 0
        // should be: (A > 10 AND B < 20) OR (C = 0)
        String java = transpile("""
                IF WS-A > 10 AND WS-B < 20 OR WS-C = 0
                    DISPLAY "COMPLEX"
                END-IF.
                STOP RUN.
            """);

        assertTrue(java.contains("&&"), "Should have AND: " + extract(java));
        assertTrue(java.contains("||"), "Should have OR: " + extract(java));
    }

    // ═══════════════════════════════════════════════════════════════
    //  NOT
    // ═══════════════════════════════════════════════════════════════

    @Test
    void notConditionName() {
        String java = transpile("""
                IF NOT ACTIVE
                    DISPLAY "NOT ACTIVE"
                END-IF.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-STATUS PIC X.
                  88 ACTIVE VALUE "A".
            """);

        assertTrue(java.contains("!"), "Should negate with !: " + extract(java));
        assertTrue(java.contains(".is(\"ACTIVE\")"), "Should test condition: " + extract(java));
    }

    @Test
    void notComparison() {
        String java = transpile("""
                IF WS-A NOT GREATER THAN 100
                    DISPLAY "NOT BIG"
                END-IF.
                STOP RUN.
            """);

        assertTrue(java.contains("!"), "Should negate: " + extract(java));
        assertTrue(java.contains("greaterThan"), "Should have greaterThan: " + extract(java));
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERFORM UNTIL with compound condition
    // ═══════════════════════════════════════════════════════════════

    @Test
    void performUntilWithAnd() {
        String java = transpile("""
                PERFORM PROCESS-LOOP UNTIL WS-EOF = "Y" OR WS-COUNT > 100.
                STOP RUN.
            """);

        assertTrue(java.contains("||"), "UNTIL with OR should have ||: " + extract(java));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String transpile(String procedureStatements) {
        return transpile(procedureStatements, """
            01 WS-REC.
               05 WS-A      PIC S9(5).
               05 WS-B      PIC S9(5).
               05 WS-C      PIC S9(5).
               05 WS-X      PIC 9(3).
               05 WS-STATUS PIC X.
               05 WS-EOF    PIC X.
               05 WS-COUNT  PIC 9(5).
            """);
    }

    private String transpile(String procedureStatements, String dataEntries) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. COND-TEST.
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

    private String extract(String java) {
        StringBuilder relevant = new StringBuilder();
        for (String line : java.split("\n")) {
            String trimmed = line.trim();
            // Only capture condition-related lines inside paragraph bodies
            if ((trimmed.startsWith("if (") || trimmed.contains("performUntil"))
                && !trimmed.contains("LOGGER_NAME")) {
                relevant.append(trimmed).append("\n");
            }
        }
        return relevant.length() > 0 ? relevant.toString() : java.substring(Math.max(0, java.length() - 300));
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
