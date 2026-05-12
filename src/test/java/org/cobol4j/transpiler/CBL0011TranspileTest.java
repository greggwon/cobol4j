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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.tools.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transpile CBL0011 from the IBM Open Mainframe Project COBOL Programming Course.
 *
 * <p>This is an external, real-world COBOL program — not written for cobol4j.
 * It exercises file I/O with FD records, COMP-3 packed decimals, numeric edited
 * output ($$,$$$,$$9.99), FUNCTION CURRENT-DATE, FUNCTION LOWER-CASE,
 * reference modification, PERFORM UNTIL, COMPUTE, WRITE FROM,
 * WRITE AFTER ADVANCING, FILLER fields, and report formatting.</p>
 *
 * <p>Source: <a href="https://github.com/openmainframeproject/cobol-programming-course">
 * openmainframeproject/cobol-programming-course</a> (CC-BY-4.0)</p>
 */
class CBL0011TranspileTest {

    private static String javaSource;
    private static TranspileDiagnostics diag;

    @BeforeAll
    static void transpile() throws Exception {
        try (InputStream is = CBL0011TranspileTest.class.getResourceAsStream("/CBL0011.cbl")) {
            assertNotNull(is, "CBL0011.cbl must be on the test classpath");
            String cobol = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            diag = new TranspileDiagnostics();
            javaSource = Transpiler.transpile(cobol, diag);
        }
    }

    @Test
    void transpilesWithoutErrors() {
        assertFalse(diag.hasErrors(), "Should transpile without errors: " + diag.errors());
        assertNotNull(javaSource, "Generated source must not be null");
    }

    @Test
    void compilesCleanly() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return; // JRE without compiler

        Path tmpDir = Files.createTempDirectory("cbl0011-test");
        try {
            Path pkgDir = tmpDir.resolve("generated");
            Files.createDirectories(pkgDir);
            Files.writeString(pkgDir.resolve("Cbl0011.java"), javaSource);

            DiagnosticCollector<JavaFileObject> compileDiag = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(compileDiag, null, null)) {
                JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, compileDiag,
                    List.of("-classpath", System.getProperty("java.class.path"),
                            "-d", tmpDir.toString()),
                    null, fm.getJavaFileObjects(pkgDir.resolve("Cbl0011.java").toFile()));

                assertTrue(task.call(), "Generated Java must compile: "
                    + compileDiag.getDiagnostics());
            }
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) {} });
        }
    }

    // ── Verify key COBOL features are correctly transpiled ─────

    @Test
    void numericEditedPicPreserved() {
        // PIC $$,$$$,$$9.99 must be captured intact
        assertTrue(javaSource.contains("$$,$$$,$$9.99"),
            "Numeric edited PIC must be preserved: " + excerptAround("ACCT-LIMIT-O"));
    }

    @Test
    void fillerFieldsUniquelyNamed() {
        assertTrue(javaSource.contains("FILLER-1"),
            "First FILLER must be uniquely named: " + excerptAround("FILLER-1"));
        assertTrue(javaSource.contains("FILLER-2"),
            "Second FILLER must be uniquely named: " + excerptAround("FILLER-2"));
        // Should NOT have raw "FILLER" collisions
        assertFalse(javaSource.contains(".pic(\"FILLER\","),
            "Should not have bare FILLER names: " + javaSource.substring(0, 500));
    }

    @Test
    void comp3Emitted() {
        assertTrue(javaSource.contains(".comp3()"),
            "COMP-3 fields must emit .comp3(): " + excerptAround("comp3"));
    }

    @Test
    void writeFromLoadsSourceRecord() {
        // WRITE PRINT-REC FROM HEADER-1 → printRec.loadFrom(header1.buffer())
        assertTrue(javaSource.contains(".loadFrom("),
            "WRITE FROM must load source into record: " + excerptAround("loadFrom"));
    }

    @Test
    void performUntilUsesStringEquals() {
        // PERFORM UNTIL LASTREC = 'Y' — alphanumeric comparison
        assertTrue(javaSource.contains(".equals(\"Y\")"),
            "Alphanumeric comparison must use .equals(): " + excerptAround("LASTREC"));
        assertFalse(javaSource.contains(".equalTo(\"Y\")"),
            "Should NOT use .equalTo() for String comparison");
    }

    @Test
    void functionCurrentDate() {
        assertTrue(javaSource.contains("Intrinsic.currentDate()"),
            "FUNCTION CURRENT-DATE must emit: " + excerptAround("currentDate"));
    }

    @Test
    void functionLowerCase() {
        assertTrue(javaSource.contains("Intrinsic.lowerCase("),
            "FUNCTION LOWER-CASE must emit: " + excerptAround("lowerCase"));
    }

    @Test
    void referenceModification() {
        // MOVE LAST-NAME(1:1) TO LAST-NAME-O(1:1)
        assertTrue(javaSource.contains("moveSubstring("),
            "Reference modification must emit moveSubstring: " + excerptAround("moveSubstring"));
    }

    @Test
    void groupItemEmitted() {
        assertTrue(javaSource.contains(".group(\"CLIENT-ADDR\""),
            "CLIENT-ADDR group must emit: " + excerptAround("CLIENT-ADDR"));
    }

    @Test
    void gobackEmitsStopRun() {
        assertTrue(javaSource.contains("ctx.stopRun()"),
            "GOBACK must emit ctx.stopRun(): " + excerptAround("stopRun"));
    }

    @Test
    void fileBindingsRegistered() {
        assertTrue(javaSource.contains(".file(printLine, printRec)"),
            "PRINT-LINE file binding must be registered: " + excerptAround(".file("));
        assertTrue(javaSource.contains(".file(acctRec, acctFields)"),
            "ACCT-REC file binding must be registered: " + excerptAround(".file("));
    }

    // ── Helper ─────────────────────────────────────────────────

    private String excerptAround(String keyword) {
        int idx = javaSource.indexOf(keyword);
        if (idx < 0) return "['" + keyword + "' not found]";
        int start = Math.max(0, idx - 60);
        int end = Math.min(javaSource.length(), idx + keyword.length() + 100);
        return "..." + javaSource.substring(start, end).replace("\n", "\\n") + "...";
    }
}
