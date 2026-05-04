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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * Dynamically loads {@link CicsProgram} implementations from JARs or class directories.
 * <p>
 * Two loading mechanisms:
 * <ul>
 *   <li><b>Explicit</b> — load a specific class from a specific JAR/path</li>
 *   <li><b>ServiceLoader</b> — discover all CicsProgram implementations in a JAR
 *       via {@code META-INF/services/org.cobol4j.cics.CicsProgram}</li>
 * </ul>
 *
 * <pre>{@code
 * // Load a specific program from a JAR
 * CicsProgram prog = ProgramLoader.fromJar(
 *     Path.of("/deploy/custinq.jar"), "com.bank.CustInquiry");
 * region.install("CUSTINQ", prog);
 *
 * // Load and install all programs discovered in a JAR via ServiceLoader
 * ProgramLoader.installAll(region, Path.of("/deploy/programs.jar"));
 *
 * // Watch a directory for new/updated JARs (hot deploy)
 * ProgramLoader.watch(region, Path.of("/deploy"));
 * }</pre>
 */
public final class ProgramLoader {

    private static final Logger LOG = Logger.getLogger(ProgramLoader.class.getName());

    private ProgramLoader() {}

    /**
     * Load a CicsProgram from a JAR file by fully-qualified class name.
     * The class must implement {@link CicsProgram} and have a no-arg constructor.
     *
     * @param jarPath   path to the JAR file
     * @param className fully-qualified class name (e.g., "com.bank.CustInquiry")
     * @return the instantiated program
     */
    public static CicsProgram fromJar(Path jarPath, String className) {
        try {
            URLClassLoader loader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                ProgramLoader.class.getClassLoader());
            Class<?> clazz = loader.loadClass(className);
            if (!CicsProgram.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(
                    className + " does not implement CicsProgram");
            }
            return (CicsProgram) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException("Failed to load program " + className
                + " from " + jarPath, e);
        }
    }

    /**
     * Load a CicsProgram from a class directory by fully-qualified class name.
     *
     * @param classDir  directory containing compiled .class files
     * @param className fully-qualified class name
     * @return the instantiated program
     */
    public static CicsProgram fromDirectory(Path classDir, String className) {
        try {
            URLClassLoader loader = new URLClassLoader(
                new URL[]{classDir.toUri().toURL()},
                ProgramLoader.class.getClassLoader());
            Class<?> clazz = loader.loadClass(className);
            if (!CicsProgram.class.isAssignableFrom(clazz)) {
                throw new IllegalArgumentException(
                    className + " does not implement CicsProgram");
            }
            return (CicsProgram) clazz.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | IOException e) {
            throw new RuntimeException("Failed to load program " + className
                + " from " + classDir, e);
        }
    }

    /**
     * Discover and install all CicsProgram implementations found in a JAR
     * via Java's ServiceLoader mechanism.
     * <p>
     * The JAR must contain {@code META-INF/services/org.cobol4j.cics.CicsProgram}
     * listing the implementation classes. Each discovered program is installed
     * using its simple class name as the program name.
     *
     * @param region the region to install into
     * @param jarPath path to the JAR
     * @return number of programs installed
     */
    public static int installAll(CicsRegion region, Path jarPath) {
        try {
            URLClassLoader loader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                ProgramLoader.class.getClassLoader());
            ServiceLoader<CicsProgram> services =
                ServiceLoader.load(CicsProgram.class, loader);
            int count = 0;
            for (CicsProgram program : services) {
                String name;
                String transId = null;

                if (program instanceof NamedCicsProgram named) {
                    // Self-describing: use declared name and routing
                    name = named.programName();
                    transId = named.transactionId();
                } else {
                    // Fall back to class simple name
                    name = program.getClass().getSimpleName().toUpperCase();
                }

                region.install(name, program);
                if (transId != null && !transId.isEmpty()) {
                    region.transaction(transId, name);
                    LOG.info("Installed program " + name + " → transaction "
                        + transId + " from " + jarPath.getFileName());
                } else {
                    LOG.info("Installed program " + name + " from " + jarPath.getFileName());
                }
                count++;
            }
            return count;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load programs from " + jarPath, e);
        }
    }

    /**
     * Watch a directory for new or modified JAR files and auto-install programs.
     * Runs in a daemon thread. Uses ServiceLoader discovery within each JAR.
     *
     * @param region the region to install into
     * @param directory the directory to watch
     * @return a Runnable that stops the watcher when called
     */
    public static Runnable watch(CicsRegion region, Path directory) {
        Thread watcher = new Thread(() -> {
            try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                directory.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

                LOG.info("Watching " + directory + " for program deployments");

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = ws.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = directory.resolve((Path) event.context());
                        if (changed.toString().endsWith(".jar")) {
                            LOG.info("Detected change: " + changed.getFileName());
                            try {
                                installAll(region, changed);
                            } catch (Exception e) {
                                LOG.warning("Failed to load " + changed + ": " + e.getMessage());
                            }
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOG.severe("Watcher failed: " + e.getMessage());
            }
        }, "CicsRegion-deploy-watcher");
        watcher.setDaemon(true);
        watcher.start();

        return watcher::interrupt;
    }
}
