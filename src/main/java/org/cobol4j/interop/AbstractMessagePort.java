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
 * Base implementation of {@link MessagePort} with common bookkeeping.
 * <p>
 * Handles EBCDIC translation, Record-to-bytes conversion, and listener
 * thread management. Subclasses implement the transport-specific operations:
 * <ul>
 *   <li>{@link #doSend(byte[], Map)} — put bytes on the wire</li>
 *   <li>{@link #doReceive(long)} — get bytes from the wire</li>
 *   <li>{@link #doOpen()} / {@link #doClose()} — connection lifecycle</li>
 * </ul>
 */
public abstract class AbstractMessagePort implements MessagePort {

    protected final String destination;
    protected final Delivery delivery;
    protected final Ebcdic ebcdicCodec;  // null if no translation
    protected volatile boolean open;
    private volatile Thread listenerThread;
    private volatile boolean listening;

    protected AbstractMessagePort(String destination, Delivery delivery,
                                   Ebcdic ebcdicCodec) {
        this.destination = destination;
        this.delivery = delivery;
        this.ebcdicCodec = ebcdicCodec;
    }

    // ── Subclass hooks ��─────────────────────────────────────────────

    /** Open the underlying transport connection. */
    protected abstract void doOpen();

    /** Close the underlying transport connection. */
    protected abstract void doClose();

    /** Send raw bytes over the transport. */
    protected abstract void doSend(byte[] data, Map<String, String> properties);

    /**
     * Receive raw bytes from the transport.
     * @param timeoutMs milliseconds to wait (0 = non-blocking, -1 = indefinite)
     * @return the received bytes, or null on timeout/close
     */
    protected abstract byte[] doReceive(long timeoutMs);

    /** Acknowledge the last received message. Override for AT_LEAST_ONCE. */
    protected void doAcknowledge() {}

    /** Commit the messaging transaction. Override for EXACTLY_ONCE. */
    protected void doCommit() {}

    /** Rollback the messaging transaction. Override for EXACTLY_ONCE. */
    protected void doRollback() {}

    // ── MessagePort implementation ───��──────────────────────────────

    @Override
    public void open() {
        doOpen();
        open = true;
    }

    @Override
    public void close() {
        stopListening();
        open = false;
        doClose();
    }

    @Override
    public boolean isOpen() { return open; }

    @Override
    public void send(Record record) {
        send(record, Map.of());
    }

    @Override
    public void send(Record record, Map<String, String> properties) {
        byte[] data = record.buffer();
        if (ebcdicCodec != null) {
            data = ebcdicCodec.encode(data);
        }
        doSend(data, properties);
    }

    @Override
    public void send(byte[] data) {
        doSend(data, Map.of());
    }

    @Override
    public void send(byte[] data, Map<String, String> properties) {
        doSend(data, properties);
    }

    @Override
    public boolean receive(Record record) {
        return receive(record, -1);
    }

    @Override
    public byte[] receive() {
        return doReceive(-1);
    }

    @Override
    public boolean receive(Record record, long timeoutMs) {
        byte[] data = doReceive(timeoutMs);
        if (data == null) return false;
        if (ebcdicCodec != null) {
            data = ebcdicCodec.decode(data);
        }
        record.loadFrom(data);
        return true;
    }

    @Override
    public void listen(Record record, Consumer<Record> listener) {
        if (listening) throw new IllegalStateException("Already listening");
        listening = true;
        listenerThread = new Thread(() -> {
            while (listening && open) {
                if (receive(record, 1000)) {
                    try {
                        listener.accept(record);
                        if (delivery != Delivery.FIRE_AND_FORGET) {
                            acknowledge();
                        }
                    } catch (Exception e) {
                        if (delivery == Delivery.EXACTLY_ONCE) {
                            rollback();
                        }
                    }
                }
            }
        }, "MessagePort-listener-" + destination);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    @Override
    public void stopListening() {
        listening = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
            try { listenerThread.join(5000); } catch (InterruptedException ignored) {}
            listenerThread = null;
        }
    }

    @Override
    public void acknowledge() { doAcknowledge(); }

    @Override
    public void commit() { doCommit(); }

    @Override
    public void rollback() { doRollback(); }

    @Override
    public String destination() { return destination; }

    @Override
    public Delivery delivery() { return delivery; }

    @Override
    public boolean isEbcdic() { return ebcdicCodec != null; }
}
