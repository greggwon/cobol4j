# Unimplemented Verb Details

There are several places where the correct implementation of functionality depends
on the deployment environment — database configuration, file system layout, report
output format, or mainframe-specific behavior that has no direct Java equivalent.

Rather than generate compile errors or silently drop statements, the transpiler
handles every standard COBOL verb in one of four ways:

1. **Fully implemented** — generates real, working Java code
2. **No-op with warning** — compiles and runs, but logs a warning explaining
   what was skipped and why
3. **Service stub** — compiles and runs, logs a warning, and describes the
   service interface a contributor would implement to make it real
4. **Unsupported marker** — only for truly unknown verbs (not part of any
   COBOL standard); generates an intentional compile error so you see exactly
   where the gap is

---

## Fully Implemented (real code generation)

These verbs are transpiled to working cobol4j API calls. They execute correctly
at runtime without any additional configuration.

| Verb | What it emits | COBOL usage |
|------|---------------|-------------|
| **START** | `file.start(keyName, keyValue, condition)` | Position an indexed file for sequential reading from a key value. See [CobolFile](docs/javadoc/apidocs/org/cobol4j/CobolFile.html). |
| **SET idx TO n** | `record.move("field", value)` | Set an index or field to a specific value. Also supports `SET idx UP BY n` and `SET idx DOWN BY n`. |
| **RELEASE** | `sortInput.release()` | Write a record into the sort input stream during an INPUT PROCEDURE. See [CobolSort](docs/javadoc/apidocs/org/cobol4j/CobolSort.html). |
| **RETURN** | `if (sortOutput.returnRecord()) { ... } else { atEnd }` | Read a sorted record during an OUTPUT PROCEDURE. Handles AT END / NOT AT END. |
| **GOBACK** | `ctx.stopRun()` | Return from a called subprogram. Equivalent to EXIT PROGRAM or STOP RUN for the called program's scope. |
| **EXIT PROGRAM** | `ctx.stopRun()` | Same as GOBACK — terminates the called program and returns to the caller. |

---

## No-op with Runtime Warning

These verbs are recognized by the parser and produce compilable Java code.
At runtime they log a `WARNING` through `java.util.logging` explaining what
happened and why no action was taken. The program continues executing.

This is the correct behavior for verbs whose COBOL semantics don't apply in
a Java environment, or where the verb modifies behavior that Java handles
automatically.

| Verb | What it emits | Why it's a no-op |
|------|---------------|------------------|
| **CANCEL** | `LOG.warning("CANCEL program — no-op in Java")` | In COBOL, CANCEL releases a called program's memory and resets its state. In Java, the garbage collector and classloader handle this automatically. If you need explicit state reset, implement it in your program's initialization logic. |
| **ALTER** | `LOG.warning("ALTER para TO target — not supported, refactor to IF/EVALUATE")` | ALTER dynamically changes a GO TO target at runtime — a practice universally discouraged in modern COBOL. The recommended migration path is to replace `ALTER`/`GO TO` pairs with `IF` or `EVALUATE` statements. The warning includes the source paragraph names so you can identify what to refactor. |
| **ENTER** | `LOG.warning("ENTER language — dialect-specific, no-op")` | ENTER switches to embedded assembler or another language section. This is dialect-specific (IBM, MicroFocus) and has no meaningful Java equivalent. The warning logs the language name for reference. |

---

## Service Stubs with Runtime Warning

These verbs represent COBOL subsystems that require a substantial implementation
to function correctly. The transpiler generates compilable code that logs a
warning describing what service would need to exist.

**For contributors**: Each of these represents an opportunity to implement a
service interface. The pattern would be:

1. Define a Java interface (e.g., `ReportService`) with methods matching the
   verb semantics
2. Provide a default implementation that logs warnings (already done in the
   generated code)
3. Allow users to plug in a real implementation via constructor injection or
   `ServiceLoader`

| Verb | What it emits | What a real implementation would do |
|------|---------------|-------------------------------------|
| **GENERATE** | `LOG.warning("GENERATE report — Report Writer not yet implemented")` | Format and output a report line according to REPORT SECTION definitions (RD, control breaks, LINE/COLUMN positioning, PAGE HEADING/FOOTING). This is the core of COBOL's declarative report generation. A `ReportService` would accept Record data and produce formatted output via CobolFile or a PrintWriter. |
| **TERMINATE** | `LOG.warning("TERMINATE report — Report Writer not yet implemented")` | Finalize a report — write control footings, page footers, and flush the output. Called once at the end of report processing. |
| **SUPPRESS** | `LOG.warning("SUPPRESS PRINTING — Report Writer not yet implemented")` | Prevent output of the current report line (typically used during control breaks to suppress redundant headings). Would set a flag on the `ReportService` that the next GENERATE checks. |
| **INITIATE** | `LOG.warning("INITIATE report — Report Writer not yet implemented")` | Initialize a report — open the output file, write page headers, set up control break tracking. Called once before the first GENERATE. |
| **USE** | `LOG.warning("USE AFTER EXCEPTION ON file — Declaratives not yet implemented")` | Register an automatic error handler that fires when a file I/O operation encounters a specific status condition. In the cobol4j runtime, this could be implemented via the existing `CobolFile.onError(Consumer)` callback — the transpiler would need to wire the COBOL declarative body into that callback. |

---

## Still Unsupported (compile error marker)

Only verbs that are **not part of any standard COBOL dialect** will produce an
intentional compile error:

```java
// COBOL line 42: FROBULATE WS-DATA USING SOME-THING
// Unknown COBOL verb. Check spelling or dialect-specific extensions
COBOL4J_UNSUPPORTED_FROBULATE; // FROBULATE WS-DATA USING SOME-THING
```

This ensures:
- The generated Java **will not compile** — the user is forced to address it
- The **COBOL source line number** is shown as a comment
- The **original COBOL text** is preserved so you can see exactly what was written
- The rest of the program generates normally — only the specific unknown statement
  is marked

**Every standard COBOL verb is now recognized.** You will only see
`COBOL4J_UNSUPPORTED` markers for misspelled verbs, vendor-specific extensions
(e.g., Fujitsu, Bull), or experimental language features.

---

## How to Check What Your Program Uses

Transpile your COBOL source and grep for warnings:

```bash
java -jar cobol4j.jar transpile MYPROG.cbl -o output/
grep -n "LOG.warning" output/Myprog.java     # find runtime stubs
grep -n "COBOL4J_UNSUPPORTED" output/Myprog.java  # find hard gaps
```

If there are no matches, your program is fully supported.

---

## See Also

- [WHATSLEFT.md](WHATSLEFT.md) — complete remaining work inventory including
  partially implemented features, runtime edge cases, and documentation gaps
- [EXAMPLES.md](EXAMPLES.md) — runnable demonstrations of every working feature
- [CICSDEMO.md](CICSDEMO.md) — CICS transaction deployment example
