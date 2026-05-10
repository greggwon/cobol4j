# Why cobol4j — A Migration Toolset Comparison

If you're evaluating COBOL-to-Java migration tools, you'll find several projects
that take fundamentally different approaches to the problem. This document explains
the design choices behind cobol4j and how they compare to the alternatives —
particularly the "mechanical compiler" family of tools that translate COBOL syntax
directly into equivalent target-language constructs.

---

## The Core Question

When you move COBOL to Java, what are you actually trying to achieve?

1. **Get off the mainframe** — run the same business logic on commodity hardware
2. **Maintain the code in Java** — developers can read, modify, and extend it
3. **Integrate with modern systems** — REST APIs, cloud databases, message queues,
   CI/CD pipelines, modern observability
4. **Preserve correctness** — the same inputs must produce the same outputs,
   especially for financial calculations

Every tool claims to address all four. The difference is in *which one they
optimize for* and what they sacrifice to get there.

---

## Approach 1: Mechanical Compilation (COBOL → C, or COBOL → verbose Java)

Projects like [opensourcecobol4j](https://github.com/opensourcecobol/opensourcecobol4j)
and its predecessor (COBOL → C via GCC) take the compiler approach:

- The COBOL source is parsed and each statement is translated to a corresponding
  construct in the target language
- A runtime library (`libcobj.jar`, or a C runtime) provides low-level support
  for I/O, packed decimal math, EBCDIC handling, etc.
- The generated code mirrors the structure of the COBOL source line-for-line

**What you get:**
- High fidelity — the translation is mechanical and predictable
- The original COBOL program's structure is preserved exactly
- Works for *any* COBOL program, regardless of complexity

**What you give up:**
- The generated code is not idiomatic Java — it reads like COBOL wearing a Java costume
- Maintenance requires COBOL knowledge to understand what the Java is doing
- Integration with Java libraries, frameworks, and tooling is unnatural
- The runtime library is a black box — you can't step through it with standard
  Java debugging intuitions
- Embedded SQL, file I/O, messaging, and transaction processing are typically
  handled by *separate* preprocessors and libraries (each with its own build
  step, configuration, and version lifecycle)

**The fragmentation problem:**

A mechanical compiler typically needs:
- A separate SQL preprocessor that runs *before* the compiler
- A separate indexed file library (ISAM) linked at a different layer
- Docker containers or complex shell scripts to wire the build together
- Multiple programming languages in the toolchain (C, Shell, COBOL itself)

Each piece has its own bugs, its own release cycle, and its own learning curve.
The more pieces, the more things that can go wrong — and the harder it is for a
new developer to set up, understand, or contribute to.

---

## Approach 2: Semantic Runtime with Thin Transpiler (cobol4j)

cobol4j inverts the architecture:

- **The runtime library implements COBOL's semantics** — data layout, MOVE rules,
  decimal arithmetic, file I/O, SQL, CICS, program structure — as a fluent Java API
- **The transpiler is thin** — it maps COBOL syntax to runtime API calls without
  reimplementing COBOL behavior in the generated code
- **The generated code reads like a description of the program**, not a
  line-by-line mechanical rewrite

**What you get:**
- Generated code that Java developers can read without knowing COBOL
- One project, one language, one build (`mvn test`)
- SQL, files, CICS, messaging, codecs, and the transpiler all integrated
- Standard Java debugging — step into `Record.move()` and see exactly what happens
- The runtime is tested independently (500+ tests) — correctness is in the library,
  not scattered across thousands of generated files
- Modern Java patterns: lambdas for paragraphs, fluent builders for records,
  ServiceLoader for extensibility

**What you give up:**
- The transpiler covers a practical subset of COBOL, not 100% of every dialect
- Programs using very obscure features (Report Writer, Screen Section) need
  hand-finishing or a service implementation
- The generated code doesn't mirror the COBOL source line-for-line — it's a
  higher-level representation

---

## Side-by-Side: Generated Code

### COBOL source

```cobol
01 WS-CUSTOMER.
   05 WS-CUST-ID      PIC X(10).
   05 WS-CUST-NAME    PIC X(30).
   05 WS-BALANCE      PIC S9(7)V99 COMP-3.

MOVE "C001" TO WS-CUST-ID.
ADD 1500.00 TO WS-BALANCE.
IF WS-BALANCE > 10000.00
    DISPLAY "High value customer: " WS-CUST-NAME
END-IF.
```

### Mechanical compiler output (representative)

```java
// Typically hundreds of lines of boilerplate above...
AbstractCobolField f_8 = new AbstractCobolField(10, new CobolFieldAttribute(...));
AbstractCobolField f_9 = new AbstractCobolField(30, new CobolFieldAttribute(...));
AbstractCobolField f_10 = new AbstractCobolField(5, new CobolFieldAttribute(...));

f_8.moveFrom("C001");
f_10.addPacked(new CobolDecimal("150000", 2));
if (f_10.compareTo(new CobolDecimal("1000000", 2)) > 0) {
    CobolRuntime.display("High value customer: ");
    CobolRuntime.display(f_9);
}
```

Fields are anonymous (`f_8`, `f_9`, `f_10`). The packed decimal `1500.00` becomes
`"150000"` with a scale parameter. The `COMP-3` storage is managed by the runtime
but exposed as raw byte manipulation. A Java developer reading this code has no
idea what `f_10` is without cross-referencing the COBOL source.

### cobol4j output

```java
Record wsCustomer = Record.define("WS-CUSTOMER")
    .pic("WS-CUST-ID",   "X(10)")
    .pic("WS-CUST-NAME", "X(30)")
    .pic("WS-BALANCE",   "S9(7)V99").comp3()
    .build();

wsCustomer.move("WS-CUST-ID", "C001");
wsCustomer.add("WS-BALANCE", Decimal.of("1500.00"));
if (wsCustomer.getDecimal("WS-BALANCE").greaterThan(Decimal.of("10000.00"))) {
    ctx.display("High value customer: ", wsCustomer.getString("WS-CUST-NAME").trim());
}
```

Field names are preserved. The PIC clause is visible. `Decimal.of("1500.00")` is
what a human would write. A Java developer can read this without knowing COBOL.
A COBOL developer can verify it matches the original without knowing Java internals.

---

## Integration Architecture

### Mechanical compiler ecosystem

```
COBOL source
    │
    ▼
SQL Preprocessor (separate C program)  →  modified COBOL source
    │
    ▼
COBOL Compiler (COBOL → Java bytecode or C)
    │
    ▼
Runtime library (libcobj.jar)     ← black box, COBOL-originated code
    │
    ├── ISAM library (separate C code, JNI bridge)
    ├── SQL library (separate project, separate config)
    └── Docker/Shell glue to wire it all together
```

Each arrow is a separate tool with its own build step, configuration file,
and failure mode.

### cobol4j architecture

```
COBOL source
    │
    ▼
Transpiler (Lexer → Parser → AST → JavaEmitter)
    │
    ▼
Java source using cobol4j API
    │
    ▼
cobol4j Runtime Library (single JAR)
    ├── Record / Decimal / Field      (data)
    ├── Program / ProgramContext      (control flow)
    ├── CobolSql / SqlSession         (database — any JDBC)
    ├── CobolFile                     (sequential + indexed files)
    ├── CicsRegion / CicsContext      (transaction processing)
    ├── MessagePort                   (async messaging)
    ├── Inspect / CobolString / Sort  (string + table ops)
    └── Ebcdic / CopybookImporter    (mainframe interop)
```

One `mvn test`. One JAR. Standard JDBC for any database. Standard Java logging.
Standard JUnit for testing. Standard Maven for dependency management.

---

## The Maintenance Question

Six months after migration, a business rule changes. A developer needs to modify
the customer discount logic.

**With mechanical compilation:**
- Find the generated Java file (one of hundreds/thousands)
- Identify which `f_N` field is the discount rate (cross-reference COBOL source)
- Understand the packed decimal manipulation
- Make the change
- Hope the runtime library handles the new edge case correctly
- No unit test infrastructure unless you built one from scratch

**With cobol4j:**
- Find the paragraph (named, searchable: `"CALC-DISCOUNT"`)
- Read the logic (field names preserved, arithmetic is `Decimal.multiply()`)
- Make the change
- Run `mvn test` — the existing 500+ test infrastructure validates
- The runtime semantics (truncation, rounding, sign handling) are already tested

---

## When to Choose What

**Choose a mechanical compiler when:**
- You have millions of lines of COBOL and need 100% automated translation
- No one will ever read or modify the generated code
- The goal is purely "get off the mainframe" with no further development
- You have dedicated build infrastructure for the multi-tool pipeline

**Choose cobol4j when:**
- You want developers to *maintain* the migrated code
- You're migrating programs incrementally (some COBOL, some new Java)
- You need integration with Java frameworks (Spring, Quarkus, etc.)
- You want one build system, one test suite, one debugging experience
- The programs use SQL, CICS, or messaging (all integrated, no separate preprocessors)
- You want to understand what the generated code does without the COBOL source open

---

## Summary

The mechanical compiler approach optimizes for **coverage** — translate everything,
regardless of readability. cobol4j optimizes for **maintainability** — generate code
that humans can work with, backed by a tested runtime that guarantees COBOL
semantics.

Both are valid. The right choice depends on whether migration is the *end* of
your COBOL story, or the *beginning* of your Java story.
