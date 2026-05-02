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
package org.cobol4j.interop;

import org.cobol4j.Decimal;
import org.cobol4j.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InteropTest {

    // ═══════════════════════════════════════════════════════════════
    //  EBCDIC CODEC
    // ═══════════════════════════════════════════════════════════════

    @Test
    void ebcdicToAsciiLetters() {
        // In CP037: 'A' = 0xC1, 'B' = 0xC2, ... 'Z' = 0xE9
        byte[] ebcdic = {(byte) 0xC1, (byte) 0xC2, (byte) 0xC3};
        byte[] ascii = Ebcdic.toAscii(ebcdic);
        assertEquals("ABC", new String(ascii));
    }

    @Test
    void ebcdicToAsciiDigits() {
        // In CP037: '0' = 0xF0, '1' = 0xF1, ... '9' = 0xF9
        byte[] ebcdic = {(byte) 0xF0, (byte) 0xF1, (byte) 0xF2};
        byte[] ascii = Ebcdic.toAscii(ebcdic);
        assertEquals("012", new String(ascii));
    }

    @Test
    void asciiToEbcdicRoundtrip() {
        String original = "HELLO WORLD 12345";
        byte[] ebcdic = Ebcdic.toEbcdic(original);
        String result = Ebcdic.toString(ebcdic);
        assertEquals(original, result);
    }

    @Test
    void ebcdicLowercaseRoundtrip() {
        String original = "abcxyz";
        byte[] ebcdic = Ebcdic.toEbcdic(original);
        String result = Ebcdic.toString(ebcdic);
        assertEquals(original, result);
    }

    @Test
    void ebcdicSpecialChars() {
        String original = "$.,+-*/=()";
        byte[] ebcdic = Ebcdic.toEbcdic(original);
        String result = Ebcdic.toString(ebcdic);
        assertEquals(original, result);
    }

    @Test
    void ebcdicSignOverpunch() {
        // Positive 5: zone 0xC, digit 5 → 0xC5
        byte pos5 = Ebcdic.encodeSignOverpunch(5, false);
        int[] decoded = Ebcdic.decodeSignOverpunch(pos5);
        assertEquals(5, decoded[0]);
        assertEquals(0, decoded[1]); // not negative

        // Negative 3: zone 0xD, digit 3 → 0xD3
        byte neg3 = Ebcdic.encodeSignOverpunch(3, true);
        decoded = Ebcdic.decodeSignOverpunch(neg3);
        assertEquals(3, decoded[0]);
        assertEquals(1, decoded[1]); // negative
    }

    @Test
    void ebcdicCollationOrder() {
        // EBCDIC: spaces < lowercase < uppercase < digits
        // In ASCII this would be: spaces < digits < uppercase < lowercase
        assertTrue(Ebcdic.compareEbcdic("a", "A") < 0);  // lowercase < uppercase in EBCDIC
        assertTrue(Ebcdic.compareEbcdic("A", "0") < 0);  // uppercase < digits in EBCDIC
        assertTrue(Ebcdic.compareEbcdic(" ", "a") < 0);  // space < everything
    }

    @Test
    void codePage500() {
        Ebcdic cp500 = Ebcdic.codePage(Ebcdic.CodePage.CP500);
        String original = "TEST 123";
        byte[] encoded = cp500.encode(original.getBytes());
        byte[] decoded = cp500.decode(encoded);
        assertEquals(original, new String(decoded));
    }

    // ═══════════════════════════════════════════════════════════════
    //  COPYBOOK IMPORTER
    // ═══════════════════════════════════════════════════════════════

    @Test
    void importCopybookToRecord() {
        String copybook = """
            01 CUSTOMER-RECORD.
               05 CUST-ID        PIC X(10).
               05 CUST-NAME      PIC X(30).
               05 CUST-BALANCE   PIC S9(7)V99 COMP-3.
               05 CUST-STATUS    PIC X.
                  88 ACTIVE       VALUE "A".
                  88 INACTIVE     VALUE "I".
            """;

        Record rec = CopybookImporter.fromSource(copybook);

        // Verify fields exist and have correct sizes
        assertTrue(rec.hasField("CUST-ID"));
        assertTrue(rec.hasField("CUST-NAME"));
        assertTrue(rec.hasField("CUST-BALANCE"));
        assertTrue(rec.hasField("CUST-STATUS"));

        // Use the record
        rec.move("CUST-ID", "C001")
           .move("CUST-NAME", "ALICE SMITH")
           .move("CUST-BALANCE", Decimal.of("12345.67"))
           .move("CUST-STATUS", "A");

        assertEquals("C001", rec.getString("CUST-ID").trim());
        assertEquals("ALICE SMITH", rec.getString("CUST-NAME").trim());
        assertTrue(rec.getDecimal("CUST-BALANCE").equalTo(Decimal.of("12345.67")));
        assertTrue(rec.is("ACTIVE"));
    }

    @Test
    void importCopybookToJavaSource() {
        String copybook = """
            01 ORDER-RECORD.
               05 ORDER-ID      PIC 9(8).
               05 ORDER-AMOUNT  PIC S9(7)V99.
               05 ORDER-STATUS  PIC X.
            """;

        String javaSource = CopybookImporter.toJavaSourceFromText(copybook);

        assertTrue(javaSource.contains("Record.define"));
        assertTrue(javaSource.contains("ORDER-ID"));
        assertTrue(javaSource.contains("ORDER-AMOUNT"));
        assertTrue(javaSource.contains("ORDER-STATUS"));
        assertTrue(javaSource.contains(".build()"));
    }

    @Test
    void importCopybookFromFile(@TempDir Path tempDir) throws Exception {
        String copybook = """
            01 EMPLOYEE.
               05 EMP-ID    PIC 9(5).
               05 EMP-NAME  PIC X(25).
               05 EMP-DEPT  PIC X(4).
            """;

        Path cpyFile = tempDir.resolve("EMPCPY.cpy");
        Files.writeString(cpyFile, copybook);

        Record rec = CopybookImporter.toRecord(cpyFile.toString());

        rec.move("EMP-ID", 12345L)
           .move("EMP-NAME", "BOB JONES")
           .move("EMP-DEPT", "ACCT");

        assertEquals(12345, rec.getInt("EMP-ID"));
        assertEquals("BOB JONES", rec.getString("EMP-NAME").trim());
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAINFRAME FILE I/O
    // ═══════════════════════════════════════════════════════════════

    @Test
    void readWriteFixedLengthEbcdic(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("TESTFILE.dat");

        Record rec = Record.define("TEST-REC")
            .pic("NAME", "X(20)")
            .pic("AMOUNT", "X(10)")
            .build();

        // Write records in EBCDIC
        var writer = MainframeFile.writer(file)
            .fixedLength(30)
            .ebcdic(Ebcdic.CodePage.CP037);

        rec.move("NAME", "ALICE").move("AMOUNT", "1000.00");
        writer.write(rec);
        rec.move("NAME", "BOB").move("AMOUNT", "2000.00");
        writer.write(rec);
        rec.move("NAME", "CAROL").move("AMOUNT", "3000.00");
        writer.write(rec);
        writer.close();

        // Read them back
        int[] count = {0};
        MainframeFile.reader(file)
            .fixedLength(30)
            .ebcdic(Ebcdic.CodePage.CP037)
            .forEach(rec, r -> {
                count[0]++;
                String name = r.getString("NAME").trim();
                assertTrue(name.equals("ALICE") || name.equals("BOB") || name.equals("CAROL"),
                    "Unexpected name: " + name);
            });

        assertEquals(3, count[0]);
    }

    @Test
    void readWriteNoTranslation(@TempDir Path tempDir) throws Exception {
        // Pure ASCII file — no EBCDIC translation
        Path file = tempDir.resolve("ASCII.dat");

        Record rec = Record.define("REC")
            .pic("DATA", "X(20)")
            .build();

        var writer = MainframeFile.writer(file)
            .fixedLength(20)
            .noTranslation();

        rec.move("DATA", "HELLO WORLD");
        writer.write(rec);
        writer.close();

        int[] count = {0};
        MainframeFile.reader(file)
            .fixedLength(20)
            .noTranslation()
            .forEach(rec, r -> {
                count[0]++;
                assertTrue(r.getString("DATA").startsWith("HELLO WORLD"));
            });
        assertEquals(1, count[0]);
    }

    @Test
    void readFromByteArray() throws Exception {
        // Simulate receiving a mainframe record from MQ or network
        String data = "ALICE SMITH         00012345";
        byte[] ebcdicData = Ebcdic.toEbcdic(data);

        Record rec = Record.define("MSG-REC")
            .pic("MSG-NAME", "X(20)")
            .pic("MSG-ID", "X(8)")
            .build();

        int[] count = {0};
        MainframeFile.reader(ebcdicData)
            .fixedLength(28)
            .ebcdic(Ebcdic.CodePage.CP037)
            .forEach(rec, r -> {
                count[0]++;
                assertEquals("ALICE SMITH", r.getString("MSG-NAME").trim());
                assertEquals("00012345", r.getString("MSG-ID").trim());
            });
        assertEquals(1, count[0]);
    }

    // ═══════════════════════════════════════════════════════════════
    //  END-TO-END: Copybook → Read Mainframe File → Process
    // ═══════════════════════════════════════════════════════════════

    @Test
    void endToEndCopybookFileProcessing(@TempDir Path tempDir) throws Exception {
        // 1. Define record from copybook
        String copybook = """
            01 INVOICE.
               05 INV-NUMBER  PIC 9(8).
               05 INV-AMOUNT  PIC X(10).
               05 INV-VENDOR  PIC X(20).
            """;
        Record invoice = CopybookImporter.fromSource(copybook);

        // 2. Write a simulated mainframe file
        Path file = tempDir.resolve("INVOICES.dat");
        var writer = MainframeFile.writer(file)
            .fixedLength(invoice.length())
            .ebcdic(Ebcdic.CodePage.CP037);

        invoice.move("INV-NUMBER", 10001L)
               .move("INV-AMOUNT", "15000.00")
               .move("INV-VENDOR", "ACME CORP");
        writer.write(invoice);

        invoice.move("INV-NUMBER", 10002L)
               .move("INV-AMOUNT", "25000.00")
               .move("INV-VENDOR", "GLOBEX INC");
        writer.write(invoice);
        writer.close();

        // 3. Read back and process — exactly as a migrated COBOL program would
        Decimal[] total = {Decimal.ZERO};
        int[] count = {0};

        MainframeFile.reader(file)
            .fixedLength(invoice.length())
            .ebcdic(Ebcdic.CodePage.CP037)
            .forEach(invoice, rec -> {
                count[0]++;
                String vendor = rec.getString("INV-VENDOR").trim();
                assertFalse(vendor.isEmpty());
            });

        assertEquals(2, count[0]);
    }
}
