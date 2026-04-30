package org.cobol4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * COBOL SEARCH and SEARCH ALL operations on OCCURS tables.
 * <p>
 * SEARCH performs a linear scan; SEARCH ALL performs a binary search
 * (requires the table to be sorted by the search key).
 * <pre>{@code
 * // Linear SEARCH — scan for a matching entry
 * Search.table(rec, "TABLE-ENTRY")
 *     .atEnd(() -> rec.move("FOUND-FLAG", "N"))
 *     .when(idx -> rec.getString("TABLE-CODE", idx).trim().equals(searchCode),
 *           idx -> {
 *               rec.move("RESULT", rec.getString("TABLE-VALUE", idx));
 *               rec.move("FOUND-FLAG", "Y");
 *           })
 *     .execute();
 *
 * // Binary SEARCH ALL — requires sorted table
 * Search.all(rec, "TABLE-ENTRY")
 *     .key(idx -> rec.getString("TABLE-CODE", idx).trim())
 *     .equalTo(searchCode)
 *     .atEnd(() -> rec.move("FOUND-FLAG", "N"))
 *     .found(idx -> rec.move("RESULT", rec.getString("TABLE-VALUE", idx)))
 *     .execute();
 * }</pre>
 */
public final class Search {

    private Search() {}

    /** Begin a linear SEARCH on an OCCURS field. */
    public static LinearBuilder table(Record record, String occursFieldName) {
        return new LinearBuilder(record, record.fieldDef(occursFieldName));
    }

    /** Begin a binary SEARCH ALL on an OCCURS field. */
    public static BinaryBuilder all(Record record, String occursFieldName) {
        return new BinaryBuilder(record, record.fieldDef(occursFieldName));
    }

    // ═══════════════════════════════════════════════════════════════

    /**
     * Linear SEARCH builder.
     */
    public static final class LinearBuilder {
        private final Record record;
        private final FieldDef field;
        private Runnable atEnd;
        private final List<WhenClause> whenClauses = new ArrayList<>();
        private int startIndex = 0;

        LinearBuilder(Record record, FieldDef field) {
            this.record = record;
            this.field = field;
            if (!field.isArray()) {
                throw new IllegalArgumentException(
                    "SEARCH requires an OCCURS field: " + field.name());
            }
        }

        /** AT END — executed when no matching entry is found. */
        public LinearBuilder atEnd(Runnable handler) {
            this.atEnd = handler;
            return this;
        }

        /** Set the starting index for the search (0-based). Default: 0. */
        public LinearBuilder startingAt(int index) {
            this.startIndex = index;
            return this;
        }

        /**
         * WHEN condition — test each element; execute action on first match.
         * The predicate receives the current index (0-based).
         * The action receives the matching index.
         */
        public LinearBuilder when(IntPredicate condition, IntConsumer action) {
            whenClauses.add(new WhenClause(condition, action));
            return this;
        }

        /** Execute the linear search. */
        public void execute() {
            for (int i = startIndex; i < field.occurs(); i++) {
                for (WhenClause clause : whenClauses) {
                    if (clause.condition.test(i)) {
                        clause.action.accept(i);
                        return;
                    }
                }
            }
            if (atEnd != null) atEnd.run();
        }

        private record WhenClause(IntPredicate condition, IntConsumer action) {}
    }

    /**
     * Binary SEARCH ALL builder.
     */
    public static final class BinaryBuilder {
        private final Record record;
        private final FieldDef field;
        private IntFunction<String> keyExtractor;
        private IntFunction<BigDecimal> numericKeyExtractor;
        private String targetString;
        private java.math.BigDecimal targetNumeric;
        private Runnable atEnd;
        private IntConsumer found;

        BinaryBuilder(Record record, FieldDef field) {
            this.record = record;
            this.field = field;
            if (!field.isArray()) {
                throw new IllegalArgumentException(
                    "SEARCH ALL requires an OCCURS field: " + field.name());
            }
        }

        /** Define the key extractor (returns String key for a given index). */
        public BinaryBuilder key(IntFunction<String> extractor) {
            this.keyExtractor = extractor;
            return this;
        }

        /** Define a numeric key extractor. */
        public BinaryBuilder numericKey(IntFunction<BigDecimal> extractor) {
            this.numericKeyExtractor = extractor;
            return this;
        }

        /** The target value to search for (String). */
        public BinaryBuilder equalTo(String value) {
            this.targetString = value;
            return this;
        }

        /** The target value to search for (numeric). */
        public BinaryBuilder equalTo(java.math.BigDecimal value) {
            this.targetNumeric = value;
            return this;
        }

        /** AT END — no matching entry found. */
        public BinaryBuilder atEnd(Runnable handler) {
            this.atEnd = handler;
            return this;
        }

        /** Action when a matching entry is found. Receives the index. */
        public BinaryBuilder found(IntConsumer handler) {
            this.found = handler;
            return this;
        }

        /** Execute the binary search. */
        public void execute() {
            int lo = 0, hi = field.occurs() - 1;

            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int cmp;

                if (numericKeyExtractor != null) {
                    cmp = numericKeyExtractor.apply(mid).compareTo(targetNumeric);
                } else {
                    cmp = keyExtractor.apply(mid).compareTo(targetString);
                }

                if (cmp < 0) {
                    lo = mid + 1;
                } else if (cmp > 0) {
                    hi = mid - 1;
                } else {
                    if (found != null) found.accept(mid);
                    return;
                }
            }
            if (atEnd != null) atEnd.run();
        }
    }
}
