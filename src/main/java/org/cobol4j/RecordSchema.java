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

import java.util.function.Consumer;

/**
 * Descriptive fluent API for building Records.
 * <p>
 * Each method returns a typed interface so that IDE code completion only shows
 * valid next operations. This is the primary API for Java developers who don't
 * know COBOL. The COBOL-named methods ({@code .pic()}, {@code .value88()})
 * delegate to these methods internally.
 *
 * <pre>{@code
 * Record customer = RecordSchema.define("CUSTOMER")
 *     .field("CUST-ID").alphanumeric(10)
 *     .field("BALANCE").decimal(7, 2).packedDecimal()
 *     .field("STATUS").alphanumeric(1)
 *         .when("ACTIVE").is("A")
 *         .when("INACTIVE").is("I")
 *     .field("CREDIT-LIMIT").decimal(7, 2).binary()
 *     .group("ADDRESS", g -> g
 *         .field("STREET").alphanumeric(30)
 *         .field("CITY").alphanumeric(20)
 *         .field("STATE").alphanumeric(2))
 *     .build();
 * }</pre>
 */
public final class RecordSchema {

    private RecordSchema() {}

    /** Begin defining a record schema with the given name. */
    public static SchemaBuilder define(String name) {
        return new SchemaBuilderImpl(name);
    }

    // ═══════════════════════════════════════════════════════════════
    //  TYPED INTERFACES — each step shows only valid next operations
    // ═══════════════════════════════════════════════════════════════

    /** Top-level builder — add fields, groups, or build. */
    public interface SchemaBuilder {
        /** Define a new field. Returns a type selector. */
        FieldTypeSelector field(String name);
        /** Define a group of fields. */
        SchemaBuilder group(String name, Consumer<SchemaBuilder> children);
        /** Build the Record. */
        Record build();
    }

    /** After naming a field — choose its type. */
    public interface FieldTypeSelector {
        /** Fixed-length text (PIC X(n)). */
        FieldOptions alphanumeric(int size);
        /** Fixed-length alphabetic only (PIC A(n)). */
        FieldOptions alphabetic(int size);
        /** Exact decimal with integer and decimal digits (PIC S9(int)V9(dec)). */
        NumericFieldOptions decimal(int integerDigits, int decimalDigits);
        /** Integer with no decimal places (PIC 9(n)). */
        NumericFieldOptions integer(int digits);
        /** Signed integer (PIC S9(n)). */
        NumericFieldOptions signedInteger(int digits);
        /** Unicode/national character (PIC N(n)). */
        FieldOptions national(int size);
    }

    /** After choosing a field type — configure storage, add conditions, or move on. */
    public interface FieldOptions extends SchemaBuilder {
        /** Add a named condition (level-88). */
        ConditionValueSelector when(String conditionName);
        /** This field repeats N times. */
        FieldOptions occurs(int times);
        /** This field repeats up to max times, actual count in another field. */
        FieldOptions occursDependingOn(int maxTimes, String countField);
        /** Set an initial value. */
        FieldOptions initialValue(String value);
    }

    /** After choosing a numeric type — add storage format options. */
    public interface NumericFieldOptions extends FieldOptions {
        /** Store as packed decimal (COMP-3 / BCD). Two digits per byte. */
        FieldOptions packedDecimal();
        /** Store as native binary (COMP). */
        FieldOptions binary();
        /** Store as native binary with full range (COMP-5). */
        FieldOptions nativeBinary();
        /** Signed, sign position trailing (default). */
        NumericFieldOptions signed();
        /** Sign in leading position. */
        NumericFieldOptions signLeading();
        /** Sign as separate trailing character. */
        NumericFieldOptions signTrailingSeparate();
        /** Sign as separate leading character. */
        NumericFieldOptions signLeadingSeparate();
    }

    /** After naming a condition — provide its value(s). */
    public interface ConditionValueSelector {
        /** The condition is true when the field equals this value. */
        ConditionChain is(String value);
        /** The condition is true when the field is in this range. */
        FieldOptions through(String from, String to);
    }

    /** After setting a condition value — add more values, more conditions, or move on. */
    public interface ConditionChain extends FieldOptions {
        /** Additional value for the same condition (OR). */
        ConditionChain or(String value);
    }

    // ═══════════════════════════════════════════════════════════════
    //  IMPLEMENTATION — delegates to Record.Builder
    // ═══════════════════════════════════════════════════════════════

