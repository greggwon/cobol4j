# cobol4j — COBOL Runtime Semantics as a Java DSL

> **Project Status — Open for Contributions**
>
> The initial design and implementation phase is complete. The runtime library,
> transpiler, CICS container, SQL integration, and mainframe interop layer are
> all functional with 499 passing tests. The project is now open for community
> contributions. If you'd like to help — whether fixing a bug, finishing an
> incomplete feature, improving documentation, or adding something new — please
> fork the repository and submit a pull request. See the
> [remaining work](WHATSLEFT.md) section below for known areas
> that could use attention.

A fluent Java library that implements COBOL's runtime semantics — data layout, MOVE
rules, decimal arithmetic, file I/O, program structure, and SQL — so that a
COBOL-to-Java transpiler can emit clean, maintainable code instead of verbose literal
translations.

The key insight: **don't translate COBOL semantics into raw Java; translate COBOL into
calls against a runtime that already understands COBOL semantics.** The transpiler
becomes a thin syntax-directed translator. All the hard behavioral correctness lives
in one testable, versioned library.

See [DESIGN.md](DESIGN.md) for the architecture philosophy.

## Getting Started

```bash
git clone https://github.com/greggwon/cobol4j.git
cd cobol4j
make test        # build and run all 499 tests
make javadoc     # generate API docs into docs/javadoc/
```

For examples, demos, and a complete map of what's here, see
**[EXAMPLES.md](EXAMPLES.md)** — it covers the supplier shipment demo (batch and
CICS), the CUSTORD transpiler end-to-end test, every feature-specific test, and
links to the generated javadoc.

## All the Details

The following sections detail the attributes of the system and what you can expect.
Have a look, and discover what's here for use.

## Why No BigDecimal, No float, No double

COBOL has **no floating point**. All COBOL arithmetic is fixed-point decimal — every
digit position is defined by the PIC clause, every operation is exact, and there is
no IEEE 754 mantissa anywhere in the system. A bank balance of `$100.10` is exactly
`100.10`, not `100.09999999999999432...`.

Java's `BigDecimal` can represent exact decimals, but it has a dangerous constructor:

```java
new BigDecimal(0.1)   // 0.1000000000000000055511151231257827021181583404541015625
new BigDecimal("0.1") // 0.1 — exact
```

One typo — using the `double` constructor instead of the `String` constructor — and
you've introduced the exact kind of mantissa error that COBOL's type system was
designed to prevent.

cobol4j eliminates this risk entirely. **The `Decimal` class is the only numeric value
type in the public API.** There is no `BigDecimal` parameter or return type anywhere.
There is no `float` or `double` parameter anywhere. `Decimal` can only be constructed
from `String` or `long` — both exact representations:

```java
Decimal price = Decimal.of("19.99");   // exact — the only way to create a value
Decimal qty   = Decimal.of(5);         // exact — integer literal
Decimal total = price.multiply(qty);   // 99.95 — exact

// These don't exist:
// Decimal.of(0.1)      — NO double constructor
// Decimal.of(19.99)    — NO double constructor
// rec.move("F", 3.14)  — NO double parameter on any API method
```

`Decimal` values are immutable and weakly cached — repeated `Decimal.of("19.99")`
calls return the same instance, reducing allocation pressure without leaking memory.

All arithmetic is traceable via `ValueTracker`:

```java
Decimal.setTracker(new ValueTracker() {
    @Override
    public void onArithmetic(Decimal left, String op, Decimal right, Decimal result) {
        auditLog.record(left + " " + op + " " + right + " = " + result);
    }
});
// Every Decimal operation is now logged — complete audit trail for financial systems
```

## Architecture

```
COBOL Source  →  Transpiler (thin)  →  Java code using cobol4j DSL
                                            ↓
                                      cobol4j Runtime Library
                                      ├── Decimal (exact numeric values, no float/double)
                                      ├── Record (byte-buffer backed data)
                                      ├── Field (by-reference handles)
                                      ├── Variable (typed named handles)
                                      ├── Program / ProgramContext (control flow)
                                      ├── CobolSql / SqlSession (database)
                                      ├── CobolFile (file I/O)
                                      ├── Arithmetic, Intrinsic, Search, Sort
                                      └── Inspect, CobolString, CobolUnstring
```

## Runner — Transpile, Compile, and Execute COBOL Programs

