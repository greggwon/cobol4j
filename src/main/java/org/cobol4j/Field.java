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

/**
 * A live reference to a field within a Record's byte buffer.
 * <p>
 * A Field is a bound pointer — it knows its Record, its definition, and its
 * byte offset. Reading or writing through a Field reads or writes the Record's
 * backing buffer directly. Fields can be stored in variables, passed to methods,
 * and used as by-reference parameters — exactly like COBOL's data items.
 * <p>
 * This solves the "stringly-typed" problem: instead of passing field names as
 * Strings everywhere, you pass Field references that are type-safe, auto-completing,
 * and can be validated at construction time rather than at every use.
 * <pre>{@code
 * // Get field references (validate once)
 * Field name    = rec.field("CUST-NAME");
 * Field balance = rec.field("CUST-BALANCE");
 * Field status  = rec.field("CUST-STATUS");
 *
 * // Use as by-reference handles — fluent chaining
 * name.move("JOHN DOE");
 * balance.move(Decimal.of("50000.00"))
 *        .add(Decimal.of("100.00"))
 *        .subtract(Decimal.of("25.50"));
 * status.set("ACTIVE");
 *
 * // Pass to SORT, SEARCH, GIVING, CALL — real by-reference
 * Arithmetic.multiply(price.get(), qty.get())
 *           .giving(total)
 *           .rounded()
 *           .execute();
 * }</pre>
 */
public final class Field {

    private final Record record;
    private final FieldDef def;
    private final int offset;       // effective offset (accounts for OCCURS index)
    private final int effectiveSize; // element size (stride for OCCURS, size otherwise)

    Field(Record record, FieldDef def) {
        this.record = record;
        this.def = def;
        this.offset = def.offset();
        this.effectiveSize = def.isArray() ? def.stride() : def.size();
    }

    Field(Record record, FieldDef def, int occursIndex) {
        this.record = record;
        this.def = def;
        this.offset = def.offsetForIndex(occursIndex);
        this.effectiveSize = def.isArray() ? def.stride() : def.size();
    }

    // ── Identity ────────────────────────────────────────────────────

    public String name()      { return def.name(); }
    public FieldDef def()     { return def; }
    public Record record()    { return record; }
    public Pic pic()          { return def.pic(); }
    public Usage usage()      { return def.usage(); }
    public int offset()       { return offset; }
    public int size()         { return effectiveSize; }
    public boolean isNumeric(){ return def.isNumeric(); }
    public boolean isGroup()  { return def.isGroup(); }

    // ── Read ────────────────────────────────────────────────────────

    /** Get the display-format String value. */
    public String getString() {
        return record.getString(def.name());
    }

    /** Get the trimmed String value. */
    public String trimmed() {
        return getString().trim();
    }

    /** Get the numeric value as Decimal. */
    public Decimal get() {
        return record.getDecimal(def.name());
    }

    /** Get the numeric value as int. */
    public int getInt() {
        return record.getInt(def.name());
    }

    /** Get the numeric value as long. */
    public long getLong() {
        return record.getLong(def.name());
    }

    // ── MOVE (write) — fluent, returns this ─────────────────────────

    /** MOVE a String value. */
    public Field move(String value) {
        record.move(def.name(), value);
        return this;
    }

    /** MOVE a numeric value. */
    public Field move(Decimal value) {
        record.move(def.name(), value);
        return this;
    }

    /** MOVE a long value. */
    public Field move(long value) {
        record.move(def.name(), value);
        return this;
    }


    /** MOVE from another field (by reference). */
    public Field move(Field source) {
        record.move(def.name(), source.record, source.def.name());
        return this;
    }

    /** MOVE SPACES. */
    public Field moveSpaces() {
        record.moveSpaces(def.name());
        return this;
    }

    /** MOVE ZEROS. */
    public Field moveZeros() {
        record.moveZeros(def.name());
        return this;
    }

    // ── Arithmetic — fluent, returns this ───────────────────────────

    public Field add(Decimal value) {
        record.add(def.name(), value);
        return this;
    }

    public Field add(Decimal value, SizeErrorHandler handler) {
        record.add(def.name(), value, handler);
        return this;
    }

    public Field subtract(Decimal value) {
        record.subtract(def.name(), value);
        return this;
    }

    public Field subtract(Decimal value, SizeErrorHandler handler) {
        record.subtract(def.name(), value, handler);
        return this;
    }

    public Field multiply(Decimal value) {
        record.multiply(def.name(), value);
        return this;
    }

    public Field multiply(Decimal value, SizeErrorHandler handler) {
        record.multiply(def.name(), value, handler);
        return this;
    }

    public Field divide(Decimal value) {
        record.divide(def.name(), value);
        return this;
    }

    public Field divide(Decimal value, SizeErrorHandler handler) {
        record.divide(def.name(), value, handler);
        return this;
    }

    public Field compute(Decimal value) {
        record.compute(def.name(), value);
        return this;
    }

    public Field compute(Decimal value, SizeErrorHandler handler) {
        record.compute(def.name(), value, handler);
        return this;
    }

    // ── Conditions ──────────────────────────────────────────────────

    /** Test a level-88 condition by name. */
    public boolean is(String conditionName) {
        return record.is(conditionName);
    }

    /** SET a level-88 condition. */
    public Field set(String conditionName) {
        record.set(conditionName);
        return this;
    }

    // ── OCCURS indexing ─────────────────────────────────────────────

    /** Get a Field reference to a specific OCCURS element (0-based). */
    public Field at(int index) {
        return new Field(record, def, index);
    }

    // ── Comparison helpers (useful for SEARCH, EVALUATE, etc.) ──────

    public boolean equalTo(String value) {
        return getString().equals(value);
    }

    public boolean equalTo(Decimal value) {
        return get().compareTo(value) == 0;
    }

    public boolean equalTo(Field other) {
        if (isNumeric() && other.isNumeric()) {
            return get().compareTo(other.get()) == 0;
        }
        return getString().equals(other.getString());
    }

    public boolean greaterThan(Decimal value) {
        return get().compareTo(value) > 0;
    }

    public boolean lessThan(Decimal value) {
        return get().compareTo(value) < 0;
    }

    @Override
    public String toString() {
        return def.name() + "=" + (isNumeric() ? get() : trimmed());
    }
}
