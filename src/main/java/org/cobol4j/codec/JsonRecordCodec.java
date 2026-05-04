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

import org.cobol4j.Decimal;
import org.cobol4j.FieldDef;
import org.cobol4j.Record;

/**
 * Built-in JSON codec — serializes/deserializes Records to/from JSON.
 * Hand-written, zero dependencies (no Jackson, no Gson).
 * <p>
 * Maps to COBOL's JSON GENERATE / JSON PARSE verbs.
 * <p>
 * Generated JSON format:
 * <pre>{@code
 * {
 *   "CUST-ID": "C001",
 *   "CUST-NAME": "ALICE SMITH",
 *   "CUST-BALANCE": 50000.00
 * }
 * }</pre>
 * Numeric fields emit unquoted numbers; alphanumeric fields emit quoted strings.
 */
final class JsonRecordCodec implements RecordCodec {

    @Override
    public String name() { return "json"; }

    @Override
    public String generate(Record record) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        boolean first = true;

        for (String fieldName : record.fieldNames()) {
            FieldDef fd = record.fieldDef(fieldName);
            if (fd.isGroup()) continue;
            if (fd.isArray()) continue;

            if (!first) sb.append(",\n");
            first = false;

            sb.append("  \"").append(fieldName).append("\": ");

            if (fd.isNumeric()) {
                Decimal val = record.getDecimal(fieldName);
                sb.append(val.toString());
            } else {
                String val = record.getString(fieldName).trim();
                sb.append("\"").append(escapeJson(val)).append("\"");
            }
        }

        sb.append("\n}");
        return sb.toString();
    }

    @Override
    public void parse(String input, Record record) {
        // Minimal JSON object parser — handles { "key": value, ... }
        // Supports string values (quoted) and numeric values (unquoted)
        String trimmed = input.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new RuntimeException("JSON PARSE: input must be a JSON object");
        }

        // Strip outer braces
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) return;

        // Split into key-value pairs (simplistic — handles most cases)
        int i = 0;
        while (i < body.length()) {
            // Skip whitespace
            while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= body.length()) break;

            // Read key (quoted string)
            if (body.charAt(i) != '"') { i++; continue; }
            i++; // skip opening quote
            int keyStart = i;
            while (i < body.length() && body.charAt(i) != '"') i++;
            String key = body.substring(keyStart, i);
            i++; // skip closing quote

            // Skip colon
            while (i < body.length() && body.charAt(i) != ':') i++;
            i++; // skip colon

            // Skip whitespace
            while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;

            // Read value
            String value;
            if (i < body.length() && body.charAt(i) == '"') {
                // String value
                i++; // skip opening quote
                StringBuilder valBuf = new StringBuilder();
                while (i < body.length() && body.charAt(i) != '"') {
                    if (body.charAt(i) == '\\' && i + 1 < body.length()) {
                        i++; // skip escape
                        valBuf.append(body.charAt(i));
                    } else {
                        valBuf.append(body.charAt(i));
                    }
                    i++;
                }
                value = valBuf.toString();
                i++; // skip closing quote
            } else {
                // Numeric value — read until comma, whitespace, or end
                int valStart = i;
                while (i < body.length() && body.charAt(i) != ',' && body.charAt(i) != '}'
                       && !Character.isWhitespace(body.charAt(i))) i++;
                value = body.substring(valStart, i).trim();
            }

            // Apply to record
            if (record.hasField(key)) {
                FieldDef fd = record.fieldDef(key);
                if (fd.isNumeric()) {
                    record.move(key, Decimal.of(value));
                } else {
                    record.move(key, value);
                }
            }

            // Skip comma
            while (i < body.length() && (body.charAt(i) == ',' || Character.isWhitespace(body.charAt(i)))) i++;
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
