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
import org.cobol4j.Pic;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Database-specific SQL generation for DDL operations.
 * <p>
 * Each dialect knows how to map COBOL PIC types to SQL column types
 * and how to generate CREATE TABLE, ALTER TABLE statements in the
 * syntax required by the target database.
 */
public interface SqlDialect {

    /** Map a COBOL FieldDef to its SQL column type (e.g., VARCHAR(20), INTEGER). */
    String mapType(FieldDef field);

    /** Generate a CREATE TABLE statement. */
    String createTable(String tableName, List<ColumnDef> columns, String primaryKey);

    /** Generate an ALTER TABLE ADD COLUMN statement. */
    String addColumn(String table, ColumnDef column);

    /** Generate an ALTER TABLE ALTER/MODIFY COLUMN statement. May not be supported by all DBs. */
    String alterColumn(String table, ColumnDef column);

    /** Generate an ALTER TABLE DROP COLUMN statement. */
    String dropColumn(String table, String columnName);

    // ── Factory methods ────────────────────────────────────────────

    /**
     * Auto-detect the dialect from a live connection's metadata.
     * Checks {@code connection.getMetaData().getDatabaseProductName()}.
     */
    static SqlDialect auto(Connection conn) {
        try {
            String product = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (product.contains("h2")) return h2();
            if (product.contains("sqlite")) return sqlite();
            if (product.contains("postgresql") || product.contains("postgres")) return postgres();
            if (product.contains("mysql") || product.contains("mariadb")) return mysql();
            if (product.contains("oracle")) return oracle();
            // Default to a generic ANSI SQL dialect
            return h2();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to detect SQL dialect", e);
        }
    }

    static SqlDialect h2() { return H2Dialect.INSTANCE; }
    static SqlDialect sqlite() { return SqliteDialect.INSTANCE; }
    static SqlDialect postgres() { return PostgresDialect.INSTANCE; }
    static SqlDialect mysql() { return MysqlDialect.INSTANCE; }
    static SqlDialect oracle() { return OracleDialect.INSTANCE; }
}

// ── H2 Dialect ────────────────────────────────────────────────────────

final class H2Dialect extends BaseDialect {
    static final H2Dialect INSTANCE = new H2Dialect();

    @Override
    public String alterColumn(String table, ColumnDef column) {
        return "ALTER TABLE " + table + " ALTER COLUMN " + column.name() + " " + column.sqlType();
    }
}

// ── SQLite Dialect ────────────────────────────────────────────────────

final class SqliteDialect extends BaseDialect {
    static final SqliteDialect INSTANCE = new SqliteDialect();

    @Override
    public String alterColumn(String table, ColumnDef column) {
        // SQLite does not support ALTER COLUMN — silently skip
        return null;
    }

    @Override
    public String dropColumn(String table, String columnName) {
        // SQLite added DROP COLUMN in 3.35.0, but may not be available
        return "ALTER TABLE " + table + " DROP COLUMN " + columnName;
    }
}

// ── PostgreSQL Dialect ────────────────────────────────────────────────

final class PostgresDialect extends BaseDialect {
    static final PostgresDialect INSTANCE = new PostgresDialect();

    @Override
    public String alterColumn(String table, ColumnDef column) {
        return "ALTER TABLE " + table + " ALTER COLUMN " + column.name()
                + " TYPE " + column.sqlType();
    }
}

// ── MySQL Dialect ─────────────────────────────────────────────────────

final class MysqlDialect extends BaseDialect {
    static final MysqlDialect INSTANCE = new MysqlDialect();

    @Override
    public String alterColumn(String table, ColumnDef column) {
        return "ALTER TABLE " + table + " MODIFY COLUMN " + column.name() + " " + column.sqlType();
    }
}

// ── Oracle Dialect ────────────────────────────────────────────────────

final class OracleDialect extends BaseDialect {
    static final OracleDialect INSTANCE = new OracleDialect();

    @Override
    public String alterColumn(String table, ColumnDef column) {
        return "ALTER TABLE " + table + " MODIFY (" + column.name() + " " + column.sqlType() + ")";
    }
}

// ── Base implementation shared across dialects ────────────────────────

abstract class BaseDialect implements SqlDialect {

    @Override
    public String mapType(FieldDef field) {
        Pic pic = field.pic();
        if (pic == null) return null; // group item — skip

        if (pic.isAlphanumeric()) {
            return "VARCHAR(" + pic.displaySize() + ")";
        }

        // Numeric
        int intDigits = pic.integerDigits();
        int decDigits = pic.decimalDigits();

        if (decDigits > 0) {
            return "DECIMAL(" + (intDigits + decDigits) + ", " + decDigits + ")";
        }

        // Integer-only numeric
        int totalDigits = intDigits;
        if (totalDigits <= 4) return "SMALLINT";
        if (totalDigits <= 9) return "INTEGER";
        return "BIGINT";
    }

    @Override
    public String createTable(String tableName, List<ColumnDef> columns, String primaryKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef col = columns.get(i);
            sb.append("    ").append(col.name()).append(" ").append(col.sqlType());
            if (!col.nullable()) sb.append(" NOT NULL");
            if (i < columns.size() - 1) sb.append(",");
            sb.append("\n");
        }
        if (primaryKey != null && !primaryKey.isEmpty()) {
            sb.append("    , PRIMARY KEY (").append(primaryKey).append(")\n");
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String addColumn(String table, ColumnDef column) {
        return "ALTER TABLE " + table + " ADD COLUMN " + column.name() + " " + column.sqlType();
    }

    @Override
    public String dropColumn(String table, String columnName) {
        return "ALTER TABLE " + table + " DROP COLUMN " + columnName;
    }
}
