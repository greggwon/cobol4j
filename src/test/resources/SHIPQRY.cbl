       IDENTIFICATION DIVISION.
       PROGRAM-ID. SHIPQRY.
      *================================================================
      * SHIPMENT QUERY — Queries all shipments for a given supplier
      * and returns summary totals via linkage.
      *
      * Called via:  CALL "SHIPQRY" USING LS-SUPPLIER-ID
      *                  LS-SUPPLIER-NAME LS-ITEM-COUNT
      *                  LS-TOTAL-QTY LS-TOTAL-VALUE LS-LATEST-DATE
      *
      * Uses a cursor to fetch all matching rows and accumulates
      * totals — the classic COBOL batch processing pattern.
      *================================================================

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-FETCH-REC.
          05 WS-F-NAME             PIC X(20).
          05 WS-F-QTY              PIC 9(5).
          05 WS-F-PRICE            PIC S9(5)V99.
          05 WS-F-DATE             PIC X(10).

       01 WS-LINE-VALUE            PIC S9(9)V99.
       01 WS-FIRST-ROW             PIC X VALUE "Y".
          88 IS-FIRST-ROW          VALUE "Y".
       01 WS-SQLCODE               PIC S9(4) COMP.

       LINKAGE SECTION.
       01 LS-SUPPLIER-ID           PIC X(4).
       01 LS-SUPPLIER-NAME         PIC X(20).
       01 LS-ITEM-COUNT            PIC 9(5).
       01 LS-TOTAL-QTY             PIC 9(7).
       01 LS-TOTAL-VALUE           PIC S9(9)V99.
       01 LS-LATEST-DATE           PIC X(10).

       PROCEDURE DIVISION USING LS-SUPPLIER-ID LS-SUPPLIER-NAME
           LS-ITEM-COUNT LS-TOTAL-QTY LS-TOTAL-VALUE LS-LATEST-DATE.

      *================================================================
       INIT-ACCUMULATORS.
           MOVE SPACES TO LS-SUPPLIER-NAME.
           MOVE ZEROS  TO LS-ITEM-COUNT.
           MOVE ZEROS  TO LS-TOTAL-QTY.
           MOVE ZEROS  TO LS-TOTAL-VALUE.
           MOVE SPACES TO LS-LATEST-DATE.
           MOVE "Y"    TO WS-FIRST-ROW.

      *================================================================
       QUERY-SUPPLIER.
           EXEC SQL
               DECLARE SUPP-CURSOR CURSOR FOR
                   SELECT SUPPLIER_NAME, SHIP_QTY, UNIT_PRICE,
                          SHIP_DATE
                   FROM SHIPMENTS
                   WHERE SUPPLIER_ID = :LS-SUPPLIER-ID
                   ORDER BY SHIP_DATE
           END-EXEC.

           EXEC SQL
               OPEN SUPP-CURSOR
           END-EXEC.

           PERFORM FETCH-AND-ACCUMULATE UNTIL SQLCODE = 100.

           EXEC SQL
               CLOSE SUPP-CURSOR
           END-EXEC.
           STOP RUN.

      *================================================================
       FETCH-AND-ACCUMULATE.
           EXEC SQL
               FETCH SUPP-CURSOR
                   INTO :WS-F-NAME, :WS-F-QTY, :WS-F-PRICE,
                        :WS-F-DATE
           END-EXEC.

           IF SQLCODE = 0
               IF IS-FIRST-ROW
                   MOVE WS-F-NAME TO LS-SUPPLIER-NAME
                   MOVE "N" TO WS-FIRST-ROW
               END-IF

               ADD 1 TO LS-ITEM-COUNT
               ADD WS-F-QTY TO LS-TOTAL-QTY

               COMPUTE WS-LINE-VALUE =
                   WS-F-QTY * WS-F-PRICE
               ADD WS-LINE-VALUE TO LS-TOTAL-VALUE

               IF WS-F-DATE > LS-LATEST-DATE
                   MOVE WS-F-DATE TO LS-LATEST-DATE
               END-IF
           END-IF.
