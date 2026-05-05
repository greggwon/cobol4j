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
package org.cobol4j.schema;

import org.cobol4j.ConnectionFactory;
import org.cobol4j.Decimal;
import org.cobol4j.Record;
import org.cobol4j.SqlSession;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the schema management system: Record-to-table mapping,
 * version tracking, auto-migration, and CRUD via RecordStore.
 */
class SchemaManagerTest {

    // ── Helper: build a standard customer Record ──────────────────────

    private Record customerRecord() {
        return Record.define("CUSTOMER-RECORD")
                .pic("CUST-ID", "9(5)")
                .pic("CUST-NAME", "X(30)")
                .pic("CUST-BALANCE", "S9(7)V99")
                .pic("CUST-STATUS", "X")
                .build();
    }

    // ── Table creation ───────────────────────────────────────────────

    @Test
    void createTableFromRecord() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_create");
        Record rec = customerRecord();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", rec, cfg -> cfg.primaryKey("CUST-ID"))
                .build();

        schema.migrate();

        // Verify table exists by querying it
        SqlSession.work(factory, session -> {
            try {
                ResultSet rs = session.connection().getMetaData()
                        .getTables(null, null, "CUSTOMERS", null);
                assertTrue(rs.next(), "CUSTOMERS table should exist");
                rs.close();
            } catch (SQLException e) {
                fail("Failed to check table existence: " + e.getMessage());
            }
        });
    }

    // ── CRUD operations ──────────────────────────────────────────────

    @Test
    void insertAndFindByKey() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_insert");
        Record rec = customerRecord();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", rec, cfg -> cfg.primaryKey("CUST-ID"))
                .build();
        schema.migrate();

        RecordStore store = schema.store("CUSTOMERS");

        // Insert
        SqlSession.work(factory, session -> {
            rec.move("CUST-ID", 12345L);
            rec.move("CUST-NAME", "ALICE JONES");
            rec.move("CUST-BALANCE", Decimal.of("1500.75"));
            rec.move("CUST-STATUS", "A");
            store.insert(session, rec);
        });

        // Find by key
        Record found = customerRecord();
        SqlSession.work(factory, session -> {
            boolean exists = store.findByKey(session, found, "12345");
            assertTrue(exists, "Should find inserted record");
            assertEquals("ALICE JONES", found.getString("CUST-NAME").trim());
            assertTrue(found.getDecimal("CUST-BALANCE").equalTo(Decimal.of("1500.75")));
            assertEquals("A", found.getString("CUST-STATUS").trim());
        });
    }

    @Test
    void updateRecord() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_update");
        Record rec = customerRecord();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", rec, cfg -> cfg.primaryKey("CUST-ID"))
                .build();
        schema.migrate();

        RecordStore store = schema.store("CUSTOMERS");

        // Insert initial
        SqlSession.work(factory, session -> {
            rec.move("CUST-ID", 100L);
            rec.move("CUST-NAME", "BOB SMITH");
            rec.move("CUST-BALANCE", Decimal.of("500.00"));
            rec.move("CUST-STATUS", "A");
            store.insert(session, rec);
        });

        // Update balance
        SqlSession.work(factory, session -> {
            rec.move("CUST-ID", 100L);
            rec.move("CUST-NAME", "BOB SMITH");
            rec.move("CUST-BALANCE", Decimal.of("750.50"));
            rec.move("CUST-STATUS", "A");
            store.update(session, rec);
        });

        // Verify update
        Record found = customerRecord();
        SqlSession.work(factory, session -> {
            boolean exists = store.findByKey(session, found, "100");
            assertTrue(exists);
            assertTrue(found.getDecimal("CUST-BALANCE").equalTo(Decimal.of("750.50")));
        });
    }

    @Test
    void deleteRecord() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_delete");
        Record rec = customerRecord();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", rec, cfg -> cfg.primaryKey("CUST-ID"))
                .build();
        schema.migrate();

        RecordStore store = schema.store("CUSTOMERS");

        // Insert
        SqlSession.work(factory, session -> {
            rec.move("CUST-ID", 200L);
            rec.move("CUST-NAME", "CAROL");
            rec.move("CUST-BALANCE", Decimal.of("100.00"));
            rec.move("CUST-STATUS", "A");
            store.insert(session, rec);
        });

        // Delete
        SqlSession.work(factory, session -> {
            rec.move("CUST-ID", 200L);
            store.delete(session, rec);
        });

        // Verify gone
        Record found = customerRecord();
        SqlSession.work(factory, session -> {
            boolean exists = store.findByKey(session, found, "200");
            assertFalse(exists, "Record should have been deleted");
        });
    }

    @Test
    void findAll() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_findall");
        Record rec = customerRecord();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", rec, cfg -> cfg.primaryKey("CUST-ID"))
                .build();
        schema.migrate();

        RecordStore store = schema.store("CUSTOMERS");

        // Insert 3 records
        SqlSession.work(factory, session -> {
            for (int i = 1; i <= 3; i++) {
                rec.move("CUST-ID", (long) i);
                rec.move("CUST-NAME", "CUSTOMER-" + i);
                rec.move("CUST-BALANCE", Decimal.of(String.valueOf(i * 100)));
                rec.move("CUST-STATUS", "A");
                store.insert(session, rec);
            }
        });

        // FindAll
        SqlSession.work(factory, session -> {
            long count = store.count(session);
            assertEquals(3, count);

            List<String> names = new ArrayList<>();
            Record row = customerRecord();
            store.findAll(session, row, r -> {
                names.add(r.getString("CUST-NAME").trim());
            });
            assertEquals(3, names.size());
        });
    }

    // ── Schema version tracking ──────────────────────────────────────

    @Test
    void schemaVersionTracked() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_version");
        Record rec = customerRecord();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", rec, cfg -> cfg.primaryKey("CUST-ID"))
                .build();
        schema.migrate();

        // Verify COBOL4J_SCHEMA has an entry
        SqlSession.work(factory, session -> {
            try {
                PreparedStatement ps = session.connection().prepareStatement(
                        "SELECT VERSION, RECORD_HASH FROM COBOL4J_SCHEMA WHERE TABLE_NAME = ?");
                ps.setString(1, "CUSTOMERS");
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next(), "Schema entry should exist");
                assertEquals(1, rs.getInt("VERSION"));
                assertNotNull(rs.getString("RECORD_HASH"));
                assertFalse(rs.getString("RECORD_HASH").isEmpty());
                rs.close();
                ps.close();
            } catch (SQLException e) {
                fail("Failed to query schema table: " + e.getMessage());
            }
        });
    }

    // ── Schema migration ─────────────────────────────────────────────

    @Test
    void schemaMigrationAddsColumn() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_migrate");

        // First version: 3 fields
        Record v1 = Record.define("CUST")
                .pic("CUST-ID", "9(5)")
                .pic("CUST-NAME", "X(30)")
                .pic("CUST-STATUS", "X")
                .build();

        SchemaManager schema1 = SchemaManager.using(factory)
                .table("CUSTOMERS", v1, cfg -> cfg.primaryKey("CUST-ID"))
                .build();
        schema1.migrate();

        // Second version: add CUST-BALANCE field
        Record v2 = Record.define("CUST")
                .pic("CUST-ID", "9(5)")
                .pic("CUST-NAME", "X(30)")
                .pic("CUST-STATUS", "X")
                .pic("CUST-BALANCE", "S9(7)V99")
                .build();

        SchemaManager schema2 = SchemaManager.using(factory)
                .table("CUSTOMERS", v2, cfg -> cfg.primaryKey("CUST-ID"))
                .build();
        schema2.migrate();

        // Verify new column exists by inserting a full record
        RecordStore store = schema2.store("CUSTOMERS");
        SqlSession.work(factory, session -> {
            v2.move("CUST-ID", 1L);
            v2.move("CUST-NAME", "TEST");
            v2.move("CUST-STATUS", "A");
            v2.move("CUST-BALANCE", Decimal.of("999.99"));
            store.insert(session, v2);

            Record found = v2.newInstance();
            boolean exists = store.findByKey(session, found, "1");
            assertTrue(exists);
            assertTrue(found.getDecimal("CUST-BALANCE").equalTo(Decimal.of("999.99")));
        });

        // Verify version was incremented
        SqlSession.work(factory, session -> {
            try {
                PreparedStatement ps = session.connection().prepareStatement(
                        "SELECT VERSION FROM COBOL4J_SCHEMA WHERE TABLE_NAME = ?");
                ps.setString(1, "CUSTOMERS");
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(2, rs.getInt("VERSION"));
                rs.close();
                ps.close();
            } catch (SQLException e) {
                fail(e.getMessage());
            }
        });
    }

    @Test
    void schemaMigrationDetectsNoChange() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_nochange");
        Record rec = customerRecord();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", rec, cfg -> cfg.primaryKey("CUST-ID"))
                .build();

        // Migrate twice
        schema.migrate();
        schema.migrate();

        // Version should still be 1
        SqlSession.work(factory, session -> {
            try {
                PreparedStatement ps = session.connection().prepareStatement(
                        "SELECT VERSION FROM COBOL4J_SCHEMA WHERE TABLE_NAME = ?");
                ps.setString(1, "CUSTOMERS");
                ResultSet rs = ps.executeQuery();
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("VERSION"), "Version should not increment on no-op migration");
                rs.close();
                ps.close();
            } catch (SQLException e) {
                fail(e.getMessage());
            }
        });
    }

    // ── Multiple tables ──────────────────────────────────────────────

    @Test
    void multipleTablesManaged() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_multi");

        Record custRec = Record.define("CUST")
                .pic("CUST-ID", "9(5)")
                .pic("CUST-NAME", "X(20)")
                .build();

        Record orderRec = Record.define("ORDER")
                .pic("ORDER-ID", "9(8)")
                .pic("ORDER-AMOUNT", "S9(7)V99")
                .build();

        SchemaManager schema = SchemaManager.using(factory)
                .table("CUSTOMERS", custRec, cfg -> cfg.primaryKey("CUST-ID"))
                .table("ORDERS", orderRec, cfg -> cfg.primaryKey("ORDER-ID"))
                .build();

        schema.migrate();

        // Verify both tables exist and work
        RecordStore custStore = schema.store("CUSTOMERS");
        RecordStore orderStore = schema.store("ORDERS");

        SqlSession.work(factory, session -> {
            custRec.move("CUST-ID", 1L);
            custRec.move("CUST-NAME", "ALICE");
            custStore.insert(session, custRec);

            orderRec.move("ORDER-ID", 10000001L);
            orderRec.move("ORDER-AMOUNT", Decimal.of("250.00"));
            orderStore.insert(session, orderRec);

            assertEquals(1, custStore.count(session));
            assertEquals(1, orderStore.count(session));
        });
    }

    // ── Database-specific tests ──────────────────────────────────────

    @Test
    void worksWithH2() {
        ConnectionFactory factory = ConnectionFactory.h2InMemory("schema_h2_full");
        fullCrudCycle(factory);
    }

    @Test
    void worksWithSQLite() {
        ConnectionFactory factory = ConnectionFactory.sqliteInMemory();
        fullCrudCycle(factory);
    }

    /** Full CRUD cycle exercising all RecordStore operations. */
    private void fullCrudCycle(ConnectionFactory factory) {
        Record rec = Record.define("ITEM")
                .pic("ITEM-ID", "9(5)")
                .pic("ITEM-NAME", "X(20)")
                .pic("ITEM-PRICE", "S9(5)V99")
                .pic("ITEM-QTY", "9(4)")
                .build();

        SchemaManager schema = SchemaManager.using(factory)
                .table("ITEMS", rec, cfg -> cfg.primaryKey("ITEM-ID"))
                .build();

        schema.dropAll();
        schema.migrate();

        RecordStore store = schema.store("ITEMS");

        // INSERT
        SqlSession.work(factory, session -> {
            rec.move("ITEM-ID", 1L);
            rec.move("ITEM-NAME", "WIDGET");
            rec.move("ITEM-PRICE", Decimal.of("19.99"));
            rec.move("ITEM-QTY", 100L);
            store.insert(session, rec);

            rec.move("ITEM-ID", 2L);
            rec.move("ITEM-NAME", "GADGET");
            rec.move("ITEM-PRICE", Decimal.of("49.50"));
            rec.move("ITEM-QTY", 50L);
            store.insert(session, rec);
        });

        // SELECT by key
        Record found = rec.newInstance();
        SqlSession.work(factory, session -> {
            assertTrue(store.findByKey(session, found, "1"));
            assertEquals("WIDGET", found.getString("ITEM-NAME").trim());
            assertTrue(found.getDecimal("ITEM-PRICE").equalTo(Decimal.of("19.99")));
            assertEquals(100, found.getInt("ITEM-QTY"));
        });

        // UPDATE
        SqlSession.work(factory, session -> {
            found.move("ITEM-ID", 1L);
            found.move("ITEM-NAME", "WIDGET-PRO");
            found.move("ITEM-PRICE", Decimal.of("24.99"));
            found.move("ITEM-QTY", 75L);
            store.update(session, found);
        });

        // Verify UPDATE
        Record updated = rec.newInstance();
        SqlSession.work(factory, session -> {
            assertTrue(store.findByKey(session, updated, "1"));
            assertEquals("WIDGET-PRO", updated.getString("ITEM-NAME").trim());
            assertTrue(updated.getDecimal("ITEM-PRICE").equalTo(Decimal.of("24.99")));
        });

        // COUNT
        SqlSession.work(factory, session -> {
            assertEquals(2, store.count(session));
        });

        // FIND ALL
        SqlSession.work(factory, session -> {
            List<String> names = new ArrayList<>();
            Record row = rec.newInstance();
            store.findAll(session, row, r -> names.add(r.getString("ITEM-NAME").trim()));
            assertEquals(2, names.size());
            assertTrue(names.contains("WIDGET-PRO"));
            assertTrue(names.contains("GADGET"));
        });

        // FIND WHERE
        SqlSession.work(factory, session -> {
            List<String> expensive = new ArrayList<>();
            Record row = rec.newInstance();
            store.findWhere(session, row, "ITEM_PRICE > 30", r ->
                    expensive.add(r.getString("ITEM-NAME").trim()));
            assertEquals(1, expensive.size());
            assertEquals("GADGET", expensive.get(0));
        });

        // DELETE
        SqlSession.work(factory, session -> {
            found.move("ITEM-ID", 2L);
            store.delete(session, found);
            assertEquals(1, store.count(session));
        });

        // Verify DELETE
        SqlSession.work(factory, session -> {
            assertFalse(store.findByKey(session, found, "2"));
        });

        // Clean up
        schema.dropAll();
    }
}
