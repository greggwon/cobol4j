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
package org.cobol4j.transpiler;

import java.util.List;

/**
 * Top-level AST: a parsed COBOL program.
 */
public record CobolProgram(
    String programId,
    List<DataEntry> dataEntries,
    List<Paragraph> paragraphs
) {
    /**
     * A parsed DATA DIVISION entry (01-level through 88-level).
     */
    public record DataEntry(
        int level,
        String name,
        String pic,          // null for group items
        String usage,        // null = DISPLAY
        String value,        // initial VALUE, null if none
        String redefines,    // null if not redefining
        int occurs,          // 0 = not an array
        String dependingOn,  // null if not OCCURS DEPENDING ON
        List<Condition88> conditions,
        String signClause    // null = default TRAILING, e.g. "LEADING", "TRAILING_SEPARATE", "LEADING_SEPARATE"
    ) {
        /** Backward-compatible constructor without signClause. */
        public DataEntry(int level, String name, String pic, String usage,
                         String value, String redefines, int occurs,
                         String dependingOn, List<Condition88> conditions) {
            this(level, name, pic, usage, value, redefines, occurs,
                 dependingOn, conditions, null);
        }
    }

    /**
     * A level-88 condition name entry.
     */
    public record Condition88(String name, List<String> values, String thruFrom, String thruTo) {}

    /**
     * A paragraph in the PROCEDURE DIVISION.
     */
    public record Paragraph(String name, List<Statement> statements) {}
}
