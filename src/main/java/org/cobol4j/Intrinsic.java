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
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * COBOL intrinsic functions.
 * <p>
 * These map to COBOL's {@code FUNCTION} keyword:
 * <pre>{@code
 * // COBOL:  MOVE FUNCTION CURRENT-DATE TO WS-DATE
 * // Java:   rec.move("WS-DATE", Intrinsic.currentDate());
 *
 * // COBOL:  COMPUTE LEN = FUNCTION LENGTH(WS-NAME)
 * // Java:   rec.compute("LEN", Intrinsic.length(rec, "WS-NAME"));
 *
 * // COBOL:  MOVE FUNCTION UPPER-CASE(WS-NAME) TO WS-UPPER
 * // Java:   rec.move("WS-UPPER", Intrinsic.upperCase(rec.getString("WS-NAME")));
 * }</pre>
 */
public final class Intrinsic {

    private static final DateTimeFormatter COBOL_DATE_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSZ");

    private Intrinsic() {}

    // ── Date/Time ───────────────────────────────────────────────────

    /**
     * FUNCTION CURRENT-DATE — returns 21-character date-time string.
     * Format: YYYYMMDDHHMMSSss+HHMM (year, month, day, hour, min, sec,
     * hundredths of second, timezone offset).
     */
    public static String currentDate() {
        LocalDateTime now = LocalDateTime.now();
        ZoneOffset offset = ZoneOffset.systemDefault().getRules()
            .getOffset(now);
        int totalMinutes = offset.getTotalSeconds() / 60;
        char sign = totalMinutes >= 0 ? '+' : '-';
        int absMin = Math.abs(totalMinutes);
        int offH = absMin / 60;
        int offM = absMin % 60;

        return String.format("%04d%02d%02d%02d%02d%02d%02d%c%02d%02d",
            now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
            now.getHour(), now.getMinute(), now.getSecond(),
            now.getNano() / 10_000_000,  // hundredths
            sign, offH, offM);
    }

    /**
     * FUNCTION WHEN-COMPILED — returns the compilation timestamp.
     * In transpiled code this would be set at build time; here we return
     * the current time as a placeholder.
     */
    public static String whenCompiled() {
        return currentDate();
    }

    // ── String functions ────────────────────────────────────────────

    /** FUNCTION LENGTH — byte length of a field. */
    public static Decimal length(Record record, String fieldName) {
        return Decimal.of(record.fieldDef(fieldName).size());
    }

    /** FUNCTION LENGTH — byte length of a field (int). */
    public static int lengthInt(Record record, String fieldName) {
        return record.fieldDef(fieldName).size();
    }

    /** FUNCTION LENGTH of a string. */
    public static int lengthInt(String value) {
        return value.length();
    }

    /** FUNCTION UPPER-CASE. */
    public static String upperCase(String value) {
        return value.toUpperCase();
    }

    /** FUNCTION LOWER-CASE. */
    public static String lowerCase(String value) {
        return value.toLowerCase();
    }

    /** FUNCTION REVERSE. */
    public static String reverse(String value) {
        return new StringBuilder(value).reverse().toString();
    }

    /** FUNCTION TRIM (both ends). */
    public static String trim(String value) {
        return value.trim();
    }

    /** FUNCTION TRIM LEADING. */
    public static String trimLeading(String value) {
        return value.stripLeading();
    }

    /** FUNCTION TRIM TRAILING. */
    public static String trimTrailing(String value) {
        return value.stripTrailing();
    }

