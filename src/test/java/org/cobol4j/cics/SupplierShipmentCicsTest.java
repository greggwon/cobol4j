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
package org.cobol4j.cics;

import org.cobol4j.Record;
import org.cobol4j.Decimal;
import org.cobol4j.CobolFile;
import org.cobol4j.ConnectionFactory;
import org.cobol4j.SqlSession;
import org.cobol4j.SqlCursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Supplier Shipment scenario deployed as CICS transactions.
 *
 * <h2>What this demonstrates</h2>
 *
 * <p>The same supplier shipment workflow from {@code SupplierShipmentTest},
 * but repackaged as CICS programs running inside a {@link CicsRegion}.
 * This shows how batch COBOL programs are restructured for online
 * transaction processing:</p>
 *
 * <ul>
 *   <li><b>Transaction SINT</b> (SHIPINTK) — receives a shipment via COMMAREA,
 *       validates, inserts into DB, writes to temp storage queue for the
 *       report writer to pick up later</li>
 *   <li><b>Transaction SQRY</b> (SHIPQRY) — receives a supplier ID via COMMAREA,
 *       queries the DB, returns summary totals in the COMMAREA</li>
 *   <li><b>Transaction SRPT</b> (RPTWRT) — reads the TS queue of pending
 *       suppliers, LINKs to SHIPQRY for each, writes report files</li>
 * </ul>
 *
 * <h2>Key differences from batch</h2>
 *
 * <p>In batch (SupplierShipmentTest), programs communicate via CALL USING
 * with LINKAGE SECTION fields. In CICS:</p>
 * <ul>
 *   <li>Programs communicate via <b>COMMAREA</b> (a shared byte buffer)</li>
 *   <li>Notifications use <b>Temporary Storage queues</b> instead of MessagePort</li>
 *   <li>Programs are <b>installed</b> in a region and <b>dispatched</b> by
 *       transaction ID</li>
 *   <li>One program can <b>LINK</b> to another (call with return) or
 *       <b>XCTL</b> (transfer control)</li>
 *   <li>The region manages <b>resource lifecycle</b> (files, databases, queues)</li>
 * </ul>
 */
class SupplierShipmentCicsTest {

    /** Shipment test data: {supplier-id, name, item, qty, price, date} */
    private static final String[][] SHIPMENTS = {
        {"SA", "ACME WIDGETS",     "STEEL BOLTS 1/4 IN",  "100", "12.50", "2026-04-01"},
        {"SA", "ACME WIDGETS",     "HEX NUTS 3/8 IN",     "150",  "8.75", "2026-04-03"},
        {"SA", "ACME WIDGETS",     "FLAT WASHERS",         "100",  "5.25", "2026-04-05"},
        {"SB", "BAKER SUPPLY",     "COPPER WIRE 12GA",     "200", "15.00", "2026-04-02"},
        {"SB", "BAKER SUPPLY",     "SOLDER PASTE",          "50", "22.50", "2026-04-04"},
        {"SB", "BAKER SUPPLY",     "HEAT SHRINK TUBE",     "150",  "6.75", "2026-04-06"},
        {"SB", "BAKER SUPPLY",     "WIRE CONNECTORS",      "100",  "9.80", "2026-04-08"},
        {"SC", "CARTER LOGISTICS", "CARDBOARD BOXES LG",    "75", "18.50", "2026-04-01"},
        {"SC", "CARTER LOGISTICS", "PACKING TAPE",         "100",  "4.25", "2026-04-03"},
        {"SC", "CARTER LOGISTICS", "BUBBLE WRAP ROLL",     "100", "12.75", "2026-04-07"},
        {"SD", "DELTA MATERIALS",  "PVC PIPE 2IN",         "150", "14.50", "2026-04-02"},
        {"SD", "DELTA MATERIALS",  "PIPE FITTINGS",        "100",  "8.25", "2026-04-05"},
        {"SD", "DELTA MATERIALS",  "VALVE ASSEMBLY",       "150", "22.00", "2026-04-09"},
    };