cobol4j includes a command-line runner that takes a COBOL source file and runs it
directly — transpiling to Java, compiling in-process, and executing without any
manual build steps.

### Build the runner JAR

```bash
export JAVA_HOME=/path/to/jdk17
mvn package -DskipTests
```

This produces `target/cobol4j-0.1.0-SNAPSHOT.jar` — a fat JAR containing the
runtime library, transpiler, and runner.

### Commands

**Run** — transpile, compile, and execute immediately:

```bash
java -jar cobol4j.jar run CUSTORD.cbl
```

**Transpile** — generate Java source only:

```bash
java -jar cobol4j.jar transpile CUSTORD.cbl -o src/generated
```

**Build** — transpile, compile, and package as a standalone JAR:

```bash
java -jar cobol4j.jar build CUSTORD.cbl -o custord.jar
java -jar custord.jar
```

### Example session

```
$ java -jar cobol4j.jar run payroll.cbl
cobol4j: Reading payroll.cbl
cobol4j: Transpiling...
cobol4j: Compiling generated.Payroll...
cobol4j: Running...
────────────────────────────────────────────────────────────────
=== PAYROLL REPORT ===
Employee: ALICE JOHNSON
Gross Pay: 5000.00
Tax: 400.00
Net Pay: 4600.00
=== END REPORT ===
────────────────────────────────────────────────────────────────
cobol4j: Program completed.
```

## Quick Example

A COBOL program:

```cobol
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
```

Transpiles to:

```java
Record customerRecord = Record.define("CUSTOMER-RECORD")
    .pic("CUST-NAME", "X(20)")
    .pic("CUST-BALANCE", "S9(7)V99").comp3()
    .pic("CUST-STATUS", "X")
        .value88("ACTIVE", "A")
        .value88("INACTIVE", "I")
    .build();

customerRecord.move("CUST-NAME", "JOHN DOE")
    .move("CUST-BALANCE", Decimal.of("50000.00"))
    .set("ACTIVE")
    .add("CUST-BALANCE", Decimal.of("100.00"),
        SizeErrorHandler.onError(this::errorRoutine));
```

Or with Field references for by-reference semantics:

```java
Field name    = customerRecord.field("CUST-NAME");
Field balance = customerRecord.field("CUST-BALANCE");

name.move("JOHN DOE");
balance.move(Decimal.of("50000.00"))
       .add(Decimal.of("100.00"),
           SizeErrorHandler.onError(this::errorRoutine));
customerRecord.set("ACTIVE");
```

---

## Feature-by-Feature: COBOL to cobol4j

### DATA DIVISION — Record Definition

COBOL's DATA DIVISION is a declarative memory layout language. Records are hierarchical,
byte-buffer-backed, with fixed-length fields whose types are defined by PIC clauses.

```cobol
01 EMPLOYEE-RECORD.
   05 EMP-NAME.
      10 FIRST-NAME     PIC X(15).
      10 LAST-NAME      PIC X(20).
   05 EMP-SALARY        PIC S9(7)V99 COMP-3.
   05 EMP-DEPT          PIC X(4).
   05 EMP-STATUS        PIC X.
      88 FULL-TIME       VALUE "F".
      88 PART-TIME       VALUE "P".
      88 CONTRACTOR      VALUE "C".
```

```java
Record emp = Record.define("EMPLOYEE-RECORD")
    .group("EMP-NAME", name -> name
        .pic("FIRST-NAME", "X(15)")
        .pic("LAST-NAME",  "X(20)"))
    .pic("EMP-SALARY", "S9(7)V99").comp3()
    .pic("EMP-DEPT", "X(4)")
    .pic("EMP-STATUS", "X")
        .value88("FULL-TIME",  "F")
        .value88("PART-TIME",  "P")
        .value88("CONTRACTOR", "C")
    .build();
```

### PIC Clauses and Usage

| COBOL | cobol4j | Meaning |
|---|---|---|
| `PIC X(20)` | `.pic("F", "X(20)")` | 20-byte alphanumeric |
| `PIC A(10)` | `.pic("F", "A(10)")` | 10-byte alphabetic |
| `PIC 9(5)` | `.pic("F", "9(5)")` | 5-digit unsigned integer |
| `PIC S9(7)V99` | `.pic("F", "S9(7)V99")` | Signed, 7 integer + 2 decimal digits |
| `PIC S9(7)V99 COMP-3` | `.pic("F", "S9(7)V99").comp3()` | Packed decimal (BCD) |
| `PIC S9(4) COMP` | `.pic("F", "S9(4)").comp()` | Native binary (halfword) |
| `PIC Z(5)9.99` | `.pic("F", "Z(5)9.99")` | Numeric edited (zero-suppressed display) |

