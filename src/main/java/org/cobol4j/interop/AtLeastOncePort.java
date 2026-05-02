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

import java.util.Map;

/**
 * At-least-once delivery — messages are acknowledged after processing.
 * If the consumer fails before acknowledging, the message is redelivered.
 * Duplicates are possible; the consumer must be idempotent.
 * <p>
 * Maps to MQTT QoS 1, JMS AUTO_ACKNOWLEDGE or CLIENT_ACKNOWLEDGE,
 * or any broker with per-message acknowledgement.
 * <p>
 * Subclasses implement the transport and acknowledgement mechanism:
 * <pre>{@code
 * public class JmsPort extends AtLeastOncePort {
 *     @Override protected void doSend(byte[] data, Map<String, String> props) {
 *         producer.send(session.createBytesMessage(data));
 *     }
 *     @Override protected byte[] doReceive(long timeoutMs) {
 *         Message msg = consumer.receive(timeoutMs);
 *         lastMessage = msg;
 *         return ((BytesMessage) msg).getBody(byte[].class);
 *     }
 *     @Override protected void doAcknowledge() {
 *         lastMessage.acknowledge();
 *     }
 * }
 * }</pre>
 */
public abstract class AtLeastOncePort extends AbstractMessagePort {

    private int maxRetries = 3;
    private long retryDelayMs = 1000;

    protected AtLeastOncePort(String destination, Ebcdic ebcdicCodec) {
        super(destination, Delivery.AT_LEAST_ONCE, ebcdicCodec);
    }

    /** Set the maximum number of send retries before giving up. Default: 3. */
    public AtLeastOncePort maxRetries(int retries) {
        this.maxRetries = retries;
        return this;
    }

    /** Set the delay between retries in milliseconds. Default: 1000. */
    public AtLeastOncePort retryDelay(long delayMs) {
        this.retryDelayMs = delayMs;
        return this;
    }

    /**
     * Send with retry — if the first attempt fails, retry up to maxRetries times.
     */
    @Override
    public void send(byte[] data, Map<String, String> properties) {
        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                doSend(data, properties);
                return; // success
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries) {
                    onRetry(attempt + 1, e);
                    try { Thread.sleep(retryDelayMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        onSendFailed(lastError);
    }

    /**
     * Called before each retry. Override for logging.
     */
    protected void onRetry(int attempt, Exception cause) {
        System.err.println("AtLeastOnce retry " + attempt + "/" + maxRetries
            + " for " + destination + ": " + cause.getMessage());
    }

    /**
     * Called when all retries are exhausted. Default: throws RuntimeException.
     * Override for custom failure handling.
     */
    protected void onSendFailed(Exception cause) {
        throw new RuntimeException("Send failed after " + maxRetries
            + " retries to " + destination, cause);
    }
}
