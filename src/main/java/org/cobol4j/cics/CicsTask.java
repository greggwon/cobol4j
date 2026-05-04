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
import org.cobol4j.Record;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A running CICS task — implements {@link CicsContext} for one transaction execution.
 * Created by the region for each dispatched transaction.
 */
final class CicsTask implements CicsContext {

    private final CicsRegion region;
    private final String transactionId;
    private final String programName;
    private final long taskNumber;
    private byte[] commarea;
    private int lastResponse;
    private final Map<String, Integer> tsReadPositions = new HashMap<>();

    CicsTask(CicsRegion region, String transactionId, String programName,
             long taskNumber, byte[] commarea) {
        this.region = region;
        this.transactionId = transactionId;
        this.programName = programName;
        this.taskNumber = taskNumber;
        this.commarea = commarea != null ? commarea.clone() : new byte[0];
        this.lastResponse = 0; // NORMAL
    }

    byte[] commareaBytes() { return commarea; }

    // ── Communication ───────────────────────────────────────────────

    @Override
    public void receive(Record record) {
        record.loadFrom(commarea);
        lastResponse = 0;
    }

    @Override
    public void send(Record record) {
        commarea = record.buffer();
        lastResponse = 0;
    }

    @Override
    public byte[] commarea() {
        return commarea.clone();
    }

    @Override
    public void returnTransaction() {
        throw new CicsRegion.ReturnException(null);
    }

    @Override
    public void returnTransaction(String nextTransactionId) {
        throw new CicsRegion.ReturnException(nextTransactionId);
    }

    // ── File I/O ────────────────────────────────────────────────────

    @Override
    public boolean readFile(String fileName, Record record, String key) {
        CobolFile file = region.getFile(fileName);
        boolean found = file.read(record.buffer(), fileName, key);
        lastResponse = found ? 0 : 13; // 13 = NOTFND
        return found;
    }

    @Override
    public void writeFile(String fileName, Record record) {
        CobolFile file = region.getFile(fileName);
        file.write(record.buffer());
        lastResponse = 0;
    }

    @Override
    public void rewriteFile(String fileName, Record record) {
        CobolFile file = region.getFile(fileName);
        file.rewrite(record.buffer());
        lastResponse = 0;
    }

    @Override
    public void deleteFile(String fileName, String key) {
        CobolFile file = region.getFile(fileName);
        // Read to position, then delete
        file.read(new byte[0], fileName, key);
        file.delete();
        lastResponse = 0;
    }

    // ── Temporary Storage ───────────────────────────────────────────

    @Override
    public int writeTS(String queueName, Record record) {
        int item = region.writeTS(queueName, record.buffer());
        lastResponse = 0;
        return item;
    }

    @Override
    public boolean readTS(String queueName, Record record, int item) {
        byte[] data = region.readTS(queueName, item);
        if (data == null) {
            lastResponse = 44; // ITEMERR
            return false;
        }
        record.loadFrom(data);
        lastResponse = 0;
        return true;
    }

    @Override
    public boolean readTSNext(String queueName, Record record) {
        int nextItem = tsReadPositions.getOrDefault(queueName, 0) + 1;
        boolean ok = readTS(queueName, record, nextItem);
        if (ok) {
            tsReadPositions.put(queueName, nextItem);
        }
        return ok;
    }

    @Override
    public void deleteTS(String queueName) {
        region.deleteTS(queueName);
        tsReadPositions.remove(queueName);
        lastResponse = 0;
    }

    @Override
    public int tsQueueDepth(String queueName) {
        return region.tsQueueDepth(queueName);
    }

    // ── Program Control ─────────────────────────────────────────────

    @Override
    public void link(String programName, Record commarea) {
        byte[] result = region.executeProgram(programName, transactionId,
            commarea != null ? commarea.buffer() : null);
        if (commarea != null && result != null) {
            commarea.loadFrom(result);
        }
        lastResponse = 0;
    }

    @Override
    public void link(String programName) {
        link(programName, null);
    }

    @Override
    public void xctl(String programName, Record commarea) {
        if (commarea != null) {
            this.commarea = commarea.buffer();
        }
        throw new CicsRegion.XctlException(programName);
    }

    @Override
    public void xctl(String programName) {
        throw new CicsRegion.XctlException(programName);
    }

    // ── Transaction Control ─────────────────────────────────────────

    @Override
    public void syncpoint() {
        // In a full implementation, this would commit file/DB changes.
        // In the lightweight version, it's a checkpoint marker.
        lastResponse = 0;
    }

    @Override
    public void rollback() {
        // In a full implementation, this would undo file/DB changes.
        lastResponse = 0;
    }

    // ── Task Info ───────────────────────────────────────────────────

    @Override
    public String transactionId() { return transactionId; }

    @Override
    public long taskNumber() { return taskNumber; }

    @Override
    public String programName() { return programName; }

    // ── Response ────────────────────────────────────────────────────

    @Override
    public int response() { return lastResponse; }

    @Override
    public boolean isNormal() { return lastResponse == 0; }

    @Override
    public boolean isNotFound() { return lastResponse == 13; }
}
