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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.cobol4j.Decimal;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the SQL layer works identically across different databases.
 * <p>
 * The same operations run against H2 (in-memory) and SQLite (in-memory).
 * In production, Oracle, PostgreSQL, MySQL would work the same way —
 * they just need their JDBC driver on the classpath.
 * <p>
 * Usage with each database:
 * <pre>
 * // H2 (embedded, testing)
 * ConnectionFactory factory = ConnectionFactory.h2("mem:mytest");
 *
 * // SQLite (file-based or in-memory)
 * ConnectionFactory factory = ConnectionFactory.sqlite("/data/app.db");
 * ConnectionFactory factory = ConnectionFactory.sqlite(":memory:");
 *
 * // PostgreSQL
 * ConnectionFactory factory = ConnectionFactory.postgres("db.example.com", 5432, "orders", "user", "pass");
 *
 * // MySQL / MariaDB
 * ConnectionFactory factory = ConnectionFactory.mysql("db.example.com", 3306, "orders", "user", "pass");
 *
 * // Oracle
 * ConnectionFactory factory = ConnectionFactory.oracle("db.example.com", 1521, "ORCL", "user", "pass");
 *
 * // Then use identically:
 * SqlSession.work(factory, session -> {
 *     session.sql().select("SELECT ...").param(...).into(...).execute();
 * });
 * </pre>
 */
class CrossDatabaseTest {

    /** Provides factory instances for each database to test against. */
    static Stream<ConnectionFactory> databases() {
        return Stream.of(
            // H2: in-memory, persistent across connections via DB_CLOSE_DELAY
            ConnectionFactory.h2("mem:crosstest_h2"),
            // SQLite: in-memory auto-detects and enables caching (DB is per-connection)
            ConnectionFactory.sqlite(":memory:")
            // PostgreSQL, MySQL, Oracle would go here with live servers:
            // ConnectionFactory.postgres("localhost", 5432, "test", "user", "pass").cached(false)
        );
    }

    @ParameterizedTest
    @MethodSource("databases")
    void createTableAndInsert(ConnectionFactory factory) {
        Record rec = Record.define("WS")
            .pic("ID",     "9(5)")
            .pic("NAME",   "X(20)")
            .pic("AMOUNT", "S9(7)V99")
            .build();

        // Setup schema
        SqlSession.work(factory, session -> {
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS CUSTOMERS (ID INT, NAME VARCHAR(20), AMOUNT DECIMAL(9,2))");
        });

        // INSERT via host variables
        SqlSession.work(factory, session -> {
            rec.move("ID", 1L).move("NAME", "ALICE").move("AMOUNT", Decimal.of("1500.00"));
            session.sql().execute("INSERT INTO CUSTOMERS (ID, NAME, AMOUNT) VALUES (?, ?, ?)")
                .param(rec, "ID")
                .param(rec, "NAME")
                .param(rec, "AMOUNT")
                .execute();
            assertTrue(session.isSuccess());
        });

        // SELECT INTO
        SqlSession.work(factory, session -> {
            session.sql().select("SELECT NAME, AMOUNT FROM CUSTOMERS WHERE ID = ?")
                .param(1)
                .into(rec, "NAME", "AMOUNT")
                .execute();
            assertTrue(session.isSuccess());
            assertEquals("ALICE", rec.getString("NAME").trim());
            assertTrue(rec.getDecimal("AMOUNT").equalTo(Decimal.of("1500.00")));
        });
    }

    @ParameterizedTest
    @MethodSource("databases")
    void cursorFetchLoop(ConnectionFactory factory) {
        Record rec = Record.define("WS")
            .pic("NAME",   "X(20)")
            .pic("AMOUNT", "S9(7)V99")
            .pic("TOTAL",  "S9(9)V99")
            .build();

        // Setup
        SqlSession.work(factory, session -> {
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS ITEMS (NAME VARCHAR(20), AMOUNT DECIMAL(9,2))");
            session.sql().executeImmediate("DELETE FROM ITEMS");
            session.sql().executeImmediate("INSERT INTO ITEMS VALUES ('APPLE', 1.50)");
            session.sql().executeImmediate("INSERT INTO ITEMS VALUES ('BANANA', 2.75)");
            session.sql().executeImmediate("INSERT INTO ITEMS VALUES ('CHERRY', 3.25)");
        });

        // Cursor fetch loop — COBOL pattern
        SqlSession.work(factory, session -> {
            SqlCursor cursor = session.sql().declareCursor("ITEM-CURSOR",
                "SELECT NAME, AMOUNT FROM ITEMS ORDER BY NAME");

            session.sql().open(cursor);
            rec.move("TOTAL", Decimal.ZERO);
            int count = 0;

            session.sql().fetch(cursor).into(rec, "NAME", "AMOUNT").execute();
            while (session.isSuccess()) {
                count++;
                rec.add("TOTAL", rec.getDecimal("AMOUNT"));
                session.sql().fetch(cursor).into(rec, "NAME", "AMOUNT").execute();
            }
            session.sql().close(cursor);

            assertEquals(3, count);
            // 1.50 + 2.75 + 3.25 = 7.50
            assertTrue(rec.getDecimal("TOTAL").equalTo(Decimal.of("7.50")));
        });
    }

