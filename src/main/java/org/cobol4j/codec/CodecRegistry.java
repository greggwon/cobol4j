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
package org.cobol4j.codec;

import org.cobol4j.Record;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for field-level and record-level codecs.
 * <p>
 * Codecs are discovered via three mechanisms (in order):
 * <ol>
 *   <li><b>Built-in</b> — XML and JSON codecs included with cobol4j</li>
 *   <li><b>ServiceLoader</b> — any JAR with {@code META-INF/services/org.cobol4j.codec.FieldCodec}
 *       or {@code META-INF/services/org.cobol4j.codec.RecordCodec} on the classpath</li>
 *   <li><b>Programmatic</b> — {@link #register(FieldCodec)} / {@link #register(RecordCodec)}</li>
 * </ol>
 *
 * <pre>{@code
 * // Get the built-in JSON codec
 * RecordCodec json = CodecRegistry.instance().recordCodec("json");
 * String output = json.generate(customerRecord);
 *
 * // Register a custom codec
 * CodecRegistry.instance().register(new ProtobufRecordCodec());
 *
 * // Use it
 * RecordCodec proto = CodecRegistry.instance().recordCodec("protobuf");
 * }</pre>
 */
public final class CodecRegistry {

    private static final CodecRegistry INSTANCE = new CodecRegistry();

    private final Map<String, FieldCodec<?>> fieldCodecs = new ConcurrentHashMap<>();
    private final Map<String, RecordCodec> recordCodecs = new ConcurrentHashMap<>();

    private CodecRegistry() {
        // Load built-in codecs
        registerBuiltins();
        // Discover codecs via ServiceLoader
        discoverFromServiceLoader();
    }

    /** The singleton registry instance. */
    public static CodecRegistry instance() {
        return INSTANCE;
    }

    // ── Registration ────────────────────────────────────────────────

    /** Register a field-level codec. */
    public <T> void register(FieldCodec<T> codec) {
        fieldCodecs.put(codec.name().toLowerCase(), codec);
    }

    /** Register a record-level codec. */
    public void register(RecordCodec codec) {
        recordCodecs.put(codec.name().toLowerCase(), codec);
    }

    // ── Lookup ──────────────────────────────────────────────────────

    /** Get a field codec by name. Returns null if not registered. */
    @SuppressWarnings("unchecked")
    public <T> FieldCodec<T> fieldCodec(String name) {
        return (FieldCodec<T>) fieldCodecs.get(name.toLowerCase());
    }

    /** Get a record codec by name. Returns null if not registered. */
    public RecordCodec recordCodec(String name) {
        return recordCodecs.get(name.toLowerCase());
    }

    /** Check if a field codec is registered. */
    public boolean hasFieldCodec(String name) {
        return fieldCodecs.containsKey(name.toLowerCase());
    }

    /** Check if a record codec is registered. */
    public boolean hasRecordCodec(String name) {
        return recordCodecs.containsKey(name.toLowerCase());
    }

    // ── Convenience — generate/parse directly ───────────────────────

    /** Generate XML from a Record. */
    public String toXml(Record record) {
        RecordCodec xml = recordCodec("xml");
        if (xml == null) throw new IllegalStateException("XML codec not registered");
        return xml.generate(record);
    }

    /** Parse XML into a Record. */
    public void fromXml(String xml, Record record) {
        RecordCodec codec = recordCodec("xml");
        if (codec == null) throw new IllegalStateException("XML codec not registered");
        codec.parse(xml, record);
    }

    /** Generate JSON from a Record. */
    public String toJson(Record record) {
        RecordCodec json = recordCodec("json");
        if (json == null) throw new IllegalStateException("JSON codec not registered");
        return json.generate(record);
    }

    /** Parse JSON into a Record. */
    public void fromJson(String json, Record record) {
        RecordCodec codec = recordCodec("json");
        if (codec == null) throw new IllegalStateException("JSON codec not registered");
        codec.parse(json, record);
    }

    // ── Internal ────────────────────────────────────────────────────

    private void registerBuiltins() {
        register(new XmlRecordCodec());
        register(new JsonRecordCodec());
    }

    @SuppressWarnings("rawtypes")
    private void discoverFromServiceLoader() {
        ServiceLoader<FieldCodec> fieldLoader = ServiceLoader.load(FieldCodec.class);
        for (FieldCodec codec : fieldLoader) {
            fieldCodecs.putIfAbsent(codec.name().toLowerCase(), codec);
        }
        ServiceLoader<RecordCodec> recordLoader = ServiceLoader.load(RecordCodec.class);
        for (RecordCodec codec : recordLoader) {
            recordCodecs.putIfAbsent(codec.name().toLowerCase(), codec);
        }
    }
}
