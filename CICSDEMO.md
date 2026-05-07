# CICS Deployment Demo — Supplier Shipment Processing

This walkthrough shows the supplier shipment scenario deployed as CICS online
transactions. It is the same business logic as the batch demo
([DEMOPLAN.md](DEMOPLAN.md)), but restructured for the CICS transaction
processing model.

**Source**: [`src/test/java/org/cobol4j/cics/SupplierShipmentCicsTest.java`](src/test/java/org/cobol4j/cics/SupplierShipmentCicsTest.java)

**Run it**: `mvn test -Dtest=org.cobol4j.cics.SupplierShipmentCicsTest`

---

## Batch vs. CICS — Side by Side

| Concept | Batch | CICS |
|---------|-------|------|
| Program invocation | `program.runWithLinkage(field1, field2, ...)` | `region.dispatch("SINT", commarea)` |
| Data exchange | LINKAGE SECTION — individual Field references | COMMAREA — single shared Record buffer |
| Notifications | MessagePort send/receive | Temporary Storage queues (`writeTS` / `readTSNext`) |
| Sub-program call | `ctx.call(program, field1, ...)` | `ctx.link("SHIPQRY", commarea)` |
| Lifecycle | Caller manages program instances | Region manages installed programs |
| Transaction routing | N/A | 4-character transaction ID → program name |

---

## Architecture

```
Terminal ──dispatch("SINT")──▶ SHIPINTK ──SQL──▶ SQLite
                                  │
                             writeTS("SHIPRPT")
                                  │
Terminal ──dispatch("SRPT")──▶ RPTWRT
                                  │
                             readTSNext("SHIPRPT")
                                  │
                          link("SHIPQRY") ──SQL──▶ SQLite
                                  │
                           CobolFile.write ──▶ .rpt files
```

---

## Step 1: COMMAREA Layouts

In CICS, programs communicate through a **COMMAREA** (Communication Area) — a
fixed-length byte buffer that carries both the request and the response. Each
program defines its COMMAREA layout using
[Record](docs/javadoc/apidocs/org/cobol4j/Record.html).

### Intake COMMAREA (SHIPINTK request/response)

```java
Record intakeCa = Record.define("INTAKE-CA")
    .pic("CA-SUPPLIER-ID",   "X(4)")
    .pic("CA-SUPPLIER-NAME", "X(20)")
    .pic("CA-ITEM-DESC",     "X(30)")
    .pic("CA-SHIP-QTY",      "9(5)")
    .pic("CA-UNIT-PRICE",    "S9(5)V99")
    .pic("CA-SHIP-DATE",     "X(10)")
    .pic("CA-STATUS-CODE",   "XX")
        .value88("CA-OK",      "00")       // ← level-88 condition names
        .value88("CA-INVALID", "10")
        .value88("CA-DB-ERR",  "20")
    .pic("CA-STATUS-MSG",    "X(40)")
    .build();
```

The **request** fills `CA-SUPPLIER-ID` through `CA-SHIP-DATE`. The **response**
comes back in `CA-STATUS-CODE` and `CA-STATUS-MSG`. The caller checks the
response using level-88 conditions:

```java
region.dispatch("SINT", intakeCa);
assertTrue(intakeCa.is("CA-OK"));   // checks CA-STATUS-CODE = "00"
```

### Query COMMAREA (SHIPQRY request/response)

```java
Record queryCa = Record.define("QUERY-CA")
    .pic("QC-SUPPLIER-ID",   "X(4)")      // request: which supplier
    .pic("QC-SUPPLIER-NAME", "X(20)")      // response: name
    .pic("QC-ITEM-COUNT",    "9(5)")       // response: how many line items
    .pic("QC-TOTAL-QTY",     "9(7)")       // response: total quantity
    .pic("QC-TOTAL-VALUE",   "S9(9)V99")   // response: total dollar value
    .pic("QC-LATEST-DATE",   "X(10)")      // response: most recent shipment
    .build();
```

