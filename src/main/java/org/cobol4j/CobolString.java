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

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent API for COBOL's STRING verb.
 * <p>
 * STRING concatenates portions of one or more fields into a receiving field,
 * with delimiters controlling how much of each source is used.
 * <pre>{@code
 * // STRING FIRST-NAME DELIMITED BY SPACES
 * //        " " DELIMITED BY SIZE
 * //        LAST-NAME DELIMITED BY SPACES
 * //   INTO FULL-NAME
 * //   WITH POINTER PTR
 * //   ON OVERFLOW PERFORM ERROR-ROUTINE.
 *
 * CobolString.into(record, "FULL-NAME")
 *     .from(record, "FIRST-NAME").delimitedBySpaces()
 *     .literal(" ")
 *     .from(record, "LAST-NAME").delimitedBySpaces()
 *     .onOverflow(() -> record.move("ERROR-FLAG", "Y"))
 *     .execute();
 * }</pre>
 */
public final class CobolString {

    private final Record targetRecord;
    private final String targetField;
    private final List<Source> sources = new ArrayList<>();
    private int pointer = 1; // 1-based, like COBOL
    private Runnable overflowHandler;
    private Runnable notOverflowHandler;

    private CobolString(Record targetRecord, String targetField) {
        this.targetRecord = targetRecord;
        this.targetField = targetField;
    }

    /** Begin a STRING operation targeting a field. */
    public static CobolString into(Record record, String fieldName) {
        return new CobolString(record, fieldName);
    }

    /** Add a field source, returning a delimiter selector. */
    public DelimiterSelector from(Record record, String fieldName) {
        return new DelimiterSelector(this, record.getString(fieldName));
    }

    /** Add a literal string source (DELIMITED BY SIZE, implicitly). */
    public CobolString literal(String value) {
        sources.add(new Source(value, null));
        return this;
    }

    /** Set the WITH POINTER value (1-based starting position). */
    public CobolString withPointer(int pointer) {
        this.pointer = pointer;
        return this;
    }

    /** ON OVERFLOW handler. */
    public CobolString onOverflow(Runnable handler) {
        this.overflowHandler = handler;
        return this;
    }

    /** NOT ON OVERFLOW handler. */
    public CobolString notOnOverflow(Runnable handler) {
        this.notOverflowHandler = handler;
        return this;
    }

    /** Execute the STRING operation. Returns the final pointer position. */
    public int execute() {
        FieldDef targetDef = targetRecord.fieldDef(targetField);
        int targetSize = targetDef.size();
        String currentValue = targetRecord.getString(targetField);
        char[] result = currentValue.toCharArray();

        int pos = pointer - 1; // convert to 0-based
        boolean overflow = false;

        for (Source src : sources) {
            String data = src.effectiveValue();
            for (int i = 0; i < data.length(); i++) {
                if (pos >= targetSize) {
                    overflow = true;
                    break;
                }
                result[pos++] = data.charAt(i);
            }
            if (overflow) break;
        }

        targetRecord.move(targetField, new String(result));

        if (overflow && overflowHandler != null) {
            overflowHandler.run();
        } else if (!overflow && notOverflowHandler != null) {
            notOverflowHandler.run();
        }

        return pos + 1; // return 1-based pointer
    }

    // ── Source + DelimiterSelector ───────────────────────────────────

    private record Source(String value, String delimiter) {
        String effectiveValue() {
            if (delimiter == null) return value;
            int idx = value.indexOf(delimiter);
            return idx >= 0 ? value.substring(0, idx) : value;
        }
    }

    /** Fluent delimiter selector — returned by {@link #from}. */
    public static final class DelimiterSelector {
        private final CobolString parent;
        private final String value;

        DelimiterSelector(CobolString parent, String value) {
            this.parent = parent;
            this.value = value;
        }

        /** DELIMITED BY SIZE — use the entire source value. */
        public CobolString delimitedBySize() {
            parent.sources.add(new Source(value, null));
            return parent;
        }

        /** DELIMITED BY SPACES — stop at the first space. */
        public CobolString delimitedBySpaces() {
            parent.sources.add(new Source(value, " "));
            return parent;
        }

        /** DELIMITED BY a specific literal. */
        public CobolString delimitedBy(String delimiter) {
            parent.sources.add(new Source(value, delimiter));
            return parent;
        }
    }
}
