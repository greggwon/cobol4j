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

/**
 * Tests for FILE SECTION / FD (File Description) parsing and emission.
 */
class FileSectionTest {

    // ═══════════════════════════════════════════════════════════════
    //  PARSER: FD RECOGNITION
    // ═══════════════════════════════════════════════════════════════

    @Test
    void parseSingleFdEntry() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. FD-TEST.
            DATA DIVISION.
            FILE SECTION.
            FD CUSTOMER-FILE
                RECORD CONTAINS 100 CHARACTERS.
            01 CUST-RECORD.
               05 CUST-ID      PIC X(10).
               05 CUST-NAME    PIC X(30).
               05 CUST-BALANCE PIC S9(7)V99 COMP-3.
            WORKING-STORAGE SECTION.
            01 WS-EOF PIC X VALUE "N".
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);

        // FD should be recognized
        assertFalse(prog.fileBindings().isEmpty(), "Should have file bindings");
        assertEquals(1, prog.fileBindings().size());

        CobolProgram.FileBinding fb = prog.fileBindings().get(0);
        assertEquals("CUSTOMER-FILE", fb.fileName());
        assertEquals("CUST-RECORD", fb.recordName());
        assertEquals(100, fb.recordSize());
    }

    @Test
    void parseMultipleFdEntries() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. MULTI-FD.
            DATA DIVISION.
            FILE SECTION.
            FD INPUT-FILE
                RECORD CONTAINS 80 CHARACTERS.
            01 INPUT-RECORD.
               05 INP-KEY   PIC X(10).
               05 INP-DATA  PIC X(70).
            FD OUTPUT-FILE
                RECORD CONTAINS 132 CHARACTERS.
            01 OUTPUT-RECORD.
               05 OUT-LINE  PIC X(132).
            WORKING-STORAGE SECTION.
            01 WS-FLAGS.
               05 WS-EOF PIC X VALUE "N".
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);

        assertEquals(2, prog.fileBindings().size());

        CobolProgram.FileBinding fb1 = prog.fileBindings().get(0);
        assertEquals("INPUT-FILE", fb1.fileName());
        assertEquals("INPUT-RECORD", fb1.recordName());
        assertEquals(80, fb1.recordSize());

        CobolProgram.FileBinding fb2 = prog.fileBindings().get(1);
        assertEquals("OUTPUT-FILE", fb2.fileName());
        assertEquals("OUTPUT-RECORD", fb2.recordName());
        assertEquals(132, fb2.recordSize());
    }

    @Test
    void parseFdWithoutRecordContains() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. NO-SIZE.
            DATA DIVISION.
            FILE SECTION.
            FD SIMPLE-FILE.
            01 SIMPLE-RECORD PIC X(80).
            WORKING-STORAGE SECTION.
            01 WS-DATA PIC X.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);

        assertEquals(1, prog.fileBindings().size());
        CobolProgram.FileBinding fb = prog.fileBindings().get(0);
        assertEquals("SIMPLE-FILE", fb.fileName());
        assertEquals("SIMPLE-RECORD", fb.recordName());
        assertEquals(0, fb.recordSize()); // no RECORD CONTAINS clause
    }

    @Test
    void parseFdWithVariousClauses() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. CLAUSE-TEST.
            DATA DIVISION.
            FILE SECTION.
            FD DETAIL-FILE
                BLOCK CONTAINS 10 RECORDS
                RECORD CONTAINS 200 CHARACTERS
                LABEL RECORDS ARE STANDARD.
            01 DETAIL-RECORD.
               05 DET-KEY    PIC X(10).
               05 DET-DESC   PIC X(50).
               05 DET-AMT    PIC S9(9)V99.
               05 DET-FILLER PIC X(129).
            WORKING-STORAGE SECTION.
            01 WS-CTL PIC X.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);

        assertEquals(1, prog.fileBindings().size());
        CobolProgram.FileBinding fb = prog.fileBindings().get(0);
        assertEquals("DETAIL-FILE", fb.fileName());
        assertEquals("DETAIL-RECORD", fb.recordName());
        assertEquals(200, fb.recordSize());
    }

    // ═══════════════════════════════════════════════════════════════
    //  PARSER: RECORD UNDER FD IS PARSED CORRECTLY
    // ═══════════════════════════════════════════════════════════════

    @Test
    void fdRecordFieldsParsedIntoDataEntries() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. FIELDS-TEST.
            DATA DIVISION.
            FILE SECTION.
            FD CUSTOMER-FILE
                RECORD CONTAINS 100 CHARACTERS.
            01 CUST-RECORD.
               05 CUST-ID      PIC X(10).
               05 CUST-NAME    PIC X(30).
               05 CUST-BALANCE PIC S9(7)V99 COMP-3.
            WORKING-STORAGE SECTION.
            01 WS-EOF PIC X VALUE "N".
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);

        // The record fields from FILE SECTION should appear in dataEntries
        var custId = prog.dataEntries().stream()
            .filter(e -> e.name().equals("CUST-ID"))
            .findFirst().orElse(null);
        assertNotNull(custId, "CUST-ID field should be parsed");
        assertEquals(5, custId.level());
        assertTrue(custId.pic().contains("X"));

        var custBalance = prog.dataEntries().stream()
            .filter(e -> e.name().equals("CUST-BALANCE"))
            .findFirst().orElse(null);
        assertNotNull(custBalance, "CUST-BALANCE field should be parsed");
        assertNotNull(custBalance.usage(), "CUST-BALANCE should have COMP-3 usage");
    }

    @Test
    void workingStorageEntriesNotLostWithFileSection() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. BOTH-TEST.
            DATA DIVISION.
            FILE SECTION.
            FD DATA-FILE.
            01 DATA-REC PIC X(50).
            WORKING-STORAGE SECTION.
            01 WS-RECORD.
               05 WS-FLAG PIC X VALUE "N".
               05 WS-COUNT PIC 9(5) VALUE 0.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);

        // Both file section and working storage entries should be present
        var wsFlag = prog.dataEntries().stream()
            .filter(e -> e.name().equals("WS-FLAG"))
            .findFirst().orElse(null);
        assertNotNull(wsFlag, "WS-FLAG from WORKING-STORAGE should be parsed");
        assertEquals("\"N\"", wsFlag.value());

        var dataRec = prog.dataEntries().stream()
            .filter(e -> e.name().equals("DATA-REC"))
            .findFirst().orElse(null);
        assertNotNull(dataRec, "DATA-REC from FILE SECTION should be parsed");
    }

    // ═══════════════════════════════════════════════════════════════
    //  EMITTER: CobolFile GENERATION
    // ═══════════════════════════════════════════════════════════════

    @Test
    void emitCreatesCobolFileForFd() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. EMIT-FD.
            DATA DIVISION.
            FILE SECTION.
            FD CUSTOMER-FILE
                RECORD CONTAINS 100 CHARACTERS.
            01 CUST-RECORD.
               05 CUST-ID   PIC X(10).
               05 CUST-NAME PIC X(30).
            WORKING-STORAGE SECTION.
            01 WS-EOF PIC X VALUE "N".
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        String java = Transpiler.transpile(source);

        assertNotNull(java);
        assertTrue(java.contains("CobolFile.sequential(\"CUSTOMER-FILE\")"),
            "Should emit CobolFile.sequential for FD: " + java);
        assertTrue(java.contains(".recordSize(100)"),
            "Should emit recordSize(100): " + java);
        assertTrue(java.contains(".build()"),
            "Should emit .build(): " + java);
    }

    @Test
    void emitBindsFileToRecord() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. BIND-TEST.
            DATA DIVISION.
            FILE SECTION.
            FD REPORT-FILE
                RECORD CONTAINS 132 CHARACTERS.
            01 REPORT-RECORD.
               05 RPT-LINE PIC X(132).
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-X PIC X.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        String java = Transpiler.transpile(source);

        assertNotNull(java);
        assertTrue(java.contains(".file(reportFile, reportRecord)"),
            "Should bind file to record in Program.define: " + java);
    }

    @Test
    void emitMultipleFilesAndBindings() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. MULTI-FILE.
            DATA DIVISION.
            FILE SECTION.
            FD INPUT-FILE
                RECORD CONTAINS 80 CHARACTERS.
            01 INPUT-RECORD.
               05 INP-DATA PIC X(80).
            FD OUTPUT-FILE
                RECORD CONTAINS 132 CHARACTERS.
            01 OUTPUT-RECORD.
               05 OUT-DATA PIC X(132).
            WORKING-STORAGE SECTION.
            01 WS-CTL.
               05 WS-EOF PIC X VALUE "N".
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        String java = Transpiler.transpile(source);

        assertNotNull(java);
        assertTrue(java.contains("CobolFile.sequential(\"INPUT-FILE\")"),
            "Should emit inputFile: " + java);
        assertTrue(java.contains("CobolFile.sequential(\"OUTPUT-FILE\")"),
            "Should emit outputFile: " + java);
        assertTrue(java.contains(".file(inputFile, inputRecord)"),
            "Should bind inputFile: " + java);
        assertTrue(java.contains(".file(outputFile, outputRecord)"),
            "Should bind outputFile: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  READ/WRITE WITH FILE/RECORD BINDING
    // ═══════════════════════════════════════════════════════════════

    @Test
    void readWriteReferenceCorrectFileAndRecord() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. IO-TEST.
            DATA DIVISION.
            FILE SECTION.
            FD CUSTOMER-FILE
                RECORD CONTAINS 100 CHARACTERS.
            01 CUST-RECORD.
               05 CUST-ID   PIC X(10).
               05 CUST-NAME PIC X(30).
            WORKING-STORAGE SECTION.
            01 WS-CTL.
               05 WS-EOF PIC X VALUE "N".
                  88 END-OF-FILE VALUE "Y".
            PROCEDURE DIVISION.
            MAIN-PARA.
                OPEN INPUT CUSTOMER-FILE.
                READ CUSTOMER-FILE
                    AT END
                        SET END-OF-FILE TO TRUE
                END-READ.
                CLOSE CUSTOMER-FILE.
                STOP RUN.
            """;

        String java = Transpiler.transpile(source);

        assertNotNull(java);
        // OPEN should reference the file variable
        assertTrue(java.contains("ctx.open(customerFile"),
            "OPEN should reference customerFile: " + java);
        // READ should reference the file variable
        assertTrue(java.contains("ctx.read(customerFile)"),
            "READ should reference customerFile: " + java);
        // CLOSE should reference the file variable
        assertTrue(java.contains("ctx.close(customerFile)"),
            "CLOSE should reference customerFile: " + java);
    }

    @Test
    void writeReferencesFdRecord() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. WRITE-TEST.
            DATA DIVISION.
            FILE SECTION.
            FD OUTPUT-FILE
                RECORD CONTAINS 80 CHARACTERS.
            01 OUT-RECORD.
               05 OUT-LINE PIC X(80).
            WORKING-STORAGE SECTION.
            01 WS-DATA.
               05 WS-MSG PIC X(80).
            PROCEDURE DIVISION.
            MAIN-PARA.
                OPEN OUTPUT OUTPUT-FILE.
                MOVE "Hello World" TO OUT-LINE.
                WRITE OUT-RECORD.
                CLOSE OUTPUT-FILE.
                STOP RUN.
            """;

        String java = Transpiler.transpile(source);

        assertNotNull(java);
        assertTrue(java.contains("ctx.open(outputFile"),
            "OPEN should reference outputFile: " + java);
        assertTrue(java.contains("ctx.write("),
            "WRITE should be emitted: " + java);
        assertTrue(java.contains("ctx.close(outputFile)"),
            "CLOSE should reference outputFile: " + java);
    }

    // ═══════════════════════════════════════════════════════════════
    //  BACKWARD COMPATIBILITY
    // ═══════════════════════════════════════════════════════════════

    @Test
    void programWithoutFileSectionStillWorks() {
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. NO-FILE.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-X PIC X(10).
            PROCEDURE DIVISION.
            MAIN-PARA.
                DISPLAY "Hello".
                STOP RUN.
            """;

        CobolProgram prog = Parser.parse(source);
        assertTrue(prog.fileBindings().isEmpty(),
            "Program without FILE SECTION should have empty fileBindings");

        String java = Transpiler.transpile(source);
        assertNotNull(java);
        assertFalse(java.contains("CobolFile.sequential("),
            "Should not emit CobolFile.sequential when no FILE SECTION: " + java);
    }

    @Test
    void custordResourceParsesFileSection() {
        // This test verifies the CUSTORD.cbl resource file with FILE SECTION
        // can be parsed without errors
        String source = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. CUSTORD.
            ENVIRONMENT DIVISION.
            INPUT-OUTPUT SECTION.
            FILE-CONTROL.
                SELECT CUST-FILE ASSIGN TO "CUSTFILE"
                    ORGANIZATION IS SEQUENTIAL
                    FILE STATUS IS WS-FILE-STATUS.
                SELECT REPORT-FILE ASSIGN TO "RPTFILE"
                    ORGANIZATION IS SEQUENTIAL
                    FILE STATUS IS WS-RPT-STATUS.
            DATA DIVISION.
            FILE SECTION.
            FD CUST-FILE.
            01 CUST-RECORD PIC X(100).
            FD REPORT-FILE.
            01 REPORT-RECORD PIC X(132).
            WORKING-STORAGE SECTION.
            01 WS-FILE-STATUS PIC XX.
            01 WS-RPT-STATUS  PIC XX.
            01 WS-CONTROL.
               05 WS-EOF-FLAG PIC X VALUE "N".
                  88 END-OF-FILE VALUE "Y".
            PROCEDURE DIVISION.
            MAIN-LOGIC.
                OPEN INPUT CUST-FILE.
                OPEN OUTPUT REPORT-FILE.
                READ CUST-FILE
                    AT END
                        SET END-OF-FILE TO TRUE
                END-READ.
                WRITE REPORT-RECORD.
                CLOSE CUST-FILE.
                CLOSE REPORT-FILE.
                STOP RUN.
            """;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        CobolProgram prog = Parser.parse(source, diag);

        assertEquals(2, prog.fileBindings().size());
        assertEquals("CUST-FILE", prog.fileBindings().get(0).fileName());
        assertEquals("CUST-RECORD", prog.fileBindings().get(0).recordName());
        assertEquals("REPORT-FILE", prog.fileBindings().get(1).fileName());
        assertEquals("REPORT-RECORD", prog.fileBindings().get(1).recordName());
    }
}
