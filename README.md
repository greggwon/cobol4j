# cobol4j — COBOL Runtime Semantics as a Java DSL

A fluent Java library that implements COBOL's runtime semantics — data layout, MOVE
rules, decimal arithmetic, file I/O, program structure, and SQL — so that a
COBOL-to-Java transpiler can emit clean, maintainable code instead of verbose literal
translations.

The key insight: **don't translate COBOL semantics into raw Java; translate COBOL into
calls against a runtime that already understands COBOL semantics.** The transpiler
becomes a thin syntax-directed translator. All the hard behavioral correctness lives
in one testable, versioned library.

## Architecture

```
COBOL Source  →  Transpiler (thin)  →  Java code using cobol4j DSL
                                            ↓
                                      cobol4j Runtime Library
                                      ├── Record (byte-buffer backed data)
                                      ├── Field (by-reference handles)
                                      ├── Program / ProgramContext (control flow)
                                      ├── CobolSql / SqlSession (database)
                                      ├── CobolFile (file I/O)
                                      ├── Arithmetic, Intrinsic, Search, Sort
                                      └── Inspect, CobolString, CobolUnstring
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
    .move("CUST-BALANCE", new BigDecimal("50000.00"))
    .set("ACTIVE")
    .add("CUST-BALANCE", new BigDecimal("100.00"),
        SizeErrorHandler.onError(this::errorRoutine));
```

Or with Field references for by-reference semantics and less string repetition:

```java
Field name   = customerRecord.field("CUST-NAME");
Field balance = customerRecord.field("CUST-BALANCE");

name.move("JOHN DOE");
balance.move(new BigDecimal("50000.00"))
       .add(new BigDecimal("100.00"),
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

The record allocates a contiguous byte buffer. Fields are views over byte ranges.
Group items span their children. The layout is computed automatically — just like
COBOL's compiler does.

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

COBOL's MOVE is not simple assignment. It's a type-converting, padding/truncating copy
with rules that depend on the sending and receiving field categories.

```cobol
MOVE "JOHN" TO FIRST-NAME.           *> left-justified, space-padded to 15
MOVE 12345.67 TO EMP-SALARY.         *> decimal-aligned, sign handled
MOVE CORR SOURCE-REC TO TARGET-REC.  *> match fields by name
MOVE SPACES TO EMP-NAME.             *> fill entire group with spaces
MOVE ZEROS TO EMP-SALARY.            *> numeric zero
MOVE HIGH-VALUES TO EMP-DEPT.        *> fill with 0xFF
```

```java
emp.move("FIRST-NAME", "JOHN");                  // left-justified, space-padded
emp.move("EMP-SALARY", new BigDecimal("12345.67")); // decimal-aligned into COMP-3
emp.moveCorresponding(sourceRec);                 // match by field name
emp.moveSpaces("EMP-NAME");                       // fill group with spaces
emp.moveZeros("EMP-SALARY");                      // numeric zero
emp.moveHighValues("EMP-DEPT");                   // 0xFF fill
```

All MOVE rules (alphanumeric-to-numeric, numeric-to-alphanumeric, group moves,
sign handling, truncation, decimal alignment) are implemented in the library.
The transpiler just emits the corresponding `move()` call.

### REDEFINES — Union Types

COBOL's REDEFINES lets the same bytes be interpreted as different field layouts,
like a C union.

```cobol
01 DATE-RECORD.
   05 DATE-FULL          PIC X(8).
   05 DATE-PARTS REDEFINES DATE-FULL.
      10 DATE-YEAR       PIC X(4).
      10 DATE-MONTH      PIC X(2).
      10 DATE-DAY        PIC X(2).
```

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
// Reading through the redefines view — same bytes, different names:
rec.getString("DATE-YEAR");   // "2026"
rec.getString("DATE-MONTH");  // "04"
rec.getString("DATE-DAY");    // "30"
```

### OCCURS — Arrays and Tables

```cobol
01 PRICE-TABLE.
   05 PRICE-ENTRY OCCURS 100.
      10 ITEM-CODE    PIC X(10).
      10 ITEM-PRICE   PIC S9(5)V99.
```

```java
Record table = Record.define("PRICE-TABLE")
    .group("PRICE-ENTRY", g -> g
        .pic("ITEM-CODE",  "X(10)")
        .pic("ITEM-PRICE", "S9(5)V99"))
    .occurs(100)
    .build();

table.move("ITEM-CODE", 0, "WIDGET-A");       // 0-based index
table.move("ITEM-PRICE", 0, new BigDecimal("29.99"));
```

