package org.cobol4j;

import java.sql.Connection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Simple connection pool that caches released connections for reuse.
 * <p>
 * Connections are acquired from the delegate factory on first use, then
 * returned to an internal queue on release. Subsequent acquires pull from
 * the queue before creating new connections.
 * <p>
 * For production use, prefer a battle-tested pool like HikariCP via
 * {@link ConnectionFactory#dataSource}. This implementation is useful for
 * testing and lightweight scenarios.
 */
final class CachedConnectionFactory implements ConnectionFactory {

    private final ConnectionFactory delegate;
    private final int maxSize;
    private final BlockingQueue<Connection> pool;
    private int created;

    CachedConnectionFactory(ConnectionFactory delegate, int maxSize) {
        this.delegate = delegate;
        this.maxSize = maxSize;
        this.pool = new LinkedBlockingQueue<>(maxSize);
        this.created = 0;
    }

    @Override
    public Connection acquire() {
        // Try to reuse a pooled connection
        Connection conn = pool.poll();
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    return conn;
                }
            } catch (Exception ignored) {}
            // Connection was stale — fall through to create a new one
        }

        // Create a new connection if under the limit
        synchronized (this) {
            if (created < maxSize) {
                created++;
                return delegate.acquire();
            }
        }

        // At capacity — block until one is released
        try {
            conn = pool.take();
            if (!conn.isClosed()) {
                return conn;
            }
        } catch (Exception e) {
            throw new RuntimeException("Interrupted waiting for connection", e);
        }

        // Stale connection from blocking wait — create a replacement
        return delegate.acquire();
    }

    @Override
    public void release(Connection connection) {
        if (connection == null) return;
        try {
            if (connection.isClosed()) return;
            // Reset connection state before returning to pool
            if (!connection.getAutoCommit()) {
                try { connection.rollback(); } catch (Exception ignored) {}
            }
            if (!pool.offer(connection)) {
                // Pool is full — close this one
                connection.close();
            }
        } catch (Exception e) {
            try { connection.close(); } catch (Exception ignored) {}
        }
    }
}
