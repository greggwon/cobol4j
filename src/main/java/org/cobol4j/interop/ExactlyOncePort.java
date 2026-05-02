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
package org.cobol4j.interop;

import org.cobol4j.Record;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Exactly-once delivery — transactional messaging with no loss or duplication.
 * <p>
 * Messages are held in a pending state until explicitly committed. If the
 * consumer fails or calls rollback, the message is returned to the queue
 * for redelivery. No duplicates reach the consumer's committed state.
 * <p>
 * Maps to JMS SESSION_TRANSACTED, Kafka exactly-once semantics, or
 * IBM MQ with syncpoint.
 * <p>
 * This is the delivery model that matches COBOL's transactional expectations:
 * a batch program either processes all records successfully and commits, or
 * rolls back and the input is unchanged.
 * <p>
 * Subclasses implement the transport and transaction mechanism:
 * <pre>{@code
 * public class TransactionalJmsPort extends ExactlyOncePort {
 *     @Override protected void doSend(byte[] data, Map<String, String> props) {
 *         producer.send(session.createBytesMessage(data));
 *     }
 *     @Override protected byte[] doReceive(long timeoutMs) {
 *         return ((BytesMessage) consumer.receive(timeoutMs)).getBody(byte[].class);
 *     }
 *     @Override protected void doCommit() { session.commit(); }
 *     @Override protected void doRollback() { session.rollback(); }
 * }
 * }</pre>
 *
 * <b>Usage pattern</b> — mirrors the SqlSession.work() pattern:
 * <pre>{@code
 * ExactlyOncePort port = new TransactionalJmsPort("queue://ORDERS");
 * port.open();
 * try {
 *     port.receive(orderRec);
 *     // process the order...
 *     port.send(confirmationRec);
 *     port.commit();
 * } catch (Exception e) {
 *     port.rollback();
 * } finally {
 *     port.close();
 * }
 * }</pre>
 */
public abstract class ExactlyOncePort extends AbstractMessagePort {

    private boolean inTransaction;

    protected ExactlyOncePort(String destination, Ebcdic ebcdicCodec) {
        super(destination, Delivery.EXACTLY_ONCE, ebcdicCodec);
    }

    @Override
    public void send(byte[] data, Map<String, String> properties) {
        ensureTransaction();
        doSend(data, properties);
    }

    @Override
    public boolean receive(Record record, long timeoutMs) {
        ensureTransaction();
        return super.receive(record, timeoutMs);
    }

    @Override
    public void commit() {
        doCommit();
        inTransaction = false;
    }

    @Override
    public void rollback() {
        doRollback();
        inTransaction = false;
    }

    /**
     * Execute a unit of work: receive, process, commit — or rollback on failure.
     * Mirrors the SqlSession.work() pattern for messaging.
     *
     * @param record   the record to receive into
     * @param work     the processing logic
     */
    public void work(Record record, Consumer<Record> work) {
        ensureTransaction();
        try {
            if (super.receive(record, -1)) {
                work.accept(record);
                commit();
            }
        } catch (Exception e) {
            rollback();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    /**
     * Execute a unit of work with a custom error handler.
     */
    public void work(Record record, Consumer<Record> work,
                      Consumer<Exception> errorHandler) {
        ensureTransaction();
        try {
            if (super.receive(record, -1)) {
                work.accept(record);
                commit();
            }
        } catch (Exception e) {
            rollback();
            errorHandler.accept(e);
        }
    }

    private void ensureTransaction() {
        if (!inTransaction) {
            inTransaction = true;
            // Subclasses can override doBeginTransaction if needed
            doBeginTransaction();
        }
    }

    /** Called when a new transaction begins. Override if the transport needs it. */
    protected void doBeginTransaction() {}
}
