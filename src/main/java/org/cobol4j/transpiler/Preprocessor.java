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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COBOL COPY/REPLACE preprocessor.
 * <p>
 * Resolves all {@code COPY} statements in the source text by locating the
 * referenced copybook file, optionally applying {@code REPLACING} text
 * substitutions, and inserting the result in place of the COPY statement.
 * <p>
 * Copybooks may themselves contain COPY statements (nested inclusion).
 * Infinite recursion is detected and reported as an error diagnostic.
 * <p>
 * Usage:
 * <pre>{@code
 * String expanded = Preprocessor.process(source, List.of(Path.of("copybooks/")));
 * }</pre>
 */
public final class Preprocessor {

    /** Maximum nesting depth to prevent infinite recursion. */
    private static final int MAX_DEPTH = 30;

    /**
     * Pattern to match a COPY statement.
     * Matches: COPY copybook-name [REPLACING ...] .
     * The COPY keyword is case-insensitive. The statement ends with a period.
     */
    private static final Pattern COPY_PATTERN = Pattern.compile(
        "(?i)\\bCOPY\\s+(\"[^\"]+\"|'[^']+'|[A-Za-z0-9_-]+)" +
        "(\\s+REPLACING\\s+.*?)?" +
        "\\s*\\.",
        Pattern.DOTALL
    );

    /**
     * Pattern to match pseudo-text replacement pairs: ==old== BY ==new==
     */
    private static final Pattern REPLACE_PAIR_PATTERN = Pattern.compile(
        "==([^=]*?)==\\s+BY\\s+==([^=]*?)==",
        Pattern.CASE_INSENSITIVE
    );

    private Preprocessor() {}

    /**
     * Process COBOL source, resolving all COPY statements.
     *
     * @param source        the raw COBOL source text
     * @param copybookPaths directories to search for copybooks (in order)
     * @return the fully expanded source with all COPYs resolved
     */
    public static String process(String source, List<Path> copybookPaths) {
        return process(source, copybookPaths, new TranspileDiagnostics());
    }

    /**
     * Process COBOL source with diagnostics collection.
     *
     * @param source        the raw COBOL source text
     * @param copybookPaths directories to search for copybooks (in order)
     * @param diag          diagnostics collector for errors and warnings
     * @return the fully expanded source with all COPYs resolved (best-effort on errors)
     */
    public static String process(String source, List<Path> copybookPaths,
                                  TranspileDiagnostics diag) {
        return expandCopy(source, copybookPaths, diag, new LinkedHashSet<>(), 0);
    }

    /**
     * Recursive expansion of COPY statements.
     *
     * @param source        source text to scan
     * @param copybookPaths search directories
     * @param diag          diagnostics collector
     * @param activeBooks   set of copybook names currently being expanded (recursion guard)
     * @param depth         current nesting depth
     * @return expanded source
     */
    private static String expandCopy(String source, List<Path> copybookPaths,
                                      TranspileDiagnostics diag,
                                      Set<String> activeBooks, int depth) {
        if (depth > MAX_DEPTH) {
            diag.error("PREPROCESSOR", 0, "COPY",
                "Maximum COPY nesting depth exceeded (" + MAX_DEPTH + ")");
            return source;
        }

        Matcher matcher = COPY_PATTERN.matcher(source);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            result.append(source, lastEnd, matcher.start());

            String rawName = matcher.group(1);
            String replacingClause = matcher.group(2);

            // Strip quotes if present
            String copybookName = stripQuotes(rawName);

            // Check for infinite recursion
            String normalizedName = copybookName.toUpperCase(Locale.ROOT);
            if (activeBooks.contains(normalizedName)) {
                diag.error("PREPROCESSOR", 0, "COPY " + copybookName,
                    "Infinite recursion detected — copybook '" + copybookName
                    + "' is already being expanded");
                lastEnd = matcher.end();
                continue;
            }

            // Resolve the copybook file
            Path resolved = resolveCopybook(copybookName, copybookPaths);
            if (resolved == null) {
                diag.error("PREPROCESSOR", 0, "COPY " + copybookName,
                    "Copybook '" + copybookName + "' not found in search paths: "
                    + copybookPaths);
                lastEnd = matcher.end();
                continue;
            }

            // Read the copybook content
            String content;
            try {
                content = Files.readString(resolved);
            } catch (IOException e) {
                diag.error("PREPROCESSOR", 0, "COPY " + copybookName,
                    "Failed to read copybook file '" + resolved + "': " + e.getMessage());
                lastEnd = matcher.end();
                continue;
            }

            // Apply REPLACING if present
            if (replacingClause != null && !replacingClause.isBlank()) {
                content = applyReplacing(content, replacingClause);
            }

            // Recurse to handle nested COPYs
            Set<String> newActiveBooks = new LinkedHashSet<>(activeBooks);
            newActiveBooks.add(normalizedName);
            content = expandCopy(content, copybookPaths, diag, newActiveBooks, depth + 1);

            result.append(content);
            lastEnd = matcher.end();
        }

        result.append(source, lastEnd, source.length());
        return result.toString();
    }

    /**
     * Strip surrounding quotes from a copybook name.
     */
    private static String stripQuotes(String name) {
        if ((name.startsWith("\"") && name.endsWith("\""))
            || (name.startsWith("'") && name.endsWith("'"))) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    /**
     * Search for a copybook file in the given directories.
     * Tries the name as-is, then with extensions .cpy, .CPY, .cbl.
     */
    private static Path resolveCopybook(String name, List<Path> searchPaths) {
        // Build candidate file names
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        // If name already has an extension (contains a dot), still try alternatives
        candidates.add(name + ".cpy");
        candidates.add(name + ".CPY");
        candidates.add(name + ".cbl");

        for (Path dir : searchPaths) {
            for (String candidate : candidates) {
                Path file = dir.resolve(candidate);
                if (Files.isRegularFile(file)) {
                    return file;
                }
            }
        }
        return null;
    }

    /**
     * Apply REPLACING substitutions to copybook content.
     * Parses pseudo-text pairs: ==old== BY ==new==
     */
    private static String applyReplacing(String content, String replacingClause) {
        Matcher pairMatcher = REPLACE_PAIR_PATTERN.matcher(replacingClause);
        while (pairMatcher.find()) {
            String oldText = pairMatcher.group(1).trim();
            String newText = pairMatcher.group(2).trim();
            if (!oldText.isEmpty()) {
                content = content.replace(oldText, newText);
            }
        }
        return content;
    }
}
