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
package org.cobol4j.codec;

import org.cobol4j.Decimal;
import org.cobol4j.Record;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodecTest {

    private final Record custRec = Record.define("CUSTOMER-RECORD")
        .pic("CUST-ID", "X(10)")
        .pic("CUST-NAME", "X(20)")
        .pic("CUST-BALANCE", "S9(7)V99")
        .pic("CUST-STATUS", "X")
        .build();

    // ═══════════════════════════════════════════════════════════════
    //  JSON GENERATE / PARSE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void jsonGenerate() {
        custRec.move("CUST-ID", "C001")
               .move("CUST-NAME", "ALICE SMITH")
               .move("CUST-BALANCE", Decimal.of("50000.00"))
               .move("CUST-STATUS", "A");

        String json = CodecRegistry.instance().toJson(custRec);

        assertTrue(json.contains("\"CUST-ID\": \"C001\""), "Should have ID: " + json);
        assertTrue(json.contains("\"CUST-NAME\": \"ALICE SMITH\""), "Should have name: " + json);
        assertTrue(json.contains("\"CUST-BALANCE\": 50000.00"), "Should have balance: " + json);
        assertTrue(json.contains("\"CUST-STATUS\": \"A\""), "Should have status: " + json);
    }

    @Test
    void jsonParse() {
        String json = """
            {
              "CUST-ID": "C002",
              "CUST-NAME": "BOB JONES",
              "CUST-BALANCE": 25000.50,
              "CUST-STATUS": "I"
            }
            """;

        CodecRegistry.instance().fromJson(json, custRec);

        assertEquals("C002", custRec.getString("CUST-ID").trim());
        assertEquals("BOB JONES", custRec.getString("CUST-NAME").trim());
        assertTrue(custRec.getDecimal("CUST-BALANCE").equalTo(Decimal.of("25000.50")));
        assertEquals("I", custRec.getString("CUST-STATUS").trim());
    }

    @Test
    void jsonRoundtrip() {
        custRec.move("CUST-ID", "C003")
               .move("CUST-NAME", "CAROL DAVIS")
               .move("CUST-BALANCE", Decimal.of("12345.67"))
               .move("CUST-STATUS", "A");

        String json = CodecRegistry.instance().toJson(custRec);

        // Parse into a fresh record
        Record fresh = custRec.newInstance();
        CodecRegistry.instance().fromJson(json, fresh);

        assertEquals("C003", fresh.getString("CUST-ID").trim());
        assertEquals("CAROL DAVIS", fresh.getString("CUST-NAME").trim());
        assertTrue(fresh.getDecimal("CUST-BALANCE").equalTo(Decimal.of("12345.67")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  XML GENERATE / PARSE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void xmlGenerate() {
        custRec.move("CUST-ID", "C001")
               .move("CUST-NAME", "ALICE SMITH")
               .move("CUST-BALANCE", Decimal.of("50000.00"))
               .move("CUST-STATUS", "A");

        String xml = CodecRegistry.instance().toXml(custRec);

        assertTrue(xml.contains("<CUSTOMER-RECORD>"), "Should have root element: " + xml);
        assertTrue(xml.contains("<CUST-ID>C001</CUST-ID>"), "Should have ID: " + xml);
        assertTrue(xml.contains("<CUST-NAME>ALICE SMITH</CUST-NAME>"), "Should have name: " + xml);
        assertTrue(xml.contains("<CUST-BALANCE>50000.00</CUST-BALANCE>"), "Should have balance: " + xml);
    }

    @Test
    void xmlParse() {
        String xml = """
            <CUSTOMER-RECORD>
              <CUST-ID>C004</CUST-ID>
              <CUST-NAME>DAVE WILSON</CUST-NAME>
              <CUST-BALANCE>99999.99</CUST-BALANCE>
              <CUST-STATUS>S</CUST-STATUS>
            </CUSTOMER-RECORD>
            """;

        CodecRegistry.instance().fromXml(xml, custRec);

        assertEquals("C004", custRec.getString("CUST-ID").trim());
        assertEquals("DAVE WILSON", custRec.getString("CUST-NAME").trim());
        assertTrue(custRec.getDecimal("CUST-BALANCE").equalTo(Decimal.of("99999.99")));
        assertEquals("S", custRec.getString("CUST-STATUS").trim());
    }

    @Test
    void xmlRoundtrip() {
        custRec.move("CUST-ID", "C005")
               .move("CUST-NAME", "EVE BROWN")
               .move("CUST-BALANCE", Decimal.of("777.77"))
               .move("CUST-STATUS", "A");

        String xml = CodecRegistry.instance().toXml(custRec);
        Record fresh = custRec.newInstance();
        CodecRegistry.instance().fromXml(xml, fresh);

        assertEquals("C005", fresh.getString("CUST-ID").trim());
        assertTrue(fresh.getDecimal("CUST-BALANCE").equalTo(Decimal.of("777.77")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  REGISTRY
    // ═══════════════════════════════════════════════════════════════

    @Test
    void registryHasBuiltinCodecs() {
        assertTrue(CodecRegistry.instance().hasRecordCodec("json"));
        assertTrue(CodecRegistry.instance().hasRecordCodec("xml"));
    }

    @Test
    void registryLookupByName() {
        RecordCodec json = CodecRegistry.instance().recordCodec("json");
        assertNotNull(json);
        assertEquals("json", json.name());

        RecordCodec xml = CodecRegistry.instance().recordCodec("xml");
        assertNotNull(xml);
        assertEquals("xml", xml.name());
    }

    @Test
    void registerCustomCodec() {
        CodecRegistry.instance().register(new RecordCodec() {
            @Override public String name() { return "csv"; }
            @Override public String generate(Record record) { return "custom-csv-output"; }
            @Override public void parse(String input, Record record) {}
        });

        assertTrue(CodecRegistry.instance().hasRecordCodec("csv"));
        RecordCodec csv = CodecRegistry.instance().recordCodec("csv");
        assertEquals("custom-csv-output", csv.generate(custRec));
    }

    @Test
    void customFieldCodec() {
        FieldCodec<String> upper = new FieldCodec<>() {
            @Override public String name() { return "uppercase"; }
            @Override public Class<String> type() { return String.class; }
            @Override public String decode(byte[] buf, int off, int len) {
                return new String(buf, off, len).trim().toUpperCase();
            }
            @Override public int encode(String value, byte[] buf, int off, int len) {
                byte[] bytes = value.toUpperCase().getBytes();
                System.arraycopy(bytes, 0, buf, off, Math.min(bytes.length, len));
                return Math.min(bytes.length, len);
            }
        };

        CodecRegistry.instance().register(upper);
        assertTrue(CodecRegistry.instance().hasFieldCodec("uppercase"));
    }
}
