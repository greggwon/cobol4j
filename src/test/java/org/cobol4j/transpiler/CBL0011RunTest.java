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

import org.cobol4j.Record;
import org.cobol4j.Decimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end: transpile CBL0011, compile, create test data, run, validate report output.
 *
 * <p>CBL0011 is a financial reporting program from the IBM Open Mainframe Project
 * COBOL Programming Course. It reads account records (with COMP-3 packed decimal
 * fields), formats a report with headers, detail lines, and totals, and writes
 * it to a sequential output file.</p>
 *
 * <p>This test creates binary input data matching the FD record layout,
 * runs the transpiled program, and verifies the report output contains
 * correct account names, formatted dollar amounts, and accumulated totals.</p>
 */
class CBL0011RunTest {

    /** Test accounts: {acctNo, limit, balance, lastName, firstName, street, city, state, comments} */
    private static final String[][] ACCOUNTS = {
        {"10101010", "15000.00", "14500.00", "ROGERS",   "BUCK",    "123 MAIN ST",          "ANYTOWN",          "VIRGINIA",     "PREMIUM CUSTOMER"},
        {"10201020", "8000.00",  "6500.50",  "NELSON",   "HARRIET", "456 OAK AVE",          "SPRINGFIELD",      "ILLINOIS",     "STANDARD ACCOUNT"},
        {"10301030", "25000.00", "22000.75",  "FRANKLIN", "BENJAMIN","789 LIBERTY BLVD",     "PHILADELPHIA",     "PENNSYLVANIA", "HIGH VALUE"},
        {"10401040", "5000.00",  "4800.00",  "LINCOLN",  "ABRAHAM", "1600 PENNSYLVANIA AVE","WASHINGTON",       "DC",           "GOVERNMENT"},
        {"10501050", "12000.00", "11250.25", "JEFFERSON","THOMAS",  "MONTICELLO DR",        "CHARLOTTESVILLE",  "VIRGINIA",     "FOUNDING MEMBER"},
    };

    @Test
    void transpileCompileAndRunCBL0011(@TempDir Path tempDir) throws Exception {
        // ── Step 1: Transpile ──────────────────────────────────
        String cobol;
        try (InputStream is = getClass().getResourceAsStream("/CBL0011.cbl")) {
            assertNotNull(is, "CBL0011.cbl must be on classpath");
            cobol = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag, "CBL0011.cbl");
        assertFalse(diag.hasErrors(), "Transpile errors: " + diag.errors());
        assertNotNull(java);

        // ── Step 2: Compile ────────────────────────────────────
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return;

        Path buildDir = tempDir.resolve("build");
        Path pkgDir = buildDir.resolve("generated");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Cbl0011.java"), java);

        DiagnosticCollector<JavaFileObject> compileDiag = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(compileDiag, null, null)) {
            boolean ok = compiler.getTask(null, fm, compileDiag,
                List.of("-classpath", System.getProperty("java.class.path"),
                        "-d", buildDir.toString()),
                null, fm.getJavaFileObjects(pkgDir.resolve("Cbl0011.java").toFile())).call();
            if (!ok) {
                StringBuilder errors = new StringBuilder("Compile failed:\n");
                compileDiag.getDiagnostics().forEach(d -> errors.append("  ").append(d).append("\n"));
                fail(errors.toString());
            }
        }

        // ── Step 3: Create test data ───────────────────────────
        // Build a Record matching the FD layout and write binary records
        Record acctRec = Record.define("ACCT-FIELDS")
            .pic("ACCT-NO",       "X(8)")
            .pic("ACCT-LIMIT",    "S9(7)V99").comp3()
            .pic("ACCT-BALANCE",  "S9(7)V99").comp3()
            .pic("LAST-NAME",     "X(20)")
            .pic("FIRST-NAME",    "X(15)")
            .pic("STREET-ADDR",   "X(25)")
            .pic("CITY-COUNTY",   "X(20)")
            .pic("USA-STATE",     "X(15)")
            .pic("RESERVED",      "X(7)")
            .pic("COMMENTS",      "X(50)")
            .build();

