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

import org.cobol4j.Decimal;
import org.cobol4j.FieldDef;
import org.cobol4j.Record;
import org.cobol4j.SqlSession;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * CRUD operations against a SQL table mapped from a COBOL Record definition.
 * <p>
 * Generates SQL (INSERT, UPDATE, DELETE, SELECT) from the Record's field
 * definitions, binding COBOL field values to JDBC parameters and reading
 * result sets back into Record fields.
 */
public final class RecordStore {

    private final String tableName;
    private final Record templateRecord;
    private final List<FieldDef> mappedFields;
    private final List<String> primaryKeyFields;
    private final SqlDialect dialect;

    RecordStore(String tableName, Record templateRecord, List<FieldDef> mappedFields,
                List<String> primaryKeyFields, SqlDialect dialect) {
        this.tableName = tableName;
        this.templateRecord = templateRecord;
        this.mappedFields = mappedFields;
        this.primaryKeyFields = primaryKeyFields;
        this.dialect = dialect;
    }

    /** INSERT all fields from the Record as a new row. */
    public void insert(SqlSession session, Record record) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        StringBuilder params = new StringBuilder();
        boolean first = true;
        for (FieldDef f : mappedFields) {
            if (!first) { sql.append(", "); params.append(", "); }
            sql.append(ColumnDef.toColumnName(f.name()));
            params.append("?");
            first = false;
        }
        sql.append(") VALUES (").append(params).append(")");

