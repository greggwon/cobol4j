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
package org.cobol4j.interop;

import org.cobol4j.Record;
import org.cobol4j.transpiler.CobolProgram;
import org.cobol4j.transpiler.Lexer;
import org.cobol4j.transpiler.Parser;
import org.cobol4j.transpiler.Token;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports COBOL copybooks (.cpy files) and produces cobol4j Record definitions.
 * <p>
 * Copybooks are shared record layouts used across COBOL programs. Importing them
 * lets Java programs read and write data in the exact same format as COBOL programs.
 * <p>
 * Two output modes:
 * <ul>
 *   <li><b>Java source</b> — generates {@code Record.define(...).build()} code</li>
 *   <li><b>Direct Record</b> — creates a live Record object at runtime</li>
 * </ul>
 * <pre>{@code
 * // Generate Java source from a copybook
 * String javaCode = CopybookImporter.toJavaSource("CUSTCPY.cpy");
 *
 * // Create a Record directly from a copybook
 * Record custRec = CopybookImporter.toRecord("CUSTCPY.cpy");
 *
 * // From a string (e.g., embedded in a resource)
 * Record rec = CopybookImporter.fromSource(copybookText);
 * }</pre>
 */
public final class CopybookImporter {

    private CopybookImporter() {}

    // ═══════════════════════════════════════════════════════════════
    //  DIRECT RECORD CREATION — runtime import
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a copybook file and create a Record directly.
     */
    public static Record toRecord(String filePath) throws IOException {
        String source = Files.readString(Path.of(filePath));
        return fromSource(source);
    }

    /**
     * Parse copybook source text and create a Record directly.
     */
    public static Record fromSource(String copybookSource) {
        List<CobolProgram.DataEntry> entries = parseCopybook(copybookSource);
        return buildRecord(entries);
    }

    // ═══════════════════════════════════════════════════════════════
    //  JAVA SOURCE GENERATION — build-time import
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a copybook file and generate Java source code that creates a Record.
     */
    public static String toJavaSource(String filePath) throws IOException {
        String source = Files.readString(Path.of(filePath));
        return toJavaSourceFromText(source);
    }

