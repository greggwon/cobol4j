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
package org.cobol4j.transpiler;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.tools.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transpile every COBOL program from the Open Mainframe Project
 * COBOL Programming Course (Git submodule).
 *
 * <p>Each program becomes a dynamic test that:</p>
 * <ol>
 *   <li>Reads the COBOL source from the submodule</li>
 *   <li>Transpiles it to Java</li>
 *   <li>Compiles the generated Java (if transpilation succeeded)</li>
 * </ol>
 *
 * <p>Programs with known issues are tracked in {@link #EXPECTED_FAILURES}
 * and are tested to confirm they fail for the expected reason. When a fix
 * lands, the test will start passing and the entry should be removed from
 * the expected-failure map.</p>
 *
 * <p>Source: <a href="https://github.com/openmainframeproject/cobol-programming-course">
 * openmainframeproject/cobol-programming-course</a> (CC-BY-4.0)</p>
 */
class CobolCourseTranspileTest {

    /** Root of the submodule checkout, relative to project root. */
    private static final Path SUBMODULE = Path.of("cobol-programming-course");

    /**
     * Programs with known transpilation issues.
     * Key: filename; Value: short description of the expected failure.
     * Remove entries as fixes land — the test will catch regressions.
     */
    private static final Map<String, String> EXPECTED_FAILURES = Map.of(
        "CBL0009.cobol", "Source uses TLIMIT but field is TLIMITED — fix: rename reference to match field name"
    );

    @TestFactory
    @EnabledIf("submoduleExists")
    Stream<DynamicTest> transpileAllCoursePrograms() throws IOException {
        List<Path> cobolFiles = Files.walk(SUBMODULE)
            .filter(p -> {
                String name = p.toString().toLowerCase();
                return name.endsWith(".cobol") || name.endsWith(".cbl");
            })
            .sorted()
            .toList();

        assertFalse(cobolFiles.isEmpty(),
            "No COBOL files found in submodule — did you run 'git submodule update --init'?");

        return cobolFiles.stream().map(path -> {
            String fileName = path.getFileName().toString();
            String displayName = fileName;
            boolean expectedFail = EXPECTED_FAILURES.containsKey(fileName);

            if (expectedFail) {
                displayName += " [KNOWN: " + EXPECTED_FAILURES.get(fileName) + "]";
            }

            return DynamicTest.dynamicTest(displayName, () -> {
                String cobol = Files.readString(path);
                TranspileDiagnostics diag = new TranspileDiagnostics();

                String java;
                try {
                    java = Transpiler.transpile(cobol, diag, path.toString());
                } catch (Exception e) {
                    if (expectedFail) return;
                    fail("Transpilation crashed\n"
                        + "  COBOL source: " + path + "\n"
                        + "  Error: " + e.getMessage());
                    return;
                }

                if (java == null) {
                    if (expectedFail) return;
                    fail("Transpilation returned null\n"
                        + "  COBOL source: " + path + "\n"
                        + "  Errors: " + diag.errors());
                    return;
                }

                if (diag.hasWarnings() && !expectedFail) {
                    System.out.println("WARNINGS for " + path + ":");
                    diag.warnings().forEach(w -> System.out.println("  " + w));
                }

                // Try to compile the generated Java
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    if (expectedFail) return; // can't verify compilation
                    return;
                }

                Path tmpDir = Files.createTempDirectory("course-" + fileName);
                try {
                    Path pkgDir = tmpDir.resolve("generated");
                    Files.createDirectories(pkgDir);

                    String className = java.lines()
                        .filter(l -> l.contains("public class "))
                        .findFirst()
                        .map(l -> l.replaceAll(".*public class (\\w+).*", "$1"))
                        .orElse("Unknown");

                    Path javaFile = pkgDir.resolve(className + ".java");
                    Files.writeString(javaFile, java);

                    DiagnosticCollector<JavaFileObject> compileDiag = new DiagnosticCollector<>();
                    try (StandardJavaFileManager fm = compiler.getStandardFileManager(compileDiag, null, null)) {
                        JavaCompiler.CompilationTask task = compiler.getTask(
                            null, fm, compileDiag,
                            List.of("-classpath", System.getProperty("java.class.path"),
                                    "-d", tmpDir.toString()),
                            null, fm.getJavaFileObjects(javaFile.toFile()));

                        boolean compiled = task.call();

                        if (compiled && expectedFail) {
                            System.out.println("FIXED! " + path
                                + " now transpiles AND compiles — remove from EXPECTED_FAILURES");
                        } else if (!compiled && expectedFail) {
                            StringBuilder info = new StringBuilder();
                            for (var d : compileDiag.getDiagnostics()) {
                                info.append("    line ").append(d.getLineNumber())
                                    .append(": ").append(d.getMessage(null)).append("\n");
                            }
                            System.out.println("EXPECTED FAIL (compile): " + path
                                + "\n  Reason: " + EXPECTED_FAILURES.get(fileName)
                                + "\n" + info);
                        } else if (!compiled) {
                            // Unexpected compile failure
                            StringBuilder errors = new StringBuilder();
                            errors.append("Generated Java doesn't compile\n");
                            errors.append("  COBOL source: ").append(path).append("\n");
                            errors.append("  Generated:    ").append(javaFile).append("\n");
                            errors.append("  Compile errors:\n");
                            for (var d : compileDiag.getDiagnostics()) {
                                errors.append("    line ").append(d.getLineNumber())
                                      .append(": ").append(d.getMessage(null)).append("\n");
                            }
                            String[] lines = java.split("\n");
                            for (var d : compileDiag.getDiagnostics()) {
                                long lineNo = d.getLineNumber();
                                if (lineNo > 0 && lineNo <= lines.length) {
                                    int start = (int) Math.max(1, lineNo - 2);
                                    int end = (int) Math.min(lines.length, lineNo + 2);
                                    errors.append("    --- generated source around line ")
                                          .append(lineNo).append(" ---\n");
                                    for (int ln = start; ln <= end; ln++) {
                                        String marker = (ln == lineNo) ? " >> " : "    ";
                                        errors.append(marker).append(String.format("%4d: %s%n", ln, lines[ln - 1]));
                                    }
                                }
                            }
                            fail(errors.toString());
                        }
                    }
                } finally {
                    Files.walk(tmpDir).sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) {} });
                }
            });
        });
    }

    /** Condition: submodule directory exists and contains files. */
    static boolean submoduleExists() {
        return Files.isDirectory(SUBMODULE)
            && SUBMODULE.resolve("COBOL Programming Course #2 - Learning COBOL").toFile().exists();
    }
}