### MOVE — Type-Converting Copy

```java
emp.move("FIRST-NAME", "JOHN");                       // left-justified, space-padded
emp.move("EMP-SALARY", Decimal.of("12345.67"));       // decimal-aligned into COMP-3
emp.moveCorresponding(sourceRec);                      // match by field name
emp.moveSpaces("EMP-NAME");                            // fill group with spaces
emp.moveZeros("EMP-SALARY");                           // numeric zero
emp.moveHighValues("EMP-DEPT");                        // 0xFF fill
```

### REDEFINES — Union Types

```java
Record rec = Record.define("DATE-RECORD")
    .group("DATE-FULL", g -> g
        .pic("DATE-FULL-VAL", "X(8)"))
    .redefines("DATE-FULL", "DATE-PARTS", g -> g
        .pic("DATE-YEAR",  "X(4)")
        .pic("DATE-MONTH", "X(2)")
        .pic("DATE-DAY",   "X(2)"))
    .build();

rec.move("DATE-FULL-VAL", "20260430");
rec.getString("DATE-YEAR");   // "2026"
rec.getString("DATE-MONTH");  // "04"
rec.getString("DATE-DAY");    // "30"
```

### OCCURS — Arrays and Tables

```java
Record table = Record.define("PRICE-TABLE")
    .pic("ITEM-CODE",  "X(10)").occurs(100)
    .pic("ITEM-PRICE", "S9(5)V99").occurs(100)
    .build();

table.move("ITEM-CODE", 0, "WIDGET-A");
table.move("ITEM-PRICE", 0, Decimal.of("29.99"));
```

### Level-88 Conditions

```java
rec.move("ACCOUNT-TYPE", "S");
rec.is("SAVINGS");       // true
rec.is("VALID-TYPE");    // true (88-level with multiple values)
rec.set("CHECKING");     // sets ACCOUNT-TYPE to "C"

// Custom conditions with lambdas — extend beyond COBOL:
.condition("HIGH-BALANCE", val -> Decimal.of(val.trim()).greaterThan(threshold))
```

### Field References — By-Reference Handles

```java
Field salary = emp.field("EMP-SALARY");
Field bonus  = ws.field("WS-BONUS");
Field total  = ws.field("WS-TOTAL");

salary.move(Decimal.of("75000.00"));
salary.add(Decimal.of("5000.00"));

// Pass to GIVING — writes directly into the field
Arithmetic.add(salary.get(), bonus.get())
    .giving(total)
    .rounded()
    .execute();
```

### Decimal — Exact Numeric Values

```java
Decimal price = Decimal.of("19.99");     // from String — exact
Decimal count = Decimal.of(100);          // from long — exact
Decimal total = price.multiply(count);    // immutable, returns new value

// Cached — same string returns same instance
assertSame(Decimal.of("19.99"), Decimal.of("19.99"));

// Rich comparison
total.greaterThan(Decimal.of("1000"));
total.equalTo(Decimal.of("1999.00"));
total.isZero();
total.isNegative();
```

### Variable — Typed Named Handles

```java
Variable<Decimal> balance = Variable.named("CUST-BALANCE").decimal("S9(7)V99");
Variable<String>  name    = Variable.named("CUST-NAME").text(20);

balance.set(Decimal.of("50000.00"));
balance.add(Decimal.of("100.00"));
String trimmedName = name.trimmed();

// Bind to a Record for persistence
balance.bindTo(customerRecord, "CUST-BALANCE");
```

### Arithmetic — ADD, SUBTRACT, MULTIPLY, DIVIDE, COMPUTE

```java
// In-place:
rec.add("BALANCE", Decimal.of("100.00"));
rec.subtract("BALANCE", rec.getDecimal("FEE"));
rec.multiply("BALANCE", rec.getDecimal("RATE"));

// COMPUTE:
Decimal interest = principal.multiply(rate).divide(Decimal.of(12), 2);
rec.compute("INTEREST", interest);

// GIVING with REMAINDER:
Arithmetic.divide(rec.getDecimal("BALANCE"), Decimal.of(12))
    .giving(rec.field("MONTHLY-AMT"))
    .remainder(rec.field("REM-AMT"))
    .execute();

// GIVING with ROUNDED and ON SIZE ERROR:
Arithmetic.add(amtA.get(), amtB.get())
    .giving(total)
    .rounded()
    .onSizeError(SizeErrorHandler.onError(() -> ctx.perform("ERR-RTN")))
    .execute();
```