**API**: [Record.define()](docs/javadoc/apidocs/org/cobol4j/Record.html),
[Record.Builder.pic()](docs/javadoc/apidocs/org/cobol4j/Record.Builder.html),
[Record.Builder.value88()](docs/javadoc/apidocs/org/cobol4j/Record.Builder.html)

---

## Step 2: Create the CICS Region

A [CicsRegion](docs/javadoc/apidocs/org/cobol4j/cics/CicsRegion.html) is the
transaction container — like a CICS address space. Programs are **installed**
into the region and **dispatched** by transaction ID.

```java
CicsRegion region = CicsRegion.create("SHIPDEMO")
    .database("SHIPDB", dbFactory)     // register a shared database
    .install("SHIPINTK", ctx -> { ... })  // install programs
    .install("SHIPQRY",  ctx -> { ... })
    .install("RPTWRT",   ctx -> { ... })
    .start();                          // region is now accepting transactions

// Map 4-character transaction IDs to installed programs
region.transaction("SINT", "SHIPINTK");
region.transaction("SQRY", "SHIPQRY");
region.transaction("SRPT", "RPTWRT");
```

**API**: [CicsRegion.create()](docs/javadoc/apidocs/org/cobol4j/cics/CicsRegion.html),
[CicsRegion.install()](docs/javadoc/apidocs/org/cobol4j/cics/CicsRegion.html),
[CicsRegion.transaction()](docs/javadoc/apidocs/org/cobol4j/cics/CicsRegion.html)

---

## Step 3: SHIPINTK — The Intake Transaction

This is the CICS equivalent of the batch SHIPINTK program. In COBOL, this
would be written with `EXEC CICS` commands:

```cobol
EXEC CICS RECEIVE INTO(INTAKE-CA) END-EXEC.
... validate ...
EXEC SQL INSERT INTO SHIPMENTS ... END-EXEC.
EXEC CICS WRITEQ TS QUEUE('SHIPRPT') FROM(TS-NOTIFY) END-EXEC.
EXEC CICS SEND FROM(INTAKE-CA) END-EXEC.
EXEC CICS RETURN END-EXEC.
```

In cobol4j, the [CicsContext](docs/javadoc/apidocs/org/cobol4j/cics/CicsContext.html)
provides the same operations:

```java
.install("SHIPINTK", ctx -> {
    ctx.receive(intakeCa);                              // EXEC CICS RECEIVE

    // ── Validate ────────────────────────────────────
    String suppId = intakeCa.getString("CA-SUPPLIER-ID").trim();
    if (suppId.isEmpty()) {
        intakeCa.move("CA-STATUS-CODE", "10");
        intakeCa.move("CA-STATUS-MSG", "SUPPLIER ID IS SPACES");
        ctx.send(intakeCa);                             // EXEC CICS SEND
        ctx.returnTransaction();                        // EXEC CICS RETURN
        return;
    }

    // ── Insert into database ────────────────────────
    SqlSession.work(dbFactory, session -> {
        session.sql().execute(
            "INSERT INTO SHIPMENTS (...) VALUES (?, ?, ?, ?, ?, ?)")
            .param(intakeCa.getString("CA-SUPPLIER-ID").trim())
            .param(intakeCa.getString("CA-SUPPLIER-NAME").trim())
            .param(intakeCa.getString("CA-ITEM-DESC").trim())
            .param(intakeCa.getDecimal("CA-SHIP-QTY"))
            .param(intakeCa.getDecimal("CA-UNIT-PRICE"))
            .param(intakeCa.getString("CA-SHIP-DATE").trim())
            .execute();
    });

    // ── Notify the report writer via TS queue ───────
    tsNotify.move("TS-SUPPLIER-ID", suppId);
    ctx.writeTS("SHIPRPT", tsNotify);                   // EXEC CICS WRITEQ TS

    intakeCa.move("CA-STATUS-CODE", "00");
    intakeCa.move("CA-STATUS-MSG", "SHIPMENT RECORDED");
    ctx.send(intakeCa);                                 // EXEC CICS SEND
    ctx.returnTransaction();                            // EXEC CICS RETURN
})
```

