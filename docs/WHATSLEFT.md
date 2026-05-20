# What's Left — Remaining Work and Known Gaps

This document tracks what's implemented, what's partially done, and what's missing
in cobol4j. It's intended for contributors deciding where to help.

**Current state: 499 passing tests.** The core runtime, transpiler, CICS container,
SQL integration, and mainframe interop are all functional. The items below are
gaps that real-world COBOL programs may need.

---

## Transpiler — Missing or Incomplete Verbs

### Not Yet Implemented (generates `COBOL4J_UNSUPPORTED` marker)

| Verb | Impact | Notes |
|------|--------|-------|
| **START** | High | Keyed file positioning — needed for indexed file I/O. Runtime `CobolFile` already has the `start()` method; the transpiler just needs to emit the call. |
| **ALTER** | Low | Modifies GO TO targets at runtime. Rarely used in modern COBOL. Refactor to IF/EVALUATE instead. |
| **CANCEL** | Low | Releases a called program from memory. In Java this is handled by the classloader. |

### Implemented as Synonyms

| Verb | Maps To | Notes |
|------|---------|-------|
| GOBACK | `ctx.stopRun()` | Returns from subprogram. Correct for called programs. |
| EXIT PROGRAM | `ctx.stopRun()` | Same — terminates the called program's execution. |

### Partially Implemented

| Feature | What Works | What's Missing |
|---------|-----------|----------------|
| **SET** | `SET condition TO TRUE`, `SET index UP/DOWN BY n` | `SET index TO literal` (e.g., `SET IDX TO 1`) |
| **PERFORM VARYING** | Single VARYING with FROM/BY/UNTIL | **AFTER** clause for nested varying (two-dimensional loops) |
| **INSPECT** | TALLYING (ALL, LEADING, CHARACTERS), REPLACING (ALL, LEADING, FIRST), CONVERTING, BEFORE/AFTER | Multiple TALLYING targets in one INSPECT; combined TALLYING+REPLACING |
| **STRING/UNSTRING** | Basic forms, DELIMITED BY, multiple INTO | OVERFLOW/NOT ON OVERFLOW handlers, POINTER clause in transpiler |
| **CALL** | USING BY REFERENCE/CONTENT/VALUE, ON EXCEPTION/NOT ON EXCEPTION | LENGTH OF parameter |
| **EVALUATE** | Single subject, WHEN THRU ranges, fall-through, WHEN OTHER | Multiple subjects (`EVALUATE A ALSO B`) |
| **XML/JSON** | GENERATE/PARSE with FROM/INTO | Namespace, prefix, encoding, array indicators — options are parsed but not emitted |

---

## Transpiler — Missing Data Division Features

| Feature | Impact | Notes |
|---------|--------|-------|
| **RENAMES (66-level)** | Medium | Record aliasing — creates an alternate name for a contiguous range of fields. Needs parser + emitter + Record API support. |
| **FILLER** | Low | Parsed (treated as a named field). Should skip emission or use anonymous placeholder. |
| **JUSTIFIED RIGHT** | Low | Right-aligns alphanumeric data on MOVE. Record.move() would need a justify option. |
| **BLANK WHEN ZERO** | Low | Displays spaces instead of zeros for numeric-edited fields. Affects display formatting only. |
| **SYNCHRONIZED** | None | Memory alignment hint. Java handles alignment automatically — no action needed. |

---

## Transpiler — Error Handling and Diagnostics

| Item | Status | Notes |
|------|--------|-------|
| Unsupported verbs | Done | Generates `COBOL4J_UNSUPPORTED_VERB;` — intentional compile error with COBOL source line and hint as comments |
| Unknown verbs | Done | Same marker with warning diagnostic |
| SIZE ERROR handlers | Done | Emits real `SizeErrorHandler.of()` with actual MOVE/SET statements inside the lambda |
| `emitStatementInline` | Partial | Handles MOVE, SET, DISPLAY in SIZE ERROR lambdas. Throws `TranspileException` for more complex statements — should eventually support IF, PERFORM, arithmetic |
| COBOL line numbers in output | Partial | Unsupported markers include line numbers. Generated Java doesn't carry line annotations throughout — adding `// COBOL line N` comments everywhere would help debugging |

---

## Runtime — Known Edge Cases

