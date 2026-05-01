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
package org.cobol4j;

import java.util.Objects;

/**
 * Parses and represents a COBOL PICTURE clause.
 * <p>
 * A PIC clause defines a field's data type, size, and display format.
 * Examples:
 * <ul>
 *   <li>{@code PIC X(20)}      — 20-char alphanumeric</li>
 *   <li>{@code PIC S9(7)V99}   — signed numeric, 7 integer + 2 decimal digits</li>
 *   <li>{@code PIC Z(5)9.99}   — numeric edited with zero suppression</li>
 * </ul>
 */
public final class Pic {

    // ── Field category ──────────────────────────────────────────────

    public enum Category {
        ALPHABETIC,
        ALPHANUMERIC,
        NUMERIC,
        NUMERIC_EDITED,
        ALPHANUMERIC_EDITED
    }

    // ── Instance state ──────────────────────────────────────────────

    private final String source;
    private final String expanded;
    private final Category category;
    private final boolean signed;
    private final int integerDigits;
    private final int decimalDigits;
    private final int displaySize;

    private Pic(String source, String expanded, Category category,
                boolean signed, int integerDigits, int decimalDigits,
                int displaySize) {
        this.source = source;
        this.expanded = expanded;
        this.category = category;
        this.signed = signed;
        this.integerDigits = integerDigits;
        this.decimalDigits = decimalDigits;
        this.displaySize = displaySize;
    }

    // ── Parsing ─────────────────────────────────────────────────────

    /**
     * Parse a COBOL PIC string into its structural components.
     *
     * @param picString e.g. "S9(7)V99", "X(20)", "Z(5)9.99"
     * @return parsed Pic metadata
     */
    public static Pic parse(String picString) {
        Objects.requireNonNull(picString, "PIC string must not be null");
        String src = picString.toUpperCase().trim();
        String expanded = expand(src);

        boolean signed = false;
        int idx = 0;
        if (!expanded.isEmpty() && expanded.charAt(0) == 'S') {
            signed = true;
            idx = 1;
        }

        boolean hasX = false, hasA = false, has9 = false;
        boolean hasV = false, hasEditChars = false;
        int intDigits = 0, decDigits = 0;
        boolean afterDecimal = false;
        int dSize = 0;

        for (int i = idx; i < expanded.length(); i++) {
            char c = expanded.charAt(i);
            switch (c) {
                case 'X' -> { hasX = true; dSize++; }
                case 'A' -> { hasA = true; dSize++; }
                case '9' -> {
                    has9 = true;
                    if (afterDecimal) decDigits++; else intDigits++;
                    dSize++;
                }
                case 'V' -> {
                    hasV = true;
                    afterDecimal = true;
                    // implied decimal — no storage
                }
                case 'P' -> {
                    // scaling position — counts as a digit position but no storage
                    if (afterDecimal) decDigits++; else intDigits++;
                }
                case 'Z', '*' -> {
                    hasEditChars = true;
                    if (afterDecimal) decDigits++; else intDigits++;
                    dSize++;
                }
                case '.', ',', '/', 'B', '0' -> {
                    hasEditChars = true;
                    dSize++; // insertion characters occupy storage
                }
                case '$', '+', '-' -> {
                    hasEditChars = true;
                    // can be fixed or floating; each position takes a byte
                    // and represents a potential digit position
                    if (afterDecimal) decDigits++; else intDigits++;
                    dSize++;
                }
                default -> {
                    // ignore unrecognized (CR, DB handled at a higher level later)
                }
            }
        }

        Category cat = classify(hasX, hasA, has9, hasEditChars);

        return new Pic(src, expanded, cat, signed, intDigits, decDigits, dSize);
    }

    private static Category classify(boolean hasX, boolean hasA, boolean has9,
                                     boolean hasEdit) {
        if (hasEdit) {
            return (hasX || hasA) ? Category.ALPHANUMERIC_EDITED
                                 : Category.NUMERIC_EDITED;
        }
        if (hasX)                return Category.ALPHANUMERIC;
        if (hasA && has9)        return Category.ALPHANUMERIC;
        if (hasA)                return Category.ALPHABETIC;
        return Category.NUMERIC;
    }

    /**
     * Expand repetition notation: {@code 9(7)} → {@code 9999999}.
     */
    static String expand(String pic) {
        StringBuilder sb = new StringBuilder(pic.length() * 2);
        int i = 0;
        while (i < pic.length()) {
            char c = pic.charAt(i);
            if (i + 1 < pic.length() && pic.charAt(i + 1) == '(') {
                int close = pic.indexOf(')', i + 2);
                if (close < 0) {
                    throw new IllegalArgumentException(
                        "Unclosed parenthesis in PIC: " + pic);
                }
                int count = Integer.parseInt(pic.substring(i + 2, close));
                sb.append(String.valueOf(c).repeat(count));
                i = close + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // ── Accessors ───────────────────────────────────────────────────

    public String source()        { return source; }
    public String expanded()      { return expanded; }
    public Category category()    { return category; }
    public boolean isSigned()     { return signed; }
    public int integerDigits()    { return integerDigits; }
    public int decimalDigits()    { return decimalDigits; }
    public int totalDigits()      { return integerDigits + decimalDigits; }
    public int displaySize()      { return displaySize; }

    public boolean isNumeric() {
        return category == Category.NUMERIC || category == Category.NUMERIC_EDITED;
    }

    public boolean isAlphanumeric() {
        return category == Category.ALPHANUMERIC || category == Category.ALPHABETIC
            || category == Category.ALPHANUMERIC_EDITED;
    }

    // ── Storage sizing ──────────────────────────────────────────────

    /**
     * Calculate byte-storage size for a given {@link Usage}.
     */
    public int storageSize(Usage usage) {
        if (!isNumeric() || usage == Usage.DISPLAY) {
            return displaySize;
        }
        int digits = totalDigits();
        return switch (usage) {
            case COMP3 -> (digits + 2) / 2;          // packed: (digits+sign_nibble)/2 rounded up
            case COMP, COMP4, COMP5 -> binarySize(digits);
            default -> displaySize;
        };
    }

    private static int binarySize(int digits) {
        if (digits <= 4)  return 2;   // halfword
        if (digits <= 9)  return 4;   // fullword
        return 8;                      // doubleword
    }

    // ── Object basics ───────────────────────────────────────────────

    @Override
    public String toString() {
        return "PIC " + source + " [" + category + ", "
            + (signed ? "signed, " : "")
            + integerDigits + "." + decimalDigits
            + ", display=" + displaySize + "]";
    }
}
