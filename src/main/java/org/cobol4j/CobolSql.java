package org.cobol4j;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * COBOL embedded SQL semantics over JDBC.
 * <p>
 * Wraps a JDBC {@link Connection} with COBOL-style SQL operations: host variable
 * binding from {@link Record} fields, SQLCA state management, cursor lifecycle,
 * and fluent builders for all SQL verbs.
 * <p>
 * <b>SELECT INTO (single row):</b>
 * <pre>{@code
 * sql.select("SELECT NAME, BALANCE FROM CUSTOMERS WHERE ID = ?")
 *    .param(rec, "WS-CUST-ID")
 *    .into(rec, "WS-NAME", "WS-BALANCE")
 *    .execute();
 *
 * if (sql.isNotFound()) {
 *     // handle missing customer
 * }
 * }</pre>
 *
 * <b>CURSOR (multi-row):</b>
 * <pre>{@code
 * SqlCursor cursor = sql.declareCursor("CUST-CURSOR",
 *     "SELECT NAME, BALANCE FROM CUSTOMERS ORDER BY NAME");
 *
 * sql.open(cursor);
 * sql.fetch(cursor).into(rec, "WS-NAME", "WS-BALANCE").execute();
 * while (sql.isSuccess()) {
 *     // process row
 *     sql.fetch(cursor).into(rec, "WS-NAME", "WS-BALANCE").execute();
 * }
 * sql.close(cursor);
 * }</pre>
 *
 * <b>DML (INSERT / UPDATE / DELETE):</b>
 * <pre>{@code
 * sql.execute("INSERT INTO CUSTOMERS (ID, NAME, BALANCE) VALUES (?, ?, ?)")
 *    .params(rec, "WS-ID", "WS-NAME", "WS-BALANCE")
 *    .execute();
 * }</pre>
 */
public final class CobolSql {

    private final Connection connection;
    private final Sqlca sqlca;

    private CobolSql(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
        this.sqlca = new Sqlca();
    }

