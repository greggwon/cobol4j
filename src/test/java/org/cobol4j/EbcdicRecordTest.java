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
import org.cobol4j.interop.Ebcdic;
import static org.junit.jupiter.api.Assertions.*;

class EbcdicRecordTest {

    @Test
    void ebcdicRecordStringRoundtrip() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(10)")
            .ebcdic()
            .build();

        rec.move("NAME", "HELLO");
        assertEquals("HELLO", rec.getString("NAME").trim());

        // Raw bytes should be EBCDIC
        byte[] raw = rec.buffer();
        // 'H' in CP037 = 0xC8, not 0x48
        assertEquals((byte) 0xC8, raw[0]);
    }

    @Test
    void ebcdicRecordNumericRoundtrip() {
        Record rec = Record.define("TEST")
            .pic("AMT", "S9(5)V99")
            .ebcdic()
            .build();

        rec.move("AMT", Decimal.of("12345.67"));
        assertTrue(rec.getDecimal("AMT").equalTo(Decimal.of("12345.67")));

        // Raw bytes should be EBCDIC digits (0xF1, 0xF2, ...)
        byte[] raw = rec.buffer();
        assertEquals((byte) 0xF1, raw[0]); // '1' in EBCDIC
    }

    @Test
    void ebcdicRecordLoadRawMainframeBytes() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(5)")
            .pic("CODE", "X(3)")
            .ebcdic()
            .build();

        // Simulate raw mainframe bytes for "HELLO" + "ABC"
        byte[] ebcdicBytes = Ebcdic.toEbcdic("HELLOABC");
        rec.loadFrom(ebcdicBytes);

        assertEquals("HELLO", rec.getString("NAME"));
        assertEquals("ABC", rec.getString("CODE"));
    }

    @Test
    void ebcdicRecordComp3Unaffected() {
        // COMP-3 is encoding-independent
        Record rec = Record.define("TEST")
            .pic("AMT", "S9(5)V99").comp3()
            .ebcdic()
            .build();

        rec.move("AMT", Decimal.of("123.45"));
        assertTrue(rec.getDecimal("AMT").equalTo(Decimal.of("123.45")));
    }

    @Test
    void ebcdicSpaceFill() {
        Record rec = Record.define("TEST")
            .pic("F", "X(5)")
            .ebcdic()
            .build();

        rec.moveSpaces("F");
        byte[] raw = rec.buffer();
        // EBCDIC space = 0x40
        assertEquals((byte) 0x40, raw[0]);
        assertEquals((byte) 0x40, raw[4]);
    }

    @Test
    void ebcdicZeroFill() {
        Record rec = Record.define("TEST")
            .pic("F", "X(5)")
            .ebcdic()
            .build();

        rec.moveZeros("F");
        byte[] raw = rec.buffer();
        // EBCDIC '0' = 0xF0
        assertEquals((byte) 0xF0, raw[0]);
        assertEquals((byte) 0xF0, raw[4]);
    }

    @Test
    void ebcdicInitializeAlphaFields() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(5)")
            .pic("AMT", "9(3)")
            .ebcdic()
            .build();

        rec.initialize();
        byte[] raw = rec.buffer();
        // Alpha field should be filled with EBCDIC space
        assertEquals((byte) 0x40, raw[0]);
        assertEquals((byte) 0x40, raw[4]);
        // Numeric field should be EBCDIC zeros
        assertEquals((byte) 0xF0, raw[5]);
        assertEquals((byte) 0xF0, raw[7]);
    }

    @Test
    void ebcdicNegativeNumericRoundtrip() {
        Record rec = Record.define("TEST")
            .pic("AMT", "S9(5)V99")
            .ebcdic()
            .build();

        rec.move("AMT", Decimal.of("-12345.67"));
        assertTrue(rec.getDecimal("AMT").equalTo(Decimal.of("-12345.67")));

        // Last byte should have 0xD_ zone (negative overpunch)
        byte[] raw = rec.buffer();
        int lastByte = raw[6] & 0xFF;
        assertEquals(0xD0, lastByte & 0xF0); // 0xD zone = negative
        assertEquals(7, lastByte & 0x0F);     // digit 7
    }

    @Test
    void ebcdicWithCodePage500() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(5)")
            .ebcdic(Ebcdic.CodePage.CP500)
            .build();

        rec.move("NAME", "HELLO");
        assertEquals("HELLO", rec.getString("NAME"));
    }

    @Test
    void ebcdicCompBinaryUnaffected() {
        // COMP (binary) is encoding-independent
        Record rec = Record.define("TEST")
            .pic("AMT", "S9(5)V99").comp()
            .ebcdic()
            .build();

        rec.move("AMT", Decimal.of("123.45"));
        assertTrue(rec.getDecimal("AMT").equalTo(Decimal.of("123.45")));
    }

    @Test
    void ebcdicNewInstancePreservesEncoding() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(5)")
            .ebcdic()
            .build();

        Record copy = rec.newInstance();
        copy.move("NAME", "WORLD");
        assertEquals("WORLD", copy.getString("NAME"));

        // Verify raw bytes are EBCDIC
        byte[] raw = copy.buffer();
        // 'W' in CP037 = 0xE6
        assertEquals((byte) 0xE6, raw[0]);
    }

    @Test
    void ebcdicDuplicatePreservesEncoding() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(5)")
            .ebcdic()
            .build();

        rec.move("NAME", "HELLO");
        Record copy = rec.duplicate();
        assertEquals("HELLO", copy.getString("NAME"));
    }
}