### Program Structure — Paragraphs, PERFORM, EVALUATE

```java
Program.define("CUSTOMER-REPORT")
    .workingStorage(wsRec)
    .paragraph("MAIN-LOGIC", ctx -> {
        ctx.perform("INIT-ROUTINE")
           .performUntil("PROCESS-LOOP", () -> wsRec.is("END-OF-FILE"))
           .perform("CLEANUP")
           .stopRun();
    })
    .paragraph("PROCESS-CUSTOMER", ctx -> {
        wsRec.add("WS-TOTAL", wsRec.getDecimal("CUST-BALANCE"))
             .add("WS-COUNT", Decimal.ONE);
    })
    .build()
    .run();
```

### PERFORM Variants

| COBOL | cobol4j |
|---|---|
| `PERFORM PARA` | `ctx.perform("PARA")` |
| `PERFORM PARA THRU PARA-EXIT` | `ctx.perform("PARA", "PARA-EXIT")` |
| `PERFORM PARA 10 TIMES` | `ctx.performTimes("PARA", 10)` |
| `PERFORM PARA UNTIL cond` | `ctx.performUntil("PARA", () -> cond)` |
| `PERFORM PARA WITH TEST AFTER` | `ctx.performUntilAfter("PARA", () -> cond)` |
| `PERFORM VARYING I FROM 1 BY 1 UNTIL I > 10` | `ctx.performVarying(...)` |
| `PERFORM VARYING I ... AFTER J ...` | `ctx.performVaryingAfter(...)` |

### EVALUATE (Switch/Case)

```java
ctx.evaluateTrue()
   .whenTrue(() -> rec.getInt("SCORE") >= 90, () -> ctx.perform("A-GRADE"))
   .whenTrue(() -> rec.getInt("SCORE") >= 80, () -> ctx.perform("B-GRADE"))
   .whenOther(() -> ctx.perform("DEFAULT-GRADE"))
   .execute();
```

### File I/O

```java
CobolFile custFile = CobolFile.indexed("CUST-FILE")
    .assignTo("/data/customers.dat")
    .recordSize(200)
    .recordKey("CUST-ID")
    .fileStatus(status)
    .build();

ctx.open(custFile, CobolFile.OpenMode.INPUT);
ctx.read(custFile).into(rec)
   .atEnd(() -> rec.set("END-OF-FILE"))
   .execute();
ctx.close(custFile);
```

### String Operations — INSPECT, STRING, UNSTRING

```java
Inspect.on(rec, "DATA-FIELD")
    .converting("abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    .apply();

CobolString.into(rec, "FULL-NAME")
    .from(rec, "FIRST-NAME").delimitedBySpaces()
    .literal(" ")
    .from(rec, "LAST-NAME").delimitedBySpaces()
    .execute();

CobolUnstring.from(rec, "INPUT-LINE")
    .delimitedBy(",").orDelimitedBy(" ")
    .into(rec, "FIELD-1").into(rec, "FIELD-2")
    .execute();
```

### SEARCH and SEARCH ALL

```java
Search.table(rec, "TABLE-ENTRY")
    .atEnd(() -> ctx.perform("NOT-FOUND"))
    .when(idx -> rec.getString("TABLE-KEY", idx).trim().equals(searchValue),
          idx -> ctx.perform("FOUND"))
    .execute();
```

### SORT with INPUT/OUTPUT PROCEDURE

```java
CobolSort.on(sortRecord)
    .ascending("SORT-NAME")
    .descending("SORT-AMOUNT")
    .inputProcedure(input -> {
        // RELEASE each record to the sort
    })
    .outputProcedure(output -> {
        while (output.returnRecord()) { /* process sorted records */ }
    })
    .execute();
```

### Intrinsic Functions

```java
rec.move("WS-DATE", Intrinsic.currentDate());
rec.compute("LEN", Intrinsic.length(rec, "WS-NAME"));
rec.move("WS-OUTPUT", Intrinsic.upperCase(rec.getString("WS-INPUT")));
rec.compute("RESULT", Intrinsic.mod(dividend, divisor));
rec.compute("AVG", Intrinsic.mean(v1, v2, v3));
```

