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

import org.cobol4j.interop.InMemoryMessagePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-program supplier shipment demonstration.
 *
 * <h2>What this test demonstrates</h2>
 *
 * <p>Three COBOL-style programs communicate through the cobol4j API:</p>
 * <ul>
 *   <li><b>SHIPINTK</b> — receives shipment records via CALL USING, validates them,
 *       stores them in SQLite, and sends a notification via MessagePort</li>
 *   <li><b>SHIPQRY</b> — called via CALL USING with a supplier ID, queries SQLite
 *       with a cursor, and returns summary totals via linkage</li>
 *   <li><b>RPTWRT</b> — receives notifications from the message queue, calls SHIPQRY
 *       for each supplier, and writes per-supplier and consolidated report files</li>
 * </ul>
 *
 * <p>The scenario: suppliers A through D ship items to a company. Each shipment
 * carries a supplier ID, name, item description, quantity, unit price, and date.
 * The system records each shipment, then generates reports showing each supplier's
 * total quantities, values, and most recent shipment date.</p>
 *
 * <h2>COBOL features exercised</h2>
 * <ul>
 *   <li>CALL ... USING / LINKAGE SECTION (inter-program record exchange)</li>
 *   <li>EXEC SQL INSERT, DECLARE CURSOR, FETCH (database I/O)</li>
 *   <li>OPEN, WRITE, CLOSE (sequential file report generation)</li>
 *   <li>MQ SEND / RECEIVE (inter-program notification)</li>
 *   <li>Level-88 conditions (status codes)</li>
 *   <li>COMPUTE / ADD / MULTIPLY (money arithmetic)</li>
 *   <li>PERFORM UNTIL (cursor fetch loop)</li>
 *   <li>IF / EVALUATE (validation, flow control)</li>
 * </ul>
 */
