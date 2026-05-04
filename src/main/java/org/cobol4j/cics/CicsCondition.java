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
package org.cobol4j.cics;

/**
 * A CICS exceptional condition — thrown when a CICS command fails.
 * Maps to COBOL's HANDLE CONDITION / RESP checking.
 */
public class CicsCondition extends RuntimeException {

    private final String condition;

    public CicsCondition(String condition, String message) {
        super(condition + ": " + message);
        this.condition = condition;
    }

    /** The CICS condition name (e.g., NOTFND, DUPREC, PGMIDERR, FILENOTFOUND). */
    public String condition() { return condition; }
}
