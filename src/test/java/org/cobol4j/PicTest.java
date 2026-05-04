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

class PicTest {

    @Test
    void parseAlphanumeric() {
        Pic pic = Pic.parse("X(20)");
        assertEquals(Pic.Category.ALPHANUMERIC, pic.category());
        assertEquals(20, pic.displaySize());
        assertFalse(pic.isSigned());
        assertEquals(0, pic.totalDigits());
    }

    @Test
    void parseAlphabetic() {
        Pic pic = Pic.parse("A(10)");
        assertEquals(Pic.Category.ALPHABETIC, pic.category());
        assertEquals(10, pic.displaySize());
    }

    @Test
    void parseSimpleNumeric() {
        Pic pic = Pic.parse("9(5)");
        assertEquals(Pic.Category.NUMERIC, pic.category());
        assertEquals(5, pic.integerDigits());
        assertEquals(0, pic.decimalDigits());
        assertEquals(5, pic.totalDigits());
        assertEquals(5, pic.displaySize());
        assertFalse(pic.isSigned());
    }

    @Test
    void parseSignedNumericWithDecimal() {
        Pic pic = Pic.parse("S9(7)V99");
        assertEquals(Pic.Category.NUMERIC, pic.category());
        assertTrue(pic.isSigned());
        assertEquals(7, pic.integerDigits());
        assertEquals(2, pic.decimalDigits());
        assertEquals(9, pic.totalDigits());
        assertEquals(9, pic.displaySize()); // V takes no storage
    }

    @Test
    void parseNumericNoParens() {
        Pic pic = Pic.parse("S999V99");
        assertTrue(pic.isSigned());
        assertEquals(3, pic.integerDigits());
        assertEquals(2, pic.decimalDigits());
    }

    @Test
    void comp3StorageSize() {
        Pic pic = Pic.parse("S9(7)V99"); // 9 digits
        assertEquals(5, pic.storageSize(Usage.COMP3)); // (9+2)/2 = 5
    }

    @Test
    void comp3StorageSizeSmall() {
        Pic pic = Pic.parse("S9(4)"); // 4 digits
        assertEquals(3, pic.storageSize(Usage.COMP3)); // (4+2)/2 = 3
    }

    @Test
    void binaryStorageSize() {
        assertEquals(2, Pic.parse("9(4)").storageSize(Usage.COMP));   // 1-4 digits = 2 bytes
        assertEquals(4, Pic.parse("9(7)").storageSize(Usage.COMP));   // 5-9 digits = 4 bytes
        assertEquals(8, Pic.parse("9(15)").storageSize(Usage.COMP));  // 10-18 digits = 8 bytes
    }

    @Test
    void expandRepetitions() {
        assertEquals("SSSSS", Pic.expand("S(5)"));
        assertEquals("999V99", Pic.expand("9(3)V9(2)"));
        assertEquals("X", Pic.expand("X"));
    }

    @Test
    void numericEdited() {
        Pic pic = Pic.parse("Z(5)9.99");
        assertEquals(Pic.Category.NUMERIC_EDITED, pic.category());
        assertEquals(9, pic.displaySize()); // ZZZZZ9.99 = 9 chars
    }

    @Test
    void picEditedWithActualDecimalPoint() {
        Pic pic = Pic.parse("Z(5)9.99");
        assertEquals(6, pic.integerDigits());  // ZZZZZ9
        assertEquals(2, pic.decimalDigits());  // 99
        assertEquals(9, pic.displaySize());    // 6 + 1(dot) + 2
    }

    @Test
    void picEditedDollarSign() {
        Pic pic = Pic.parse("$$$,$$9.99");
        assertTrue(pic.integerDigits() > 0);
        assertEquals(2, pic.decimalDigits());
    }
}
