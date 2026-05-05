       IDENTIFICATION DIVISION.
       PROGRAM-ID. PAYROLL.

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-EMPLOYEE.
          05 WS-EMP-NAME     PIC X(20).
          05 WS-HOURS        PIC 9(3).
          05 WS-RATE         PIC S9(3)V99.
          05 WS-GROSS-PAY    PIC S9(7)V99.
          05 WS-TAX          PIC S9(7)V99.
          05 WS-NET-PAY      PIC S9(7)V99.
          05 WS-TAX-RATE     PIC S9V9999.
          05 WS-OVERTIME     PIC S9(5)V99.

       01 WS-TOTALS.
          05 WS-TOTAL-GROSS  PIC S9(9)V99.
          05 WS-TOTAL-TAX    PIC S9(9)V99.
          05 WS-TOTAL-NET    PIC S9(9)V99.
          05 WS-EMP-COUNT    PIC 9(3).

       01 WS-CONTROL.
          05 WS-IDX          PIC 9(3).

       PROCEDURE DIVISION.
       MAIN-LOGIC.
           PERFORM INIT-ROUTINE.
           PERFORM PROCESS-EMPLOYEE 3 TIMES.
           PERFORM COMPUTE-AVERAGES.
           PERFORM DISPLAY-REPORT.
           STOP RUN.

       INIT-ROUTINE.
           MOVE 0 TO WS-TOTAL-GROSS.
           MOVE 0 TO WS-TOTAL-TAX.
           MOVE 0 TO WS-TOTAL-NET.
           MOVE 0 TO WS-EMP-COUNT.

       PROCESS-EMPLOYEE.
           ADD 1 TO WS-EMP-COUNT.
           MOVE "EMPLOYEE" TO WS-EMP-NAME.
           MOVE 40 TO WS-HOURS.
           MOVE 25.00 TO WS-RATE.
           MOVE 0.22 TO WS-TAX-RATE.
           PERFORM CALCULATE-PAY.
           ADD WS-GROSS-PAY TO WS-TOTAL-GROSS.
           ADD WS-TAX TO WS-TOTAL-TAX.
           ADD WS-NET-PAY TO WS-TOTAL-NET.

       CALCULATE-PAY.
           IF WS-HOURS > 40
               COMPUTE WS-OVERTIME =
                   ( WS-HOURS - 40 ) * WS-RATE * 1.5
               COMPUTE WS-GROSS-PAY =
                   40 * WS-RATE + WS-OVERTIME
           ELSE
               COMPUTE WS-GROSS-PAY = WS-HOURS * WS-RATE
           END-IF.
           COMPUTE WS-TAX = WS-GROSS-PAY * WS-TAX-RATE.
           COMPUTE WS-NET-PAY = WS-GROSS-PAY - WS-TAX.

       COMPUTE-AVERAGES.
           DISPLAY "Employees processed: " WS-EMP-COUNT.

       DISPLAY-REPORT.
           DISPLAY "=== PAYROLL SUMMARY ===".
           DISPLAY "Total Gross: " WS-TOTAL-GROSS.
           DISPLAY "Total Tax: " WS-TOTAL-TAX.
           DISPLAY "Total Net: " WS-TOTAL-NET.
           DISPLAY "=== END REPORT ===".