### Embedded SQL

```java
// All SQL goes through ConnectionFactory → SqlSession
ConnectionFactory factory = ConnectionFactory.jdbc(
    "jdbc:postgresql://db:5432/orders", "user", "pass").cached(true);

SqlSession.work(factory, session -> {
    session.sql()
        .select("SELECT CUST_NAME, BALANCE FROM CUSTOMERS WHERE ID = ?")
        .param(rec, "WS-CUST-ID")
        .into(rec, "WS-NAME", "WS-BALANCE")
        .execute();

    if (session.isNotFound()) {
        ctx.display("Not found");
    }
});
// commit on success, rollback on failure, connection always released
```

### Database Connectivity

```java
// Any JDBC database — standard URL, the only thing that changes:
ConnectionFactory.jdbc("jdbc:postgresql://db:5432/orders", "user", "pass").cached(true)
ConnectionFactory.jdbc("jdbc:mysql://db:3306/orders", "user", "pass").cached(true)
ConnectionFactory.jdbc("jdbc:oracle:thin:@//db:1521/ORCL", "user", "pass").cached(true)
ConnectionFactory.jdbc("jdbc:db2://db:50000/ORDERS", "user", "pass").cached(true)

// In-memory convenience for testing (the name makes the ephemeral choice explicit):
ConnectionFactory.h2InMemory("testdb")
ConnectionFactory.sqliteInMemory()

// With a production connection pool (HikariCP, etc.):
ConnectionFactory.jdbc(hikariDataSource)
```

## Mainframe Interop — Coexisting with Live COBOL Systems

In a real migration, COBOL and Java programs coexist for years, sharing data through
files, databases, and message queues. cobol4j provides the interop layer so Java
programs can read, write, and exchange data in the exact formats that mainframe COBOL
programs expect.

### Copybook Import

Every COBOL system's data contracts live in **copybooks** (`.cpy` files). The copybook
importer reads them and produces matching `Record` definitions — so the Java side uses
the same field names, same byte layout, same data types as the COBOL side.

```java
// Import a copybook and create a Record at runtime
Record custRec = CopybookImporter.toRecord("CUSTCPY.cpy");

// Or generate Java source for build-time integration
String javaCode = CopybookImporter.toJavaSource("CUSTCPY.cpy");
// Produces: Record customerRecord = Record.define("CUSTOMER-RECORD").pic(...).build();
```

From a copybook like:
```cobol
01 CUSTOMER-RECORD.
   05 CUST-ID        PIC X(10).
   05 CUST-NAME      PIC X(30).
   05 CUST-BALANCE   PIC S9(7)V99 COMP-3.
   05 CUST-STATUS    PIC X.
      88 ACTIVE       VALUE "A".
      88 INACTIVE     VALUE "I".
```

The importer produces a Record with the same fields, same sizes, same conditions.
The Java program can then read and write data that COBOL programs understand.

### EBCDIC Encoding

Mainframes use EBCDIC, not ASCII. Every byte transferred from z/OS needs translation.

```java
// Translate a mainframe data buffer to ASCII
byte[] asciiData = Ebcdic.toAscii(ebcdicBytes);

// Translate back for sending to the mainframe
byte[] ebcdicData = Ebcdic.toEbcdic(asciiString);

// Supported code pages:
Ebcdic.codePage(Ebcdic.CodePage.CP037)   // US/Canada (most common)
Ebcdic.codePage(Ebcdic.CodePage.CP500)   // International (Latin-1)
Ebcdic.codePage(Ebcdic.CodePage.CP1047)  // z/OS Unix
```

EBCDIC collation order differs from ASCII (spaces < lowercase < uppercase < digits).
For comparisons that must match mainframe behavior:

```java
// EBCDIC-order string comparison
Ebcdic.compareEbcdic("abc", "ABC");  // negative — lowercase < uppercase in EBCDIC
Ebcdic.compareEbcdic("ABC", "123");  // negative — letters < digits in EBCDIC
```

### Mainframe File I/O

Read and write files in mainframe format — fixed-length (FB) or variable-length
with Record Descriptor Words (VB), with automatic EBCDIC translation.