class SupplierShipmentTest {

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
    void supplierShipmentEndToEnd(@TempDir Path tempDir) {

        // ═══════════════════════════════════════════════════════════════
        //  INFRASTRUCTURE SETUP
        // ═══════════════════════════════════════════════════════════════

        // Shared SQLite database (in-memory, cached so all programs see same data)
        ConnectionFactory dbFactory = ConnectionFactory.sqliteInMemory();

        // Create the SHIPMENTS table
        SqlSession.work(dbFactory, session -> {
            session.sql().executeImmediate(
                "CREATE TABLE SHIPMENTS ("
                + "SUPPLIER_ID   VARCHAR(4)   NOT NULL, "
                + "SUPPLIER_NAME VARCHAR(20)  NOT NULL, "
                + "ITEM_DESC     VARCHAR(30)  NOT NULL, "
                + "SHIP_QTY      INTEGER      NOT NULL, "
                + "UNIT_PRICE    DECIMAL(7,2) NOT NULL, "
                + "SHIP_DATE     VARCHAR(10)  NOT NULL)");
        });

        // MessagePort queue for SHIPINTK → RPTWRT notifications
        BlockingQueue<byte[]> notifyQueue = new LinkedBlockingQueue<>();
        InMemoryMessagePort notifySender =
            InMemoryMessagePort.fireAndForget("SHIP-NOTIFY", notifyQueue);
        InMemoryMessagePort notifyReceiver =
            InMemoryMessagePort.fireAndForget("SHIP-NOTIFY", notifyQueue);
        notifySender.open();
        notifyReceiver.open();

        // ═══════════════════════════════════════════════════════════════
        //  RECORD DEFINITIONS
        // ═══════════════════════════════════════════════════════════════

        // ── Shipment input record ──────────────────────────────────
        Record shipmentRec = Record.define("WS-SHIPMENT-REC")
            .pic("WS-SUPPLIER-ID",   "X(4)")
            .pic("WS-SUPPLIER-NAME", "X(20)")
            .pic("WS-ITEM-DESC",     "X(30)")
            .pic("WS-SHIP-QTY",      "9(5)")
            .pic("WS-UNIT-PRICE",    "S9(5)V99")
            .pic("WS-SHIP-DATE",     "X(10)")
            .build();

        // ── Intake status (returned by SHIPINTK) ───────────────────
        Record intakeStatus = Record.define("WS-INTAKE-STATUS")
            .pic("WS-STATUS-CODE", "XX")
                .value88("INTAKE-OK",       "00")
                .value88("INTAKE-INVALID",  "10")
                .value88("INTAKE-DB-ERROR", "20")
            .pic("WS-STATUS-MSG", "X(40)")
            .build();

        // ── Notification record (MessagePort payload) ──────────────
        Record notification = Record.define("WS-NOTIFICATION")
            .pic("WS-NOTIFY-TYPE", "X")
                .value88("NOTIFY-UPDATED", "U")
                .value88("NOTIFY-END",     "E")
            .pic("WS-NOTIFY-SUPPLIER", "X(4)")
            .build();

        // ── Query result (returned by SHIPQRY) ────────────────────
        Record queryResult = Record.define("WS-QUERY-RESULT")
            .pic("WS-QR-SUPPLIER-NAME", "X(20)")
            .pic("WS-QR-ITEM-COUNT",    "9(5)")
            .pic("WS-QR-TOTAL-QTY",     "9(7)")
            .pic("WS-QR-TOTAL-VALUE",   "S9(9)V99")
            .pic("WS-QR-LATEST-DATE",   "X(10)")
            .build();

        // ── Report output line ─────────────────────────────────────
        Record reportLine = Record.define("WS-REPORT-LINE")
            .pic("WS-RPT-DATA", "X(80)")
            .build();

        // ── Grand totals (accumulated by RPTWRT) ───────────────────
        Record grandTotals = Record.define("WS-GRAND-TOTALS")
            .pic("WS-GT-ITEMS",   "9(5)").value("0")
            .pic("WS-GT-QTY",     "9(7)").value("0")
            .pic("WS-GT-VALUE",   "S9(9)V99").value("0")
            .build();

        // ═══════════════════════════════════════════════════════════════
        //  PROGRAM 1: SHIPINTK — Shipment Intake
        //
        //  COBOL equivalent:
        //    CALL "SHIPINTK" USING WS-SUPPLIER-ID WS-SUPPLIER-NAME
        //         WS-ITEM-DESC WS-SHIP-QTY WS-UNIT-PRICE WS-SHIP-DATE
        //         WS-STATUS-CODE WS-STATUS-MSG
        // ═══════════════════════════════════════════════════════════════

        Program shipIntake = Program.define("SHIPINTK")
            .linkage("LS-SUPPLIER-ID",   "X(4)")
            .linkage("LS-SUPPLIER-NAME", "X(20)")
            .linkage("LS-ITEM-DESC",     "X(30)")
            .linkage("LS-SHIP-QTY",      "9(5)")
            .linkage("LS-UNIT-PRICE",    "S9(5)V99")
            .linkage("LS-SHIP-DATE",     "X(10)")
            .linkage("LS-STATUS-CODE",   "XX")
            .linkage("LS-STATUS-MSG",    "X(40)")

            // ── VALIDATE-INPUT ─────────────────────────────────────
            .paragraph("VALIDATE-INPUT", ctx -> {
                String suppId = ctx.linkageField("LS-SUPPLIER-ID").trimmed();
                if (suppId.isEmpty()) {
                    ctx.linkageField("LS-STATUS-CODE").move("10");
                    ctx.linkageField("LS-STATUS-MSG").move("SUPPLIER ID IS SPACES");
                    ctx.stopRun();
                }
                if (ctx.linkageField("LS-SHIP-QTY").get().compareTo(Decimal.ZERO) <= 0) {
                    ctx.linkageField("LS-STATUS-CODE").move("10");
                    ctx.linkageField("LS-STATUS-MSG").move("QUANTITY MUST BE > 0");
                    ctx.stopRun();
                }
                if (ctx.linkageField("LS-UNIT-PRICE").get().compareTo(Decimal.ZERO) <= 0) {
                    ctx.linkageField("LS-STATUS-CODE").move("10");
                    ctx.linkageField("LS-STATUS-MSG").move("UNIT PRICE MUST BE > 0");
                    ctx.stopRun();
                }
            })

            // ── INSERT-SHIPMENT ────────────────────────────────────
            .paragraph("INSERT-SHIPMENT", ctx -> {
                SqlSession.work(dbFactory, session -> {
                    session.sql().execute(
                        "INSERT INTO SHIPMENTS "
                        + "(SUPPLIER_ID, SUPPLIER_NAME, ITEM_DESC, SHIP_QTY, UNIT_PRICE, SHIP_DATE) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                        .param(ctx.linkageField("LS-SUPPLIER-ID").trimmed())
                        .param(ctx.linkageField("LS-SUPPLIER-NAME").trimmed())
                        .param(ctx.linkageField("LS-ITEM-DESC").trimmed())
                        .param(ctx.linkageField("LS-SHIP-QTY").get())
                        .param(ctx.linkageField("LS-UNIT-PRICE").get())
                        .param(ctx.linkageField("LS-SHIP-DATE").trimmed())
                        .execute();
                }, ex -> {
                    ctx.linkageField("LS-STATUS-CODE").move("20");
                    ctx.linkageField("LS-STATUS-MSG").move("DB ERROR");
                    ctx.stopRun();
                });
            })

            // ── SEND-NOTIFICATION ──────────────────────────────────
            .paragraph("SEND-NOTIFICATION", ctx -> {
                ctx.linkageField("LS-STATUS-CODE").move("00");
                ctx.linkageField("LS-STATUS-MSG").move("SHIPMENT RECORDED");
                notification.move("WS-NOTIFY-TYPE", "U");
                notification.move("WS-NOTIFY-SUPPLIER",
                    ctx.linkageField("LS-SUPPLIER-ID").trimmed());
                notifySender.send(notification);
            })
            .build();

        // ═══════════════════════════════════════════════════════════════
        //  PROGRAM 2: SHIPQRY — Shipment Query
        //
        //  COBOL equivalent:
        //    CALL "SHIPQRY" USING WS-SUPPLIER-ID
        //         WS-QR-SUPPLIER-NAME WS-QR-ITEM-COUNT
        //         WS-QR-TOTAL-QTY WS-QR-TOTAL-VALUE WS-QR-LATEST-DATE
        // ═══════════════════════════════════════════════════════════════

        Program shipQuery = Program.define("SHIPQRY")
            .linkage("LS-SUPPLIER-ID",    "X(4)")
            .linkage("LS-SUPPLIER-NAME",  "X(20)")
            .linkage("LS-ITEM-COUNT",     "9(5)")
            .linkage("LS-TOTAL-QTY",      "9(7)")
            .linkage("LS-TOTAL-VALUE",    "S9(9)V99")
            .linkage("LS-LATEST-DATE",    "X(10)")

            .paragraph("INIT-ACCUMULATORS", ctx -> {
                ctx.linkageField("LS-SUPPLIER-NAME").moveSpaces();
                ctx.linkageField("LS-ITEM-COUNT").moveZeros();
                ctx.linkageField("LS-TOTAL-QTY").moveZeros();
                ctx.linkageField("LS-TOTAL-VALUE").moveZeros();
                ctx.linkageField("LS-LATEST-DATE").moveSpaces();
            })

            .paragraph("QUERY-SUPPLIER", ctx -> {
                Record fetchRec = Record.define("FETCH-REC")
                    .pic("F-NAME",  "X(20)")
                    .pic("F-QTY",   "9(5)")
                    .pic("F-PRICE", "S9(5)V99")
                    .pic("F-DATE",  "X(10)")
                    .build();

                SqlSession.work(dbFactory, session -> {
                    SqlCursor cursor = session.sql().declareCursor("SUPP-CUR",
                        "SELECT SUPPLIER_NAME, SHIP_QTY, UNIT_PRICE, SHIP_DATE "
                        + "FROM SHIPMENTS WHERE SUPPLIER_ID = ? "
                        + "ORDER BY SHIP_DATE");

                    session.sql().open(cursor,
                        ctx.linkageField("LS-SUPPLIER-ID").trimmed());

                    session.sql().fetch(cursor)
                        .into(fetchRec, "F-NAME", "F-QTY", "F-PRICE", "F-DATE")
                        .execute();

                    boolean first = true;
                    while (session.isSuccess()) {
                        // Capture supplier name from first row
                        if (first) {
                            ctx.linkageField("LS-SUPPLIER-NAME")
                                .move(fetchRec.getString("F-NAME").trim());
                            first = false;
                        }

                        // Accumulate
                        ctx.linkageField("LS-ITEM-COUNT").add(Decimal.ONE);

                        Decimal qty = fetchRec.getDecimal("F-QTY");
                        ctx.linkageField("LS-TOTAL-QTY").add(qty);

                        Decimal lineValue = qty.multiply(fetchRec.getDecimal("F-PRICE"));
                        ctx.linkageField("LS-TOTAL-VALUE").add(lineValue);

                        // Track latest date (string compare works for ISO dates)
                        String rowDate = fetchRec.getString("F-DATE").trim();
                        String curLatest = ctx.linkageField("LS-LATEST-DATE").trimmed();
                        if (curLatest.isEmpty() || rowDate.compareTo(curLatest) > 0) {
                            ctx.linkageField("LS-LATEST-DATE").move(rowDate);
                        }

                        // Fetch next
                        session.sql().fetch(cursor)
                            .into(fetchRec, "F-NAME", "F-QTY", "F-PRICE", "F-DATE")
                            .execute();
                    }
                    session.sql().close(cursor);
                });
            })
            .build();

        // ═══════════════════════════════════════════════════════════════
        //  PROGRAM 3: RPTWRT — Report Writer
        //
        //  Receives notifications, queries each supplier, writes reports.
        //  Uses CobolFile for sequential report output.
        // ═══════════════════════════════════════════════════════════════

        // Collect unique supplier IDs from notifications
        LinkedHashSet<String> suppliers = new LinkedHashSet<>();
        Record rptNotify = Record.define("RPT-NOTIFY")
            .pic("RPT-N-TYPE", "X")
            .pic("RPT-N-SUPP", "X(4)")
            .build();

        Program rptwrt = Program.define("RPTWRT")
            .workingStorage(queryResult, reportLine, grandTotals)
            .onDisplay(s -> {}) // silent

            // ── RECEIVE-NOTIFICATIONS ──────────────────────────────
            .paragraph("RECEIVE-NOTIFICATIONS", ctx -> {
                boolean done = false;
                while (!done) {
                    if (notifyReceiver.receive(rptNotify, 2000)) {
                        String type = rptNotify.getString("RPT-N-TYPE").trim();
                        if ("E".equals(type)) {
                            done = true;
                        } else {
                            suppliers.add(rptNotify.getString("RPT-N-SUPP").trim());
                        }
                    } else {
                        done = true; // timeout = no more messages
                    }
                }
            })

            // ── WRITE-SUPPLIER-REPORTS ──────────────────────────────
            .paragraph("WRITE-SUPPLIER-REPORTS", ctx -> {
                for (String suppId : suppliers) {
                    // CALL SHIPQRY USING supplier-ID and result fields
                    queryResult.initialize();
                    Record qryParam = Record.define("QP")
                        .pic("QP-ID", "X(4)").build();
                    qryParam.move("QP-ID", suppId);

                    shipQuery.runWithLinkage(
                        qryParam.field("QP-ID"),
                        queryResult.field("WS-QR-SUPPLIER-NAME"),
                        queryResult.field("WS-QR-ITEM-COUNT"),
                        queryResult.field("WS-QR-TOTAL-QTY"),
                        queryResult.field("WS-QR-TOTAL-VALUE"),
                        queryResult.field("WS-QR-LATEST-DATE"));

                    // Accumulate grand totals
                    grandTotals.add("WS-GT-ITEMS",
                        queryResult.getDecimal("WS-QR-ITEM-COUNT"));
                    grandTotals.add("WS-GT-QTY",
                        queryResult.getDecimal("WS-QR-TOTAL-QTY"));
                    grandTotals.add("WS-GT-VALUE",
                        queryResult.getDecimal("WS-QR-TOTAL-VALUE"));

                    // Write per-supplier report file
                    String fileName = suppId + ".rpt";
                    Path filePath = tempDir.resolve(fileName);
                    CobolFile rptFile = CobolFile.sequential(suppId + "-RPT")
                        .assignTo(filePath.toString())
                        .recordSize(80)
                        .build();

                    rptFile.open(CobolFile.OpenMode.OUTPUT);
                    writeLine(rptFile, reportLine, "================================================================");
                    writeLine(rptFile, reportLine, String.format(
                        "SUPPLIER SHIPMENT REPORT                    DATE: %s", "2026-05-06"));
                    writeLine(rptFile, reportLine, String.format(
                        "SUPPLIER: %-4s %s", suppId,
                        queryResult.getString("WS-QR-SUPPLIER-NAME").trim()));
                    writeLine(rptFile, reportLine, String.format(
                        "LAST SHIPMENT: %s",
                        queryResult.getString("WS-QR-LATEST-DATE").trim()));
                    writeLine(rptFile, reportLine, "================================================================");
                    writeLine(rptFile, reportLine, String.format(
                        "ITEMS SHIPPED: %05d        TOTAL QTY:     %07d",
                        queryResult.getInt("WS-QR-ITEM-COUNT"),
                        queryResult.getInt("WS-QR-TOTAL-QTY")));
                    writeLine(rptFile, reportLine, String.format(
                        "TOTAL VALUE:   $%,12.2f",
                        queryResult.getDecimal("WS-QR-TOTAL-VALUE").toBigDecimal().doubleValue()));
                    writeLine(rptFile, reportLine, "================================================================");
                    rptFile.close();
                }
            })

            // ── WRITE-CONSOLIDATED-REPORT ──────────────────────────
            .paragraph("WRITE-CONSOLIDATED-REPORT", ctx -> {
                Path allPath = tempDir.resolve("ALL-SUPPLIERS.rpt");
                CobolFile allFile = CobolFile.sequential("ALL-RPT")
                    .assignTo(allPath.toString())
                    .recordSize(80)
                    .build();

                allFile.open(CobolFile.OpenMode.OUTPUT);
                writeLine(allFile, reportLine, "================================================================");
                writeLine(allFile, reportLine, String.format(
                    "CONSOLIDATED SHIPMENT REPORT                DATE: %s", "2026-05-06"));
                writeLine(allFile, reportLine, "================================================================");
                writeLine(allFile, reportLine,
                    "SUPPLIER  NAME                 ITEMS  TOTAL QTY  LAST SHIP   TOTAL VALUE");
                writeLine(allFile, reportLine,
                    "--------  -------------------  -----  ---------  ----------  -----------");

                for (String suppId : suppliers) {
                    // Re-query each supplier for the consolidated report
                    queryResult.initialize();
                    Record qp = Record.define("QP")
                        .pic("QP-ID", "X(4)").build();
                    qp.move("QP-ID", suppId);
                    shipQuery.runWithLinkage(
                        qp.field("QP-ID"),
                        queryResult.field("WS-QR-SUPPLIER-NAME"),
                        queryResult.field("WS-QR-ITEM-COUNT"),
                        queryResult.field("WS-QR-TOTAL-QTY"),
                        queryResult.field("WS-QR-TOTAL-VALUE"),
                        queryResult.field("WS-QR-LATEST-DATE"));

                    writeLine(allFile, reportLine, String.format(
                        "%-8s  %-19s  %05d    %07d  %-10s  $%,10.2f",
                        suppId,
                        queryResult.getString("WS-QR-SUPPLIER-NAME").trim(),
                        queryResult.getInt("WS-QR-ITEM-COUNT"),
                        queryResult.getInt("WS-QR-TOTAL-QTY"),
                        queryResult.getString("WS-QR-LATEST-DATE").trim(),
                        queryResult.getDecimal("WS-QR-TOTAL-VALUE")
                            .toBigDecimal().doubleValue()));
                }

                writeLine(allFile, reportLine,
                    "                               -----  ---------              -----------");
                writeLine(allFile, reportLine, String.format(
                    "GRAND TOTAL:                   %05d    %07d              $%,10.2f",
                    grandTotals.getInt("WS-GT-ITEMS"),
                    grandTotals.getInt("WS-GT-QTY"),
                    grandTotals.getDecimal("WS-GT-VALUE")
                        .toBigDecimal().doubleValue()));
                writeLine(allFile, reportLine, "================================================================");
                allFile.close();
            })
            .build();

        // ═══════════════════════════════════════════════════════════════
        //  EXECUTE: Feed shipments → notify → generate reports
        // ═══════════════════════════════════════════════════════════════

        // Load all shipments through SHIPINTK
        for (String[] data : SHIPMENTS) {
            shipmentRec.move("WS-SUPPLIER-ID",   data[0]);
            shipmentRec.move("WS-SUPPLIER-NAME", data[1]);
            shipmentRec.move("WS-ITEM-DESC",     data[2]);
            shipmentRec.move("WS-SHIP-QTY",      Decimal.of(data[3]));
            shipmentRec.move("WS-UNIT-PRICE",    Decimal.of(data[4]));
            shipmentRec.move("WS-SHIP-DATE",     data[5]);
            intakeStatus.moveSpaces("WS-STATUS-CODE");
            intakeStatus.moveSpaces("WS-STATUS-MSG");

            shipIntake.runWithLinkage(
                shipmentRec.field("WS-SUPPLIER-ID"),
                shipmentRec.field("WS-SUPPLIER-NAME"),
                shipmentRec.field("WS-ITEM-DESC"),
                shipmentRec.field("WS-SHIP-QTY"),
                shipmentRec.field("WS-UNIT-PRICE"),
                shipmentRec.field("WS-SHIP-DATE"),
                intakeStatus.field("WS-STATUS-CODE"),
                intakeStatus.field("WS-STATUS-MSG"));

            assertTrue(intakeStatus.is("INTAKE-OK"),
                "Shipment " + data[0] + "/" + data[2] + " failed: "
                + intakeStatus.getString("WS-STATUS-MSG").trim());
        }

        // Send end-of-notifications signal
        notification.move("WS-NOTIFY-TYPE", "E");
        notification.move("WS-NOTIFY-SUPPLIER", "    ");
        notifySender.send(notification);

        // Run the report writer
        rptwrt.run();

        // ═══════════════════════════════════════════════════════════════
        //  VALIDATE: Database, reports, computed totals
        // ═══════════════════════════════════════════════════════════════

        // ── Database row count ─────────────────────────────────────
        Record countRec = Record.define("CNT").pic("CNT", "9(5)").build();
        SqlSession.work(dbFactory, session -> {
            session.sql().select("SELECT COUNT(*) FROM SHIPMENTS")
                .into(countRec, "CNT").execute();
            assertTrue(session.isSuccess());
        });
        assertEquals(13, countRec.getInt("CNT"), "Should have 13 shipment rows");

        // ── Per-supplier report files exist ─────────────────────────
        assertTrue(Files.exists(tempDir.resolve("SA.rpt")), "SA.rpt missing");
        assertTrue(Files.exists(tempDir.resolve("SB.rpt")), "SB.rpt missing");
        assertTrue(Files.exists(tempDir.resolve("SC.rpt")), "SC.rpt missing");
        assertTrue(Files.exists(tempDir.resolve("SD.rpt")), "SD.rpt missing");
        assertTrue(Files.exists(tempDir.resolve("ALL-SUPPLIERS.rpt")), "ALL-SUPPLIERS.rpt missing");

        // ── Per-supplier report contents ────────────────────────────
        assertReportContains("SA.rpt", tempDir, "ACME WIDGETS");
        assertReportContains("SA.rpt", tempDir, "00003");       // 3 items
        assertReportContains("SA.rpt", tempDir, "0000350");     // qty 350
        assertReportContains("SA.rpt", tempDir, "2026-04-05");  // latest date
        assertReportContains("SA.rpt", tempDir, "3,087.50");    // total value

        assertReportContains("SB.rpt", tempDir, "BAKER SUPPLY");
        assertReportContains("SB.rpt", tempDir, "00004");       // 4 items
        assertReportContains("SB.rpt", tempDir, "2026-04-08");  // latest date

        assertReportContains("SD.rpt", tempDir, "DELTA MATERIALS");
        assertReportContains("SD.rpt", tempDir, "2026-04-09");  // latest date

        // ── Consolidated report ────────────────────────────────────
        assertReportContains("ALL-SUPPLIERS.rpt", tempDir, "CONSOLIDATED");
        assertReportContains("ALL-SUPPLIERS.rpt", tempDir, "SA");
        assertReportContains("ALL-SUPPLIERS.rpt", tempDir, "SB");
        assertReportContains("ALL-SUPPLIERS.rpt", tempDir, "SC");
        assertReportContains("ALL-SUPPLIERS.rpt", tempDir, "SD");
        assertReportContains("ALL-SUPPLIERS.rpt", tempDir, "18,592.50");  // grand total

        // ── Grand totals from working storage ──────────────────────
        assertEquals(13, grandTotals.getInt("WS-GT-ITEMS"));
        assertEquals(1525, grandTotals.getInt("WS-GT-QTY"));
        assertTrue(grandTotals.getDecimal("WS-GT-VALUE")
            .equalTo(Decimal.of("18592.50")),
            "Grand total value should be 18592.50, got: "
            + grandTotals.getDecimal("WS-GT-VALUE"));

        // ── Validation rejection test ──────────────────────────────
        shipmentRec.moveSpaces("WS-SUPPLIER-ID"); // blank = invalid
        shipmentRec.move("WS-SHIP-QTY", Decimal.of("100"));
        shipmentRec.move("WS-UNIT-PRICE", Decimal.of("10.00"));
        shipmentRec.move("WS-SHIP-DATE", "2026-05-06");
        intakeStatus.moveSpaces("WS-STATUS-CODE");

        shipIntake.runWithLinkage(
            shipmentRec.field("WS-SUPPLIER-ID"),
            shipmentRec.field("WS-SUPPLIER-NAME"),
            shipmentRec.field("WS-ITEM-DESC"),
            shipmentRec.field("WS-SHIP-QTY"),
            shipmentRec.field("WS-UNIT-PRICE"),
            shipmentRec.field("WS-SHIP-DATE"),
            intakeStatus.field("WS-STATUS-CODE"),
            intakeStatus.field("WS-STATUS-MSG"));

        assertTrue(intakeStatus.is("INTAKE-INVALID"),
            "Blank supplier should be rejected, got: "
            + intakeStatus.getString("WS-STATUS-CODE").trim());
        assertTrue(intakeStatus.getString("WS-STATUS-MSG").trim()
            .contains("SUPPLIER ID"),
            "Should mention supplier ID in error: "
            + intakeStatus.getString("WS-STATUS-MSG").trim());

        // Verify the invalid record was NOT inserted
        SqlSession.work(dbFactory, session -> {
            session.sql().select("SELECT COUNT(*) FROM SHIPMENTS")
                .into(countRec, "CNT").execute();
        });
        assertEquals(13, countRec.getInt("CNT"),
            "Invalid record should not be in database");

        // Cleanup
        notifySender.close();
        notifyReceiver.close();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /** Write a line to a sequential report file via a Record buffer. */
    private static void writeLine(CobolFile file, Record lineRec, String text) {
        // Pad or truncate to 80 bytes (COBOL fixed-width record)
        String padded = String.format("%-80s", text);
        if (padded.length() > 80) padded = padded.substring(0, 80);
        lineRec.move("WS-RPT-DATA", padded);
        file.write(lineRec);
    }

    /** Assert that a report file contains a specific string. */
    private static void assertReportContains(String fileName, Path dir, String expected) {
        try {
            String content = Files.readString(dir.resolve(fileName));
            assertTrue(content.contains(expected),
                fileName + " should contain '" + expected + "' but got:\n" + content);
        } catch (Exception e) {
            fail(fileName + " could not be read: " + e.getMessage());
        }
    }
}
