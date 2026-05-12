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
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TranspilerTest {

    // ═══════════════════════════════════════════════════════════════
    //  LEXER TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    void lexBasicTokens() {
        List<Token> tokens = Lexer.tokenize("MOVE \"HELLO\" TO WS-NAME.", false);

        assertEquals(Token.Type.WORD, tokens.get(0).type());
        assertEquals("MOVE", tokens.get(0).value());
        assertEquals(Token.Type.STRING, tokens.get(1).type());
        assertEquals("HELLO", tokens.get(1).value());
        assertEquals(Token.Type.WORD, tokens.get(2).type());
        assertEquals("TO", tokens.get(2).value());
        assertEquals(Token.Type.WORD, tokens.get(3).type());
        assertEquals("WS-NAME", tokens.get(3).value());
        assertEquals(Token.Type.PERIOD, tokens.get(4).type());
    }

    @Test
    void lexNumericLiterals() {
        List<Token> tokens = Lexer.tokenize("ADD 100.50 TO BALANCE.", false);

        assertEquals("ADD", tokens.get(0).value());
        assertEquals(Token.Type.NUMBER, tokens.get(1).type());
        assertEquals("100.50", tokens.get(1).value());
    }

    @Test
    void lexPicString() {
        List<Token> tokens = Lexer.tokenize("PIC S9(7)V99.", false);

        assertEquals("PIC", tokens.get(0).value());
        // PIC string tokenizes as WORD + LPAREN + NUMBER + RPAREN + WORD
        // or as a single WORD depending on spacing
        assertTrue(tokens.size() >= 2);
    }

    @Test
    void lexHandlesFixedFormat() {
        String fixedFormat =
            "000100 IDENTIFICATION DIVISION.                                        \n" +
            "000200 PROGRAM-ID. TESTPROG.                                           \n" +
            "000300*THIS IS A COMMENT                                               \n" +
            "000400 DATA DIVISION.                                                  \n";

        List<Token> tokens = Lexer.tokenize(fixedFormat, true);

        // Should skip comment line and parse the rest
        assertTrue(tokens.stream().anyMatch(t -> t.is("IDENTIFICATION")));
        assertTrue(tokens.stream().anyMatch(t -> t.is("TESTPROG")));
        assertTrue(tokens.stream().anyMatch(t -> t.is("DATA")));
        // Comment should not produce tokens
        assertFalse(tokens.stream().anyMatch(t -> t.value().contains("COMMENT")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  PARSER TESTS
    // ═══════════════════════════════════════════════════════════════

    @Test
    void parseProgramId() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. CUSTOMER-RPT.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-NAME PIC X(20).
            PROCEDURE DIVISION.
            MAIN-LOGIC.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);
        assertEquals("CUSTOMER-RPT", prog.programId());
    }

    @Test
    void parseDataDivision() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 CUSTOMER-RECORD.
               05 CUST-NAME      PIC X(20).
               05 CUST-BALANCE   PIC S9(7)V99 COMP-3.
               05 CUST-STATUS    PIC X.
                  88 ACTIVE       VALUE "A".
                  88 INACTIVE     VALUE "I".
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);

        assertFalse(prog.dataEntries().isEmpty());
        // Find CUST-NAME entry
        var custName = prog.dataEntries().stream()
            .filter(e -> e.name().equals("CUST-NAME"))
            .findFirst().orElse(null);
        assertNotNull(custName);
        assertEquals(5, custName.level());
        assertNotNull(custName.pic());
        assertTrue(custName.pic().contains("X"));

        // Find CUST-STATUS with 88-levels
        var custStatus = prog.dataEntries().stream()
            .filter(e -> e.name().equals("CUST-STATUS"))
            .findFirst().orElse(null);
        assertNotNull(custStatus);
        assertFalse(custStatus.conditions().isEmpty());
    }

    @Test
    void parseProcedureDivision() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-TOTAL PIC S9(7)V99.
               05 WS-COUNT PIC 9(3).
            PROCEDURE DIVISION.
            MAIN-LOGIC.
                MOVE 0 TO WS-TOTAL.
                PERFORM PROCESS-LOOP UNTIL WS-COUNT = 10.
                DISPLAY "Done: " WS-TOTAL.
                STOP RUN.
            PROCESS-LOOP.
                ADD 100 TO WS-TOTAL.
                ADD 1 TO WS-COUNT.
            """;

        CobolProgram prog = Parser.parse(source);

        assertEquals(2, prog.paragraphs().size());
        assertEquals("MAIN-LOGIC", prog.paragraphs().get(0).name());
        assertEquals("PROCESS-LOOP", prog.paragraphs().get(1).name());

        // Check MAIN-LOGIC has the right statements
        var mainStmts = prog.paragraphs().get(0).statements();
        assertTrue(mainStmts.size() >= 3);
        assertInstanceOf(Statement.Move.class, mainStmts.get(0));
        assertInstanceOf(Statement.Perform.class, mainStmts.get(1));
        assertInstanceOf(Statement.Display.class, mainStmts.get(2));
    }

    // ═══════════════════════════════════════════════════════════════
    //  END-TO-END TRANSPILATION
    // ═══════════════════════════════════════════════════════════════

    @Test
    void endToEndTranspilation() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIMPLE-CALC.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-RECORD.
               05 WS-A       PIC S9(5)V99.
               05 WS-B       PIC S9(5)V99.
               05 WS-RESULT  PIC S9(7)V99.
               05 WS-STATUS  PIC X.
                  88 DONE     VALUE "Y".
            PROCEDURE DIVISION.
            MAIN-LOGIC.
                MOVE 100.00 TO WS-A.
                MOVE 200.50 TO WS-B.
                ADD WS-A TO WS-B GIVING WS-RESULT.
                DISPLAY "Result: " WS-RESULT.
                SET DONE TO TRUE.
                STOP RUN.
            """;

        String java = Transpiler.transpile(cobol);

        // Verify the output is reasonable Java
        assertNotNull(java);
        assertTrue(java.contains("Record.define"), "Should contain Record.define");
        assertTrue(java.contains("Program.define"), "Should contain Program.define");
        assertTrue(java.contains("MAIN-LOGIC"), "Should contain paragraph name");
        assertTrue(java.contains("WS-A"), "Should reference field names");
        assertTrue(java.contains(".pic("), "Should define PIC fields");
        assertTrue(java.contains("stopRun"), "Should have STOP RUN");
        assertTrue(java.contains("class SimpleCalc"), "Should generate class name");

        // Print the output for inspection
        System.out.println("=== Generated Java ===");
        System.out.println(java);
        System.out.println("=== End ===");
    }

    @Test
    void transpileIfElse() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. IF-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-SCORE PIC 9(3).
               05 WS-GRADE PIC X.
            PROCEDURE DIVISION.
            MAIN-PARA.
                MOVE 85 TO WS-SCORE.
                IF WS-SCORE GREATER THAN OR EQUAL TO 90
                    MOVE "A" TO WS-GRADE
                ELSE
                    MOVE "B" TO WS-GRADE
                END-IF.
                STOP RUN.
            """;

        String java = Transpiler.transpile(cobol);
        System.out.println("=== IF TEST OUTPUT ===\n" + java + "\n=== END ===");
        assertTrue(java.contains("if") || java.contains("IF"), "Should contain if statement, got:\n" + java);
        assertTrue(java.contains("else") || java.contains("ELSE"), "Should contain else, got:\n" + java);
    }

    @Test
    void transpilePerformVarying() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. LOOP-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-IDX   PIC 9(3).
               05 WS-TOTAL PIC 9(5).
            PROCEDURE DIVISION.
            MAIN-PARA.
                PERFORM ADD-LOOP VARYING WS-IDX FROM 1 BY 1
                    UNTIL WS-IDX GREATER THAN 10.
                STOP RUN.
            ADD-LOOP.
                ADD WS-IDX TO WS-TOTAL.
            """;

        String java = Transpiler.transpile(cobol);
        assertTrue(java.contains("performVarying"));
        assertTrue(java.contains("WS-IDX"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  CALL STATEMENT TRANSPILATION
    // ═══════════════════════════════════════════════════════════════

    @Test
    void transpileCallOpen() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. CALL-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-PATH   PIC X(50).
               05 WS-FD     PIC S9(9).
               05 WS-RESULT PIC X(100).
            PROCEDURE DIVISION.
            MAIN-PARA.
                MOVE "/proc/cpuinfo" TO WS-PATH.
                CALL "open" USING BY CONTENT WS-PATH
                    BY VALUE 0
                    RETURNING WS-FD.
                CALL "close" USING BY VALUE WS-FD.
                STOP RUN.
            """;

        String java = Transpiler.transpile(cobol);
        assertTrue(java.contains("sys.open("), "Should emit sys.open: " + java);
        assertTrue(java.contains("sys.close("), "Should emit sys.close: " + java);
    }

    @Test
    void transpileCallGetenv() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. ENV-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-HOME PIC X(100).
            PROCEDURE DIVISION.
            MAIN-PARA.
                CALL "getenv" USING BY CONTENT "HOME"
                    RETURNING WS-HOME.
                DISPLAY WS-HOME.
                STOP RUN.
            """;

        String java = Transpiler.transpile(cobol);
        assertTrue(java.contains("sys.getenv("), "Should emit sys.getenv: " + java);
    }

    @Test
    void transpileCallSystem() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SYS-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-RC PIC S9(9).
            PROCEDURE DIVISION.
            MAIN-PARA.
                CALL "system" USING BY CONTENT "ls -la"
                    RETURNING WS-RC.
                STOP RUN.
            """;

        String java = Transpiler.transpile(cobol);
        assertTrue(java.contains("sys.system("), "Should emit sys.system: " + java);
    }

    @Test
    void transpileDiagnosticsCollectWarnings() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. DIAG-TEST.
            ENVIRONMENT DIVISION.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-X PIC X(10).
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should succeed (no errors)");
        // ENVIRONMENT DIVISION skipped = info diagnostic
        assertFalse(diag.isEmpty(), "Should have at least one diagnostic");
    }

    @Test
    void transpileCallUnknownProducesWarning() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. UNK-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-X PIC X(10).
            PROCEDURE DIVISION.
            MAIN-PARA.
                CALL "ioctl" USING BY VALUE WS-X.
                STOP RUN.
            """;

        // Unknown CALL targets now produce a LOG.warning in the generated code
        // instead of failing transpilation — the user can replace with a real call
        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Unknown CALL should still produce output");
        assertTrue(java.contains("LOG.warning") && java.contains("ioctl"),
            "Should emit LOG.warning about ioctl: " + java);
    }

    @Test
    void multipleErrorsAllCollectedWithoutCascading() {
        System.out.println("--- Expected errors below (testing error recovery with unknown verbs) ---");
        try {
        // Use made-up verbs that don't exist in any COBOL dialect
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. MULTI-ERR.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-X PIC X(10).
            PROCEDURE DIVISION.
            MAIN-PARA.
                FROBULATE WS-X USING SOME ARGS HERE.
                TRANSMOGIFY WS-X INTO SOMETHING ELSE.
                DISPLAY "Between errors".
                STOP RUN.
            """;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);

        // Unknown verbs now generate warnings (not errors) and produce
        // COBOL4J_UNSUPPORTED markers in the output — the user sees exactly
        // where the gaps are and can fill them in.
        assertNotNull(java, "Should produce output with unsupported markers");
        assertTrue(java.contains("COBOL4J_UNSUPPORTED_FROBULATE"),
            "Must generate marker for FROBULATE: " + java);
        assertTrue(java.contains("COBOL4J_UNSUPPORTED_TRANSMOGIFY"),
            "Must generate marker for TRANSMOGIFY: " + java);

        // Each unknown verb should produce a warning — not cascading
        long warnCount = diag.warnings().size();
        assertTrue(warnCount >= 2, "Should have warnings for unknown verbs: " + diag.warnings());

        // DISPLAY and STOP RUN should still work — they're recognized verbs
        // that were parsed after error recovery skipped the unknown ones
        assertTrue(java.contains("ctx.display("),
            "DISPLAY should still be emitted: " + java);
        assertTrue(java.contains("ctx.stopRun()"),
            "STOP RUN should still be emitted: " + java);
        } finally {
            System.out.println("--- End expected errors ---");
        }
    }
}
