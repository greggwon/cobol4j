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
 * A messaging port for sending and receiving COBOL records over any transport.
 * <p>
 * MessagePort abstracts the messaging substrate — JMS, IBM MQ, ZeroMQ, Kafka,
 * MQTT, or raw sockets — behind a record-oriented interface. Messages are
 * byte buffers that map directly to Record layouts (from copybooks), with
 * optional EBCDIC translation for mainframe interop.
 * <p>
 * Three delivery semantics are supported via {@link Delivery}:
 * <ul>
 *   <li><b>FIRE_AND_FORGET</b> �� best effort, no acknowledgement (MQTT QoS 0, UDP)</li>
 *   <li><b>AT_LEAST_ONCE</b> — acknowledged delivery, may duplicate (MQTT QoS 1, JMS AUTO_ACKNOWLEDGE)</li>
 *   <li><b>EXACTLY_ONCE</b> — transactional, no loss or duplication (JMS SESSION_TRANSACTED, Kafka exactly-once)</li>
 * </ul>
 *
 * <pre>{@code
 * // Send a record as a message
 * try (MessagePort port = SomePort.connect("queue://ORDERS")) {
 *     port.send(orderRecord);
 * }
 *
 * // Receive and process
 * try (MessagePort port = SomePort.connect("queue://ORDERS")) {
 *     port.receive(orderRecord, rec -> {
 *         String orderId = rec.getString("ORDER-ID").trim();
 *         Decimal amount = rec.getDecimal("ORDER-AMOUNT");
 *         // process...
 *     });
 * }
 * }</pre>
 */
public interface MessagePort extends AutoCloseable {

    /** Delivery guarantee levels. */
    enum Delivery {
        /** Best effort — no acknowledgement, fastest, may lose messages. */
        FIRE_AND_FORGET,
        /** Acknowledged — retried on failure, may deliver duplicates. */
        AT_LEAST_ONCE,
        /** Transactional — no loss, no duplication, slowest. */
        EXACTLY_ONCE
    }

    // ── Connection lifecycle ────────────────────────────────────────

    /** Open the connection to the messaging system. */
    void open();

    /** Close the connection and release resources. */
    @Override
    void close();

    /** Is the port currently open? */
    boolean isOpen();

    // ── Sending ───────────────���─────────────────────────────────────

    /**
     * Send a Record as a message. The Record's byte buffer is the message body.
     *
     * @param record the record to send
     */
    void send(Record record);

    /**
     * Send a Record with message properties/headers.
     *
     * @param record     the record to send
     * @param properties key-value headers (correlation ID, reply-to, etc.)
     */
    void send(Record record, Map<String, String> properties);

    /**
     * Send raw bytes as a message.
     */
    void send(byte[] data);

    /**
     * Send raw bytes with properties.
     */
    void send(byte[] data, Map<String, String> properties);

    // ── Receiving ───────────────��───────────────────────────────────

    /**
     * Receive the next message, loading it into the provided Record.
     * Blocks until a message is available or the port is closed.
     *
     * @param record the record to load the message into
     * @return true if a message was received, false if the port closed
     */
    boolean receive(Record record);

    /**
     * Receive the next message as raw bytes.
     * Returns null if the port closed before a message arrived.
     */
    byte[] receive();

    /**
     * Receive the next message with a timeout.
     *
     * @param record    the record to load into
     * @param timeoutMs maximum time to wait in milliseconds (0 = non-blocking)
     * @return true if a message was received, false on timeout or close
     */
    boolean receive(Record record, long timeoutMs);

    /**
     * Start a message listener — incoming messages are dispatched to the consumer.
     * The Record is reused for each message (COBOL-style single-record-area).
     *
     * @param record   the record to load each message into
     * @param listener callback for each received message
     */
    void listen(Record record, Consumer<Record> listener);

    /**
     * Stop listening for messages (if a listener is active).
     */
    void stopListening();

    // ��─ Acknowledgement ─────────────────────────��───────────────────

    /**
     * Acknowledge the last received message (for AT_LEAST_ONCE and EXACTLY_ONCE).
     * For FIRE_AND_FORGET, this is a no-op.
     */
    void acknowledge();

    /**
     * Commit the current messaging transaction (for EXACTLY_ONCE).
     * For other delivery modes, this is a no-op.
     */
    void commit();

    /**
     * Rollback the current messaging transaction (for EXACTLY_ONCE).
     * The message will be redelivered.
     */
    void rollback();

    // ── Configuration ───────────────────────────────────────────────

    /** The destination name (queue or topic). */
    String destination();

    /** The delivery guarantee level. */
    Delivery delivery();

    /** Is EBCDIC translation enabled? */
    boolean isEbcdic();
}
