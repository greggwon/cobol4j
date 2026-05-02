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
import java.util.function.Consumer;

/**
 * Fire-and-forget messaging — best effort, no acknowledgement required.
 * <p>
 * Suitable for notifications, metrics, logging, and any scenario where
 * occasional message loss is acceptable. Maps to MQTT QoS 0, UDP datagrams,
 * or JMS with AUTO_ACKNOWLEDGE where the consumer doesn't need confirmation.
 * <p>
 * Subclasses implement only the transport:
 * <pre>{@code
 * public class UdpNotificationPort extends FireAndForgetPort {
 *     public UdpNotificationPort(String host, int port) {
 *         super("udp://" + host + ":" + port, null);
 *     }
 *
 *     @Override protected void doOpen() { socket = new DatagramSocket(); }
 *     @Override protected void doClose() { socket.close(); }
 *     @Override protected void doSend(byte[] data, Map<String, String> props) {
 *         socket.send(new DatagramPacket(data, data.length, address, port));
 *     }
 *     @Override protected byte[] doReceive(long timeoutMs) {
 *         // receive from socket...
 *     }
 * }
 * }</pre>
 */
public abstract class FireAndForgetPort extends AbstractMessagePort {

    protected FireAndForgetPort(String destination, Ebcdic ebcdicCodec) {
        super(destination, Delivery.FIRE_AND_FORGET, ebcdicCodec);
    }

    /**
     * Send with error swallowing — fire-and-forget means send failures
     * are logged but don't propagate. Override {@link #onSendError} to
     * customize error handling.
     */
    @Override
    public void send(byte[] data, Map<String, String> properties) {
        try {
            doSend(data, properties);
        } catch (Exception e) {
            onSendError(e);
        }
    }

    /**
     * Called when a send fails. Default: prints to stderr.
     * Override for custom error reporting (logging framework, metrics, etc.).
     */
    protected void onSendError(Exception e) {
        System.err.println("FireAndForget send failed: " + e.getMessage());
    }
}
