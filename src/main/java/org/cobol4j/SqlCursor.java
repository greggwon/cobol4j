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