    /** Create a CobolSql context wrapping a JDBC Connection. */
    public static CobolSql using(Connection connection) {
        return new CobolSql(connection);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SQLCA — state after every SQL operation
    // ═══════════════════════════════════════════════════════════════

    /** The SQLCA for this context. */
    public Sqlca sqlca()        { return sqlca; }
    public int sqlCode()        { return sqlca.sqlCode(); }
    public String sqlState()    { return sqlca.sqlState(); }
    public String sqlErrm()     { return sqlca.sqlErrm(); }
    public boolean isSuccess()  { return sqlca.isSuccess(); }
    public boolean isNotFound() { return sqlca.isNotFound(); }
    public boolean isError()    { return sqlca.isError(); }

    // ═══════════════════════════════════════════════════════════════
    //  SELECT ... INTO (single-row query)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Begin a SELECT statement that returns at most one row.
     * The SQL should NOT include an INTO clause — use the fluent
     * {@link SelectBuilder#into} method instead.
     */
    public SelectBuilder select(String sql) {
        return new SelectBuilder(sql);
    }

    // ═══════════════════════════════════════════════════════════════
    //  CURSOR — DECLARE / OPEN / FETCH / CLOSE
    // ═══════════════════════════════════════════════════════════════

    /**
     * DECLARE CURSOR — create a named cursor for a query.
     * The cursor is not opened until {@link #open(SqlCursor, Object...)} is called.
     */
    public SqlCursor declareCursor(String name, String sql) {
        return new SqlCursor(name, sql);
    }

    /**
     * OPEN CURSOR — execute the cursor's query and prepare for fetching.
     *
     * @param cursor the cursor to open
     * @param params bind parameters for the cursor's query (positional)
     */
    public CobolSql open(SqlCursor cursor, Object... params) {
        try {
            cursor.cleanup();
            cursor.statement = connection.prepareStatement(cursor.sql());
            bindParams(cursor.statement, List.of(params));
            cursor.resultSet = cursor.statement.executeQuery();
            cursor.isOpen = true;
            sqlca.reset();
        } catch (SQLException e) {
            handleException(e);
        }
        return this;
    }

    /**
     * OPEN CURSOR with Record-based host variable parameters.
     */
    public CobolSql open(SqlCursor cursor, Record record, String... fieldNames) {
        List<Object> params = new ArrayList<>();
        for (String fn : fieldNames) {
            params.add(extractParam(record, fn));
        }
        return open(cursor, params.toArray());
    }

    /**
     * FETCH CURSOR — begin a fluent fetch that reads the next row.
     */
    public FetchBuilder fetch(SqlCursor cursor) {
        return new FetchBuilder(cursor);
    }

    /**
     * CLOSE CURSOR — release the cursor's JDBC resources.
     */
    public CobolSql close(SqlCursor cursor) {
        cursor.cleanup();
        sqlca.reset();
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  DML — INSERT / UPDATE / DELETE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Begin a DML statement (INSERT, UPDATE, DELETE, or any non-query SQL).
     * <pre>{@code
     * sql.execute("INSERT INTO T (A, B) VALUES (?, ?)")
     *    .param("hello")
     *    .param(42)
     *    .execute();
     * }</pre>
     */
    public DmlBuilder execute(String sql) {
        return new DmlBuilder(sql);
    }

    /**
     * Execute a simple DML statement with inline parameters.
     * Convenience for statements where a builder isn't needed.
     */
    public CobolSql executeImmediate(String sql, Object... params) {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            bindParams(stmt, List.of(params));
            int rows = stmt.executeUpdate();
            sqlca.set(Sqlca.SUCCESS, "00000", "", rows);
        } catch (SQLException e) {
            handleException(e);
        }
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    //  TRANSACTION CONTROL
    // ═══════════════════════════════════════════════════════════════

    /** COMMIT — make all changes permanent. */
    public CobolSql commit() {
        try {
            connection.commit();
            sqlca.reset();
        } catch (SQLException e) {
            handleException(e);
        }
        return this;
    }

    /** ROLLBACK — undo all changes since the last commit. */
    public CobolSql rollback() {
        try {
            connection.rollback();
            sqlca.reset();
        } catch (SQLException e) {
            handleException(e);
        }
        return this;
    }

    /** Access the underlying JDBC connection (for advanced use). */
    public Connection connection() { return connection; }

    // ═══════════════════════════════════════════════════════════════
    //  INTERNAL — parameter binding and result extraction
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extract a parameter value from a Record field, converting to the
     * appropriate JDBC type based on the field's PIC category.
     */
    static Object extractParam(Record record, String fieldName) {
        FieldDef fd = record.fieldDef(fieldName);
        if (fd.isNumeric()) {
            return record.getDecimal(fieldName);
        } else {
            return record.getString(fieldName).trim();
        }
    }

    /**
     * Read a ResultSet row into Record fields, mapping columns by position
     * to the named fields. Numeric fields receive BigDecimal; alphanumeric
     * fields receive String.
     */
    static void readInto(ResultSet rs, Record record, String[] fieldNames) throws SQLException {
        for (int i = 0; i < fieldNames.length; i++) {
            FieldDef fd = record.fieldDef(fieldNames[i]);
            if (fd.isNumeric()) {
                BigDecimal val = rs.getBigDecimal(i + 1);
                record.move(fieldNames[i], val != null ? val : BigDecimal.ZERO);
            } else {
                String val = rs.getString(i + 1);
                record.move(fieldNames[i], val != null ? val : "");
            }
        }
    }

    private static void bindParams(PreparedStatement stmt, List<Object> params)
            throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p == null) {
                stmt.setNull(i + 1, Types.VARCHAR);
            } else if (p instanceof BigDecimal bd) {
                stmt.setBigDecimal(i + 1, bd);
            } else if (p instanceof Integer n) {
                stmt.setInt(i + 1, n);
            } else if (p instanceof Long n) {
                stmt.setLong(i + 1, n);
            } else if (p instanceof Double d) {
                stmt.setDouble(i + 1, d);
            } else {
                stmt.setString(i + 1, p.toString());
            }
        }
    }

    private void handleException(SQLException e) {
        sqlca.set(-e.getErrorCode(),
                  e.getSQLState() != null ? e.getSQLState() : "99999",
                  e.getMessage());
    }

    // ═══════════════════════════════════════════════════════════════
    //  SELECT BUILDER
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fluent builder for SELECT ... INTO (single-row query).
     * <p>
     * Maps directly to COBOL's:
     * <pre>
     * EXEC SQL
     *     SELECT col1, col2 INTO :HOST1, :HOST2
     *     FROM table WHERE key = :HOST3
     * END-EXEC
     * </pre>
     */
    public final class SelectBuilder {
        private final String sql;
        private final List<Object> params = new ArrayList<>();
        private Record intoRecord;
        private String[] intoFields;

