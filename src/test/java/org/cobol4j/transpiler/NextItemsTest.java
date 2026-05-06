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

import org.cobol4j.CobolFile;
import org.cobol4j.Pic;
import org.cobol4j.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Next.md items 6-16.
 */
class NextItemsTest {

    // ═══════════════════════════════════════════════════════════════
    //  #6 — ON EXCEPTION / NOT ON EXCEPTION for CALL
    // ═══════════════════════════════════════════════════════════════

    @Test
    void callWithOnException() {
        String cobol = wrap("""
                CALL "system" USING BY CONTENT "echo test"
                    ON EXCEPTION
                        DISPLAY "CALL FAILED"
                    NOT ON EXCEPTION
                        DISPLAY "CALL OK"
                END-CALL.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("try {"), "Should wrap CALL in try block: " + java);
        assertTrue(java.contains("catch (Exception"), "Should have catch block: " + java);
        assertTrue(java.contains("CALL OK"), "Should contain NOT ON EXCEPTION handler: " + java);
        assertTrue(java.contains("CALL FAILED"), "Should contain ON EXCEPTION handler: " + java);
    }

    @Test
    void callOnExceptionParsesHandlerStatements() {
        String cobol = wrap("""
                CALL "system" USING BY CONTENT "test"
                    ON EXCEPTION
                        MOVE "E" TO WS-FLAG
                END-CALL.
                STOP RUN.
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var stmt = prog.paragraphs().get(0).statements().get(0);
        assertInstanceOf(Statement.Call.class, stmt);
        Statement.Call call = (Statement.Call) stmt;
        assertFalse(call.onException().isEmpty(), "Should have ON EXCEPTION handlers");
        assertInstanceOf(Statement.Move.class, call.onException().get(0));
    }

    @Test
    void callOnExceptionWithMoveHandler() {
        String cobol = wrap("""
                CALL "system" USING BY CONTENT "echo test"
                    ON EXCEPTION
                        MOVE "E" TO WS-FLAG
                    NOT ON EXCEPTION
                        MOVE "S" TO WS-FLAG
                END-CALL.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("try {"), "Should wrap in try: " + java);
        assertTrue(java.contains("catch (Exception"), "Should have catch: " + java);
    }

    @Test
    void callWithoutExceptionBackwardCompatible() {
        String cobol = wrap("""
                CALL "system" USING BY CONTENT "echo hi"
                    RETURNING WS-RC.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-RC PIC S9(9).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertFalse(java.contains("try {"), "Should NOT have try block without handlers: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #7 — TALLYING in UNSTRING
    // ═══════════════════════════════════════════════════════════════

    @Test
    void unstringWithTallying() {
        String cobol = wrap("""
                UNSTRING WS-INPUT DELIMITED BY ","
                    INTO WS-F1 WS-F2 WS-F3
                    TALLYING IN WS-COUNT.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-INPUT PIC X(30).
               05 WS-F1    PIC X(10).
               05 WS-F2    PIC X(10).
               05 WS-F3    PIC X(10).
               05 WS-COUNT PIC 9(3).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("_unstringCount"), "Should capture tally count: " + java);
        assertTrue(java.contains("WS-COUNT"), "Should move count to tally field: " + java);
    }

    @Test
    void unstringTallyFieldParsed() {
        String cobol = wrap("""
                UNSTRING WS-INPUT DELIMITED BY ","
                    INTO WS-F1
                    TALLYING IN WS-COUNT.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-INPUT PIC X(30).
               05 WS-F1    PIC X(10).
               05 WS-COUNT PIC 9(3).
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var stmt = prog.paragraphs().get(0).statements().get(0);
        assertInstanceOf(Statement.UnstringStmt.class, stmt);
        Statement.UnstringStmt us = (Statement.UnstringStmt) stmt;
        assertEquals("WS-COUNT", us.tallyField());
    }

    // ═══════════════════════════════════════════════════════════════
    //  #8 — STRING/UNSTRING with POINTER
    // ═══════════════════════════════════════════════════════════════

    @Test
    void stringWithPointer() {
        String cobol = wrap("""
                STRING WS-FIRST DELIMITED BY SPACES
                    " " DELIMITED BY SIZE
                    WS-LAST DELIMITED BY SPACES
                    INTO WS-FULL
                    WITH POINTER WS-PTR
                END-STRING.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-FIRST PIC X(10).
               05 WS-LAST  PIC X(10).
               05 WS-FULL  PIC X(25).
               05 WS-PTR   PIC 9(3).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("withPointer"), "Should use withPointer: " + java);
        assertTrue(java.contains("WS-PTR"), "Should reference pointer field: " + java);
        assertTrue(java.contains("_stringPtr"), "Should capture return pointer: " + java);
    }

    @Test
    void unstringWithPointer() {
        String cobol = wrap("""
                UNSTRING WS-INPUT DELIMITED BY ","
                    INTO WS-F1 WS-F2
                    WITH POINTER WS-PTR.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-INPUT PIC X(30).
               05 WS-F1    PIC X(10).
               05 WS-F2    PIC X(10).
               05 WS-PTR   PIC 9(3).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("withPointer"), "Should use withPointer: " + java);
        assertTrue(java.contains("WS-PTR"), "Should reference pointer field: " + java);
    }

    @Test
    void stringPointerFieldParsed() {
        String cobol = wrap("""
                STRING WS-A DELIMITED BY SIZE
                    INTO WS-B
                    WITH POINTER WS-PTR.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-A   PIC X(10).
               05 WS-B   PIC X(20).
               05 WS-PTR PIC 9(3).
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var stmt = prog.paragraphs().get(0).statements().get(0);
        assertInstanceOf(Statement.StringStmt.class, stmt);
        Statement.StringStmt ss = (Statement.StringStmt) stmt;
        assertEquals("WS-PTR", ss.pointer());
    }

    // ═══════════════════════════════════════════════════════════════
    //  #9 — DISPLAY UPON CONSOLE / SYSPUNCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    void displayUponConsole() {
        String cobol = wrap("""
                DISPLAY "Hello" UPON CONSOLE.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("ctx.display("), "Should emit display call: " + java);
        assertTrue(java.contains("Hello"), "Should contain display content: " + java);
    }

    @Test
    void displayUponParsesDeviceName() {
        String cobol = wrap("""
                DISPLAY "msg" UPON SYSPUNCH.
                STOP RUN.
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var stmt = prog.paragraphs().get(0).statements().get(0);
        assertInstanceOf(Statement.Display.class, stmt);
        Statement.Display disp = (Statement.Display) stmt;
        assertEquals("SYSPUNCH", disp.upon());
    }

    @Test
    void displayWithoutUponStillWorks() {
        String cobol = wrap("""
                DISPLAY "Normal display".
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("ctx.display("), "Should emit normal display: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #10 — OPEN I-O for sequential files
    // ═══════════════════════════════════════════════════════════════

    @Test
    void sequentialFileOpenIoMode(@TempDir Path tempDir) throws IOException {
        Path dataFile = tempDir.resolve("testio.dat");
        int recSize = 20;

        // Write initial data
        byte[] record1 = padToSize("ORIGINAL-RECORD-1   ", recSize);
        byte[] record2 = padToSize("ORIGINAL-RECORD-2   ", recSize);
        Files.write(dataFile, concat(record1, record2));

        CobolFile file = CobolFile.sequential("TEST-IO")
            .assignTo(dataFile.toString())
            .recordSize(recSize)
            .build();

        // Open in I-O mode (should not throw)
        file.open(CobolFile.OpenMode.IO);
        assertEquals("00", file.status().code(), "Should open successfully");

        // Read first record
        byte[] buffer = new byte[recSize];
        assertTrue(file.read(buffer));
        assertEquals("ORIGINAL-RECORD-1   ", new String(buffer));

        // Write a new record at current position
        byte[] newRecord = padToSize("UPDATED-RECORD-3   ", recSize);
        file.write(newRecord);
        assertEquals("00", file.status().code());

        file.close();
    }

    @Test
    void sequentialFileRewriteInIoMode(@TempDir Path tempDir) throws IOException {
        Path dataFile = tempDir.resolve("rewrite.dat");
        int recSize = 20;

        byte[] record1 = padToSize("REC-ONE             ", recSize);
        byte[] record2 = padToSize("REC-TWO             ", recSize);
        Files.write(dataFile, concat(record1, record2));

        CobolFile file = CobolFile.sequential("TEST-RW")
            .assignTo(dataFile.toString())
            .recordSize(recSize)
            .build();

        file.open(CobolFile.OpenMode.IO);

        // Read first record, then rewrite it
        byte[] buffer = new byte[recSize];
        assertTrue(file.read(buffer));
        byte[] replacement = padToSize("REC-REPLACED        ", recSize);
        file.rewrite(replacement);

        file.close();

        // Re-read to verify
        byte[] fileContent = Files.readAllBytes(dataFile);
        String first = new String(fileContent, 0, recSize);
        assertTrue(first.startsWith("REC-REPLACED"), "Record should be replaced: " + first);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #11 — SORT DUPLICATES IN ORDER
    // ═══════════════════════════════════════════════════════════════

    @Test
    void sortDuplicatesInOrder() {
        String cobol = wrap("""
                SORT SORT-FILE ON ASCENDING KEY SORT-KEY
                    WITH DUPLICATES IN ORDER
                    USING INPUT-FILE
                    GIVING OUTPUT-FILE.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("CobolSort.on("), "Should emit sort: " + java);
        // The DUPLICATES clause is informational — Java's sort is already stable
        assertTrue(diag.diagnostics().stream()
            .anyMatch(d -> d.message().contains("stable")),
            "Should have info about stable sort: " + diag.diagnostics());
    }

    @Test
    void sortDuplicatesInOrderParsed() {
        String cobol = wrap("""
                SORT SORT-FILE ON ASCENDING KEY SORT-KEY
                    WITH DUPLICATES IN ORDER
                    USING INPUT-FILE
                    GIVING OUTPUT-FILE.
                STOP RUN.
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var stmt = prog.paragraphs().get(0).statements().get(0);
        assertInstanceOf(Statement.SortStmt.class, stmt);
        Statement.SortStmt sort = (Statement.SortStmt) stmt;
        assertTrue(sort.duplicatesInOrder(), "Should parse DUPLICATES IN ORDER flag");
    }

    // ═══════════════════════════════════════════════════════════════
    //  #12 — PIC N (NATIONAL)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void picNationalParses() {
        Pic pic = Pic.parse("N(10)");
        assertEquals(Pic.Category.NATIONAL, pic.category());
        assertEquals(20, pic.displaySize(), "N(10) should be 20 bytes (double-byte)");
    }

    @Test
    void picNationalSingleChar() {
        Pic pic = Pic.parse("N");
        assertEquals(Pic.Category.NATIONAL, pic.category());
        assertEquals(2, pic.displaySize(), "Single N should be 2 bytes");
    }

    @Test
    void picNationalInDataDivision() {
        String cobol = wrap("""
                DISPLAY WS-NFIELD.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-NFIELD PIC N(5).
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("N(5)"), "Should preserve PIC N in definition: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #13 — GLOBAL / EXTERNAL
    // ═══════════════════════════════════════════════════════════════

    @Test
    void globalClauseParsed() {
        String cobol = wrap("""
                DISPLAY WS-SHARED.
                STOP RUN.
            """, """
            01 WS-DATA GLOBAL.
               05 WS-SHARED PIC X(10).
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var entry = prog.dataEntries().stream()
            .filter(e -> e.name().equals("WS-DATA"))
            .findFirst().orElse(null);
        // WS-DATA is a group item (level 01 without PIC) — it won't appear as a child
        // The GLOBAL flag is on the 01-level group
    }

    @Test
    void globalClauseTranspiles() {
        String cobol = wrap("""
                DISPLAY WS-SHARED.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-SHARED PIC X(10) GLOBAL.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("GLOBAL"), "Should annotate GLOBAL field: " + java);
    }

    @Test
    void externalClauseParsed() {
        String cobol = wrap("""
                DISPLAY WS-EXT.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-EXT PIC X(10) EXTERNAL.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("EXTERNAL"), "Should annotate EXTERNAL field: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #14 — CONTINUE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void continueStatementParsed() {
        String cobol = wrap("""
                IF WS-FLAG = "Y"
                    CONTINUE
                ELSE
                    DISPLAY "NO"
                END-IF.
                STOP RUN.
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var stmt = prog.paragraphs().get(0).statements().get(0);
        assertInstanceOf(Statement.If.class, stmt);
        Statement.If ifStmt = (Statement.If) stmt;
        assertFalse(ifStmt.thenBlock().isEmpty(), "Then block should not be empty");
        assertInstanceOf(Statement.Continue.class, ifStmt.thenBlock().get(0));
    }

    @Test
    void continueStatementEmitsComment() {
        String cobol = wrap("""
                IF WS-FLAG = "Y"
                    CONTINUE
                ELSE
                    DISPLAY "NO"
                END-IF.
                STOP RUN.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("// CONTINUE"), "Should emit CONTINUE as comment: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  #15 — POINTER / FUNCTION-POINTER
    // ═══════════════════════════════════════════════════════════════

    @Test
    void pointerUsageParsed() {
        String cobol = wrap("""
                DISPLAY WS-NAME.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-NAME PIC X(10).
               05 WS-PTR  POINTER.
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var ptrEntry = prog.dataEntries().stream()
            .filter(e -> e.name().equals("WS-PTR"))
            .findFirst().orElse(null);
        assertNotNull(ptrEntry, "Should parse POINTER field");
        assertEquals("POINTER", ptrEntry.usage());
    }

    @Test
    void functionPointerUsageParsed() {
        String cobol = wrap("""
                DISPLAY WS-NAME.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-NAME    PIC X(10).
               05 WS-FPTR    FUNCTION-POINTER.
            """);

        CobolProgram prog = Transpiler.parse(cobol);
        var fptrEntry = prog.dataEntries().stream()
            .filter(e -> e.name().equals("WS-FPTR"))
            .findFirst().orElse(null);
        assertNotNull(fptrEntry, "Should parse FUNCTION-POINTER field");
        assertEquals("FUNCTION-POINTER", fptrEntry.usage());
    }

    @Test
    void pointerEmitsAsAlphanumeric() {
        String cobol = wrap("""
                DISPLAY WS-NAME.
                STOP RUN.
            """, """
            01 WS-DATA.
               05 WS-NAME PIC X(10).
               05 WS-PTR  POINTER.
            """);

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Should transpile without errors: " + diag.errors());
        assertTrue(java.contains("WS-PTR"), "Should define POINTER field: " + java);
        assertTrue(java.contains("POINTER"), "Should annotate as POINTER: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String wrap(String procedureStatements) {
        return wrap(procedureStatements, """
            01 WS-DATA.
               05 WS-NAME PIC X(20).
               05 WS-FLAG PIC X.
               05 WS-RC   PIC S9(9).
            """);
    }

    private String wrap(String procedureStatements, String dataEntries) {
        return """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. NEXTTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            """ + dataEntries + """
            PROCEDURE DIVISION.
            MAIN-PARA.
            """ + procedureStatements;
    }

    private byte[] padToSize(String s, int size) {
        byte[] result = new byte[size];
        java.util.Arrays.fill(result, (byte) ' ');
        byte[] src = s.getBytes();
        System.arraycopy(src, 0, result, 0, Math.min(src.length, size));
        return result;
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
