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
package org.cobol4j.interop;

import org.cobol4j.Record;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads and writes mainframe data files — fixed-length or variable-length (RDW)
 * records in EBCDIC encoding.
 * <p>
 * When data is transferred from a mainframe (z/OS) via FTP or Connect:Direct, it
 * arrives as raw bytes in EBCDIC encoding. This class reads those bytes, translates
 * to ASCII, and loads them into cobol4j Records defined from the same copybook.
 * <p>
 * Supports:
 * <ul>
 *   <li>Fixed-length records (FB — Fixed Blocked)</li>
 *   <li>Variable-length records with 4-byte RDW prefix (VB — Variable Blocked)</li>
 *   <li>EBCDIC → ASCII translation (configurable code page)</li>
 *   <li>Streaming read (record-at-a-time, memory efficient)</li>
 * </ul>
 * <pre>{@code
 * // Read a mainframe file into Records
 * Record custRec = CopybookImporter.toRecord("CUSTCPY.cpy");
 *
 * MainframeFile.reader("CUSTFILE.dat")
 *     .fixedLength(200)
 *     .ebcdic(Ebcdic.CodePage.CP037)
 *     .forEach(custRec, rec -> {
 *         System.out.println(rec.getString("CUST-NAME").trim());
 *     });
 *
 * // Write records in mainframe format
 * MainframeFile.writer("OUTFILE.dat")
 *     .fixedLength(200)
 *     .ebcdic(Ebcdic.CodePage.CP037)
 *     .write(custRec);
 * }</pre>
 */
public final class MainframeFile {

    private MainframeFile() {}

    /** Create a reader for a mainframe data file. */
    public static ReaderBuilder reader(String path) {
        return new ReaderBuilder(Path.of(path));
    }

    /** Create a reader from a Path. */
    public static ReaderBuilder reader(Path path) {
        return new ReaderBuilder(path);
    }

    /** Create a reader from raw bytes (e.g., from a message queue or network). */
    public static ReaderBuilder reader(byte[] data) {
        return new ReaderBuilder(data);
    }

    /** Create a writer for a mainframe data file. */
    public static WriterBuilder writer(String path) {
        return new WriterBuilder(Path.of(path));
    }

    /** Create a writer from a Path. */
    public static WriterBuilder writer(Path path) {
        return new WriterBuilder(path);
    }

    // ═══════════════════════════════════════════════════════════════
    //  READER
    // ═══════════════════════════════════════════════════════════════

    public static final class ReaderBuilder {
        private final Path path;
        private final byte[] rawData;
        private int recordLength;
        private boolean variableLength;
        private Ebcdic codec;
        private boolean translateToAscii = true;

        ReaderBuilder(Path path) { this.path = path; this.rawData = null; }
        ReaderBuilder(byte[] data) { this.path = null; this.rawData = data; }

        /** Fixed-length records (FB format). */
        public ReaderBuilder fixedLength(int recordLength) {
            this.recordLength = recordLength;
            this.variableLength = false;
            return this;
        }

        /** Variable-length records with 4-byte RDW prefix (VB format). */
        public ReaderBuilder variableLength() {
            this.variableLength = true;
            return this;
        }

        /** Set EBCDIC code page for translation. Default: CP037. */
        public ReaderBuilder ebcdic(Ebcdic.CodePage codePage) {
            this.codec = Ebcdic.codePage(codePage);
            return this;
        }

        /** Read as raw bytes — no EBCDIC translation. */
        public ReaderBuilder noTranslation() {
            this.translateToAscii = false;
            return this;
        }

        /**
         * Read all records, loading each into the provided Record and calling
         * the consumer. The Record is reused for each iteration (COBOL-style).
         */
        public void forEach(Record record, Consumer<Record> consumer) throws IOException {
            byte[] fileData = readFileData();
            if (codec == null && translateToAscii) codec = Ebcdic.defaultCodePage();

            int offset = 0;
            while (offset < fileData.length) {
                int recLen;
                int dataStart;

                if (variableLength) {
                    // RDW: 2-byte length (big-endian) + 2 bytes reserved
                    if (offset + 4 > fileData.length) break;
                    recLen = ((fileData[offset] & 0xFF) << 8)
                           | (fileData[offset + 1] & 0xFF);
                    recLen -= 4; // RDW includes itself
                    dataStart = offset + 4;
                } else {
                    recLen = recordLength;
                    dataStart = offset;
                }

                if (dataStart + recLen > fileData.length) break;

                byte[] recordBytes;
                if (translateToAscii && codec != null) {
                    recordBytes = codec.decode(fileData, dataStart, recLen);
                } else {
                    recordBytes = new byte[recLen];
                    System.arraycopy(fileData, dataStart, recordBytes, 0, recLen);
                }

                record.loadFrom(recordBytes);
                consumer.accept(record);

                offset = variableLength ? dataStart + recLen : offset + recordLength;
            }
        }