```java
// Read a file transferred from z/OS via FTP
Record custRec = CopybookImporter.toRecord("CUSTCPY.cpy");

MainframeFile.reader("/data/from_mainframe/CUSTFILE.dat")
    .fixedLength(200)
    .ebcdic(Ebcdic.CodePage.CP037)
    .forEach(custRec, rec -> {
        String name = rec.getString("CUST-NAME").trim();
        Decimal balance = rec.getDecimal("CUST-BALANCE");
        // process — same field names as the COBOL program
    });

// Write a file the mainframe can read
MainframeFile.writer("/data/to_mainframe/OUTFILE.dat")
    .fixedLength(200)
    .ebcdic(Ebcdic.CodePage.CP037)
    .write(custRec);
```

Variable-length records (with 4-byte RDW prefix):

```java
MainframeFile.reader(path).variableLength().ebcdic(Ebcdic.CodePage.CP037)
    .forEach(rec, r -> { /* process */ });
```

### Message Queue Interop (MQ)

MQ messages from a mainframe are just EBCDIC byte buffers with a copybook layout.
Read them the same way:

```java
// Receive a message from MQ as raw bytes
byte[] mqMessage = mqQueue.get();

// Load into a Record defined from the same copybook
Record msgRec = CopybookImporter.toRecord("MQMSGCPY.cpy");
MainframeFile.reader(mqMessage)
    .fixedLength(msgRec.length())
    .ebcdic(Ebcdic.CodePage.CP037)
    .forEach(msgRec, rec -> {
        // Process the message using COBOL field names
    });
```

### CICS COMMAREA

CICS programs communicate via a COMMAREA — a byte buffer matching a copybook layout.
The Record byte buffer *is* the COMMAREA:

```java
// Build a COMMAREA from a copybook
Record commarea = CopybookImporter.toRecord("CICSCPY.cpy");
commarea.move("REQUEST-TYPE", "INQ")
        .move("CUST-ID", "C001");

// Send to CICS via Transaction Gateway
byte[] ebcdicCommarea = Ebcdic.toEbcdic(commarea.buffer());
byte[] response = cicsGateway.call("CUSTINQ", ebcdicCommarea);

// Read the response
commarea.loadFrom(Ebcdic.toAscii(response));
String name = commarea.getString("CUST-NAME").trim();
```

## CICS Container

cobol4j includes a lightweight CICS-like transaction processing container. Programs
are installed, routed by transaction ID, and share managed resources — the same
programming model as IBM CICS, running in-process in the JVM.

See [CICSIntg.md](CICSIntg.md) for full documentation.

```java
CicsRegion region = CicsRegion.create("PROD")
    .install("CUSTINQ", ctx -> {
        ctx.receive(commarea);
        ctx.readFile("CUSTFILE", custRec, commarea.getString("CUST-ID").trim());
        commarea.move("CUST-NAME", custRec.getString("NAME"));
        ctx.send(commarea);
        ctx.returnTransaction();
    })
    .transaction("CINQ", "CUSTINQ")
    .start();

region.dispatch("CINQ", commarea);
```

Programs can be hot-deployed from JARs at runtime — drop a JAR in a watched
directory and it's automatically loaded, installed, and routed via ServiceLoader.

## Codec System (JSON / XML / Pluggable)

Records can be serialized to/from JSON and XML (mapping to COBOL's JSON GENERATE /
XML GENERATE verbs). Custom codecs are discoverable via Java's ServiceLoader.

```java
String json = CodecRegistry.instance().toJson(customerRecord);
CodecRegistry.instance().fromJson(jsonInput, customerRecord);

String xml = CodecRegistry.instance().toXml(customerRecord);
CodecRegistry.instance().fromXml(xmlInput, customerRecord);
```

No external dependencies — XML uses JDK's javax.xml, JSON is hand-written.
Add custom codecs by implementing `RecordCodec` or `FieldCodec` and registering
via `META-INF/services`.

## CALL with LINKAGE SECTION

Inter-program communication with by-reference parameter passing:

```java
Program calcTax = Program.define("CALC-TAX")
    .linkage("LS-AMOUNT", "S9(7)V99")
    .linkage("LS-RATE", "S9V9999")
    .linkage("LS-RESULT", "S9(7)V99")
    .paragraph("CALC", ctx -> {
        Decimal amount = ctx.linkageField("LS-AMOUNT").get();
        Decimal rate = ctx.linkageField("LS-RATE").get();
        ctx.linkageField("LS-RESULT").move(amount.multiply(rate));
    })
    .build();

// Caller passes Field references — called program writes directly to caller's data
ctx.call(calcTax, rec.field("WS-AMOUNT"), rec.field("WS-RATE"), rec.field("WS-RESULT"));
```

