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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreprocessorTest {

    @Test
    void copySimple(@TempDir Path tempDir) throws Exception {
        // Write a copybook
        Files.writeString(tempDir.resolve("CUSTCPY.cpy"), """
               05 CUST-ID   PIC X(10).
               05 CUST-NAME PIC X(20).
            """);

        String source = """
            01 CUSTOMER.
               COPY CUSTCPY.
            """;

        String expanded = Preprocessor.process(source, List.of(tempDir));
        assertTrue(expanded.contains("CUST-ID"));
        assertTrue(expanded.contains("CUST-NAME"));
        assertFalse(expanded.contains("COPY CUSTCPY"));
    }

    @Test
    void copyWithReplacing(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("GENERIC.cpy"), """
               05 :TAG:-ID   PIC X(10).
               05 :TAG:-NAME PIC X(20).
            """);

        String source = """
            01 CUSTOMER.
               COPY GENERIC REPLACING ==:TAG:== BY ==CUST==.
            """;

        String expanded = Preprocessor.process(source, List.of(tempDir));
        assertTrue(expanded.contains("CUST-ID"));
        assertTrue(expanded.contains("CUST-NAME"));
        assertFalse(expanded.contains(":TAG:"));
    }

    @Test
    void copyNotFoundReportsError(@TempDir Path tempDir) {
        String source = "COPY NONEXISTENT.";
        TranspileDiagnostics diag = new TranspileDiagnostics();
        Preprocessor.process(source, List.of(tempDir), diag);
        assertTrue(diag.hasErrors());
    }

    @Test
    void nestedCopy(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("INNER.cpy"), "   05 INNER-FIELD PIC X(5).");
        Files.writeString(tempDir.resolve("OUTER.cpy"), """
               05 OUTER-FIELD PIC X(10).
               COPY INNER.
            """);

        String source = """
            01 REC.
               COPY OUTER.
            """;

        String expanded = Preprocessor.process(source, List.of(tempDir));
        assertTrue(expanded.contains("OUTER-FIELD"));
        assertTrue(expanded.contains("INNER-FIELD"));
    }

    @Test
    void infiniteRecursionDetected(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("LOOP.cpy"), "COPY LOOP.");

        String source = "COPY LOOP.";
        TranspileDiagnostics diag = new TranspileDiagnostics();
        Preprocessor.process(source, List.of(tempDir), diag);
        assertTrue(diag.hasErrors());
    }

    @Test
    void copyWithQuotedPath(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("mybook.cpy"), "   05 QUOTED-FIELD PIC X(5).");

        String source = """
            COPY "mybook.cpy".
            """;

        String expanded = Preprocessor.process(source, List.of(tempDir));
        assertTrue(expanded.contains("QUOTED-FIELD"));
    }

    @Test
    void multipleReplacingPairs(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("TMPL.cpy"), """
               05 :PFX:-:SFX: PIC X(10).
            """);

        String source = """
            COPY TMPL REPLACING ==:PFX:== BY ==CUST==
                               ==:SFX:== BY ==NAME==.
            """;

        String expanded = Preprocessor.process(source, List.of(tempDir));
        assertTrue(expanded.contains("CUST-NAME"));
        assertFalse(expanded.contains(":PFX:"));
        assertFalse(expanded.contains(":SFX:"));
    }

    @Test
    void noCopyStatementsPassesThrough() {
        String source = """
            01 CUSTOMER.
               05 CUST-ID PIC X(10).
            """;

        String expanded = Preprocessor.process(source, List.of());
        assertEquals(source, expanded);
    }

    @Test
    void caseInsensitiveCopyKeyword(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("LOWER.cpy"), "   05 LOW-FIELD PIC X(5).");

        String source = "copy LOWER.";

        String expanded = Preprocessor.process(source, List.of(tempDir));
        assertTrue(expanded.contains("LOW-FIELD"));
    }
}
