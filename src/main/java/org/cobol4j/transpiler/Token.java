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

/**
 * A lexical token from COBOL source.
 */
public record Token(Type type, String value, int line) {

    public enum Type {
        /** A COBOL word: identifier or reserved word (e.g., MOVE, WS-FIELD, CUST-NAME). */
        WORD,
        /** A numeric literal (e.g., 100, 3.14, -5). */
        NUMBER,
        /** A string literal (e.g., "HELLO", 'WORLD'). */
        STRING,
        /** Period — sentence/statement terminator. */
        PERIOD,
        /** Left parenthesis. */
        LPAREN,
        /** Right parenthesis. */
        RPAREN,
        /** Equals sign or equality comparison. */
        EQUALS,
        /** Greater-than sign. */
        GREATER,
        /** Less-than sign. */
        LESS,
        /** Plus sign (in arithmetic expressions). */
        PLUS,
        /** Minus sign (in arithmetic expressions). */
        MINUS,
        /** Asterisk (multiply or comment). */
        STAR,
        /** Slash (divide). */
        SLASH,
        /** Colon (reference modification separator). */
        COLON,
        /** Comma (separator, usually ignored in COBOL). */
        COMMA,
        /** Semicolon (separator, ignored in COBOL). */
        SEMICOLON,
        /** End of input. */
        EOF
    }

    /** Case-insensitive check if this token is a specific word. */
    public boolean is(String word) {
        return type == Type.WORD && value.equalsIgnoreCase(word);
    }

    /** Check if this token is any of the given words. */
    public boolean isAny(String... words) {
        if (type != Type.WORD) return false;
        for (String w : words) {
            if (value.equalsIgnoreCase(w)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return type + "(" + value + ")@" + line;
    }
}
