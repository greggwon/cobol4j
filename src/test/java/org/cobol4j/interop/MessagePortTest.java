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

import org.cobol4j.Decimal;
import org.cobol4j.Record;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class MessagePortTest {

    @Test
    void sendAndReceiveRecord() {
        var queue = new LinkedBlockingQueue<byte[]>();
        Record rec = Record.define("MSG")
            .pic("NAME", "X(20)")
            .pic("AMOUNT", "X(10)")
            .build();

        var sender = InMemoryMessagePort.fireAndForget("test-q", queue);
        var receiver = InMemoryMessagePort.fireAndForget("test-q", queue);
        sender.open();
        receiver.open();

        rec.move("NAME", "ALICE").move("AMOUNT", "1500.00");
        sender.send(rec);

        Record result = Record.define("MSG")
            .pic("NAME", "X(20)")
            .pic("AMOUNT", "X(10)")
            .build();

        assertTrue(receiver.receive(result, 1000));
        assertEquals("ALICE", result.getString("NAME").trim());
        assertEquals("1500.00", result.getString("AMOUNT").trim());

        sender.close();
        receiver.close();
    }

    @Test
    void sendMultipleReceiveInOrder() {
        var queue = new LinkedBlockingQueue<byte[]>();
        Record rec = Record.define("MSG").pic("ID", "X(5)").build();

        var port = InMemoryMessagePort.fireAndForget("q", queue);
        port.open();

        rec.move("ID", "ONE"); port.send(rec);
        rec.move("ID", "TWO"); port.send(rec);
        rec.move("ID", "THREE"); port.send(rec);

        Record out = Record.define("MSG").pic("ID", "X(5)").build();
        assertTrue(port.receive(out, 100));
        assertEquals("ONE", out.getString("ID").trim());
        assertTrue(port.receive(out, 100));
        assertEquals("TWO", out.getString("ID").trim());
        assertTrue(port.receive(out, 100));
        assertEquals("THREE", out.getString("ID").trim());

        port.close();
    }

    @Test
    void receiveTimesOut() {
        var queue = new LinkedBlockingQueue<byte[]>();
        var port = InMemoryMessagePort.fireAndForget("empty-q", queue);
        port.open();

        Record rec = Record.define("MSG").pic("F", "X(5)").build();
        assertFalse(port.receive(rec, 50)); // should timeout, not block forever

        port.close();
    }

    @Test
    void listenReceivesAsync() throws Exception {
        var queue = new LinkedBlockingQueue<byte[]>();
        Record rec = Record.define("MSG").pic("VAL", "X(10)").build();

        var sender = InMemoryMessagePort.fireAndForget("async-q", queue);
        var receiver = InMemoryMessagePort.fireAndForget("async-q", queue);
        sender.open();
        receiver.open();

        List<String> received = new ArrayList<>();
        Record listenerRec = Record.define("MSG").pic("VAL", "X(10)").build();
        receiver.listen(listenerRec, r -> {
            synchronized (received) {
                received.add(r.getString("VAL").trim());
            }
        });

        // Send messages
        rec.move("VAL", "MSG-1"); sender.send(rec);
        rec.move("VAL", "MSG-2"); sender.send(rec);
        rec.move("VAL", "MSG-3"); sender.send(rec);

        // Give the listener time to process
        Thread.sleep(500);

        receiver.stopListening();
        sender.close();
        receiver.close();

        synchronized (received) {
            assertEquals(3, received.size());
            assertTrue(received.contains("MSG-1"));
            assertTrue(received.contains("MSG-2"));
            assertTrue(received.contains("MSG-3"));
        }
    }

    @Test
    void deliveryModes() {
        var queue = new LinkedBlockingQueue<byte[]>();

        var ff = InMemoryMessagePort.fireAndForget("q", queue);
        assertEquals(MessagePort.Delivery.FIRE_AND_FORGET, ff.delivery());

        var al = InMemoryMessagePort.atLeastOnce("q", queue);
        assertEquals(MessagePort.Delivery.AT_LEAST_ONCE, al.delivery());

        var eo = InMemoryMessagePort.exactlyOnce("q", queue);
        assertEquals(MessagePort.Delivery.EXACTLY_ONCE, eo.delivery());
    }

    @Test
    void ebcdicTranslation() {
        var queue = new LinkedBlockingQueue<byte[]>();
        Record rec = Record.define("MSG").pic("DATA", "X(10)").build();

        // Sender encodes to EBCDIC
        var sender = InMemoryMessagePort.withEbcdic("q", queue, Ebcdic.CodePage.CP037);
        sender.open();
        rec.move("DATA", "HELLO");
        sender.send(rec);
        sender.close();

        // Raw bytes on queue should be EBCDIC
        byte[] raw = queue.peek();
        assertNotNull(raw);
        // 'H' in CP037 = 0xC8, not 0x48
        assertNotEquals((byte) 'H', raw[0]);

        // Receiver decodes from EBCDIC
        var receiver = InMemoryMessagePort.withEbcdic("q", queue, Ebcdic.CodePage.CP037);
        receiver.open();
        Record result = Record.define("MSG").pic("DATA", "X(10)").build();
        assertTrue(receiver.receive(result, 100));
        assertEquals("HELLO", result.getString("DATA").trim());
        receiver.close();
    }

    @Test
    void standalonePort() {
        var port = InMemoryMessagePort.standalone("self-contained");
        port.open();

        Record rec = Record.define("MSG").pic("V", "X(5)").build();
        rec.move("V", "TEST");
        port.send(rec);

        Record out = Record.define("MSG").pic("V", "X(5)").build();
        assertTrue(port.receive(out, 100));
        assertEquals("TEST", out.getString("V").trim());

        port.close();
    }
}
