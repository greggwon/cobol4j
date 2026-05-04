# Design Philosophy — cobol4j

## The Problem Everyone Gets Wrong

Most COBOL-to-Java migration attempts try to translate COBOL semantics into raw
Java — generating verbose, literal code that replicates every PIC clause, every
MOVE rule, every decimal alignment behavior inline. The result is unmaintainable:
thousands of lines of generated code that no one can read, no one can debug, and
no one dares touch. You've traded one legacy system for another.

cobol4j takes a fundamentally different approach.

## Don't Translate Semantics. Provide Them.

The core insight: **COBOL's semantics belong in a runtime library, not in
generated code.** A PIC clause is parsed once, in the library. A MOVE's
type-converting, padding, truncating copy logic is implemented once, in the
library. Decimal alignment, sign handling, packed decimal encoding — all
implemented once, tested once, correct everywhere.

The transpiler's job collapses to syntax-directed translation — parse a COBOL
statement, emit the corresponding API call. No semantic analysis. No inline
replication of COBOL's behavioral rules. The generated code is a thin layer of
calls against a library that already understands everything COBOL does.

The result: generated code that a COBOL programmer can read and recognize, and
a Java programmer can understand and maintain.

## Two Architectural Decisions That Made It Work

### Fluent API

Every operation chains. Records define themselves in a single expression.
Programs build themselves with paragraph lambdas. Arithmetic flows through
GIVING/ROUNDED/REMAINDER without temporary variables. The API reads like a
description of what the program does, not an instruction manual for how to do it.

This matters for transpilation because the emitter is almost a lookup table.
Each COBOL verb maps to one fluent call. There's no state to thread through
the code generator, no complex emission patterns, no multi-line scaffolding
around each statement. One COBOL line in, one Java line out.

It also matters for humans. The generated code is something you can work with
after transpilation — edit it, extend it, refactor it. The fluent style makes
the intent visible. A COBOL programmer reads it and sees their program. A Java
programmer reads it and sees clean API usage.

### Actor Pattern

Programs don't manage their own state. They execute within a context — a
`ProgramContext` for batch programs, a `CicsContext` for transactions — that
holds the execution environment: paragraph dispatch, file handles, control flow,
display output, SQL sessions, temporary storage.

This eliminates an entire class of code generation complexity. The transpiler
doesn't need to generate resource management, connection lifecycle, error
recovery scaffolding, or transaction coordination. It emits lambdas into a
container that already knows how to do all of that.

The pattern scales naturally. `Program` handles batch execution. `CicsRegion`
handles transaction dispatch with hot-deploy and shared resource pools.
`SqlSession` handles connection acquire/commit/release. Each is a container
that manages a lifecycle so the generated code doesn't have to.

## Decimal by Design

COBOL has no floating point. Every digit is exact, every decimal position is
defined, and there is no IEEE 754 anywhere in the system. A balance of $100.10
is exactly 100.10 — not 100.09999999999999432.

cobol4j enforces this at the type level. `Decimal` is the only numeric type in
the public API. It can only be constructed from a `String` or a `long` — both
exact representations. There is no `double` parameter anywhere. There is no
`float`. There is no `BigDecimal` in any public method signature. The mantissa
error that plagues most Java financial code is not merely discouraged — it is
structurally impossible.

Values are immutable, weakly cached, and observable via `ValueTracker` for audit
trails. The library handles money the way COBOL handles money: exactly.

## Everything Is Pluggable

The library is designed around interfaces and lambdas, not concrete classes:

- **`CicsProgram`** is a `@FunctionalInterface` — a transaction handler is a lambda
- **`MessagePort`** abstracts messaging with three delivery semantics
- **`ConnectionFactory.jdbc().cached()`** works with any JDBC database
- **`RecordCodec`** and **`FieldCodec`** are discoverable via Java's `ServiceLoader`
- **`SystemCall`** maps POSIX functions with a replaceable implementation
- **`Condition`** accepts custom predicates for level-88 extensions
- Programs are hot-deployable into a running `CicsRegion` via JAR drop

No feature requires subclassing an abstract base. No integration requires
importing a framework. The extension model is interfaces, lambdas, and
`ServiceLoader` — standard Java, zero magic, zero external dependencies.

## The Transpiler Is the Least Important Part

This is counterintuitive for a COBOL migration project, but it's true. The
transpiler is a thin syntax translator — a recursive descent parser that walks
COBOL source and emits corresponding API calls. It handles error recovery without
cascading, collects diagnostics for every decision point, and refuses to produce
partial output when constructs can't be translated.

But the transpiler is replaceable. You could write a different parser. You could
hand-write the API calls. You could generate them from a copybook. The runtime
library and its programming model are what matter — they're the platform that
makes COBOL semantics available in Java without compromise.

## The System

```
21,000+ lines of working code across 83 files
328 passing tests — zero failures
A complete runtime library (Record, Decimal, Field, Variable, Program)
A recursive descent transpiler with error recovery and full diagnostics
SQL with connection pooling across any JDBC database
EBCDIC codec and mainframe file interop (CP037, CP500, CP1047)
A CICS transaction processing container with hot-deploy via ServiceLoader
Messaging with fire-and-forget, at-least-once, and exactly-once delivery
JSON/XML record codecs with pluggable extension via ServiceLoader
POSIX system call mapping for transpiled CALL statements
A command-line runner that compiles and executes COBOL directly
Zero production dependencies
```

## The Point

There's a difference between translating a program and providing a platform.

Translation gives you a snapshot — a one-time conversion that starts decaying
the moment it's generated. Every bug fix, every enhancement, every adaptation
to the new environment requires understanding both the original COBOL and the
generated Java, with no shared abstraction to bridge them.

A platform gives you a foundation. The semantics are in the library. The
generated code is a thin, readable, editable layer on top. New programs can be
written directly against the API. Old programs can be migrated incrementally.
COBOL and Java programs can coexist, sharing data through copybooks, EBCDIC
files, message queues, and database tables — because the platform speaks both
languages.

cobol4j is the platform.
