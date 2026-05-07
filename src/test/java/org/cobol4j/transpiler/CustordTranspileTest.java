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
import org.cobol4j.Program;
import org.cobol4j.Decimal;
import org.cobol4j.Field;
import org.cobol4j.Arithmetic;
import org.cobol4j.SizeErrorHandler;
import org.cobol4j.CobolString;
import org.cobol4j.CobolUnstring;
import org.cobol4j.Inspect;
import org.cobol4j.Search;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.tools.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CUSTORD end-to-end integration test demonstrating the full cobol4j pipeline
 * and inter-program record exchange.
 *
 * <h2>What this test demonstrates</h2>
 *
 * <p><b>Phase 1 — Transpile, Compile, Run:</b>
 * CUSTORD.cbl is transpiled to Java source, compiled at runtime, and executed.
 * DISPLAY output is captured and every computed value is verified.
 * This proves the full COBOL→Java pipeline works end-to-end.</p>
 *
 * <p><b>Phase 2 — Record Exchange (CALL USING / LINKAGE):</b>
 * The same CUSTORD logic is built using shared Records. After CUSTORD runs,
 * it CALLs a VALIDATOR program, passing its working storage fields via
 * CALL ... USING. The VALIDATOR receives the data through its LINKAGE SECTION
 * and checks every value — this is the COBOL inter-program communication
 * pattern, demonstrating how transpiled programs can interoperate with
 * hand-written Java programs through the cobol4j API.</p>
 *
 * <p>Together, these two phases show:</p>
 * <ul>
 *   <li>The transpiler generates correct, compilable Java</li>
 *   <li>The runtime produces correct COBOL semantics</li>
 *   <li>Programs can exchange data through Records (CALL USING / LINKAGE)</li>
 *   <li>Java programs can validate COBOL program output at the field level</li>
 * </ul>
 */
class CustordTranspileTest {

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1 — Transpile → Compile → Run → Validate output
    // ═══════════════════════════════════════════════════════════════

    private static String javaSource;
    private static List<String> displayOutput;
    private static boolean ranSuccessfully;

    @BeforeAll
    static void transpileCompileAndRun() throws Exception {
        // ── Step 1: Read COBOL source ──────────────────────────
        String cobol;
        try (InputStream is = CustordTranspileTest.class.getResourceAsStream("/CUSTORD.cbl")) {
            assertNotNull(is, "CUSTORD.cbl must be on the test classpath");
            cobol = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // ── Step 2: Transpile to Java ──────────────────────────
        TranspileDiagnostics diag = new TranspileDiagnostics();
        javaSource = Transpiler.transpile(cobol, diag);
        assertFalse(diag.hasErrors(), "Transpilation failed: " + diag.errors());
        assertNotNull(javaSource, "Generated source must not be null");

        // ── Step 3: Compile the generated Java ─────────────────
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            ranSuccessfully = false;
            return; // JRE without compiler — Phase 1 runtime tests will be skipped
        }

        Path tmpDir = Files.createTempDirectory("custord-test");
        Path pkgDir = tmpDir.resolve("generated");
        Files.createDirectories(pkgDir);
        Files.writeString(pkgDir.resolve("Custord.java"), javaSource);

        DiagnosticCollector<JavaFileObject> compileDiag = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(compileDiag, null, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, compileDiag,
                List.of("-classpath", System.getProperty("java.class.path"),
                        "-d", tmpDir.toString()),
                null, fm.getJavaFileObjects(pkgDir.resolve("Custord.java").toFile()));

            if (!task.call()) {
                StringBuilder errors = new StringBuilder("Compilation failed:\n");
                compileDiag.getDiagnostics().forEach(d -> errors.append("  ").append(d).append("\n"));
                fail(errors.toString());
            }
        }

