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
package org.cobol4j.schema;

import org.cobol4j.FieldDef;

/**
 * Definition of a SQL column derived from a COBOL field.
 * <p>
 * Handles the mapping from COBOL naming conventions (hyphens) to SQL
 * naming conventions (underscores), and from COBOL PIC types to SQL types.
 */
public record ColumnDef(String name, String sqlType, boolean nullable, boolean primaryKey) {

    /**
     * Create a ColumnDef from a COBOL FieldDef, using the given dialect for type mapping.
     */
    public static ColumnDef from(FieldDef field, SqlDialect dialect) {
        String colName = toColumnName(field.name());
        String sqlType = dialect.mapType(field);
        return new ColumnDef(colName, sqlType, true, false);
    }

    /**
     * Create a ColumnDef with primary key designation.
     */
    public ColumnDef withPrimaryKey(boolean pk) {
        return new ColumnDef(name, sqlType, pk ? false : nullable, pk);
    }

    /**
     * Create a ColumnDef with nullable designation.
     */
    public ColumnDef withNullable(boolean nullable) {
        return new ColumnDef(name, sqlType, nullable, primaryKey);
    }

    /**
     * Convert a COBOL field name to a SQL column name.
     * Replaces hyphens with underscores and converts to upper case.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code CUST-NAME} → {@code CUST_NAME}</li>
     *   <li>{@code ACCT-BALANCE} → {@code ACCT_BALANCE}</li>
     * </ul>
     */
    public static String toColumnName(String cobolName) {
        return cobolName.replace('-', '_').toUpperCase();
    }
}
