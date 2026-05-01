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

import java.util.*;

/**
 * Immutable definition of a single COBOL data item (field or group).
 * <p>
 * Corresponds to a DATA DIVISION entry like:
 * <pre>
 *   05 CUST-NAME PIC X(20).
 * </pre>
 * A group item (no PIC) contains children and spans their combined storage.
 * An elementary item has a PIC and occupies a specific byte range in the record buffer.
 */
public final class FieldDef {

    private final String name;
    private final int level;
    private final Pic pic;          // null for group items
    private final Usage usage;
    private final int offset;       // byte offset within the record buffer
    private final int size;         // byte size in the buffer
    private final boolean group;
    private final String redefines; // name of redefined field, or null
    private final int occurs;       // 0 = not an array; >0 = OCCURS count
    private final int stride;       // byte size of one occurrence (for OCCURS)

    private final List<FieldDef> children;
    private final Map<String, Condition> conditions;

    FieldDef(String name, int level, Pic pic, Usage usage,
             int offset, int size, boolean group, String redefines,
             int occurs, int stride,
             List<FieldDef> children, Map<String, Condition> conditions) {
        this.name = name;
        this.level = level;
        this.pic = pic;
        this.usage = usage;
        this.offset = offset;
        this.size = size;
        this.group = group;
        this.redefines = redefines;
        this.occurs = occurs;
        this.stride = stride;
        this.children = List.copyOf(children);
        this.conditions = Map.copyOf(conditions);
    }

    // ── Accessors ───────────────────────────────────────────────────

    public String name()                     { return name; }
    public int level()                       { return level; }
    public Pic pic()                         { return pic; }
    public Usage usage()                     { return usage; }
    public int offset()                      { return offset; }
    public int size()                        { return size; }
    public boolean isGroup()                 { return group; }
    public boolean isElementary()            { return !group; }
    public String redefines()                { return redefines; }
    public int occurs()                      { return occurs; }
    public int stride()                      { return stride; }
    public boolean isArray()                 { return occurs > 0; }
    public List<FieldDef> children()         { return children; }
    public Map<String, Condition> conditions(){ return conditions; }

    /**
     * For numeric fields, whether the PIC implies exact decimal semantics.
     */
    public boolean isNumeric() {
        return pic != null && pic.isNumeric();
    }

    /**
     * For the given occurrence index (0-based), compute the byte offset.
     * For non-OCCURS fields, returns the base offset.
     */
    public int offsetForIndex(int index) {
        if (!isArray()) return offset;
        if (index < 0 || index >= occurs) {
            throw new IndexOutOfBoundsException(
                "Index " + index + " out of bounds for OCCURS " + occurs
                + " field " + name);
        }
        return offset + (index * stride);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%02d %s", level, name));
        if (pic != null) sb.append(" ").append(pic.source());
        if (usage != Usage.DISPLAY) sb.append(" ").append(usage);
        if (occurs > 0) sb.append(" OCCURS ").append(occurs);
        if (redefines != null) sb.append(" REDEFINES ").append(redefines);
        sb.append(String.format(" [@%d:%d]", offset, size));
        return sb.toString();
    }
}
