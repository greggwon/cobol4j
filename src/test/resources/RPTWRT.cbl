       IDENTIFICATION DIVISION.
       PROGRAM-ID. RPTWRT.
      *================================================================
      * REPORT WRITER — Receives notifications from the message queue,
      * queries each supplier's shipment data, and writes:
      *   1. Per-supplier report files ({SUPPLIER-ID}.rpt)
      *   2. Consolidated report (ALL-SUPPLIERS.rpt)
      *
      * Demonstrates: MQ receive, CALL USING, sequential file WRITE,
      *   accumulation, formatted report output.
      *================================================================

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT RPT-FILE ASSIGN TO WS-RPT-FILENAME
               ORGANIZATION IS SEQUENTIAL.

       DATA DIVISION.
       FILE SECTION.
       FD RPT-FILE.
       01 RPT-RECORD              PIC X(80).

       WORKING-STORAGE SECTION.
      *--- Notification from message queue ---
       01 WS-NOTIFICATION.
          05 WS-NOTIFY-TYPE        PIC X.
             88 NOTIFY-UPDATED     VALUE "U".
             88 NOTIFY-END         VALUE "E".
          05 WS-NOTIFY-SUPPLIER    PIC X(4).

      *--- Query result (filled by CALL to SHIPQRY) ---
       01 WS-QUERY-RESULT.
          05 WS-QR-SUPPLIER-NAME   PIC X(20).
          05 WS-QR-ITEM-COUNT      PIC 9(5).
          05 WS-QR-TOTAL-QTY       PIC 9(7).
          05 WS-QR-TOTAL-VALUE     PIC S9(9)V99.
          05 WS-QR-LATEST-DATE     PIC X(10).

      *--- Report formatting ---
       01 WS-RPT-LINE              PIC X(80).
       01 WS-RPT-FILENAME          PIC X(60).
       01 WS-DISPLAY-VALUE         PIC Z(7)9.99.

      *--- Grand totals ---
       01 WS-GRAND-TOTALS.
          05 WS-GT-ITEMS           PIC 9(5) VALUE ZEROS.
          05 WS-GT-QTY             PIC 9(7) VALUE ZEROS.
          05 WS-GT-VALUE           PIC S9(9)V99 VALUE ZEROS.

      *--- Control ---
       01 WS-EOF-FLAG              PIC X VALUE "N".
          88 END-OF-NOTIFICATIONS  VALUE "Y".
       01 WS-SUPP-IDX              PIC 9(3).

      *--- Supplier table (collected from notifications) ---
       01 WS-SUPP-TABLE.
          05 WS-SUPP-COUNT         PIC 9(3) VALUE ZEROS.
          05 WS-SUPP-ENTRY OCCURS 50.
             10 WS-SUPP-ID         PIC X(4).

       PROCEDURE DIVISION.
      *================================================================
       MAIN-LOGIC.
           PERFORM RECEIVE-NOTIFICATIONS.
           PERFORM WRITE-SUPPLIER-REPORTS.
           PERFORM WRITE-CONSOLIDATED-REPORT.
           STOP RUN.

      *================================================================
       RECEIVE-NOTIFICATIONS.
      *    MQ RECEIVE loop — drain the notification queue
      *    In cobol4j: notifyReceiver.receive(rptNotify, 2000)
           PERFORM UNTIL END-OF-NOTIFICATIONS
      *        (receive notification from queue)
               IF NOTIFY-END
                   SET END-OF-NOTIFICATIONS TO TRUE
               ELSE
                   PERFORM CHECK-DUPLICATE-SUPPLIER
               END-IF
           END-PERFORM.

      *================================================================
       CHECK-DUPLICATE-SUPPLIER.
      *    Add supplier to table if not already there
           PERFORM VARYING WS-SUPP-IDX FROM 1 BY 1
               UNTIL WS-SUPP-IDX > WS-SUPP-COUNT
               IF WS-SUPP-ID(WS-SUPP-IDX) = WS-NOTIFY-SUPPLIER
                   EXIT PARAGRAPH
               END-IF
           END-PERFORM.
      *    Not found — add it
           ADD 1 TO WS-SUPP-COUNT.
           MOVE WS-NOTIFY-SUPPLIER
               TO WS-SUPP-ID(WS-SUPP-COUNT).

      *================================================================
       WRITE-SUPPLIER-REPORTS.
           PERFORM VARYING WS-SUPP-IDX FROM 1 BY 1
               UNTIL WS-SUPP-IDX > WS-SUPP-COUNT
               PERFORM WRITE-SINGLE-SUPPLIER-REPORT
           END-PERFORM.

      *================================================================
       WRITE-SINGLE-SUPPLIER-REPORT.
      *    Query this supplier's data
           CALL "SHIPQRY" USING WS-SUPP-ID(WS-SUPP-IDX)
               WS-QR-SUPPLIER-NAME WS-QR-ITEM-COUNT
               WS-QR-TOTAL-QTY WS-QR-TOTAL-VALUE
               WS-QR-LATEST-DATE.

      *    Accumulate grand totals
           ADD WS-QR-ITEM-COUNT TO WS-GT-ITEMS.
           ADD WS-QR-TOTAL-QTY  TO WS-GT-QTY.
           ADD WS-QR-TOTAL-VALUE TO WS-GT-VALUE.

      *    Build filename: "{SUPPLIER-ID}.rpt"
           STRING WS-SUPP-ID(WS-SUPP-IDX) DELIMITED BY SPACES
                  ".rpt" DELIMITED BY SIZE
               INTO WS-RPT-FILENAME.

      *    Write the report
           OPEN OUTPUT RPT-FILE.
           MOVE "================================" &
                "================================"
               TO RPT-RECORD.
           WRITE RPT-RECORD.

           STRING "SUPPLIER SHIPMENT REPORT"
                  "                    DATE: 2026-05-06"
               DELIMITED BY SIZE INTO RPT-RECORD.
           WRITE RPT-RECORD.

           STRING "SUPPLIER: " DELIMITED BY SIZE
                  WS-SUPP-ID(WS-SUPP-IDX) DELIMITED BY SPACES
                  "   " DELIMITED BY SIZE
                  WS-QR-SUPPLIER-NAME DELIMITED BY SPACES
               INTO RPT-RECORD.
           WRITE RPT-RECORD.

           STRING "LAST SHIPMENT: " DELIMITED BY SIZE
                  WS-QR-LATEST-DATE DELIMITED BY SPACES
               INTO RPT-RECORD.
           WRITE RPT-RECORD.

           MOVE "================================" &
                "================================"
               TO RPT-RECORD.
           WRITE RPT-RECORD.

           CLOSE RPT-FILE.

      *================================================================
       WRITE-CONSOLIDATED-REPORT.
           MOVE "ALL-SUPPLIERS.rpt" TO WS-RPT-FILENAME.
           OPEN OUTPUT RPT-FILE.

           MOVE "================================" &
                "================================"
               TO RPT-RECORD.
           WRITE RPT-RECORD.

           STRING "CONSOLIDATED SHIPMENT REPORT"
                  "                DATE: 2026-05-06"
               DELIMITED BY SIZE INTO RPT-RECORD.
           WRITE RPT-RECORD.

      *    Column headers
           MOVE "SUPPLIER  NAME                 ITEMS" &
                "  TOTAL QTY  LAST SHIP   TOTAL VALUE"
               TO RPT-RECORD.
           WRITE RPT-RECORD.

      *    Detail lines for each supplier
           PERFORM VARYING WS-SUPP-IDX FROM 1 BY 1
               UNTIL WS-SUPP-IDX > WS-SUPP-COUNT
               CALL "SHIPQRY" USING WS-SUPP-ID(WS-SUPP-IDX)
                   WS-QR-SUPPLIER-NAME WS-QR-ITEM-COUNT
                   WS-QR-TOTAL-QTY WS-QR-TOTAL-VALUE
                   WS-QR-LATEST-DATE
      *        Format and write detail line
               WRITE RPT-RECORD
           END-PERFORM.

      *    Grand total line
           MOVE WS-GT-VALUE TO WS-DISPLAY-VALUE.
           WRITE RPT-RECORD.

           MOVE "================================" &
                "================================"
               TO RPT-RECORD.
           WRITE RPT-RECORD.

           CLOSE RPT-FILE.