    /**
     * Parse copybook source text and generate Java source code.
     */
    public static String toJavaSourceFromText(String copybookSource) {
        List<CobolProgram.DataEntry> entries = parseCopybook(copybookSource);
        return generateJavaSource(entries);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PARSING — reuses the transpiler's DATA DIVISION parser
    // ═══════════════════════════════════════════════════════════════

    private static List<CobolProgram.DataEntry> parseCopybook(String source) {
        // Copybooks are DATA DIVISION fragments — they don't have
        // IDENTIFICATION or PROCEDURE divisions. Wrap them so the parser
        // can handle them.
        String wrapped = "IDENTIFICATION DIVISION.\n"
            + "PROGRAM-ID. COPYBOOK.\n"
            + "DATA DIVISION.\n"
            + "WORKING-STORAGE SECTION.\n"
            + source + "\n"
            + "PROCEDURE DIVISION.\n"
            + "DUMMY-PARA.\n"
            + "    STOP RUN.\n";

        CobolProgram program = Parser.parse(wrapped);
        return program.dataEntries();
    }

    // ═══════════════════════════════════════════════════════════════
    //  RECORD BUILDING — from parsed entries
    // ═══════════════════════════════════════════════════════════════

    private static Record buildRecord(List<CobolProgram.DataEntry> entries) {
        // Find the 01-level name
        String recordName = "COPYBOOK";
        for (CobolProgram.DataEntry e : entries) {
            if (e.level() == 1) {
                recordName = e.name();
                break;
            }
        }

        Record.Builder builder = Record.define(recordName);

        for (CobolProgram.DataEntry entry : entries) {
            if (entry.level() == 1) continue; // skip the 01 level (it's the record name)
            if (entry.level() == 88) continue; // handled inline

            if (entry.pic() != null) {
                builder.pic(entry.name(), entry.pic());

                // Apply USAGE
                if (entry.usage() != null) {
                    String u = entry.usage().toUpperCase().replace("-", "");
                    switch (u) {
                        case "COMP3", "COMPUTATIONAL3", "PACKEDDECIMAL" -> builder.comp3();
                        case "COMP", "COMP4", "COMPUTATIONAL", "BINARY" -> builder.comp();
                        case "COMP5", "COMPUTATIONAL5" -> builder.comp5();
                    }
                }

                // Apply OCCURS
                if (entry.occurs() > 0) {
                    builder.occurs(entry.occurs());
                }

                // Apply VALUE
                if (entry.value() != null) {
                    String val = entry.value();
                    if (val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }
                    builder.value(val);
                }

                // Apply 88-level conditions
                for (CobolProgram.Condition88 cond : entry.conditions()) {
                    if (cond.thruTo() != null) {
                        builder.value88Range(cond.name(),
                            stripQuotes(cond.thruFrom()),
                            stripQuotes(cond.thruTo()));
                    } else {
                        String[] vals = cond.values().stream()
                            .map(CopybookImporter::stripQuotes)
                            .toArray(String[]::new);
                        builder.value88(cond.name(), vals);
                    }
                }
            }
            // Group items (no PIC) are currently flattened — the parser
            // handles the hierarchy through level numbers
        }

        return builder.build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  JAVA SOURCE GENERATION
    // ═══════════════════════════════════════════════════════════════

    private static String generateJavaSource(List<CobolProgram.DataEntry> entries) {
        StringBuilder sb = new StringBuilder();

        String recordName = "COPYBOOK";
        for (CobolProgram.DataEntry e : entries) {
            if (e.level() == 1) { recordName = e.name(); break; }
        }

        String varName = toCamelCase(recordName);
        sb.append("Record ").append(varName).append(" = Record.define(\"")
          .append(recordName).append("\")\n");

        for (CobolProgram.DataEntry entry : entries) {
            if (entry.level() == 1 || entry.level() == 88) continue;

            if (entry.pic() != null) {
                sb.append("    .pic(\"").append(entry.name())
                  .append("\", \"").append(entry.pic()).append("\")");

                if (entry.usage() != null) {
                    String u = entry.usage().toUpperCase().replace("-", "");
                    switch (u) {
                        case "COMP3", "COMPUTATIONAL3", "PACKEDDECIMAL" -> sb.append(".comp3()");
                        case "COMP", "COMP4", "COMPUTATIONAL", "BINARY" -> sb.append(".comp()");
                        case "COMP5", "COMPUTATIONAL5" -> sb.append(".comp5()");
                    }
                }

                if (entry.occurs() > 0) {
                    sb.append(".occurs(").append(entry.occurs()).append(")");
                }

                if (entry.value() != null) {
                    sb.append(".value(").append(entry.value()).append(")");
                }

                sb.append("\n");

                for (CobolProgram.Condition88 cond : entry.conditions()) {
                    if (cond.thruTo() != null) {
                        sb.append("        .value88Range(\"").append(cond.name())
                          .append("\", ").append(cond.thruFrom())
                          .append(", ").append(cond.thruTo()).append(")\n");
                    } else {
                        sb.append("        .value88(\"").append(cond.name()).append("\"");
                        for (String v : cond.values()) {
                            sb.append(", ").append(v);
                        }
                        sb.append(")\n");
                    }
                }
            } else {
                // Group item
                sb.append("    // GROUP: ").append(entry.name())
                  .append(" (level ").append(entry.level()).append(")\n");
            }
        }

        sb.append("    .build();\n");
        return sb.toString();
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    private static String toCamelCase(String cobolName) {
        StringBuilder sb = new StringBuilder();
        boolean lower = true;
        for (char c : cobolName.toCharArray()) {
            if (c == '-' || c == '_') {
                lower = false;
            } else {
                sb.append(lower ? Character.toLowerCase(c) : Character.toUpperCase(c));
                lower = true;
            }
        }
        return sb.toString();
    }
}
