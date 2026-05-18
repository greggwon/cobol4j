# Is COBOL Necessary?

The question isn't whether COBOL works. It does — reliably, at scale, in
production, for decades. The question is whether the organizations running
COBOL can continue to maintain, extend, and staff for it. The answer,
increasingly, is no. Not because the language failed, but because the
workforce moved on and the institutional knowledge didn't survive the
transition.

## The Real Problem

A 40-year-old COBOL program in a bank's batch settlement system isn't just
code. It's the accumulated history of every business rule, regulatory
requirement, edge case, and undocumented workaround that the organization
has encountered over four decades. The paragraph names are the process map.
The copybooks are the data dictionary. The 88-level conditions are the
business vocabulary:

```cobol
88 PREFERRED-CUSTOMER    VALUE "P" "G" "D".
```

Why "D"? Nobody knows. But the quarterly revenue report depends on it.

The program is the only specification. No one alive at the organization wrote
it. No one fully understands every path through it. The code IS the business
logic — and it's written in a language that fewer people can read each year.

## The Approaches

### IBM's Answer: Keep COBOL, Add a Bridge

IBM's Enterprise COBOL 6.5 (released June 2025, updated through March 2026)
provides Java interoperability through JNI:

- **JAVA-CALLABLE** — marks a COBOL program so Java can call it
- **JAVA-SHAREABLE** — exposes WORKING-STORAGE items to Java
- **CALL 'Java.class.method'** — COBOL calls static Java methods

This is a compatibility layer. The COBOL stays COBOL. Java wraps it,
calls into it, reads its data through a JNI bridge. The black box
remains closed — you interact with it at arm's length.

IBM's own type mapping reveals the limitations:

| COBOL Type | Java Type | Problem |
|-----------|-----------|---------|
| COMP-3 / PACKED-DECIMAL | `BigDecimal` | The `BigDecimal(double)` constructor is one typo away |
| COMP-1 (hex float) | `float` | IEEE precision loss — IBM's docs warn about this |
| COMP-2 (hex float) | `double` | Same precision loss |
| Group items | `byte[]` | "It is the Java application's responsibility to read and set the byte array appropriately" |
| PIC S9(5)V9(4) | `BigDecimal` | The V-implied decimal scale isn't carried in the Java type |

IBM's own example code demonstrates the risk:

```java
// From IBM's COBOL/Java interop documentation
enterprise.COBOL.strg.PROGA.F1.put(3.1415f);           // float — precision loss
enterprise.COBOL.strg.PROGA.G1.data2.put(new BigDecimal(25.3645));  // double constructor — mantissa noise
```

The `new BigDecimal(25.3645)` call produces
`25.364500000000000...` — not `25.3645`. IBM's own sample code
introduces the exact mantissa error that COBOL's fixed-point arithmetic
was designed to prevent.

Additional constraints:
- Field names must be unique across the entire COBOL application
- Names limited to 26 characters
- WORKING-STORAGE must be initialized before Java can access it
- Non-Java-compatible types are silently ignored
- Only static Java methods can be called
- Five levels of package nesting to read one field:
  `enterprise.COBOL.strg.PROGA.G1.data1.get()`

This approach makes sense when you're keeping COBOL on the mainframe
and adding Java alongside it. The COBOL program doesn't change. The risk
is low. But the black box stays closed, the mainframe bill stays high,
and the shrinking COBOL workforce is still a dependency.

### SoftwareMining's Answer: Generate Java Beans

SoftwareMining converts COBOL to getter/setter Java beans. The generated
code is readable — `data.setYearRow(1)` instead of raw byte manipulation.
But their arithmetic uses Java `double`:

```java
// SoftwareMining generated output
data.setIntdec(data.getNextInterest() / (12.0 * 100.0));
```

That's `double` division for a financial interest calculation. The original
COBOL used fixed-point decimal. The migration silently changed the arithmetic
model. For a ledger processing millions of transactions, the accumulated
rounding differences will eventually surface as unexplained penny discrepancies
that an auditor will find.

