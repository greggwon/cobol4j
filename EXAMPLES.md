# Examples and Learning Resources

This guide points you to the runnable examples, demonstrations, and documentation
that show how cobol4j works end-to-end.

## Running the Examples

All examples are implemented as JUnit tests and run with:

```bash
mvn test                                          # run everything
mvn test -Dtest=org.cobol4j.SupplierShipmentTest  # run one example
```

## Demonstrations

### Supplier Shipment — Batch Processing

Three programs communicate via CALL USING, MessagePort, and SQLite to process
incoming shipments, store them, and generate supplier reports.

- **Test**: [`src/test/java/org/cobol4j/SupplierShipmentTest.java`](src/test/java/org/cobol4j/SupplierShipmentTest.java)
- **COBOL sources**: [`SHIPINTK.cbl`](src/test/resources/SHIPINTK.cbl), [`SHIPQRY.cbl`](src/test/resources/SHIPQRY.cbl), [`RPTWRT.cbl`](src/test/resources/RPTWRT.cbl)
- **Design**: [DEMOPLAN.md](DEMOPLAN.md)

What you'll see: LINKAGE SECTION record exchange, embedded SQL with cursors,
sequential file report output, message queue notifications, money arithmetic
with Decimal, and level-88 condition-based validation.

### Supplier Shipment — CICS Transactions

The same business logic redeployed as CICS online transactions inside a
CicsRegion container.

- **Test**: [`src/test/java/org/cobol4j/cics/SupplierShipmentCicsTest.java`](src/test/java/org/cobol4j/cics/SupplierShipmentCicsTest.java)
- **Walkthrough**: [CICSDEMO.md](CICSDEMO.md) — annotated source with EXEC CICS equivalents

What you'll see: COMMAREA-based request/response, Temporary Storage queues,
LINK (call with return), transaction routing by 4-character IDs, and how batch
programs are restructured for online processing.

### CUSTORD — Transpiler End-to-End

A comprehensive COBOL program that exercises every supported language feature,
transpiled from COBOL source to Java, compiled at runtime, and executed with
output validation.

- **COBOL source**: [`src/test/resources/CUSTORD.cbl`](src/test/resources/CUSTORD.cbl)
- **Transpile test**: [`src/test/java/org/cobol4j/transpiler/CustordTranspileTest.java`](src/test/java/org/cobol4j/transpiler/CustordTranspileTest.java)
- **Runtime test**: [`src/test/java/org/cobol4j/FullFeatureTest.java`](src/test/java/org/cobol4j/FullFeatureTest.java)

What you'll see: The full pipeline from `.cbl` source to running Java program,
including REDEFINES, OCCURS tables, SEARCH, STRING/UNSTRING, INSPECT,
EVALUATE TRUE with condition names, COMPUTE expressions, ADD/MULTIPLY GIVING
ROUNDED with ON SIZE ERROR, and inter-program record exchange via CALL USING.

### PAYROLL — Simple Transpilation

A smaller COBOL program showing basic transpilation: record definitions,
arithmetic, and formatted output.

- **COBOL source**: [`src/test/resources/PAYROLL.cbl`](src/test/resources/PAYROLL.cbl)

## Feature-Specific Tests