**Key patterns**:
- `ctx.receive(record)` / `ctx.send(record)` — COMMAREA in/out
- `ctx.writeTS(queue, record)` — Temporary Storage write (notification)
- `ctx.returnTransaction()` — end of transaction
- [SqlSession.work()](docs/javadoc/apidocs/org/cobol4j/SqlSession.html) — transactional SQL with auto-commit
- [Decimal](docs/javadoc/apidocs/org/cobol4j/Decimal.html) — money-safe arithmetic (no float/double in the API)

---

## Step 4: SHIPQRY — The Query Transaction

Called either directly via `dispatch("SQRY", queryCa)` or from within another
program via `ctx.link("SHIPQRY", queryCa)`. Uses a cursor to fetch all
shipments for a supplier and accumulates totals.

```java
.install("SHIPQRY", ctx -> {
    ctx.receive(queryCa);

    // Zero the output fields
    queryCa.move("QC-ITEM-COUNT", Decimal.ZERO);
    queryCa.move("QC-TOTAL-QTY", Decimal.ZERO);
    queryCa.move("QC-TOTAL-VALUE", Decimal.ZERO);

    SqlSession.work(dbFactory, session -> {
        // EXEC SQL DECLARE CURSOR
        SqlCursor cursor = session.sql().declareCursor("SC",
            "SELECT SUPPLIER_NAME, SHIP_QTY, UNIT_PRICE, SHIP_DATE "
            + "FROM SHIPMENTS WHERE SUPPLIER_ID = ? ORDER BY SHIP_DATE");

        // EXEC SQL OPEN CURSOR
        session.sql().open(cursor, queryCa.getString("QC-SUPPLIER-ID").trim());

        // EXEC SQL FETCH — classic COBOL cursor loop
        session.sql().fetch(cursor)
            .into(fetchRec, "F-NAME", "F-QTY", "F-PRICE", "F-DATE")
            .execute();

        while (session.isSuccess()) {
            queryCa.add("QC-ITEM-COUNT", Decimal.ONE);
            Decimal qty = fetchRec.getDecimal("F-QTY");
            queryCa.add("QC-TOTAL-QTY", qty);
            queryCa.add("QC-TOTAL-VALUE",
                qty.multiply(fetchRec.getDecimal("F-PRICE")));

            // Track latest date
            String rowDate = fetchRec.getString("F-DATE").trim();
            if (rowDate.compareTo(
                    queryCa.getString("QC-LATEST-DATE").trim()) > 0) {
                queryCa.move("QC-LATEST-DATE", rowDate);
            }

            session.sql().fetch(cursor)                 // FETCH NEXT
                .into(fetchRec, "F-NAME", "F-QTY", "F-PRICE", "F-DATE")
                .execute();
        }
        session.sql().close(cursor);                    // EXEC SQL CLOSE
    });

    ctx.send(queryCa);
    ctx.returnTransaction();
})
```

**Key patterns**:
- [SqlCursor](docs/javadoc/apidocs/org/cobol4j/SqlCursor.html) — cursor lifecycle (declare, open, fetch, close)
- `session.isSuccess()` — equivalent to checking SQLCODE = 0
- `record.add(field, value)` — accumulate totals (COBOL ADD verb)
- `qty.multiply(price)` — line value computation using [Decimal](docs/javadoc/apidocs/org/cobol4j/Decimal.html) arithmetic

---

## Step 5: RPTWRT — The Report Writer Transaction

Reads the TS queue to find which suppliers were updated, LINKs to SHIPQRY for
each, and writes report files using
[CobolFile](docs/javadoc/apidocs/org/cobol4j/CobolFile.html).

