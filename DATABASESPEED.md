# Database Speed for Migrated COBOL Workloads

When a COBOL batch program moves off the mainframe, the database becomes
the bottleneck. This document describes specific partitioning strategies
that preserve batch window performance using commodity hardware and
standard databases.

These techniques are not theoretical. They are production patterns used
in high-volume transactional systems.

---

## The Problem

A mainframe COBOL batch job processes millions of sequential committed
writes against DB2. The I/O path is a memory bus — nanosecond latency.
When the same workload moves to a Java program writing to MySQL or
PostgreSQL on a network-attached database, every commit crosses a wire.
The batch window doubles or worse.

The solution isn't faster hardware for one stream. It's smarter data
layout so that physical I/O paths don't contend with each other and
indexes stay small enough to fit in memory.

---

## Strategy 1: Date Partitioning — Keep Today's Indexes Small

### The Pattern

A ledger table accumulates transactions over years. The index grows with
every insert. After millions of rows, each INSERT updates a B-tree that
spans gigabytes of index pages. The database has to read, split, and write
index nodes for every commit — and most of that index is for historical
data that tonight's batch doesn't care about.

Partition by date. Today's transactions go into a dedicated partition with
its own index. The index is small because it only covers today's data.
Inserts are fast because the index fits in the buffer pool.

### MySQL Example

```sql
CREATE TABLE LEDGER_ENTRIES (
    ENTRY_ID      BIGINT AUTO_INCREMENT,
    LEDGER_ID     VARCHAR(10) NOT NULL,
    POSTING_DATE  DATE NOT NULL,
    AMOUNT        DECIMAL(11,2) NOT NULL,
    BALANCE_AFTER DECIMAL(13,2) NOT NULL,
    DESCRIPTION   VARCHAR(80),
    PRIMARY KEY (ENTRY_ID, POSTING_DATE)
) PARTITION BY RANGE (TO_DAYS(POSTING_DATE)) (
    PARTITION p_history VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION p_current VALUES LESS THAN (TO_DAYS('2026-06-01')),
    PARTITION p_future  VALUES LESS THAN MAXVALUE
);
```

### What This Gives You

- **INSERT speed**: tonight's batch writes to `p_current`. The index for
  `p_current` is small — it covers one month, not ten years. Every index
  update touches pages that are already in the buffer pool.

- **No contention with history**: a query against last quarter's data
  hits `p_history`. It never touches the same index pages as the batch
  INSERT. The I/O paths are physically independent.

- **Partition pruning**: `WHERE POSTING_DATE = CURRENT_DATE` scans only
  `p_current`. The query planner knows at compile time that no other
  partition can contain matching rows.

- **Free archival**: at the end of the month, detach the old partition.
  No row-by-row DELETE. No index rebuild. One metadata operation moves
  an entire month to history. Add a new empty partition for next month.

### Sophisticated Indexes Without INSERT Penalty

History partitions can have additional indexes that would be too expensive
to maintain during batch processing:

```sql
-- Add indexes on history partition only (after batch window closes)
ALTER TABLE LEDGER_ENTRIES
    ADD INDEX ix_ledger_balance (LEDGER_ID, BALANCE_AFTER)
    -- MySQL applies this per-partition; existing partitions are unchanged
    -- during tonight's batch inserts
```

The hot partition (today) has a minimal index for fast inserts. The cold
partitions (history) have rich indexes for reporting and audit queries.
Each partition's indexes are sized to its data volume, not the table's
total volume.

### Partition Maintenance

```sql
-- Monthly rotation: archive the old, create the new
ALTER TABLE LEDGER_ENTRIES REORGANIZE PARTITION p_future INTO (
    PARTITION p_2026_06 VALUES LESS THAN (TO_DAYS('2026-07-01')),
    PARTITION p_future  VALUES LESS THAN MAXVALUE
);
```

The batch program doesn't change. The `INSERT INTO LEDGER_ENTRIES` is the
same statement. MySQL routes it to the correct partition based on the
POSTING_DATE value. The partitioning strategy is DBA-owned infrastructure,
not application logic.