**OCCURS DEPENDING ON** (variable-length arrays):

```java
Record rec = Record.define("VAR-TABLE")
    .pic("ITEM-COUNT", "9(3)")
    .pic("ITEM", "X(10)").occursDependingOn(100, "ITEM-COUNT")
    .build();
```

### Level-88 Conditions

```cobol
05 ACCOUNT-TYPE    PIC X.
   88 CHECKING      VALUE "C".
   88 SAVINGS       VALUE "S".
   88 MONEY-MARKET  VALUE "M".
   88 VALID-TYPE    VALUE "C" "S" "M".
   88 PREMIUM       VALUE "G" THRU "Z".
```

```java
Record rec = Record.define("ACCT")
    .pic("ACCOUNT-TYPE", "X")
        .value88("CHECKING",     "C")
        .value88("SAVINGS",      "S")
        .value88("MONEY-MARKET", "M")
        .value88("VALID-TYPE",   "C", "S", "M")  // multiple values
        .value88Range("PREMIUM", "G", "Z")         // VALUE ... THRU ...
    .build();

rec.move("ACCOUNT-TYPE", "S");
assertTrue(rec.is("SAVINGS"));
assertTrue(rec.is("VALID-TYPE"));

rec.set("CHECKING");  // sets ACCOUNT-TYPE to "C"

// Custom conditions with lambdas — extend beyond COBOL:
.condition("HIGH-BALANCE", val -> new BigDecimal(val.trim()).compareTo(threshold) > 0)
```

### Field References — By-Reference Handles

COBOL data items are inherently by-reference: when you pass a field to a CALL,
the called program operates on the same memory. `Field` provides this in Java.

```java
Field salary = emp.field("EMP-SALARY");
Field bonus  = ws.field("WS-BONUS");
Field total  = ws.field("WS-TOTAL");

// Fields are live references into the record's byte buffer.
// Mutating through a Field mutates the record.
salary.move(new BigDecimal("75000.00"));
salary.add(new BigDecimal("5000.00"));

// Pass to GIVING — by-reference, the Arithmetic writes directly into the field
Arithmetic.add(salary.get(), bonus.get())
    .giving(total)
    .rounded()
    .execute();

// Pass to methods — real by-reference semantics
processField(salary);  // method receives and mutates the same data
```

### Arithmetic — ADD, SUBTRACT, MULTIPLY, DIVIDE, COMPUTE

Basic arithmetic modifies the target field in place, respecting its PIC constraints:

```cobol
ADD 100.00 TO BALANCE.
SUBTRACT FEE FROM BALANCE.
MULTIPLY RATE BY BALANCE.
DIVIDE BALANCE BY 12 GIVING MONTHLY-AMT REMAINDER REM-AMT.
COMPUTE INTEREST = PRINCIPAL * RATE / 12.
ADD AMT-A AMT-B GIVING TOTAL ROUNDED ON SIZE ERROR PERFORM ERR-RTN.
```

```java
// In-place (modifies the target field):
rec.add("BALANCE", new BigDecimal("100.00"));
rec.subtract("BALANCE", rec.getDecimal("FEE"));
rec.multiply("BALANCE", rec.getDecimal("RATE"));

// COMPUTE — use Java's BigDecimal for the expression, store with PIC constraints:
BigDecimal interest = principal.multiply(rate)
    .divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_EVEN);
rec.compute("INTEREST", interest);

// GIVING — result stored in a different field:
Arithmetic.divide(rec.getDecimal("BALANCE"), BigDecimal.valueOf(12))
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

### INITIALIZE

```cobol
INITIALIZE EMPLOYEE-RECORD.
```

```java
emp.initialize();           // spaces for alpha, zeros for numeric, recurse groups
emp.initialize("EMP-NAME"); // initialize just a group
```

### Program Structure — Paragraphs, PERFORM, EVALUATE

COBOL programs are structured as named paragraphs executed in sequence.
`Program` and `ProgramContext` model this as an actor/container:

```cobol
PROCEDURE DIVISION.
   MAIN-LOGIC.
       PERFORM INIT-ROUTINE.
       PERFORM PROCESS-LOOP UNTIL END-OF-FILE.
       PERFORM CLEANUP.
       STOP RUN.

   INIT-ROUTINE.
       OPEN INPUT CUST-FILE.
       MOVE "N" TO WS-EOF.

   PROCESS-LOOP.
       READ CUST-FILE INTO WS-REC
           AT END SET END-OF-FILE TO TRUE.
       IF NOT END-OF-FILE
           PERFORM PROCESS-CUSTOMER.

   PROCESS-CUSTOMER.
       ADD CUST-BALANCE TO WS-TOTAL.
       ADD 1 TO WS-COUNT.

   CLEANUP.
       CLOSE CUST-FILE.
       DISPLAY "Processed " WS-COUNT " customers".
       DISPLAY "Total: " WS-TOTAL.
