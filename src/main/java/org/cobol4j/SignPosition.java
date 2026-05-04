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
 * Represents the COBOL SIGN clause for DISPLAY numeric fields.
 * <p>
 * The SIGN clause determines how the sign of a numeric field is stored:
 * <ul>
 *   <li>{@link #TRAILING} — sign overpunch in the zone of the last byte (default)</li>
 *   <li>{@link #LEADING} — sign overpunch in the zone of the first byte</li>
 *   <li>{@link #TRAILING_SEPARATE} — separate '+' or '-' character after the digits (adds 1 byte)</li>
 *   <li>{@link #LEADING_SEPARATE} — separate '+' or '-' character before the digits (adds 1 byte)</li>
 * </ul>
 */
public enum SignPosition {

    /** Sign overpunch in the zone of the last byte (COBOL default). */
    TRAILING,

    /** Sign overpunch in the zone of the first byte. */
    LEADING,

    /** Separate '+' or '-' character after the digits. Adds 1 byte to storage. */
    TRAILING_SEPARATE,

    /** Separate '+' or '-' character before the digits. Adds 1 byte to storage. */
    LEADING_SEPARATE;

    /** Returns true if this sign position uses a separate sign character. */
    public boolean isSeparate() {
        return this == TRAILING_SEPARATE || this == LEADING_SEPARATE;
    }
}
