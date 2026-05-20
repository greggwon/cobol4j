# CICS Integration — Transaction Processing Container

cobol4j includes a lightweight CICS-like transaction processing container that
provides the same programming model as IBM CICS without requiring a mainframe or
CICS Transaction Server.

## What CICS Is

CICS (Customer Information Control System) is a transaction processing monitor that
IBM has shipped since 1968. It's essentially the world's first application server:

- Programs are **deployed** into it (like deploying a WAR to Tomcat)
- Transactions are **routed** to programs by ID (like URL mapping)
- Resources (files, databases, queues) are **shared** across all programs
- Transactions are **atomic** — all-or-nothing via SYNCPOINT/ROLLBACK
- Programs can be **hot-swapped** without restarting the container

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  CicsRegion (the running container)                     │
│                                                         │
│  Transaction Routes:                                    │
│    "CINQ" → CUSTINQ program                             │
│    "CUPD" → CUSTUPD program                             │
│    "OENT" → ORDRENT program                             │
│                                                         │
│  Shared Resources:                                      │
│    CUSTFILE → CobolFile (indexed)                        │
│    DB01     → ConnectionFactory (pooled)                │
│                                                         │
│  Temporary Storage:                                     │
│    Named queues that persist across transactions        │
│                                                         │
│  Task Dispatch:                                         │
│    Request arrives → route → create task → run program  │
└─────────────────────────────────────────────────────────┘
```

## Setting Up a Region

```java
CicsRegion region = CicsRegion.create("PRODRGN")
    .file("CUSTFILE", custFile)         // shared file resource
    .database("DB01", connFactory)      // shared DB connection pool
    .start();
```

## Installing Programs

### Inline (lambda)

```java
region.install("CUSTINQ", ctx -> {
    ctx.receive(commarea);
    String custId = commarea.getString("CUST-ID").trim();
    
    if (ctx.readFile("CUSTFILE", custRec, custId)) {
        commarea.move("CUST-NAME", custRec.getString("CUST-NAME"));
        commarea.move("CUST-BAL", custRec.getDecimal("CUST-BAL"));
        commarea.move("RESP-CODE", "OK");
    } else {
        commarea.move("RESP-CODE", "NF");
    }
    
    ctx.send(commarea);
    ctx.returnTransaction();
});

region.transaction("CINQ", "CUSTINQ");
```

### From a JAR (dynamic loading)

```java
// Load a specific class
CicsProgram prog = ProgramLoader.fromJar(
    Path.of("/deploy/banking.jar"), "com.bank.CustInquiry");
region.install("CUSTINQ", prog);

// Or discover all programs in a JAR via ServiceLoader
ProgramLoader.installAll(region, Path.of("/deploy/banking.jar"));
```

### Self-Describing Programs

Programs can declare their own name and routing by implementing `NamedCicsProgram`:

```java
public class CustInquiry implements NamedCicsProgram {
    @Override public String programName()    { return "CUSTINQ"; }
    @Override public String transactionId()  { return "CINQ"; }
    @Override public String description()    { return "Customer inquiry by ID"; }

    @Override
    public void execute(CicsContext ctx) {
        // ... transaction logic ...
    }
}
```

With `META-INF/services/org.cobol4j.cics.CicsProgram`:
```
com.bank.CustInquiry
com.bank.CustUpdate
com.bank.OrderEntry
```

Then `ProgramLoader.installAll()` auto-installs all programs AND wires their
transaction routes — zero configuration needed in the region.

### Hot Deploy (directory watching)

```java
// Watch a directory — new/modified JARs are auto-loaded
Runnable stopWatcher = ProgramLoader.watch(region, Path.of("/deploy"));

// Drop banking-v2.jar into /deploy → automatically replaces the old version
// In-flight tasks finish with old code; new dispatches get new code

// Stop watching on shutdown
stopWatcher.run();
```

## Dispatching Transactions

```java
// From external input (like receiving an HTTP request or MQ message)
commarea.move("CUST-ID", "C001");
region.dispatch("CINQ", commarea);
// commarea now has the response

// Or with raw bytes
byte[] response = region.dispatch("CINQ", requestBytes);
```

## CicsContext — The Programming Model

Every EXEC CICS command in COBOL maps to a method on `CicsContext`:

### Communication (COMMAREA)

```java
ctx.receive(commarea);              // EXEC CICS RECEIVE INTO(COMMAREA)
ctx.send(commarea);                 // EXEC CICS SEND FROM(COMMAREA)
ctx.returnTransaction();            // EXEC CICS RETURN
ctx.returnTransaction("NEXT");      // EXEC CICS RETURN TRANSID('NEXT')
```

### File I/O (through managed resources)

```java
ctx.readFile("CUSTFILE", rec, key);    // EXEC CICS READ FILE('CUSTFILE') RIDFLD(KEY)
ctx.writeFile("CUSTFILE", rec);        // EXEC CICS WRITE FILE('CUSTFILE')
ctx.rewriteFile("CUSTFILE", rec);      // EXEC CICS REWRITE FILE('CUSTFILE')
ctx.deleteFile("CUSTFILE", key);       // EXEC CICS DELETE FILE('CUSTFILE') RIDFLD(KEY)
```

### Temporary Storage (session/scratch data)

```java
int item = ctx.writeTS("MYQUEUE", rec);    // EXEC CICS WRITEQ TS QUEUE('MYQUEUE')
ctx.readTS("MYQUEUE", rec, 1);             // EXEC CICS READQ TS QUEUE('MYQUEUE') ITEM(1)
ctx.readTSNext("MYQUEUE", rec);            // EXEC CICS READQ TS QUEUE('MYQUEUE') NEXT
ctx.deleteTS("MYQUEUE");                   // EXEC CICS DELETEQ TS QUEUE('MYQUEUE')
int depth = ctx.tsQueueDepth("MYQUEUE");   // EXEC CICS INQUIRE QUEUE
```

TS queues persist across transactions within the same region — useful for
multi-step conversations, scratch data, and session state.

### Program Control

```java
ctx.link("SUBPROG", linkCommarea);     // EXEC CICS LINK PROGRAM('SUBPROG')
// ↑ calls SUBPROG and returns here when it finishes