        /**
         * Read all records as raw byte arrays (one per record).
         */
        public List<byte[]> readAll() throws IOException {
            List<byte[]> records = new ArrayList<>();
            byte[] fileData = readFileData();
            if (codec == null && translateToAscii) codec = Ebcdic.defaultCodePage();

            int offset = 0;
            while (offset < fileData.length) {
                int recLen;
                int dataStart;

                if (variableLength) {
                    if (offset + 4 > fileData.length) break;
                    recLen = ((fileData[offset] & 0xFF) << 8)
                           | (fileData[offset + 1] & 0xFF) - 4;
                    dataStart = offset + 4;
                } else {
                    recLen = recordLength;
                    dataStart = offset;
                }

                if (dataStart + recLen > fileData.length) break;

                byte[] recordBytes;
                if (translateToAscii && codec != null) {
                    recordBytes = codec.decode(fileData, dataStart, recLen);
                } else {
                    recordBytes = new byte[recLen];
                    System.arraycopy(fileData, dataStart, recordBytes, 0, recLen);
                }

                records.add(recordBytes);
                offset = variableLength ? dataStart + recLen : offset + recordLength;
            }
            return records;
        }

        private byte[] readFileData() throws IOException {
            if (rawData != null) return rawData;
            return Files.readAllBytes(path);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  WRITER
    // ═══════════════════════════════════════════════════════════════

    public static final class WriterBuilder {
        private final Path path;
        private int recordLength;
        private boolean variableLength;
        private Ebcdic codec;
        private boolean translateToEbcdic = true;
        private OutputStream outputStream;

        WriterBuilder(Path path) { this.path = path; }

        /** Fixed-length records (FB format). */
        public WriterBuilder fixedLength(int recordLength) {
            this.recordLength = recordLength;
            this.variableLength = false;
            return this;
        }

        /** Variable-length records with 4-byte RDW prefix (VB format). */
        public WriterBuilder variableLength() {
            this.variableLength = true;
            return this;
        }

        /** Set EBCDIC code page for translation. Default: CP037. */
        public WriterBuilder ebcdic(Ebcdic.CodePage codePage) {
            this.codec = Ebcdic.codePage(codePage);
            return this;
        }

        /** Write as raw bytes — no ASCII-to-EBCDIC translation. */
        public WriterBuilder noTranslation() {
            this.translateToEbcdic = false;
            return this;
        }

        /** Open the file for writing. Call close() when done. */
        public WriterBuilder open() throws IOException {
            outputStream = new BufferedOutputStream(Files.newOutputStream(path));
            if (codec == null && translateToEbcdic) codec = Ebcdic.defaultCodePage();
            return this;
        }

        /** Write a single record. */
        public WriterBuilder write(Record record) throws IOException {
            if (outputStream == null) open();
            byte[] data = record.buffer();

            // Pad or truncate to fixed length if needed
            if (!variableLength && recordLength > 0) {
                byte[] padded = new byte[recordLength];
                System.arraycopy(data, 0, padded, 0, Math.min(data.length, recordLength));
                // Pad remainder with spaces
                for (int i = data.length; i < recordLength; i++) {
                    padded[i] = (byte) ' ';
                }
                data = padded;
            }

            byte[] outputData;
            if (translateToEbcdic && codec != null) {
                outputData = codec.encode(data);
            } else {
                outputData = data;
            }

            if (variableLength) {
                // Write RDW: 2-byte length (includes RDW itself) + 2 reserved bytes
                int totalLen = outputData.length + 4;
                outputStream.write((totalLen >> 8) & 0xFF);
                outputStream.write(totalLen & 0xFF);
                outputStream.write(0);
                outputStream.write(0);
            }

            outputStream.write(outputData);
            return this;
        }

        /** Write multiple records from a list of byte arrays. */
        public WriterBuilder writeAll(List<byte[]> records) throws IOException {
            if (outputStream == null) open();
            for (byte[] rec : records) {
                Record temp = Record.define("TEMP")
                    .pic("DATA", "X(" + rec.length + ")")
                    .build();
                temp.loadFrom(rec);
                write(temp);
            }
            return this;
        }

        /** Close the output file. */
        public void close() throws IOException {
            if (outputStream != null) {
                outputStream.flush();
                outputStream.close();
                outputStream = null;
            }
        }
    }
}
