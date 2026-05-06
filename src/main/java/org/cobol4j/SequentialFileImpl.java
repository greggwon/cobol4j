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
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Sequential file implementation backed by a flat file on disk.
 * <p>
 * Records are fixed-length byte blocks written and read sequentially.
 * This mirrors COBOL sequential file organization.
 */
final class SequentialFileImpl implements CobolFile {

    private static final Logger LOG = Logger.getLogger(SequentialFileImpl.class.getName());

    private final String name;
    private final String path;
    private final int recordSize;
    private final FileStatus fileStatus;
    private final Consumer<CobolFile> onOpen;
    private final Consumer<CobolFile> onClose;

    private InputStream input;
    private OutputStream output;
    private RandomAccessFile ioFile;
    private OpenMode currentMode;

    SequentialFileImpl(String name, String path, int recordSize,
                       FileStatus fileStatus,
                       Consumer<CobolFile> onOpen, Consumer<CobolFile> onClose) {
        this.name = name;
        this.path = path;
        this.recordSize = recordSize;
        this.fileStatus = fileStatus;
        this.onOpen = onOpen;
        this.onClose = onClose;
    }

    @Override
    public String name() { return name; }

    @Override
    public void open(OpenMode mode) {
        try {
            currentMode = mode;
            switch (mode) {
                case INPUT -> input = new BufferedInputStream(new FileInputStream(path));
                case OUTPUT -> output = new BufferedOutputStream(new FileOutputStream(path));
                case EXTEND -> output = new BufferedOutputStream(new FileOutputStream(path, true));
                case IO -> {
                    // I-O mode: open with RandomAccessFile for both reading and writing
                    ioFile = new RandomAccessFile(path, "rw");
                    LOG.fine("Opened sequential file '" + name + "' in I-O mode via RandomAccessFile");
                }
            }
            fileStatus.set(FileStatus.SUCCESS);
            if (onOpen != null) onOpen.accept(this);
        } catch (FileNotFoundException e) {
            fileStatus.set(FileStatus.FILE_NOT_FOUND);
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
        }
    }

    @Override
    public void close() {
        try {
            if (input != null) { input.close(); input = null; }
            if (output != null) { output.close(); output = null; }
            if (ioFile != null) { ioFile.close(); ioFile = null; }
            fileStatus.set(FileStatus.SUCCESS);
            if (onClose != null) onClose.accept(this);
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
        }
        currentMode = null;
    }

    @Override
    public boolean read(byte[] buffer) {
        // I-O mode: read via RandomAccessFile
        if (ioFile != null) {
            return readFromRandomAccess(buffer);
        }
        if (input == null) {
            fileStatus.set(FileStatus.LOGIC_ERROR);
            return false;
        }
        try {
            int totalRead = 0;
            int size = Math.min(recordSize, buffer.length);
            while (totalRead < size) {
                int n = input.read(buffer, totalRead, size - totalRead);
                if (n < 0) {
                    if (totalRead == 0) {
                        fileStatus.set(FileStatus.END_OF_FILE);
                        return false;
                    }
                    break;
                }
                totalRead += n;
            }
            // Pad remaining buffer with spaces if partial read
            for (int i = totalRead; i < buffer.length; i++) {
                buffer[i] = (byte) ' ';
            }
            fileStatus.set(FileStatus.SUCCESS);
            return true;
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
            return false;
        }
    }

    private boolean readFromRandomAccess(byte[] buffer) {
        try {
            int totalRead = 0;
            int size = Math.min(recordSize, buffer.length);
            while (totalRead < size) {
                int n = ioFile.read(buffer, totalRead, size - totalRead);
                if (n < 0) {
                    if (totalRead == 0) {
                        fileStatus.set(FileStatus.END_OF_FILE);
                        return false;
                    }
                    break;
                }
                totalRead += n;
            }
            for (int i = totalRead; i < buffer.length; i++) {
                buffer[i] = (byte) ' ';
            }
            fileStatus.set(FileStatus.SUCCESS);
            return true;
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
            return false;
        }
    }

    @Override
    public void write(byte[] buffer) {
        // I-O mode: write via RandomAccessFile
        if (ioFile != null) {
            writeToRandomAccess(buffer);
            return;
        }
        if (output == null) {
            fileStatus.set(FileStatus.LOGIC_ERROR);
            return;
        }
        try {
            // Write exactly recordSize bytes, padding if needed
            output.write(buffer, 0, Math.min(recordSize, buffer.length));
            if (buffer.length < recordSize) {
                byte[] pad = new byte[recordSize - buffer.length];
                java.util.Arrays.fill(pad, (byte) ' ');
                output.write(pad);
            }
            fileStatus.set(FileStatus.SUCCESS);
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
        }
    }

    private void writeToRandomAccess(byte[] buffer) {
        try {
            ioFile.write(buffer, 0, Math.min(recordSize, buffer.length));
            if (buffer.length < recordSize) {
                byte[] pad = new byte[recordSize - buffer.length];
                java.util.Arrays.fill(pad, (byte) ' ');
                ioFile.write(pad);
            }
            fileStatus.set(FileStatus.SUCCESS);
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
        }
    }

    @Override
    public void rewrite(byte[] buffer) {
        if (ioFile != null) {
            // In I-O mode, REWRITE replaces the last-read record
            try {
                long currentPos = ioFile.getFilePointer();
                ioFile.seek(currentPos - recordSize); // back up one record
                writeToRandomAccess(buffer);
            } catch (IOException e) {
                fileStatus.set(FileStatus.IO_ERROR);
            }
            return;
        }
        fileStatus.set(FileStatus.LOGIC_ERROR); // not supported for sequential in non-IO mode
    }

    @Override
    public void delete() {
        fileStatus.set(FileStatus.LOGIC_ERROR); // not supported for sequential
    }

    @Override
    public FileStatus status() { return fileStatus; }
}
