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

import org.cobol4j.Decimal;
import org.cobol4j.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CicsRegionTest {

    private CicsRegion region;
    private Record commarea;

    @BeforeEach
    void setup() {
        commarea = Record.define("COMMAREA")
            .pic("REQUEST", "X(10)")
            .pic("CUST-ID", "X(10)")
            .pic("CUST-NAME", "X(20)")
            .pic("AMOUNT", "S9(7)V99")
            .pic("STATUS", "X(2)")
            .build();

        region = CicsRegion.create("TESTRGN").start();
    }

    // ═══════════════════════════════════════════════════════════════
    //  BASIC DISPATCH
    // ═══════════════════════════════════════════════════════════════

    @Test
    void dispatchSimpleTransaction() {
        region.install("ECHO", ctx -> {
            ctx.receive(commarea);
            commarea.move("STATUS", "OK");
            ctx.send(commarea);
            ctx.returnTransaction();
        });
        region.transaction("ECHO", "ECHO");

        commarea.move("REQUEST", "PING");
        region.dispatch("ECHO", commarea);

        assertEquals("OK", commarea.getString("STATUS").trim());
    }

    @Test
    void dispatchRoutesToCorrectProgram() {
        List<String> log = new ArrayList<>();

        region.install("PROG-A", ctx -> { log.add("A"); ctx.returnTransaction(); });
        region.install("PROG-B", ctx -> { log.add("B"); ctx.returnTransaction(); });
        region.transaction("TXNA", "PROG-A");
        region.transaction("TXNB", "PROG-B");

        region.dispatch("TXNA", new byte[0]);
        region.dispatch("TXNB", new byte[0]);
        region.dispatch("TXNA", new byte[0]);

        assertEquals(List.of("A", "B", "A"), log);
    }

    @Test
    void unknownTransactionThrows() {
        assertThrows(CicsCondition.class, () ->
            region.dispatch("XXXX", new byte[0]));
    }

    @Test
    void unknownProgramThrows() {
        region.transaction("BAD", "NONEXISTENT");
        assertThrows(CicsCondition.class, () ->
            region.dispatch("BAD", new byte[0]));
    }

    // ═══════════════════════════════════════════════════════════════
    //  COMMAREA PASSING (request/response)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void commareaModifiedByProgram() {
        region.install("CALC", ctx -> {
            Record ca = Record.define("CA")
                .pic("A", "S9(5)V99")
                .pic("B", "S9(5)V99")
                .pic("RESULT", "S9(7)V99")
                .build();
            ctx.receive(ca);
            Decimal sum = ca.getDecimal("A").add(ca.getDecimal("B"));
            ca.move("RESULT", sum);
            ctx.send(ca);
            ctx.returnTransaction();
        });
        region.transaction("CALC", "CALC");

        Record input = Record.define("CA")
            .pic("A", "S9(5)V99")
            .pic("B", "S9(5)V99")
            .pic("RESULT", "S9(7)V99")
            .build();
        input.move("A", Decimal.of("100.00"));
        input.move("B", Decimal.of("200.50"));

        region.dispatch("CALC", input);
        assertTrue(input.getDecimal("RESULT").equalTo(Decimal.of("300.50")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  LINK (subroutine call within same task)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void linkCallsSubProgramAndReturns() {
        Record subCommarea = Record.define("SUB-CA")
            .pic("VALUE", "S9(5)")
            .build();

        region.install("DOUBLE", ctx -> {
            ctx.receive(subCommarea);
            Decimal val = subCommarea.getDecimal("VALUE");
            subCommarea.move("VALUE", val.multiply(Decimal.of("2")));
            ctx.send(subCommarea);
            ctx.returnTransaction();
        });

        region.install("MAIN", ctx -> {
            subCommarea.move("VALUE", Decimal.of("21"));
            ctx.link("DOUBLE", subCommarea);
            // After link, subCommarea has the result
            commarea.move("AMOUNT", subCommarea.getDecimal("VALUE"));
            ctx.send(commarea);
            ctx.returnTransaction();
        });
        region.transaction("MAIN", "MAIN");

        region.dispatch("MAIN", commarea);
        assertTrue(commarea.getDecimal("AMOUNT").equalTo(Decimal.of("42")));
    }

    // ═══════════════════════════════════════════════════════════════
    //  XCTL (transfer control — no return)
    // ═══════════════════════════════════════════════════════════════

    @Test
    void xctlTransfersToAnotherProgram() {
        List<String> log = new ArrayList<>();

        region.install("FIRST", ctx -> {
            log.add("FIRST-START");
            ctx.xctl("SECOND");
            log.add("FIRST-AFTER-XCTL"); // should NOT execute
        });

        region.install("SECOND", ctx -> {
            log.add("SECOND");
            ctx.returnTransaction();
        });

        region.transaction("GO", "FIRST");
        region.dispatch("GO", new byte[0]);

        assertEquals(List.of("FIRST-START", "SECOND"), log);
    }

    // ═══════════════════════════════════════════════════════════════
    //  TEMPORARY STORAGE
    // ═══════════════════════════════════════════════════════════════

    @Test
    void temporaryStorageWriteAndRead() {
        Record tsRec = Record.define("TS")
            .pic("DATA", "X(20)")
            .build();

        region.install("TS-TEST", ctx -> {
            tsRec.move("DATA", "ITEM-ONE");
            ctx.writeTS("MYQUEUE", tsRec);
            tsRec.move("DATA", "ITEM-TWO");
            ctx.writeTS("MYQUEUE", tsRec);

            // Read back
            assertTrue(ctx.readTS("MYQUEUE", tsRec, 1));
            assertEquals("ITEM-ONE", tsRec.getString("DATA").trim());
            assertTrue(ctx.readTS("MYQUEUE", tsRec, 2));
            assertEquals("ITEM-TWO", tsRec.getString("DATA").trim());

            assertEquals(2, ctx.tsQueueDepth("MYQUEUE"));
            ctx.returnTransaction();
        });
        region.transaction("TST", "TS-TEST");

        region.dispatch("TST", new byte[0]);
    }

    @Test
    void temporaryStorageSequentialRead() {
        Record tsRec = Record.define("TS").pic("V", "X(10)").build();

        region.install("TS-SEQ", ctx -> {
            tsRec.move("V", "FIRST");  ctx.writeTS("Q", tsRec);
            tsRec.move("V", "SECOND"); ctx.writeTS("Q", tsRec);
            tsRec.move("V", "THIRD");  ctx.writeTS("Q", tsRec);

            assertTrue(ctx.readTSNext("Q", tsRec));
            assertEquals("FIRST", tsRec.getString("V").trim());
            assertTrue(ctx.readTSNext("Q", tsRec));
            assertEquals("SECOND", tsRec.getString("V").trim());
            assertTrue(ctx.readTSNext("Q", tsRec));
            assertEquals("THIRD", tsRec.getString("V").trim());
            assertFalse(ctx.readTSNext("Q", tsRec)); // end

            ctx.returnTransaction();
        });
        region.transaction("SEQ", "TS-SEQ");
        region.dispatch("SEQ", new byte[0]);
    }

    @Test
    void temporaryStoragePersistsAcrossTransactions() {
        Record tsRec = Record.define("TS").pic("V", "X(10)").build();

        region.install("WRITER", ctx -> {
            tsRec.move("V", "SHARED");
            ctx.writeTS("SHARED-Q", tsRec);
            ctx.returnTransaction();
        });

        region.install("READER", ctx -> {
            assertTrue(ctx.readTS("SHARED-Q", tsRec, 1));
            assertEquals("SHARED", tsRec.getString("V").trim());
            ctx.returnTransaction();
        });

        region.transaction("WRT", "WRITER");
        region.transaction("RDR", "READER");

        region.dispatch("WRT", new byte[0]);
        region.dispatch("RDR", new byte[0]);
        // TS queue persists across tasks within the same region
    }

    // ═══════════════════════════════════════════════════════════════
    //  HOT DEPLOY
    // ═══════════════════════════════════════════════════════════════

    @Test
    void hotDeployReplacesProgram() {
        List<String> log = new ArrayList<>();

        region.install("HOT", ctx -> { log.add("V1"); ctx.returnTransaction(); });
        region.transaction("HOT", "HOT");

        region.dispatch("HOT", new byte[0]);

        // Replace while running
        region.install("HOT", ctx -> { log.add("V2"); ctx.returnTransaction(); });

        region.dispatch("HOT", new byte[0]);

        assertEquals(List.of("V1", "V2"), log);
    }

    // ═══════════════════════════════════════════════════════════════
    //  TASK INFO
    // ═══════════════════════════════════════════════════════════════

    @Test
    void taskInfoAvailable() {
        region.install("INFO", ctx -> {
            assertEquals("TINF", ctx.transactionId());
            assertEquals("INFO", ctx.programName());
            assertTrue(ctx.taskNumber() > 0);
            ctx.returnTransaction();
        });
        region.transaction("TINF", "INFO");
        region.dispatch("TINF", new byte[0]);
    }

    @Test
    void taskNumbersIncrement() {
        long[] tasks = new long[3];
        int[] idx = {0};

        region.install("COUNTER", ctx -> {
            tasks[idx[0]++] = ctx.taskNumber();
            ctx.returnTransaction();
        });
        region.transaction("CNT", "COUNTER");

        region.dispatch("CNT", new byte[0]);
        region.dispatch("CNT", new byte[0]);
        region.dispatch("CNT", new byte[0]);

        assertTrue(tasks[1] > tasks[0]);
        assertTrue(tasks[2] > tasks[1]);
    }
}
