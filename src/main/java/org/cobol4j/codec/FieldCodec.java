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

/**
 * SPI for field-level data encoding/decoding within a Record's byte buffer.
 * <p>
 * A FieldCodec handles conversion between a Java type and raw bytes stored
 * in a specific field range. Use for fields that hold structured data
 * (JSON fragments, binary protocols, custom formats) rather than standard
 * COBOL PIC-defined data.
 * <p>
 * Register via {@link CodecRegistry#register(FieldCodec)} or Java's ServiceLoader
 * mechanism ({@code META-INF/services/org.cobol4j.codec.FieldCodec}).
 *
 * @param <T> the Java type this codec produces/consumes
 */
public interface FieldCodec<T> {

    /** Unique name for this codec (e.g., "json", "xml", "protobuf"). */
    String name();

    /** The Java class this codec decodes to. */
    Class<T> type();

    /**
     * Decode raw bytes from a field range into the Java type.
     *
     * @param buffer the Record's byte buffer
     * @param offset start of the field
     * @param length byte length of the field
     * @return the decoded value
     */
    T decode(byte[] buffer, int offset, int length);

    /**
     * Encode a Java value into raw bytes for storage in a field range.
     *
     * @param value  the value to encode
     * @param buffer the Record's byte buffer to write into
     * @param offset start of the field
     * @param length max byte length available
     * @return number of bytes written
     */
    int encode(T value, byte[] buffer, int offset, int length);
}
