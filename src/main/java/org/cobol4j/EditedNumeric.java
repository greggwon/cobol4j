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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Formats a numeric value into a COBOL numeric-edited display string.
 * <p>
 * When a numeric value is MOVEd to a numeric-edited field (PIC with Z, *, $, +, -, etc.),
 * the result is a human-readable formatted string with zero suppression, floating signs,
 * insertion characters, etc.
 * <pre>
 * PIC Z(5)9.99   + 123.45    → "   123.45"
 * PIC $$$,$$9.99  + 1234.56   → "  $1,234.56"
 * PIC -(5)9.99    + -123.45   → "   -123.45"
 * PIC **,***,**9.99 + 1234.56 → "****1,234.56"
 * PIC Z(5)9       + 0         → "      0"
 * </pre>
 */
final class EditedNumeric {

    private EditedNumeric() {}

    /**
     * Format a value according to a numeric-edited PIC using default (US) config.
     *
     * @param value the numeric value to format
     * @param pic   the PIC clause (must be NUMERIC_EDITED)
     * @return the formatted display string, padded to pic.displaySize()
     */
    static String format(BigDecimal value, Pic pic) {
        return format(value, pic, CobolConfig.defaults());
    }

    /**
     * Format a value according to a numeric-edited PIC using the given config.
     *
     * @param value  the numeric value to format
     * @param pic    the PIC clause (must be NUMERIC_EDITED)
     * @param config the COBOL config (SPECIAL-NAMES settings)
     * @return the formatted display string, padded to pic.displaySize()
     */
    static String format(BigDecimal value, Pic pic, CobolConfig config) {
        String expanded = pic.expanded();
        boolean negative = value.signum() < 0;
        BigDecimal absValue = value.abs();

        // Determine decimal digits from the PIC
        int decDigits = pic.decimalDigits();
        int intDigits = pic.integerDigits();

        // Scale and extract digit string
        BigDecimal scaled = absValue.setScale(decDigits, RoundingMode.DOWN);
        String allDigits = scaled.movePointRight(decDigits)
            .toBigInteger().toString();

        int totalDigits = intDigits + decDigits;
        if (allDigits.length() < totalDigits) {
            allDigits = "0".repeat(totalDigits - allDigits.length()) + allDigits;
        } else if (allDigits.length() > totalDigits) {
            allDigits = allDigits.substring(allDigits.length() - totalDigits);
        }

        // Walk the expanded PIC and produce the output
        char[] result = new char[pic.displaySize()];
        int digitIdx = 0; // cursor into allDigits
        int outIdx = 0;   // cursor into result

        // Find the first significant (non-zero) digit position
        int firstSignificant = -1;
        for (int i = 0; i < allDigits.length(); i++) {
            if (allDigits.charAt(i) != '0') { firstSignificant = i; break; }
        }
        if (firstSignificant < 0) firstSignificant = allDigits.length() - 1; // at least last digit

        // Track whether we've passed the suppression zone
        boolean pastSuppression = false;

        // Track floating sign: count leading $, +, - in PIC
        char floatChar = 0;
        int floatCount = 0;
        for (int i = 0; i < expanded.length(); i++) {
            char c = expanded.charAt(i);
            if (c == '$' || c == '+' || c == '-') {
                if (floatChar == 0 || floatChar == c) {
                    floatChar = c;
                    floatCount++;
                } else break;
            } else if (c != 'S') break;
        }
        boolean hasFloatingSign = floatCount > 1;

        for (int i = 0; i < expanded.length(); i++) {
            char c = expanded.charAt(i);
            if (c == 'S') continue; // sign indicator, no output position

            switch (c) {
                case '9' -> {
                    result[outIdx++] = digitIdx < allDigits.length()
                        ? allDigits.charAt(digitIdx) : '0';
                    digitIdx++;
                    pastSuppression = true;
                }
                case 'Z' -> {
                    char d = digitIdx < allDigits.length()
                        ? allDigits.charAt(digitIdx) : '0';
                    if (!pastSuppression && digitIdx < firstSignificant) {
                        result[outIdx++] = ' ';
                    } else {
                        result[outIdx++] = d;
                        pastSuppression = true;
                    }
                    digitIdx++;
                }
                case '*' -> {
                    char d = digitIdx < allDigits.length()
                        ? allDigits.charAt(digitIdx) : '0';
                    if (!pastSuppression && digitIdx < firstSignificant) {
                        result[outIdx++] = '*';
                    } else {
                        result[outIdx++] = d;
                        pastSuppression = true;
                    }
                    digitIdx++;
                }
                case '$' -> {
                    if (hasFloatingSign && floatChar == '$') {
                        char d = digitIdx < allDigits.length()
                            ? allDigits.charAt(digitIdx) : '0';
                        if (!pastSuppression && digitIdx < firstSignificant) {
                            // Is this the position just before the first significant digit?
                            if (digitIdx == firstSignificant - 1 || i == floatCount - 1) {
                                result[outIdx++] = config.currencySign();
                                pastSuppression = true;
                            } else {
                                result[outIdx++] = ' ';
                            }
                        } else {
                            result[outIdx++] = d;
                            pastSuppression = true;
                        }
                        digitIdx++;
                    } else {
                        // Fixed $
                        result[outIdx++] = config.currencySign();
                    }
                }
                case '+' -> {
                    if (hasFloatingSign && floatChar == '+') {
                        char d = digitIdx < allDigits.length()
                            ? allDigits.charAt(digitIdx) : '0';
                        if (!pastSuppression && digitIdx < firstSignificant) {
                            if (digitIdx == firstSignificant - 1 || i == floatCount - 1) {
                                result[outIdx++] = negative ? '-' : '+';
                                pastSuppression = true;
                            } else {
                                result[outIdx++] = ' ';
                            }
                        } else {
                            result[outIdx++] = d;
                            pastSuppression = true;
                        }
                        digitIdx++;
                    } else {
                        // Fixed + sign
                        result[outIdx++] = negative ? '-' : '+';
                    }
                }
                case '-' -> {
                    if (hasFloatingSign && floatChar == '-') {
                        char d = digitIdx < allDigits.length()
                            ? allDigits.charAt(digitIdx) : '0';
                        if (!pastSuppression && digitIdx < firstSignificant) {
                            if (digitIdx == firstSignificant - 1 || i == floatCount - 1) {
                                result[outIdx++] = negative ? '-' : ' ';
                                pastSuppression = true;
                            } else {
                                result[outIdx++] = ' ';
                            }
                        } else {
                            result[outIdx++] = d;
                            pastSuppression = true;
                        }
                        digitIdx++;
                    } else {
                        // Fixed - sign
                        result[outIdx++] = negative ? '-' : ' ';
                    }
                }
                case 'V' -> {
                    // Implied decimal — no output character
                }
                case '.' -> {
                    result[outIdx++] = config.decimalChar();
                    pastSuppression = true; // decimals always show
                }
                case ',' -> {
                    if (!pastSuppression) {
                        result[outIdx++] = ' '; // suppress comma in suppressed zone
                    } else {
                        result[outIdx++] = config.thousandsSeparator();
                    }
                }
                case '/' -> result[outIdx++] = '/';
                case 'B' -> result[outIdx++] = ' ';
                case '0' -> result[outIdx++] = '0';
                default -> {
                    if (outIdx < result.length) result[outIdx++] = c;
                }
            }
        }

        return new String(result, 0, outIdx);
    }
}