| Feature | Test | What it covers |
|---------|------|----------------|
| Multi-argument arithmetic | [`MultiArgArithmeticTest`](src/test/java/org/cobol4j/transpiler/MultiArgArithmeticTest.java) | ADD/SUBTRACT with multiple sources and targets, GIVING to multiple fields, SIZE ERROR handlers, REDEFINES emission |
| Expressions & precedence | [`ComputeExprTest`](src/test/java/org/cobol4j/transpiler/ComputeExprTest.java), [`ExpressionCorrectnessTest`](src/test/java/org/cobol4j/transpiler/ExpressionCorrectnessTest.java) | COMPUTE with `+`, `-`, `*`, `/`, `**`, parentheses, unary minus |
| EVALUATE / WHEN | [`EvaluateTest`](src/test/java/org/cobol4j/transpiler/EvaluateTest.java) | EVALUATE TRUE, WHEN THRU ranges, fall-through, WHEN OTHER |
| Conditions (AND/OR/NOT) | [`ConditionTest`](src/test/java/org/cobol4j/transpiler/ConditionTest.java) | Compound conditions, operator precedence, negation |
| EXEC SQL | [`ExecSqlTest`](src/test/java/org/cobol4j/transpiler/ExecSqlTest.java) | Host variables, SELECT INTO, INSERT/UPDATE/DELETE, CURSOR, COMMIT/ROLLBACK |
| FILE SECTION | [`FileSectionTest`](src/test/java/org/cobol4j/transpiler/FileSectionTest.java) | FD entries, file-record binding, OPEN/READ/WRITE/CLOSE |
| CALL / LINKAGE | [`LinkageTest`](src/test/java/org/cobol4j/LinkageTest.java) | CALL USING with by-reference fields, nested calls, multiple paragraphs, alphanumeric linkage |
| CICS container | [`CicsRegionTest`](src/test/java/org/cobol4j/cics/CicsRegionTest.java) | Dispatch, COMMAREA, LINK, XCTL, Temporary Storage, hot deploy, task info |
| SQL across databases | [`CrossDatabaseTest`](src/test/java/org/cobol4j/CrossDatabaseTest.java) | Same operations on H2 and SQLite |
| Schema management | [`SchemaManagerTest`](src/test/java/org/cobol4j/schema/SchemaManagerTest.java) | Auto-migration, version tracking, RecordStore CRUD |
| Record layout | [`RecordTest`](src/test/java/org/cobol4j/RecordTest.java) | PIC types, COMP/COMP-3, groups, OCCURS, REDEFINES, initialize |
| Decimal arithmetic | [`DecimalTest`](src/test/java/org/cobol4j/DecimalTest.java) | Exact arithmetic, comparisons, caching, ValueTracker |
| Sign clause | [`SignClauseTest`](src/test/java/org/cobol4j/SignClauseTest.java) | SIGN IS LEADING/TRAILING/SEPARATE |
| String operations | [`StringOpsTest`](src/test/java/org/cobol4j/StringOpsTest.java) | STRING, UNSTRING, INSPECT TALLYING/REPLACING/CONVERTING |
| EBCDIC / mainframe | [`EbcdicRecordTest`](src/test/java/org/cobol4j/EbcdicRecordTest.java), [`InteropTest`](src/test/java/org/cobol4j/interop/InteropTest.java) | EBCDIC encoding, copybook import, mainframe file I/O |
| Messaging | [`MessagePortTest`](src/test/java/org/cobol4j/interop/MessagePortTest.java) | Fire-and-forget, at-least-once, exactly-once delivery |
| System calls | [`SystemCallTest`](src/test/java/org/cobol4j/interop/SystemCallTest.java) | POSIX open/read/write/close/stat mapped from COBOL CALL |
| Codecs | [`CodecTest`](src/test/java/org/cobol4j/codec/CodecTest.java) | XML and JSON serialization of Records |

## API Documentation

Generate the javadoc:

```bash
make javadoc
# or: mvn javadoc:javadoc
```

Browse at `docs/javadoc/apidocs/index.html`. Key entry points:

- [Record](docs/javadoc/apidocs/org/cobol4j/Record.html) — data layout with PIC clauses
- [Program](docs/javadoc/apidocs/org/cobol4j/Program.html) / [ProgramContext](docs/javadoc/apidocs/org/cobol4j/ProgramContext.html) — batch program execution
- [CicsRegion](docs/javadoc/apidocs/org/cobol4j/cics/CicsRegion.html) / [CicsContext](docs/javadoc/apidocs/org/cobol4j/cics/CicsContext.html) — CICS transaction container
- [Decimal](docs/javadoc/apidocs/org/cobol4j/Decimal.html) — money-safe arithmetic
- [SqlSession](docs/javadoc/apidocs/org/cobol4j/SqlSession.html) / [CobolSql](docs/javadoc/apidocs/org/cobol4j/CobolSql.html) — embedded SQL
- [Transpiler](docs/javadoc/apidocs/org/cobol4j/transpiler/Transpiler.html) — COBOL source to Java source

## Further Reading

- [DESIGN.md](DESIGN.md) — architecture philosophy and design decisions
- [PLATFORM.md](PLATFORM.md) — extensibility roadmap (interface extraction, protected state)
- [CICSIntg.md](CICSIntg.md) — CICS container architecture and hot-deploy
- [CICSDEMO.md](CICSDEMO.md) — annotated CICS demo walkthrough
- [DEMOPLAN.md](DEMOPLAN.md) — batch demo design and expected results
