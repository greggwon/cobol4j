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

class RecordSchemaTest {

    @Test
    void descriptiveApiCreatesWorkingRecord() {
        Record rec = RecordSchema.define("CUSTOMER")
            .field("CUST-ID").alphanumeric(10)
            .field("CUST-NAME").alphanumeric(20)
            .field("BALANCE").decimal(7, 2).packedDecimal()
            .field("STATUS").alphanumeric(1)
                .when("ACTIVE").is("A")
                .when("INACTIVE").is("I")
            .build();

        rec.move("CUST-ID", "C001");
        rec.move("CUST-NAME", "ALICE");
        rec.move("BALANCE", Decimal.of("5000.00"));
        rec.move("STATUS", "A");

        assertEquals("C001", rec.getString("CUST-ID").trim());
        assertEquals("ALICE", rec.getString("CUST-NAME").trim());
        assertTrue(rec.getDecimal("BALANCE").equalTo(Decimal.of("5000.00")));
        assertTrue(rec.is("ACTIVE"));
        assertFalse(rec.is("INACTIVE"));
    }

    @Test
    void matchesCobolApi() {
        // COBOL API
        Record cobol = Record.define("TEST")
            .pic("F1", "X(10)")
            .pic("F2", "S9(5)V99").comp3()
            .pic("F3", "9(3)")
            .build();

        // Descriptive API — should produce identical Record
        Record descriptive = RecordSchema.define("TEST")
            .field("F1").alphanumeric(10)
            .field("F2").decimal(5, 2).packedDecimal()
            .field("F3").integer(3)
            .build();

        // Same size
        assertEquals(cobol.length(), descriptive.length());

        // Same field behavior
        cobol.move("F1", "HELLO");
        descriptive.move("F1", "HELLO");
        assertEquals(cobol.getString("F1"), descriptive.getString("F1"));

        cobol.move("F2", Decimal.of("123.45"));
        descriptive.move("F2", Decimal.of("123.45"));
        assertTrue(cobol.getDecimal("F2").equalTo(descriptive.getDecimal("F2")));
    }

    @Test
    void conditionWithMultipleValues() {
        Record rec = RecordSchema.define("TEST")
            .field("TYPE").alphanumeric(1)
                .when("VALID").is("A").or("B").or("C")
            .build();

        rec.move("TYPE", "B");
        assertTrue(rec.is("VALID"));

        rec.move("TYPE", "Z");
        assertFalse(rec.is("VALID"));
    }

    @Test
    void conditionWithRange() {
        Record rec = RecordSchema.define("TEST")
            .field("GRADE").alphanumeric(1)
                .when("PASSING").through("A", "C")
            .build();

        rec.move("GRADE", "B");
        assertTrue(rec.is("PASSING"));

        rec.move("GRADE", "F");
        assertFalse(rec.is("PASSING"));
    }

    @Test
    void numericOptions() {
        Record rec = RecordSchema.define("TEST")
            .field("COMP3-FIELD").decimal(5, 2).packedDecimal()
            .field("BINARY-FIELD").signedInteger(4).binary()
            .field("NATIVE-FIELD").integer(9).nativeBinary()
            .build();

        rec.move("COMP3-FIELD", Decimal.of("123.45"));
        assertTrue(rec.getDecimal("COMP3-FIELD").equalTo(Decimal.of("123.45")));

        rec.move("BINARY-FIELD", Decimal.of("1234"));
        assertEquals(1234, rec.getInt("BINARY-FIELD"));
    }

    @Test
    void groupsWork() {
        Record rec = RecordSchema.define("EMPLOYEE")
            .group("NAME", g -> g
                .field("FIRST").alphanumeric(15)
                .field("LAST").alphanumeric(20))
            .field("DEPT").alphanumeric(4)
            .build();

        rec.move("FIRST", "JOHN");
        rec.move("LAST", "DOE");
        rec.move("DEPT", "ACCT");

        assertEquals("JOHN", rec.getString("FIRST").trim());
        assertEquals("DOE", rec.getString("LAST").trim());
    }

    @Test
    void occursWorks() {
        Record rec = RecordSchema.define("TABLE")
            .field("ITEM").alphanumeric(10).occurs(5)
            .build();

        rec.move("ITEM", 0, "FIRST");
        rec.move("ITEM", 2, "THIRD");

        assertEquals("FIRST", rec.getString("ITEM", 0).trim());
        assertEquals("THIRD", rec.getString("ITEM", 2).trim());
    }

    @Test
    void initialValueWorks() {
        Record rec = RecordSchema.define("TEST")
            .field("STATUS").alphanumeric(1).initialValue("N")
            .field("COUNT").integer(3).initialValue("0")
            .build();

        assertEquals("N", rec.getString("STATUS").trim());
    }

    @Test
    void signOptions() {
        Record rec = RecordSchema.define("TEST")
            .field("AMT").decimal(5, 2).signLeading()
            .build();

        rec.move("AMT", Decimal.of("-123.45"));
        assertTrue(rec.getDecimal("AMT").equalTo(Decimal.of("-123.45")));
    }

    @Test
    void ideCodeCompletionScoping() {
        // This test verifies the TYPE SYSTEM works — each step returns the correct type.
        // If any of these don't compile, the typed interfaces are wrong.

        RecordSchema.SchemaBuilder sb = RecordSchema.define("TEST");
        RecordSchema.FieldTypeSelector fts = sb.field("F1");
        RecordSchema.FieldOptions fo = fts.alphanumeric(10);
        RecordSchema.ConditionValueSelector cvs = fo.when("COND");
        RecordSchema.ConditionChain cc = cvs.is("Y");
        RecordSchema.ConditionChain cc2 = cc.or("N");
        RecordSchema.FieldTypeSelector fts2 = cc2.field("F2");
        RecordSchema.NumericFieldOptions nfo = fts2.decimal(5, 2);
        RecordSchema.FieldOptions fo2 = nfo.packedDecimal();
        Record rec = fo2.build();

        assertNotNull(rec);
        assertTrue(rec.hasField("F1"));
        assertTrue(rec.hasField("F2"));
    }
}