        Path inputFile = tempDir.resolve("ACCTREC.dat");
        try (OutputStream out = Files.newOutputStream(inputFile)) {
            for (String[] acct : ACCOUNTS) {
                acctRec.move("ACCT-NO",      acct[0]);
                acctRec.move("ACCT-LIMIT",   Decimal.of(acct[1]));
                acctRec.move("ACCT-BALANCE", Decimal.of(acct[2]));
                acctRec.move("LAST-NAME",    acct[3]);
                acctRec.move("FIRST-NAME",   acct[4]);
                acctRec.move("STREET-ADDR",  acct[5]);
                acctRec.move("CITY-COUNTY",  acct[6]);
                acctRec.move("USA-STATE",    acct[7]);
                acctRec.move("COMMENTS",     acct[8]);
                out.write(acctRec.buffer());
            }
        }

        Path outputFile = tempDir.resolve("PRTLINE.dat");

        // ── Step 4: Run the generated program ──────────────────
        // Set system properties for file assignments (from ENVIRONMENT DIVISION)
        System.setProperty("cobol4j.file.ACCTREC", inputFile.toString());
        System.setProperty("cobol4j.file.PRTLINE", outputFile.toString());

        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{buildDir.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> clazz = cl.loadClass("generated.Cbl0011");
            clazz.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.clearProperty("cobol4j.file.ACCTREC");
            System.clearProperty("cobol4j.file.PRTLINE");
        }

        // ── Step 5: Validate the report ────────────────────────
        assertTrue(Files.exists(outputFile), "Report file must be created");
        byte[] reportBytes = Files.readAllBytes(outputFile);
        assertTrue(reportBytes.length > 0, "Report must not be empty");

        // Parse report lines (fixed 80-byte records... or whatever size PRINT-REC is)
        // The report is binary fixed-length — read as strings
        String report = new String(reportBytes, StandardCharsets.US_ASCII);

        // Header checks
        assertTrue(report.contains("Financial Report for"),
            "Report must contain header: " + report);

        // Account detail lines — names should be mixed case (first letter upper, rest lower)
        assertTrue(report.contains("Rogers"),
            "ROGERS should be formatted as Rogers: " + firstLines(report, 20));
        assertTrue(report.contains("Nelson"),
            "NELSON should be formatted as Nelson: " + firstLines(report, 20));
        assertTrue(report.contains("Franklin"),
            "FRANKLIN should be formatted as Franklin: " + firstLines(report, 20));

        // Dollar amounts — should contain formatted values
        assertTrue(report.contains("15,000.00") || report.contains("$15,000.00"),
            "ROGERS limit $15,000.00 should appear: " + firstLines(report, 20));
        assertTrue(report.contains("14,500.00") || report.contains("$14,500.00"),
            "ROGERS balance $14,500.00 should appear: " + firstLines(report, 20));

        // Totals line
        assertTrue(report.contains("Totals"),
            "Report must contain totals line: " + report);

        // Total limit = 15000 + 8000 + 25000 + 5000 + 12000 = 65000
        assertTrue(report.contains("65,000.00") || report.contains("$65,000.00"),
            "Total limit $65,000.00 should appear: " + lastLines(report, 10));

        // Total balance = 14500 + 6500.50 + 22000.75 + 4800 + 11250.25 = 59051.50
        assertTrue(report.contains("59,051.50") || report.contains("$59,051.50"),
            "Total balance $59,051.50 should appear: " + lastLines(report, 10));
    }

    private static String firstLines(String text, int n) {
        return text.lines().limit(n).reduce("", (a, b) -> a + "\n" + b);
    }

    private static String lastLines(String text, int n) {
        var lines = text.lines().toList();
        int start = Math.max(0, lines.size() - n);
        return String.join("\n", lines.subList(start, lines.size()));
    }
}
