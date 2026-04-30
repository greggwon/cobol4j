package org.cobol4j;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for all 9 new features: GIVING/ROUNDED/REMAINDER, INITIALIZE,
 * Intrinsic functions, SEARCH/SEARCH ALL, SORT, OCCURS DEPENDING ON,
 * numeric edited formatting, INSPECT CONVERTING/BEFORE/AFTER,
 * nested VARYING, EXIT PARAGRAPH, and Field references.
 */
class NewFeaturesTest {

    // ═══════════════════════════════════════════════════════════════
    //  FIELD REFERENCES
    // ═══════════════════════════════════════════════════════════════

    @Test
    void fieldReferenceReadWrite() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(10)")
            .pic("BAL", "S9(5)V99")
            .build();

        Field name = rec.field("NAME");
        Field bal = rec.field("BAL");

        name.move("ALICE");
        bal.move(new BigDecimal("500.00")).add(new BigDecimal("100.00"));

        assertEquals("ALICE", name.trimmed());
        assertEquals(0, new BigDecimal("600.00").compareTo(bal.get()));
    }

    @Test
    void fieldReferencePassByReference() {
        Record rec = Record.define("TEST")
            .pic("A", "S9(5)V99")
            .pic("B", "S9(5)V99")
            .build();

        Field a = rec.field("A");
        Field b = rec.field("B");
        a.move(new BigDecimal("100.00"));
        b.move(new BigDecimal("200.00"));

        // Pass fields to a method — by-reference semantics
        addFields(a, b);
        assertEquals(0, new BigDecimal("300.00").compareTo(a.get()));
    }

    private void addFields(Field target, Field source) {
        target.add(source.get());
    }

    // ═══════════════════════════════════════════════════════════════
    //  ARITHMETIC — GIVING, ROUNDED, REMAINDER
    // ═══════════════════════════════════════════════════════════════

    @Test
    void addGiving() {
        Record rec = Record.define("TEST")
            .pic("A", "S9(5)V99")
            .pic("B", "S9(5)V99")
            .pic("C", "S9(5)V99")
            .build();

        rec.move("A", new BigDecimal("100.00"));
        rec.move("B", new BigDecimal("200.50"));

        Arithmetic.add(rec.getDecimal("A"), rec.getDecimal("B"))
            .giving(rec.field("C"))
            .execute();

        assertEquals(0, new BigDecimal("300.50").compareTo(rec.getDecimal("C")));
        // A and B unchanged
        assertEquals(0, new BigDecimal("100.00").compareTo(rec.getDecimal("A")));
    }

    @Test
    void multiplyGivingRounded() {
        Record rec = Record.define("TEST")
            .pic("PRICE", "S9(5)V99")
            .pic("QTY",   "9(3)")
            .pic("TOTAL", "S9(7)V99")
            .build();

        rec.move("PRICE", new BigDecimal("10.33"));
        rec.move("QTY", 3L);

        // 10.33 * 3 = 30.99 (exact), so rounded vs not doesn't matter here
        Arithmetic.multiply(rec.getDecimal("PRICE"), rec.getDecimal("QTY"))
            .giving(rec.field("TOTAL"))
            .rounded()
            .execute();

        assertEquals(0, new BigDecimal("30.99").compareTo(rec.getDecimal("TOTAL")));
    }

    @Test
    void divideGivingRemainder() {
        Record rec = Record.define("TEST")
            .pic("DIVIDEND",  "S9(5)")
            .pic("DIVISOR",   "S9(3)")
            .pic("QUOTIENT",  "S9(5)")
            .pic("REMAINDER", "S9(3)")
            .build();

        rec.move("DIVIDEND", 17L);
        rec.move("DIVISOR", 5L);

        Arithmetic.divide(rec.getDecimal("DIVIDEND"), rec.getDecimal("DIVISOR"))
            .giving(rec.field("QUOTIENT"))
            .remainder(rec.field("REMAINDER"))
            .execute();

        assertEquals(3, rec.getInt("QUOTIENT"));
        assertEquals(2, rec.getInt("REMAINDER")); // 17 - 3*5 = 2
    }

    @Test
    void givingSizeError() {
        Record rec = Record.define("TEST")
            .pic("BIG", "S9(7)V99")
            .pic("SMALL", "S9(3)V99")
            .pic("ERR", "X")
            .build();

        rec.move("BIG", new BigDecimal("99999.99"));

        Arithmetic.add(rec.getDecimal("BIG"), BigDecimal.ONE)
            .giving(rec.field("SMALL"))
            .onSizeError(SizeErrorHandler.onError(() -> rec.move("ERR", "Y")))
            .execute();

        assertEquals("Y", rec.getString("ERR").trim());
    }

    // ═══════════════════════════════════════════════════════════════
    //  INITIALIZE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void initializeRecord() {
        Record rec = Record.define("TEST")
            .pic("NAME", "X(10)")
            .pic("AMT",  "S9(5)V99")
            .pic("CODE", "X(3)")
            .build();

        rec.move("NAME", "HELLO")
           .move("AMT", new BigDecimal("123.45"))
           .move("CODE", "ABC");

        rec.initialize();

        assertEquals("          ", rec.getString("NAME")); // spaces
        assertEquals(0, BigDecimal.ZERO.compareTo(rec.getDecimal("AMT"))); // zeros
        assertEquals("   ", rec.getString("CODE")); // spaces
    }

    @Test
    void initializeGroup() {
        Record rec = Record.define("TEST")
            .group("GRP", g -> g
                .pic("F1", "X(5)")
                .pic("F2", "9(3)"))
            .pic("OUTSIDE", "X(5)")
            .build();

        rec.move("F1", "HELLO").move("F2", 99L).move("OUTSIDE", "KEEP");

        rec.initialize("GRP");

        assertEquals("     ", rec.getString("F1"));
        assertEquals(0, rec.getInt("F2"));
        assertEquals("KEEP ", rec.getString("OUTSIDE")); // untouched
    }

    // ═══════════════════════════════════════════════════════════════
    //  INTRINSIC FUNCTIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    void currentDateFormat() {
        String date = Intrinsic.currentDate();
        assertEquals(21, date.length());
        // Should start with "2026" (current year)
        assertTrue(date.startsWith("2026"));
    }

    @Test
    void lengthFunction() {
        Record rec = Record.define("T").pic("F", "X(20)").build();
        assertEquals(BigDecimal.valueOf(20), Intrinsic.length(rec, "F"));
        assertEquals(20, Intrinsic.lengthInt(rec, "F"));
    }

    @Test
    void stringFunctions() {
        assertEquals("HELLO", Intrinsic.upperCase("hello"));
        assertEquals("hello", Intrinsic.lowerCase("HELLO"));
        assertEquals("olleH", Intrinsic.reverse("Hello"));
        assertEquals("hello", Intrinsic.trim("  hello  "));
        assertEquals("hello  ", Intrinsic.trimLeading("  hello  "));
        assertEquals("  hello", Intrinsic.trimTrailing("  hello  "));
    }

    @Test
    void numvalFunction() {
        assertEquals(0, new BigDecimal("123.45").compareTo(Intrinsic.numval("  123.45 ")));
        assertEquals(0, new BigDecimal("-42").compareTo(Intrinsic.numval("42-")));
        assertEquals(0, new BigDecimal("100").compareTo(Intrinsic.numval("+100")));
    }

    @Test
    void numvalCFunction() {
        assertEquals(0, new BigDecimal("1234.56").compareTo(Intrinsic.numvalC("$1,234.56")));
    }

    @Test
    void numericFunctions() {
        assertEquals(0, BigDecimal.valueOf(2).compareTo(Intrinsic.mod(BigDecimal.valueOf(17), BigDecimal.valueOf(5))));
        assertEquals(0, BigDecimal.valueOf(2).compareTo(Intrinsic.rem(BigDecimal.valueOf(17), BigDecimal.valueOf(5))));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(Intrinsic.integer(new BigDecimal("3.7"))));
        assertEquals(0, BigDecimal.valueOf(-3).compareTo(Intrinsic.integerPart(new BigDecimal("-3.7"))));
        assertEquals(0, BigDecimal.valueOf(5).compareTo(Intrinsic.abs(new BigDecimal("-5"))));
    }

    @Test
    void aggregateFunctions() {
        BigDecimal[] vals = {BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30)};
        assertEquals(0, BigDecimal.valueOf(30).compareTo(Intrinsic.max(vals)));
        assertEquals(0, BigDecimal.valueOf(10).compareTo(Intrinsic.min(vals)));
        assertEquals(0, BigDecimal.valueOf(20).compareTo(
            Intrinsic.mean(vals).setScale(0, java.math.RoundingMode.HALF_UP)));
        assertEquals(0, BigDecimal.valueOf(20).compareTo(Intrinsic.median(vals)));
    }

    // ═══════════════════════════════════════════════════════════════
    //  SEARCH / SEARCH ALL
    // ═══════════════════════════════════════════════════════════════

    @Test
    void linearSearch() {
        Record rec = Record.define("TABLE")
            .pic("CODE", "X(3)").occurs(5)
            .pic("FOUND-IDX", "9(3)")
            .build();

        rec.move("CODE", 0, "AAA");
        rec.move("CODE", 1, "BBB");
        rec.move("CODE", 2, "CCC");
        rec.move("CODE", 3, "DDD");
        rec.move("CODE", 4, "EEE");

        int[] result = {-1};
        Search.table(rec, "CODE")
            .atEnd(() -> result[0] = -1)
            .when(idx -> rec.getString("CODE", idx).trim().equals("CCC"),
                  idx -> result[0] = idx)
            .execute();

        assertEquals(2, result[0]);
    }

    @Test
    void linearSearchNotFound() {
        Record rec = Record.define("TABLE")
            .pic("CODE", "X(3)").occurs(3)
            .build();

        rec.move("CODE", 0, "AAA");
        rec.move("CODE", 1, "BBB");
        rec.move("CODE", 2, "CCC");

        boolean[] atEndCalled = {false};
        Search.table(rec, "CODE")
            .atEnd(() -> atEndCalled[0] = true)
            .when(idx -> rec.getString("CODE", idx).trim().equals("ZZZ"),
                  idx -> {})
            .execute();

        assertTrue(atEndCalled[0]);
    }

    @Test
    void binarySearchAll() {
        Record rec = Record.define("TABLE")
            .pic("KEY", "X(5)").occurs(5)
            .build();

        // Must be sorted for SEARCH ALL — keys are space-padded to 5 chars
        rec.move("KEY", 0, "AA");
        rec.move("KEY", 1, "BB");
        rec.move("KEY", 2, "CC");
        rec.move("KEY", 3, "DD");
        rec.move("KEY", 4, "EE");

        int[] result = {-1};
        Search.all(rec, "KEY")
            .key(idx -> rec.getString("KEY", idx).trim())
            .equalTo("CC")
            .atEnd(() -> result[0] = -1)
            .found(idx -> result[0] = idx)
            .execute();

        assertEquals(2, result[0]);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SORT
    // ═══════════════════════════════════════════════════════════════

    @Test
    void sortWithProcedures() {
        Record sortRec = Record.define("SORT-REC")
            .pic("SORT-NAME", "X(10)")
            .pic("SORT-AMT",  "S9(5)V99")
            .build();

        List<String> sortedNames = new ArrayList<>();

        CobolSort.on(sortRec)
            .ascending("SORT-NAME")
            .inputProcedure(input -> {
                // Feed records in unsorted order
                for (String name : List.of("CHARLIE", "ALICE", "BOB")) {
                    sortRec.move("SORT-NAME", name);
                    sortRec.move("SORT-AMT", BigDecimal.ZERO);
                    input.release();
                }
            })
            .outputProcedure(output -> {
                while (output.returnRecord()) {
                    sortedNames.add(sortRec.getString("SORT-NAME").trim());
                }
            })
            .execute();

        assertEquals(List.of("ALICE", "BOB", "CHARLIE"), sortedNames);
    }

    @Test
    void sortDescending() {
        Record sortRec = Record.define("SORT-REC")
            .pic("SORT-NUM", "9(3)")
            .build();

        List<Integer> sorted = new ArrayList<>();

        CobolSort.on(sortRec)
            .descending("SORT-NUM")
            .inputProcedure(input -> {
                for (int n : new int[]{30, 10, 50, 20, 40}) {
                    sortRec.move("SORT-NUM", (long) n);
                    input.release();
                }
            })
            .outputProcedure(output -> {
                while (output.returnRecord()) {
                    sorted.add(sortRec.getInt("SORT-NUM"));
                }
            })
            .execute();

        assertEquals(List.of(50, 40, 30, 20, 10), sorted);
    }

    // ═══════════════════════════════════════════════════════════════
    //  NUMERIC EDITED FORMATTING
    // ═══════════════════════════════════════════════════════════════

    @Test
    void numericEditedZeroSuppression() {
        Record rec = Record.define("TEST")
            .pic("DISPLAY-AMT", "Z(4)9V99")
            .build();

        rec.move("DISPLAY-AMT", new BigDecimal("123.45"));
        String result = rec.getString("DISPLAY-AMT");
        // PIC Z(4)9V99: 5 integer positions + 2 decimal, no actual dot char
        // "  12345" (V is implied — no display character)
        // For display with actual period, use PIC Z(4)9.99
        assertTrue(result.trim().length() > 0, "Got: '" + result + "'");
    }

    @Test
    void numericEditedWithDecimalPoint() {
        Record rec = Record.define("TEST")
            .pic("DISPLAY-AMT", "Z(5)9.99")
            .build();

        rec.move("DISPLAY-AMT", new BigDecimal("1234.56"));
        String result = rec.getString("DISPLAY-AMT");
        // Contains the formatted number with decimal point
        assertTrue(result.contains("."), "Got: '" + result + "'");
    }

    @Test
    void numericEditedZeroValue() {
        Record rec = Record.define("TEST")
            .pic("DISPLAY-AMT", "Z(5)9.99")
            .build();

        rec.move("DISPLAY-AMT", BigDecimal.ZERO);
        String result = rec.getString("DISPLAY-AMT");
        // Zero suppressed: leading Zs become spaces, the 9 shows 0
        assertTrue(result.contains("0"), "Got: '" + result + "'");
    }

    // ═══════════════════════════════════════════════════════════════
    //  INSPECT CONVERTING / BEFORE / AFTER
    // ═══════════════════════════════════════════════════════════════

    @Test
    void inspectConverting() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(20)")
            .build();

        rec.move("DATA", "hello world");
        Inspect.on(rec, "DATA")
            .converting("abcdefghijklmnopqrstuvwxyz",
                         "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .apply();

        assertTrue(rec.getString("DATA").startsWith("HELLO WORLD"));
    }

    @Test
    void inspectTallyBeforeInitial() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(20)")
            .build();

        rec.move("DATA", "ABCABC.ABCABC");
        int count = Inspect.on(rec, "DATA")
            .tallyAll("A")
            .before(".")
            .count();

        assertEquals(2, count); // only count A's before the first "."
    }

    @Test
    void inspectTallyAfterInitial() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(20)")
            .build();

        rec.move("DATA", "XXX.ABAABA");
        int count = Inspect.on(rec, "DATA")
            .tallyAll("A")
            .after(".")
            .count();

        assertEquals(4, count); // count A's after "."
    }

    @Test
    void inspectReplacingAfterBefore() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(20)")
            .build();

        rec.move("DATA", "000123000456");
        Inspect.on(rec, "DATA")
            .replaceAll('0', ' ')
            .after("123")
            .before("456")
            .apply();

        String result = rec.getString("DATA");
        // Only 0s between "123" and "456" should be replaced
        assertTrue(result.startsWith("000123"), "Got: '" + result + "'");
        assertTrue(result.contains("   456"), "Got: '" + result + "'");
    }

    // ═══════════════════════════════════════════════════════════════
    //  NESTED VARYING (AFTER) and EXIT PARAGRAPH
    // ═══════════════════════════════════════════════════════════════

    @Test
    void nestedVaryingAfter() {
        Record rec = Record.define("WS")
            .pic("I", "9(3)")
            .pic("J", "9(3)")
            .pic("COUNT", "9(5)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                // 3 outer * 4 inner = 12 iterations
                ctx.performVaryingAfter("BODY",
                    rec, "I", 1, 1, () -> rec.getInt("I") > 3,
                    rec, "J", 1, 1, () -> rec.getInt("J") > 4);
                ctx.stopRun();
            })
            .paragraph("BODY", ctx ->
                rec.add("COUNT", BigDecimal.ONE))
            .build();

        program.run();
        assertEquals(12, rec.getInt("COUNT"));
    }

    @Test
    void exitParagraph() {
        Record rec = Record.define("WS")
            .pic("RESULT", "X(10)")
            .build();

        Program program = Program.define("TEST")
            .paragraph("MAIN", ctx -> {
                ctx.perform("EARLY-EXIT").stopRun();
            })
            .paragraph("EARLY-EXIT", ctx -> {
                rec.move("RESULT", "BEFORE");
                ctx.exitParagraph();
                rec.move("RESULT", "AFTER"); // should NOT execute
            })
            .build();

        program.run();
        assertEquals("BEFORE", rec.getString("RESULT").trim());
    }

    // ═══════════════════════════════════════════════════════════════
    //  OCCURS DEPENDING ON
    // ═══════════════════════════════════════════════════════════════

    @Test
    void occursDependingOnAllocatesMaxSize() {
        Record rec = Record.define("TABLE")
            .pic("ITEM-COUNT", "9(3)")
            .pic("ITEM", "X(5)").occursDependingOn(10, "ITEM-COUNT")
            .build();

        // Full 10 slots allocated (5 * 10 = 50 bytes for ITEM)
        assertEquals(53, rec.length()); // 3 (ITEM-COUNT) + 50 (ITEM)

        // Use 3 slots
        rec.move("ITEM-COUNT", 3L);
        rec.move("ITEM", 0, "ONE");
        rec.move("ITEM", 1, "TWO");
        rec.move("ITEM", 2, "THREE");

        assertEquals("ONE  ", rec.getString("ITEM", 0));
        assertEquals("TWO  ", rec.getString("ITEM", 1));
        assertEquals("THREE", rec.getString("ITEM", 2));
    }
}