```java
.install("RPTWRT", ctx -> {
    // ── Read all notifications from TS queue ────────
    LinkedHashSet<String> suppliers = new LinkedHashSet<>();
    while (ctx.readTSNext("SHIPRPT", tsNotify)) {      // EXEC CICS READQ TS
        suppliers.add(tsNotify.getString("TS-SUPPLIER-ID").trim());
    }

    for (String suppId : suppliers) {
        // ── LINK to SHIPQRY ─────────────────────────
        queryCa.move("QC-SUPPLIER-ID", suppId);
        ctx.link("SHIPQRY", queryCa);                   // EXEC CICS LINK

        // ── Write per-supplier report file ──────────
        CobolFile rptFile = CobolFile.sequential(suppId + "-RPT")
            .assignTo(tempDir.resolve(suppId + ".rpt").toString())
            .recordSize(80)
            .build();

        rptFile.open(CobolFile.OpenMode.OUTPUT);
        writeLine(rptFile, rptLine, "============================...");
        writeLine(rptFile, rptLine, "SUPPLIER: " + suppId + " " + name);
        writeLine(rptFile, rptLine, "LAST SHIPMENT: " + lastDate);
        writeLine(rptFile, rptLine, "TOTAL VALUE:   $" + totalVal);
        rptFile.close();
    }

    // ── Write consolidated report ───────────────────
    // Same pattern: open file, write header/detail/totals, close
    ctx.returnTransaction();
})
```

**Key patterns**:
- `ctx.readTSNext(queue, record)` — sequential TS queue read; returns false at end
- `ctx.link(program, commarea)` — LINK (call with return) within the same task
- [CobolFile.sequential()](docs/javadoc/apidocs/org/cobol4j/CobolFile.html) — sequential file for report output
- `file.open(OpenMode.OUTPUT)` / `file.write(record)` / `file.close()` — standard file lifecycle

---

## Step 6: Dispatch Transactions

Each `dispatch` is like a terminal user entering a transaction ID:

```java
// Load 13 shipments — each is a separate SINT transaction
for (String[] data : SHIPMENTS) {
    intakeCa.move("CA-SUPPLIER-ID",   data[0]);
    intakeCa.move("CA-SUPPLIER-NAME", data[1]);
    intakeCa.move("CA-ITEM-DESC",     data[2]);
    intakeCa.move("CA-SHIP-QTY",      Decimal.of(data[3]));
    intakeCa.move("CA-UNIT-PRICE",    Decimal.of(data[4]));
    intakeCa.move("CA-SHIP-DATE",     data[5]);

    region.dispatch("SINT", intakeCa);

    assertTrue(intakeCa.is("CA-OK"));   // check response status
}

// Run the report writer — reads TS queue, queries DB, writes files
region.dispatch("SRPT", new byte[0]);
```

**API**: [CicsRegion.dispatch()](docs/javadoc/apidocs/org/cobol4j/cics/CicsRegion.html)

---

## Step 7: Validate Results

```java
// Database has 13 rows
assertEquals(13, cnt.getInt("C"));

// Per-supplier reports written
assertFileContains(tempDir.resolve("SA.rpt"), "ACME WIDGETS");
assertFileContains(tempDir.resolve("SA.rpt"), "3,087.50");
assertFileContains(tempDir.resolve("SA.rpt"), "2026-04-05");

// Consolidated report grand total
assertFileContains(tempDir.resolve("ALL-SUPPLIERS.rpt"), "18,592.50");

// Direct query via SQRY transaction
queryCa.move("QC-SUPPLIER-ID", "SB");
region.dispatch("SQRY", queryCa);
assertEquals("BAKER SUPPLY", queryCa.getString("QC-SUPPLIER-NAME").trim());
assertEquals(4, queryCa.getInt("QC-ITEM-COUNT"));
assertTrue(queryCa.getDecimal("QC-TOTAL-VALUE").equalTo(Decimal.of("6117.50")));

// Validation rejection
intakeCa.moveSpaces("CA-SUPPLIER-ID");
region.dispatch("SINT", intakeCa);
assertTrue(intakeCa.is("CA-INVALID"));   // blank supplier rejected
```

---

## API Reference

Generate the full javadoc with `make javadoc` (output: `docs/javadoc/apidocs/`).

