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
import java.math.BigInteger;

/**
 * COMP-3 (packed decimal) codec.
 * <p>
 * Packed decimal stores two digits per byte, with the last nibble holding the sign.
 * Sign nibbles: {@code 0xC} = positive, {@code 0xD} = negative, {@code 0xF} = unsigned.
 * <p>
 * Example: the value +12345 in PIC S9(5) COMP-3 is stored as 3 bytes:
 * {@code [0x12, 0x34, 0x5C]}
 */
public final class PackedDecimal {

    private static final int SIGN_POSITIVE = 0x0C;
    private static final int SIGN_NEGATIVE = 0x0D;
    private static final int SIGN_UNSIGNED = 0x0F;

    private PackedDecimal() {} // utility class

    /**
     * Encode a BigDecimal into packed decimal bytes.
     *
     * @param value   the value to encode
     * @param pic     the field's PIC clause (defines digit count and scale)
     * @param bytes   target byte array
     * @param offset  starting offset in the target array
     * @param length  number of bytes allocated for this field
     * @return true if the value fit without truncation of integer digits
     */
    public static boolean encode(BigDecimal value, Pic pic, byte[] bytes, int offset, int length) {
        // Scale the value to remove the decimal point: 123.45 with scale 2 → 12345
        BigDecimal scaled = value.movePointRight(pic.decimalDigits());
        BigInteger unscaled = scaled.toBigInteger(); // truncates toward zero

        boolean negative = unscaled.signum() < 0;
        String digits = unscaled.abs().toString();

        int totalDigits = pic.totalDigits();
        boolean overflow = digits.length() > totalDigits;

        // Pad or truncate to the expected digit count
        if (digits.length() < totalDigits) {
            digits = "0".repeat(totalDigits - digits.length()) + digits;
        } else if (overflow) {
            // Truncate high-order digits (COBOL truncation behavior)
            digits = digits.substring(digits.length() - totalDigits);
        }

        // Pack: two digits per byte, sign in last nibble
        // Total nibbles = totalDigits + 1 (sign)
        // We fill from the right
        int byteIdx = offset + length - 1;

        // Last byte: last digit in high nibble, sign in low nibble
        int signNibble = !pic.isSigned() ? SIGN_UNSIGNED
                       : negative         ? SIGN_NEGATIVE
                       :                    SIGN_POSITIVE;
        int digitIdx = digits.length() - 1;
        bytes[byteIdx] = (byte) (((digits.charAt(digitIdx) - '0') << 4) | signNibble);
        digitIdx--;
        byteIdx--;

        // Remaining bytes: two digits per byte
        while (byteIdx >= offset && digitIdx >= 0) {
            int lo = digits.charAt(digitIdx) - '0';
            digitIdx--;
            int hi = digitIdx >= 0 ? (digits.charAt(digitIdx) - '0') : 0;
            digitIdx--;
            bytes[byteIdx] = (byte) ((hi << 4) | lo);
            byteIdx--;
        }

        // Zero-fill any remaining leading bytes
        while (byteIdx >= offset) {
            bytes[byteIdx] = 0x00;
            byteIdx--;
        }

        return !overflow;
    }

    /**
     * Decode packed decimal bytes into a BigDecimal.
     *
     * @param pic     the field's PIC clause
     * @param bytes   source byte array
     * @param offset  starting offset
     * @param length  number of bytes for this field
     * @return the decoded value with proper scale
     */
    public static BigDecimal decode(Pic pic, byte[] bytes, int offset, int length) {
        StringBuilder digits = new StringBuilder(pic.totalDigits());

        // Read all bytes except last: two digits each
        for (int i = offset; i < offset + length - 1; i++) {
            int b = bytes[i] & 0xFF;
            digits.append((char) ('0' + (b >> 4)));
            digits.append((char) ('0' + (b & 0x0F)));
        }

        // Last byte: high nibble is a digit, low nibble is sign
        int lastByte = bytes[offset + length - 1] & 0xFF;
        digits.append((char) ('0' + (lastByte >> 4)));
        int signNibble = lastByte & 0x0F;

        boolean negative = (signNibble == SIGN_NEGATIVE);

        // Trim to expected digit count (leading zeros from byte alignment)
        String digitStr = digits.toString();
        int expected = pic.totalDigits();
        if (digitStr.length() > expected) {
            digitStr = digitStr.substring(digitStr.length() - expected);
        }

        BigDecimal result = new BigDecimal(new BigInteger(digitStr), pic.decimalDigits());
        return negative ? result.negate() : result;
    }
}