### IBM watsonx: Generate Java with AI

IBM's watsonx Code Assistant for Z uses a large language model to translate
COBOL to Java. The generated code looks idiomatic — like hand-written Java.
But:

- **Non-deterministic** — the same input can produce different output on
  different runs
- **No runtime guarantee** — there's no tested library ensuring COBOL
  semantics are preserved
- **Requires human review** — IBM explicitly says every generated method
  needs validation
- **Not auditable** — "the AI generated this" is not an answer a bank
  examiner accepts

### Mechanical Compilers (Ispirer, CoJaC, opensourcecobol4j)

These tools parse COBOL and emit equivalent Java, usually with a proprietary
runtime library. The generated code preserves COBOL structure but is often
difficult to read — anonymous variables, framework wrapper classes, and
COBOL-structured Java that requires COBOL knowledge to understand.

The runtime libraries are closed-source (Ispirer, CoJaC) or written in
COBOL/C (opensourcecobol4j), making the generated code dependent on an
opaque layer that can't be independently verified.

### Fresche Solutions: Automated Factory

A commercial "Code Transformation Factory" that automates conversion.
Marketing materials claim risk-free migration. No public examples of
generated code. No published information about the arithmetic model,
runtime library, or data type handling. Contact sales for details.

## cobol4j's Answer: A Bridge, Not a Compatibility Layer

cobol4j takes a different approach. Instead of wrapping COBOL in a JNI
bridge or mechanically translating syntax, it provides a **Java runtime
library that implements COBOL semantics** — data layout, MOVE rules,
fixed-point decimal arithmetic, file I/O, program structure, SQL — and
a thin transpiler that maps COBOL syntax to calls against that runtime.

The generated code reads like a description of the COBOL program:

```java
Record customer = Record.define("WS-CUSTOMER")
    .pic("WS-CUST-ID",      "X(10)")
    .pic("WS-CUST-BALANCE", "S9(7)V99").comp3()
    .pic("WS-CUST-STATUS",  "X")
        .value88("PREFERRED-CUSTOMER", "P", "G", "D")
    .build();

customer.move("WS-CUST-BALANCE", Decimal.of(253645, 4));  // 25.3645 — exact
if (customer.is("PREFERRED-CUSTOMER")) { ... }
```

### What's Different

**No float, no double, no mantissa risk.** The `Decimal` class is the only
numeric type in the public API. There is no `BigDecimal(double)` constructor
to misuse. `Decimal.of(253645, 4)` produces exactly `25.3645` — the unscaled
value and the decimal position, just like COBOL's implied decimal.

**PIC clauses are visible.** The generated code shows `.pic("WS-CUST-BALANCE",
"S9(7)V99").comp3()` — the field name, the picture clause, and the usage are
all in the Java source. A developer reading the code knows the field is a
7-digit signed decimal with 2 decimal places in packed format. IBM's interop
layer gives you `BigDecimal` and hopes you remember the scale.

**The Record owns its data.** No `byte[]` interpretation. No external copybook
required. The Record knows its layout, its size, its field names, its conditions.
It reads and writes itself through `readFrom(InputStream)` and
`writeTo(OutputStream)`. It implements `Serializable` for Java-native transport.

**88-level conditions work correctly.** `customer.is("PREFERRED-CUSTOMER")`
checks if the status field contains "P", "G", or "D". IBM's mapping converts
a single PIC X with two 88-levels to a Java `boolean` — which can't represent
multi-value conditions at all.

**Deterministic and auditable.** The same COBOL input always produces the same
Java output. The transpiler has rules. The runtime has 550 tests. 29 of 30
programs from IBM's own COBOL Programming Course transpile and compile
successfully. When an auditor asks "how do you know the Java does the same
thing as the COBOL?" — the answer is testable, not "we reviewed the AI output."

