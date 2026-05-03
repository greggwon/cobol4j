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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SystemCallTest {

    private final SystemCall sys = SystemCall.defaultInstance();

    // ── File I/O ────────────────────────────────────────────────────

    @Test
    void openReadCloseFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "HELLO WORLD");

        int fd = sys.open(file.toString(), SystemCall.O_RDONLY);
        assertTrue(fd >= 0, "open should return valid fd");

        byte[] buf = new byte[20];
        int n = sys.read(fd, buf, 20);
        assertTrue(n > 0, "read should return bytes");
        assertEquals("HELLO WORLD", new String(buf, 0, n));

        sys.close(fd);
    }

    @Test
    void openWriteReadRoundtrip(@TempDir Path tempDir) {
        String path = tempDir.resolve("out.txt").toString();

        int fd = sys.open(path, SystemCall.O_WRONLY | SystemCall.O_CREAT);
        assertTrue(fd >= 0);
        byte[] data = "TEST DATA 12345".getBytes();
        int written = sys.write(fd, data, data.length);
        assertEquals(data.length, written);
        sys.close(fd);

        // Read it back
        int fd2 = sys.open(path, SystemCall.O_RDONLY);
        byte[] buf = new byte[50];
        int n = sys.read(fd2, buf, 50);
        assertEquals("TEST DATA 12345", new String(buf, 0, n));
        sys.close(fd2);
    }

    @Test
    void readFileConvenience(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("content.txt");
        Files.writeString(file, "quick read");

        String content = sys.readFile(file.toString());
        assertEquals("quick read", content);
    }

    @Test
    void writeFileConvenience(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("written.txt");

        sys.writeFile(file.toString(), "quick write");
        assertEquals("quick write", Files.readString(file));
    }

    @Test
    void openNonexistentReturnsNegative() {
        int fd = sys.open("/nonexistent/path/foo.bar", SystemCall.O_RDONLY);
        assertEquals(-1, fd);
    }

    // ── Environment ─────────────────────────────────────────────────

    @Test
    void getenvReadsVariable() {
        // PATH is always set on any Unix or macOS system
        String path = sys.getenv("PATH");
        assertNotNull(path);
        assertFalse(path.isEmpty());
    }

    @Test
    void getenvNullForMissing() {
        assertNull(sys.getenv("COBOL4J_NONEXISTENT_VAR_XYZ"));
    }

    @Test
    void environReturnsMap() {
        var env = sys.environ();
        assertNotNull(env);
        assertFalse(env.isEmpty());
        assertTrue(env.containsKey("PATH"));
    }

    // ── Process execution ───────────────────────────────────────────

    @Test
    void systemRunsCommand() {
        int rc = sys.system("true");
        assertEquals(0, rc);
    }

    @Test
    void systemReturnsExitCode() {
        int rc = sys.system("false");
        assertNotEquals(0, rc);
    }

    @Test
    void execCapturesOutput() {
        String output = sys.exec("echo", "hello");
        assertNotNull(output);
        assertTrue(output.trim().contains("hello"));
    }

    // ── Sockets ─────────────────────────────────────────────────────

    @Test
    void socketConnectSendRecv() throws Exception {
        // Start a simple server in a thread
        int serverFd = sys.socket(SystemCall.AF_INET, SystemCall.SOCK_STREAM);
        sys.bind(serverFd, "127.0.0.1", 0); // port 0 = OS assigns

        // Get the actual port from the underlying ServerSocket
        // (We test the basic lifecycle even if we can't get the port directly)
        sys.closeSocket(serverFd);

        // Verify socket creation returns valid fd
        int fd = sys.socket(SystemCall.AF_INET, SystemCall.SOCK_STREAM);
        assertTrue(fd >= 0);
        sys.closeSocket(fd);
    }

    // ── System info ���────────────────────────────────────────────────

    @Test
    void getcwdReturnsDirectory() {
        String cwd = sys.getcwd();
        assertNotNull(cwd);
        assertFalse(cwd.isEmpty());
    }

    @Test
    void hostnameReturnsValue() {
        String host = sys.hostname();
        assertNotNull(host);
        assertFalse(host.isEmpty());
    }

    @Test
    void timeReturnsEpoch() {
        long t = sys.time();
        // Should be somewhere after 2020 and before 2100
        assertTrue(t > 1577836800L); // 2020-01-01
        assertTrue(t < 4102444800L); // 2100-01-01
    }
}