ctx.xctl("NEXTPROG", commarea);        // EXEC CICS XCTL PROGRAM('NEXTPROG')
// ↑ transfers control — this program does NOT resume
```

### Transaction Control

```java
ctx.syncpoint();    // EXEC CICS SYNCPOINT — commit all changes
ctx.rollback();     // EXEC CICS SYNCPOINT ROLLBACK — undo all changes
```

### Response Checking

```java
if (ctx.readFile("CUSTFILE", rec, key)) {
    // found
} else if (ctx.isNotFound()) {
    // NOTFND condition
}

int resp = ctx.response();  // EIBRESP value
```

### Task Information

```java
ctx.transactionId();   // "CINQ" — the transaction that started this task
ctx.taskNumber();      // unique incrementing task number
ctx.programName();     // "CUSTINQ" — current program name
```

## LINK vs XCTL

- **LINK** = subroutine call. The linked program runs, finishes, and control
  returns to the caller. Think `method()` call.
  
- **XCTL** = transfer control. The current program is abandoned and the target
  program takes over. Think `goto` to a different handler. The target's RETURN
  goes back to wherever the original transaction was dispatched from.

## Mapping to COBOL EXEC CICS Commands

| COBOL | cobol4j |
|---|---|
| `EXEC CICS RECEIVE INTO(CA) END-EXEC` | `ctx.receive(ca)` |
| `EXEC CICS SEND FROM(CA) END-EXEC` | `ctx.send(ca)` |
| `EXEC CICS RETURN END-EXEC` | `ctx.returnTransaction()` |
| `EXEC CICS RETURN TRANSID('NEXT') END-EXEC` | `ctx.returnTransaction("NEXT")` |
| `EXEC CICS READ FILE('F') INTO(R) RIDFLD(K) END-EXEC` | `ctx.readFile("F", r, k)` |
| `EXEC CICS WRITE FILE('F') FROM(R) END-EXEC` | `ctx.writeFile("F", r)` |
| `EXEC CICS REWRITE FILE('F') FROM(R) END-EXEC` | `ctx.rewriteFile("F", r)` |
| `EXEC CICS DELETE FILE('F') RIDFLD(K) END-EXEC` | `ctx.deleteFile("F", k)` |
| `EXEC CICS WRITEQ TS QUEUE('Q') FROM(R) END-EXEC` | `ctx.writeTS("Q", r)` |
| `EXEC CICS READQ TS QUEUE('Q') INTO(R) ITEM(N) END-EXEC` | `ctx.readTS("Q", r, n)` |
| `EXEC CICS DELETEQ TS QUEUE('Q') END-EXEC` | `ctx.deleteTS("Q")` |
| `EXEC CICS LINK PROGRAM('P') COMMAREA(C) END-EXEC` | `ctx.link("P", c)` |
| `EXEC CICS XCTL PROGRAM('P') COMMAREA(C) END-EXEC` | `ctx.xctl("P", c)` |
| `EXEC CICS SYNCPOINT END-EXEC` | `ctx.syncpoint()` |
| `EXEC CICS SYNCPOINT ROLLBACK END-EXEC` | `ctx.rollback()` |

## Integration Patterns

### As a REST Backend

Wire transaction dispatch to HTTP endpoints:

```java
// With any HTTP framework (Javalin, Spark, etc.)
app.post("/api/customer/:id", req -> {
    commarea.move("CUST-ID", req.pathParam("id"));
    region.dispatch("CINQ", commarea);
    return CodecRegistry.instance().toJson(commarea);
});
```

### With Message Queues

Receive COMMAREAs from MQ, dispatch as transactions:

```java
messagePort.listen(commareaRec, rec -> {
    String transId = rec.getString("TRANS-ID").trim();
    region.dispatch(transId, rec);
    // Response is in rec — send it back
    replyPort.send(rec);
});
```

### Testing

The in-process container makes testing trivial — no external CICS server needed:

```java
@Test
void customerInquiryReturnsData() {
    region.dispatch("CINQ", commarea);
    assertEquals("OK", commarea.getString("RESP-CODE").trim());
    assertEquals("ALICE SMITH", commarea.getString("CUST-NAME").trim());
}
```

## Not Yet Implemented

- BMS Maps (SEND MAP / RECEIVE MAP) — screen-based UI
- Transient Data Queues (TD) with trigger levels
- Two-phase commit across file + DB
- HANDLE CONDITION (automatic condition routing)
- START/RETRIEVE (async task initiation)
- ENQ/DEQ (resource locking)
