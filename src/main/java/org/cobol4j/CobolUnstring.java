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
 * Fluent API for COBOL's UNSTRING verb.
 * <p>
 * UNSTRING splits a source field into multiple receiving fields based on delimiters.
 * <pre>{@code
 * // UNSTRING INPUT-LINE DELIMITED BY "," OR SPACES
 * //   INTO FIELD-1 FIELD-2 FIELD-3
 * //   WITH POINTER PTR
 * //   TALLYING IN FIELD-COUNT
 * //   ON OVERFLOW PERFORM OVERFLOW-ROUTINE.
 *
 * int count = CobolUnstring.from(record, "INPUT-LINE")
 *     .delimitedBy(",")
 *     .orDelimitedBy(" ")
 *     .into(record, "FIELD-1")
 *     .into(record, "FIELD-2")
 *     .into(record, "FIELD-3")
 *     .onOverflow(() -> System.out.println("overflow"))
 *     .execute();
 * }</pre>
 */
public final class CobolUnstring {

    private final Record sourceRecord;
    private final String sourceField;
    private final List<String> delimiters = new ArrayList<>();
    private final List<Target> targets = new ArrayList<>();
    private int pointer = 1; // 1-based
    private Runnable overflowHandler;
    private Runnable notOverflowHandler;

    private CobolUnstring(Record sourceRecord, String sourceField) {
        this.sourceRecord = sourceRecord;
        this.sourceField = sourceField;
    }

    /** Begin an UNSTRING operation from a source field. */
    public static CobolUnstring from(Record record, String fieldName) {
        return new CobolUnstring(record, fieldName);
    }

    /** DELIMITED BY a literal. */
    public CobolUnstring delimitedBy(String delimiter) {
        delimiters.add(delimiter);
        return this;
    }

    /** OR DELIMITED BY another literal. */
    public CobolUnstring orDelimitedBy(String delimiter) {
        delimiters.add(delimiter);
        return this;
    }

    /** DELIMITED BY ALL SPACES. */
    public CobolUnstring delimitedBySpaces() {
        delimiters.add(" ");
        return this;
    }

    /** Add a receiving field. */
    public CobolUnstring into(Record record, String fieldName) {
        targets.add(new Target(record, fieldName, null, null));
        return this;
    }

    /** Add a receiving field with a DELIMITER IN field. */
    public CobolUnstring into(Record record, String fieldName,
                               Record delimRecord, String delimField) {
        targets.add(new Target(record, fieldName, delimRecord, delimField));
        return this;
    }

    /** WITH POINTER (1-based). */
    public CobolUnstring withPointer(int pointer) {
        this.pointer = pointer;
        return this;
    }

    public CobolUnstring onOverflow(Runnable handler) {
        this.overflowHandler = handler;
        return this;
    }

    public CobolUnstring notOnOverflow(Runnable handler) {
        this.notOverflowHandler = handler;
        return this;
    }

    /**
     * Execute the UNSTRING. Returns the number of fields populated (tally count).
     */
    public int execute() {
        String source = sourceRecord.getString(sourceField);
        int pos = pointer - 1; // 0-based
        int fieldCount = 0;
        boolean overflow = false;

        for (Target target : targets) {
            if (pos >= source.length()) {
                overflow = true;
                break;
            }

            // Find the next delimiter
            int delimIdx = -1;
            String matchedDelim = null;
            for (String delim : delimiters) {
                int idx = source.indexOf(delim, pos);
                if (idx >= 0 && (delimIdx < 0 || idx < delimIdx)) {
                    delimIdx = idx;
                    matchedDelim = delim;
                }
            }

            String extracted;
            if (delimIdx >= 0) {
                extracted = source.substring(pos, delimIdx);
                pos = delimIdx + matchedDelim.length();
            } else {
                extracted = source.substring(pos);
                pos = source.length();
            }

            target.record.move(target.fieldName, extracted);
            if (target.delimRecord != null && matchedDelim != null) {
                target.delimRecord.move(target.delimField, matchedDelim);
            }
            fieldCount++;
        }

        if (overflow && overflowHandler != null) {
            overflowHandler.run();
        } else if (!overflow && notOverflowHandler != null) {
            notOverflowHandler.run();
        }

        return fieldCount;
    }

    private record Target(Record record, String fieldName,
                           Record delimRecord, String delimField) {}
}