| Class | Description |
|-------|-------------|
| [CicsRegion](docs/javadoc/apidocs/org/cobol4j/cics/CicsRegion.html) | Transaction container — install, dispatch, manage resources |
| [CicsContext](docs/javadoc/apidocs/org/cobol4j/cics/CicsContext.html) | Runtime context inside a program — receive, send, link, writeTS, readFile |
| [CicsProgram](docs/javadoc/apidocs/org/cobol4j/cics/CicsProgram.html) | Functional interface for program lambdas |
| [Record](docs/javadoc/apidocs/org/cobol4j/Record.html) | COBOL record with PIC-governed fields, groups, REDEFINES, OCCURS |
| [Record.Builder](docs/javadoc/apidocs/org/cobol4j/Record.Builder.html) | Fluent builder: `.pic()`, `.group()`, `.value88()`, `.occurs()`, `.redefines()` |
| [Decimal](docs/javadoc/apidocs/org/cobol4j/Decimal.html) | Money-safe numeric — the only numeric type in the public API |
| [Program](docs/javadoc/apidocs/org/cobol4j/Program.html) | Batch program with paragraphs (compare with CicsProgram for online) |
| [ProgramContext](docs/javadoc/apidocs/org/cobol4j/ProgramContext.html) | Batch context — perform, call, display, file I/O |
| [SqlSession](docs/javadoc/apidocs/org/cobol4j/SqlSession.html) | Transactional SQL with auto-commit |
| [CobolSql](docs/javadoc/apidocs/org/cobol4j/CobolSql.html) | Embedded SQL: select, execute, declareCursor, fetch |
| [SqlCursor](docs/javadoc/apidocs/org/cobol4j/SqlCursor.html) | Cursor lifecycle for multi-row fetch |
| [ConnectionFactory](docs/javadoc/apidocs/org/cobol4j/ConnectionFactory.html) | Database connections: SQLite, H2, PostgreSQL, MySQL, Oracle |
| [CobolFile](docs/javadoc/apidocs/org/cobol4j/CobolFile.html) | Sequential and indexed file I/O |
| [SizeErrorHandler](docs/javadoc/apidocs/org/cobol4j/SizeErrorHandler.html) | ON SIZE ERROR / NOT ON SIZE ERROR callbacks |
| [Field](docs/javadoc/apidocs/org/cobol4j/Field.html) | By-reference handle to a field within a Record |
| [Arithmetic](docs/javadoc/apidocs/org/cobol4j/Arithmetic.html) | ADD/SUBTRACT/MULTIPLY/DIVIDE with GIVING, ROUNDED, REMAINDER |

### Interop & Messaging

| Class | Description |
|-------|-------------|
| [MessagePort](docs/javadoc/apidocs/org/cobol4j/interop/MessagePort.html) | Record-oriented messaging interface |
| [InMemoryMessagePort](docs/javadoc/apidocs/org/cobol4j/interop/InMemoryMessagePort.html) | In-memory queue implementation for demos and tests |
| [Ebcdic](docs/javadoc/apidocs/org/cobol4j/interop/Ebcdic.html) | EBCDIC translation (CP037, CP500, CP1047) |
| [SystemCall](docs/javadoc/apidocs/org/cobol4j/interop/SystemCall.html) | POSIX system call mapping for COBOL CALL |

### Transpiler

| Class | Description |
|-------|-------------|
| [Transpiler](docs/javadoc/apidocs/org/cobol4j/transpiler/Transpiler.html) | Entry point: COBOL source → Java source |
| [Lexer](docs/javadoc/apidocs/org/cobol4j/transpiler/Lexer.html) | COBOL lexer — source text → token stream |
| [Parser](docs/javadoc/apidocs/org/cobol4j/transpiler/Parser.html) | Recursive descent parser — tokens → AST |
| [JavaEmitter](docs/javadoc/apidocs/org/cobol4j/transpiler/JavaEmitter.html) | AST → Java source using cobol4j API |

---

## See Also

- [DEMOPLAN.md](DEMOPLAN.md) — batch version of this demo (CALL USING / MessagePort)
- [CICSIntg.md](CICSIntg.md) — CICS container architecture and hot-deploy
- [DESIGN.md](DESIGN.md) — cobol4j architecture philosophy
- [README.md](README.md) — getting started and feature overview