    private static class SchemaBuilderImpl implements SchemaBuilder, FieldTypeSelector,
            FieldOptions, NumericFieldOptions, ConditionValueSelector, ConditionChain {

        private final Record.Builder builder;
        private String pendingFieldName;
        private String currentConditionName;
        private java.util.List<String> currentConditionValues;

        SchemaBuilderImpl(String name) {
            this.builder = Record.define(name);
        }

        /** Wrap an existing Record.Builder (for group nesting). */
        SchemaBuilderImpl(Record.Builder existing) {
            this.builder = existing;
        }

        /** Flush any pending multi-value condition before moving on. */
        private void flushCondition() {
            if (currentConditionName != null && currentConditionValues != null
                && !currentConditionValues.isEmpty()) {
                builder.value88(currentConditionName,
                    currentConditionValues.toArray(new String[0]));
                currentConditionName = null;
                currentConditionValues = null;
            }
        }

        // ── SchemaBuilder ───────────────────────────────────────────

        @Override
        public FieldTypeSelector field(String name) {
            flushCondition();
            this.pendingFieldName = name;
            return this;
        }

        @Override
        public SchemaBuilder group(String name, Consumer<SchemaBuilder> children) {
            builder.group(name, innerBuilder -> {
                children.accept(new SchemaBuilderImpl(innerBuilder));
            });
            return this;
        }

        @Override
        public Record build() {
            flushCondition();
            return builder.build();
        }

        // ── FieldTypeSelector ───────────────────────────────────────

        @Override
        public FieldOptions alphanumeric(int size) {
            builder.pic(pendingFieldName, "X(" + size + ")");
            return this;
        }

        @Override
        public FieldOptions alphabetic(int size) {
            builder.pic(pendingFieldName, "A(" + size + ")");
            return this;
        }

        @Override
        public NumericFieldOptions decimal(int integerDigits, int decimalDigits) {
            builder.pic(pendingFieldName,
                "S9(" + integerDigits + ")V9(" + decimalDigits + ")");
            return this;
        }

        @Override
        public NumericFieldOptions integer(int digits) {
            builder.pic(pendingFieldName, "9(" + digits + ")");
            return this;
        }

        @Override
        public NumericFieldOptions signedInteger(int digits) {
            builder.pic(pendingFieldName, "S9(" + digits + ")");
            return this;
        }

        @Override
        public FieldOptions national(int size) {
            builder.pic(pendingFieldName, "N(" + size + ")");
            return this;
        }

        // ── NumericFieldOptions ─────────────────────────────────────

        @Override
        public FieldOptions packedDecimal() {
            builder.comp3();
            return this;
        }

        @Override
        public FieldOptions binary() {
            builder.comp();
            return this;
        }

        @Override
        public FieldOptions nativeBinary() {
            builder.comp5();
            return this;
        }

        @Override
        public NumericFieldOptions signed() {
            return this; // default is signed trailing
        }

        @Override
        public NumericFieldOptions signLeading() {
            builder.signLeading();
            return this;
        }

        @Override
        public NumericFieldOptions signTrailingSeparate() {
            builder.signTrailingSeparate();
            return this;
        }

        @Override
        public NumericFieldOptions signLeadingSeparate() {
            builder.signLeadingSeparate();
            return this;
        }

        // ── FieldOptions ────────────────────────────────────────────

        @Override
        public ConditionValueSelector when(String conditionName) {
            flushCondition(); // flush any previous condition
            this.currentConditionName = conditionName;
            this.currentConditionValues = new java.util.ArrayList<>();
            return this;
        }

        @Override
        public FieldOptions occurs(int times) {
            builder.occurs(times);
            return this;
        }

        @Override
        public FieldOptions occursDependingOn(int maxTimes, String countField) {
            builder.occursDependingOn(maxTimes, countField);
            return this;
        }

        @Override
        public FieldOptions initialValue(String value) {
            builder.value(value);
            return this;
        }

        // ── ConditionValueSelector ──────────────────────────────────

        @Override
        public ConditionChain is(String value) {
            currentConditionValues.add(value);
            return this;
        }

        @Override
        public FieldOptions through(String from, String to) {
            builder.value88Range(currentConditionName, from, to);
            return this;
        }

        // ── ConditionChain ──────────────────────────────────────────

        @Override
        public ConditionChain or(String value) {
            currentConditionValues.add(value);
            return this;
        }
    }
}
