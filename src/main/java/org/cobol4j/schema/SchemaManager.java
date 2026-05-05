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

import org.cobol4j.ConnectionFactory;
import org.cobol4j.FieldDef;
import org.cobol4j.Record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Schema manager: maps COBOL Record definitions to SQL tables, tracks versions,
 * and performs auto-migration when Record definitions change.
 * <p>
 * Usage:
 * <pre>{@code
 * SchemaManager schema = SchemaManager.using(factory)
 *     .table("CUSTOMERS", customerRecord, cfg -> cfg.primaryKey("CUST-ID"))
 *     .table("ORDERS", orderRecord, cfg -> cfg.primaryKey("ORDER-ID"))
 *     .build();
 *
 * schema.migrate();  // creates/alters tables as needed
 *
 * RecordStore customers = schema.store("CUSTOMERS");
 * SqlSession.work(factory, session -> {
 *     customers.insert(session, rec);
 * });
 * }</pre>
 */
public final class SchemaManager {

    private static final String META_TABLE = "COBOL4J_SCHEMA";

    private final ConnectionFactory factory;
    private final SqlDialect dialect;
    private final Map<String, TableDef> tables;

    private SchemaManager(ConnectionFactory factory, SqlDialect dialect, Map<String, TableDef> tables) {
        this.factory = factory;
        this.dialect = dialect;
        this.tables = tables;
    }

    /** Begin building a SchemaManager with the given ConnectionFactory. */
    public static Builder using(ConnectionFactory factory) {
        return new Builder(factory);
    }

    /**
     * Check all managed tables and apply any needed migrations.
     * <ol>
     *   <li>Ensure COBOL4J_SCHEMA metadata table exists</li>
     *   <li>For each managed table, compare current Record definition against stored schema</li>
     *   <li>CREATE new tables, ALTER existing tables as needed</li>
     *   <li>Track all schema versions in COBOL4J_SCHEMA</li>
     * </ol>
     */
    public void migrate() {
        Connection conn = factory.acquire();
        try {
            SqlDialect effectiveDialect = this.dialect != null ? this.dialect : SqlDialect.auto(conn);
            ensureMetaTable(conn, effectiveDialect);

            for (Map.Entry<String, TableDef> entry : tables.entrySet()) {
                String tableName = entry.getKey();
                TableDef tableDef = entry.getValue();
                migrateTable(conn, tableName, tableDef, effectiveDialect);
            }

            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("Schema migration failed", e);
        } finally {
            factory.release(conn);
        }
    }

    /** Get a RecordStore for CRUD operations on a specific table. */
    public RecordStore store(String tableName) {
        TableDef def = tables.get(tableName);
        if (def == null) {
            throw new IllegalArgumentException("No managed table: " + tableName);
        }
        SqlDialect effectiveDialect = resolveDialect();
        return new RecordStore(tableName, def.record, def.mappedFields(effectiveDialect),
                def.primaryKeyFields, effectiveDialect);
    }

    /** Drop all managed tables (for testing). */
    public void dropAll() {
        Connection conn = factory.acquire();
        try {
            Statement stmt = conn.createStatement();
            for (String tableName : tables.keySet()) {
                try {
                    stmt.execute("DROP TABLE IF EXISTS " + tableName);
                } catch (SQLException ignored) {}
            }
            try {
                stmt.execute("DROP TABLE IF EXISTS " + META_TABLE);
            } catch (SQLException ignored) {}
            stmt.close();
            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw new RuntimeException("Failed to drop tables", e);
        } finally {
            factory.release(conn);
        }
    }

    // ── Internal migration logic ─────────────────────────────────────

    private void ensureMetaTable(Connection conn, SqlDialect dialect) throws SQLException {
        if (tableExists(conn, META_TABLE)) return;

        String createSql = "CREATE TABLE " + META_TABLE + " (\n"
                + "    TABLE_NAME  VARCHAR(128) PRIMARY KEY,\n"
                + "    VERSION     INTEGER NOT NULL,\n"
                + "    RECORD_HASH VARCHAR(64) NOT NULL,\n"
                + "    APPLIED_AT  TIMESTAMP NOT NULL,\n"
                + "    COLUMNS     VARCHAR(4000)\n"
                + ")";
        Statement stmt = conn.createStatement();
        stmt.execute(createSql);
        stmt.close();
    }