        // ── Step 4: Run the generated program ──────────────────
        displayOutput = new ArrayList<>();
        Logger logger = Logger.getLogger("cobol4j");
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) { displayOutput.add(r.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(capture);

        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{tmpDir.toUri().toURL()},
                CustordTranspileTest.class.getClassLoader())) {
            Class<?> clazz = cl.loadClass("generated.Custord");
            clazz.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            ranSuccessfully = true;
        } finally {
            logger.removeHandler(capture);
            try { // cleanup
                Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) {} });
            } catch (Exception e) { /* best effort */ }
        }
    }

    // ── Phase 1 assertions: transpiled output values ───────────

    @Test void transpileProducesValidJava() {
        assertNotNull(javaSource);
        assertTrue(javaSource.contains("public class Custord"));
        assertTrue(javaSource.contains("public static void main("));
    }

    @Test void programRuns()        { assertTrue(ranSuccessfully); }
    @Test void reportHeader()       { assertOutput("=== CUSTOMER ORDER REPORT ==="); }
    @Test void reportFooter()       { assertOutput("=== END REPORT ==="); }
    @Test void customerName()       { assertOutput("Customer: ALICE JOHNSON"); }
    @Test void customerStatus()     { assertOutput("Status: A"); }
    @Test void customerBalance()    { assertOutput("Balance: 50000.00"); }
    @Test void totalSales()         { assertOutput("Total Sales: 5000.00"); }
    @Test void taxAmount()          { assertOutput("Tax: 400.00"); }
    @Test void grandTotal()         { assertOutput("Grand Total: 5400.00"); }
    @Test void averageSale()        { assertOutput("Average Sale: 5000.00"); }
    @Test void totalItems()         { assertOutput("Items: 925"); }
    @Test void dateFromRedefines()  { assertOutput("Date: 2026/05/01"); }
    @Test void searchFoundItem()    { assertOutput("Item Found: Y"); }

    // ── Phase 1 assertions: generated source structure ─────────

    @Test void redefinesNotGroup() {
        assertTrue(javaSource.contains(".redefines(\"WS-DATE-FULL\""));
        assertFalse(javaSource.contains(".group(\"WS-DATE-PARTS\""));
    }

    @Test void sizeErrorNotStub() {
        assertTrue(javaSource.contains("SizeErrorHandler.of("));
        assertFalse(javaSource.contains("/* size error */"));
    }

    @Test void typeAwareAccess() {
        assertTrue(javaSource.contains("getString(\"WS-FULL-NAME\").trim()"));
        assertTrue(javaSource.contains("getString(\"WS-DATE-YEAR\").trim()"));
        assertTrue(javaSource.contains("getDecimal(\"WS-GRAND-TOTAL\")"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 2 — Record Exchange: CUSTORD → VALIDATOR via CALL USING
    //
    //  This demonstrates how a transpiled COBOL program's results
    //  can be consumed by another program through shared Records.
    //  The VALIDATOR receives CUSTORD's working storage fields via
    //  LINKAGE SECTION — the same mechanism COBOL uses for
    //  inter-program communication.
    // ═══════════════════════════════════════════════════════════════

    @Test
    void recordExchangeWithValidator() {

        // ── Shared Records ─────────────────────────────────────
        // These are the working storage areas that CUSTORD populates
        // and the VALIDATOR reads — same bytes, shared by reference.

        Record wsCustomer = Record.define("WS-CUSTOMER")
            .pic("WS-CUST-ID", "X(10)")
            .group("WS-CUST-NAME", g -> g
                .pic("WS-FIRST-NAME", "X(15)")
                .pic("WS-LAST-NAME", "X(20)"))
            .pic("WS-CUST-BALANCE", "S9(7)V99").comp3()
            .pic("WS-CUST-STATUS", "X")
                .value88("ACTIVE", "A")
                .value88("INACTIVE", "I")
                .value88("SUSPENDED", "S")
                .value88("VALID-STATUS", "A", "I", "S")
            .pic("WS-CUST-TYPE", "X")
                .value88("RETAIL", "R")
                .value88("WHOLESALE", "W")
                .value88Range("PREMIUM", "P", "Z")
            .pic("WS-CREDIT-LIMIT", "S9(7)V99").comp()
            .build();

        Record wsDateArea = Record.define("WS-DATE-AREA")
            .pic("WS-DATE-FULL", "X(8)")
            .redefines("WS-DATE-FULL", "WS-DATE-PARTS", g -> g
                .pic("WS-DATE-YEAR", "X(4)")
                .pic("WS-DATE-MONTH", "X(2)")
                .pic("WS-DATE-DAY", "X(2)"))
            .build();

        Record wsPriceTable = Record.define("WS-PRICE-TABLE")
            .pic("WS-TABLE-SIZE", "9(3)").value("5")
            .group("WS-PRICE-ENTRY", g -> g
                .pic("WS-ITEM-CODE", "X(10)")
                .pic("WS-ITEM-PRICE", "S9(5)V99")
                .pic("WS-ITEM-QTY", "9(5)"))
            .occurs(20)
            .build();

        Record wsAccumulators = Record.define("WS-ACCUMULATORS")
            .pic("WS-TOTAL-SALES", "S9(9)V99").value("0")
            .pic("WS-TOTAL-ITEMS", "9(7)").value("0")
            .pic("WS-CUST-COUNT", "9(5)").value("0")
            .pic("WS-AVG-SALE", "S9(7)V99")
            .pic("WS-TAX-AMOUNT", "S9(7)V99")
            .pic("WS-GRAND-TOTAL", "S9(9)V99")
            .pic("WS-QUOTIENT", "S9(7)")
            .pic("WS-REMAINDER", "S9(3)")
            .build();

        Record wsControl = Record.define("WS-CONTROL")
            .pic("WS-EOF-FLAG", "X").value("N")
                .value88("END-OF-FILE", "Y")
            .pic("WS-ERROR-FLAG", "X").value("N")
                .value88("HAS-ERROR", "Y")
            .pic("WS-IDX", "9(3)")
            .pic("WS-INNER-IDX", "9(3)")
            .pic("WS-SEARCH-CODE", "X(10)")
            .pic("WS-FOUND-FLAG", "X").value("N")
                .value88("ITEM-FOUND", "Y")
            .build();

        Record wsStringWork = Record.define("WS-STRING-WORK")
            .pic("WS-FULL-NAME", "X(40)")
            .pic("WS-MESSAGE", "X(80)")
            .pic("WS-DISPLAY-AMT", "Z(7)9.99")
            .group("WS-PARTS", g -> g
                .pic("WS-PART-1", "X(20)")
                .pic("WS-PART-2", "X(20)")
                .pic("WS-PART-3", "X(20)"))
            .pic("WS-SPACE-COUNT", "9(3)")
            .build();

        // ── Validation results — the VALIDATOR writes here ─────
        // This record tracks pass/fail for each check.
        Record valResults = Record.define("VAL-RESULTS")
            .pic("VAL-PASS-COUNT", "9(3)").value("0")
            .pic("VAL-FAIL-COUNT", "9(3)").value("0")
            .pic("VAL-LAST-FAIL", "X(80)")
            .build();

        // ── The VALIDATOR program ──────────────────────────────
        // Receives CUSTORD's fields via LINKAGE SECTION and checks
        // every computed value. This is a Java program consuming
        // COBOL program output through record exchange.

        Program validator = Program.define("VALIDATOR")
            .linkage("L-GRAND-TOTAL", "S9(9)V99")
            .linkage("L-TAX-AMOUNT", "S9(7)V99")
            .linkage("L-TOTAL-SALES", "S9(9)V99")
            .linkage("L-AVG-SALE", "S9(7)V99")
            .linkage("L-TOTAL-ITEMS", "9(7)")
            .linkage("L-CUST-COUNT", "9(5)")
            .linkage("L-QUOTIENT", "S9(7)")
            .linkage("L-REMAINDER", "S9(3)")
            .paragraph("CHECK-ALL", ctx -> {
                // Every assertion goes through the LINKAGE SECTION —
                // the validator reads the caller's data BY REFERENCE,
                // just like COBOL inter-program communication.

                check(valResults, "Grand Total = 5400",
                    ctx.linkageDecimal("L-GRAND-TOTAL").equalTo(Decimal.of("5400.00")));

                check(valResults, "Tax = 400",
                    ctx.linkageDecimal("L-TAX-AMOUNT").equalTo(Decimal.of("400.00")));

                check(valResults, "Total Sales = 5000",
                    ctx.linkageDecimal("L-TOTAL-SALES").equalTo(Decimal.of("5000.00")));

                check(valResults, "Average Sale = 5000",
                    ctx.linkageDecimal("L-AVG-SALE").equalTo(Decimal.of("5000.00")));

                check(valResults, "Total Items = 925",
                    ctx.linkageDecimal("L-TOTAL-ITEMS").equalTo(Decimal.of("925")));

                check(valResults, "Customer Count = 1",
                    ctx.linkageDecimal("L-CUST-COUNT").equalTo(Decimal.of("1")));

                check(valResults, "Quotient (50/3) = 16",
                    ctx.linkageDecimal("L-QUOTIENT").equalTo(Decimal.of("16")));

                check(valResults, "Remainder (50/3) = 2",
                    ctx.linkageDecimal("L-REMAINDER").equalTo(Decimal.of("2")));
            })
            .build();

        // ── CUSTORD program ────────────────────────────────────
        // This mirrors the transpiled CUSTORD logic. After computing
        // its results, it CALLs the VALIDATOR, passing fields from
        // working storage. The CALL USING mechanism shares the actual
        // field storage — the validator reads the SAME bytes.

        Program custord = Program.define("CUSTORD")
            .workingStorage(wsCustomer, wsDateArea, wsPriceTable,
                            wsAccumulators, wsControl, wsStringWork)
            .onDisplay(s -> {}) // silent

            .paragraph("MAIN-LOGIC", ctx -> {
                ctx.perform("INIT-ROUTINE");
                ctx.perform("PROCESS-DATA");
                ctx.perform("COMPUTE-SUMMARIES");
                ctx.perform("STRING-OPERATIONS");
                ctx.perform("TABLE-OPERATIONS");
                // After all processing, CALL the VALIDATOR
                ctx.perform("CALL-VALIDATOR");
                ctx.stopRun();
            })

            .paragraph("INIT-ROUTINE", ctx -> {
                wsCustomer.initialize();
                wsAccumulators.initialize();
                wsDateArea.move("WS-DATE-FULL", "20260501");
                wsCustomer.move("WS-CUST-ID", "C001");
                wsCustomer.move("WS-FIRST-NAME", "ALICE");
                wsCustomer.move("WS-LAST-NAME", "JOHNSON");
                wsCustomer.move("WS-CUST-BALANCE", Decimal.of("50000.00"));
                wsCustomer.set("ACTIVE");
                wsCustomer.set("RETAIL");
                wsCustomer.move("WS-CREDIT-LIMIT", Decimal.of("100000.00"));
                ctx.exitParagraph();
            })

            .paragraph("PROCESS-DATA", ctx -> {
                if (wsCustomer.is("ACTIVE")) {
                    wsAccumulators.add("WS-CUST-COUNT", Decimal.of("1"));
                    ctx.perform("PROCESS-ORDERS");
                }
                ctx.evaluateTrue()
                    .whenTrue(() -> wsCustomer.is("RETAIL"),
                              () -> wsAccumulators.move("WS-TAX-AMOUNT", Decimal.of("0.08")))
                    .whenTrue(() -> wsCustomer.is("WHOLESALE"),
                              () -> wsAccumulators.move("WS-TAX-AMOUNT", Decimal.of("0.05")))
                    .whenOther(() -> wsAccumulators.move("WS-TAX-AMOUNT", Decimal.of("0.10")))
                    .execute();
            })

            .paragraph("PROCESS-ORDERS", ctx -> {
                ctx.performTimes("PROCESS-SINGLE-ORDER", 5);
            })

            .paragraph("PROCESS-SINGLE-ORDER", ctx -> {
                wsAccumulators.add("WS-TOTAL-SALES", Decimal.of("1000.00"));
                wsAccumulators.add("WS-TOTAL-ITEMS", Decimal.of("10"));
            })

            .paragraph("COMPUTE-SUMMARIES", ctx -> {
                wsAccumulators.compute("WS-AVG-SALE",
                    wsAccumulators.getDecimal("WS-TOTAL-SALES")
                        .divide(wsAccumulators.getDecimal("WS-CUST-COUNT"), 10));

                Arithmetic.multiply(
                    wsAccumulators.getDecimal("WS-TOTAL-SALES"),
                    wsAccumulators.getDecimal("WS-TAX-AMOUNT"))
                    .giving(wsAccumulators.field("WS-TAX-AMOUNT"))
                    .rounded()
                    .execute();

                Arithmetic.add(
                    wsAccumulators.getDecimal("WS-TOTAL-SALES"),
                    wsAccumulators.getDecimal("WS-TAX-AMOUNT"))
                    .giving(wsAccumulators.field("WS-GRAND-TOTAL"))
                    .onSizeError(SizeErrorHandler.of(
                        () -> wsControl.set("HAS-ERROR"),
                        () -> wsControl.move("WS-ERROR-FLAG", "N")))
                    .execute();

                Arithmetic.divide(
                    wsAccumulators.getDecimal("WS-TOTAL-ITEMS"),
                    Decimal.of("3"))
                    .giving(wsAccumulators.field("WS-QUOTIENT"))
                    .remainder(wsAccumulators.field("WS-REMAINDER"))
                    .execute();
            })

            .paragraph("STRING-OPERATIONS", ctx -> {
                CobolString.into(wsStringWork, "WS-FULL-NAME")
                    .from(wsCustomer, "WS-FIRST-NAME").delimitedBySpaces()
                    .literal(" ")
                    .from(wsCustomer, "WS-LAST-NAME").delimitedBySpaces()
                    .execute();

                CobolUnstring.from(wsStringWork, "WS-FULL-NAME")
                    .delimitedBy(" ")
                    .into(wsStringWork, "WS-PART-1")
                    .into(wsStringWork, "WS-PART-2")
                    .execute();

                wsStringWork.move("WS-SPACE-COUNT",
                    (long) Inspect.on(wsStringWork, "WS-FULL-NAME").tallyAll(" ").count());
            })

            .paragraph("TABLE-OPERATIONS", ctx -> {
                wsPriceTable.move("WS-ITEM-CODE", 0, "WIDGET-A");
                wsPriceTable.move("WS-ITEM-PRICE", 0, Decimal.of("29.99"));
                wsPriceTable.move("WS-ITEM-QTY", 0, Decimal.of("100"));
                wsPriceTable.move("WS-ITEM-CODE", 1, "GADGET-B");
                wsPriceTable.move("WS-ITEM-PRICE", 1, Decimal.of("49.99"));
                wsPriceTable.move("WS-ITEM-QTY", 1, Decimal.of("50"));
                wsPriceTable.move("WS-ITEM-CODE", 2, "DEVICE-C");
                wsPriceTable.move("WS-ITEM-PRICE", 2, Decimal.of("99.99"));
                wsPriceTable.move("WS-ITEM-QTY", 2, Decimal.of("25"));
                wsPriceTable.move("WS-ITEM-CODE", 3, "TOOL-D");
                wsPriceTable.move("WS-ITEM-PRICE", 3, Decimal.of("19.99"));
                wsPriceTable.move("WS-ITEM-QTY", 3, Decimal.of("200"));
                wsPriceTable.move("WS-ITEM-CODE", 4, "PART-E");
                wsPriceTable.move("WS-ITEM-PRICE", 4, Decimal.of("9.99"));
                wsPriceTable.move("WS-ITEM-QTY", 4, Decimal.of("500"));

                wsControl.move("WS-SEARCH-CODE", "DEVICE-C");
                ctx.perform("SEARCH-TABLE");

                ctx.performVarying("ACCUMULATE-TABLE",
                    wsControl, "WS-IDX", 1, 1,
                    () -> wsControl.getDecimal("WS-IDX")
                        .greaterThan(wsPriceTable.getDecimal("WS-TABLE-SIZE")));
            })

            .paragraph("SEARCH-TABLE", ctx -> {
                Search.table(wsPriceTable, "WS-PRICE-ENTRY")
                    .atEnd(() -> wsControl.move("WS-FOUND-FLAG", "N"))
                    .when(idx -> wsPriceTable.getString("WS-ITEM-CODE", idx)
                              .trim().equals(wsControl.getString("WS-SEARCH-CODE").trim()),
                          idx -> wsControl.move("WS-FOUND-FLAG", "Y"))
                    .execute();
            })

            .paragraph("ACCUMULATE-TABLE", ctx -> {
                wsAccumulators.add("WS-TOTAL-ITEMS",
                    wsPriceTable.getDecimal("WS-ITEM-QTY",
                        (int) wsControl.getLong("WS-IDX") - 1));
            })

            // ── The interop moment: CALL VALIDATOR USING fields ──
            .paragraph("CALL-VALIDATOR", ctx -> {
                ctx.call(validator,
                    wsAccumulators.field("WS-GRAND-TOTAL"),
                    wsAccumulators.field("WS-TAX-AMOUNT"),
                    wsAccumulators.field("WS-TOTAL-SALES"),
                    wsAccumulators.field("WS-AVG-SALE"),
                    wsAccumulators.field("WS-TOTAL-ITEMS"),
                    wsAccumulators.field("WS-CUST-COUNT"),
                    wsAccumulators.field("WS-QUOTIENT"),
                    wsAccumulators.field("WS-REMAINDER"));
            })

            .build();

        // ── Run CUSTORD — it populates records then CALLs VALIDATOR ──
        custord.run();

        // ── Check VALIDATOR results ────────────────────────────
        int passes = valResults.getInt("VAL-PASS-COUNT");
        int fails = valResults.getInt("VAL-FAIL-COUNT");
        String lastFail = valResults.getString("VAL-LAST-FAIL").trim();

        assertEquals(0, fails,
            "VALIDATOR reported " + fails + " failure(s), last: " + lastFail);
        assertEquals(8, passes,
            "VALIDATOR should have checked 8 values, checked: " + passes);

        // ── Also verify fields that VALIDATOR didn't check ─────
        // (alphanumeric fields, conditions, REDEFINES, STRING results)
        assertEquals("C001", wsCustomer.getString("WS-CUST-ID").trim());
        assertEquals("ALICE", wsCustomer.getString("WS-FIRST-NAME").trim());
        assertEquals("JOHNSON", wsCustomer.getString("WS-LAST-NAME").trim());
        assertTrue(wsCustomer.is("ACTIVE"));
        assertTrue(wsCustomer.is("RETAIL"));

        assertEquals("2026", wsDateArea.getString("WS-DATE-YEAR").trim());
        assertEquals("05", wsDateArea.getString("WS-DATE-MONTH").trim());
        assertEquals("01", wsDateArea.getString("WS-DATE-DAY").trim());

        assertTrue(wsStringWork.getString("WS-FULL-NAME").trim().startsWith("ALICE JOHNSON"));
        assertEquals("ALICE", wsStringWork.getString("WS-PART-1").trim());
        assertEquals("JOHNSON", wsStringWork.getString("WS-PART-2").trim());

        assertTrue(wsControl.is("ITEM-FOUND"));
        assertFalse(wsControl.is("HAS-ERROR"));
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Record a pass or fail into the validation results record.
     * This is what a COBOL validation program would do:
     * increment a counter and save the last failure message.
     */
    private static void check(Record valResults, String label, boolean passed) {
        if (passed) {
            valResults.add("VAL-PASS-COUNT", Decimal.ONE);
        } else {
            valResults.add("VAL-FAIL-COUNT", Decimal.ONE);
            valResults.move("VAL-LAST-FAIL", label);
        }
    }

    private void assertOutput(String expected) {
        assertTrue(ranSuccessfully, "Program must have run before checking output");
        assertTrue(displayOutput.stream().anyMatch(line -> line.contains(expected)),
            "Expected output containing '" + expected + "' but got:\n"
            + String.join("\n", displayOutput));
    }
}
