# Create a Complete, Multi Program Demo
Create a realistic multi-program COBOL demonstration showing how transpiled programs interoperate: receiving shipment records, storing in
SQLite, notifying a report writer, querying data, and generating reports. This educates users on COBOL-style inter-program communication
using the cobol4j API.

---
## Architecture
Three programs communicating via CALL USING, MessagePort, and shared SQLite:
```
Shipment Data ──▶ SHIPINTK ──▶ SQLite DB
	     │
	MessagePort
	     │
	 RPTWRT ──CALL──▶ SHIPQRY ──▶ SQLite DB
	     │
	Report Files
```
---
### Program 1: SHIPINTK (Shipment Intake)

- Receives shipment record fields via CALL USING / LINKAGE
- Validates (supplier ID not blank, qty > 0, price > 0)
- INSERTs into SQLite SHIPMENTS table
- Sends notification via MessagePort
- Returns status code via linkage: 00=OK, 10=INVALID, 20=DB-ERROR

### Program 2: SHIPQRY (Shipment Query)

- Called via CALL USING with a supplier ID
- Opens cursor on SHIPMENTS for that supplier
- Accumulates: item count, total qty, total value (qty × price), latest date
- Returns summary fields via linkage

### Program 3: RPTWRT (Report Writer)

- Receives notifications from MessagePort (drains queue)
- De-duplicates supplier IDs
- For each supplier: CALLs SHIPQRY, writes {SUPPLIER-ID}.rpt
- Writes consolidated ALL-SUPPLIERS.rpt with grand totals

---
## Record Layouts
### WS-SHIPMENT-REC (76 bytes)
```
05 WS-SUPPLIER-ID       PIC X(4)
05 WS-SUPPLIER-NAME     PIC X(20)
05 WS-ITEM-DESC         PIC X(30)
05 WS-SHIP-QTY          PIC 9(5)
05 WS-UNIT-PRICE        PIC S9(5)V99
05 WS-SHIP-DATE         PIC X(10)         ← "2026-04-15" format
```
### WS-INTAKE-STATUS
```
05 WS-STATUS-CODE       PIC XX
   88 INTAKE-OK         VALUE "00"
   88 INTAKE-INVALID    VALUE "10"
88 INTAKE-DB-ERROR   VALUE "20"
05 WS-STATUS-MSG        PIC X(40)
```
### WS-NOTIFICATION (5 bytes — MessagePort payload)
```
05 WS-NOTIFY-TYPE       PIC X              ← "U"=updated, "E"=end
05 WS-NOTIFY-SUPPLIER   PIC X(4)
```
### WS-QUERY-RESULT (SHIPQRY linkage output)
```
05 WS-QR-SUPPLIER-NAME  PIC X(20)
05 WS-QR-ITEM-COUNT     PIC 9(5)
05 WS-QR-TOTAL-QTY      PIC 9(7)
05 WS-QR-TOTAL-VALUE    PIC S9(9)V99
05 WS-QR-LATEST-DATE    PIC X(10)          ← most recent shipment date
```
---
## SQLite Schema
```
CREATE TABLE SHIPMENTS (
	SUPPLIER_ID   VARCHAR(4)   NOT NULL,
	SUPPLIER_NAME VARCHAR(20)  NOT NULL,
	ITEM_DESC     VARCHAR(30)  NOT NULL,
	SHIP_QTY      INTEGER      NOT NULL,
	UNIT_PRICE    DECIMAL(7,2) NOT NULL,
	SHIP_DATE     VARCHAR(10)  NOT NULL
)
```
## Test Data (13 shipments, 4 suppliers)