    private void migrateTable(Connection conn, String tableName, TableDef tableDef,
                              SqlDialect dialect) throws SQLException {
        List<FieldDef> fields = tableDef.mappedFields(dialect);
        String currentHash = computeHash(fields);
        String columnsJson = buildColumnsJson(fields, dialect);

        // Read existing schema entry
        SchemaEntry existing = readSchemaEntry(conn, tableName);

        if (existing == null) {
            // No entry: create the table
            createTable(conn, tableName, tableDef, fields, dialect);
            insertSchemaEntry(conn, tableName, 1, currentHash, columnsJson);
        } else if (!existing.recordHash.equals(currentHash)) {
            // Hash differs: compare and alter
            alterTable(conn, tableName, tableDef, fields, existing, dialect);
            updateSchemaEntry(conn, tableName, existing.version + 1, currentHash, columnsJson);
        }
        // else: no change, nothing to do
    }

    private void createTable(Connection conn, String tableName, TableDef tableDef,
                             List<FieldDef> fields, SqlDialect dialect) throws SQLException {
        List<ColumnDef> columns = new ArrayList<>();
        for (FieldDef f : fields) {
            ColumnDef col = ColumnDef.from(f, dialect);
            if (tableDef.primaryKeyFields.contains(f.name())) {
                col = col.withPrimaryKey(true);
            }
            columns.add(col);
        }

        String pkClause = null;
        if (!tableDef.primaryKeyFields.isEmpty()) {
            StringBuilder pk = new StringBuilder();
            boolean first = true;
            for (String pkField : tableDef.primaryKeyFields) {
                if (!first) pk.append(", ");
                pk.append(ColumnDef.toColumnName(pkField));
                first = false;
            }
            pkClause = pk.toString();
        }

        String sql = dialect.createTable(tableName, columns, pkClause);
        Statement stmt = conn.createStatement();
        stmt.execute(sql);
        stmt.close();
    }

    private void alterTable(Connection conn, String tableName, TableDef tableDef,
                            List<FieldDef> currentFields, SchemaEntry existing,
                            SqlDialect dialect) throws SQLException {
        // Parse existing columns from the stored JSON
        Set<String> existingColumns = parseColumnNames(existing.columnsJson);

        Statement stmt = conn.createStatement();
        for (FieldDef f : currentFields) {
            String colName = ColumnDef.toColumnName(f.name());
            if (!existingColumns.contains(colName)) {
                // New column — ADD
                ColumnDef col = ColumnDef.from(f, dialect);
                String alterSql = dialect.addColumn(tableName, col);
                if (alterSql != null) {
                    stmt.execute(alterSql);
                }
            }
        }
        stmt.close();
    }

    // ── Schema entry CRUD ────────────────────────────────────────────

