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
package org.cobol4j.runner;

import org.cobol4j.transpiler.TranspileDiagnostics;
import org.cobol4j.transpiler.Transpiler;

import javax.tools.*;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command-line tool that transpiles, compiles, and runs COBOL programs.
 * <pre>
 * # Transpile, compile, and run immediately
 * java -jar cobol4j.jar run CUSTORD.cbl
 *
 * # Transpile to Java source only
 * java -jar cobol4j.jar transpile CUSTORD.cbl [-o OutputDir]
 *
 * # Transpile, compile, and package as a standalone JAR
 * java -jar cobol4j.jar build CUSTORD.cbl [-o CUSTORD.jar]
 * </pre>
 */
public class CobolRunner {

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
            return;
        }

        String command = args[0].toLowerCase();
        String sourceFile = args[1];
        String outputPath = parseOption(args, "-o");

        try {
            switch (command) {
                case "run" -> run(sourceFile);
                case "transpile" -> transpile(sourceFile, outputPath);
                case "build" -> build(sourceFile, outputPath);
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  RUN — transpile, compile in-memory, execute immediately
    // ═══════════════════════════════════════════════════════════════

    public static void run(String sourceFile) throws Exception {
        System.out.println("cobol4j: Reading " + sourceFile);
        String cobolSource = Files.readString(Path.of(sourceFile));

        // Transpile with diagnostics
        System.out.println("cobol4j: Transpiling...");
        TranspileDiagnostics diag = new TranspileDiagnostics();
        String javaSource = Transpiler.transpile(cobolSource, diag);

        // Show all diagnostics
        if (!diag.isEmpty()) {
            System.err.println("cobol4j: Transpilation diagnostics:");
            diag.diagnostics().forEach(d -> System.err.println("  " + d));
        }
        if (diag.hasErrors()) {
            System.err.println("cobol4j: " + diag.errors().size() + " error(s) — cannot proceed.");
            System.err.println("cobol4j: All constructs must be translatable. "
                + "No partial output was generated.");
            System.exit(1);
            return;
        }
        if (diag.hasWarnings()) {
            System.err.println("cobol4j: " + diag.warnings().size()
                + " warning(s) — output generated but review needed.");
        }

        // Extract class name from generated source
        String className = extractClassName(javaSource);
        String fqn = "generated." + className;

        // Compile in-memory
        System.out.println("cobol4j: Compiling " + fqn + "...");
        Path tempDir = Files.createTempDirectory("cobol4j_");
        Path packageDir = tempDir.resolve("generated");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve(className + ".java");
        Files.writeString(javaFile, javaSource);

        compile(javaFile, tempDir);

        // Load and execute
        System.out.println("cobol4j: Running...");
        System.out.println("─".repeat(60));

        URLClassLoader loader = new URLClassLoader(
            new URL[]{tempDir.toUri().toURL()},
            CobolRunner.class.getClassLoader());

        Class<?> clazz = loader.loadClass(fqn);
        clazz.getMethod("main", String[].class)
             .invoke(null, (Object) new String[]{});

        System.out.println("─".repeat(60));
        System.out.println("cobol4j: Program completed.");

        // Cleanup
        loader.close();
        deleteRecursive(tempDir);
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRANSPILE — generate Java source file
    // ═══════════════════════════════════════════════════════════════

    public static void transpile(String sourceFile, String outputPath) throws Exception {
        String cobolSource = Files.readString(Path.of(sourceFile));
        String javaSource = Transpiler.transpile(cobolSource);
        String className = extractClassName(javaSource);

        Path outputDir;
        if (outputPath != null) {
            outputDir = Path.of(outputPath);
        } else {
            outputDir = Path.of(".").resolve("generated");
        }
        Files.createDirectories(outputDir);

        Path javaFile = outputDir.resolve(className + ".java");
        Files.writeString(javaFile, javaSource);
        System.out.println("cobol4j: Transpiled to " + javaFile);
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUILD — transpile, compile, package as JAR
    // ═══════════════════════════════════════════════════════════════

    public static void build(String sourceFile, String outputPath) throws Exception {
        String cobolSource = Files.readString(Path.of(sourceFile));
        System.out.println("cobol4j: Transpiling " + sourceFile + "...");
        String javaSource = Transpiler.transpile(cobolSource);
        String className = extractClassName(javaSource);
        String fqn = "generated." + className;

        // Write and compile
        Path tempDir = Files.createTempDirectory("cobol4j_build_");
        Path packageDir = tempDir.resolve("generated");
        Files.createDirectories(packageDir);
        Path javaFile = packageDir.resolve(className + ".java");
        Files.writeString(javaFile, javaSource);

        System.out.println("cobol4j: Compiling...");
        compile(javaFile, tempDir);

        // Determine output JAR path
        Path jarPath;
        if (outputPath != null) {
            jarPath = Path.of(outputPath);
        } else {
            String baseName = sourceFile.replaceAll("\\.[^.]+$", "");
            jarPath = Path.of(baseName + ".jar");
        }

        // Find cobol4j JAR location for Class-Path reference
        String cobol4jJar = findCobol4jJar();

        // Create JAR with manifest
        System.out.println("cobol4j: Packaging " + jarPath + "...");
        createJar(jarPath, tempDir, fqn, cobol4jJar);

        System.out.println("cobol4j: Built " + jarPath);
        System.out.println("cobol4j: Run with: java -jar " + jarPath);

        // Cleanup temp
        deleteRecursive(tempDir);
    }

    // ═══════════════════════════════════════════════════════════════
    //  INTERNAL — compilation, JAR creation, utilities
    // ═══════════════════════════════════════════════════════════════

    private static void compile(Path javaFile, Path outputDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                "No Java compiler available. Run with a JDK (not just a JRE).");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(diagnostics, null, null);

        Iterable<? extends JavaFileObject> compilationUnits =
            fileManager.getJavaFileObjects(javaFile.toFile());

        // Set classpath to include cobol4j classes
        String classpath = System.getProperty("java.class.path");
        List<String> options = List.of(
            "-d", outputDir.toString(),
            "-classpath", classpath,
            "-source", "17",
            "-target", "17"
        );

        JavaCompiler.CompilationTask task =
            compiler.getTask(null, fileManager, diagnostics, options, null, compilationUnits);

        boolean success = task.call();
        fileManager.close();

        if (!success) {
            StringBuilder errors = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
                errors.append("  Line ").append(diag.getLineNumber())
                      .append(": ").append(diag.getMessage(null)).append("\n");
            }
            throw new RuntimeException(errors.toString());
        }
    }

    private static void createJar(Path jarPath, Path classesDir,
                                   String mainClass, String cobol4jJar) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        if (cobol4jJar != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, cobol4jJar);
        }
        manifest.getMainAttributes().putValue("Created-By", "cobol4j transpiler");

        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(jarPath), manifest)) {
            // Walk the classes directory and add all .class files
            Path root = classesDir;
            Files.walk(root)
                .filter(p -> p.toString().endsWith(".class"))
                .forEach(classFile -> {
                    try {
                        String entryName = root.relativize(classFile).toString()
                            .replace(File.separatorChar, '/');
                        jar.putNextEntry(new JarEntry(entryName));
                        Files.copy(classFile, jar);
                        jar.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        }
    }

    private static String findCobol4jJar() {
        // Look for cobol4j on the current classpath
        String cp = System.getProperty("java.class.path");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.contains("cobol4j") && entry.endsWith(".jar")) {
                return Path.of(entry).getFileName().toString();
            }
        }
        // If running from target/classes (development), reference the expected JAR name
        return "cobol4j-0.1.0-SNAPSHOT.jar";
    }

    private static String extractClassName(String javaSource) {
        Matcher m = Pattern.compile("public class (\\w+)").matcher(javaSource);
        if (m.find()) return m.group(1);
        throw new IllegalStateException("Could not find class name in generated source");
    }

    private static String parseOption(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) return args[i + 1];
        }
        return null;
    }

    private static void deleteRecursive(Path dir) {
        try {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }

    private static void printUsage() {
        System.out.println("""
            cobol4j - COBOL to Java transpiler and runner

            Usage:
              java -jar cobol4j.jar run <source.cbl>
                  Transpile, compile, and execute immediately.

              java -jar cobol4j.jar transpile <source.cbl> [-o <output-dir>]
                  Transpile to Java source only.

              java -jar cobol4j.jar build <source.cbl> [-o <output.jar>]
                  Transpile, compile, and package as a runnable JAR.

            Examples:
              java -jar cobol4j.jar run CUSTORD.cbl
              java -jar cobol4j.jar transpile PAYROLL.cbl -o src/generated
              java -jar cobol4j.jar build REPORTS.cbl -o reports.jar
            """);
    }
}