```
┌──────┬──────────────────┬────────────────────┬─────┬───────┬────────────┐
│ Supp │       Name       │        Item        │ Qty │ Price │    Date    │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SA   │ ACME WIDGETS     │ STEEL BOLTS 1/4 IN │ 100 │ 12.50 │ 2026-04-01 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SA   │ ACME WIDGETS     │ HEX NUTS 3/8 IN    │ 150 │ 8.75  │ 2026-04-03 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SA   │ ACME WIDGETS     │ FLAT WASHERS       │ 100 │ 5.25  │ 2026-04-05 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SB   │ BAKER SUPPLY     │ COPPER WIRE 12GA   │ 200 │ 15.00 │ 2026-04-02 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SB   │ BAKER SUPPLY     │ SOLDER PASTE       │ 50  │ 22.50 │ 2026-04-04 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SB   │ BAKER SUPPLY     │ HEAT SHRINK TUBE   │ 150 │ 6.75  │ 2026-04-06 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SB   │ BAKER SUPPLY     │ WIRE CONNECTORS    │ 100 │ 9.80  │ 2026-04-08 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SC   │ CARTER LOGISTICS │ CARDBOARD BOXES LG │ 75  │ 18.50 │ 2026-04-01 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SC   │ CARTER LOGISTICS │ PACKING TAPE       │ 100 │ 4.25  │ 2026-04-03 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SC   │ CARTER LOGISTICS │ BUBBLE WRAP ROLL   │ 100 │ 12.75 │ 2026-04-07 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SD   │ DELTA MATERIALS  │ PVC PIPE 2IN       │ 150 │ 14.50 │ 2026-04-02 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SD   │ DELTA MATERIALS  │ PIPE FITTINGS      │ 100 │ 8.25  │ 2026-04-05 │
├──────┼──────────────────┼────────────────────┼─────┼───────┼────────────┤
│ SD   │ DELTA MATERIALS  │ VALVE ASSEMBLY     │ 150 │ 22.00 │ 2026-04-09 │
└──────┴──────────────────┴────────────────────┴─────┴───────┴────────────┘
```
### Expected totals:
- SA: 3 items, qty=350, value=3087.50, latest=2026-04-05
- SB: 4 items, qty=500, value=6117.50, latest=2026-04-08
- SC: 3 items, qty=275, value=3087.50, latest=2026-04-07
- SD: 3 items, qty=400, value=6300.00, latest=2026-04-09
- Grand: 13 items, qty=1525, value=18592.50

## Report Format

### Per-supplier ({SUPPLIER-ID}.rpt)
```
================================================================
SUPPLIER SHIPMENT REPORT                    DATE: 2026-05-06
SUPPLIER: SA   ACME WIDGETS
LAST SHIPMENT: 2026-04-05
================================================================
ITEMS SHIPPED: 00003        TOTAL QTY:     0000350
TOTAL VALUE:   $   3,087.50
================================================================
```
### Consolidated (ALL-SUPPLIERS.rpt)
```
================================================================
CONSOLIDATED SHIPMENT REPORT                DATE: 2026-05-06
================================================================
SUPPLIER  NAME                 ITEMS  TOTAL QTY  LAST SHIP   TOTAL VALUE
--------  -------------------  -----  ---------  ----------  -----------
SA        ACME WIDGETS         00003    0000350  2026-04-05  $  3,087.50
SB        BAKER SUPPLY         00004    0000500  2026-04-08  $  6,117.50
SC        CARTER LOGISTICS     00003    0000275  2026-04-07  $  3,087.50
SD        DELTA MATERIALS      00003    0000400  2026-04-09  $  6,300.00
                               -----  ---------              -----------
GRAND TOTAL:                   00013    0001525              $ 18,592.50
================================================================
```
## Test Flow

1. Setup: Create SQLite schema, message queue, temp dir
2. Load: Feed 13 shipments through SHIPINTK via CALL USING
3. Signal: Send "E" notification to mark end
4. Report: Run RPTWRT — drains queue, queries DB, writes files
5. Validate: DB row count, report file contents, computed totals
6. Negative test: Feed invalid record (blank supplier), assert INTAKE-INVALID

### Files to Create

- src/test/java/org/cobol4j/SupplierShipmentTest.java — integration test
- src/test/resources/SHIPINTK.cbl — companion COBOL source
- src/test/resources/SHIPQRY.cbl — companion COBOL source
- src/test/resources/RPTWRT.cbl — companion COBOL source

### Key APIs Used

- Program.define().linkage().paragraph().build() — program definition
- program.runWithLinkage(field1, field2, ...) — CALL USING
- ctx.call(subprog, field1, ...) — nested program call
- SqlSession.work(factory, session -> { ... }) — transactional SQL
- session.sql().declareCursor() / open() / fetch().into() / close() — cursor
- CobolFile.sequential().assignTo().recordSize().build() — report files
- InMemoryMessagePort.fireAndForget() — notification queue
- Record.define().pic().value88().build() — data layouts
- Decimal.of() / add() / multiply() — money arithmetic

### Verification

1. mvn test -Dtest=SupplierShipmentTest passes
2. DB contains 13 rows
3. 5 report files written (SA.rpt, SB.rpt, SC.rpt, SD.rpt, ALL-SUPPLIERS.rpt)
4. Grand total = $18,592.50
5. Invalid record returns status "10"
6. COBOL source files serve as documentation of what the equivalent COBOL looks like
