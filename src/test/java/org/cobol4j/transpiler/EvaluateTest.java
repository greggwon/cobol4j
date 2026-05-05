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

class EvaluateTest {

    // ═══════════════════════════════════════════════════════════════
    //  EVALUATE field WHEN literal
    // ═══════════════════════════════════════════════════════════════

    @Test
    void evaluateFieldWhenLiteral() {
        String java = transpile("""
                EVALUATE WS-STATUS
                    WHEN "A"
                        DISPLAY "ACTIVE"
                    WHEN "I"
                        DISPLAY "INACTIVE"
                    WHEN OTHER
                        DISPLAY "UNKNOWN"
                END-EVALUATE.
                STOP RUN.
            """);

        assertNotNull(java, "Should transpile");
        assertTrue(java.contains("evaluate("), "Should have evaluate: " + java);
        assertTrue(java.contains(".when("), "Should have .when(): " + java);
        assertTrue(java.contains("whenOther"), "Should have whenOther: " + java);
        assertTrue(java.contains("ACTIVE"), "Should have ACTIVE display: " + java);
        assertTrue(java.contains("INACTIVE"), "Should have INACTIVE display: " + java);
        assertTrue(java.contains("UNKNOWN"), "Should have UNKNOWN display: " + java);
    }

    @Test
    void evaluateFieldWhenLiteralNoOther() {
        String java = transpile("""
                EVALUATE WS-STATUS
                    WHEN "A"
                        DISPLAY "FOUND A"
                    WHEN "B"
                        DISPLAY "FOUND B"
                END-EVALUATE.
                STOP RUN.
            """);

        assertNotNull(java, "Should transpile");
        assertTrue(java.contains(".when("), "Should have when clauses: " + java);
        assertFalse(java.contains("whenOther"), "Should NOT have whenOther: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  EVALUATE TRUE WHEN condition
    // ═══════════════════════════════════════════════════════════════

    @Test
    void evaluateTrueWhenConditionName() {
        String java = transpile("""
                EVALUATE TRUE
                    WHEN ACTIVE
                        DISPLAY "IS ACTIVE"
                    WHEN INACTIVE
                        DISPLAY "IS INACTIVE"
                    WHEN OTHER
                        DISPLAY "UNKNOWN STATUS"
                END-EVALUATE.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-STATUS PIC X.
                  88 ACTIVE   VALUE "A".
                  88 INACTIVE VALUE "I".
            """);

        assertNotNull(java, "Should transpile");
        assertTrue(java.contains("evaluateTrue()"), "Should use evaluateTrue: " + java);
        assertTrue(java.contains("whenTrue"), "Should have whenTrue: " + java);
        // The condition should generate a boolean expression, not a string
        assertFalse(java.contains("() -> \"ACTIVE\""),
            "Should NOT generate string literal for condition name: " + java);
    }

    @Test
    void evaluateTrueWhenMultipleActions() {
        String java = transpile("""
                EVALUATE TRUE
                    WHEN ACTIVE
                        MOVE "Y" TO WS-FLAG
                        DISPLAY "ACTIVE"
                    WHEN OTHER
                        MOVE "N" TO WS-FLAG
                END-EVALUATE.
                STOP RUN.
            """, """
            01 WS-REC.
               05 WS-STATUS PIC X.
                  88 ACTIVE VALUE "A".
               05 WS-FLAG PIC X.
            """);

        assertNotNull(java, "Should transpile");
        // WHEN body should have multiple statements
        assertTrue(java.contains("ACTIVE") && java.contains("WS-FLAG"),
            "Should have both statements in WHEN body: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  EVALUATE doesn't consume surrounding statements
    // ═══════════════════════════════════════════════════════════════

    @Test
    void evaluateDoesNotConsumeFollowingStatements() {
        String java = transpile("""
                DISPLAY "BEFORE".
                EVALUATE WS-STATUS
                    WHEN "A"
                        DISPLAY "IN EVAL"
                END-EVALUATE.
                DISPLAY "AFTER".
                STOP RUN.
            """);

        assertNotNull(java, "Should transpile");
        assertTrue(java.contains("BEFORE"), "Should have BEFORE: " + java);
        assertTrue(java.contains("IN EVAL"), "Should have IN EVAL: " + java);
        assertTrue(java.contains("AFTER"), "Should have AFTER: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String transpile(String procedureStatements) {
        return transpile(procedureStatements, """
            01 WS-REC.
               05 WS-STATUS PIC X.
               05 WS-FLAG   PIC X.
            """);
    }

    private String transpile(String procedureStatements, String dataEntries) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. EVAL-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            """ + dataEntries + """
            PROCEDURE DIVISION.
            MAIN-PARA.
            """ + procedureStatements;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        if (java == null) {
            fail("Transpilation failed: " + diag.errors());
        }
        return java;
    }
}
