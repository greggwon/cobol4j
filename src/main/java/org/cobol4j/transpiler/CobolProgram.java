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
    List<Paragraph> paragraphs,
    List<FileBinding> fileBindings,
    List<FileControl> fileControls,
    List<String> dataLevelSql       // EXEC SQL DECLARE CURSOR in WORKING-STORAGE
) {
    public CobolProgram(String programId, List<DataEntry> dataEntries,
                        List<Paragraph> paragraphs, List<FileBinding> fileBindings,
                        List<FileControl> fileControls) {
        this(programId, dataEntries, paragraphs, fileBindings, fileControls, List.of());
    }
    public CobolProgram(String programId, List<DataEntry> dataEntries,
                        List<Paragraph> paragraphs, List<FileBinding> fileBindings) {
        this(programId, dataEntries, paragraphs, fileBindings, List.of(), List.of());
    }
    public CobolProgram(String programId, List<DataEntry> dataEntries, List<Paragraph> paragraphs) {
        this(programId, dataEntries, paragraphs, List.of(), List.of(), List.of());
    }

    /**
     * A binding between an FD file name and its associated record.
     * Represents the FILE SECTION's association of a file descriptor
     * with a 01-level record layout.
     */
    public record FileBinding(String fileName, String recordName, int recordSize) {}

    /**
     * A SELECT entry from the ENVIRONMENT DIVISION / FILE-CONTROL.
     * Maps a logical file name to its external assignment and configuration.
     *
     * <p>In mainframe COBOL, the ASSIGN TO value is a DD name resolved by JCL.
     * In cobol4j, it maps to a system property or file path:</p>
     * <pre>
     * System.getProperty("cobol4j.file.PRTLINE", "PRTLINE.dat")
     * </pre>
     */
    public record FileControl(
        String fileName,        // logical name (e.g., "PRINT-LINE")
        String assignTo,        // external name (e.g., "PRTLINE")
        String organization,    // SEQUENTIAL, INDEXED, RELATIVE, or null
        String accessMode,      // SEQUENTIAL, RANDOM, DYNAMIC, or null
        String fileStatus,      // status variable name, or null
        String recordKey        // primary key field for INDEXED, or null
    ) {}
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
        String signClause,   // null = default TRAILING
        boolean isGlobal,
        boolean isExternal,
        List<String> indexedBy  // INDEXED BY names (owned by this OCCURS entry)
    ) {
        public DataEntry(int level, String name, String pic, String usage,
                         String value, String redefines, int occurs,
                         String dependingOn, List<Condition88> conditions) {
            this(level, name, pic, usage, value, redefines, occurs,
                 dependingOn, conditions, null, false, false, List.of());
        }
        public DataEntry(int level, String name, String pic, String usage,
                         String value, String redefines, int occurs,
                         String dependingOn, List<Condition88> conditions,
                         String signClause) {
            this(level, name, pic, usage, value, redefines, occurs,
                 dependingOn, conditions, signClause, false, false, List.of());
        }
        public DataEntry(int level, String name, String pic, String usage,
                         String value, String redefines, int occurs,
                         String dependingOn, List<Condition88> conditions,
                         String signClause, boolean isGlobal, boolean isExternal) {
            this(level, name, pic, usage, value, redefines, occurs,
                 dependingOn, conditions, signClause, isGlobal, isExternal, List.of());
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
