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

/**
 * Configuration derived from COBOL's SPECIAL-NAMES paragraph in the
 * ENVIRONMENT DIVISION.
 * <p>
 * Supports:
 * <ul>
 *   <li>{@code DECIMAL-POINT IS COMMA} — swaps the role of . and , in
 *       numeric literals and edited output (European format: 1.234,56
 *       instead of 1,234.56)</li>
 *   <li>{@code CURRENCY SIGN IS "x"} — changes the currency symbol used
 *       in PIC editing from $ to the specified character</li>
 * </ul>
 *
 * <pre>{@code
 * CobolConfig config = CobolConfig.defaults()
 *     .decimalPointIsComma(true)
 *     .currencySign('€');
 * }</pre>
 */
public final class CobolConfig {

    private boolean decimalPointIsComma = false;
    private char currencySign = '$';

    private CobolConfig() {}

    /** Create a config with standard US defaults (decimal='.', currency='$'). */
    public static CobolConfig defaults() {
        return new CobolConfig();
    }

    /** Set whether DECIMAL-POINT IS COMMA is active. */
    public CobolConfig decimalPointIsComma(boolean value) {
        this.decimalPointIsComma = value;
        return this;
    }

    /** Set the currency sign character (default '$'). */
    public CobolConfig currencySign(char sign) {
        this.currencySign = sign;
        return this;
    }

    /** Whether DECIMAL-POINT IS COMMA is active. */
    public boolean isDecimalPointComma() {
        return decimalPointIsComma;
    }

    /** The currency sign character. */
    public char currencySign() {
        return currencySign;
    }

    /** The character used as the decimal point (',' if DECIMAL-POINT IS COMMA, else '.'). */
    public char decimalChar() {
        return decimalPointIsComma ? ',' : '.';
    }

    /** The character used as the thousands separator ('.' if DECIMAL-POINT IS COMMA, else ','). */
    public char thousandsSeparator() {
        return decimalPointIsComma ? '.' : ',';
    }
}
