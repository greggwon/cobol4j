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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Abstraction over COBOL file I/O operations.
 * <p>
 * This interface defines the contract for all COBOL file organizations (sequential,
 * indexed, relative). Implementations provide the actual storage backend — flat files,
 * databases, in-memory stores, or cloud storage.
 * <p>
 * The interface is designed for lambda-based extensibility:
 * <pre>{@code
 * CobolFile file = CobolFile.sequential("CUSTOMER-FILE")
 *     .assignTo("/data/customers.dat")
 *     .recordSize(200)
 *     .fileStatus(status)
 *     .onOpen(f -> System.out.println("Opened: " + f.name()))
 *     .build();
 *
 * file.open(OpenMode.INPUT);
 * while (file.read(buffer)) {
 *     // process record
 * }
 * file.close();
 * }</pre>
 */
public interface CobolFile {

    /** File open modes corresponding to COBOL's OPEN statement. */
    enum OpenMode {
        INPUT, OUTPUT, IO, EXTEND
    }

    // ── Core operations ─────────────────────────────────────────────

    String name();

    void open(OpenMode mode);

    void close();

    /**
     * READ — read the next record into a raw byte buffer.
     * Returns true if a record was read, false on end-of-file.
     */
    boolean read(byte[] buffer);

    /**
     * READ INTO — the Record reads itself from this file's input stream.
     * The Record owns its size and format — no external size configuration needed.
     */
    default boolean read(Record record) {
        InputStream in = inputStream();
        if (in == null) return false;
        try {
            return record.readFrom(in);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * WRITE — write raw bytes.
     */
    void write(byte[] buffer);

    /**
     * WRITE FROM — the Record writes itself to this file's output stream.
     * The Record owns its size and format.
     */
    default void write(Record record) {
        OutputStream out = outputStream();
        if (out == null) return;
        try {
            record.writeTo(out);
        } catch (java.io.IOException e) {
            throw new RuntimeException("WRITE failed for " + name() + ": " + e.getMessage(), e);
        }
    }

    /**
     * REWRITE — update the last-read record.
     */
    void rewrite(byte[] buffer);

    /** Access the underlying input stream (when open for INPUT or I-O). */
    InputStream inputStream();

    /** Access the underlying output stream (when open for OUTPUT, EXTEND, or I-O). */
    OutputStream outputStream();

    /**
     * DELETE — delete the last-read record (indexed/relative files).
     */
    void delete();

    /** Get the current file status. */
    FileStatus status();

    // ── Indexed file operations ─────────────────────────────────────

    /**
     * READ with a specific key value (indexed files).
     * Default implementation throws UnsupportedOperationException.
     */
    default boolean read(byte[] buffer, String keyName, String keyValue) {
        throw new UnsupportedOperationException(
            "Keyed READ not supported for " + name());
    }

    /**
     * START — position the file cursor for sequential reading from a key.
     * Default implementation throws UnsupportedOperationException.
     */
    default void start(String keyName, String keyValue, StartCondition condition) {
        throw new UnsupportedOperationException(
            "START not supported for " + name());
    }

    enum StartCondition {
        EQUAL, GREATER, NOT_LESS, GREATER_OR_EQUAL
    }

    // ── Factory: Sequential ─────────────────────────────────────────

    static SequentialFileBuilder sequential(String name) {
        return new SequentialFileBuilder(name);
    }

    // ── Factory: Indexed ────────────────────────────────────────────

    static IndexedFileBuilder indexed(String name) {
        return new IndexedFileBuilder(name);
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUILDERS
    // ═══════════════════════════════════════════════════════════════

    class SequentialFileBuilder {
        private final String name;
        private String path;
        private int recordSize;
        private FileStatus fileStatus;
        private Consumer<CobolFile> onOpen;
        private Consumer<CobolFile> onClose;

        SequentialFileBuilder(String name) { this.name = name; }

        public SequentialFileBuilder assignTo(String path) {
            this.path = path; return this;
        }

        public SequentialFileBuilder recordSize(int size) {
            this.recordSize = size; return this;
        }

        public SequentialFileBuilder fileStatus(FileStatus fs) {
            this.fileStatus = fs; return this;
        }

        public SequentialFileBuilder onOpen(Consumer<CobolFile> hook) {
            this.onOpen = hook; return this;
        }

        public SequentialFileBuilder onClose(Consumer<CobolFile> hook) {
            this.onClose = hook; return this;
        }

        public CobolFile build() {
            if (fileStatus == null) fileStatus = new FileStatus();
            return new SequentialFileImpl(name, path, recordSize, fileStatus, onOpen, onClose);
        }
    }

    class IndexedFileBuilder {
        private final String name;
        private String path;
        private int recordSize;
        private FileStatus fileStatus;
        private String primaryKey;
        private Consumer<CobolFile> onOpen;
        private Consumer<CobolFile> onClose;

        IndexedFileBuilder(String name) { this.name = name; }

        public IndexedFileBuilder assignTo(String path) {
            this.path = path; return this;
        }

        public IndexedFileBuilder recordSize(int size) {
            this.recordSize = size; return this;
        }

        public IndexedFileBuilder recordKey(String keyFieldName) {
            this.primaryKey = keyFieldName; return this;
        }

        public IndexedFileBuilder fileStatus(FileStatus fs) {
            this.fileStatus = fs; return this;
        }

        public IndexedFileBuilder onOpen(Consumer<CobolFile> hook) {
            this.onOpen = hook; return this;
        }

        public IndexedFileBuilder onClose(Consumer<CobolFile> hook) {
            this.onClose = hook; return this;
        }

        public CobolFile build() {
            if (fileStatus == null) fileStatus = new FileStatus();
            return new IndexedFileImpl(name, path, recordSize, primaryKey,
                                       fileStatus, onOpen, onClose);
        }
    }
}