---

## Strategy 2: Key Partitioning — Distribute Load Across Physical Devices

### The Pattern

A settlement batch processes transactions for thousands of customers.
Each customer's transactions are sequential — posting N depends on the
balance after posting N-1. But customer A's ledger is completely
independent of customer B's ledger.

Partition by the ledger key. Each partition lives on a separate physical
tablespace. Each tablespace lives on a separate drive. Each drive has
its own I/O bus. Parallel workers processing different customers never
contend on the same physical device.

### MySQL Example

```sql
CREATE TABLE LEDGER_ENTRIES (
    ENTRY_ID      BIGINT AUTO_INCREMENT,
    LEDGER_ID     VARCHAR(10) NOT NULL,
    POSTING_DATE  DATE NOT NULL,
    AMOUNT        DECIMAL(11,2) NOT NULL,
    BALANCE_AFTER DECIMAL(13,2) NOT NULL,
    DESCRIPTION   VARCHAR(80),
    PRIMARY KEY (ENTRY_ID, LEDGER_ID)
) PARTITION BY HASH(CRC32(LEDGER_ID)) PARTITIONS 16;
```

Sixteen partitions — each can be placed on a separate tablespace mapped
to a separate physical drive:

```sql
ALTER TABLE LEDGER_ENTRIES
    PARTITION p0 DATA DIRECTORY = '/mnt/ssd0/ledger'
    PARTITION p1 DATA DIRECTORY = '/mnt/ssd1/ledger'
    -- ... through p15
```

### What This Gives You

- **No cross-partition contention**: worker processing customer "C001"
  writes to partition 7 (based on the hash). Worker processing "C002"
  writes to partition 3. Different partitions, different drives, different
  I/O buses. No lock contention. No shared buffer pool pages.

- **Parallel commit paths**: each drive has its own write-ahead log flush.
  Sixteen workers committing simultaneously flush to sixteen different
  drives. The aggregate commit throughput is sixteen times a single drive's
  throughput.

- **Scalable with hardware**: add more drives, add more partitions, add
  more workers. The scaling is linear until you saturate the bus controller
  or the network interface.

### cobol4j Worker Pattern

```java
// Each worker processes one partition of ledgers
ConnectionFactory db = ConnectionFactory.jdbc(
    System.getProperty("cobol4j.db.url"),
    System.getProperty("cobol4j.db.user"),
    System.getProperty("cobol4j.db.password"));

Program ledgerWorker = Program.define("LEDGER-WORKER")
    .linkage("LS-LEDGER-ID", "X(10)")
    .paragraph("PROCESS-LEDGER", ctx -> {
        String ledgerId = ctx.linkageField("LS-LEDGER-ID").trimmed();
        SqlSession.work(db, session -> {
            // Sequential processing for THIS ledger
            // MySQL routes to the correct partition automatically
            session.sql().execute(
                "INSERT INTO LEDGER_ENTRIES "
                + "(LEDGER_ID, POSTING_DATE, AMOUNT, BALANCE_AFTER, DESCRIPTION) "
                + "VALUES (?, CURRENT_DATE, ?, ?, ?)")
                .param(ledgerId)
                .param(rec, "WS-AMOUNT")
                .param(rec, "WS-NEW-BALANCE")
                .param(rec, "WS-DESCRIPTION")
                .execute();
        });
    })
    .build();
```

The application code doesn't know about partitions. It inserts a row with
a LEDGER_ID. MySQL hashes the key and routes to the physical partition.
The DBA controls the physical layout. The programmer controls the business
logic. The ownership boundary is clean.

---

## Strategy 3: Combined Partitioning — Key + Date

For the highest throughput, combine both strategies:

