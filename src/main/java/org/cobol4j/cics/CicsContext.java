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

/**
 * The CICS execution context — what a program sees during a transaction.
 * <p>
 * Analogous to HttpServletRequest + HttpServletResponse + transaction manager
 * combined into a single handle. Every EXEC CICS command in COBOL maps to
 * a method on this interface.
 * <p>
 * Programs never manage resources directly — they ask the context, and the
 * context delegates to the region's managed resource pools.
 *
 * <pre>{@code
 * CicsProgram inquiry = (ctx) -> {
 *     ctx.receive(commarea);
 *     String custId = commarea.getString("CUST-ID").trim();
 *
 *     ctx.readFile("CUSTFILE", custRec, custId);
 *     commarea.move("CUST-NAME", custRec.getString("NAME"));
 *     commarea.move("CUST-BAL", custRec.getDecimal("BALANCE"));
 *
 *     ctx.send(commarea);
 *     ctx.returnTransaction();
 * };
 * }</pre>
 */
public interface CicsContext {

    // ═══════════════════════════════════════════════════════════════
    //  COMMUNICATION — send/receive the COMMAREA (request/response)
    // ═══════════════════════════════════════════════════════════════

    /**
     * EXEC CICS RECEIVE — read the incoming COMMAREA into a Record.
     * In a conversational transaction, this waits for terminal input.
     */
    void receive(Record commarea);

    /**
     * EXEC CICS SEND — write the COMMAREA back to the caller.
     */
    void send(Record commarea);

    /**
     * Get the raw COMMAREA bytes that initiated this transaction.
     */
    byte[] commarea();

    /**
     * EXEC CICS RETURN — end this program's execution.
     * The COMMAREA is returned to the caller.
     */
    void returnTransaction();

    /**
     * EXEC CICS RETURN TRANSID(xxxx) — end this program but schedule
     * another transaction to run next (pseudo-conversational pattern).
     */
    void returnTransaction(String nextTransactionId);

    // ═══════════════════════════════════════════════════════════════
    //  FILE I/O — through the region's managed file pool
    // ═══════════════════════════════════════════════════════════════

    /**
     * EXEC CICS READ FILE(name) INTO(record) RIDFLD(key).
     * Reads a record from a region-managed file by key.
     *
     * @return true if found, false if NOTFND condition
     */
    boolean readFile(String fileName, Record record, String key);

    /**
     * EXEC CICS WRITE FILE(name) FROM(record).
     */
    void writeFile(String fileName, Record record);

    /**
     * EXEC CICS REWRITE FILE(name) FROM(record).
     * Updates the last-read record.
     */
    void rewriteFile(String fileName, Record record);

    /**
     * EXEC CICS DELETE FILE(name) RIDFLD(key).
     */
    void deleteFile(String fileName, String key);

    // ═══════════════════════════════════════════════════════════════
    //  TEMPORARY STORAGE — named queues for session/scratch data
    // ═══════════════════════════════════════════════════════════════

    /**
     * EXEC CICS WRITEQ TS QUEUE(name) FROM(record).
     * Appends a record to a TS queue. Returns the item number.
     */
    int writeTS(String queueName, Record record);

    /**
     * EXEC CICS READQ TS QUEUE(name) INTO(record) ITEM(n).
     * Reads item N from a TS queue (1-based).
     *
     * @return true if the item exists
     */
    boolean readTS(String queueName, Record record, int item);

    /**
     * EXEC CICS READQ TS QUEUE(name) INTO(record) NEXT.
     * Reads the next sequential item.
     *
     * @return true if there was a next item
     */
    boolean readTSNext(String queueName, Record record);

    /**
     * EXEC CICS DELETEQ TS QUEUE(name).
     * Deletes an entire TS queue.
     */
    void deleteTS(String queueName);

    /**
     * Get the number of items in a TS queue.
     */
    int tsQueueDepth(String queueName);

    // ═══════════════════════════════════════════════════════════════
    //  PROGRAM CONTROL — LINK, XCTL, LOAD
    // ═══════════════════════════════════════════════════════════════

    /**
     * EXEC CICS LINK PROGRAM(name) COMMAREA(data).
     * Calls a sub-program with a COMMAREA. Control returns here when
     * the linked program finishes. Like a subroutine call.
     */
    void link(String programName, Record commarea);

    /**
     * EXEC CICS LINK PROGRAM(name).
     * Link without a COMMAREA.
     */
    void link(String programName);

    /**
     * EXEC CICS XCTL PROGRAM(name) COMMAREA(data).
     * Transfer control to another program — this program does NOT get
     * control back. Like a goto to another handler.
     */
    void xctl(String programName, Record commarea);

    /**
     * EXEC CICS XCTL PROGRAM(name).
     * Transfer without COMMAREA.
     */
    void xctl(String programName);

    // ═══════════════════════════════════════════════════════════════
    //  TRANSACTION CONTROL
    // ═══════════════════════════════════════════════════════════════

    /**
     * EXEC CICS SYNCPOINT — commit all recoverable resources
     * (files, DB, queues) as a unit of work.
     */
    void syncpoint();

    /**
     * EXEC CICS SYNCPOINT ROLLBACK — roll back all changes
     * since the last syncpoint.
     */
    void rollback();

    // ═══════════════════════════════════════════════════════════════
    //  TASK INFO
    // ═══════════════════════════════════════════════════════════════

    /** The transaction ID that started this task (4 characters). */
    String transactionId();

    /** The task number (unique per CICS region lifetime). */
    long taskNumber();

    /** The program name currently executing. */
    String programName();

    // ═══════════════════════════════════════════════════════════════
    //  CONDITION HANDLING
    // ═══════════════════════════════════════════════════════════════

    /**
     * EIBRESP — the response code from the last CICS command.
     * 0 = NORMAL, non-zero = condition occurred.
     */
    int response();

    /** Was the last command successful? */
    boolean isNormal();

    /** Did the last READ get NOTFND? */
    boolean isNotFound();
}