        SelectBuilder(String sql) {
            this.sql = sql;
        }

        /** Bind a literal parameter value. */
        public SelectBuilder param(Object value) {
            params.add(value);
            return this;
        }

        /** Bind a parameter from a Record field (host variable). */
        public SelectBuilder param(Record record, String fieldName) {
            params.add(extractParam(record, fieldName));
            return this;
        }

        /** Bind multiple parameters from Record fields. */
        public SelectBuilder params(Record record, String... fieldNames) {
            for (String fn : fieldNames) {
                params.add(extractParam(record, fn));
            }
            return this;
        }

        /** Specify the INTO target — Record fields to receive the result columns. */
        public SelectBuilder into(Record record, String... fieldNames) {
            this.intoRecord = record;
            this.intoFields = fieldNames;
            return this;
        }

        /** Execute the SELECT. Sets SQLCODE: 0=found, 100=not found, <0=error. */
        public CobolSql execute() {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                bindParams(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        if (intoRecord != null && intoFields != null) {
                            readInto(rs, intoRecord, intoFields);
                        }
                        sqlca.set(Sqlca.SUCCESS, "00000", "");
                    } else {
                        sqlca.set(Sqlca.NOT_FOUND, "02000", "No data found");
                    }
                }
            } catch (SQLException e) {
                handleException(e);
            }
            return CobolSql.this;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  FETCH BUILDER
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fluent builder for FETCH cursor INTO fields.
     */
    public final class FetchBuilder {
        private final SqlCursor cursor;
        private Record intoRecord;
        private String[] intoFields;

        FetchBuilder(SqlCursor cursor) {
            this.cursor = cursor;
        }

        /** Specify the INTO target fields. */
        public FetchBuilder into(Record record, String... fieldNames) {
            this.intoRecord = record;
            this.intoFields = fieldNames;
            return this;
        }

        /** Execute the FETCH. Sets SQLCODE: 0=row fetched, 100=no more rows. */
        public CobolSql execute() {
            if (!cursor.isOpen || cursor.resultSet == null) {
                sqlca.set(-1, "24000", "Cursor not open: " + cursor.name());
                return CobolSql.this;
            }
            try {
                if (cursor.resultSet.next()) {
                    if (intoRecord != null && intoFields != null) {
                        readInto(cursor.resultSet, intoRecord, intoFields);
                    }
                    sqlca.set(Sqlca.SUCCESS, "00000", "");
                } else {
                    sqlca.set(Sqlca.NOT_FOUND, "02000", "No more rows");
                }
            } catch (SQLException e) {
                handleException(e);
            }
            return CobolSql.this;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  DML BUILDER — INSERT / UPDATE / DELETE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fluent builder for DML statements (INSERT, UPDATE, DELETE).
     * <p>
     * Maps directly to COBOL's:
     * <pre>
     * EXEC SQL
     *     INSERT INTO CUSTOMERS (ID, NAME, BALANCE)
     *     VALUES (:WS-ID, :WS-NAME, :WS-BALANCE)
     * END-EXEC
     * </pre>
     */
    public final class DmlBuilder {
        private final String sql;
        private final List<Object> params = new ArrayList<>();

        DmlBuilder(String sql) {
            this.sql = sql;
        }

        /** Bind a literal parameter value. */
        public DmlBuilder param(Object value) {
            params.add(value);
            return this;
        }

        /** Bind a parameter from a Record field (host variable). */
        public DmlBuilder param(Record record, String fieldName) {
            params.add(extractParam(record, fieldName));
            return this;
        }

        /** Bind multiple parameters from Record fields. */
        public DmlBuilder params(Record record, String... fieldNames) {
            for (String fn : fieldNames) {
                params.add(extractParam(record, fn));
            }
            return this;
        }

        /** Execute the DML. Sets SQLCODE and rows affected. */
        public CobolSql execute() {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                bindParams(stmt, params);
                int rows = stmt.executeUpdate();
                sqlca.set(Sqlca.SUCCESS, "00000", "", rows);
            } catch (SQLException e) {
                handleException(e);
            }
            return CobolSql.this;
        }
    }
}
