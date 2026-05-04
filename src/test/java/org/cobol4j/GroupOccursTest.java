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
 * Tests for group-level OCCURS — child fields of a repeating group
 * can be accessed by index.
 */
class GroupOccursTest {

    @Test
    void groupLevelOccursWithChildAccess() {
        Record table = Record.define("PRICE-TABLE")
            .group("PRICE-ENTRY", g -> g
                .pic("ITEM-CODE", "X(10)")
                .pic("ITEM-PRICE", "S9(5)V99"))
            .occurs(5)
            .build();

        // Write to different occurrences
        table.move("ITEM-CODE", 0, "WIDGET");
        table.move("ITEM-CODE", 1, "GADGET");
        table.move("ITEM-CODE", 2, "DEVICE");

        table.move("ITEM-PRICE", 0, Decimal.of("29.99"));
        table.move("ITEM-PRICE", 1, Decimal.of("49.99"));
        table.move("ITEM-PRICE", 2, Decimal.of("99.99"));

        // Read back — each occurrence is independent
        assertEquals("WIDGET", table.getString("ITEM-CODE", 0).trim());
        assertEquals("GADGET", table.getString("ITEM-CODE", 1).trim());
        assertEquals("DEVICE", table.getString("ITEM-CODE", 2).trim());

        assertTrue(table.getDecimal("ITEM-PRICE", 0).equalTo(Decimal.of("29.99")));
        assertTrue(table.getDecimal("ITEM-PRICE", 1).equalTo(Decimal.of("49.99")));
        assertTrue(table.getDecimal("ITEM-PRICE", 2).equalTo(Decimal.of("99.99")));

        // Total record size: 5 * (10 + 7) = 85 bytes
        assertEquals(85, table.length());
    }

    @Test
    void searchOnGroupOccursChild() {
        Record table = Record.define("TABLE")
            .group("ENTRY", g -> g
                .pic("KEY", "X(5)")
                .pic("VAL", "X(10)"))
            .occurs(3)
            .build();

        table.move("KEY", 0, "AAA");
        table.move("KEY", 1, "BBB");
        table.move("KEY", 2, "CCC");
        table.move("VAL", 0, "FIRST");
        table.move("VAL", 1, "SECOND");
        table.move("VAL", 2, "THIRD");

        int[] found = {-1};
        Search.table(table, "KEY")
            .when(idx -> table.getString("KEY", idx).trim().equals("BBB"),
                  idx -> found[0] = idx)
            .execute();

        assertEquals(1, found[0]);
        assertEquals("SECOND", table.getString("VAL", found[0]).trim());
    }

    @Test
    void groupOccursChildFieldDefHasCorrectProperties() {
        Record table = Record.define("T")
            .group("GRP", g -> g
                .pic("A", "X(5)")
                .pic("B", "X(3)"))
            .occurs(4)
            .build();

        FieldDef grp = table.fieldDef("GRP");
        FieldDef a = table.fieldDef("A");
        FieldDef b = table.fieldDef("B");

        // Group itself
        assertEquals(4, grp.occurs());
        assertEquals(8, grp.stride()); // 5 + 3 = 8 bytes per occurrence

        // Children inherit occurs from parent group
        assertEquals(4, a.occurs());
        assertEquals(8, a.stride()); // stride = parent's stride
        assertTrue(a.isArray());

        assertEquals(4, b.occurs());
        assertEquals(8, b.stride());
        assertTrue(b.isArray());

        // Offsets: A starts at 0, B starts at 5 (within one occurrence)
        assertEquals(0, a.offset());
        assertEquals(5, b.offset());

        // offsetForIndex computes correctly
        assertEquals(0, a.offsetForIndex(0));
        assertEquals(8, a.offsetForIndex(1));
        assertEquals(16, a.offsetForIndex(2));

        assertEquals(5, b.offsetForIndex(0));
        assertEquals(13, b.offsetForIndex(1));
        assertEquals(21, b.offsetForIndex(2));
    }

    @Test
    void nestedGroupWithOccursDoesNotAffectSiblings() {
        // A record with a group-OCCURS table followed by a regular field
        Record rec = Record.define("REC")
            .group("TABLE", g -> g
                .pic("CODE", "X(3)")
                .pic("AMT", "9(5)"))
            .occurs(3)
            .pic("TOTAL", "9(7)")
            .build();

        // Total size: 3 * (3 + 5) + 7 = 31
        assertEquals(31, rec.length());

        // The TOTAL field should NOT be an array
        FieldDef total = rec.fieldDef("TOTAL");
        assertFalse(total.isArray());
        assertEquals(24, total.offset()); // after 3*8 bytes

        // Table children work correctly
        rec.move("CODE", 0, "AAA");
        rec.move("CODE", 1, "BBB");
        rec.move("CODE", 2, "CCC");
        assertEquals("AAA", rec.getString("CODE", 0).trim());
        assertEquals("BBB", rec.getString("CODE", 1).trim());
        assertEquals("CCC", rec.getString("CODE", 2).trim());
    }
}