```

```java
Program.define("CUSTOMER-REPORT")
    .workingStorage(wsRec)
    .paragraph("MAIN-LOGIC", ctx -> {
        ctx.perform("INIT-ROUTINE")
           .performUntil("PROCESS-LOOP", () -> wsRec.is("END-OF-FILE"))
           .perform("CLEANUP")
           .stopRun();
    })
    .paragraph("INIT-ROUTINE", ctx -> {
        ctx.open(custFile, CobolFile.OpenMode.INPUT);
        wsRec.move("WS-EOF", "N");
    })
    .paragraph("PROCESS-LOOP", ctx -> {
        ctx.read(custFile).into(wsRec)
           .atEnd(() -> wsRec.set("END-OF-FILE"))
           .notAtEnd(() -> ctx.perform("PROCESS-CUSTOMER"))
           .execute();
    })
    .paragraph("PROCESS-CUSTOMER", ctx -> {
        wsRec.add("WS-TOTAL", wsRec.getDecimal("CUST-BALANCE"))
             .add("WS-COUNT", BigDecimal.ONE);
    })
    .paragraph("CLEANUP", ctx -> {
        ctx.close(custFile)
           .display("Processed ", wsRec.getInt("WS-COUNT"), " customers")
           .display("Total: ", wsRec.getDecimal("WS-TOTAL"));
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
| `PERFORM PARA WITH TEST AFTER UNTIL cond` | `ctx.performUntilAfter("PARA", () -> cond)` |
| `PERFORM PARA VARYING I FROM 1 BY 1 UNTIL I > 10` | `ctx.performVarying("PARA", rec, "I", 1, 1, () -> rec.getInt("I") > 10)` |
| `PERFORM PARA VARYING I ... AFTER J ...` | `ctx.performVaryingAfter("PARA", rec,"I",1,1,untilI, rec,"J",1,1,untilJ)` |

### EVALUATE (Switch/Case)

```cobol
EVALUATE TRUE
   WHEN SCORE >= 90  PERFORM A-GRADE
   WHEN SCORE >= 80  PERFORM B-GRADE
   WHEN OTHER        PERFORM DEFAULT-GRADE
END-EVALUATE.
```

```java
ctx.evaluateTrue()
   .whenTrue(() -> rec.getInt("SCORE") >= 90, () -> ctx.perform("A-GRADE"))
   .whenTrue(() -> rec.getInt("SCORE") >= 80, () -> ctx.perform("B-GRADE"))
   .whenOther(() -> ctx.perform("DEFAULT-GRADE"))
   .execute();
```

### GO TO and EXIT PARAGRAPH

```java
ctx.goTo("ERROR-HANDLER");     // transfer control to another paragraph
ctx.exitParagraph();           // return from current paragraph immediately
```

### File I/O

COBOL has first-class file handling with sequential, indexed, and relative
organizations.

```cobol
SELECT CUST-FILE ASSIGN TO "CUSTFILE"
   ORGANIZATION IS INDEXED
   ACCESS IS DYNAMIC
   RECORD KEY IS CUST-ID
   FILE STATUS IS WS-STATUS.
```

```java
FileStatus status = new FileStatus();
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
ctx.write(custFile, rec);
ctx.close(custFile);
```

### String Operations — INSPECT, STRING, UNSTRING

```cobol
INSPECT MESSAGE TALLYING SPACE-COUNT FOR ALL SPACES.
INSPECT AMOUNT-DISPLAY REPLACING LEADING ZEROS BY SPACES.
INSPECT DATA-FIELD CONVERTING "abcdefghijklmnopqrstuvwxyz"
                           TO "ABCDEFGHIJKLMNOPQRSTUVWXYZ".
```

```java
int spaces = Inspect.on(rec, "MESSAGE").tallyAll(" ").count();

Inspect.on(rec, "AMOUNT-DISPLAY").replaceLeading('0', ' ').apply();

Inspect.on(rec, "DATA-FIELD")
    .converting("abcdefghijklmnopqrstuvwxyz",
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    .apply();

// With BEFORE/AFTER scoping:
Inspect.on(rec, "DATA").tallyAll("A").before(".").count();
```

**STRING** (concatenation with delimiters):

```cobol
STRING FIRST-NAME DELIMITED BY SPACES
       " " DELIMITED BY SIZE
       LAST-NAME DELIMITED BY SPACES
  INTO FULL-NAME
  ON OVERFLOW PERFORM OVERFLOW-ROUTINE.
```

```java
CobolString.into(rec, "FULL-NAME")
    .from(rec, "FIRST-NAME").delimitedBySpaces()
    .literal(" ")
    .from(rec, "LAST-NAME").delimitedBySpaces()
    .onOverflow(() -> ctx.perform("OVERFLOW-ROUTINE"))
    .execute();
```

**UNSTRING** (splitting):

```cobol
UNSTRING INPUT-LINE DELIMITED BY "," OR SPACES
   INTO FIELD-1 FIELD-2 FIELD-3.
```

```java
CobolUnstring.from(rec, "INPUT-LINE")
    .delimitedBy(",")
    .orDelimitedBy(" ")
    .into(rec, "FIELD-1")
    .into(rec, "FIELD-2")
    .into(rec, "FIELD-3")
    .execute();
```

### SEARCH and SEARCH ALL

```cobol
SEARCH TABLE-ENTRY
   AT END PERFORM NOT-FOUND-ROUTINE
   WHEN TABLE-KEY(IDX) = SEARCH-VALUE
        PERFORM FOUND-ROUTINE
END-SEARCH.
```

```java
Search.table(rec, "TABLE-ENTRY")
    .atEnd(() -> ctx.perform("NOT-FOUND-ROUTINE"))
    .when(idx -> rec.getString("TABLE-KEY", idx).trim().equals(searchValue),
          idx -> ctx.perform("FOUND-ROUTINE"))
    .execute();

// Binary search (SEARCH ALL) — requires sorted table:
Search.all(rec, "TABLE-ENTRY")
    .key(idx -> rec.getString("TABLE-KEY", idx).trim())
    .equalTo(searchValue)
    .atEnd(() -> ctx.perform("NOT-FOUND"))
    .found(idx -> { /* process found entry at idx */ })
    .execute();
```

### SORT with INPUT/OUTPUT PROCEDURE

```cobol
SORT SORT-FILE
   ON ASCENDING KEY SORT-NAME
   ON DESCENDING KEY SORT-AMOUNT
   INPUT PROCEDURE IS FEED-RECORDS
   OUTPUT PROCEDURE IS PROCESS-SORTED.
```

```java
CobolSort.on(sortRecord)
    .ascending("SORT-NAME")
    .descending("SORT-AMOUNT")
    .inputProcedure(input -> {
        // Read source data and RELEASE each record to the sort
        while (sourceFile.read(inputRec)) {
            sortRecord.moveCorresponding(inputRec);
            input.release();
        }
    })
    .outputProcedure(output -> {
        // RETURN sorted records one at a time
        while (output.returnRecord()) {
            outputRec.moveCorresponding(sortRecord);
            outputFile.write(outputRec.buffer());
        }
    })
    .execute();
```

### Intrinsic Functions

```cobol
MOVE FUNCTION CURRENT-DATE TO WS-DATE.
COMPUTE LEN = FUNCTION LENGTH(WS-NAME).
MOVE FUNCTION UPPER-CASE(WS-INPUT) TO WS-OUTPUT.
COMPUTE RESULT = FUNCTION MOD(DIVIDEND, DIVISOR).
COMPUTE AVG = FUNCTION MEAN(V1, V2, V3).
```

```java
rec.move("WS-DATE", Intrinsic.currentDate());
rec.compute("LEN", Intrinsic.length(rec, "WS-NAME"));
rec.move("WS-OUTPUT", Intrinsic.upperCase(rec.getString("WS-INPUT")));
rec.compute("RESULT", Intrinsic.mod(dividend, divisor));
rec.compute("AVG", Intrinsic.mean(v1, v2, v3));
```

Available functions: `currentDate`, `length`, `upperCase`, `lowerCase`, `reverse`,
`trim`, `trimLeading`, `trimTrailing`, `numval`, `numvalC`, `mod`, `rem`, `integer`,
`integerPart`, `abs`, `max`, `min`, `mean`, `median`, `sqrt`, `log`, `log10`, `ord`,
`charFunction`.

### Embedded SQL (EXEC SQL)

COBOL embedded SQL uses host variables (`:FIELD-NAME`) to bind record fields
to SQL parameters and result columns.

```cobol
EXEC SQL
    SELECT CUST_NAME, CUST_BALANCE
    INTO :WS-NAME, :WS-BALANCE
    FROM CUSTOMERS
    WHERE CUST_ID = :WS-CUST-ID
END-EXEC.
IF SQLCODE = 100
    DISPLAY "Not found"
END-IF.
```

```java
sql.select("SELECT CUST_NAME, CUST_BALANCE FROM CUSTOMERS WHERE CUST_ID = ?")
   .param(rec, "WS-CUST-ID")           // host variable → PreparedStatement param
   .into(rec, "WS-NAME", "WS-BALANCE") // INTO → ResultSet columns to record fields
   .execute();

if (sql.isNotFound()) {
    ctx.display("Not found");
}
```

**Cursors** (multi-row results):

```java
SqlCursor cursor = sql.declareCursor("CUST-CURSOR",
    "SELECT NAME, BALANCE FROM CUSTOMERS ORDER BY NAME");

sql.open(cursor);
sql.fetch(cursor).into(rec, "WS-NAME", "WS-BAL").execute();
while (sql.isSuccess()) {
    // process row
    sql.fetch(cursor).into(rec, "WS-NAME", "WS-BAL").execute();
}
sql.close(cursor);
```

**Connection lifecycle** (acquire/work/commit/release):

```java
// Unit-of-work pattern — commit on success, rollback on failure, always release:
SqlSession.work(factory, session -> {
    session.sql().select("SELECT ...").param(...).into(...).execute();
    session.sql().execute("UPDATE ...").param(...).execute();
    // auto-commit at end of block
});

// Or explicit try-with-resources:
try (SqlSession session = SqlSession.from(factory)) {
    session.sql().select(...).param(...).into(...).execute();
    session.commit();
}
// no commit → auto-rollback; connection always released
```

Connection factories support pooling:

```java
ConnectionFactory factory = ConnectionFactory.simple(url, user, pass);       // no pooling
ConnectionFactory pooled  = ConnectionFactory.cached(factory, 10);           // simple pool
ConnectionFactory ds      = ConnectionFactory.dataSource(hikariDataSource);  // production pool
```

## Extension Points

The library is designed for extension via interfaces and lambdas:

| Extension Point | Interface/Type | Purpose |
|---|---|---|
| `Condition` | `@FunctionalInterface` | Custom level-88 conditions beyond value matching |
| `SizeErrorHandler` | interface | ON SIZE ERROR / NOT ON SIZE ERROR as lambdas |
| `CobolFile` | interface | Custom file backends (JDBC, cloud, message queues) |
| `ConnectionFactory` | `@FunctionalInterface` | Pluggable connection acquisition/release |
| `Inspect.replaceAll(IntUnaryOperator)` | lambda | Custom character-level transformations |
| `CobolString.onOverflow(Runnable)` | lambda | Overflow handling |
| `Program.onDisplay(Consumer<String>)` | lambda | Redirect DISPLAY output |
| `Program.onAccept(Supplier<String>)` | lambda | Redirect ACCEPT input |
| `Search.when(IntPredicate, IntConsumer)` | lambdas | Custom search conditions and actions |

## Building

```bash
export JAVA_HOME=/path/to/jdk17
mvn clean test
```

Requires Java 17+ and Maven. Tests use JUnit 5 and H2 (in-memory database for SQL tests).

## Status

This is a working prototype demonstrating feasibility. The core COBOL runtime
semantics are implemented and tested (119 tests). Missing pieces for production
transpilation include:

- CALL with LINKAGE SECTION (inter-program communication with by-reference parameters)
- Report Writer (REPORT SECTION — declarative report generation)
- Screen Section (terminal UI)
- CICS middleware commands
- EBCDIC collation for comparisons
- COPY/REPLACE preprocessor (transpiler concern, not runtime)
- SPECIAL-NAMES (DECIMAL-POINT IS COMMA, etc.)
