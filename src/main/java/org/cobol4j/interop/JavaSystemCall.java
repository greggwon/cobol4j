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

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default {@link SystemCall} implementation using Java's built-in facilities.
 * <p>
 * Maps POSIX-style calls to java.nio.file, java.net, and ProcessBuilder.
 * File descriptors and socket descriptors are integer handles mapped to
 * open Java resources via internal tables.
 * <p>
 * Covers: file I/O (including /proc, /sys, device files), environment variables,
 * process execution, and TCP/UDP sockets. Does NOT support ioctl(), raw sockets,
 * or signal handling — those require JNA, Panama, or JNI.
 */
final class JavaSystemCall implements SystemCall {

    static final JavaSystemCall INSTANCE = new JavaSystemCall();

    private final AtomicInteger nextFd = new AtomicInteger(3); // 0,1,2 reserved
    private final Map<Integer, FileResource> openFiles = new ConcurrentHashMap<>();
    private final Map<Integer, SocketResource> openSockets = new ConcurrentHashMap<>();

    // ── File I/O ────────────────────────────────────────────────────

    @Override
    public int open(String path, int flags) {
        return open(path, flags, 0644);
    }

    @Override
    public int open(String path, int flags, int mode) {
        try {
            Path p = Path.of(path);
            List<OpenOption> opts = new ArrayList<>();

            if ((flags & O_RDWR) == O_RDWR) {
                opts.add(StandardOpenOption.READ);
                opts.add(StandardOpenOption.WRITE);
            } else if ((flags & O_WRONLY) == O_WRONLY) {
                opts.add(StandardOpenOption.WRITE);
            } else {
                opts.add(StandardOpenOption.READ);
            }

            if ((flags & O_CREAT) != 0) opts.add(StandardOpenOption.CREATE);
            if ((flags & O_TRUNC) != 0) opts.add(StandardOpenOption.TRUNCATE_EXISTING);
            if ((flags & O_APPEND) != 0) opts.add(StandardOpenOption.APPEND);

            // For /proc, /sys, and device files, fall back to stream-based I/O
            // since they may not support SeekableByteChannel
            boolean isSpecial = path.startsWith("/proc") || path.startsWith("/sys")
                || path.startsWith("/dev");

            int fd = nextFd.getAndIncrement();
            if (isSpecial) {
                InputStream in = null;
                OutputStream out = null;
                if (opts.contains(StandardOpenOption.READ)) {
                    in = new FileInputStream(path);
                }
                if (opts.contains(StandardOpenOption.WRITE)) {
                    out = new FileOutputStream(path,
                        opts.contains(StandardOpenOption.APPEND));
                }
                openFiles.put(fd, new FileResource(null, in, out));
            } else {
                FileChannel channel = FileChannel.open(p,
                    opts.toArray(new OpenOption[0]));
                openFiles.put(fd, new FileResource(channel, null, null));
            }
            return fd;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public int read(int fd, byte[] buffer, int length) {
        FileResource res = openFiles.get(fd);
        if (res == null) return -1;
        try {
            if (res.channel != null) {
                return res.channel.read(java.nio.ByteBuffer.wrap(buffer, 0, length));
            } else if (res.input != null) {
                int n = res.input.read(buffer, 0, length);
                return n;
            }
            return -1;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public int write(int fd, byte[] buffer, int length) {
        FileResource res = openFiles.get(fd);
        if (res == null) return -1;
        try {
            if (res.channel != null) {
                return res.channel.write(java.nio.ByteBuffer.wrap(buffer, 0, length));
            } else if (res.output != null) {
                res.output.write(buffer, 0, length);
                res.output.flush();
                return length;
            }
            return -1;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void close(int fd) {
        FileResource res = openFiles.remove(fd);
        if (res != null) res.close();
    }

    @Override
    public String readFile(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void writeFile(String path, String content) {
        try {
            Files.writeString(Path.of(path), content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path, e);
        }
    }

    // ── Environment ─────────────────────────────────────────────────

    @Override
    public String getenv(String name) {
        return System.getenv(name);
    }

    @Override
    public Map<String, String> environ() {
        return System.getenv();
    }

    // ── Process execution ───────────────────────────────────────────

    @Override
    public int system(String command) {
        try {
            String shell = System.getenv("SHELL");
            if (shell == null) shell = "/bin/sh";
            Process proc = new ProcessBuilder(shell, "-c", command)
                .inheritIO()
                .start();
            return proc.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public String exec(String... command) {
        try {
            Process proc = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            String output;
            try (var reader = proc.inputReader()) {
                output = reader.lines().reduce("", (a, b) -> a + b + "\n");
            }
            proc.waitFor();
            return output;
        } catch (Exception e) {
            return null;
        }
    }

    // ── TCP/UDP Sockets ──────────────────────────��──────────────────

    @Override
    public int socket(int domain, int type) {
        int fd = nextFd.getAndIncrement();
        openSockets.put(fd, new SocketResource(type));
        return fd;
    }

    @Override
    public void connect(int sockfd, String host, int port) {
        SocketResource res = openSockets.get(sockfd);
        if (res == null) throw new IllegalArgumentException("Bad socket fd: " + sockfd);
        try {
            res.socket = new Socket();
            res.socket.connect(new InetSocketAddress(host, port), 30000);
        } catch (IOException e) {
            throw new RuntimeException("connect failed: " + host + ":" + port, e);
        }
    }

    @Override
    public void bind(int sockfd, String host, int port) {
        SocketResource res = openSockets.get(sockfd);
        if (res == null) throw new IllegalArgumentException("Bad socket fd: " + sockfd);
        try {
            res.server = new ServerSocket();
            res.server.bind(new InetSocketAddress(host, port));
        } catch (IOException e) {
            throw new RuntimeException("bind failed: " + host + ":" + port, e);
        }
    }

    @Override
    public void listen(int sockfd, int backlog) {
        // ServerSocket.bind() already sets the backlog; this is a no-op in Java
    }

    @Override
    public int accept(int sockfd) {
        SocketResource res = openSockets.get(sockfd);
        if (res == null || res.server == null) {
            throw new IllegalArgumentException("Bad server socket fd: " + sockfd);
        }
        try {
            Socket client = res.server.accept();
            int newFd = nextFd.getAndIncrement();
            SocketResource clientRes = new SocketResource(SOCK_STREAM);
            clientRes.socket = client;
            openSockets.put(newFd, clientRes);
            return newFd;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public int send(int sockfd, byte[] data, int length) {
        SocketResource res = openSockets.get(sockfd);
        if (res == null || res.socket == null) return -1;
        try {
            res.socket.getOutputStream().write(data, 0, length);
            return length;
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public int recv(int sockfd, byte[] buffer, int length) {
        SocketResource res = openSockets.get(sockfd);
        if (res == null || res.socket == null) return -1;
        try {
            return res.socket.getInputStream().read(buffer, 0, length);
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public void closeSocket(int sockfd) {
        SocketResource res = openSockets.remove(sockfd);
        if (res != null) res.close();
    }

    // ── System info ───────────────────────────────��─────────────────

    @Override
    public String getcwd() {
        return System.getProperty("user.dir");
    }

    @Override
    public String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    @Override
    public long time() {
        return System.currentTimeMillis() / 1000;
    }

    // ── Internal resource tracking ──────────────────────────────────

    private static class FileResource {
        final FileChannel channel;
        final InputStream input;
        final OutputStream output;

        FileResource(FileChannel channel, InputStream input, OutputStream output) {
            this.channel = channel;
            this.input = input;
            this.output = output;
        }

        void close() {
            try { if (channel != null) channel.close(); } catch (Exception ignored) {}
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
        }
    }

    private static class SocketResource {
        final int type;
        Socket socket;
        ServerSocket server;

        SocketResource(int type) { this.type = type; }

        void close() {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }
    }
}
