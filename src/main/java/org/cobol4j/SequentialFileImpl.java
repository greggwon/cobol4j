package org.cobol4j;

import java.io.*;
import java.util.function.Consumer;

/**
 * Sequential file implementation backed by a flat file on disk.
 * <p>
 * Records are fixed-length byte blocks written and read sequentially.
 * This mirrors COBOL sequential file organization.
 */
final class SequentialFileImpl implements CobolFile {

    private final String name;
    private final String path;
    private final int recordSize;
    private final FileStatus fileStatus;
    private final Consumer<CobolFile> onOpen;
    private final Consumer<CobolFile> onClose;

    private InputStream input;
    private OutputStream output;
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
                case IO -> throw new UnsupportedOperationException(
                    "OPEN I-O not supported for sequential files");
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
            fileStatus.set(FileStatus.SUCCESS);
            if (onClose != null) onClose.accept(this);
        } catch (IOException e) {
            fileStatus.set(FileStatus.IO_ERROR);
        }
        currentMode = null;
    }

    @Override
    public boolean read(byte[] buffer) {
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

    @Override
    public void write(byte[] buffer) {
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

    @Override
    public void rewrite(byte[] buffer) {
        fileStatus.set(FileStatus.LOGIC_ERROR); // not supported for sequential
    }

    @Override
    public void delete() {
        fileStatus.set(FileStatus.LOGIC_ERROR); // not supported for sequential
    }

    @Override
    public FileStatus status() { return fileStatus; }
}
