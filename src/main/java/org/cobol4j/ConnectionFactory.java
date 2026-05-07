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

import java.sql.Connection;

/**
 * Factory for acquiring and releasing JDBC connections.
 * <p>
 * Implementations control the connection lifecycle — pooling, caching,
 * per-thread reuse, or direct creation. The contract is simple:
 * <ol>
 *   <li>{@link #acquire()} returns a usable connection</li>
 *   <li>{@link #release(Connection)} returns it to the pool / closes it</li>
 * </ol>
 * <p>
 * The {@link SqlSession} class enforces the try/finally pattern automatically:
 * <pre>{@code
 * try (SqlSession session = SqlSession.from(factory)) {
 *     session.sql()
 *         .select("SELECT NAME FROM CUST WHERE ID = ?")
 *         .param(custId)
 *         .into(rec, "WS-NAME")
 *         .execute();
 *     session.commit();
 * }
 * // connection released automatically
 * }</pre>
 * <p>
 * Built-in implementations:
 * <ul>
 *   <li>{@link #simple(String, String, String)} — DriverManager, no pooling</li>
 *   <li>{@code jdbc(DataSource)} — delegates to a DataSource (use with HikariCP, etc.)</li>
 *   <li>{@link #cached(ConnectionFactory, int)} — simple internal pool</li>
 * </ul>
 */
@FunctionalInterface
public interface ConnectionFactory {

    /** Acquire a connection ready for use. */
    Connection acquire();

    /** Release a connection back to the pool or close it. Default: close. */
    default void release(Connection connection) {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception ignored) {}
    }

    // ── Fluent caching qualifier ────────────────────────────────────

    /**
     * Enable or disable connection caching on this factory.
     * <pre>
     * // Wrap with caching (pool of 10):
     * ConnectionFactory.postgres(...).cached(true)
     *
     * // Explicitly no caching (new connection each time):
     * ConnectionFactory.h2("mem:test").cached(false)
     *
     * // SQLite in-memory REQUIRES caching (DB is per-connection):
     * ConnectionFactory.sqlite(":memory:").cached(true)
     * </pre>
     *
     * @param enabled true = wrap in a connection pool; false = return this unchanged
     * @return the same factory (if false) or a cached wrapper (if true)
     */
    default ConnectionFactory cached(boolean enabled) {
        return cached(enabled, 10);
    }

    /**
     * Enable caching with explicit pool size.
     */
    default ConnectionFactory cached(boolean enabled, int poolSize) {
        if (!enabled) return this;
        return new CachedConnectionFactory(this, poolSize);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PRIMARY API — for all databases via standard JDBC
    // ═══════════════════════════════════════════════════════════════

    /**
     * Standard JDBC connection factory — works with any database that has a JDBC driver.
     * This is the primary entry point for PostgreSQL, MySQL, MariaDB, Oracle, DB2, etc.
     * <p>
     * Explicit commit is the default (autoCommit=false), matching COBOL transactional
     * semantics. Chain {@code .cached(true)} if you want connection reuse.
     * <pre>
     * // PostgreSQL
     * ConnectionFactory.jdbc("jdbc:postgresql://db:5432/orders", "user", "pass").cached(true)
     *
     * // MySQL / MariaDB
     * ConnectionFactory.jdbc("jdbc:mysql://db:3306/orders", "user", "pass").cached(true)
     *
     * // Oracle
     * ConnectionFactory.jdbc("jdbc:oracle:thin:@//db:1521/ORCL", "user", "pass").cached(true)
     *
     * // DB2
     * ConnectionFactory.jdbc("jdbc:db2://db:50000/ORDERS", "user", "pass").cached(true)
     *
     * // Any JDBC URL — just put the driver on the classpath
     * ConnectionFactory.jdbc(url, user, pass).cached(true)
     * </pre>
     */
    static ConnectionFactory jdbc(String url, String user, String password) {
        return () -> {
            try {
                Connection conn = java.sql.DriverManager.getConnection(url, user, password);
                conn.setAutoCommit(false);
                return conn;
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Failed to acquire connection: " + url, e);
            }
        };
    }

    /**
     * JDBC factory backed by a javax.sql.DataSource (HikariCP, Tomcat JDBC, etc.).
     * Use when the DataSource handles pooling externally.
     */
    static ConnectionFactory jdbc(javax.sql.DataSource ds) {
        return () -> {
            try {
                Connection conn = ds.getConnection();
                conn.setAutoCommit(false);
                return conn;
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Failed to acquire connection from DataSource", e);
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════
    //  IN-MEMORY CONVENIENCE — makes the ephemeral choice explicit
    // ═══════════════════════════════════════════════════════════════

    /**
     * H2 in-memory database — data exists only for the lifetime of the JVM.
     * Connection is cached by default because H2 in-memory DBs require
     * at least one live connection to persist.
     * <p>
     * For file-based H2, use the standard JDBC API:
     * {@code ConnectionFactory.jdbc("jdbc:h2:./data/myapp", "", "")}
     * <pre>
     * ConnectionFactory.h2InMemory("testdb")   // explicit: this is ephemeral
     * </pre>
     */
    static ConnectionFactory h2InMemory(String name) {
        return jdbc("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "", "");
    }

    /**
     * SQLite in-memory database — data exists only for the lifetime of the connection.
     * Automatically cached because SQLite in-memory DBs are per-connection.
     * <p>
     * For file-based SQLite, use the standard JDBC API:
     * {@code ConnectionFactory.jdbc("jdbc:sqlite:/data/app.db", null, null)}
     * <pre>
     * ConnectionFactory.sqliteInMemory()       // explicit: this is ephemeral
     * </pre>
     */
    static ConnectionFactory sqliteInMemory() {
        ConnectionFactory base = () -> {
            try {
                Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:");
                conn.setAutoCommit(false);
                return conn;
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Failed to acquire SQLite in-memory connection", e);
            }
        };
        return base.cached(true, 1);
    }

    // ── Deprecated aliases — use jdbc() or the InMemory variants ────

    /** @Deprecated Use {@link #h2InMemory(String)} or {@code jdbc("jdbc:h2:...")} */
    static ConnectionFactory h2(String nameOrPath) {
        if (nameOrPath.startsWith("mem:")) {
            return h2InMemory(nameOrPath.substring(4));
        }
        return jdbc("jdbc:h2:" + nameOrPath + ";DB_CLOSE_DELAY=-1", "", "");
    }

    /** @Deprecated Use {@link #sqliteInMemory()} or {@code jdbc("jdbc:sqlite:...")} */
    static ConnectionFactory sqlite(String pathOrMemory) {
        if (pathOrMemory.contains(":memory:")) {
            return sqliteInMemory();
        }
        return jdbc("jdbc:sqlite:" + pathOrMemory, null, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  CACHING — connection pool wrapper
    // ═══════════════════════════════════════════════════════════════

    /**
     * Wrap any factory with a connection cache.
     * Prefer {@code .cached(true)} fluent qualifier on the factory itself.
     */
    static ConnectionFactory cached(ConnectionFactory delegate, int maxSize) {
        return new CachedConnectionFactory(delegate, maxSize);
    }

    // Keep simple() as internal for backward compat with tests
    /** @Deprecated Use {@link #jdbc(String, String, String)} instead. */
    static ConnectionFactory simple(String url, String user, String password) {
        return jdbc(url, user, password);
    }
}
