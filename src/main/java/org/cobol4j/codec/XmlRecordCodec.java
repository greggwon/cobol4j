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

import org.cobol4j.FieldDef;
import org.cobol4j.Record;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.io.StringWriter;

/**
 * Built-in XML codec — serializes/deserializes Records to/from XML.
 * Uses JDK's javax.xml (no external dependencies).
 * <p>
 * Maps to COBOL's XML GENERATE / XML PARSE verbs.
 * <p>
 * Generated XML format:
 * <pre>{@code
 * <CUSTOMER-RECORD>
 *   <CUST-ID>C001</CUST-ID>
 *   <CUST-NAME>ALICE SMITH</CUST-NAME>
 *   <CUST-BALANCE>50000.00</CUST-BALANCE>
 * </CUSTOMER-RECORD>
 * }</pre>
 */
final class XmlRecordCodec implements RecordCodec {

    @Override
    public String name() { return "xml"; }

    @Override
    public String generate(Record record) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.newDocument();

            Element root = doc.createElement(record.name());
            doc.appendChild(root);

            for (String fieldName : record.fieldNames()) {
                FieldDef fd = record.fieldDef(fieldName);
                if (fd.isGroup()) continue; // skip groups, emit their children
                if (fd.isArray()) continue; // skip arrays for now

                Element elem = doc.createElement(toXmlName(fieldName));
                if (fd.isNumeric()) {
                    elem.setTextContent(record.getDecimal(fieldName).toString());
                } else {
                    elem.setTextContent(record.getString(fieldName).trim());
                }
                root.appendChild(elem);
            }

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");

            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("XML GENERATE failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void parse(String input, Record record) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(input)));
            doc.getDocumentElement().normalize();

            Element root = doc.getDocumentElement();
            NodeList children = root.getChildNodes();

            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element elem) {
                    String fieldName = fromXmlName(elem.getTagName());
                    String value = elem.getTextContent().trim();

                    if (record.hasField(fieldName)) {
                        FieldDef fd = record.fieldDef(fieldName);
                        if (fd.isNumeric()) {
                            record.move(fieldName, org.cobol4j.Decimal.of(value));
                        } else {
                            record.move(fieldName, value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("XML PARSE failed: " + e.getMessage(), e);
        }
    }

    // COBOL field names use hyphens; XML element names also allow hyphens, so direct mapping
    private static String toXmlName(String cobolName) {
        return cobolName;
    }

    private static String fromXmlName(String xmlName) {
        return xmlName;
    }
}
