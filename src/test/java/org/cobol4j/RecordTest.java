package org.cobol4j;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class RecordTest {

    // ── Basic field definition and access ────────────────────────────

    @Test
    void alphanumericMoveAndGet() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(10)")
            .build();

        rec.move("NAME", "HELLO");
        assertEquals("HELLO     ", rec.getString("NAME")); // left-justified, space-padded
    }

    @Test
    void alphanumericTruncation() {
        Record rec = Record.define("TEST")
            .pic("SHORT", "X(5)")
            .build();

        rec.move("SHORT", "TRUNCATED");
        assertEquals("TRUNC", rec.getString("SHORT")); // truncated to 5
    }

    @Test
    void numericDisplayMoveAndGet() {
        Record rec = Record.define("TEST")
            .pic("AMT", "9(5)")
            .build();

        rec.move("AMT", 123L);
        assertEquals(new BigDecimal("123"), rec.getDecimal("AMT"));
    }

    @Test
    void signedNumericWithDecimal() {
        Record rec = Record.define("TEST")
            .pic("BALANCE", "S9(7)V99")
            .build();

        rec.move("BALANCE", new BigDecimal("12345.67"));
        BigDecimal result = rec.getDecimal("BALANCE");
        assertEquals(0, new BigDecimal("12345.67").compareTo(result));
    }

    @Test
    void negativeSignedNumeric() {
        Record rec = Record.define("TEST")
            .pic("BALANCE", "S9(7)V99")
            .build();

        rec.move("BALANCE", new BigDecimal("-500.25"));
        BigDecimal result = rec.getDecimal("BALANCE");
        assertEquals(0, new BigDecimal("-500.25").compareTo(result));
    }

    // ── COMP-3 (packed decimal) ─────────────────────────────────────

    @Test
    void comp3Roundtrip() {
        Record rec = Record.define("TEST")
            .pic("AMT", "S9(7)V99").comp3()
            .build();

        rec.move("AMT", new BigDecimal("12345.67"));
        assertEquals(0, new BigDecimal("12345.67").compareTo(rec.getDecimal("AMT")));
    }

    @Test
    void comp3Negative() {
        Record rec = Record.define("TEST")
            .pic("AMT", "S9(5)V99").comp3()
            .build();

        rec.move("AMT", new BigDecimal("-999.99"));
        assertEquals(0, new BigDecimal("-999.99").compareTo(rec.getDecimal("AMT")));
    }

    // ── COMP (binary) ───────────────────────────────────────────────

    @Test
    void compBinaryRoundtrip() {
        Record rec = Record.define("TEST")
            .pic("COUNT", "S9(4)").comp()
            .build();

        rec.move("COUNT", 1234L);
        assertEquals(1234, rec.getInt("COUNT"));
    }

    // ── Groups ──────────────────────────────────────────────────────

    @Test
    void groupLayout() {
        Record rec = Record.define("CUSTOMER")
            .group("NAME", name -> name
                .pic("FIRST", "X(10)")
                .pic("LAST",  "X(15)"))
            .pic("STATUS", "X")
            .build();

        rec.move("FIRST", "JOHN");
        rec.move("LAST", "DOE");
        rec.move("STATUS", "A");

        assertEquals("JOHN      ", rec.getString("FIRST"));
        assertEquals("DOE            ", rec.getString("LAST"));
        assertEquals("A", rec.getString("STATUS"));

        // Group includes both children: 25 bytes
        assertEquals(25, rec.fieldDef("NAME").size());

        // Total record: 25 + 1 = 26 bytes
        assertEquals(26, rec.length());
    }

    // ── Level-88 conditions ─────────────────────────────────────────

    @Test
    void condition88Test() {
        Record rec = Record.define("TEST")
            .pic("STATUS", "X")
                .value88("ACTIVE", "A")
                .value88("INACTIVE", "I")
                .value88("SUSPENDED", "S")
            .build();

        rec.move("STATUS", "A");
        assertTrue(rec.is("ACTIVE"));
        assertFalse(rec.is("INACTIVE"));

        rec.set("SUSPENDED");
        assertTrue(rec.is("SUSPENDED"));
        assertEquals("S", rec.getString("STATUS").trim());
    }

    @Test
    void condition88MultipleValues() {
        Record rec = Record.define("TEST")
            .pic("CODE", "X(2)")
                .value88("VALID", "AA", "BB", "CC")
            .build();

        rec.move("CODE", "BB");
        assertTrue(rec.is("VALID"));

        rec.move("CODE", "ZZ");
        assertFalse(rec.is("VALID"));
    }

    @Test
    void condition88Range() {
        Record rec = Record.define("TEST")
            .pic("GRADE", "X")
                .value88Range("PASSING", "A", "C")
            .build();

        rec.move("GRADE", "B");
        assertTrue(rec.is("PASSING"));

        rec.move("GRADE", "F");
        assertFalse(rec.is("PASSING"));
    }

    @Test
    void customConditionWithLambda() {
        Record rec = Record.define("TEST")
            .pic("SCORE", "9(3)")
                .condition("HIGH-SCORE", value -> {
                    try {
                        return Integer.parseInt(value.trim()) > 90;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
            .build();

        rec.move("SCORE", 95L);
        assertTrue(rec.is("HIGH-SCORE"));

        rec.move("SCORE", 50L);
        assertFalse(rec.is("HIGH-SCORE"));
    }

    // ── REDEFINES ───────────────────────────────────────────────────

    @Test
    void redefines() {
        Record rec = Record.define("RECORD")
            .group("DATE-GROUP", g -> g
                .pic("DATE-FULL", "X(8)"))
            .redefines("DATE-GROUP", "DATE-PARTS", g -> g
                .pic("DATE-YEAR",  "X(4)")
                .pic("DATE-MONTH", "X(2)")
                .pic("DATE-DAY",   "X(2)"))
            .build();

        rec.move("DATE-FULL", "20260430");
        assertEquals("2026", rec.getString("DATE-YEAR"));
        assertEquals("04", rec.getString("DATE-MONTH"));
        assertEquals("30", rec.getString("DATE-DAY"));

        // Modifying through redefines view affects the original
        rec.move("DATE-MONTH", "12");
        assertEquals("20261230", rec.getString("DATE-FULL"));
    }

    // ── OCCURS ──────────────────────────────────────────────────────

    @Test
    void occursArray() {
        Record rec = Record.define("TABLE")
            .pic("ITEM", "X(10)").occurs(5)
            .build();

        rec.move("ITEM", 0, "FIRST");
        rec.move("ITEM", 1, "SECOND");
        rec.move("ITEM", 4, "FIFTH");

        assertEquals("FIRST     ", rec.getString("ITEM", 0));
        assertEquals("SECOND    ", rec.getString("ITEM", 1));
        assertEquals("FIFTH     ", rec.getString("ITEM", 4));

        // Total storage: 10 * 5 = 50 bytes
        assertEquals(50, rec.fieldDef("ITEM").size());
    }

    // ── Arithmetic ──────────────────────────────────────────────────

    @Test
    void addToField() {
        Record rec = Record.define("TEST")
            .pic("TOTAL", "S9(5)V99")
            .build();

        rec.move("TOTAL", new BigDecimal("100.00"));
        rec.add("TOTAL", new BigDecimal("50.25"));
        assertEquals(0, new BigDecimal("150.25").compareTo(rec.getDecimal("TOTAL")));
    }

    @Test
    void subtractFromField() {
        Record rec = Record.define("TEST")
            .pic("TOTAL", "S9(5)V99")
            .build();

        rec.move("TOTAL", new BigDecimal("200.00"));
        rec.subtract("TOTAL", new BigDecimal("75.50"));
        assertEquals(0, new BigDecimal("124.50").compareTo(rec.getDecimal("TOTAL")));
    }

    @Test
    void multiplyField() {
        Record rec = Record.define("TEST")
            .pic("PRICE", "S9(5)V99")
            .build();

        rec.move("PRICE", new BigDecimal("10.00"));
        rec.multiply("PRICE", new BigDecimal("3"));
        assertEquals(0, new BigDecimal("30.00").compareTo(rec.getDecimal("PRICE")));
    }

    @Test
    void divideField() {
        Record rec = Record.define("TEST")
            .pic("AMT", "S9(5)V99")
            .build();

        rec.move("AMT", new BigDecimal("100.00"));
        rec.divide("AMT", new BigDecimal("3"));
        // 100/3 = 33.33... truncated to 2 decimal places = 33.33
        assertEquals(0, new BigDecimal("33.33").compareTo(rec.getDecimal("AMT")));
    }

    @Test
    void sizeErrorOnOverflow() {
        Record rec = Record.define("TEST")
            .pic("SMALL", "9(3)")
            .build();

        rec.move("SMALL", 100L);

        boolean[] errorOccurred = {false};
        rec.add("SMALL", new BigDecimal("999"),
            SizeErrorHandler.onError(() -> errorOccurred[0] = true));

        assertTrue(errorOccurred[0]);
        // Field should retain original value on size error
        assertEquals(100, rec.getInt("SMALL"));
    }

    @Test
    void sizeErrorCallbackWithLambda() {
        Record rec = Record.define("TEST")
            .pic("AMT", "9(3)V99")
            .pic("ERR", "X")
            .build();

        rec.move("AMT", new BigDecimal("500.00"));

        rec.add("AMT", new BigDecimal("600.00"),
            SizeErrorHandler.of(
                () -> rec.move("ERR", "Y"),  // ON SIZE ERROR
                () -> rec.move("ERR", "N")   // NOT ON SIZE ERROR
            ));

        // 500 + 600 = 1100, but PIC 9(3)V99 max integer is 999
        assertEquals("Y", rec.getString("ERR").trim());
    }

    @Test
    void computeWithBigDecimal() {
        Record rec = Record.define("TEST")
            .pic("PRINCIPAL", "S9(7)V99")
            .pic("RATE",      "S9V9(4)")
            .pic("INTEREST",  "S9(7)V99")
            .build();

        BigDecimal principal = new BigDecimal("50000.00");
        BigDecimal rate = new BigDecimal("0.0525");
        rec.move("PRINCIPAL", principal);
        rec.move("RATE", rate);

        // COMPUTE INTEREST = PRINCIPAL * RATE / 12
        BigDecimal interest = principal.multiply(rate)
            .divide(BigDecimal.valueOf(12), 10, java.math.RoundingMode.HALF_EVEN);
        rec.compute("INTEREST", interest);

        // 50000 * 0.0525 / 12 = 218.75
        assertEquals(0, new BigDecimal("218.75").compareTo(rec.getDecimal("INTEREST")));
    }

    // ── Figurative constants ────────────────────────────────────────

    @Test
    void moveSpaces() {
        Record rec = Record.define("TEST")
            .pic("FIELD", "X(10)")
            .build();

        rec.move("FIELD", "HELLO");
        rec.moveSpaces("FIELD");
        assertEquals("          ", rec.getString("FIELD"));
    }

    @Test
    void moveZeros() {
        Record rec = Record.define("TEST")
            .pic("NUM", "9(5)")
            .build();

        rec.move("NUM", 12345L);
        rec.moveZeros("NUM");
        assertEquals(0, rec.getInt("NUM"));
    }

    // ── Reference modification ──────────────────────────────────────

    @Test
    void referenceModification() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(20)")
            .build();

        rec.move("DATA", "HELLO WORLD");
        assertEquals("WORLD", rec.substring("DATA", 7, 5)); // 1-based
    }

    // ── MOVE CORRESPONDING ──────────────────────────────────────────

    @Test
    void moveCorresponding() {
        Record source = Record.define("SOURCE")
            .pic("NAME",    "X(10)")
            .pic("BALANCE", "S9(5)V99")
            .pic("CODE",    "X(3)")
            .build();

        Record target = Record.define("TARGET")
            .pic("NAME",    "X(10)")
            .pic("BALANCE", "S9(5)V99")
            .pic("STATUS",  "X")
            .build();

        source.move("NAME", "JOHN");
        source.move("BALANCE", new BigDecimal("500.00"));
        source.move("CODE", "ABC");

        target.moveCorresponding(source);

        assertEquals("JOHN      ", target.getString("NAME"));
        assertEquals(0, new BigDecimal("500.00").compareTo(target.getDecimal("BALANCE")));
        // STATUS shouldn't be touched — no corresponding field in source
        assertEquals(" ", target.getString("STATUS"));
    }

    // ── New instance / duplicate ─────────────────────────────────────

    @Test
    void newInstanceSharesSchema() {
        Record template = Record.define("TMPL")
            .pic("F1", "X(5)")
            .pic("F2", "9(3)")
            .build();

        template.move("F1", "HELLO");

        Record fresh = template.newInstance();
        // Fresh instance has blank data
        assertEquals("     ", fresh.getString("F1"));
        // But same field definitions
        assertTrue(fresh.hasField("F1"));
        assertTrue(fresh.hasField("F2"));
    }

    @Test
    void duplicateCopiesData() {
        Record original = Record.define("ORIG")
            .pic("NAME", "X(10)")
            .build();

        original.move("NAME", "ALICE");
        Record copy = original.duplicate();
        assertEquals("ALICE     ", copy.getString("NAME"));

        // Modifying copy doesn't affect original
        copy.move("NAME", "BOB");
        assertEquals("ALICE     ", original.getString("NAME"));
    }

    // ── Initial VALUES ──────────────────────────────────────────────

    @Test
    void initialValues() {
        Record rec = Record.define("TEST")
            .pic("STATUS", "X").value("A")
            .pic("COUNT",  "9(3)").value("000")
            .build();

        assertEquals("A", rec.getString("STATUS"));
        assertEquals(0, rec.getInt("COUNT"));
    }

    // ── Fluent chaining ─────────────────────────────────────────────

    @Test
    void fluentChaining() {
        Record rec = Record.define("CUSTOMER")
            .pic("CUST-NAME",    "X(20)")
            .pic("CUST-BALANCE", "S9(7)V99").comp3()
            .pic("CUST-STATUS",  "X")
                .value88("ACTIVE",   "A")
                .value88("INACTIVE", "I")
            .pic("ERR-FLAG",     "X")
            .build();

        // One fluent chain — no repetitive variable name
        rec.move("CUST-NAME", "JOHN DOE")
           .move("CUST-BALANCE", new BigDecimal("50000.00"))
           .set("ACTIVE")
           .add("CUST-BALANCE", new BigDecimal("100.00"),
               SizeErrorHandler.of(
                   () -> rec.move("ERR-FLAG", "Y"),
                   () -> rec.move("ERR-FLAG", "N")));

        assertEquals("JOHN DOE            ", rec.getString("CUST-NAME"));
        assertEquals(0, new BigDecimal("50100.00").compareTo(rec.getDecimal("CUST-BALANCE")));
        assertTrue(rec.is("ACTIVE"));
        assertEquals("N", rec.getString("ERR-FLAG").trim());
    }

    @Test
    void fluentFigurativeConstants() {
        Record rec = Record.define("TEST")
            .pic("F1", "X(5)")
            .pic("F2", "X(5)")
            .pic("F3", "X(5)")
            .build();

        rec.move("F1", "HELLO")
           .moveSpaces("F2")
           .moveZeros("F3");

        assertEquals("HELLO", rec.getString("F1"));
        assertEquals("     ", rec.getString("F2"));
        assertEquals("00000", rec.getString("F3"));
    }
}