**Standard Java, no JNI.** The generated code is a normal Java class. It can
implement interfaces, receive dependency injection, participate in Spring or
Quarkus frameworks, be tested with JUnit, be debugged with standard tools.
No JNI, no native libraries, no z/OS dependency. It runs on any JVM — including
IBM's own z/OS JVM if the mainframe is where you want it.

**Local I/O by default.** File assignments from the ENVIRONMENT DIVISION map to
system properties: `System.getProperty("cobol4j.file.CUSTFILE", "CUSTFILE.dat")`.
Database connections are pluggable: `ConnectionFactory.sqlite()` for local,
`ConnectionFactory.postgres()` for remote. The batch window doesn't die to
network latency because the I/O stays local unless you choose otherwise.

## The Visibility Argument

The real migration isn't COBOL to Java. It's from "nobody understands this"
to "we can at least read it."

A JNI-wrapped COBOL program is still a black box. You can call it, but you
can't see what it does. The business rules — the DSL of the company — remain
locked in a language the team can't read.

A mechanically compiled Java program with anonymous variables (`f_10`,
`f_11`) and framework wrapper classes is technically Java but practically
unreadable without the COBOL source open next to it.

An AI-generated Java program might look readable, but it's different every
time you generate it. You can't diff two runs. You can't build regression
tests against it. You can't audit it.

cobol4j generates Java where the field names, the PIC clauses, the condition
names, and the business logic are all visible in the code:

```java
.paragraph("CALC-DISCOUNT", ctx -> {
    if (customer.is("PREFERRED-CUSTOMER")) {
        wsAccum.compute("WS-DISCOUNT",
            wsAccum.getDecimal("WS-SUBTOTAL")
                .multiply(Decimal.of(15, 2)));  // 0.15 — 15% discount
    }
})
```

A Java developer can read that. A business analyst can follow it. An auditor
can trace the discount rule to the code. Nobody needs to know COBOL to
understand what the program does.

That's the bridge: not between COBOL and Java at runtime, but between the
business knowledge locked in the COBOL source and the development team that
needs to own it going forward.

## The Batch Window Question

The LinkedIn discussion about mainframe migration often centers on batch
performance: a settlement run that finishes in four hours on z/OS takes
eight hours on AWS because every database commit crosses a network.

This is a real physics problem. Nanosecond memory-bus latency vs. millisecond
network latency, multiplied by millions of sequential committed writes, is
catastrophic. No software optimization fixes it.

cobol4j doesn't pretend to solve the physics. What it does:

- **Keep I/O local.** `ConnectionFactory.sqlite()` on local NVMe. `CobolFile`
  on local disk. No network hop per commit.
- **Preserve the processing model.** Sequential committed writes stay
  sequential and committed. The generated code doesn't change the transaction
  semantics.
- **Enable architectural evolution.** Because the generated code is readable,
  a development team can identify which parts of the batch are truly sequential
  (the balance update chain) and which can be parallelized (audit trail,
  report generation, downstream feeds). You can't make that analysis on
  `f_10.addPacked()` output.

The batch window follows the I/O architecture, not the language. Move the
COBOL to readable Java. Keep the database local. Evolve the architecture
where the code lets you. That's the practical path.

## Open Source, Testable, Free

cobol4j is LGPL 2.1. The source is public. The runtime library has 550 tests.
The transpiler passes 29 of 30 programs from IBM's own teaching course. Anyone
can clone it, run `mvn test`, and verify the claims.

No sales call. No license negotiation. No proprietary runtime you can't
inspect. The code is the argument.

## See Also

- [COMPARISON.md](COMPARISON.md) — detailed comparison with mechanical compilers
- [EXAMPLES.md](EXAMPLES.md) — runnable demonstrations and test inventory
- [CICSDEMO.md](CICSDEMO.md) — CICS transaction deployment example
- [README.md](README.md) — getting started
