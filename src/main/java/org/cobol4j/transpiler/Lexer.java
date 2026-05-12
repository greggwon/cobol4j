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

import java.util.ArrayList;
import java.util.List;

/**
 * COBOL lexer — transforms source text into a stream of {@link Token}s.
 * <p>
 * Handles both fixed-format (columns 1-6 sequence, 7 indicator, 8-72 code, 73-80 ignored)
 * and free-format COBOL. Supports:
 * <ul>
 *   <li>Comments (* in column 7, or *> for inline comments)</li>
 *   <li>Continuation lines (- in column 7)</li>
 *   <li>String literals in single or double quotes</li>
 *   <li>COBOL identifiers with hyphens (e.g., WS-CUST-NAME)</li>
 *   <li>Numeric literals including decimals</li>
 *   <li>Period as sentence terminator</li>
 * </ul>
 */
public final class Lexer {

    private final String source;
    private final boolean fixedFormat;
    private final List<Token> tokens = new ArrayList<>();

    private Lexer(String source, boolean fixedFormat) {
        this.source = source;
        this.fixedFormat = fixedFormat;
    }

    /**
     * Tokenize COBOL source (auto-detect format based on content heuristics).
     */
    public static List<Token> tokenize(String source) {
        boolean fixed = detectFixedFormat(source);
        return new Lexer(source, fixed).doTokenize();
    }

    /** Tokenize with explicit format specification. */
    public static List<Token> tokenize(String source, boolean fixedFormat) {
        return new Lexer(source, fixedFormat).doTokenize();
    }

    private List<Token> doTokenize() {
        // Phase 1: Pre-process lines (handle columns, comments, continuations)
        List<String> processedLines = preprocess();

        // Phase 2: Tokenize the processed text
        for (int lineNum = 0; lineNum < processedLines.size(); lineNum++) {
            tokenizeLine(processedLines.get(lineNum), lineNum + 1);
        }
        tokens.add(new Token(Token.Type.EOF, "", tokens.isEmpty() ? 1
            : tokens.get(tokens.size() - 1).line()));

        return tokens;
    }

    // ── Phase 1: Line pre-processing ────────────────────────────────

    private List<String> preprocess() {
        String[] rawLines = source.split("\n", -1);
        List<String> result = new ArrayList<>();
        StringBuilder continued = null;

        for (String rawLine : rawLines) {
            String line = rawLine;

            if (fixedFormat) {
                // Skip lines shorter than 7 characters (blank/malformed)
                if (line.length() < 7) {
                    result.add("");
                    continue;
                }

                char indicator = line.charAt(6);

                // Comment line
                if (indicator == '*' || indicator == '/') {
                    result.add("");
                    continue;
                }

                // Extract code area (columns 8-72, 0-indexed: 7-71)
                int endCol = Math.min(line.length(), 72);
                String codeArea = line.substring(7, endCol);

                // Continuation line
                if (indicator == '-') {
                    if (continued != null) {
                        // Append, stripping leading spaces from continuation
                        continued.append(codeArea.stripLeading());
                    }
                    continue;
                }

                // Normal line
                if (continued != null) {
                    result.add(continued.toString());
                }
                continued = new StringBuilder(codeArea);
            } else {
                // Free format: just handle inline comments (*>)
                int commentIdx = line.indexOf("*>");
                if (commentIdx >= 0) {
                    line = line.substring(0, commentIdx);
                }
                if (continued != null) {
                    result.add(continued.toString());
                }
                continued = new StringBuilder(line);
            }
        }
        if (continued != null) {
            result.add(continued.toString());
        }
        return result;
    }

    // ── Phase 2: Tokenize a single processed line ───────────────────

