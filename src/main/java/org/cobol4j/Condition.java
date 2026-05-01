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

import java.util.List;
import java.util.function.Predicate;

/**
 * Represents a COBOL level-88 condition name.
 * <p>
 * Level-88s are named boolean tests bound to a field. A condition is true when
 * the field's current value matches one of the declared values or falls within
 * a declared range.
 * <p>
 * The interface is functional — custom conditions can be supplied as lambdas:
 * <pre>{@code
 *   Condition custom = Condition.of("HIGH-BALANCE",
 *       (value) -> new BigDecimal(value.trim()).compareTo(threshold) > 0);
 * }</pre>
 */
@FunctionalInterface
public interface Condition {

    /**
     * Test whether this condition is satisfied by the given field value.
     *
     * @param currentValue the field's current display-format value
     * @return true if the condition holds
     */
    boolean test(String currentValue);

    /**
     * The condition's name (e.g., "ACTIVE"). Defaults to "ANONYMOUS".
     */
    default String name() { return "ANONYMOUS"; }

    /**
     * The value to SET the field to when this condition is activated via
     * {@code record.set("ACTIVE")}. Returns null if the condition has no
     * settable value (e.g., range or custom conditions).
     */
    default String setValue() { return null; }

    // ── Factory methods ─────────────────────────────────────────────

    /**
     * Standard 88-level: true when the field equals one of the given values.
     */
    static Condition values(String name, String... values) {
        List<String> expected = List.of(values);
        String primary = values[0]; // SET uses the first value
        return new Condition() {
            @Override public boolean test(String v) { return expected.contains(v); }
            @Override public String name()          { return name; }
            @Override public String setValue()       { return primary; }
        };
    }

    /**
     * Range 88-level: true when the field value falls within [from, to] inclusive,
     * using the natural string ordering (which matches COBOL alphanumeric comparison).
     */
    static Condition range(String name, String from, String to) {
        return new Condition() {
            @Override public boolean test(String v) {
                return v.compareTo(from) >= 0 && v.compareTo(to) <= 0;
            }
            @Override public String name() { return name; }
            @Override public String setValue() { return from; }
        };
    }

    /**
     * Custom condition backed by a lambda. Fully extensible — the predicate
     * can implement any business logic.
     */
    static Condition of(String name, Predicate<String> test) {
        return new Condition() {
            @Override public boolean test(String v) { return test.test(v); }
            @Override public String name()          { return name; }
        };
    }
}