    @Test
    void supplierShipmentViaCics(@TempDir Path tempDir) {

        // ═══════════════════════════════════════════════════════════════
        //  COMMAREA LAYOUTS — the shared contract between programs
        // ═══════════════════════════════════════════════════════════════

        // COMMAREA for SINT (intake) — request + response in one buffer
        Record intakeCa = Record.define("INTAKE-CA")
            .pic("CA-SUPPLIER-ID",   "X(4)")
            .pic("CA-SUPPLIER-NAME", "X(20)")
            .pic("CA-ITEM-DESC",     "X(30)")
            .pic("CA-SHIP-QTY",      "9(5)")
            .pic("CA-UNIT-PRICE",    "S9(5)V99")
            .pic("CA-SHIP-DATE",     "X(10)")
            .pic("CA-STATUS-CODE",   "XX")
                .value88("CA-OK",      "00")
                .value88("CA-INVALID", "10")
                .value88("CA-DB-ERR",  "20")
            .pic("CA-STATUS-MSG",    "X(40)")
            .build();

        // COMMAREA for SQRY (query) — request: supplier ID; response: totals
        Record queryCa = Record.define("QUERY-CA")
            .pic("QC-SUPPLIER-ID",   "X(4)")
            .pic("QC-SUPPLIER-NAME", "X(20)")
            .pic("QC-ITEM-COUNT",    "9(5)")
            .pic("QC-TOTAL-QTY",     "9(7)")
            .pic("QC-TOTAL-VALUE",   "S9(9)V99")
            .pic("QC-LATEST-DATE",   "X(10)")
            .build();

        // TS queue notification record
        Record tsNotify = Record.define("TS-NOTIFY")
            .pic("TS-SUPPLIER-ID", "X(4)")
            .build();

        // Report line buffer
        Record rptLine = Record.define("RPT-LINE")
            .pic("RPT-DATA", "X(80)")
            .build();

        // ═══════════════════════════════════════════════════════════════
        //  INFRASTRUCTURE — database + region setup
        // ═══════════════════════════════════════════════════════════════

        ConnectionFactory dbFactory = ConnectionFactory.sqliteInMemory();

        SqlSession.work(dbFactory, session -> {
            session.sql().executeImmediate(
                "CREATE TABLE SHIPMENTS ("
                + "SUPPLIER_ID VARCHAR(4) NOT NULL, "
                + "SUPPLIER_NAME VARCHAR(20) NOT NULL, "
                + "ITEM_DESC VARCHAR(30) NOT NULL, "
                + "SHIP_QTY INTEGER NOT NULL, "
                + "UNIT_PRICE DECIMAL(7,2) NOT NULL, "
                + "SHIP_DATE VARCHAR(10) NOT NULL)");
        });

        // ═══════════════════════════════════════════════════════════════
        //  CICS REGION — the transaction container
        // ═══════════════════════════════════════════════════════════════

        CicsRegion region = CicsRegion.create("SHIPDEMO")
            .database("SHIPDB", dbFactory)

            // ── SHIPINTK — Transaction SINT ────────────────────────
            //
            // EXEC CICS RECEIVE INTO(INTAKE-CA)
            // ... validate ...
            // EXEC SQL INSERT INTO SHIPMENTS ...
            // EXEC CICS WRITEQ TS QUEUE('SHIPRPT') FROM(TS-NOTIFY)
            // EXEC CICS SEND FROM(INTAKE-CA)
            // EXEC CICS RETURN
            //
            .install("SHIPINTK", ctx -> {
                ctx.receive(intakeCa);

                // Validate
                String suppId = intakeCa.getString("CA-SUPPLIER-ID").trim();
                if (suppId.isEmpty()) {
                    intakeCa.move("CA-STATUS-CODE", "10");
                    intakeCa.move("CA-STATUS-MSG", "SUPPLIER ID IS SPACES");
                    ctx.send(intakeCa);
                    ctx.returnTransaction();
                    return;
                }
                if (intakeCa.getDecimal("CA-SHIP-QTY").compareTo(Decimal.ZERO) <= 0) {
                    intakeCa.move("CA-STATUS-CODE", "10");
                    intakeCa.move("CA-STATUS-MSG", "QUANTITY MUST BE > 0");
                    ctx.send(intakeCa);
                    ctx.returnTransaction();
                    return;
                }

                // Insert into database
                SqlSession.work(dbFactory, session -> {
                    session.sql().execute(
                        "INSERT INTO SHIPMENTS "
                        + "(SUPPLIER_ID, SUPPLIER_NAME, ITEM_DESC, "
                        + "SHIP_QTY, UNIT_PRICE, SHIP_DATE) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                        .param(intakeCa.getString("CA-SUPPLIER-ID").trim())
                        .param(intakeCa.getString("CA-SUPPLIER-NAME").trim())
                        .param(intakeCa.getString("CA-ITEM-DESC").trim())
                        .param(intakeCa.getDecimal("CA-SHIP-QTY"))
                        .param(intakeCa.getDecimal("CA-UNIT-PRICE"))
                        .param(intakeCa.getString("CA-SHIP-DATE").trim())
                        .execute();
                });

                // Write to TS queue so report writer knows which suppliers changed
                tsNotify.move("TS-SUPPLIER-ID", suppId);
                ctx.writeTS("SHIPRPT", tsNotify);

                intakeCa.move("CA-STATUS-CODE", "00");
                intakeCa.move("CA-STATUS-MSG", "SHIPMENT RECORDED");
                ctx.send(intakeCa);
                ctx.returnTransaction();
            })

            // ── SHIPQRY — Transaction SQRY ─────────────────────────
            //
            // EXEC CICS RECEIVE INTO(QUERY-CA)
            // EXEC SQL DECLARE CURSOR ...
            // EXEC SQL FETCH ... (loop)
            // EXEC CICS SEND FROM(QUERY-CA)
            // EXEC CICS RETURN
            //
            .install("SHIPQRY", ctx -> {
                ctx.receive(queryCa);

                queryCa.move("QC-SUPPLIER-NAME", "");
                queryCa.move("QC-ITEM-COUNT", Decimal.ZERO);
                queryCa.move("QC-TOTAL-QTY", Decimal.ZERO);
                queryCa.move("QC-TOTAL-VALUE", Decimal.ZERO);
                queryCa.move("QC-LATEST-DATE", "");

                String suppId = queryCa.getString("QC-SUPPLIER-ID").trim();

                Record fetchRec = Record.define("F")
                    .pic("F-NAME", "X(20)")
                    .pic("F-QTY", "9(5)")
                    .pic("F-PRICE", "S9(5)V99")
                    .pic("F-DATE", "X(10)")
                    .build();

                SqlSession.work(dbFactory, session -> {
                    SqlCursor cursor = session.sql().declareCursor("SC",
                        "SELECT SUPPLIER_NAME, SHIP_QTY, UNIT_PRICE, SHIP_DATE "
                        + "FROM SHIPMENTS WHERE SUPPLIER_ID = ? ORDER BY SHIP_DATE");
                    session.sql().open(cursor, suppId);
                    session.sql().fetch(cursor)
                        .into(fetchRec, "F-NAME", "F-QTY", "F-PRICE", "F-DATE")
                        .execute();

                    boolean first = true;
                    while (session.isSuccess()) {
                        if (first) {
                            queryCa.move("QC-SUPPLIER-NAME",
                                fetchRec.getString("F-NAME").trim());
                            first = false;
                        }
                        queryCa.add("QC-ITEM-COUNT", Decimal.ONE);
                        Decimal qty = fetchRec.getDecimal("F-QTY");
                        queryCa.add("QC-TOTAL-QTY", qty);
                        queryCa.add("QC-TOTAL-VALUE",
                            qty.multiply(fetchRec.getDecimal("F-PRICE")));

                        String rowDate = fetchRec.getString("F-DATE").trim();
                        String curDate = queryCa.getString("QC-LATEST-DATE").trim();
                        if (curDate.isEmpty() || rowDate.compareTo(curDate) > 0) {
                            queryCa.move("QC-LATEST-DATE", rowDate);
                        }

                        session.sql().fetch(cursor)
                            .into(fetchRec, "F-NAME", "F-QTY", "F-PRICE", "F-DATE")
                            .execute();
                    }
                    session.sql().close(cursor);
                });

                ctx.send(queryCa);
                ctx.returnTransaction();
            })

            // ── RPTWRT — Transaction SRPT ──────────────────────────
            //
            // EXEC CICS READQ TS QUEUE('SHIPRPT') (loop)
            // EXEC CICS LINK PROGRAM('SHIPQRY') COMMAREA(QUERY-CA)
            // ... write report files ...
            // EXEC CICS RETURN
            //
            .install("RPTWRT", ctx -> {
                // Drain the TS queue to find unique suppliers
                java.util.LinkedHashSet<String> suppliers = new java.util.LinkedHashSet<>();
                while (ctx.readTSNext("SHIPRPT", tsNotify)) {
                    suppliers.add(tsNotify.getString("TS-SUPPLIER-ID").trim());
                }

                // Grand totals
                int grandItems = 0, grandQty = 0;
                double grandValue = 0;

                // Per-supplier reports
                for (String suppId : suppliers) {
                    // LINK to SHIPQRY — call and return within same task
                    queryCa.move("QC-SUPPLIER-ID", suppId);
                    ctx.link("SHIPQRY", queryCa);

                    int items = queryCa.getInt("QC-ITEM-COUNT");
                    int totalQty = queryCa.getInt("QC-TOTAL-QTY");
                    double totalVal = queryCa.getDecimal("QC-TOTAL-VALUE")
                        .toBigDecimal().doubleValue();
                    String name = queryCa.getString("QC-SUPPLIER-NAME").trim();
                    String lastDate = queryCa.getString("QC-LATEST-DATE").trim();

                    grandItems += items;
                    grandQty += totalQty;
                    grandValue += totalVal;

                    // Write per-supplier report
                    Path rptPath = tempDir.resolve(suppId + ".rpt");
                    CobolFile rptFile = CobolFile.sequential(suppId + "-RPT")
                        .assignTo(rptPath.toString()).recordSize(80).build();
                    rptFile.open(CobolFile.OpenMode.OUTPUT);
                    writeLine(rptFile, rptLine, "================================================================");
                    writeLine(rptFile, rptLine, String.format(
                        "SUPPLIER SHIPMENT REPORT                    DATE: %s", "2026-05-07"));
                    writeLine(rptFile, rptLine, String.format(
                        "SUPPLIER: %-4s %s", suppId, name));
                    writeLine(rptFile, rptLine, String.format(
                        "LAST SHIPMENT: %s", lastDate));
                    writeLine(rptFile, rptLine, "================================================================");
                    writeLine(rptFile, rptLine, String.format(
                        "ITEMS SHIPPED: %05d        TOTAL QTY:     %07d", items, totalQty));
                    writeLine(rptFile, rptLine, String.format(
                        "TOTAL VALUE:   $%,12.2f", totalVal));
                    writeLine(rptFile, rptLine, "================================================================");
                    rptFile.close();
                }

                // Consolidated report
                Path allPath = tempDir.resolve("ALL-SUPPLIERS.rpt");
                CobolFile allFile = CobolFile.sequential("ALL-RPT")
                    .assignTo(allPath.toString()).recordSize(80).build();
                allFile.open(CobolFile.OpenMode.OUTPUT);
                writeLine(allFile, rptLine, "================================================================");
                writeLine(allFile, rptLine, String.format(
                    "CONSOLIDATED SHIPMENT REPORT                DATE: %s", "2026-05-07"));
                writeLine(allFile, rptLine, "================================================================");
                writeLine(allFile, rptLine,
                    "SUPPLIER  NAME                 ITEMS  TOTAL QTY  LAST SHIP   TOTAL VALUE");
                writeLine(allFile, rptLine,
                    "--------  -------------------  -----  ---------  ----------  -----------");

                for (String suppId : suppliers) {
                    queryCa.move("QC-SUPPLIER-ID", suppId);
                    ctx.link("SHIPQRY", queryCa);
                    writeLine(allFile, rptLine, String.format(
                        "%-8s  %-19s  %05d    %07d  %-10s  $%,10.2f",
                        suppId,
                        queryCa.getString("QC-SUPPLIER-NAME").trim(),
                        queryCa.getInt("QC-ITEM-COUNT"),
                        queryCa.getInt("QC-TOTAL-QTY"),
                        queryCa.getString("QC-LATEST-DATE").trim(),
                        queryCa.getDecimal("QC-TOTAL-VALUE")
                            .toBigDecimal().doubleValue()));
                }
                writeLine(allFile, rptLine,
                    "                               -----  ---------              -----------");
                writeLine(allFile, rptLine, String.format(
                    "GRAND TOTAL:                   %05d    %07d              $%,10.2f",
                    grandItems, grandQty, grandValue));
                writeLine(allFile, rptLine, "================================================================");
                allFile.close();

                ctx.returnTransaction();
            })

            .start();

        // Map 4-character transaction IDs to programs
        region.transaction("SINT", "SHIPINTK");
        region.transaction("SQRY", "SHIPQRY");
        region.transaction("SRPT", "RPTWRT");

        // ═══════════════════════════════════════════════════════════════
        //  EXECUTE — dispatch transactions just like a CICS terminal
        // ═══════════════════════════════════════════════════════════════

        // Load shipments — each dispatch is like a terminal user
        // entering transaction "SINT" with the shipment data
        for (String[] data : SHIPMENTS) {
            intakeCa.move("CA-SUPPLIER-ID",   data[0]);
            intakeCa.move("CA-SUPPLIER-NAME", data[1]);
            intakeCa.move("CA-ITEM-DESC",     data[2]);
            intakeCa.move("CA-SHIP-QTY",      Decimal.of(data[3]));
            intakeCa.move("CA-UNIT-PRICE",    Decimal.of(data[4]));
            intakeCa.move("CA-SHIP-DATE",     data[5]);

            region.dispatch("SINT", intakeCa);

            assertTrue(intakeCa.is("CA-OK"),
                "SINT failed for " + data[0] + "/" + data[2] + ": "
                + intakeCa.getString("CA-STATUS-MSG").trim());
        }

        // Dispatch the report writer — it reads the TS queue,
        // LINKs to SHIPQRY, and writes all reports
        region.dispatch("SRPT", new byte[0]);

        // ═══════════════════════════════════════════════════════════════
        //  VALIDATE
        // ═══════════════════════════════════════════════════════════════

        // Database
        Record cnt = Record.define("C").pic("C", "9(5)").build();
        SqlSession.work(dbFactory, session -> {
            session.sql().select("SELECT COUNT(*) FROM SHIPMENTS")
                .into(cnt, "C").execute();
        });
        assertEquals(13, cnt.getInt("C"));

        // Report files exist
        assertTrue(Files.exists(tempDir.resolve("SA.rpt")));
        assertTrue(Files.exists(tempDir.resolve("SB.rpt")));
        assertTrue(Files.exists(tempDir.resolve("SC.rpt")));
        assertTrue(Files.exists(tempDir.resolve("SD.rpt")));
        assertTrue(Files.exists(tempDir.resolve("ALL-SUPPLIERS.rpt")));

        // Spot-check per-supplier report
        assertFileContains(tempDir.resolve("SA.rpt"), "ACME WIDGETS");
        assertFileContains(tempDir.resolve("SA.rpt"), "3,087.50");
        assertFileContains(tempDir.resolve("SA.rpt"), "2026-04-05");

        // Consolidated report totals
        assertFileContains(tempDir.resolve("ALL-SUPPLIERS.rpt"), "CONSOLIDATED");
        assertFileContains(tempDir.resolve("ALL-SUPPLIERS.rpt"), "18,592.50");

        // Query a single supplier via SQRY transaction
        queryCa.move("QC-SUPPLIER-ID", "SB");
        region.dispatch("SQRY", queryCa);
        assertEquals("BAKER SUPPLY", queryCa.getString("QC-SUPPLIER-NAME").trim());
        assertEquals(4, queryCa.getInt("QC-ITEM-COUNT"));
        assertEquals(500, queryCa.getInt("QC-TOTAL-QTY"));
        assertTrue(queryCa.getDecimal("QC-TOTAL-VALUE")
            .equalTo(Decimal.of("6117.50")));
        assertEquals("2026-04-08", queryCa.getString("QC-LATEST-DATE").trim());

        // Validation rejection via CICS
        intakeCa.moveSpaces("CA-SUPPLIER-ID");
        intakeCa.move("CA-SHIP-QTY", Decimal.of("100"));
        intakeCa.move("CA-UNIT-PRICE", Decimal.of("10.00"));
        region.dispatch("SINT", intakeCa);
        assertTrue(intakeCa.is("CA-INVALID"),
            "Blank supplier should be rejected via CICS too");

        region.stop();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private static void writeLine(CobolFile file, Record lineRec, String text) {
        String padded = String.format("%-80s", text);
        if (padded.length() > 80) padded = padded.substring(0, 80);
        lineRec.move("RPT-DATA", padded);
        file.write(lineRec);
    }

    private static void assertFileContains(Path path, String expected) {
        try {
            String content = Files.readString(path);
            assertTrue(content.contains(expected),
                path.getFileName() + " should contain '" + expected + "'");
        } catch (Exception e) {
            fail("Cannot read " + path + ": " + e.getMessage());
        }
    }
}