```sql
CREATE TABLE LEDGER_ENTRIES (
    ENTRY_ID      BIGINT AUTO_INCREMENT,
    LEDGER_ID     VARCHAR(10) NOT NULL,
    POSTING_DATE  DATE NOT NULL,
    AMOUNT        DECIMAL(11,2) NOT NULL,
    BALANCE_AFTER DECIMAL(13,2) NOT NULL,
    DESCRIPTION   VARCHAR(80),
    PRIMARY KEY (ENTRY_ID, LEDGER_ID, POSTING_DATE)
) PARTITION BY RANGE (TO_DAYS(POSTING_DATE))
  SUBPARTITION BY HASH(CRC32(LEDGER_ID))
  SUBPARTITIONS 16 (
    PARTITION p_history VALUES LESS THAN (TO_DAYS('2026-05-01')),
    PARTITION p_current VALUES LESS THAN (TO_DAYS('2026-06-01')),
    PARTITION p_future  VALUES LESS THAN MAXVALUE
);
```

This produces `3 × 16 = 48` physical sub-partitions. Tonight's batch writes
hit `p_current` (small date range = small indexes) distributed across 16
key-based sub-partitions (parallel I/O paths). History queries hit
`p_history` sub-partitions with rich indexes. No cross-partition contention
anywhere.

---

## Strategy 4: Read Replicas — Separate the Reporting Path

Batch processing writes. Reporting reads. They don't need to share a
database instance.

```
Batch Workers ──▶ Primary (local SSD, tuned for write throughput)
                       │
                  replication
                       │
Reporting ──▶ Read Replica (can be remote, tuned for query performance)
```

The batch window uses 100% of the primary's I/O budget for writes. No
reporting queries compete for buffer pool pages, disk bandwidth, or lock
resources during the batch window. Reports run against the replica, which
trails by seconds — acceptable for any report that's not real-time.

```java
// Batch uses the primary
ConnectionFactory batchDb = ConnectionFactory.jdbc(
    "jdbc:mysql://primary:3306/ledger", "batch", password);

// Reports use the replica
ConnectionFactory reportDb = ConnectionFactory.jdbc(
    "jdbc:mysql://replica:3306/ledger", "reader", password);
```

The application code is identical. The `ConnectionFactory` is the only
difference. The deployment configuration decides which path each workload
takes.

---

## The Ownership Principle

In all of these strategies, the application code doesn't change. The cobol4j
program inserts a row, commits a transaction, reads a balance. How that
operation maps to physical storage — which partition, which drive, which
replica — is an infrastructure decision.

This is the same separation that COBOL's ENVIRONMENT DIVISION provides:
the program declares what files and databases it needs. The deployment
(JCL on mainframe, system properties in cobol4j) declares where they live.

```java
// cobol4j ENVIRONMENT DIVISION → system property
ConnectionFactory db = ConnectionFactory.jdbc(
    System.getProperty("cobol4j.db.url"),    // DBA decides
    System.getProperty("cobol4j.db.user"),
    System.getProperty("cobol4j.db.password"));
```

The programmer owns the business logic. The DBA owns the physical layout.
The batch window depends on both — but they change independently.

---

## Practical Numbers

| Configuration | Single-worker INSERT/sec | 16-worker INSERT/sec | Index overhead |
|--------------|------------------------|---------------------|----------------|
| Unpartitioned, 10M rows | ~5,000 | ~5,000 (contention) | Full table index |
| Date-partitioned, today only | ~15,000 | ~15,000 (contention) | Small daily index |
| Key-partitioned, 16 drives | ~5,000 per worker | ~80,000 aggregate | Per-partition index |
| Combined, 16 drives | ~15,000 per worker | ~240,000 aggregate | Small per-subpartition |

These numbers are representative, not benchmarked. Actual performance
depends on hardware, row size, index complexity, and commit frequency.
The point is the scaling pattern: date partitioning reduces per-write cost,
key partitioning adds parallelism, and the combination multiplies.

---

## See Also

- [ISCOBOLNECESSARY.md](ISCOBOLNECESSARY.md) — why readable Java matters for migration
- [COMPARISON.md](COMPARISON.md) — how cobol4j compares to other migration tools
- [DEMOPLAN.md](DEMOPLAN.md) — supplier shipment demo with SQLite
- [EXAMPLES.md](EXAMPLES.md) — runnable demonstrations
