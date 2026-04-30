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
 *   <li>{@link #dataSource(javax.sql.DataSource)} — delegates to a DataSource (use with HikariCP, etc.)</li>
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

    // ── Built-in factories ──────────────────────────────────────────

    /**
     * Simple factory using DriverManager. No pooling — each acquire() creates
     * a new connection, each release() closes it.
     * <p>
     * Default: explicit commit required (autoCommit=false). This matches COBOL
     * transactional semantics where COMMIT is a deliberate act.
     */
    static ConnectionFactory simple(String url, String user, String password) {
        return simple(url, user, password, false);
    }

    /**
     * Simple factory with explicit auto-commit control.
     *
     * @param autoCommit false = explicit commit required (default, COBOL-style);
     *                   true = each statement commits immediately
     */
    static ConnectionFactory simple(String url, String user, String password,
                                    boolean autoCommit) {
        return () -> {
            try {
                Connection conn = java.sql.DriverManager.getConnection(url, user, password);
                conn.setAutoCommit(autoCommit);
                return conn;
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Failed to acquire connection: " + url, e);
            }
        };
    }

    /**
     * Factory backed by a javax.sql.DataSource (HikariCP, Tomcat JDBC, etc.).
     * Default: explicit commit required (autoCommit=false).
     */
    static ConnectionFactory dataSource(javax.sql.DataSource ds) {
        return dataSource(ds, false);
    }

    /**
     * Factory backed by a DataSource with explicit auto-commit control.
     */
    static ConnectionFactory dataSource(javax.sql.DataSource ds, boolean autoCommit) {
        return () -> {
            try {
                Connection conn = ds.getConnection();
                conn.setAutoCommit(autoCommit);
                return conn;
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Failed to acquire connection from DataSource", e);
            }
        };
    }

    /**
     * Caching wrapper — maintains a pool of up to {@code maxSize} connections.
     * Released connections are returned to the pool rather than closed.
     * <p>
     * For production, use a proper pool (HikariCP) via {@link #dataSource}.
     * This is useful for testing and lightweight use cases.
     */
    static ConnectionFactory cached(ConnectionFactory delegate, int maxSize) {
        return new CachedConnectionFactory(delegate, maxSize);
    }
}
