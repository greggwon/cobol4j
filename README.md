# Creating COBOL representative APIs.

## What a translated program looks like:

  A COBOL program like:
  01 CUSTOMER-RECORD.
     05 CUST-NAME         PIC X(20).
     05 CUST-BALANCE      PIC S9(7)V99 COMP-3.
     05 CUST-STATUS       PIC X.
        88 ACTIVE          VALUE "A".
        88 INACTIVE        VALUE "I".

  PROCEDURE DIVISION.
     MOVE "JOHN DOE" TO CUST-NAME.
     MOVE 50000.00 TO CUST-BALANCE.
     SET ACTIVE TO TRUE.
     ADD 100.00 TO CUST-BALANCE
        ON SIZE ERROR PERFORM ERROR-ROUTINE.

## Transpiles to:
  Record customerRecord = Record.define("CUSTOMER-RECORD")
      .pic("CUST-NAME", "X(20)")
      .pic("CUST-BALANCE", "S9(7)V99").comp3()
      .pic("CUST-STATUS", "X")
          .value88("ACTIVE", "A")
          .value88("INACTIVE", "I")
      .build();

  customerRecord.move("CUST-NAME", "JOHN DOE");
  customerRecord.move("CUST-BALANCE", new BigDecimal("50000.00"));
  customerRecord.set("ACTIVE");
  customerRecord.add("CUST-BALANCE", new BigDecimal("100.00"),
      SizeErrorHandler.onError(this::errorRoutine));