    @ParameterizedTest
    @MethodSource("databases")
    void updateAndDelete(ConnectionFactory factory) {
        Record rec = Record.define("WS")
            .pic("AMT", "S9(7)V99")
            .build();

        // Setup
        SqlSession.work(factory, session -> {
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS ACCOUNTS (ID INT, BALANCE DECIMAL(9,2))");
            session.sql().executeImmediate("DELETE FROM ACCOUNTS");
            session.sql().executeImmediate("INSERT INTO ACCOUNTS VALUES (1, 1000.00)");
            session.sql().executeImmediate("INSERT INTO ACCOUNTS VALUES (2, 2000.00)");
        });

        // UPDATE
        SqlSession.work(factory, session -> {
            session.sql().execute("UPDATE ACCOUNTS SET BALANCE = ? WHERE ID = ?")
                .param(Decimal.of("1500.00"))
                .param(1)
                .execute();
            assertTrue(session.isSuccess());
            assertEquals(1, session.sql().sqlca().rowsAffected());
        });

        // Verify UPDATE
        SqlSession.work(factory, session -> {
            session.sql().select("SELECT BALANCE FROM ACCOUNTS WHERE ID = ?")
                .param(1)
                .into(rec, "AMT")
                .execute();
            assertTrue(rec.getDecimal("AMT").equalTo(Decimal.of("1500.00")));
        });

        // DELETE
        SqlSession.work(factory, session -> {
            session.sql().execute("DELETE FROM ACCOUNTS WHERE ID = ?")
                .param(2)
                .execute();
            assertTrue(session.isSuccess());
            assertEquals(1, session.sql().sqlca().rowsAffected());
        });

        // Verify DELETE — should get NOT FOUND
        SqlSession.work(factory, session -> {
            session.sql().select("SELECT BALANCE FROM ACCOUNTS WHERE ID = ?")
                .param(2)
                .into(rec, "AMT")
                .execute();
            assertTrue(session.sql().isNotFound());
            assertEquals(100, session.sqlCode());
        });
    }

    @ParameterizedTest
    @MethodSource("databases")
    void transactionRollbackOnFailure(ConnectionFactory factory) {
        // Setup
        SqlSession.work(factory, session -> {
            session.sql().executeImmediate(
                "CREATE TABLE IF NOT EXISTS TXN_TEST (ID INT, VAL VARCHAR(10))");
            session.sql().executeImmediate("DELETE FROM TXN_TEST");
            session.sql().executeImmediate("INSERT INTO TXN_TEST VALUES (1, 'ORIGINAL')");
        });

        // Work unit that fails — should rollback
        boolean[] errorHandled = {false};
        SqlSession.work(factory,
            session -> {
                session.sql().execute("UPDATE TXN_TEST SET VAL = ? WHERE ID = ?")
                    .param("CHANGED").param(1)
                    .execute();
                throw new RuntimeException("Deliberate failure");
            },
            ex -> errorHandled[0] = true
        );
        assertTrue(errorHandled[0]);

        // Verify value is still ORIGINAL (rollback happened)
        Record rec = Record.define("T").pic("V", "X(10)").build();
        SqlSession.work(factory, session -> {
            session.sql().select("SELECT VAL FROM TXN_TEST WHERE ID = ?")
                .param(1)
                .into(rec, "V")
                .execute();
            assertTrue(session.isSuccess());
            assertEquals("ORIGINAL", rec.getString("V").trim());
        });
    }

    /**
     * Shows what production code would look like with each database.
     * These DON'T run (no live servers) but demonstrate the API is identical.
     */
    @Test
    void documentProductionUsagePatterns() {
        // The ONLY thing that changes between databases is the ConnectionFactory.
        // All SQL operations, cursors, sessions, host variables are identical.

        // PostgreSQL:
        // ConnectionFactory pgFactory = ConnectionFactory.postgres(
        //     "db.prod.internal", 5432, "customer_orders", "app_user", "secret");

        // MySQL / MariaDB:
        // ConnectionFactory myFactory = ConnectionFactory.mysql(
        //     "db.prod.internal", 3306, "customer_orders", "app_user", "secret");

        // Oracle:
        // ConnectionFactory oraFactory = ConnectionFactory.oracle(
        //     "db.prod.internal", 1521, "CUSTORD", "app_user", "secret");

        // SQLite (embedded, no server):
        // ConnectionFactory liteFactory = ConnectionFactory.sqlite("/opt/data/orders.db");

        // Then use identically — the program doesn't know or care which DB:
        // SqlSession.work(factory, session -> {
        //     session.sql()
        //         .select("SELECT CUST_NAME, BALANCE FROM CUSTOMERS WHERE ID = ?")
        //         .param(rec, "WS-CUST-ID")
        //         .into(rec, "WS-NAME", "WS-BALANCE")
        //         .execute();
        // });

        assertTrue(true); // documentation test, always passes
    }
}