    private void tokenizeLine(String line, int lineNum) {
        int i = 0;
        int len = line.length();

        while (i < len) {
            char c = line.charAt(i);

            // Skip whitespace
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            // Inline comment
            if (c == '*' && i + 1 < len && line.charAt(i + 1) == '>') {
                break; // rest of line is comment
            }

            // String literal
            if (c == '"' || c == '\'') {
                i = readStringLiteral(line, i, lineNum);
                continue;
            }

            // Period
            if (c == '.') {
                // .25 = decimal number (period followed by digit, preceded by whitespace)
                if (i + 1 < len && Character.isDigit(line.charAt(i + 1))
                    && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                    // Leading-decimal number literal: .25 means 0.25
                    i = readNumber(line, i, lineNum);
                    continue;
                }
                // Mid-number decimal: 100.50
                if (i + 1 < len && Character.isDigit(line.charAt(i + 1))
                    && i > 0 && Character.isDigit(line.charAt(i - 1))) {
                    // Part of a number — handled by the number reader
                    i++;
                    continue;
                }
                tokens.add(new Token(Token.Type.PERIOD, ".", lineNum));
                i++;
                continue;
            }

            // Punctuation
            switch (c) {
                case '(' -> { tokens.add(new Token(Token.Type.LPAREN, "(", lineNum)); i++; continue; }
                case ')' -> { tokens.add(new Token(Token.Type.RPAREN, ")", lineNum)); i++; continue; }
                case '=' -> { tokens.add(new Token(Token.Type.EQUALS, "=", lineNum)); i++; continue; }
                case '>' -> { tokens.add(new Token(Token.Type.GREATER, ">", lineNum)); i++; continue; }
                case '<' -> { tokens.add(new Token(Token.Type.LESS, "<", lineNum)); i++; continue; }
                case '+' -> { tokens.add(new Token(Token.Type.PLUS, "+", lineNum)); i++; continue; }
                case '*' -> { tokens.add(new Token(Token.Type.STAR, "*", lineNum)); i++; continue; }
                case '/' -> { tokens.add(new Token(Token.Type.SLASH, "/", lineNum)); i++; continue; }
                case ':' -> { tokens.add(new Token(Token.Type.COLON, ":", lineNum)); i++; continue; }
                case ',' -> { tokens.add(new Token(Token.Type.COMMA, ",", lineNum)); i++; continue; }
                case ';' -> { tokens.add(new Token(Token.Type.SEMICOLON, ";", lineNum)); i++; continue; }
            }

            // Minus: could be part of an identifier or a numeric sign
            if (c == '-') {
                // If previous token is a WORD, this might be part of that identifier
                // But in COBOL, standalone minus is rare in procedure division
                // We'll emit it as MINUS; the parser handles context
                tokens.add(new Token(Token.Type.MINUS, "-", lineNum));
                i++;
                continue;
            }

            // Number or numeric paragraph name (e.g., 1000-OPEN-FILES)
            if (Character.isDigit(c)) {
                // Peek ahead: if digits are followed by hyphen+letter, it's a WORD
                int peek = i;
                while (peek < len && Character.isDigit(line.charAt(peek))) peek++;
                if (peek < len && line.charAt(peek) == '-'
                    && peek + 1 < len && Character.isLetter(line.charAt(peek + 1))) {
                    // Numeric-prefixed paragraph name: treat as WORD
                    i = readWord(line, i, lineNum);
                } else {
                    i = readNumber(line, i, lineNum);
                }
                continue;
            }

            // Dollar sign — valid in PIC strings ($$,$$$,$$9.99)
            // Emit as a WORD token so the PIC parser can collect it
            if (c == '$') {
                StringBuilder sb = new StringBuilder();
                while (i < len && line.charAt(i) == '$') {
                    sb.append('$');
                    i++;
                }
                tokens.add(new Token(Token.Type.WORD, sb.toString(), lineNum));
                continue;
            }

            // Word (identifier or reserved word): starts with letter or digit, can contain hyphens
            if (Character.isLetter(c) || c == '_') {
                i = readWord(line, i, lineNum);
                continue;
            }

            // Unknown character — skip
            i++;
        }
    }

    private int readStringLiteral(String line, int start, int lineNum) {
        char quote = line.charAt(start);
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == quote) {
                // Check for escaped quote (doubled)
                if (i + 1 < line.length() && line.charAt(i + 1) == quote) {
                    sb.append(quote);
                    i += 2;
                } else {
                    i++; // consume closing quote
                    break;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        tokens.add(new Token(Token.Type.STRING, sb.toString(), lineNum));
        return i;
    }

    private int readNumber(String line, int start, int lineNum) {
        int i = start;
        StringBuilder sb = new StringBuilder();
        // Digits and decimal point (but not a trailing period that's a sentence terminator)
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
                i++;
            } else if (c == '.') {
                // Period is a decimal point only if followed by a digit
                if (i + 1 < line.length() && Character.isDigit(line.charAt(i + 1))) {
                    sb.append(c);
                    i++;
                } else {
                    break; // trailing period = sentence terminator, not decimal
                }
            } else {
                break;
            }
        }
        tokens.add(new Token(Token.Type.NUMBER, sb.toString(), lineNum));
        return i;
    }

    private int readWord(String line, int start, int lineNum) {
        int i = start;
        StringBuilder sb = new StringBuilder();
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                sb.append(c);
                i++;
            } else {
                break;
            }
        }
        // Trim trailing hyphens (shouldn't happen in valid COBOL but safety)
        String word = sb.toString();
        while (word.endsWith("-")) word = word.substring(0, word.length() - 1);
        tokens.add(new Token(Token.Type.WORD, word.toUpperCase(), lineNum));
        return i;
    }

    // ── Format detection ────────────────────────────────────────────

    private static boolean detectFixedFormat(String source) {
        // Heuristic: if multiple lines have characters in columns 1-6 that look
        // like sequence numbers, or column 7 has indicators, it's fixed format.
        String[] lines = source.split("\n");
        int fixedScore = 0;
        int checked = 0;
        for (String line : lines) {
            if (line.length() >= 7 && checked < 20) {
                checked++;
                char col7 = line.charAt(6);
                if (col7 == ' ' || col7 == '*' || col7 == '-' || col7 == '/') {
                    // Check if cols 1-6 are digits or spaces
                    String seq = line.substring(0, 6);
                    if (seq.matches("[0-9 ]{6}")) {
                        fixedScore++;
                    }
                }
            }
        }
        return checked > 0 && fixedScore > checked / 2;
    }
}
