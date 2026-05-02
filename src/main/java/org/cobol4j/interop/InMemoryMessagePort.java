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
import java.util.concurrent.*;

/**
 * In-memory message port for testing and demonstration.
 * <p>
 * Uses a {@link BlockingQueue} as the message store. Supports all three delivery
 * modes (behavior differences are in the abstract base classes, not here).
 * <p>
 * For testing, create a shared queue and connect a sender and receiver:
 * <pre>{@code
 * BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
 *
 * InMemoryMessagePort sender = InMemoryMessagePort.fireAndForget("test-queue", queue);
 * InMemoryMessagePort receiver = InMemoryMessagePort.fireAndForget("test-queue", queue);
 *
 * sender.open();
 * sender.send(orderRecord);
 *
 * receiver.open();
 * receiver.receive(orderRecord);  // blocks until message available
 * }</pre>
 */
public class InMemoryMessagePort extends AbstractMessagePort {

    private final BlockingQueue<byte[]> queue;

    private InMemoryMessagePort(String destination, Delivery delivery,
                                 Ebcdic ebcdicCodec, BlockingQueue<byte[]> queue) {
        super(destination, delivery, ebcdicCodec);
        this.queue = queue;
    }

    // ── Factory methods ─────────────────────────────────────────────

    /** Fire-and-forget with a shared queue. */
    public static InMemoryMessagePort fireAndForget(String name, BlockingQueue<byte[]> queue) {
        return new InMemoryMessagePort(name, Delivery.FIRE_AND_FORGET, null, queue);
    }

    /** At-least-once with a shared queue. */
    public static InMemoryMessagePort atLeastOnce(String name, BlockingQueue<byte[]> queue) {
        return new InMemoryMessagePort(name, Delivery.AT_LEAST_ONCE, null, queue);
    }

    /** Exactly-once with a shared queue. */
    public static InMemoryMessagePort exactlyOnce(String name, BlockingQueue<byte[]> queue) {
        return new InMemoryMessagePort(name, Delivery.EXACTLY_ONCE, null, queue);
    }

    /** With EBCDIC translation. */
    public static InMemoryMessagePort withEbcdic(String name, BlockingQueue<byte[]> queue,
                                                   Ebcdic.CodePage codePage) {
        return new InMemoryMessagePort(name, Delivery.AT_LEAST_ONCE,
            Ebcdic.codePage(codePage), queue);
    }

    /** Create a new port with its own internal queue (standalone testing). */
    public static InMemoryMessagePort standalone(String name) {
        return new InMemoryMessagePort(name, Delivery.FIRE_AND_FORGET, null,
            new LinkedBlockingQueue<>());
    }

    // ── Transport implementation ────────────────────────────────────

    @Override protected void doOpen() { /* no-op for in-memory */ }
    @Override protected void doClose() { /* no-op for in-memory */ }

    @Override
    protected void doSend(byte[] data, Map<String, String> properties) {
        queue.offer(data.clone());
    }

    @Override
    protected byte[] doReceive(long timeoutMs) {
        try {
            if (timeoutMs == 0) {
                return queue.poll();
            } else if (timeoutMs < 0) {
                return queue.take();
            } else {
                return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Access the underlying queue (for testing assertions). */
    public BlockingQueue<byte[]> queue() { return queue; }
}
