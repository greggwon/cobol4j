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
 * Observer for Decimal value lifecycle events — enables audit trails,
 * debug logging, and financial transaction tracking.
 * <p>
 * Plug in a tracker to see every value creation and every arithmetic operation:
 * <pre>{@code
 * Decimal.setTracker(new ValueTracker() {
 *     @Override
 *     public void onArithmetic(Decimal left, String op, Decimal right, Decimal result) {
 *         logger.info("{} {} {} = {}", left, op, right, result);
 *     }
 * });
 *
 * // Now all Decimal arithmetic is logged:
 * Decimal total = price.multiply(qty);  // logs: "19.99 * 5 = 99.95"
 * }</pre>
 * <p>
 * For financial systems, the tracker provides a complete audit trail of every
 * computation — which values were combined, what operation was performed, and
 * what the result was. This is the "money math traceability" that COBOL shops
 * require for regulatory compliance.
 */
public interface ValueTracker {

    /** Called when a new Decimal value is created via Decimal.of(). */
    default void onCreated(Decimal value) {}

    /**
     * Called on every arithmetic operation.
     *
     * @param left   the left operand
     * @param op     the operation symbol (+, -, *, /)
     * @param right  the right operand
     * @param result the computed result
     */
    default void onArithmetic(Decimal left, String op, Decimal right, Decimal result) {}

    /**
     * Called when a Variable's value changes (if wired through Variable).
     *
     * @param variableName the COBOL field name
     * @param oldValue     previous value (null if first assignment)
     * @param newValue     new value
     */
    default void onVariableChanged(String variableName, Decimal oldValue, Decimal newValue) {}

    /** No-op tracker — the default. Zero overhead when tracking is off. */
    ValueTracker NOOP = new ValueTracker() {};
}
