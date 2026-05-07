       IDENTIFICATION DIVISION.
       PROGRAM-ID. SHIPINTK.
      *================================================================
      * SHIPMENT INTAKE — Receives a shipment record, validates it,
      * stores it in the SHIPMENTS database, and sends a notification.
      *
      * Called via:  CALL "SHIPINTK" USING WS-SUPPLIER-ID
      *                  WS-SUPPLIER-NAME WS-ITEM-DESC WS-SHIP-QTY
      *                  WS-UNIT-PRICE WS-SHIP-DATE
      *                  WS-STATUS-CODE WS-STATUS-MSG
      *
      * Returns:     Status 00=OK, 10=INVALID, 20=DB-ERROR
      *================================================================

       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-NOTIFICATION.
          05 WS-NOTIFY-TYPE        PIC X.
             88 NOTIFY-UPDATED     VALUE "U".
             88 NOTIFY-END         VALUE "E".
          05 WS-NOTIFY-SUPPLIER    PIC X(4).

       01 WS-SQLCODE               PIC S9(4) COMP.

       LINKAGE SECTION.
       01 LS-SUPPLIER-ID           PIC X(4).
       01 LS-SUPPLIER-NAME         PIC X(20).
       01 LS-ITEM-DESC             PIC X(30).
       01 LS-SHIP-QTY              PIC 9(5).
       01 LS-UNIT-PRICE            PIC S9(5)V99.
       01 LS-SHIP-DATE             PIC X(10).
       01 LS-STATUS-CODE           PIC XX.
          88 INTAKE-OK             VALUE "00".
          88 INTAKE-INVALID        VALUE "10".
          88 INTAKE-DB-ERROR       VALUE "20".
       01 LS-STATUS-MSG            PIC X(40).

       PROCEDURE DIVISION USING LS-SUPPLIER-ID LS-SUPPLIER-NAME
           LS-ITEM-DESC LS-SHIP-QTY LS-UNIT-PRICE LS-SHIP-DATE
           LS-STATUS-CODE LS-STATUS-MSG.

      *================================================================
       VALIDATE-INPUT.
           IF LS-SUPPLIER-ID = SPACES
               MOVE "10" TO LS-STATUS-CODE
               MOVE "SUPPLIER ID IS SPACES" TO LS-STATUS-MSG
               STOP RUN
           END-IF.
           IF LS-SHIP-QTY NOT > 0
               MOVE "10" TO LS-STATUS-CODE
               MOVE "QUANTITY MUST BE > 0" TO LS-STATUS-MSG
               STOP RUN
           END-IF.
           IF LS-UNIT-PRICE NOT > 0
               MOVE "10" TO LS-STATUS-CODE
               MOVE "UNIT PRICE MUST BE > 0" TO LS-STATUS-MSG
               STOP RUN
           END-IF.

      *================================================================
       INSERT-SHIPMENT.
           EXEC SQL
               INSERT INTO SHIPMENTS
                   (SUPPLIER_ID, SUPPLIER_NAME, ITEM_DESC,
                    SHIP_QTY, UNIT_PRICE, SHIP_DATE)
               VALUES
                   (:LS-SUPPLIER-ID, :LS-SUPPLIER-NAME,
                    :LS-ITEM-DESC, :LS-SHIP-QTY,
                    :LS-UNIT-PRICE, :LS-SHIP-DATE)
           END-EXEC.
           IF SQLCODE NOT = 0
               MOVE "20" TO LS-STATUS-CODE
               MOVE "DB INSERT FAILED" TO LS-STATUS-MSG
               STOP RUN
           END-IF.

      *================================================================
       SEND-NOTIFICATION.
           SET NOTIFY-UPDATED TO TRUE.
           MOVE LS-SUPPLIER-ID TO WS-NOTIFY-SUPPLIER.
      *    MQ SEND would go here — in cobol4j this maps to
      *    MessagePort.send(notification)
           MOVE "00" TO LS-STATUS-CODE.
           MOVE "SHIPMENT RECORDED" TO LS-STATUS-MSG.
           STOP RUN.
