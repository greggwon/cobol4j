package org.cobol4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StringOpsTest {

    // ── INSPECT ─────────────────────────────────────────────────────

    @Test
    void inspectTallyAll() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(20)")
            .build();

        rec.move("DATA", "HELLO WORLD");
        int count = Inspect.on(rec, "DATA").tallyAll("L").count();
        assertEquals(3, count);
    }

    @Test
    void inspectTallyLeading() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(10)")
            .build();

        rec.move("DATA", "000123");
        int count = Inspect.on(rec, "DATA").tallyLeading('0').count();
        assertEquals(3, count);
    }

    @Test
    void inspectReplaceLeadingZeros() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(10)")
            .build();

        rec.move("DATA", "000123");
        Inspect.on(rec, "DATA").replaceLeading('0', ' ').apply();
        assertEquals("   123    ", rec.getString("DATA"));
    }

    @Test
    void inspectCustomReplacer() {
        Record rec = Record.define("TEST")
            .pic("DATA", "X(10)")
            .build();

        rec.move("DATA", "1,234.56");
        Inspect.on(rec, "DATA")
            .replaceAll(ch -> ch == ',' ? '.' : ch == '.' ? ',' : ch)
            .apply();
        assertEquals("1.234,56  ", rec.getString("DATA"));
    }

    // ── STRING ──────────────────────────────────────────────────────

    @Test
    void stringConcatenation() {
        Record rec = Record.define("TEST")
            .pic("FIRST", "X(10)")
            .pic("LAST",  "X(10)")
            .pic("FULL",  "X(25)")
            .build();

        rec.move("FIRST", "JOHN");
        rec.move("LAST", "DOE");

        CobolString.into(rec, "FULL")
            .from(rec, "FIRST").delimitedBySpaces()
            .literal(" ")
            .from(rec, "LAST").delimitedBySpaces()
            .execute();

        assertTrue(rec.getString("FULL").startsWith("JOHN DOE"));
    }

    @Test
    void stringOverflowHandler() {
        Record rec = Record.define("TEST")
            .pic("SMALL", "X(5)")
            .pic("BIG",   "X(20)")
            .pic("ERR",   "X")
            .build();

        rec.move("BIG", "THIS IS WAY TOO LONG");

        CobolString.into(rec, "SMALL")
            .from(rec, "BIG").delimitedBySize()
            .onOverflow(() -> rec.move("ERR", "Y"))
            .execute();

        assertEquals("Y", rec.getString("ERR").trim());
        assertEquals("THIS ", rec.getString("SMALL")); // truncated to 5
    }

    // ── UNSTRING ────────────────────────────────────────────────────

    @Test
    void unstringBasic() {
        Record rec = Record.define("TEST")
            .pic("INPUT",  "X(30)")
            .pic("FIELD1", "X(10)")
            .pic("FIELD2", "X(10)")
            .pic("FIELD3", "X(10)")
            .build();

        rec.move("INPUT", "APPLE,BANANA,CHERRY");

        int count = CobolUnstring.from(rec, "INPUT")
            .delimitedBy(",")
            .into(rec, "FIELD1")
            .into(rec, "FIELD2")
            .into(rec, "FIELD3")
            .execute();

        assertEquals(3, count);
        assertEquals("APPLE     ", rec.getString("FIELD1"));
        assertEquals("BANANA    ", rec.getString("FIELD2"));
        assertEquals("CHERRY    ", rec.getString("FIELD3"));
    }

    @Test
    void unstringMultipleDelimiters() {
        Record rec = Record.define("TEST")
            .pic("INPUT",  "X(30)")
            .pic("FIELD1", "X(10)")
            .pic("FIELD2", "X(10)")
            .build();

        rec.move("INPUT", "HELLO WORLD,FOO");

        int count = CobolUnstring.from(rec, "INPUT")
            .delimitedBy(",")
            .orDelimitedBy(" ")
            .into(rec, "FIELD1")
            .into(rec, "FIELD2")
            .execute();

        assertEquals(2, count);
        assertEquals("HELLO     ", rec.getString("FIELD1"));
        // " " is found before "," at position 5, so WORLD,FOO goes to FIELD2
    }
}
