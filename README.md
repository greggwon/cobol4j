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

See [DESIGN.md](DESIGN.md) for the architecture philosophy and
[COMPARISON.md](COMPARISON.md) for a detailed look at how this approach differs
from mechanical COBOL-to-Java compilers.

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
from `String`, `long`, or an unscaled integer with a decimal point position — all
exact representations:

```java
Decimal price = Decimal.of("19.99");   // exact — from string
Decimal qty   = Decimal.of(5);         // exact — integer literal
Decimal total = price.multiply(qty);   // 99.95 — exact

// Implied decimal — like COBOL's PIC V clause
// The second parameter is how many digits from the right are after the decimal point
Decimal amt   = Decimal.of(1999, 2);   // 19.99 — unscaled 1999, 2 decimal places
Decimal tax   = Decimal.of(5, 2);      // 0.05  — unscaled 5, 2 decimal places
Decimal rate  = Decimal.of(19999L, 3); // 19.999

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

## Learn More

| I want to... | Read |
|---|---|
| See every COBOL feature mapped to Java | [FEATURES.md](FEATURES.md) |
| Understand why readable Java matters for migration | [ISCOBOLNECESSARY.md](ISCOBOLNECESSARY.md) |
| Compare with other tools (IBM watsonx, SoftwareMining, etc.) | [COMPARISON.md](COMPARISON.md) |
| Plan database performance for batch workloads | [DATABASESPEED.md](DATABASESPEED.md) |
| Run the demos and examples | [EXAMPLES.md](EXAMPLES.md) |
| See the CICS transaction deployment pattern | [CICSDEMO.md](CICSDEMO.md) |
| Understand the architecture philosophy | [DESIGN.md](DESIGN.md) |
| Find what's left to implement | [WHATSLEFT.md](WHATSLEFT.md) |

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
