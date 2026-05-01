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
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DecimalTest {

    @Test
    void stringConstruction() {
        Decimal d = Decimal.of("123.45");
        assertEquals("123.45", d.toString());
        assertEquals(0, d.compareTo(Decimal.of("123.45")));
    }

    @Test
    void longConstruction() {
        Decimal d = Decimal.of(100);
        assertEquals("100", d.toString());
        assertTrue(d.equalTo(Decimal.of("100")));
    }

    @Test
    void noDoubleConstructor() {
        // There is no Decimal.of(double) — by design.
        // You must use String: Decimal.of("0.1") not Decimal.of(0.1)
        Decimal d = Decimal.of("0.1");
        // This is exact — no 0.10000000000000001 nonsense
        assertEquals("0.1", d.toString());
    }

    @Test
    void weakReferenceCache() {
        Decimal a = Decimal.of("19.99");
        Decimal b = Decimal.of("19.99");
        // Same instance from cache (may not hold under GC pressure, but in practice works)
        assertSame(a, b);
    }

    @Test
    void arithmetic() {
        Decimal price = Decimal.of("19.99");
        Decimal qty = Decimal.of("5");
        Decimal total = price.multiply(qty);
        assertTrue(total.equalTo(Decimal.of("99.95")));
    }

    @Test
    void divisionWithScale() {
        Decimal total = Decimal.of("100.00");
        Decimal parts = Decimal.of("3");
        Decimal each = total.divide(parts, 2);
        assertEquals("33.33", each.toString());
    }

    @Test
    void remainder() {
        Decimal a = Decimal.of("17");
        Decimal b = Decimal.of("5");
        Decimal rem = a.remainder(b);
        assertTrue(rem.equalTo(Decimal.of("2")));
    }

    @Test
    void comparison() {
        Decimal a = Decimal.of("100.00");
        Decimal b = Decimal.of("200.00");
        assertTrue(a.lessThan(b));
        assertTrue(b.greaterThan(a));
        assertFalse(a.equalTo(b));
        assertTrue(Decimal.ZERO.isZero());
        assertTrue(Decimal.of("-5").isNegative());
        assertTrue(Decimal.of("5").isPositive());
    }

    @Test
    void valueTracking() {
        List<String> log = new ArrayList<>();
        Decimal.setTracker(new ValueTracker() {
            @Override
            public void onArithmetic(Decimal left, String op, Decimal right, Decimal result) {
                log.add(left + " " + op + " " + right + " = " + result);
            }
        });

        try {
            Decimal a = Decimal.of("100");
            Decimal b = Decimal.of("50");
            a.add(b);       // tracked
            a.multiply(b);  // tracked

            assertEquals(2, log.size());
            assertEquals("100 + 50 = 150", log.get(0));
            assertEquals("100 * 50 = 5000", log.get(1));
        } finally {
            Decimal.setTracker(ValueTracker.NOOP);
        }
    }

    // ── Variable tests ──────────────────────────────────────────────

    @Test
    void variableDecimal() {
        Variable<Decimal> balance = Variable.named("CUST-BALANCE").decimal("S9(7)V99");

        balance.set(Decimal.of("500.00"));
        assertEquals("CUST-BALANCE", balance.name());
        assertTrue(balance.get().equalTo(Decimal.of("500.00")));

        balance.add(Decimal.of("100.00"));
        assertTrue(balance.get().equalTo(Decimal.of("600.00")));

        balance.subtract(Decimal.of("50.00"));
        assertTrue(balance.get().equalTo(Decimal.of("550.00")));
    }

    @Test
    void variableText() {
        Variable<String> name = Variable.named("CUST-NAME").text(20);

        name.set("ALICE SMITH");
        assertEquals("ALICE SMITH", name.trimmed());
        assertEquals(Variable.VType.TEXT, name.type());
    }

    @Test
    void variableBoundToRecord() {
        Record rec = Record.define("TEST")
            .pic("BAL", "S9(5)V99")
            .pic("NAME", "X(10)")
            .build();

        Variable<Decimal> bal = Variable.named("BAL").decimal("S9(5)V99");
        bal.bindTo(rec, "BAL");

        // Write through Variable → Record is updated
        bal.set(Decimal.of("123.45"));
        assertEquals(0, new java.math.BigDecimal("123.45").compareTo(rec.getDecimal("BAL")));

        // Read through Variable ← Record value
        rec.move("BAL", new java.math.BigDecimal("999.99"));
        assertTrue(bal.get().equalTo(Decimal.of("999.99")));
    }

    @Test
    void variableTracksChanges() {
        List<String> changes = new ArrayList<>();
        Decimal.setTracker(new ValueTracker() {
            @Override
            public void onVariableChanged(String name, Decimal oldValue, Decimal newValue) {
                changes.add(name + ": " + oldValue + " -> " + newValue);
            }
        });

        try {
            Variable<Decimal> total = Variable.named("WS-TOTAL").decimal("S9(7)V99");
            total.set(Decimal.of("100.00"));
            total.add(Decimal.of("50.00"));

            assertEquals(2, changes.size());
            assertTrue(changes.get(0).contains("WS-TOTAL"));
            assertTrue(changes.get(1).contains("WS-TOTAL"));
        } finally {
            Decimal.setTracker(ValueTracker.NOOP);
        }
    }
}