| Issue | Severity | Notes |
|-------|----------|-------|
| **REDEFINES size validation** | Medium | A redefining group whose children exceed the target field's byte size is not rejected at Record.build() time. Could cause silent corruption within the byte buffer. |
| **UNSTRING overflow** | Low | When source is exhausted before all INTO targets are filled, remaining targets are not space-filled per the COBOL standard. |
| **Search startIndex bounds** | Low | Linear search with startIndex >= occurs() silently completes without calling the atEnd handler. |
| **Division by zero** | Fixed | `Decimal.divide()` returns ZERO; `Arithmetic.DivideBuilder` triggers `onSizeError()`. |

---

## Runtime — Missing Features

| Feature | Impact | Notes |
|---------|--------|-------|
| **Report Writer** | High | REPORT SECTION with declarative report generation — control breaks, RD, PAGE HEADING/FOOTING, LINE, COLUMN. This is a substantial feature, essentially a report DSL built on top of Record and CobolFile. |
| **Screen Section** | Medium | Terminal UI with positioned ACCEPT/DISPLAY. Would need a terminal abstraction (JLine or similar). |
| **USE AFTER EXCEPTION** | Medium | Declaratives — automatic file I/O error handlers that fire on specific file status conditions. |
| **MERGE verb** | Low | Parser handles it; emitter generates a placeholder. Merges pre-sorted files — similar to SORT but input is already ordered. |
| **EXEC CICS in transpiler** | Low | The CICS runtime works fully through the Java API (`CicsRegion`, `CicsContext`). The transpiler handles `EXEC SQL` but not `EXEC CICS` — CICS programs are currently written directly in Java. |

---

## Japanese / NATIONAL Character Support (PIC N)

COBOL's NATIONAL data type (`PIC N`) is critical for Japanese enterprise systems.
Over 32,000 companies rely on COBOL with Japanese text processing — customer names,
addresses, product descriptions all stored as double-byte characters. Without proper
PIC N support, string operations produce mojibake (garbled text) by splitting
double-byte characters at byte boundaries.

**What's needed:**

| Feature | Impact | Notes |
|---------|--------|-------|
| **PIC N field type** | High | `Record.Builder.picN(name, count)` — allocates `count * 2` bytes for double-byte characters. The PIC clause governs whether a field is byte-oriented (PIC X) or character-oriented (PIC N). |
| **Shift-JIS encoding** | High | Japanese mainframe and Windows systems use Shift-JIS. The `BaseRecord` encoding model currently supports ASCII and EBCDIC (CP037/CP500/CP1047). Needs to extend to Shift-JIS and UTF-16. |
| **NATIONAL literals** | Medium | `N"こんにちは"` — double-byte string literals in COBOL source. The lexer needs to recognize the `N"..."` prefix and the parser needs to preserve the encoding. |
| **Character-aware string operations** | High | `move()`, `getString()`, `substring()` on PIC N fields must count characters, not bytes. `FIELD(1:5)` on PIC N means characters 1-5 (bytes 1-10). `Inspect` and `CobolString` need to check the field's PIC type and use character-wise operations for NATIONAL fields. |
| **Japanese identifiers** | Low | Variable names in Shift-JIS (e.g., `01 顧客名 PIC N(20).`). The lexer currently reads ASCII/UTF-8 identifiers. Multi-byte identifier support would require lexer changes. |
| **NATIONAL figurative constants** | Low | SPACE, ZERO, QUOTE in a NATIONAL context occupy double-byte width. |

**Architecture notes:**

The design is clean — no redesign needed:
- `Record` already carries an encoding parameter (currently `Ebcdic` or null for ASCII)
- Extending to a general `Charset` model that includes Shift-JIS and UTF-16 is straightforward
- The PIC N flag on the field definition drives character-vs-byte behavior throughout
- `BaseRecord.readFrom()` / `writeTo()` are encoding-agnostic (raw bytes) — the interpretation happens at the field level

**Why this matters:**

Japan has the largest COBOL market outside the United States. The Japanese OSS
Consortium maintains opensourcecobol4j specifically to serve this market, with
extensive Shift-JIS and PIC N extensions. For cobol4j to be viable for Japanese
enterprise migration, NATIONAL character support is essential. This is the single
largest feature gap for the Japanese audience.

---

## Build, Tooling, and Distribution

