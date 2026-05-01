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

import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Immutable exact-decimal value — the safe numeric type for money and business math.
 * <p>
 * Wraps {@link BigDecimal} but enforces String-based construction to prevent
 * floating-point mantissa errors. All arithmetic produces new Decimal instances
 * (value-object semantics). Commonly-used values are cached via weak references
 * to reduce allocation pressure without leaking memory.
 * <p>
 * Provides an interception point for value tracking, audit logging, and debugging —
 * all arithmetic routes through methods that can be observed via {@link ValueTracker}.
 * <pre>{@code
 * Decimal price  = Decimal.of("19.99");
 * Decimal qty    = Decimal.of(5);
 * Decimal total  = price.multiply(qty);    // "99.95" — exact, no mantissa drift
 * Decimal tax    = total.multiply(Decimal.of("0.0825"));
 * Decimal grand  = total.add(tax);
 *
 * // Values are cached:
 * assertSame(Decimal.of("19.99"), Decimal.of("19.99")); // same instance from cache
 * }</pre>
 */
public final class Decimal implements Comparable<Decimal> {

    // ── Weak reference cache ────────────────────────────────────────
    private static final ConcurrentMap<String, WeakReference<Decimal>> CACHE =
        new ConcurrentHashMap<>(256);

    // ── Well-known constants (strongly referenced, never collected) ──
    public static final Decimal ZERO = internStrong("0");
    public static final Decimal ONE  = internStrong("1");
    public static final Decimal TEN  = internStrong("10");
    public static final Decimal HUNDRED = internStrong("100");

    // ── Value tracker (global, pluggable) ───────────────────────────
    private static volatile ValueTracker tracker = ValueTracker.NOOP;

    // ── Instance state ──────────────────────────────────────────────
    private final BigDecimal value;
    private final String canonical; // cache key

    private Decimal(BigDecimal value, String canonical) {
        this.value = value;
        this.canonical = canonical;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONSTRUCTION — always from String or long, never from double
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create a Decimal from a String representation.
     * This is the primary factory method — guarantees exact representation.
     * Results are weakly cached: repeated calls with the same string may
     * return the same instance.
     */
    public static Decimal of(String value) {
        if (value == null || value.isEmpty()) return ZERO;
        String key = value.trim();

        // Check cache first
        WeakReference<Decimal> ref = CACHE.get(key);
        if (ref != null) {
            Decimal cached = ref.get();
            if (cached != null) return cached;
        }

        // Create and cache
        Decimal d = new Decimal(new BigDecimal(key), key);
        CACHE.put(key, new WeakReference<>(d));
        tracker.onCreated(d);
        return d;
    }

    /** Create from a long — exact, no precision loss. */
    public static Decimal of(long value) {
        return of(Long.toString(value));
    }

    /** Create from an int — exact, no precision loss. */
    public static Decimal of(int value) {
        return of(Integer.toString(value));
    }

    /**
     * Wrap an existing BigDecimal. Use when you already have a BigDecimal
     * from a trusted source (e.g., JDBC ResultSet). The value is NOT
     * constructed from a double.
     */
    public static Decimal wrap(BigDecimal value) {
        if (value == null) return ZERO;
        return of(value.toPlainString());
    }

    // ═══════════════════════════════════════════════════════════════
    //  ARITHMETIC — returns new Decimal, tracks operations
    // ═══════════════════════════════════════════════════════════════

    public Decimal add(Decimal other) {
        Decimal result = wrap(value.add(other.value));
        tracker.onArithmetic(this, "+", other, result);
        return result;
    }

    public Decimal subtract(Decimal other) {
        Decimal result = wrap(value.subtract(other.value));
        tracker.onArithmetic(this, "-", other, result);
        return result;
    }

    public Decimal multiply(Decimal other) {
        Decimal result = wrap(value.multiply(other.value));
        tracker.onArithmetic(this, "*", other, result);
        return result;
    }

    /**
     * Divide with explicit scale (number of decimal places in the result).
     * Uses HALF_UP rounding by default.
     */
    public Decimal divide(Decimal other, int scale) {
        return divide(other, scale, RoundingMode.HALF_UP);
    }

    public Decimal divide(Decimal other, int scale, RoundingMode rounding) {
        Decimal result = wrap(value.divide(other.value, scale, rounding));
        tracker.onArithmetic(this, "/", other, result);
        return result;
    }

    /** Negate. */
    public Decimal negate() {
        return wrap(value.negate());
    }

    /** Absolute value. */
    public Decimal abs() {
        return wrap(value.abs());
    }

    /** Set scale (decimal places) with specified rounding. */
    public Decimal setScale(int scale, RoundingMode rounding) {
        return wrap(value.setScale(scale, rounding));
    }

    /** Remainder after division. */
    public Decimal remainder(Decimal divisor) {
        return wrap(value.remainder(divisor.value));
    }

    // ═══════════════════════════════════════════════════════════════
    //  COMPARISON
    // ═══════════════════════════════════════════════════════════════

    @Override
    public int compareTo(Decimal other) {
        return value.compareTo(other.value);
    }

    public boolean isZero()     { return value.signum() == 0; }
    public boolean isPositive() { return value.signum() > 0; }
    public boolean isNegative() { return value.signum() < 0; }

    public boolean greaterThan(Decimal other)    { return compareTo(other) > 0; }
    public boolean lessThan(Decimal other)       { return compareTo(other) < 0; }
    public boolean equalTo(Decimal other)        { return compareTo(other) == 0; }
    public boolean greaterOrEqual(Decimal other) { return compareTo(other) >= 0; }
    public boolean lessOrEqual(Decimal other)    { return compareTo(other) <= 0; }

    // ═══════════════════════════════════════════════════════════════
    //  CONVERSION
    // ═══════════════════════════════════════════════════════════════

    /** Get the underlying BigDecimal (for interop with APIs that require it). */
    public BigDecimal toBigDecimal() { return value; }

    /** String representation (plain, no scientific notation). */
    @Override
    public String toString() { return value.toPlainString(); }

    /** Integer value (truncates decimals). */
    public int toInt() { return value.intValue(); }

    /** Long value (truncates decimals). */
    public long toLong() { return value.longValue(); }

    /** Double value — USE ONLY FOR DISPLAY, never for further arithmetic. */
    public double toDouble() { return value.doubleValue(); }

    /** Number of decimal places in the current value. */
    public int scale() { return value.scale(); }

    // ═══════════════════════════════════════════════════════════════
    //  OBJECT IDENTITY
    // ═══════════════════════════════════════════════════════════════

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Decimal other)) return false;
        return value.compareTo(other.value) == 0;
    }

    @Override
    public int hashCode() {
        // Use stripTrailingZeros so 1.0 and 1.00 have same hash
        return value.stripTrailingZeros().hashCode();
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRACKING — pluggable observer for audit/debug
    // ═══════════════════════════════════════════════════════════════

    /** Set the global value tracker. */
    public static void setTracker(ValueTracker t) {
        tracker = t != null ? t : ValueTracker.NOOP;
    }

    /** Get the current tracker. */
    public static ValueTracker tracker() { return tracker; }

    // ── internal ─────────────────────────────��──────────────────────

    private static Decimal internStrong(String value) {
        Decimal d = new Decimal(new BigDecimal(value), value);
        CACHE.put(value, new WeakReference<>(d));
        return d;
    }
}
