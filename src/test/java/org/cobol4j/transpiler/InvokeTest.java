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

class InvokeTest {

    @Test
    void invokeMethodOnObject() {
        String java = transpile("""
                INVOKE WS-CALCULATOR "calculateTax"
                    USING WS-AMOUNT
                    RETURNING WS-TAX.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains(".calculateTax("), "Should emit method call: " + relevant(java));
        assertTrue(java.contains("WS-TAX"), "Should assign to RETURNING field: " + relevant(java));
    }

    @Test
    void invokeMethodNoReturn() {
        String java = transpile("""
                INVOKE WS-PRINTER "printReport" USING WS-DATA.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains(".printReport("), "Should emit method call: " + relevant(java));
    }

    @Test
    void invokeConstructor() {
        // INVOKE ClassName "new" RETURNING object
        String java = transpile("""
                INVOKE TAX-SERVICE "new" RETURNING WS-SERVICE.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("new TaxService("), "Should emit constructor: " + relevant(java));
        assertTrue(java.contains("var "), "Should declare variable: " + relevant(java));
    }

    @Test
    void invokeNoArgs() {
        String java = transpile("""
                INVOKE WS-CONNECTION "close".
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains(".close("), "Should emit no-arg method call: " + relevant(java));
    }

    @Test
    void invokeMultipleArgs() {
        String java = transpile("""
                INVOKE WS-CALC "compute"
                    USING WS-A WS-B WS-C
                    RETURNING WS-RESULT.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains(".compute("), "Should emit method: " + relevant(java));
        assertTrue(java.contains("WS-A"), "Should pass WS-A: " + relevant(java));
        assertTrue(java.contains("WS-B"), "Should pass WS-B: " + relevant(java));
        assertTrue(java.contains("WS-C"), "Should pass WS-C: " + relevant(java));
    }

    @Test
    void invokePreservesMethodName() {
        // Method name is whatever the programmer wrote — no case transformation
        String java = transpile("""
                INVOKE WS-OBJ "getBalance" RETURNING WS-BAL.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains(".getBalance("), "Should preserve method name as-is: " + relevant(java));
    }

    // ═════════════════════════════════════════════════════════════

    private String transpile(String stmts) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. OO-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS.
               05 WS-AMOUNT      PIC S9(7)V99.
               05 WS-TAX         PIC S9(7)V99.
               05 WS-BAL         PIC S9(7)V99.
               05 WS-A           PIC S9(5).
               05 WS-B           PIC S9(5).
               05 WS-C           PIC S9(5).
               05 WS-RESULT      PIC S9(7)V99.
               05 WS-DATA        PIC X(100).
               05 WS-CALCULATOR  PIC X(256).
               05 WS-PRINTER     PIC X(256).
               05 WS-CONNECTION  PIC X(256).
               05 WS-CALC        PIC X(256).
               05 WS-OBJ         PIC X(256).
               05 WS-SERVICE     PIC X(256).
            PROCEDURE DIVISION.
            MAIN-PARA.
            """ + stmts;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        if (java == null) fail("Transpilation failed: " + diag.errors());
        return java;
    }

    private String relevant(String java) {
        StringBuilder sb = new StringBuilder();
        for (String line : java.split("\n")) {
            String t = line.trim();
            if (t.contains("Invoke") || t.contains("invoke") || t.contains(".calculate")
                || t.contains(".print") || t.contains(".close") || t.contains(".compute")
                || t.contains(".get") || t.contains("new Tax") || t.contains("var ")) {
                sb.append(t).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : java.substring(Math.max(0, java.length() - 300));
    }
}
