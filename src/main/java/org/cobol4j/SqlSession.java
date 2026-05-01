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
import java.util.function.Consumer;

/**
 * A scoped SQL session — acquires a connection on creation, releases it on close.
 * <p>
 * Two usage patterns are supported:
 * <p>
 * <b>1. Explicit try-with-resources (you control the flow):</b>
 * <pre>
 * try (SqlSession session = SqlSession.from(factory)) {
 *     session.sql().select(...).param(...).into(...).execute();
 *     session.sql().execute(...).param(...).execute();
 *     session.commit();
 * }
 * // Connection automatically released (returned to pool or closed)
 * </pre>
 * <p>
 * <b>2. Unit-of-work lambda (the session manages the lifecycle):</b>
 * <pre>
 * SqlSession.work(factory, session -&gt; {
 *     session.sql().select(...).param(...).into(...).execute();
 *     session.sql().execute(...).param(...).execute();
 *     // commit happens automatically at end of block
 * });
 * // on success: commit + release
 * // on exception: rollback + release, then rethrow or handle
 * </pre>
 * <p>
 * In both patterns, if the session closes without a commit, the connection
 * is rolled back before release.
 */
public final class SqlSession implements AutoCloseable {

    private final ConnectionFactory factory;
    private final Connection connection;
    private final CobolSql sql;
    private boolean committed;

    private SqlSession(ConnectionFactory factory) {
        this.factory = factory;
        this.connection = factory.acquire();
        this.sql = CobolSql.using(connection);
        this.committed = false;
    }

    // ── Factory methods ─────────────────────────────────────────────

    /** Create a session by acquiring a connection from the factory. */
    public static SqlSession from(ConnectionFactory factory) {
        return new SqlSession(factory);
    }

    /**
     * Execute a unit of work with managed lifecycle:
     * acquire → work → commit → release (on success)
     * acquire → work → rollback → release → rethrow (on failure)
     * <p>
     * This is the equivalent of:
     * <pre>
     * try {
     *     conn = factory.acquire();
     *     // ... work ...
     *     conn.commit();
     * } catch (Exception ex) {
     *     conn.rollback();
     *     throw ex;
     * } finally {
     *     conn.release();
     * }
     * </pre>
     */
    public static void work(ConnectionFactory factory, Consumer<SqlSession> work) {
        SqlSession session = new SqlSession(factory);
        try {
            work.accept(session);
            session.commit();
        } catch (Exception ex) {
            try { session.rollback(); } catch (Exception suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
        } finally {
            session.factory.release(session.connection);
        }
    }

    /**
     * Execute a unit of work with a custom error handler instead of rethrowing.
     * The error handler receives the exception after rollback, allowing
     * logging or status-flag updates without propagating the exception.
     */
    public static void work(ConnectionFactory factory,
                             Consumer<SqlSession> work,
                             Consumer<Exception> errorHandler) {
        SqlSession session = new SqlSession(factory);
        try {
            work.accept(session);
            session.commit();
        } catch (Exception ex) {
            try { session.rollback(); } catch (Exception suppressed) {
                ex.addSuppressed(suppressed);
            }
            errorHandler.accept(ex);
        } finally {
            session.factory.release(session.connection);
        }
    }

    // ── Accessors ───────────────────────────────────────────────────

    /** The CobolSql instance for this session's connection. */
    public CobolSql sql() {
        return sql;
    }

    /** The underlying JDBC connection (for advanced use). */
    public Connection connection() {
        return connection;
    }

    /** Shortcut: current SQLCODE. */
    public int sqlCode() { return sql.sqlCode(); }

    /** Shortcut: did the last operation succeed? */
    public boolean isSuccess() { return sql.isSuccess(); }

    /** Shortcut: was the last SELECT/FETCH empty? */
    public boolean isNotFound() { return sql.isNotFound(); }

    // ── Transaction control ─────────────────────────────────────────

    /** Commit the current transaction. */
    public SqlSession commit() {
        sql.commit();
        committed = true;
        return this;
    }

    /** Rollback the current transaction. */
    public SqlSession rollback() {
        sql.rollback();
        committed = false;
        return this;
    }

    /**
     * Close the session: rollback if not committed, then release the connection.
     */
    @Override
    public void close() {
        try {
            if (!committed) {
                sql.rollback();
            }
        } finally {
            factory.release(connection);
        }
    }
}
