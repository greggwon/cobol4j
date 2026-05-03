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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects and reports all diagnostics generated during transpilation.
 * <p>
 * Every time the transpiler skips, approximates, or cannot fully translate
 * a COBOL construct, it reports a diagnostic here. Nothing is silently dropped.
 * <p>
 * Diagnostics are:
 * <ul>
 *   <li><b>ERROR</b> — translation failed, generated code will not work correctly</li>
 *   <li><b>WARNING</b> — translation was approximate or incomplete, review needed</li>
 *   <li><b>INFO</b> — informational (e.g., a construct was translated using a default assumption)</li>
 * </ul>
 * <p>
 * After transpilation, call {@link #diagnostics()} to retrieve all messages,
 * or check {@link #hasErrors()} / {@link #hasWarnings()} before using the output.
 * <pre>{@code
 * TranspileDiagnostics diag = new TranspileDiagnostics();
 * String java = Transpiler.transpile(cobolSource, diag);
 *
 * if (diag.hasErrors()) {
 *     System.err.println("Transpilation produced errors:");
 *     diag.errors().forEach(d -> System.err.println("  " + d));
 * }
 * if (diag.hasWarnings()) {
 *     System.err.println("Transpilation warnings:");
 *     diag.warnings().forEach(d -> System.err.println("  " + d));
 * }
 * }</pre>
 */
public final class TranspileDiagnostics {

    private static final Logger LOG = Logger.getLogger("org.cobol4j.transpiler");

    public enum Severity { INFO, WARNING, ERROR }

    public record Diagnostic(Severity severity, String phase, int line,
                              String cobolConstruct, String message) {
        @Override
        public String toString() {
            String loc = line > 0 ? " (line " + line + ")" : "";
            return severity + " [" + phase + "]" + loc + ": "
                + cobolConstruct + " — " + message;
        }
    }

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    // ── Reporting ───────────────────────────────────────────────────

    public void error(String phase, int line, String construct, String message) {
        Diagnostic d = new Diagnostic(Severity.ERROR, phase, line, construct, message);
        diagnostics.add(d);
        LOG.log(Level.SEVERE, d.toString());
    }

    public void warning(String phase, int line, String construct, String message) {
        Diagnostic d = new Diagnostic(Severity.WARNING, phase, line, construct, message);
        diagnostics.add(d);
        LOG.log(Level.WARNING, d.toString());
    }

    public void info(String phase, int line, String construct, String message) {
        Diagnostic d = new Diagnostic(Severity.INFO, phase, line, construct, message);
        diagnostics.add(d);
        LOG.log(Level.INFO, d.toString());
    }

    // ── Query ───────────────────────────────────────────────────────

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream()
            .filter(d -> d.severity == Severity.ERROR)
            .toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream()
            .filter(d -> d.severity == Severity.WARNING)
            .toList();
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity == Severity.ERROR);
    }

    public boolean hasWarnings() {
        return diagnostics.stream().anyMatch(d -> d.severity == Severity.WARNING);
    }

    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    public int count() {
        return diagnostics.size();
    }

    /** Print all diagnostics to stderr. */
    public void printAll() {
        diagnostics.forEach(d -> System.err.println(d));
    }

    /** Clear all diagnostics (e.g., between transpilation runs). */
    public void clear() {
        diagnostics.clear();
    }
}
