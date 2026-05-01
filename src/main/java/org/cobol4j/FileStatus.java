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
 * COBOL FILE STATUS codes — the two-character status returned after every I/O operation.
 * <p>
 * Status codes follow the COBOL standard:
 * <ul>
 *   <li>{@code "00"} — successful</li>
 *   <li>{@code "10"} — end of file</li>
 *   <li>{@code "22"} — duplicate key</li>
 *   <li>{@code "23"} — record not found</li>
 *   <li>{@code "35"} — file not found on OPEN</li>
 * </ul>
 */
public final class FileStatus {

    public static final String SUCCESS          = "00";
    public static final String END_OF_FILE      = "10";
    public static final String DUPLICATE_KEY    = "22";
    public static final String RECORD_NOT_FOUND = "23";
    public static final String BOUNDARY_ERROR   = "24";
    public static final String FILE_NOT_FOUND   = "35";
    public static final String PERMISSION_ERROR = "37";
    public static final String LOGIC_ERROR      = "41";
    public static final String IO_ERROR         = "30";

    private String code;

    public FileStatus() {
        this.code = SUCCESS;
    }

    public String code() { return code; }

    public void set(String code) { this.code = code; }

    public boolean isSuccess()    { return SUCCESS.equals(code); }
    public boolean isEndOfFile()  { return END_OF_FILE.equals(code); }
    public boolean is(String expected) { return expected.equals(code); }

    /** Category check: successful (0x), end of file (1x), invalid key (2x), etc. */
    public char category() { return code.charAt(0); }

    @Override
    public String toString() { return code; }
}