| Item | Status | Notes |
|------|--------|-------|
| Maven build | Done | `mvn test`, `mvn package`, `mvn install` |
| Makefile | Done | `make test`, `make package`, `make javadoc`, `make install`, `make sources` |
| Javadoc | Done | `make javadoc` generates to `docs/javadoc/apidocs/`. Has warnings on records (missing `@param`) but builds with `doclint=none`. |
| Fat JAR runner | Done | `mvn package` → self-contained JAR for `java -jar cobol4j.jar run source.cbl` |
| Source JAR | Done | `make sources` |
| Maven Central | Not done | pom.xml needs `<distributionManagement>`, GPG signing, Sonatype staging setup. |
| CI/CD | Not done | No GitHub Actions workflow. Should run `mvn test` on push/PR, publish javadoc to GitHub Pages. |

---

## Documentation

| Document | Status | Notes |
|----------|--------|-------|
| [README.md](README.md) | Current | Project overview, architecture, API examples |
| [EXAMPLES.md](EXAMPLES.md) | Current | Complete guide to demos, tests, and javadoc |
| [DESIGN.md](DESIGN.md) | Current | Architecture philosophy — why a runtime library instead of direct translation |
| [CICSDEMO.md](CICSDEMO.md) | Current | Annotated CICS demo walkthrough with javadoc links |
| [DEMOPLAN.md](DEMOPLAN.md) | Current | Batch supplier shipment demo design and expected results |
| [CICSIntg.md](CICSIntg.md) | Current | CICS container architecture and hot-deploy |
| [PLATFORM.md](PLATFORM.md) | Current | Extensibility roadmap — interface extraction, protected state, RecordFactory |
| Getting Started tutorial | Not written | Step-by-step for first-time users: clone, build, transpile a .cbl, run it |
| Contributing guide | Not written | Code style, test expectations, PR process, how the transpiler pipeline works |
| Javadoc quality | Needs work | `@param` missing on all record types; some HTML escaping issues in comment tags |

---

## Test Coverage Summary

| Area | Tests | Key test classes |
|------|-------|-----------------|
| Record/Field/Decimal | 86 | RecordTest, DecimalTest, PicTest, SignClauseTest, GroupOccursTest, EbcdicRecordTest, PackedDecimalTest |
| Program execution | 19 | ProgramTest |
| Transpiler | 127 | TranspilerTest, ComputeExprTest, ExpressionCorrectnessTest, ExpressionGapsTest, ConditionTest, EvaluateTest, GapFixTest, MultiArgArithmeticTest, FileSectionTest, ExecSqlTest, InvokeTest, PreprocessorTest, NextItemsTest |
| CUSTORD end-to-end | 18 | CustordTranspileTest — transpile, compile, run, validate output + record exchange |
| SQL/Database | 34 | CobolSqlTest, CrossDatabaseTest |
| Schema management | 11 | SchemaManagerTest |
| CICS | 14 | CicsRegionTest, SupplierShipmentCicsTest |
| Interop | 44 | InteropTest, SystemCallTest, MessagePortTest |
| String operations | 8 | StringOpsTest |
| Codecs | 10 | CodecTest |
| Linkage/CALL | 10 | LinkageTest |
| Full feature demo | 1 | FullFeatureTest — comprehensive runtime API exercise |
| Supplier shipment demo | 2 | SupplierShipmentTest (batch), SupplierShipmentCicsTest (CICS) |
| Other | 46 | NewFeaturesTest, RecordSchemaTest, CobolConfigTest |
| **Total** | **499** | |

---

## Priority Recommendations for Contributors

1. **High value, moderate effort**: PERFORM VARYING AFTER — unlocks nested loops common in matrix/table processing. Needs parser change (recognize AFTER keyword after UNTIL) and ProgramContext support for nested varying.

2. **High value, low effort**: START verb in transpiler — the runtime already supports it via `CobolFile.start()`. Just needs parser recognition and emitter output.

3. **High value, high effort**: Report Writer — REPORT SECTION is a major COBOL feature used in many enterprise programs. This would be a significant contribution.

4. **Good first contribution**: Fix javadoc warnings — add `@param` tags to record types in Statement.java, Expr.java, CobolProgram.java, Token.java. Straightforward, teaches the codebase structure.

5. **Good first contribution**: EVALUATE ALSO — multiple subjects in EVALUATE. Parser needs to collect a list of subjects; emitter generates compound conditions.

6. **Infrastructure**: GitHub Actions CI — create `.github/workflows/ci.yml` that runs `mvn test` on push and PR. 