## Extension Points

| Extension Point | Interface/Type | Purpose |
|---|---|---|
| `Condition` | `@FunctionalInterface` | Custom level-88 conditions beyond value matching |
| `SizeErrorHandler` | interface | ON SIZE ERROR / NOT ON SIZE ERROR as lambdas |
| `CobolFile` | interface | Custom file backends (JDBC, cloud, message queues) |
| `ConnectionFactory` | `@FunctionalInterface` | Pluggable connection acquisition/release |
| `ValueTracker` | interface | Audit trail for all Decimal arithmetic and variable changes |
| `CicsProgram` | `@FunctionalInterface` | Transaction handler deployed into a CICS region |
| `RecordCodec` / `FieldCodec` | interface + ServiceLoader | Pluggable data format codecs |
| `MessagePort` | interface | Messaging with delivery guarantees (fire-and-forget/at-least-once/exactly-once) |
| `SystemCall` | interface | POSIX system call mapping (pluggable for JNI/Panama) |

## Schema Management

Records map automatically to database tables with version tracking and auto-migration:

```java
SchemaManager schema = SchemaManager.using(factory)
    .table("CUSTOMERS", customerRecord, t -> t.primaryKey("CUST-ID"))
    .build();

schema.migrate();  // creates tables, detects drift, applies ALTERs

RecordStore store = schema.store("CUSTOMERS");
store.insert(session, rec);
store.findByKey(session, rec, "C001");
store.update(session, rec);
```

Schema versions are tracked in a `COBOL4J_SCHEMA` table. When a Record definition
changes (field added, size expanded), `migrate()` detects the diff and generates
the appropriate ALTER TABLE statements.

## OO COBOL — INVOKE

OO COBOL's INVOKE maps directly to Java method calls since we're already in Java:

```cobol
INVOKE myObject "calculateTax" USING WS-AMOUNT RETURNING WS-TAX.
INVOKE TaxService "new" RETURNING WS-SERVICE.
```

Transpiles to:

```java
rec.move("WS-TAX", myObject.calculateTax(rec.getDecimal("WS-AMOUNT")));
var wsService = new TaxService();
```

Method names are preserved exactly as written — no case transformation.

## Embedded SQL — Host Variable Binding

EXEC SQL with `:HOST-VAR` references auto-translates to CobolSql API calls:

```cobol
EXEC SQL
    SELECT CUST_NAME, BALANCE INTO :WS-NAME, :WS-BAL
    FROM CUSTOMERS WHERE CUST_ID = :WS-ID
END-EXEC.
```

Transpiles to:

```java
sql.select("SELECT CUST_NAME, BALANCE FROM CUSTOMERS WHERE CUST_ID = ?")
    .param(rec, "WS-ID")
    .into(rec, "WS-NAME", "WS-BAL")
    .execute();
```

Supports SELECT INTO, INSERT/UPDATE/DELETE, DECLARE CURSOR, OPEN/FETCH/CLOSE,
COMMIT, ROLLBACK — all with automatic host variable replacement.

## Building

```bash
export JAVA_HOME=/path/to/jdk17
mvn clean test        # run all tests
mvn package           # build the runner JAR
make all              # or use the Makefile
```

Requires Java 17+ and Maven. Tests use JUnit 5, H2, and SQLite.
Zero external production dependencies.

## Status

**499 passing tests.** See [WHATSLEFT.md](WHATSLEFT.md) for the full list of
remaining work and known gaps.

## Contributing

Fork the repository, make your changes, and submit a pull request. Areas where
help is welcome:

- Implementing missing COBOL verbs (START, RENAMES/66-level, PERFORM VARYING AFTER)
- Improving the transpiler's coverage of real-world COBOL programs
- Adding end-to-end examples with new COBOL source files
- Database dialect testing (PostgreSQL, MySQL, Oracle)
- Documentation improvements and tutorials

See [EXAMPLES.md](EXAMPLES.md) for the full test inventory and
[the remaining work section](WHATSLEFT.md) for specific items.

## License

LGPL 2.1 — the library is free to use in proprietary applications. Improvements to
the library itself must be shared. See [LICENSE](LICENSE) for details.
