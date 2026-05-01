       IDENTIFICATION DIVISION.
       PROGRAM-ID. CUSTORD.
      *================================================================
      * CUSTOMER ORDER PROCESSING - Comprehensive Feature Demonstration
      * This program exercises every COBOL feature supported by cobol4j.
      *================================================================

       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT CUST-FILE ASSIGN TO "CUSTFILE"
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-FILE-STATUS.
           SELECT REPORT-FILE ASSIGN TO "RPTFILE"
               ORGANIZATION IS SEQUENTIAL
               FILE STATUS IS WS-RPT-STATUS.

       DATA DIVISION.
       FILE SECTION.
       FD CUST-FILE.
       01 CUST-RECORD          PIC X(100).

       FD REPORT-FILE.
       01 REPORT-RECORD        PIC X(132).

       WORKING-STORAGE SECTION.
      *--- File Status ---
       01 WS-FILE-STATUS        PIC XX.
       01 WS-RPT-STATUS         PIC XX.

      *--- Customer Record Layout ---
       01 WS-CUSTOMER.
          05 WS-CUST-ID         PIC X(10).
          05 WS-CUST-NAME.
             10 WS-FIRST-NAME   PIC X(15).
             10 WS-LAST-NAME    PIC X(20).
          05 WS-CUST-BALANCE    PIC S9(7)V99 COMP-3.
          05 WS-CUST-STATUS     PIC X.
             88 ACTIVE           VALUE "A".
             88 INACTIVE         VALUE "I".
             88 SUSPENDED        VALUE "S".
             88 VALID-STATUS     VALUE "A" "I" "S".
          05 WS-CUST-TYPE       PIC X.
             88 RETAIL           VALUE "R".
             88 WHOLESALE        VALUE "W".
             88 PREMIUM          VALUE "P" THRU "Z".
          05 WS-CREDIT-LIMIT    PIC S9(7)V99 COMP.

      *--- Date handling with REDEFINES ---
       01 WS-DATE-AREA.
          05 WS-DATE-FULL       PIC X(8).
          05 WS-DATE-PARTS REDEFINES WS-DATE-FULL.
             10 WS-DATE-YEAR    PIC X(4).
             10 WS-DATE-MONTH   PIC X(2).
             10 WS-DATE-DAY     PIC X(2).

      *--- Price Table with OCCURS ---
       01 WS-PRICE-TABLE.
          05 WS-TABLE-SIZE      PIC 9(3) VALUE 5.
          05 WS-PRICE-ENTRY OCCURS 20 DEPENDING ON WS-TABLE-SIZE.
             10 WS-ITEM-CODE    PIC X(10).
             10 WS-ITEM-PRICE   PIC S9(5)V99.
             10 WS-ITEM-QTY     PIC 9(5).

      *--- Accumulators ---
       01 WS-ACCUMULATORS.
          05 WS-TOTAL-SALES     PIC S9(9)V99 VALUE ZEROS.
          05 WS-TOTAL-ITEMS     PIC 9(7) VALUE ZEROS.
          05 WS-CUST-COUNT      PIC 9(5) VALUE ZEROS.
          05 WS-AVG-SALE        PIC S9(7)V99.
          05 WS-TAX-AMOUNT      PIC S9(7)V99.
          05 WS-GRAND-TOTAL     PIC S9(9)V99.
          05 WS-QUOTIENT        PIC S9(7).
          05 WS-REMAINDER       PIC S9(3).

      *--- Control Fields ---
       01 WS-CONTROL.
          05 WS-EOF-FLAG        PIC X VALUE "N".
             88 END-OF-FILE      VALUE "Y".
          05 WS-ERROR-FLAG      PIC X VALUE "N".
             88 HAS-ERROR        VALUE "Y".
          05 WS-IDX             PIC 9(3).
          05 WS-INNER-IDX       PIC 9(3).
          05 WS-SEARCH-CODE     PIC X(10).
          05 WS-FOUND-FLAG      PIC X VALUE "N".
             88 ITEM-FOUND       VALUE "Y".

      *--- String Work Areas ---
       01 WS-STRING-WORK.
          05 WS-FULL-NAME       PIC X(40).
          05 WS-MESSAGE         PIC X(80).
          05 WS-DISPLAY-AMT     PIC Z(7)9.99.
          05 WS-PARTS.
             10 WS-PART-1       PIC X(20).
             10 WS-PART-2       PIC X(20).
             10 WS-PART-3       PIC X(20).
          05 WS-SPACE-COUNT     PIC 9(3).

      *--- Sort Work Area ---
       01 WS-SORT-REC.
          05 WS-SORT-NAME       PIC X(20).
          05 WS-SORT-AMOUNT     PIC S9(7)V99.

      *--- Current Date ---
       01 WS-CURRENT-DATE       PIC X(21).

       PROCEDURE DIVISION.
      *================================================================
       MAIN-LOGIC.
           PERFORM INIT-ROUTINE.
           PERFORM PROCESS-DATA.
           PERFORM COMPUTE-SUMMARIES.
           PERFORM STRING-OPERATIONS.
           PERFORM TABLE-OPERATIONS.
           PERFORM DISPLAY-RESULTS.
           STOP RUN.

      *================================================================
       INIT-ROUTINE.
           INITIALIZE WS-CUSTOMER.
           INITIALIZE WS-ACCUMULATORS.
           MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE.
           MOVE "20260501" TO WS-DATE-FULL.
           MOVE "C001" TO WS-CUST-ID.
           MOVE "ALICE" TO WS-FIRST-NAME.
           MOVE "JOHNSON" TO WS-LAST-NAME.
           MOVE 50000.00 TO WS-CUST-BALANCE.
           SET ACTIVE TO TRUE.
           SET RETAIL TO TRUE.
           MOVE 100000.00 TO WS-CREDIT-LIMIT.
           EXIT PARAGRAPH.

      *================================================================
       PROCESS-DATA.
           IF ACTIVE
               ADD 1 TO WS-CUST-COUNT
               PERFORM PROCESS-ORDERS
           ELSE
               DISPLAY "Customer not active"
           END-IF.

           EVALUATE TRUE
               WHEN RETAIL
                   MOVE 0.08 TO WS-TAX-AMOUNT
               WHEN WHOLESALE
                   MOVE 0.05 TO WS-TAX-AMOUNT
               WHEN OTHER
                   MOVE 0.10 TO WS-TAX-AMOUNT
           END-EVALUATE.

      *================================================================
       PROCESS-ORDERS.
           PERFORM PROCESS-SINGLE-ORDER 5 TIMES.

       PROCESS-SINGLE-ORDER.
           ADD 1000.00 TO WS-TOTAL-SALES.
           ADD 10 TO WS-TOTAL-ITEMS.

      *================================================================
       COMPUTE-SUMMARIES.
           COMPUTE WS-AVG-SALE =
               WS-TOTAL-SALES / WS-CUST-COUNT.

           MULTIPLY WS-TOTAL-SALES BY WS-TAX-AMOUNT
               GIVING WS-TAX-AMOUNT ROUNDED.

           ADD WS-TOTAL-SALES WS-TAX-AMOUNT
               GIVING WS-GRAND-TOTAL
               ON SIZE ERROR
                   SET HAS-ERROR TO TRUE
               NOT ON SIZE ERROR
                   MOVE "N" TO WS-ERROR-FLAG
           END-ADD.

           DIVIDE WS-TOTAL-ITEMS BY 3
               GIVING WS-QUOTIENT
               REMAINDER WS-REMAINDER.

      *================================================================
       STRING-OPERATIONS.
           STRING WS-FIRST-NAME DELIMITED BY SPACES
                  " " DELIMITED BY SIZE
                  WS-LAST-NAME DELIMITED BY SPACES
             INTO WS-FULL-NAME.

           UNSTRING WS-FULL-NAME DELIMITED BY " "
             INTO WS-PART-1 WS-PART-2.

           INSPECT WS-FULL-NAME
               TALLYING WS-SPACE-COUNT FOR ALL SPACES.

           INSPECT WS-MESSAGE
               CONVERTING "abcdefghijklmnopqrstuvwxyz"
                       TO "ABCDEFGHIJKLMNOPQRSTUVWXYZ".

      *================================================================
       TABLE-OPERATIONS.
           MOVE "WIDGET-A" TO WS-ITEM-CODE(1).
           MOVE 29.99 TO WS-ITEM-PRICE(1).
           MOVE 100 TO WS-ITEM-QTY(1).
           MOVE "GADGET-B" TO WS-ITEM-CODE(2).
           MOVE 49.99 TO WS-ITEM-PRICE(2).
           MOVE 50 TO WS-ITEM-QTY(2).
           MOVE "DEVICE-C" TO WS-ITEM-CODE(3).
           MOVE 99.99 TO WS-ITEM-PRICE(3).
           MOVE 25 TO WS-ITEM-QTY(3).
           MOVE "TOOL-D" TO WS-ITEM-CODE(4).
           MOVE 19.99 TO WS-ITEM-PRICE(4).
           MOVE 200 TO WS-ITEM-QTY(4).
           MOVE "PART-E" TO WS-ITEM-CODE(5).
           MOVE 9.99 TO WS-ITEM-PRICE(5).
           MOVE 500 TO WS-ITEM-QTY(5).

           MOVE "DEVICE-C" TO WS-SEARCH-CODE.
           PERFORM SEARCH-TABLE.

           PERFORM ACCUMULATE-TABLE
               VARYING WS-IDX FROM 1 BY 1
               UNTIL WS-IDX > WS-TABLE-SIZE.

      *================================================================
       SEARCH-TABLE.
           SEARCH WS-PRICE-ENTRY
               AT END
                   MOVE "N" TO WS-FOUND-FLAG
               WHEN WS-ITEM-CODE(WS-IDX) = WS-SEARCH-CODE
                   MOVE "Y" TO WS-FOUND-FLAG
           END-SEARCH.

      *================================================================
       ACCUMULATE-TABLE.
           ADD WS-ITEM-QTY(WS-IDX) TO WS-TOTAL-ITEMS.

      *================================================================
       DISPLAY-RESULTS.
           DISPLAY "=== CUSTOMER ORDER REPORT ===".
           DISPLAY "Customer: " WS-FULL-NAME.
           DISPLAY "Status: " WS-CUST-STATUS.
           DISPLAY "Balance: " WS-CUST-BALANCE.
           DISPLAY "Total Sales: " WS-TOTAL-SALES.
           DISPLAY "Tax: " WS-TAX-AMOUNT.
           DISPLAY "Grand Total: " WS-GRAND-TOTAL.
           DISPLAY "Average Sale: " WS-AVG-SALE.
           DISPLAY "Items: " WS-TOTAL-ITEMS.
           DISPLAY "Date: " WS-DATE-YEAR "/" WS-DATE-MONTH
                   "/" WS-DATE-DAY.
           DISPLAY "Item Found: " WS-FOUND-FLAG.
           DISPLAY "=== END REPORT ===".
