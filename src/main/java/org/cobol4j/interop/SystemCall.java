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

import java.util.Map;

/**
 * POSIX-style system call interface for transpiled COBOL programs.
 * <p>
 * When a COBOL program contains {@code CALL "open"} or {@code CALL "getenv"},
 * the transpiler emits calls against this interface. The default implementation
 * maps to Java's built-in facilities (java.nio, java.net, ProcessBuilder, etc.).
 * <p>
 * For device-specific operations that require {@code ioctl()} or native access
 * (serial ports, GPIO via libgpiod, custom drivers), provide an alternative
 * implementation using JNA, Panama, or JNI.
 *
 * <pre>{@code
 * // Transpiled COBOL: CALL "open" USING ws-path ws-flags RETURNING ws-fd
 * SystemCall sys = SystemCall.defaultInstance();
 *
 * int fd = sys.open("/proc/cpuinfo", SystemCall.O_RDONLY);
 * byte[] buf = new byte[4096];
 * int n = sys.read(fd, buf, buf.length);
 * sys.close(fd);
 *
 * // Environment
 * String home = sys.getenv("HOME");
 *
 * // Process execution
 * int rc = sys.system("ls -la /tmp");
 *
 * // TCP sockets
 * int sock = sys.socket(SystemCall.AF_INET, SystemCall.SOCK_STREAM);
 * sys.connect(sock, "192.168.1.1", 8080);
 * sys.send(sock, data, data.length);
 * sys.closeSocket(sock);
 * }</pre>
 */
public interface SystemCall {

    // ── File open flags ─────────────────────────────────────────────
    int O_RDONLY   = 0;
    int O_WRONLY   = 1;
    int O_RDWR     = 2;
    int O_CREAT    = 0x40;
    int O_TRUNC    = 0x200;
    int O_APPEND   = 0x400;

    // ── Socket constants ────────────────────────────────────────────
    int AF_INET     = 2;
    int AF_INET6    = 10;
    int AF_UNIX     = 1;
    int SOCK_STREAM = 1;
    int SOCK_DGRAM  = 2;

    // ── File I/O ────────────────────────────────────────────────────

    /**
     * Open a file or device path.
     * @return a file descriptor handle (implementation-defined)
     */
    int open(String path, int flags);

    /** Open with permissions (for O_CREAT). */
    int open(String path, int flags, int mode);

    /** Read bytes from an open file descriptor. Returns bytes read, or -1 on EOF. */
    int read(int fd, byte[] buffer, int length);

    /** Write bytes to an open file descriptor. Returns bytes written. */
    int write(int fd, byte[] buffer, int length);

    /** Close a file descriptor. */
    void close(int fd);

    /** Read the entire contents of a path as a string (convenience for /proc, /sys). */
    String readFile(String path);

    /** Write a string to a path (convenience for /sys/class/gpio, etc.). */
    void writeFile(String path, String content);

    // ── Environment ─────────────────────────────────────────────────

    /** Get an environment variable. Returns null if not set. */
    String getenv(String name);

    /** Get all environment variables. */
    Map<String, String> environ();

    // ── Process execution ───────────────────────────────────────────

    /** Execute a shell command. Returns the exit code. */
    int system(String command);

    /**
     * Execute a command and capture its stdout.
     * @return the command's standard output as a string
     */
    String exec(String... command);

    // ── TCP/UDP Sockets ─────────────────────────────────────────────

    /** Create a socket. Returns a socket descriptor handle. */
    int socket(int domain, int type);

    /** Connect a socket to a remote address. */
    void connect(int sockfd, String host, int port);

    /** Bind a socket to a local address for listening. */
    void bind(int sockfd, String host, int port);

    /** Listen for incoming connections. */
    void listen(int sockfd, int backlog);

    /** Accept an incoming connection. Returns a new socket descriptor. */
    int accept(int sockfd);

    /** Send data on a connected socket. Returns bytes sent. */
    int send(int sockfd, byte[] data, int length);

    /** Receive data from a connected socket. Returns bytes received. */
    int recv(int sockfd, byte[] buffer, int length);

    /** Close a socket. */
    void closeSocket(int sockfd);

    // ── System info ─────────────────────────────────────────────────

    /** Get the current working directory. */
    String getcwd();

    /** Get the hostname. */
    String hostname();

    /** Get current time as Unix epoch seconds. */
    long time();

    // ── Factory ─────────────────────────────────────────────────────

    /** Get the default implementation backed by Java built-in facilities. */
    static SystemCall defaultInstance() {
        return JavaSystemCall.INSTANCE;
    }
}
