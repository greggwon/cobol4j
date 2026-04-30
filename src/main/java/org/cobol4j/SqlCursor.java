package org.cobol4j;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * A COBOL SQL cursor — a named, scrollable pointer over a query result set.
 * <p>
 * Cursors in COBOL follow a strict lifecycle: DECLARE → OPEN → FETCH → CLOSE.
 * This class holds the cursor state and its associated JDBC resources.
 * <pre>{@code
 * SqlCursor cursor = sql.declareCursor("CUST-CURSOR",
 *     "SELECT NAME, BALANCE FROM CUSTOMERS WHERE STATUS = ?");
 *
 * sql.open(cursor, rec.getString("WS-STATUS").trim());
 * sql.fetch(cursor).into(rec, "WS-NAME", "WS-BALANCE").execute();
 * while (sql.isSuccess()) {
 *     // process row
 *     sql.fetch(cursor).into(rec, "WS-NAME", "WS-BALANCE").execute();
 * }
 * sql.close(cursor);
 * }</pre>
 */
public final class SqlCursor {

    private final String name;
    private final String sql;
    private final List<Object> openParams = new ArrayList<>();

    // Managed by CobolSql — package-private
    PreparedStatement statement;
    ResultSet resultSet;
    boolean isOpen;

    SqlCursor(String name, String sql) {
        this.name = name;
        this.sql = sql;
    }

    public String name() { return name; }
    public String sql()  { return sql; }

    /** Is the cursor currently open? */
    public boolean isOpen() { return isOpen; }

    void setOpenParams(List<Object> params) {
        openParams.clear();
        openParams.addAll(params);
    }

    List<Object> openParams() { return openParams; }

    void cleanup() {
        try {
            if (resultSet != null) { resultSet.close(); resultSet = null; }
            if (statement != null) { statement.close(); statement = null; }
        } catch (Exception ignored) {}
        isOpen = false;
    }
}