    /**
     * FUNCTION NUMVAL — convert a display numeric string to a Decimal.
     * Handles leading/trailing spaces, embedded signs, decimal points.
     */
    public static Decimal numval(String value) {
        String s = value.trim().replace(" ", "");
        if (s.isEmpty()) return Decimal.ZERO;
        // Handle trailing sign: "123-" or "123+"
        if (s.endsWith("-")) {
            s = "-" + s.substring(0, s.length() - 1);
        } else if (s.endsWith("+")) {
            s = s.substring(0, s.length() - 1);
        }
        // Handle leading sign: "+123" or "-123" (already handled)
        try {
            return Decimal.wrap(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return Decimal.ZERO;
        }
    }

    /**
     * FUNCTION NUMVAL-C — like NUMVAL but also strips currency signs and commas.
     */
    public static Decimal numvalC(String value, String currencySign) {
        String s = value.trim()
            .replace(currencySign, "")
            .replace(",", "")
            .replace(" ", "");
        if (s.isEmpty()) return Decimal.ZERO;
        if (s.endsWith("-")) s = "-" + s.substring(0, s.length() - 1);
        else if (s.endsWith("+")) s = s.substring(0, s.length() - 1);
        try {
            return Decimal.wrap(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return Decimal.ZERO;
        }
    }

    /** FUNCTION NUMVAL-C with default currency "$". */
    public static Decimal numvalC(String value) {
        return numvalC(value, "$");
    }

    // ── Numeric functions ───────────────────────────────────────────

    /** FUNCTION MOD — modulo (always non-negative). */
    public static Decimal mod(Decimal a, Decimal b) {
        // COBOL MOD: a - b * FUNCTION INTEGER(a / b)
        BigDecimal av = a.toBigDecimal();
        BigDecimal bv = b.toBigDecimal();
        BigDecimal quotient = av.divide(bv, 0, RoundingMode.FLOOR);
        return Decimal.wrap(av.subtract(bv.multiply(quotient)));
    }

    /** FUNCTION REM — remainder (sign matches dividend). */
    public static Decimal rem(Decimal a, Decimal b) {
        return Decimal.wrap(a.toBigDecimal().remainder(b.toBigDecimal()));
    }

    /** FUNCTION INTEGER — greatest integer not exceeding the value (floor). */
    public static Decimal integer(Decimal value) {
        return Decimal.wrap(value.toBigDecimal().setScale(0, RoundingMode.FLOOR));
    }

    /** FUNCTION INTEGER-PART — truncate toward zero. */
    public static Decimal integerPart(Decimal value) {
        return Decimal.wrap(value.toBigDecimal().setScale(0, RoundingMode.DOWN));
    }

    /** FUNCTION ABS — absolute value. */
    public static Decimal abs(Decimal value) {
        return Decimal.wrap(value.toBigDecimal().abs());
    }

    /** FUNCTION MAX — maximum of a set of values. */
    public static Decimal max(Decimal... values) {
        Decimal max = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i].compareTo(max) > 0) max = values[i];
        }
        return max;
    }

    /** FUNCTION MIN — minimum of a set of values. */
    public static Decimal min(Decimal... values) {
        Decimal min = values[0];
        for (int i = 1; i < values.length; i++) {
            if (values[i].compareTo(min) < 0) min = values[i];
        }
        return min;
    }

    /** FUNCTION MEAN — arithmetic mean. */
    public static Decimal mean(Decimal... values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Decimal v : values) sum = sum.add(v.toBigDecimal());
        return Decimal.wrap(sum.divide(BigDecimal.valueOf(values.length), 18, RoundingMode.HALF_UP));
    }

    /** FUNCTION MEDIAN — middle value (sorts the array). */
    public static Decimal median(Decimal... values) {
        Decimal[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        BigDecimal mid1 = sorted[n / 2 - 1].toBigDecimal();
        BigDecimal mid2 = sorted[n / 2].toBigDecimal();
        return Decimal.wrap(mid1.add(mid2)
            .divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP));
    }

    /** FUNCTION ORD — ordinal position of a character (1-based). */
    public static int ord(char c) {
        return (int) c + 1; // COBOL ORD is 1-based
    }

    /** FUNCTION ORD — ordinal of the first character of a string. */
    public static int ord(String s) {
        return s.isEmpty() ? 0 : ord(s.charAt(0));
    }

    /** FUNCTION CHAR — character at ordinal position (1-based). */
    public static String charFunction(int ordinal) {
        return String.valueOf((char) (ordinal - 1));
    }

    /** FUNCTION SQRT — square root. */
    public static Decimal sqrt(Decimal value) {
        return Decimal.wrap(value.toBigDecimal().sqrt(MathContext.DECIMAL64));
    }

    /** FUNCTION LOG — natural logarithm. */
    public static Decimal log(Decimal value) {
        return Decimal.wrap(BigDecimal.valueOf(Math.log(value.toBigDecimal().doubleValue())));
    }

    /** FUNCTION LOG10 — base-10 logarithm. */
    public static Decimal log10(Decimal value) {
        return Decimal.wrap(BigDecimal.valueOf(Math.log10(value.toBigDecimal().doubleValue())));
    }
}
