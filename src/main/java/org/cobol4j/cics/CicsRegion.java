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

import org.cobol4j.CobolFile;
import org.cobol4j.ConnectionFactory;
import org.cobol4j.Record;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A CICS region — the running container that hosts programs, manages resources,
 * and dispatches transactions.
 * <p>
 * Analogous to a servlet container: programs are installed (deployed), transactions
 * are routed to programs (URL mapping), and resources are shared (connection pooling).
 * <p>
 * <b>Lifecycle:</b>
 * <pre>{@code
 * CicsRegion region = CicsRegion.create("CICSPROD")
 *     .install("CUSTINQ", customerInquiryProgram)
 *     .install("CUSTUPD", customerUpdateProgram)
 *     .install("ORDRENT", orderEntryProgram)
 *     .transaction("CINQ", "CUSTINQ")
 *     .transaction("CUPD", "CUSTUPD")
 *     .transaction("OENT", "ORDRENT")
 *     .file("CUSTFILE", custFileInstance)
 *     .database("DB01", connectionFactory)
 *     .start();
 *
 * // Dispatch a transaction (like receiving an HTTP request)
 * byte[] response = region.dispatch("CINQ", requestCommarea);
 *
 * // Hot deploy — install/replace while running
 * region.install("CUSTINQ", newVersionOfProgram);
 *
 * // Shutdown
 * region.stop();
 * }</pre>
 */
public final class CicsRegion {

    private final String regionId;
    private final Map<String, CicsProgram> programs = new ConcurrentHashMap<>();
    private final Map<String, String> transactionRoutes = new ConcurrentHashMap<>();
    private final Map<String, CobolFile> files = new ConcurrentHashMap<>();
    private final Map<String, ConnectionFactory> databases = new ConcurrentHashMap<>();
    private final Map<String, List<byte[]>> tsQueues = new ConcurrentHashMap<>();
    private final AtomicLong taskCounter = new AtomicLong(1);
    private volatile boolean running;

    private CicsRegion(String regionId) {
        this.regionId = regionId;
    }

    /** Create a new CICS region with the given ID. */
    public static CicsRegion create(String regionId) {
        return new CicsRegion(regionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PROGRAM MANAGEMENT — install, replace, remove (hot deploy)
    // ═══════════════════════════════════════════════════════════════

    /** Install or replace a program. Can be done while running. */
    public CicsRegion install(String programName, CicsProgram program) {
        programs.put(programName, program);
        return this;
    }

    /** Remove an installed program. */
    public CicsRegion remove(String programName) {
        programs.remove(programName);
        return this;
    }

    /** Is a program installed? */
    public boolean isInstalled(String programName) {
        return programs.containsKey(programName);
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRANSACTION ROUTING — map transaction IDs to programs
    // ═══════════════════════════════════════════════════════════════

    /** Define a transaction → program mapping. */
    public CicsRegion transaction(String transactionId, String programName) {
        transactionRoutes.put(transactionId, programName);
        return this;
    }

    /** Remove a transaction route. */
    public CicsRegion removeTransaction(String transactionId) {
        transactionRoutes.remove(transactionId);
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  RESOURCE MANAGEMENT — shared files, databases
    // ═══════════════════════════════════════════════════════════════

    /** Register a managed file resource. */
    public CicsRegion file(String fileName, CobolFile file) {
        files.put(fileName, file);
        return this;
    }

    /** Register a managed database connection factory. */
    public CicsRegion database(String dbName, ConnectionFactory factory) {
        databases.put(dbName, factory);
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════

    /** Start the region. */
    public CicsRegion start() {
        running = true;
        return this;
    }

    /** Stop the region. */
    public void stop() {
        running = false;
        // Clean up TS queues
        tsQueues.clear();
    }

    /** Is the region running? */
    public boolean isRunning() { return running; }

    /** Region identifier. */
    public String regionId() { return regionId; }

    // ═══════════════════════════════════════════════════════════════
    //  TRANSACTION DISPATCH — the core operation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Dispatch a transaction — find the program, create a task, run it.
     * Returns the COMMAREA after the program completes (the response).
     *
     * @param transactionId the 4-char transaction ID
     * @param commarea      the input COMMAREA bytes (the request)
     * @return the output COMMAREA bytes (the response)
     */
    public byte[] dispatch(String transactionId, byte[] commarea) {
        if (!running) {
            throw new IllegalStateException("Region " + regionId + " is not running");
        }

        String programName = transactionRoutes.get(transactionId);
        if (programName == null) {
            throw new CicsCondition("TRANSIDERR",
                "No program mapped to transaction '" + transactionId + "'");
        }

        return executeProgram(programName, transactionId, commarea);
    }

    /**
     * Dispatch a transaction using a Record as the COMMAREA.
     * The Record is both input and output — modified in place.
     */
    public void dispatch(String transactionId, Record commarea) {
        byte[] result = dispatch(transactionId, commarea.buffer());
        commarea.loadFrom(result);
    }

    /**
     * Run a program directly by name (used by LINK).
     */
    byte[] executeProgram(String programName, String transactionId, byte[] commarea) {
        CicsProgram program = programs.get(programName);
        if (program == null) {
            throw new CicsCondition("PGMIDERR",
                "Program '" + programName + "' not installed");
        }

        long taskNum = taskCounter.getAndIncrement();
        CicsTask task = new CicsTask(this, transactionId, programName, taskNum, commarea);

        try {
            program.execute(task);
        } catch (ReturnException e) {
            // Normal termination via ctx.returnTransaction()
        } catch (XctlException e) {
            // XCTL — transfer to another program
            return executeProgram(e.programName, transactionId, task.commareaBytes());
        }

        return task.commareaBytes();
    }

    // ═══════════════════════════════════════════════════════════════
    //  RESOURCE ACCESS (package-private — used by CicsTask)
    // ═══════════════════════════════════════════════════════════════

    CobolFile getFile(String name) {
        CobolFile file = files.get(name);
        if (file == null) {
            throw new CicsCondition("FILENOTFOUND", "File '" + name + "' not defined");
        }
        return file;
    }

    ConnectionFactory getDatabase(String name) {
        ConnectionFactory factory = databases.get(name);
        if (factory == null) {
            throw new CicsCondition("DBNOTFOUND", "Database '" + name + "' not defined");
        }
        return factory;
    }

    // ── Temporary Storage ───────────────────────────────────────────

    synchronized int writeTS(String queueName, byte[] data) {
        List<byte[]> queue = tsQueues.computeIfAbsent(queueName, k -> new ArrayList<>());
        queue.add(data.clone());
        return queue.size(); // 1-based item number
    }

    synchronized byte[] readTS(String queueName, int item) {
        List<byte[]> queue = tsQueues.get(queueName);
        if (queue == null || item < 1 || item > queue.size()) return null;
        return queue.get(item - 1).clone();
    }

    synchronized void deleteTS(String queueName) {
        tsQueues.remove(queueName);
    }

    synchronized int tsQueueDepth(String queueName) {
        List<byte[]> queue = tsQueues.get(queueName);
        return queue == null ? 0 : queue.size();
    }

    // ── Control flow exceptions (package-private) ───────────────────

    static final class ReturnException extends RuntimeException {
        final String nextTransId;
        ReturnException(String nextTransId) {
            super(null, null, true, false);
            this.nextTransId = nextTransId;
        }
    }

    static final class XctlException extends RuntimeException {
        final String programName;
        XctlException(String programName) {
            super(null, null, true, false);
            this.programName = programName;
        }
    }
}
