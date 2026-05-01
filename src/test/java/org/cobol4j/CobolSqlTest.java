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

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class CobolSqlTest {

    private static Connection conn;
    private CobolSql sql;

    @BeforeAll
    static void setupDatabase() throws Exception {
        conn = DriverManager.getConnection("jdbc:h2:mem:cobol4j_test;DB_CLOSE_DELAY=-1");
        conn.setAutoCommit(false);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE CUSTOMERS (
                    CUST_ID    VARCHAR(10) NOT NULL PRIMARY KEY,
                    CUST_NAME  VARCHAR(30),
                    CUST_BAL   DECIMAL(9,2),
                    CUST_STATUS CHAR(1)
                )
                """);
            stmt.execute("INSERT INTO CUSTOMERS VALUES ('C001', 'ALICE SMITH', 5000.00, 'A')");
            stmt.execute("INSERT INTO CUSTOMERS VALUES ('C002', 'BOB JONES',   1500.50, 'A')");
            stmt.execute("INSERT INTO CUSTOMERS VALUES ('C003', 'CAROL DAVIS',  250.75, 'I')");
            conn.commit();
        }
    }

    @BeforeEach
    void setup() {
        sql = CobolSql.using(conn);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (conn != null) conn.close();
    }

    // ── SELECT INTO ─────────────────────────────────────────────────

    @Test
    void selectIntoSingleRow() {
        Record rec = Record.define("WS")
            .pic("WS-ID",   "X(10)")
            .pic("WS-NAME", "X(30)")
            .pic("WS-BAL",  "S9(7)V99")
            .build();

        sql.select("SELECT CUST_NAME, CUST_BAL FROM CUSTOMERS WHERE CUST_ID = ?")
           .param("C001")
           .into(rec, "WS-NAME", "WS-BAL")
           .execute();

        assertTrue(sql.isSuccess());
        assertEquals(0, sql.sqlCode());
        assertEquals("ALICE SMITH", rec.getString("WS-NAME").trim());
        assertEquals(0, new BigDecimal("5000.00").compareTo(rec.getDecimal("WS-BAL")));
    }

    @Test
    void selectIntoWithHostVariable() {
        Record rec = Record.define("WS")
            .pic("WS-ID",   "X(10)")
            .pic("WS-NAME", "X(30)")
            .pic("WS-BAL",  "S9(7)V99")
            .build();

        rec.move("WS-ID", "C002");

        sql.select("SELECT CUST_NAME, CUST_BAL FROM CUSTOMERS WHERE CUST_ID = ?")
           .param(rec, "WS-ID")
           .into(rec, "WS-NAME", "WS-BAL")
           .execute();

        assertTrue(sql.isSuccess());
        assertEquals("BOB JONES", rec.getString("WS-NAME").trim());
        assertEquals(0, new BigDecimal("1500.50").compareTo(rec.getDecimal("WS-BAL")));
    }

    @Test
    void selectNotFound() {
        Record rec = Record.define("WS")
            .pic("WS-NAME", "X(30)")
            .build();

        sql.select("SELECT CUST_NAME FROM CUSTOMERS WHERE CUST_ID = ?")
           .param("XXXXX")
           .into(rec, "WS-NAME")
           .execute();

        assertTrue(sql.isNotFound());
        assertEquals(100, sql.sqlCode());
    }

    // ── CURSOR ──────────────────────────────────────────────────────

    @Test
    void cursorFetchAllRows() {
        Record rec = Record.define("WS")
            .pic("WS-NAME", "X(30)")
            .pic("WS-BAL",  "S9(7)V99")
            .build();

        SqlCursor cursor = sql.declareCursor("CUST-CURSOR",
            "SELECT CUST_NAME, CUST_BAL FROM CUSTOMERS ORDER BY CUST_ID");

        sql.open(cursor);
        assertTrue(cursor.isOpen());

        int count = 0;
        BigDecimal total = BigDecimal.ZERO;
        sql.fetch(cursor).into(rec, "WS-NAME", "WS-BAL").execute();
        while (sql.isSuccess()) {
            count++;
            total = total.add(rec.getDecimal("WS-BAL"));
            sql.fetch(cursor).into(rec, "WS-NAME", "WS-BAL").execute();
        }

        sql.close(cursor);
        assertFalse(cursor.isOpen());

        assertEquals(3, count);
        // 5000.00 + 1500.50 + 250.75 = 6751.25
        assertEquals(0, new BigDecimal("6751.25").compareTo(total));
    }

    @Test
    void cursorWithParameter() {
        Record rec = Record.define("WS")
            .pic("WS-STATUS", "X")
            .pic("WS-NAME",   "X(30)")
            .build();

        rec.move("WS-STATUS", "A");

        SqlCursor cursor = sql.declareCursor("ACTIVE-CURSOR",
            "SELECT CUST_NAME FROM CUSTOMERS WHERE CUST_STATUS = ? ORDER BY CUST_NAME");

        sql.open(cursor, rec, "WS-STATUS");

        int count = 0;
        sql.fetch(cursor).into(rec, "WS-NAME").execute();
        while (sql.isSuccess()) {
            count++;
            sql.fetch(cursor).into(rec, "WS-NAME").execute();
        }
        sql.close(cursor);

        assertEquals(2, count); // ALICE and BOB are active
    }

    // ── INSERT ──────────────────────────────────────────────────────

    @Test
    void insertWithHostVariables() throws Exception {
        Record rec = Record.define("WS")
            .pic("WS-ID",     "X(10)")
            .pic("WS-NAME",   "X(30)")
            .pic("WS-BAL",    "S9(7)V99")
            .pic("WS-STATUS", "X")
            .build();

        rec.move("WS-ID", "C099")
           .move("WS-NAME", "NEW CUSTOMER")
           .move("WS-BAL", new BigDecimal("999.99"))
           .move("WS-STATUS", "A");

        sql.execute("INSERT INTO CUSTOMERS (CUST_ID, CUST_NAME, CUST_BAL, CUST_STATUS) VALUES (?, ?, ?, ?)")
           .params(rec, "WS-ID", "WS-NAME", "WS-BAL", "WS-STATUS")
           .execute();

        assertTrue(sql.isSuccess());
        assertEquals(1, sql.sqlca().rowsAffected());

        // Verify the insert
        sql.select("SELECT CUST_NAME FROM CUSTOMERS WHERE CUST_ID = ?")
           .param("C099")
           .into(rec, "WS-NAME")
           .execute();

        assertTrue(sql.isSuccess());
        assertEquals("NEW CUSTOMER", rec.getString("WS-NAME").trim());

        // Cleanup
        sql.executeImmediate("DELETE FROM CUSTOMERS WHERE CUST_ID = ?", "C099");
        conn.commit();
    }

    // ── UPDATE ──────────────────────────────────────────────────────

    @Test
    void updateWithHostVariables() throws Exception {
        Record rec = Record.define("WS")
            .pic("WS-ID",  "X(10)")
            .pic("WS-BAL", "S9(7)V99")
            .build();

        rec.move("WS-ID", "C003")
           .move("WS-BAL", new BigDecimal("500.00"));

        sql.execute("UPDATE CUSTOMERS SET CUST_BAL = ? WHERE CUST_ID = ?")
           .param(rec, "WS-BAL")
           .param(rec, "WS-ID")
           .execute();

        assertTrue(sql.isSuccess());
        assertEquals(1, sql.sqlca().rowsAffected());

        // Verify
        sql.select("SELECT CUST_BAL FROM CUSTOMERS WHERE CUST_ID = ?")
           .param("C003")
           .into(rec, "WS-BAL")
           .execute();

        assertEquals(0, new BigDecimal("500.00").compareTo(rec.getDecimal("WS-BAL")));

        // Restore original
        sql.executeImmediate("UPDATE CUSTOMERS SET CUST_BAL = 250.75 WHERE CUST_ID = ?", "C003");
        conn.commit();
    }

    // ── DELETE ───────────────────────────────────────────────────────

    @Test
    void deleteAndRollback() throws Exception {
        sql.execute("DELETE FROM CUSTOMERS WHERE CUST_ID = ?")
           .param("C001")
           .execute();

        assertTrue(sql.isSuccess());
        assertEquals(1, sql.sqlca().rowsAffected());

        // Verify it's gone
        sql.select("SELECT CUST_NAME FROM CUSTOMERS WHERE CUST_ID = ?")
           .param("C001")
           .into(Record.define("T").pic("N","X(30)").build(), "N")
           .execute();
        assertTrue(sql.isNotFound());

        // Rollback to restore
        conn.rollback();

        // Should be back
        Record check = Record.define("T").pic("N","X(30)").build();
        sql.select("SELECT CUST_NAME FROM CUSTOMERS WHERE CUST_ID = ?")
           .param("C001")
           .into(check, "N")
           .execute();
        assertTrue(sql.isSuccess());
        assertEquals("ALICE SMITH", check.getString("N").trim());
    }

    // ── SqlSession — connection lifecycle ────────────────────────────

    @Test
    void sqlSessionAcquireAndRelease() throws Exception {
        ConnectionFactory factory = ConnectionFactory.simple(
            "jdbc:h2:mem:session_test", "", "");

        try (SqlSession session = SqlSession.from(factory)) {
            // Create table within the session
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS TEST (ID INT, NAME VARCHAR(20))");
            session.sql().execute("INSERT INTO TEST VALUES (?, ?)")
                .param(1).param("HELLO")
                .execute();

            assertTrue(session.isSuccess());

            Record rec = Record.define("WS").pic("N","X(20)").build();
            session.sql().select("SELECT NAME FROM TEST WHERE ID = ?")
                .param(1)
                .into(rec, "N")
                .execute();

            assertTrue(session.isSuccess());
            assertEquals("HELLO", rec.getString("N").trim());

            session.commit();
        }
        // Connection released automatically — no leak
    }

    @Test
    void sqlSessionRollsBackOnCloseWithoutCommit() throws Exception {
        // Use DB_CLOSE_DELAY=-1 so the in-memory DB persists across connections
        ConnectionFactory factory = ConnectionFactory.simple(
            "jdbc:h2:mem:rollback_test;DB_CLOSE_DELAY=-1", "", "");

        // First session: create table and seed data
        try (SqlSession session = SqlSession.from(factory)) {
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS RB_TEST (ID INT, VAL VARCHAR(10))");
            session.sql().executeImmediate(
                "INSERT INTO RB_TEST VALUES (1, 'ORIGINAL')");
            session.commit();
        }

        // Second session: update but don't commit — should rollback on close
        try (SqlSession session = SqlSession.from(factory)) {
            session.sql().execute("UPDATE RB_TEST SET VAL = ? WHERE ID = ?")
                .param("CHANGED").param(1)
                .execute();
            // No commit — close() will rollback
        }

        // Third session: verify the value is still ORIGINAL
        try (SqlSession session = SqlSession.from(factory)) {
            Record rec = Record.define("T").pic("V","X(10)").build();
            session.sql().select("SELECT VAL FROM RB_TEST WHERE ID = ?")
                .param(1)
                .into(rec, "V")
                .execute();

            assertTrue(session.isSuccess());
            assertEquals("ORIGINAL", rec.getString("V").trim());
        }
    }

    // ── Cached connection factory ───────────────────────────────────

    @Test
    void cachedFactoryReusesConnections() {
        ConnectionFactory base = ConnectionFactory.simple(
            "jdbc:h2:mem:pool_test", "", "");
        ConnectionFactory pooled = ConnectionFactory.cached(base, 2);

        Connection c1 = pooled.acquire();
        assertNotNull(c1);
        pooled.release(c1);

        // Second acquire should reuse the same connection
        Connection c2 = pooled.acquire();
        assertSame(c1, c2);
        pooled.release(c2);
    }

    // ── SqlSession.work() — unit-of-work pattern ──────────────────────

    @Test
    void workPatternCommitsOnSuccess() throws Exception {
        ConnectionFactory factory = ConnectionFactory.simple(
            "jdbc:h2:mem:work_test;DB_CLOSE_DELAY=-1", "", "");

        // Setup
        SqlSession.work(factory, session -> {
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS WORK_TEST (ID INT, NAME VARCHAR(20))");
        });

        // Work unit: insert a row — auto-commits on success
        SqlSession.work(factory, session -> {
            session.sql().execute("INSERT INTO WORK_TEST VALUES (?, ?)")
                .param(1).param("COMMITTED")
                .execute();
            // no explicit commit — work() commits for us
        });

        // Verify: row should be persisted
        SqlSession.work(factory, session -> {
            Record rec = Record.define("T").pic("N", "X(20)").build();
            session.sql().select("SELECT NAME FROM WORK_TEST WHERE ID = ?")
                .param(1)
                .into(rec, "N")
                .execute();

            assertTrue(session.isSuccess());
            assertEquals("COMMITTED", rec.getString("N").trim());
        });
    }

    @Test
    void workPatternRollsBackOnException() throws Exception {
        ConnectionFactory factory = ConnectionFactory.simple(
            "jdbc:h2:mem:work_rb_test;DB_CLOSE_DELAY=-1", "", "");

        // Setup
        SqlSession.work(factory, session ->
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS WORK_RB (ID INT, VAL VARCHAR(20))"));

        SqlSession.work(factory, session ->
            session.sql().executeImmediate(
                "INSERT INTO WORK_RB VALUES (1, 'ORIGINAL')"));

        // Work unit that fails mid-way — should rollback
        boolean[] errorHandled = {false};
        SqlSession.work(factory,
            session -> {
                session.sql().execute("UPDATE WORK_RB SET VAL = ? WHERE ID = ?")
                    .param("CHANGED").param(1)
                    .execute();
                throw new RuntimeException("Simulated failure");
            },
            ex -> errorHandled[0] = true  // error handler instead of rethrow
        );

        assertTrue(errorHandled[0]);

        // Verify: value should still be ORIGINAL (rollback happened)
        SqlSession.work(factory, session -> {
            Record rec = Record.define("T").pic("V", "X(20)").build();
            session.sql().select("SELECT VAL FROM WORK_RB WHERE ID = ?")
                .param(1)
                .into(rec, "V")
                .execute();

            assertTrue(session.isSuccess());
            assertEquals("ORIGINAL", rec.getString("V").trim());
        });
    }

    // ── Full transpiled program with SQL ─────────────────────────────

    @Test
    void fullProgramWithSql() {
        Record wsRec = Record.define("WORKING-STORAGE")
            .pic("WS-ID",     "X(10)")
            .pic("WS-NAME",   "X(30)")
            .pic("WS-BAL",    "S9(7)V99")
            .pic("WS-TOTAL",  "S9(9)V99")
            .pic("WS-COUNT",  "9(5)")
            .build();

        SqlCursor cursor = sql.declareCursor("ALL-CUST",
            "SELECT CUST_ID, CUST_NAME, CUST_BAL FROM CUSTOMERS ORDER BY CUST_ID");

        Program program = Program.define("CUSTOMER-TOTALS")
            .workingStorage(wsRec)
            .onDisplay(s -> {})
            .paragraph("MAIN-LOGIC", ctx -> {
                wsRec.move("WS-TOTAL", BigDecimal.ZERO)
                     .move("WS-COUNT", 0L);

                sql.open(cursor);
                ctx.performUntil("FETCH-LOOP", () -> sql.isNotFound());
                sql.close(cursor);

                ctx.display("Customers: ", wsRec.getInt("WS-COUNT"))
                   .display("Total balance: ", wsRec.getDecimal("WS-TOTAL"))
                   .stopRun();
            })
            .paragraph("FETCH-LOOP", ctx -> {
                sql.fetch(cursor)
                   .into(wsRec, "WS-ID", "WS-NAME", "WS-BAL")
                   .execute();

                if (sql.isSuccess()) {
                    wsRec.add("WS-TOTAL", wsRec.getDecimal("WS-BAL"))
                         .add("WS-COUNT", BigDecimal.ONE);
                }
            })
            .build();

        program.run();

        assertEquals(3, wsRec.getInt("WS-COUNT"));
        assertEquals(0, new BigDecimal("6751.25").compareTo(wsRec.getDecimal("WS-TOTAL")));

        var output = program.context().displayOutput();
        assertEquals("Customers: 3", output.get(0));
        assertEquals("Total balance: 6751.25", output.get(1));
    }
}
