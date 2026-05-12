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

import java.io.*;
import java.util.Arrays;

/**
 * Base class for COBOL record I/O.
 *
 * <p>A COBOL record is a fixed-length byte buffer whose layout is governed by
 * PIC clauses. This class owns the buffer and provides stream-based I/O that
 * preserves the exact binary format — COMP-3 packed decimals, COMP binary
 * integers, and DISPLAY alphanumeric data are read and written as raw bytes.</p>
 *
 * <p>Implements {@link Serializable} with custom serialization: the raw record
 * bytes are written directly to the stream. This means Records can be passed
 * through any Java I/O mechanism — files, sockets, RMI, queues — and the
 * receiving side gets the exact COBOL byte layout.</p>
 *
 * <h3>Stream I/O (for COBOL file operations)</h3>
 * <pre>{@code
 * // Write a record to a file
 * try (OutputStream out = Files.newOutputStream(path)) {
 *     record.writeTo(out);
 * }
 *
 * // Read records from a file
 * try (InputStream in = Files.newInputStream(path)) {
 *     while (record.readFrom(in)) {
 *         // process record
 *     }
 * }
 * }</pre>
 *
 * <h3>Java Serialization (for inter-process communication)</h3>
 * <pre>{@code
 * // Send via ObjectOutputStream (RMI, sockets, etc.)
 * objectOut.writeObject(record);
 *
 * // Receive
 * Record rec = (Record) objectIn.readObject();
 * }</pre>
 */
public abstract class BaseRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The raw byte buffer — the record's data in COBOL binary format. */
    protected byte[] data;

    /** Total record size in bytes, determined by the PIC clause layout. */
    protected final int recordSize;

    protected BaseRecord(int recordSize, byte[] data) {
        this.recordSize = recordSize;
        this.data = data;
    }

    // ── Size ───────────────────────────────────────────────────────

    /** The fixed byte length of this record layout. */
    public int size() { return recordSize; }

    // ── Stream I/O ─────────────────────────────────────────────────

    /**
     * Write this record's raw bytes to a stream.
     * Writes exactly {@link #size()} bytes — the complete fixed-length record.
     */
    public void writeTo(OutputStream out) throws IOException {
        out.write(data, 0, recordSize);
    }

    /**
     * Read this record's bytes from a stream.
     * Reads exactly {@link #size()} bytes into the buffer.
     *
     * @return true if a complete record was read; false on clean EOF
     *         (no bytes available). Partial reads at EOF are padded with spaces.
     */
    public boolean readFrom(InputStream in) throws IOException {
        int total = 0;
        while (total < recordSize) {
            int n = in.read(data, total, recordSize - total);
            if (n < 0) {
                if (total == 0) return false; // clean EOF — no record
                // Partial record at EOF — pad remainder with spaces
                Arrays.fill(data, total, recordSize, spaceByte());
                return true;
            }
            total += n;
        }
        return true;
    }

    // ── Protected access for subclass ──────────────────────────────

    /** Direct access to the backing buffer. */
    protected byte[] rawData() { return data; }

    /** The space byte for padding — 0x20 for ASCII, encoding-dependent for EBCDIC. */
    protected abstract byte spaceByte();

    // ── Serializable ───────────────────────────────────────────────
    //
    // Custom serialization writes the record size and raw bytes directly.
    // No object headers, no field descriptors for the data buffer —
    // just the bytes that a COBOL program would produce.

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject(); // writes recordSize and any subclass fields
        out.write(data, 0, recordSize);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject(); // reads recordSize and any subclass fields
        if (data == null) {
            data = new byte[recordSize];
        }
        in.readFully(data, 0, recordSize);
    }
}
