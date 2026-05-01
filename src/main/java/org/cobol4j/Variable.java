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

import java.util.Objects;

/**
 * A typed, named variable — the user-facing handle for COBOL data items.
 * <p>
 * Variable provides a clean API for hand-written code that uses cobol4j directly
 * (not just transpiled code). It combines naming, typing, and value tracking
 * into a single ergonomic object.
 * <p>
 * Construction uses a fluent naming pattern:
 * <pre>{@code
 * Variable<Decimal> balance   = Variable.named("CUST-BALANCE").decimal("S9(7)V99");
 * Variable<Decimal> count     = Variable.named("WS-COUNT").decimal("9(3)");
 * Variable<String>  custName  = Variable.named("CUST-NAME").text(20);
 * Variable<String>  status    = Variable.named("CUST-STATUS").text(1);
 *
 * // Type-safe access — no casting, no string lookups:
 * balance.set(Decimal.of("50000.00"));
 * balance.add(Decimal.of("100.00"));
 * String name = custName.get();
 * Decimal bal = balance.get();
 *
 * // Variables can be bound to a Record for persistence:
 * balance.bindTo(customerRecord, "CUST-BALANCE");
 * }</pre>
 *
 * @param <T> the value type: {@link Decimal} for numeric, {@link String} for alphanumeric
 */
public final class Variable<T> {

    /** The type classification for a Variable. */
    public enum VType {
        DECIMAL,   // Numeric — backed by Decimal (exact decimal arithmetic)
        TEXT,      // Alphanumeric — String with fixed-length semantics
        INTEGER    // Integer subset of DECIMAL (no decimal places)
    }

    private final String name;
    private final VType type;
    private final String pic;    // PIC clause if defined
    private final int size;      // byte size for TEXT, digit count for DECIMAL
    private T value;

    // Optional binding to a Record field
    private Record boundRecord;
    private String boundField;