        try {
            PreparedStatement ps = session.connection().prepareStatement(sql.toString());
            int idx = 1;
            for (FieldDef f : mappedFields) {
                bindField(ps, idx++, f, record);
            }
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throw new RuntimeException("INSERT failed for table " + tableName, e);
        }
    }

    /** UPDATE the row matching the primary key with all other fields. */
    public void update(SqlSession session, Record record) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(tableName).append(" SET ");
        List<FieldDef> nonKeyFields = new ArrayList<>();
        boolean first = true;
        for (FieldDef f : mappedFields) {
            if (isPrimaryKey(f.name())) continue;
            nonKeyFields.add(f);
            if (!first) sql.append(", ");
            sql.append(ColumnDef.toColumnName(f.name())).append(" = ?");
            first = false;
        }
        sql.append(" WHERE ");
        first = true;
        List<FieldDef> keyFields = new ArrayList<>();
        for (String pkName : primaryKeyFields) {
            FieldDef f = findField(pkName);
            if (f == null) continue;
            keyFields.add(f);
            if (!first) sql.append(" AND ");
            sql.append(ColumnDef.toColumnName(f.name())).append(" = ?");
            first = false;
        }

        try {
            PreparedStatement ps = session.connection().prepareStatement(sql.toString());
            int idx = 1;
            for (FieldDef f : nonKeyFields) {
                bindField(ps, idx++, f, record);
            }
            for (FieldDef f : keyFields) {
                bindField(ps, idx++, f, record);
            }
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throw new RuntimeException("UPDATE failed for table " + tableName, e);
        }
    }

    /** DELETE the row matching the primary key. */
    public void delete(SqlSession session, Record record) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(tableName).append(" WHERE ");
        List<FieldDef> keyFields = new ArrayList<>();
        boolean first = true;
        for (String pkName : primaryKeyFields) {
            FieldDef f = findField(pkName);
            if (f == null) continue;
            keyFields.add(f);
            if (!first) sql.append(" AND ");
            sql.append(ColumnDef.toColumnName(f.name())).append(" = ?");
            first = false;
        }

        try {
            PreparedStatement ps = session.connection().prepareStatement(sql.toString());
            int idx = 1;
            for (FieldDef f : keyFields) {
                bindField(ps, idx++, f, record);
            }
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            throw new RuntimeException("DELETE failed for table " + tableName, e);
        }
    }

    /** SELECT by primary key, load into Record. Returns true if found. */
    public boolean findByKey(SqlSession session, Record record, String... keyValues) {
        StringBuilder sql = new StringBuilder("SELECT ");
        boolean first = true;
        for (FieldDef f : mappedFields) {
            if (!first) sql.append(", ");
            sql.append(ColumnDef.toColumnName(f.name()));
            first = false;
        }
        sql.append(" FROM ").append(tableName).append(" WHERE ");
        first = true;
        for (int i = 0; i < primaryKeyFields.size(); i++) {
            String pkName = primaryKeyFields.get(i);
            FieldDef f = findField(pkName);
            if (f == null) continue;
            if (!first) sql.append(" AND ");
            sql.append(ColumnDef.toColumnName(f.name())).append(" = ?");
            first = false;
        }

        try {
            PreparedStatement ps = session.connection().prepareStatement(sql.toString());
            for (int i = 0; i < keyValues.length && i < primaryKeyFields.size(); i++) {
                FieldDef f = findField(primaryKeyFields.get(i));
                if (f != null && f.isNumeric()) {
                    ps.setBigDecimal(i + 1, new BigDecimal(keyValues[i]));
                } else {
                    ps.setString(i + 1, keyValues[i]);
                }
            }
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                loadFromResultSet(rs, record);
                rs.close();
                ps.close();
                return true;
            }
            rs.close();
            ps.close();
            return false;
        } catch (SQLException e) {
            throw new RuntimeException("SELECT failed for table " + tableName, e);
        }
    }

    /** SELECT all rows, calling consumer for each. */
    public void findAll(SqlSession session, Record record, Consumer<Record> consumer) {
        StringBuilder sql = new StringBuilder("SELECT ");
        boolean first = true;
        for (FieldDef f : mappedFields) {
            if (!first) sql.append(", ");
            sql.append(ColumnDef.toColumnName(f.name()));
            first = false;
        }
        sql.append(" FROM ").append(tableName);

        try {
            PreparedStatement ps = session.connection().prepareStatement(sql.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                loadFromResultSet(rs, record);
                consumer.accept(record);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            throw new RuntimeException("SELECT ALL failed for table " + tableName, e);
        }
    }

    /** SELECT with WHERE clause. */
    public void findWhere(SqlSession session, Record record, String where, Consumer<Record> consumer) {
        StringBuilder sql = new StringBuilder("SELECT ");
        boolean first = true;
        for (FieldDef f : mappedFields) {
            if (!first) sql.append(", ");
            sql.append(ColumnDef.toColumnName(f.name()));
            first = false;
        }
        sql.append(" FROM ").append(tableName).append(" WHERE ").append(where);

        try {
            PreparedStatement ps = session.connection().prepareStatement(sql.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                loadFromResultSet(rs, record);
                consumer.accept(record);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            throw new RuntimeException("SELECT WHERE failed for table " + tableName, e);
        }
    }

    /** COUNT rows. */
    public long count(SqlSession session) {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try {
            PreparedStatement ps = session.connection().prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            rs.next();
            long count = rs.getLong(1);
            rs.close();
            ps.close();
            return count;
        } catch (SQLException e) {
            throw new RuntimeException("COUNT failed for table " + tableName, e);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────

    private void bindField(PreparedStatement ps, int index, FieldDef f, Record record)
            throws SQLException {
        if (f.isNumeric()) {
            ps.setBigDecimal(index, record.getDecimal(f.name()).toBigDecimal());
        } else {
            ps.setString(index, record.getString(f.name()).trim());
        }
    }

    private void loadFromResultSet(ResultSet rs, Record record) throws SQLException {
        for (int i = 0; i < mappedFields.size(); i++) {
            FieldDef f = mappedFields.get(i);
            String colName = ColumnDef.toColumnName(f.name());
            if (f.isNumeric()) {
                BigDecimal val = rs.getBigDecimal(colName);
                if (val != null) {
                    record.move(f.name(), Decimal.wrap(val));
                }
            } else {
                String val = rs.getString(colName);
                if (val != null) {
                    record.move(f.name(), val);
                }
            }
        }
    }

    private boolean isPrimaryKey(String fieldName) {
        for (String pk : primaryKeyFields) {
            if (pk.equals(fieldName)) return true;
        }
        return false;
    }

    private FieldDef findField(String name) {
        for (FieldDef f : mappedFields) {
            if (f.name().equals(name)) return f;
        }
        return null;
    }
}
