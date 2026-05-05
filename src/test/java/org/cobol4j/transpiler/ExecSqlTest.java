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
package org.cobol4j.transpiler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecSqlTest {

    @Test
    void selectIntoWithHostVars() {
        String java = transpile("""
                EXEC SQL
                    SELECT CUST_NAME, CUST_BALANCE
                    INTO :WS-NAME, :WS-BAL
                    FROM CUSTOMERS
                    WHERE CUST_ID = :WS-ID
                END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        // Should generate sql.select with ? replacing :WS-ID
        assertTrue(java.contains("sql.select("), "Should have sql.select: " + relevant(java));
        assertTrue(java.contains("?"), "Should replace host vars with ?: " + relevant(java));
        assertTrue(java.contains(".param("), "Should bind input param: " + relevant(java));
        assertTrue(java.contains("WS-ID"), "Should reference WS-ID: " + relevant(java));
        assertTrue(java.contains(".into("), "Should have INTO clause: " + relevant(java));
        assertTrue(java.contains("WS-NAME"), "Should reference output WS-NAME: " + relevant(java));
        assertTrue(java.contains("WS-BAL"), "Should reference output WS-BAL: " + relevant(java));
        // Should NOT contain :WS- (raw host var syntax)
        assertFalse(java.contains(":WS-"), "Should not have raw :HOST-VAR: " + relevant(java));
    }

    @Test
    void insertWithHostVars() {
        String java = transpile("""
                EXEC SQL
                    INSERT INTO CUSTOMERS (CUST_ID, CUST_NAME, BALANCE)
                    VALUES (:WS-ID, :WS-NAME, :WS-BAL)
                END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("sql.execute("), "Should have sql.execute: " + relevant(java));
        // 3 host vars → 3 question marks (spacing may vary from token joining)
        long qCount = relevant(java).chars().filter(c -> c == '?').count();
        assertTrue(qCount >= 3, "Should have 3+ ? placeholders: " + relevant(java));
        assertTrue(java.contains(".param("), "Should bind params: " + relevant(java));
    }

    @Test
    void updateWithHostVars() {
        String java = transpile("""
                EXEC SQL
                    UPDATE CUSTOMERS
                    SET BALANCE = :WS-BAL
                    WHERE CUST_ID = :WS-ID
                END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("sql.execute("), "Should have sql.execute: " + relevant(java));
        assertTrue(java.contains("SET BALANCE = ?"), "Should replace SET host var: " + relevant(java));
        assertTrue(java.contains("WHERE CUST_ID = ?"), "Should replace WHERE host var: " + relevant(java));
    }

    @Test
    void deleteWithHostVar() {
        String java = transpile("""
                EXEC SQL
                    DELETE FROM CUSTOMERS
                    WHERE CUST_ID = :WS-ID
                END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("sql.execute("), "Should have sql.execute: " + relevant(java));
        assertTrue(java.contains("WHERE CUST_ID = ?"), "Should replace host var: " + relevant(java));
    }

    @Test
    void commitAndRollback() {
        String java = transpile("""
                EXEC SQL COMMIT END-EXEC.
                EXEC SQL ROLLBACK END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("sql.commit()"), "Should have commit: " + relevant(java));
        assertTrue(java.contains("sql.rollback()"), "Should have rollback: " + relevant(java));
    }

    @Test
    void declareCursor() {
        String java = transpile("""
                EXEC SQL
                    DECLARE CUST-CURSOR CURSOR FOR
                    SELECT CUST_NAME, BALANCE
                    FROM CUSTOMERS
                    WHERE STATUS = :WS-STATUS
                END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("declareCursor("), "Should declare cursor: " + relevant(java));
        assertTrue(java.contains("CUST-CURSOR") || java.contains("custCursor"),
            "Should have cursor name: " + relevant(java));
        assertTrue(java.contains("?"), "Should replace host var in cursor SQL: " + relevant(java));
    }

    @Test
    void openCursor() {
        String java = transpile("""
                EXEC SQL OPEN CUST-CURSOR END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("sql.open("), "Should open cursor: " + relevant(java));
    }

    @Test
    void fetchCursor() {
        String java = transpile("""
                EXEC SQL
                    FETCH CUST-CURSOR INTO :WS-NAME, :WS-BAL
                END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("sql.fetch("), "Should fetch cursor: " + relevant(java));
        assertTrue(java.contains(".into("), "Should have INTO: " + relevant(java));
        assertTrue(java.contains("WS-NAME"), "Should reference WS-NAME: " + relevant(java));
        assertTrue(java.contains("WS-BAL"), "Should reference WS-BAL: " + relevant(java));
    }

    @Test
    void closeCursor() {
        String java = transpile("""
                EXEC SQL CLOSE CUST-CURSOR END-EXEC.
                STOP RUN.
            """);

        assertNotNull(java);
        assertTrue(java.contains("sql.close("), "Should close cursor: " + relevant(java));
    }

    // ═══════════════════════════════════════════════════════════════

    private String transpile(String procedureStatements) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. SQL-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS.
               05 WS-ID   PIC X(10).
               05 WS-NAME PIC X(30).
               05 WS-BAL  PIC S9(7)V99.
               05 WS-STATUS PIC X.
            PROCEDURE DIVISION.
            MAIN-PARA.
            """ + procedureStatements;

        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        if (java == null) fail("Transpilation failed: " + diag.errors());
        return java;
    }

    private String relevant(String java) {
        StringBuilder sb = new StringBuilder();
        for (String line : java.split("\n")) {
            if (line.contains("sql.") || line.contains(".param(") || line.contains(".into(")
                || line.contains(".execute") || line.contains("Cursor")) {
                sb.append(line.trim()).append("\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : java.substring(Math.max(0, java.length() - 400));
    }
}
