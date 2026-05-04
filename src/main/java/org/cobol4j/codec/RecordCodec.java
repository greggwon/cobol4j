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

/**
 * SPI for whole-record serialization/deserialization.
 * <p>
 * Maps to COBOL's XML GENERATE, XML PARSE, JSON GENERATE, JSON PARSE verbs.
 * A RecordCodec serializes all fields of a Record to/from a text format.
 * <p>
 * Register via {@link CodecRegistry#register(RecordCodec)} or Java's ServiceLoader
 * mechanism ({@code META-INF/services/org.cobol4j.codec.RecordCodec}).
 */
public interface RecordCodec {

    /** Unique name for this codec (e.g., "xml", "json"). */
    String name();

    /**
     * GENERATE — serialize a Record's fields to a string representation.
     * Equivalent to COBOL's XML GENERATE or JSON GENERATE.
     *
     * @param record the record to serialize
     * @return the serialized string (XML document, JSON object, etc.)
     */
    String generate(Record record);

    /**
     * PARSE — deserialize a string into a Record's fields.
     * Equivalent to COBOL's XML PARSE or JSON PARSE.
     * Field values in the input are matched by name and moved into the record.
     *
     * @param input  the serialized string to parse
     * @param record the record to populate
     */
    void parse(String input, Record record);
}