    private Variable(String name, VType type, String pic, int size, T initialValue) {
        this.name = name;
        this.type = type;
        this.pic = pic;
        this.size = size;
        this.value = initialValue;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSTRUCTION — fluent naming pattern
    // ═══════════════════════════════════════════════════════════════

    /** Begin defining a variable with its COBOL name. */
    public static VariableBuilder named(String cobolName) {
        return new VariableBuilder(cobolName);
    }

    /**
     * Fluent builder for Variable construction.
     */
    public static final class VariableBuilder {
        private final String name;

        VariableBuilder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        /** Define as a decimal numeric with a PIC clause. */
        public Variable<Decimal> decimal(String pic) {
            return new Variable<>(name, VType.DECIMAL, pic, 0, Decimal.ZERO);
        }

        /** Define as a decimal numeric with initial value. */
        public Variable<Decimal> decimal(String pic, String initialValue) {
            return new Variable<>(name, VType.DECIMAL, pic, 0, Decimal.of(initialValue));
        }

        /** Define as a fixed-length text field. */
        public Variable<String> text(int size) {
            return new Variable<>(name, VType.TEXT, "X(" + size + ")", size,
                " ".repeat(size));
        }

        /** Define as a fixed-length text with initial value. */
        public Variable<String> text(int size, String initialValue) {
            String padded = initialValue.length() >= size
                ? initialValue.substring(0, size)
                : initialValue + " ".repeat(size - initialValue.length());
            return new Variable<>(name, VType.TEXT, "X(" + size + ")", size, padded);
        }

        /** Define as an integer (no decimal places). */
        public Variable<Decimal> integer(String pic) {
            return new Variable<>(name, VType.INTEGER, pic, 0, Decimal.ZERO);
        }

        /** Define as an integer with initial value. */
        public Variable<Decimal> integer(String pic, long initialValue) {
            return new Variable<>(name, VType.INTEGER, pic, 0, Decimal.of(initialValue));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ACCESS
    // ═══════════════════════════════════════════════════════════════

    /** Get the current value. If bound to a Record, reads from the record. */
    @SuppressWarnings("unchecked")
    public T get() {
        if (boundRecord != null) {
            if (type == VType.TEXT) {
                return (T) boundRecord.getString(boundField);
            } else {
                return (T) Decimal.wrap(boundRecord.getDecimal(boundField));
            }
        }
        return value;
    }

    /** Set the value. If bound to a Record, writes to the record. Tracks changes. */
    public Variable<T> set(T newValue) {
        T oldValue = this.value;
        this.value = newValue;

        if (boundRecord != null) {
            if (newValue instanceof Decimal d) {
                boundRecord.move(boundField, d.toBigDecimal());
            } else if (newValue instanceof String s) {
                boundRecord.move(boundField, s);
            }
        }

        // Track the change
        if (type != VType.TEXT && newValue instanceof Decimal dNew) {
            Decimal dOld = oldValue instanceof Decimal d ? d : null;
            Decimal.tracker().onVariableChanged(name, dOld, dNew);
        }

        return this;
    }

    /** The COBOL name. */
    public String name() { return name; }

    /** The type classification. */
    public VType type() { return type; }

    /** The PIC clause (if defined). */
    public String pic() { return pic; }

    // ═══════════════════════════════════════════════════════════════
    //  DECIMAL ARITHMETIC (only for numeric Variables)
    // ═══════════════════════════════════════════════════════════════

    /** ADD to this variable's value. Returns this for chaining. */
    @SuppressWarnings("unchecked")
    public Variable<T> add(Decimal amount) {
        requireNumeric();
        Decimal current = (Decimal) get();
        set((T) current.add(amount));
        return this;
    }

    /** SUBTRACT from this variable's value. */
    @SuppressWarnings("unchecked")
    public Variable<T> subtract(Decimal amount) {
        requireNumeric();
        Decimal current = (Decimal) get();
        set((T) current.subtract(amount));
        return this;
    }

    /** MULTIPLY this variable's value. */
    @SuppressWarnings("unchecked")
    public Variable<T> multiply(Decimal factor) {
        requireNumeric();
        Decimal current = (Decimal) get();
        set((T) current.multiply(factor));
        return this;
    }

    /** DIVIDE this variable's value. */
    @SuppressWarnings("unchecked")
    public Variable<T> divide(Decimal divisor, int scale) {
        requireNumeric();
        Decimal current = (Decimal) get();
        set((T) current.divide(divisor, scale));
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  TEXT OPERATIONS (only for text Variables)
    // ═══════════════════════════════════════════════════════════════

    /** Get the trimmed text value. */
    public String trimmed() {
        requireText();
        return ((String) get()).trim();
    }

    // ═══════════════════════════════════════════════════════════════
    //  RECORD BINDING — connect to a Record field for persistence
    // ═══════════════════════════════════════════════════════════════

    /**
     * Bind this Variable to a field in a Record.
     * After binding, get() reads from the Record and set() writes to it.
     * This connects the typed Variable API to the byte-buffer-backed Record.
     */
    public Variable<T> bindTo(Record record, String fieldName) {
        this.boundRecord = record;
        this.boundField = fieldName;
        return this;
    }

    /** Unbind from any Record. */
    public Variable<T> unbind() {
        this.boundRecord = null;
        this.boundField = null;
        return this;
    }

    /** Is this variable bound to a Record? */
    public boolean isBound() {
        return boundRecord != null;
    }

    // ── internals ───────────────────────────────────────────────────

    private void requireNumeric() {
        if (type == VType.TEXT) {
            throw new UnsupportedOperationException(
                "Arithmetic not supported on TEXT variable: " + name);
        }
    }

    private void requireText() {
        if (type != VType.TEXT) {
            throw new UnsupportedOperationException(
                "Text operation not supported on numeric variable: " + name);
        }
    }

    @Override
    public String toString() {
        return name + "=" + value;
    }
}
