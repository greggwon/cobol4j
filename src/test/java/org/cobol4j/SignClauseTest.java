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
package org.cobol4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the COBOL SIGN clause implementation across all four modes:
 * TRAILING, LEADING, TRAILING SEPARATE, and LEADING SEPARATE.
 */
class SignClauseTest {

    // ═══════════════════════════════════════════════════════════════
    //  SIGN IS TRAILING (default behavior)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void trailingSign_positiveZero() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)")
            .build();

        rec.move("F", 0L);
        // Positive zero: last byte should be '{' (overpunch for +0)
        byte[] raw = rec.getBytes("F");
        assertEquals(5, raw.length);
        assertEquals('{', (char) raw[4]);
        assertEquals(Decimal.of(0), rec.getDecimal("F"));
    }

    @Test
    void trailingSign_positiveValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)")
            .build();

        rec.move("F", 12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(5, raw.length);
        // Last digit is 5 -> overpunch 'E' (A=1, B=2, C=3, D=4, E=5)
        assertEquals('1', (char) raw[0]);
        assertEquals('2', (char) raw[1]);
        assertEquals('3', (char) raw[2]);
        assertEquals('4', (char) raw[3]);
        assertEquals('E', (char) raw[4]);
        assertEquals(Decimal.of(12345), rec.getDecimal("F"));
    }

    @Test
    void trailingSign_negativeValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)")
            .build();

        rec.move("F", -12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(5, raw.length);
        // Last digit is 5 -> negative overpunch 'N' (J=1, K=2, L=3, M=4, N=5)
        assertEquals('1', (char) raw[0]);
        assertEquals('2', (char) raw[1]);
        assertEquals('3', (char) raw[2]);
        assertEquals('4', (char) raw[3]);
        assertEquals('N', (char) raw[4]);
        assertEquals(Decimal.of(-12345), rec.getDecimal("F"));
    }

    @Test
    void trailingSign_negativeZero() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)")
            .build();

        rec.move("F", Decimal.of("-00000"));
        byte[] raw = rec.getBytes("F");
        // -0 rounds to 0, encoded as positive
        assertEquals('{', (char) raw[4]);
    }

    @Test
    void trailingSign_roundTrip() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)")
            .build();

        for (long v : new long[]{0, 1, -1, 99999, -99999, 42, -42}) {
            rec.move("F", v);
            assertEquals(Decimal.of(v), rec.getDecimal("F"),
                "Round-trip failed for value " + v);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIGN IS LEADING
    // ═══════════════════════════════════════════════════════════════

    @Test
    void leadingSign_positiveValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeading()
            .build();

        rec.move("F", 12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(5, raw.length);
        // First digit is 1 -> positive overpunch 'A'
        assertEquals('A', (char) raw[0]);
        assertEquals('2', (char) raw[1]);
        assertEquals('3', (char) raw[2]);
        assertEquals('4', (char) raw[3]);
        assertEquals('5', (char) raw[4]);
        assertEquals(Decimal.of(12345), rec.getDecimal("F"));
    }

    @Test
    void leadingSign_negativeValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeading()
            .build();

        rec.move("F", -12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(5, raw.length);
        // First digit is 1 -> negative overpunch 'J'
        assertEquals('J', (char) raw[0]);
        assertEquals('2', (char) raw[1]);
        assertEquals('3', (char) raw[2]);
        assertEquals('4', (char) raw[3]);
        assertEquals('5', (char) raw[4]);
        assertEquals(Decimal.of(-12345), rec.getDecimal("F"));
    }

    @Test
    void leadingSign_positiveZero() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeading()
            .build();

        rec.move("F", 0L);
        byte[] raw = rec.getBytes("F");
        // First byte should be '{' (positive overpunch for 0)
        assertEquals('{', (char) raw[0]);
        assertEquals('0', (char) raw[1]);
        assertEquals('0', (char) raw[2]);
        assertEquals('0', (char) raw[3]);
        assertEquals('0', (char) raw[4]);
        assertEquals(Decimal.of(0), rec.getDecimal("F"));
    }

    @Test
    void leadingSign_roundTrip() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeading()
            .build();

        for (long v : new long[]{0, 1, -1, 99999, -99999, 42, -42}) {
            rec.move("F", v);
            assertEquals(Decimal.of(v), rec.getDecimal("F"),
                "Round-trip failed for value " + v);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIGN IS TRAILING SEPARATE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void trailingSeparate_storageSizeAddsOneByte() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signTrailingSeparate()
            .build();

        // S9(5) with SEPARATE = 5 digits + 1 sign = 6 bytes
        assertEquals(6, rec.fieldDef("F").size());
    }

    @Test
    void trailingSeparate_positiveValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signTrailingSeparate()
            .build();

        rec.move("F", 12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(6, raw.length);
        assertEquals('1', (char) raw[0]);
        assertEquals('2', (char) raw[1]);
        assertEquals('3', (char) raw[2]);
        assertEquals('4', (char) raw[3]);
        assertEquals('5', (char) raw[4]);
        assertEquals('+', (char) raw[5]);
        assertEquals(Decimal.of(12345), rec.getDecimal("F"));
    }

    @Test
    void trailingSeparate_negativeValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signTrailingSeparate()
            .build();

        rec.move("F", -12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(6, raw.length);
        assertEquals('1', (char) raw[0]);
        assertEquals('2', (char) raw[1]);
        assertEquals('3', (char) raw[2]);
        assertEquals('4', (char) raw[3]);
        assertEquals('5', (char) raw[4]);
        assertEquals('-', (char) raw[5]);
        assertEquals(Decimal.of(-12345), rec.getDecimal("F"));
    }

    @Test
    void trailingSeparate_zero() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signTrailingSeparate()
            .build();

        rec.move("F", 0L);
        byte[] raw = rec.getBytes("F");
        assertEquals('0', (char) raw[0]);
        assertEquals('0', (char) raw[4]);
        assertEquals('+', (char) raw[5]);
        assertEquals(Decimal.of(0), rec.getDecimal("F"));
    }

    @Test
    void trailingSeparate_roundTrip() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signTrailingSeparate()
            .build();

        for (long v : new long[]{0, 1, -1, 99999, -99999, 42, -42}) {
            rec.move("F", v);
            assertEquals(Decimal.of(v), rec.getDecimal("F"),
                "Round-trip failed for value " + v);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SIGN IS LEADING SEPARATE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void leadingSeparate_storageSizeAddsOneByte() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeadingSeparate()
            .build();

        // S9(5) with SEPARATE = 1 sign + 5 digits = 6 bytes
        assertEquals(6, rec.fieldDef("F").size());
    }

    @Test
    void leadingSeparate_positiveValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeadingSeparate()
            .build();

        rec.move("F", 12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(6, raw.length);
        assertEquals('+', (char) raw[0]);
        assertEquals('1', (char) raw[1]);
        assertEquals('2', (char) raw[2]);
        assertEquals('3', (char) raw[3]);
        assertEquals('4', (char) raw[4]);
        assertEquals('5', (char) raw[5]);
        assertEquals(Decimal.of(12345), rec.getDecimal("F"));
    }

    @Test
    void leadingSeparate_negativeValue() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeadingSeparate()
            .build();

        rec.move("F", -12345L);
        byte[] raw = rec.getBytes("F");
        assertEquals(6, raw.length);
        assertEquals('-', (char) raw[0]);
        assertEquals('1', (char) raw[1]);
        assertEquals('2', (char) raw[2]);
        assertEquals('3', (char) raw[3]);
        assertEquals('4', (char) raw[4]);
        assertEquals('5', (char) raw[5]);
        assertEquals(Decimal.of(-12345), rec.getDecimal("F"));
    }

    @Test
    void leadingSeparate_zero() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeadingSeparate()
            .build();

        rec.move("F", 0L);
        byte[] raw = rec.getBytes("F");
        assertEquals('+', (char) raw[0]);
        assertEquals('0', (char) raw[1]);
        assertEquals('0', (char) raw[5]);
        assertEquals(Decimal.of(0), rec.getDecimal("F"));
    }

    @Test
    void leadingSeparate_roundTrip() {
        Record rec = Record.define("T")
            .pic("F", "S9(5)").signLeadingSeparate()
            .build();

        for (long v : new long[]{0, 1, -1, 99999, -99999, 42, -42}) {
            rec.move("F", v);
            assertEquals(Decimal.of(v), rec.getDecimal("F"),
                "Round-trip failed for value " + v);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Decimal fields with SIGN clause
    // ═══════════════════════════════════════════════════════════════

    @Test
    void trailingSeparate_withDecimalDigits() {
        Record rec = Record.define("T")
            .pic("F", "S9(3)V99").signTrailingSeparate()
            .build();

        // S9(3)V99 TRAILING SEPARATE = 5 digits + 1 sign = 6 bytes
        assertEquals(6, rec.fieldDef("F").size());

        rec.move("F", Decimal.of("123.45"));
        byte[] raw = rec.getBytes("F");
        assertEquals('1', (char) raw[0]);
        assertEquals('2', (char) raw[1]);
        assertEquals('3', (char) raw[2]);
        assertEquals('4', (char) raw[3]);
        assertEquals('5', (char) raw[4]);
        assertEquals('+', (char) raw[5]);
        assertEquals(Decimal.of("123.45"), rec.getDecimal("F"));
    }

    @Test
    void leadingSeparate_withDecimalDigits() {
        Record rec = Record.define("T")
            .pic("F", "S9(3)V99").signLeadingSeparate()
            .build();

        // S9(3)V99 LEADING SEPARATE = 1 sign + 5 digits = 6 bytes
        assertEquals(6, rec.fieldDef("F").size());

        rec.move("F", Decimal.of("-123.45"));
        byte[] raw = rec.getBytes("F");
        assertEquals('-', (char) raw[0]);
        assertEquals('1', (char) raw[1]);
        assertEquals('2', (char) raw[2]);
        assertEquals('3', (char) raw[3]);
        assertEquals('4', (char) raw[4]);
        assertEquals('5', (char) raw[5]);
        assertEquals(Decimal.of("-123.45"), rec.getDecimal("F"));
    }

    @Test
    void leadingSign_withDecimalDigits_roundTrip() {
        Record rec = Record.define("T")
            .pic("F", "S9(3)V99").signLeading()
            .build();

        // S9(3)V99 LEADING = 5 bytes (no extra byte since not SEPARATE)
        assertEquals(5, rec.fieldDef("F").size());

        rec.move("F", Decimal.of("-123.45"));
        assertEquals(Decimal.of("-123.45"), rec.getDecimal("F"));

        rec.move("F", Decimal.of("67.89"));
        assertEquals(Decimal.of("67.89"), rec.getDecimal("F"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Multiple fields with different sign positions in same record
    // ═══════════════════════════════════════════════════════════════

    @Test
    void mixedSignPositions_offsetsCorrect() {
        Record rec = Record.define("T")
            .pic("A", "S9(3)").signTrailing()          // 3 bytes at offset 0
            .pic("B", "S9(3)").signLeading()           // 3 bytes at offset 3
            .pic("C", "S9(3)").signTrailingSeparate()  // 4 bytes at offset 6
            .pic("D", "S9(3)").signLeadingSeparate()   // 4 bytes at offset 10
            .build();

        assertEquals(0, rec.fieldDef("A").offset());
        assertEquals(3, rec.fieldDef("A").size());
        assertEquals(3, rec.fieldDef("B").offset());
        assertEquals(3, rec.fieldDef("B").size());
        assertEquals(6, rec.fieldDef("C").offset());
        assertEquals(4, rec.fieldDef("C").size());
        assertEquals(10, rec.fieldDef("D").offset());
        assertEquals(4, rec.fieldDef("D").size());
        assertEquals(14, rec.length());
    }

    @Test
    void mixedSignPositions_independentValues() {
        Record rec = Record.define("T")
            .pic("A", "S9(3)").signTrailing()
            .pic("B", "S9(3)").signLeading()
            .pic("C", "S9(3)").signTrailingSeparate()
            .pic("D", "S9(3)").signLeadingSeparate()
            .build();

        rec.move("A", -123L);
        rec.move("B", 456L);
        rec.move("C", -789L);
        rec.move("D", 321L);

        assertEquals(Decimal.of(-123), rec.getDecimal("A"));
        assertEquals(Decimal.of(456), rec.getDecimal("B"));
        assertEquals(Decimal.of(-789), rec.getDecimal("C"));
        assertEquals(Decimal.of(321), rec.getDecimal("D"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  SignPosition enum tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    void signPositionEnum_isSeparate() {
        assertFalse(SignPosition.TRAILING.isSeparate());
        assertFalse(SignPosition.LEADING.isSeparate());
        assertTrue(SignPosition.TRAILING_SEPARATE.isSeparate());
        assertTrue(SignPosition.LEADING_SEPARATE.isSeparate());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Parser recognizes SIGN clause
    // ═══════════════════════════════════════════════════════════════

    @Test
    void parser_recognizesSignTrailing() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIGNTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-AMT PIC S9(5) SIGN IS TRAILING.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        org.cobol4j.transpiler.TranspileDiagnostics diag = new org.cobol4j.transpiler.TranspileDiagnostics();
        org.cobol4j.transpiler.CobolProgram prog = org.cobol4j.transpiler.Parser.parse(cobol, diag);
        // Should parse without errors
        assertTrue(diag.errors().isEmpty(), "Parse errors: " + diag.errors());
        // The field should have TRAILING sign clause (or null since it is default)
        var entries = prog.dataEntries();
        var amtEntry = entries.stream()
            .filter(e -> "WS-AMT".equals(e.name()))
            .findFirst().orElseThrow();
        // TRAILING is default, so signClause may be "TRAILING" or null
        assertTrue(amtEntry.signClause() == null || "TRAILING".equals(amtEntry.signClause()));
    }

    @Test
    void parser_recognizesSignLeading() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIGNTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-AMT PIC S9(5) SIGN IS LEADING.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        org.cobol4j.transpiler.TranspileDiagnostics diag = new org.cobol4j.transpiler.TranspileDiagnostics();
        org.cobol4j.transpiler.CobolProgram prog = org.cobol4j.transpiler.Parser.parse(cobol, diag);
        assertTrue(diag.errors().isEmpty(), "Parse errors: " + diag.errors());
        var amtEntry = prog.dataEntries().stream()
            .filter(e -> "WS-AMT".equals(e.name()))
            .findFirst().orElseThrow();
        assertEquals("LEADING", amtEntry.signClause());
    }

    @Test
    void parser_recognizesSignTrailingSeparate() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIGNTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-AMT PIC S9(5) SIGN IS TRAILING SEPARATE CHARACTER.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        org.cobol4j.transpiler.TranspileDiagnostics diag = new org.cobol4j.transpiler.TranspileDiagnostics();
        org.cobol4j.transpiler.CobolProgram prog = org.cobol4j.transpiler.Parser.parse(cobol, diag);
        assertTrue(diag.errors().isEmpty(), "Parse errors: " + diag.errors());
        var amtEntry = prog.dataEntries().stream()
            .filter(e -> "WS-AMT".equals(e.name()))
            .findFirst().orElseThrow();
        assertEquals("TRAILING_SEPARATE", amtEntry.signClause());
    }

    @Test
    void parser_recognizesSignLeadingSeparate() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIGNTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-AMT PIC S9(5) SIGN IS LEADING SEPARATE.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        org.cobol4j.transpiler.TranspileDiagnostics diag = new org.cobol4j.transpiler.TranspileDiagnostics();
        org.cobol4j.transpiler.CobolProgram prog = org.cobol4j.transpiler.Parser.parse(cobol, diag);
        assertTrue(diag.errors().isEmpty(), "Parse errors: " + diag.errors());
        var amtEntry = prog.dataEntries().stream()
            .filter(e -> "WS-AMT".equals(e.name()))
            .findFirst().orElseThrow();
        assertEquals("LEADING_SEPARATE", amtEntry.signClause());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Emitter generates correct builder calls
    // ═══════════════════════════════════════════════════════════════

    @Test
    void emitter_generatesSignLeadingCall() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIGNTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-AMT PIC S9(5) SIGN IS LEADING.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        String java = org.cobol4j.transpiler.Transpiler.transpile(cobol);
        assertNotNull(java);
        assertTrue(java.contains(".signLeading()"),
            "Expected .signLeading() in emitted code: " + java);
    }

    @Test
    void emitter_generatesSignTrailingSeparateCall() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIGNTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-AMT PIC S9(5) SIGN IS TRAILING SEPARATE.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        String java = org.cobol4j.transpiler.Transpiler.transpile(cobol);
        assertNotNull(java);
        assertTrue(java.contains(".signTrailingSeparate()"),
            "Expected .signTrailingSeparate() in emitted code: " + java);
    }

    @Test
    void emitter_generatesSignLeadingSeparateCall() {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SIGNTEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-AMT PIC S9(5) SIGN IS LEADING SEPARATE CHARACTER.
            PROCEDURE DIVISION.
            MAIN-PARA.
                STOP RUN.
            """;

        String java = org.cobol4j.transpiler.Transpiler.transpile(cobol);
        assertNotNull(java);
        assertTrue(java.contains(".signLeadingSeparate()"),
            "Expected .signLeadingSeparate() in emitted code: " + java);
    }
}