    private SchemaEntry readSchemaEntry(Connection conn, String tableName) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "SELECT VERSION, RECORD_HASH, COLUMNS FROM " + META_TABLE + " WHERE TABLE_NAME = ?");
        ps.setString(1, tableName);
        ResultSet rs = ps.executeQuery();
        SchemaEntry entry = null;
        if (rs.next()) {
            entry = new SchemaEntry(
                    rs.getInt("VERSION"),
                    rs.getString("RECORD_HASH"),
                    rs.getString("COLUMNS")
            );
        }
        rs.close();
        ps.close();
        return entry;
    }

    private void insertSchemaEntry(Connection conn, String tableName,
                                   int version, String hash, String columnsJson) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + META_TABLE + " (TABLE_NAME, VERSION, RECORD_HASH, APPLIED_AT, COLUMNS) "
                        + "VALUES (?, ?, ?, ?, ?)");
        ps.setString(1, tableName);
        ps.setInt(2, version);
        ps.setString(3, hash);
        ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
        ps.setString(5, columnsJson);
        ps.executeUpdate();
        ps.close();
    }

    private void updateSchemaEntry(Connection conn, String tableName,
                                   int version, String hash, String columnsJson) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + META_TABLE + " SET VERSION = ?, RECORD_HASH = ?, APPLIED_AT = ?, COLUMNS = ? "
                        + "WHERE TABLE_NAME = ?");
        ps.setInt(1, version);
        ps.setString(2, hash);
        ps.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
        ps.setString(4, columnsJson);
        ps.setString(5, tableName);
        ps.executeUpdate();
        ps.close();
    }

    // ── Utility methods ──────────────────────────────────────────────

    private boolean tableExists(Connection conn, String tableName) throws SQLException {
        ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null);
        boolean exists = rs.next();
        rs.close();
        if (!exists) {
            // Try lowercase (some databases are case-sensitive in metadata)
            rs = conn.getMetaData().getTables(null, null, tableName.toLowerCase(), null);
            exists = rs.next();
            rs.close();
        }
        return exists;
    }

    private String computeHash(List<FieldDef> fields) {
        StringBuilder sb = new StringBuilder();
        for (FieldDef f : fields) {
            sb.append(f.name()).append(':');
            if (f.pic() != null) {
                sb.append(f.pic().source());
            }
            sb.append(':').append(f.size()).append(';');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().substring(0, 16); // Use first 16 hex chars
        } catch (NoSuchAlgorithmException e) {
            // Fallback: use hashCode
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private String buildColumnsJson(List<FieldDef> fields, SqlDialect dialect) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (FieldDef f : fields) {
            if (!first) sb.append(",");
            String colName = ColumnDef.toColumnName(f.name());
            String sqlType = dialect.mapType(f);
            sb.append("{\"name\":\"").append(colName)
              .append("\",\"type\":\"").append(sqlType).append("\"}");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private Set<String> parseColumnNames(String columnsJson) {
        Set<String> names = new HashSet<>();
        if (columnsJson == null || columnsJson.isEmpty()) return names;
        // Simple JSON parsing: extract "name":"VALUE" pairs
        int idx = 0;
        String nameKey = "\"name\":\"";
        while ((idx = columnsJson.indexOf(nameKey, idx)) >= 0) {
            idx += nameKey.length();
            int end = columnsJson.indexOf('"', idx);
            if (end > idx) {
                names.add(columnsJson.substring(idx, end));
                idx = end;
            }
        }
        return names;
    }

    private SqlDialect resolveDialect() {
        if (this.dialect != null) return this.dialect;
        Connection conn = factory.acquire();
        try {
            return SqlDialect.auto(conn);
        } finally {
            factory.release(conn);
        }
    }

    // ── Internal data structures ─────────────────────────────────────

    private record SchemaEntry(int version, String recordHash, String columnsJson) {}

    static final class TableDef {
        final Record record;
        final List<String> primaryKeyFields;
        final List<List<String>> indexes;

        TableDef(Record record, List<String> primaryKeyFields, List<List<String>> indexes) {
            this.record = record;
            this.primaryKeyFields = primaryKeyFields;
            this.indexes = indexes;
        }

        /** Get the list of elementary, non-FILLER fields suitable for SQL mapping. */
        List<FieldDef> mappedFields(SqlDialect dialect) {
            List<FieldDef> result = new ArrayList<>();
            for (String fieldName : record.fieldNames()) {
                FieldDef f = record.fieldDef(fieldName);
                // Skip group items, FILLER, and fields with no PIC
                if (f.isGroup()) continue;
                if (f.name().equals("FILLER")) continue;
                if (f.pic() == null) continue;
                // Skip arrays (OCCURS) — they require normalization
                if (f.isArray()) continue;
                // Ensure the dialect can map this type
                String sqlType = dialect.mapType(f);
                if (sqlType == null) continue;
                result.add(f);
            }
            return result;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BUILDER
    // ═══════════════════════════════════════════════════════════════

    public static final class Builder {
        private final ConnectionFactory factory;
        private SqlDialect dialect;
        private final Map<String, TableDef> tables = new LinkedHashMap<>();

        Builder(ConnectionFactory factory) {
            this.factory = factory;
        }

        /** Register a table mapping: table name, Record definition, and configuration. */
        public Builder table(String tableName, Record record, Consumer<TableConfig> config) {
            TableConfig cfg = new TableConfig();
            config.accept(cfg);
            tables.put(tableName, new TableDef(record, cfg.primaryKeyFields, cfg.indexes));
            return this;
        }

        /** Register a table mapping with no special configuration. */
        public Builder table(String tableName, Record record) {
            tables.put(tableName, new TableDef(record, List.of(), List.of()));
            return this;
        }

        /** Override the auto-detected dialect. */
        public Builder dialect(SqlDialect dialect) {
            this.dialect = dialect;
            return this;
        }

        /** Build the SchemaManager. */
        public SchemaManager build() {
            return new SchemaManager(factory, dialect, tables);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  TABLE CONFIG
    // ═══════════════════════════════════════════════════════════════

    public static final class TableConfig {
        final List<String> primaryKeyFields = new ArrayList<>();
        final List<List<String>> indexes = new ArrayList<>();

        /** Specify primary key field(s) using their COBOL names. */
        public TableConfig primaryKey(String... fieldNames) {
            primaryKeyFields.addAll(Arrays.asList(fieldNames));
            return this;
        }

        /** Add an index on the given field(s). */
        public TableConfig index(String... fieldNames) {
            indexes.add(Arrays.asList(fieldNames));
            return this;
        }
    }
}
