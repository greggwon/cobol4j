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
import java.util.*;
import java.util.function.Consumer;

/**
 * Indexed file implementation using an in-memory TreeMap.
 * <p>
 * This provides VSAM KSDS-like semantics: keyed access, sequential browsing,
 * and alternate keys. For production use, this would be backed by a B-tree
 * file or database — the interface contract stays the same.
 * <p>
 * The implementation is intentionally simple to demonstrate the API contract.
 * A production implementation would use persistent storage (e.g., SQLite, a
 * custom B-tree file, or a JDBC-backed store).
 */
final class IndexedFileImpl implements CobolFile {

    private final String name;
    private final String path;
    private final int recordSize;
    private final String primaryKeyField;
    private final FileStatus fileStatus;
    private final Consumer<CobolFile> onOpen;
    private final Consumer<CobolFile> onClose;

    // In-memory storage: key → record bytes
    private TreeMap<String, byte[]> store;
    private Iterator<Map.Entry<String, byte[]>> browseIterator;
    private String lastReadKey;
    private boolean isOpen;

    IndexedFileImpl(String name, String path, int recordSize,
                    String primaryKeyField, FileStatus fileStatus,
                    Consumer<CobolFile> onOpen, Consumer<CobolFile> onClose) {
        this.name = name;
        this.path = path;
        this.recordSize = recordSize;
        this.primaryKeyField = primaryKeyField;
        this.fileStatus = fileStatus;
        this.onOpen = onOpen;
        this.onClose = onClose;
    }

    @Override
    public String name() { return name; }

    @Override
    public void open(OpenMode mode) {
        store = new TreeMap<>();
        if (mode == OpenMode.INPUT || mode == OpenMode.IO) {
            loadFromDisk();
        }
        isOpen = true;
        browseIterator = null;
        fileStatus.set(FileStatus.SUCCESS);
        if (onOpen != null) onOpen.accept(this);
    }

    @Override
    public void close() {
        if (store != null) {
            saveToDisk();
        }
        isOpen = false;
        store = null;
        browseIterator = null;
        fileStatus.set(FileStatus.SUCCESS);
        if (onClose != null) onClose.accept(this);
    }

    @Override
    public boolean read(byte[] buffer) {
        // Sequential read (browse mode)
        if (browseIterator == null) {
            browseIterator = store.entrySet().iterator();
        }
        if (!browseIterator.hasNext()) {
            fileStatus.set(FileStatus.END_OF_FILE);
            return false;
        }
        Map.Entry<String, byte[]> entry = browseIterator.next();
        lastReadKey = entry.getKey();
        System.arraycopy(entry.getValue(), 0, buffer, 0,
                         Math.min(recordSize, buffer.length));
        fileStatus.set(FileStatus.SUCCESS);
        return true;
    }

    @Override
    public boolean read(byte[] buffer, String keyName, String keyValue) {
        byte[] record = store.get(keyValue.trim());
        if (record == null) {
            fileStatus.set(FileStatus.RECORD_NOT_FOUND);
            return false;
        }
        lastReadKey = keyValue.trim();
        System.arraycopy(record, 0, buffer, 0, Math.min(recordSize, buffer.length));
        fileStatus.set(FileStatus.SUCCESS);
        return true;
    }

    @Override
    public void start(String keyName, String keyValue, StartCondition condition) {
        NavigableMap<String, byte[]> subset = switch (condition) {
            case EQUAL -> store.subMap(keyValue, true, keyValue, true);
            case GREATER -> store.tailMap(keyValue, false);
            case NOT_LESS, GREATER_OR_EQUAL -> store.tailMap(keyValue, true);
        };
        browseIterator = subset.entrySet().iterator();
        if (subset.isEmpty()) {
            fileStatus.set(FileStatus.RECORD_NOT_FOUND);
        } else {
            fileStatus.set(FileStatus.SUCCESS);
        }
    }

    @Override
    public void write(byte[] buffer) {
        // Extract key from the record bytes (simplified: uses first N bytes)
        String key = extractKey(buffer);
        if (store.containsKey(key)) {
            fileStatus.set(FileStatus.DUPLICATE_KEY);
            return;
        }
        store.put(key, Arrays.copyOf(buffer, recordSize));
        fileStatus.set(FileStatus.SUCCESS);
    }

    @Override
    public void rewrite(byte[] buffer) {
        if (lastReadKey == null) {
            fileStatus.set(FileStatus.LOGIC_ERROR);
            return;
        }
        store.put(lastReadKey, Arrays.copyOf(buffer, recordSize));
        fileStatus.set(FileStatus.SUCCESS);
    }

    @Override
    public void delete() {
        if (lastReadKey == null) {
            fileStatus.set(FileStatus.LOGIC_ERROR);
            return;
        }
        store.remove(lastReadKey);
        lastReadKey = null;
        fileStatus.set(FileStatus.SUCCESS);
    }

    @Override
    public FileStatus status() { return fileStatus; }

    // ── persistence (simple line-based for prototype) ───────────────

    private void loadFromDisk() {
        if (path == null) return;
        File f = new File(path);
        if (!f.exists()) return;
        try (var in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[recordSize];
            while (true) {
                int totalRead = 0;
                while (totalRead < recordSize) {
                    int n = in.read(buf, totalRead, recordSize - totalRead);
                    if (n < 0) {
                        if (totalRead == 0) return; // clean EOF
                        // Partial record at end — pad and store
                        Arrays.fill(buf, totalRead, recordSize, (byte) ' ');
                        break;
                    }
                    totalRead += n;
                }
                String key = extractKey(buf);
                store.put(key, Arrays.copyOf(buf, recordSize));
            }
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
        }
    }

    private void saveToDisk() {
        if (path == null) return;
        try (var out = new BufferedOutputStream(new FileOutputStream(path))) {
            for (byte[] record : store.values()) {
                out.write(record, 0, recordSize);
            }
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
        }
    }

    private String extractKey(byte[] buffer) {
        // For this prototype, the key is extracted as a trimmed string
        // from the first field-width of the record.
        // A full implementation would use the Record's field definition
        // to locate the key bytes.
        // For now, use a fixed key length heuristic or the full record
        return new String(buffer, 0, Math.min(20, recordSize)).trim();
    }
}
