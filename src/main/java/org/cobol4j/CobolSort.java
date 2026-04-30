package org.cobol4j;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * COBOL SORT with INPUT PROCEDURE and OUTPUT PROCEDURE.
 * <p>
 * SORT collects records, sorts them by one or more keys, and delivers
 * them in order. It supports three patterns:
 * <ul>
 *   <li>INPUT PROCEDURE feeds records via {@link SortInput#release}</li>
 *   <li>OUTPUT PROCEDURE retrieves sorted records via {@link SortOutput#returnRecord}</li>
 *   <li>In-memory table sort for OCCURS arrays</li>
 * </ul>
 * <pre>{@code
 * // Full SORT with input and output procedures
 * CobolSort.on(sortRecord)
 *     .ascending("SORT-KEY-1")
 *     .descending("SORT-KEY-2")
 *     .inputProcedure(input -> {
 *         // Read from file, RELEASE each record to the sort
 *         while (file.read(inputRec)) {
 *             sortRecord.moveCorresponding(inputRec);
 *             input.release();
 *         }
 *     })
 *     .outputProcedure(output -> {
 *         // RETURN sorted records one at a time
 *         while (output.returnRecord()) {
 *             outputRec.moveCorresponding(sortRecord);
 *             outFile.write(outputRec.buffer());
 *         }
 *     })
 *     .execute();
 *
 * // Simple list sort (for in-memory data)
 * List<byte[]> sorted = CobolSort.on(recordDef)
 *     .ascending("KEY-FIELD")
 *     .sort(unsortedRecords);
 * }</pre>
 */
public final class CobolSort {

    private final Record sortRecord;
    private final List<SortKey> keys = new ArrayList<>();
    private Consumer<SortInput> inputProcedure;
    private Consumer<SortOutput> outputProcedure;

    private CobolSort(Record sortRecord) {
        this.sortRecord = sortRecord;
    }

    /** Begin a SORT using the given record as the sort work area. */
    public static CobolSort on(Record sortRecord) {
        return new CobolSort(sortRecord);
    }

    /** Add an ascending sort key. */
    public CobolSort ascending(String fieldName) {
        keys.add(new SortKey(fieldName, true));
        return this;
    }

    /** Add a descending sort key. */
    public CobolSort descending(String fieldName) {
        keys.add(new SortKey(fieldName, false));
        return this;
    }

    /** Define the INPUT PROCEDURE (feeds records to the sort). */
    public CobolSort inputProcedure(Consumer<SortInput> procedure) {
        this.inputProcedure = procedure;
        return this;
    }

    /** Define the OUTPUT PROCEDURE (consumes sorted records). */
    public CobolSort outputProcedure(Consumer<SortOutput> procedure) {
        this.outputProcedure = procedure;
        return this;
    }

    /**
     * Execute the SORT: run input procedure, sort, run output procedure.
     */
    public void execute() {
        // Phase 1: Collect records from input procedure
        SortInput input = new SortInput(sortRecord);
        if (inputProcedure != null) {
            inputProcedure.accept(input);
        }

        // Phase 2: Sort the collected records
        Comparator<byte[]> comparator = buildComparator();
        input.records.sort(comparator);

        // Phase 3: Deliver sorted records to output procedure
        if (outputProcedure != null) {
            SortOutput output = new SortOutput(sortRecord, input.records);
            outputProcedure.accept(output);
        }
    }

    /**
     * Sort a list of record byte arrays and return the sorted list.
     * Simpler alternative when you already have the records in memory.
     */
    public List<byte[]> sort(List<byte[]> records) {
        Comparator<byte[]> comparator = buildComparator();
        List<byte[]> sorted = new ArrayList<>(records);
        sorted.sort(comparator);
        return sorted;
    }

    private Comparator<byte[]> buildComparator() {
        Comparator<byte[]> comparator = (a, b) -> 0;
        for (SortKey key : keys) {
            FieldDef fd = sortRecord.fieldDef(key.fieldName);
            Comparator<byte[]> keyComp = (recA, recB) -> {
                sortRecord.loadFrom(recA);
                String valA = fd.isNumeric()
                    ? sortRecord.getDecimal(key.fieldName).toPlainString()
                    : sortRecord.getString(key.fieldName);
                sortRecord.loadFrom(recB);
                String valB = fd.isNumeric()
                    ? sortRecord.getDecimal(key.fieldName).toPlainString()
                    : sortRecord.getString(key.fieldName);

                int cmp;
                if (fd.isNumeric()) {
                    cmp = new java.math.BigDecimal(valA).compareTo(new java.math.BigDecimal(valB));
                } else {
                    cmp = valA.compareTo(valB);
                }
                return key.ascending ? cmp : -cmp;
            };
            comparator = comparator.thenComparing(keyComp);
        }
        return comparator;
    }

    // ── Sort phases ─────────────────────────────────────────────────

    /**
     * Handle for the INPUT PROCEDURE — call {@link #release()} to feed
     * the current sort record's contents into the sort.
     */
    public static final class SortInput {
        private final Record sortRecord;
        final List<byte[]> records = new ArrayList<>();

        SortInput(Record sortRecord) {
            this.sortRecord = sortRecord;
        }

        /** RELEASE — feed the current sort record into the sort. */
        public void release() {
            records.add(sortRecord.buffer()); // copies current buffer
        }

        /** RELEASE a specific record's contents. */
        public void release(Record source) {
            records.add(source.buffer());
        }
    }

    /**
     * Handle for the OUTPUT PROCEDURE — call {@link #returnRecord()} to
     * get the next sorted record.
     */
    public static final class SortOutput {
        private final Record sortRecord;
        private final List<byte[]> records;
        private int index;

        SortOutput(Record sortRecord, List<byte[]> records) {
            this.sortRecord = sortRecord;
            this.records = records;
            this.index = 0;
        }

        /**
         * RETURN — load the next sorted record into the sort record.
         * Returns true if a record was loaded, false when all records
         * have been returned (equivalent to AT END).
         */
        public boolean returnRecord() {
            if (index >= records.size()) return false;
            sortRecord.loadFrom(records.get(index++));
            return true;
        }

        /** RETURN into a specific target record. */
        public boolean returnRecord(Record target) {
            if (index >= records.size()) return false;
            target.loadFrom(records.get(index++));
            return true;
        }
    }

    private record SortKey(String fieldName, boolean ascending) {}
}
