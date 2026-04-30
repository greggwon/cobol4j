package org.cobol4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Consumer;

/**
 * A COBOL record: a named, hierarchical group of fields backed by a byte buffer.
 * <p>
 * The byte buffer IS the data — just like COBOL. Fields are views over byte ranges,
 * with encoding/decoding governed by their PIC clause and Usage. REDEFINES creates
 * alternate views over the same bytes. OCCURS creates indexed views at computed offsets.
 * <p>
 * <b>Usage — define a record, then use it:</b>
 * <pre>{@code
 * Record customer = Record.define("CUSTOMER-RECORD")
 *     .group("CUST-NAME", name -> name
 *         .pic("FIRST-NAME",  "X(20)")
 *         .pic("LAST-NAME",   "X(25)"))
 *     .pic("CUST-BALANCE", "S9(7)V99").comp3()
 *     .pic("CUST-STATUS", "X")
 *         .value88("ACTIVE",   "A")
 *         .value88("INACTIVE", "I")
 *     .build();
 *
 * customer.move("FIRST-NAME", "JOHN");
 * customer.move("CUST-BALANCE", new BigDecimal("12345.67"));
 * if (customer.is("ACTIVE")) { ... }
 * }</pre>
 */
public final class Record {

    private final String name;
    private final int totalSize;
    private final Map<String, FieldDef> fieldsByName;      // flat lookup
    private final Map<String, Condition> conditionsByName;  // all 88-levels
    private final List<FieldDef> topLevelFields;            // hierarchical
    private final byte[] data;

    private Record(String name, int totalSize,
                   Map<String, FieldDef> fieldsByName,
                   Map<String, Condition> conditionsByName,
                   List<FieldDef> topLevelFields,
                   byte[] data) {
        this.name = name;
        this.totalSize = totalSize;
        this.fieldsByName = fieldsByName;
        this.conditionsByName = conditionsByName;
        this.topLevelFields = topLevelFields;
        this.data = data;
    }

    // ── Factory ─────────────────────────────────────────────────────

    /** Begin defining a record with the given COBOL name. */
    public static Builder define(String name) {
        return new Builder(name);
    }

    /** Create a new instance with the same definition but a fresh (space-filled) buffer. */
    public Record newInstance() {
        byte[] buf = new byte[totalSize];
        Arrays.fill(buf, (byte) ' ');
        return new Record(name, totalSize, fieldsByName, conditionsByName,
                          topLevelFields, buf);
    }

    /** Create a new instance initialized as a copy of this record's data. */
    public Record duplicate() {
        return new Record(name, totalSize, fieldsByName, conditionsByName,
                          topLevelFields, Arrays.copyOf(data, data.length));
    }

    // ═══════════════════════════════════════════════════════════════
    //  DATA ACCESS
    // ═══════════════════════════════════════════════════════════════

    /** Get a field's value as a String (display format). */
    public String getString(String fieldName) {
        FieldDef f = requireField(fieldName);
        return decodeDisplay(f, f.offset());
    }

    /** Get an OCCURS element's value as a String. */
    public String getString(String fieldName, int index) {
        FieldDef f = requireField(fieldName);
        return decodeDisplay(f, f.offsetForIndex(index));
    }

    /** Get a numeric field's value as BigDecimal, respecting PIC scale. */
    public BigDecimal getDecimal(String fieldName) {
        FieldDef f = requireNumericField(fieldName);
        return decodeNumeric(f, f.offset());
    }

    /** Get an OCCURS numeric element as BigDecimal. */
    public BigDecimal getDecimal(String fieldName, int index) {
        FieldDef f = requireNumericField(fieldName);
        return decodeNumeric(f, f.offsetForIndex(index));
    }

    /** Get a numeric field's value as long (integer portion, truncating decimals). */
    public long getLong(String fieldName) {
        return getDecimal(fieldName).longValue();
    }

    /** Get a numeric field's value as int. */
    public int getInt(String fieldName) {
        return getDecimal(fieldName).intValue();
    }

    /** Direct access to the field's raw bytes. Returns a copy. */
    public byte[] getBytes(String fieldName) {
        FieldDef f = requireField(fieldName);
        return Arrays.copyOfRange(data, f.offset(), f.offset() + f.size());
    }

    /** Access the entire record buffer. Returns a copy. */
    public byte[] buffer() {
        return Arrays.copyOf(data, data.length);
    }

    /** Total record size in bytes. */
    public int length() { return totalSize; }

    /** Record name. */
    public String name() { return name; }

    // ═══════════════════════════════════════════════════════════════
    //  MOVE — the heart of COBOL data handling
    // ═══════════════════════════════════════════════════════════════

    /** MOVE a String value to a field, applying COBOL move rules. */
    public void move(String fieldName, String value) {
        FieldDef f = requireField(fieldName);
        moveToField(f, f.offset(), value);
    }

    /** MOVE a String value to an OCCURS element. */
    public void move(String fieldName, int index, String value) {
        FieldDef f = requireField(fieldName);
        moveToField(f, f.offsetForIndex(index), value);
    }

    /** MOVE a numeric value to a field. */
    public void move(String fieldName, BigDecimal value) {
        FieldDef f = requireField(fieldName);
        if (f.isNumeric()) {
            encodeNumeric(f, f.offset(), value);
        } else {
            moveToField(f, f.offset(), value.toPlainString());
        }
    }

    /** MOVE a numeric literal to a field. */
    public void move(String fieldName, long value) {
        move(fieldName, BigDecimal.valueOf(value));
    }

    /** MOVE a double literal to a field. */
    public void move(String fieldName, double value) {
        move(fieldName, BigDecimal.valueOf(value));
    }

    /** MOVE from one field in a source record to a field in this record. */
    public void move(String targetField, Record source, String sourceField) {
        FieldDef target = requireField(targetField);
        FieldDef src = source.requireField(sourceField);

        if (target.isGroup() || src.isGroup()) {
            // Group move: treat as alphanumeric-to-alphanumeric
            String val = source.getString(sourceField);
            moveAlphanumeric(target, target.offset(), val);
        } else if (target.isNumeric() && src.isNumeric()) {
            // Numeric to numeric: decimal-aligned
            BigDecimal val = source.getDecimal(sourceField);
            encodeNumeric(target, target.offset(), val);
        } else if (target.isNumeric()) {
            // Alphanumeric to numeric: de-edit, treat as integer
            String val = source.getString(sourceField).trim();
            try {
                encodeNumeric(target, target.offset(), new BigDecimal(val));
            } catch (NumberFormatException e) {
                encodeNumeric(target, target.offset(), BigDecimal.ZERO);
            }
        } else {
            // Numeric to alphanumeric or alpha-to-alpha
            String val = source.getString(sourceField);
            moveAlphanumeric(target, target.offset(), val);
        }
    }

    /**
     * MOVE CORRESPONDING — copy fields with matching names from source to this record.
     * For each field in source, if this record has a field with the same name, MOVE it.
     */
    public void moveCorresponding(Record source) {
        for (String fieldName : source.fieldsByName.keySet()) {
            FieldDef target = fieldsByName.get(fieldName);
            if (target != null && target.isElementary()) {
                FieldDef src = source.fieldsByName.get(fieldName);
                if (src != null && src.isElementary()) {
                    move(fieldName, source, fieldName);
                }
            }
        }
    }

    /** MOVE SPACES — fill field with spaces. */
    public void moveSpaces(String fieldName) {
        FieldDef f = requireField(fieldName);
        Arrays.fill(data, f.offset(), f.offset() + f.size(), (byte) ' ');
    }

    /** MOVE ZEROS — fill numeric field with zeros, alphanumeric with '0'. */
    public void moveZeros(String fieldName) {
        FieldDef f = requireField(fieldName);
        if (f.isNumeric()) {
            encodeNumeric(f, f.offset(), BigDecimal.ZERO);
        } else {
            Arrays.fill(data, f.offset(), f.offset() + f.size(), (byte) '0');
        }
    }

    /** MOVE HIGH-VALUES — fill with 0xFF bytes. */
    public void moveHighValues(String fieldName) {
        FieldDef f = requireField(fieldName);
        Arrays.fill(data, f.offset(), f.offset() + f.size(), (byte) 0xFF);
    }

    /** MOVE LOW-VALUES — fill with 0x00 bytes. */
    public void moveLowValues(String fieldName) {
        FieldDef f = requireField(fieldName);
        Arrays.fill(data, f.offset(), f.offset() + f.size(), (byte) 0x00);
    }

    // ── MOVE internals ──────────────────────────────────────────────

    private void moveToField(FieldDef f, int offset, String value) {
        if (f.isGroup()) {
            moveAlphanumeric(f, offset, value);
        } else if (f.isNumeric()) {
            // String to numeric: parse and encode
            String trimmed = value == null ? "" : value.trim();
            BigDecimal numVal;
            try {
                numVal = trimmed.isEmpty() ? BigDecimal.ZERO : new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                numVal = BigDecimal.ZERO;
            }
            encodeNumeric(f, offset, numVal);
        } else {
            moveAlphanumeric(f, offset, value);
        }
    }

    /**
     * Alphanumeric MOVE: left-justified, space-padded or truncated on the right.
     */
    private void moveAlphanumeric(FieldDef f, int offset, String value) {
        String val = value == null ? "" : value;
        int size = elementSize(f);
        // Fill with spaces first
        Arrays.fill(data, offset, offset + size, (byte) ' ');
        // Copy characters, left-justified, truncate if too long
        int copyLen = Math.min(val.length(), size);
        for (int i = 0; i < copyLen; i++) {
            data[offset + i] = (byte) val.charAt(i);
        }
    }

    /** Effective size of one element — stride for OCCURS fields, full size otherwise. */
    private static int elementSize(FieldDef f) {
        return f.isArray() ? f.stride() : f.size();
    }

    // ═══════════════════════════════════════════════════════════════
    //  NUMERIC ENCODING / DECODING
    // ═══════════════════════════════════════════════════════════════

    private void encodeNumeric(FieldDef f, int offset, BigDecimal value) {
        switch (f.usage()) {
            case COMP3 -> PackedDecimal.encode(value, f.pic(), data, offset, f.size());
            case COMP, COMP4, COMP5 -> encodeBinary(f, offset, value);
            default -> encodeDisplay(f, offset, value);
        }
    }

    private BigDecimal decodeNumeric(FieldDef f, int offset) {
        return switch (f.usage()) {
            case COMP3 -> PackedDecimal.decode(f.pic(), data, offset, f.size());
            case COMP, COMP4, COMP5 -> decodeBinary(f, offset);
            default -> decodeDisplayNumeric(f, offset);
        };
    }

    /**
     * DISPLAY numeric: one ASCII digit per byte, sign in the zone of the last byte.
     * Decimal alignment: the PIC's V position determines where digits land.
     */
    private void encodeDisplay(FieldDef f, int offset, BigDecimal value) {
        Pic pic = f.pic();
        int intDigits = pic.integerDigits();
        int decDigits = pic.decimalDigits();
        int totalDigits = intDigits + decDigits;

        // Scale to remove decimal point
        BigDecimal scaled = value.movePointRight(decDigits)
                                 .setScale(0, RoundingMode.DOWN);
        boolean negative = scaled.signum() < 0;
        String digits = scaled.abs().toBigInteger().toString();

        // Pad or truncate
        if (digits.length() < totalDigits) {
            digits = "0".repeat(totalDigits - digits.length()) + digits;
        } else if (digits.length() > totalDigits) {
            digits = digits.substring(digits.length() - totalDigits);
        }

        // Write digits as ASCII
        for (int i = 0; i < totalDigits; i++) {
            data[offset + i] = (byte) digits.charAt(i);
        }

        // Trailing sign: encode in the zone nibble of the last byte
        // For simplicity in this prototype, we use ASCII sign overpunch:
        // positive: last digit stays as-is (or we store a trailing '+'/digit)
        // COBOL convention: positive = {, A-I for 0-9; negative = }, J-R for 0-9
        if (pic.isSigned()) {
            int lastDigit = digits.charAt(totalDigits - 1) - '0';
            if (negative) {
                // EBCDIC sign overpunch for negative: } for 0, J-R for 1-9
                data[offset + totalDigits - 1] = (byte) (lastDigit == 0 ? '}' : ('J' - 1 + lastDigit));
            } else {
                // Positive: { for 0, A-I for 1-9
                data[offset + totalDigits - 1] = (byte) (lastDigit == 0 ? '{' : ('A' - 1 + lastDigit));
            }
        }
    }

    private BigDecimal decodeDisplayNumeric(FieldDef f, int offset) {
        Pic pic = f.pic();
        int totalDigits = pic.totalDigits();
        StringBuilder digits = new StringBuilder(totalDigits);
        boolean negative = false;

        for (int i = 0; i < totalDigits; i++) {
            byte b = data[offset + i];
            char c = (char) (b & 0xFF);

            if (i == totalDigits - 1 && pic.isSigned()) {
                // Decode sign overpunch from last byte
                if (c >= '0' && c <= '9') {
                    digits.append(c);
                } else if (c == '{') {
                    digits.append('0');
                } else if (c >= 'A' && c <= 'I') {
                    digits.append((char) ('1' + (c - 'A')));
                } else if (c == '}') {
                    digits.append('0');
                    negative = true;
                } else if (c >= 'J' && c <= 'R') {
                    digits.append((char) ('1' + (c - 'J')));
                    negative = true;
                } else {
                    digits.append('0');
                }
            } else if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                digits.append('0'); // non-numeric byte → treat as zero
            }
        }

        BigDecimal result = new BigDecimal(new BigInteger(digits.toString()), pic.decimalDigits());
        return negative ? result.negate() : result;
    }

    /**
     * COMP/BINARY: native binary in big-endian (COBOL standard).
     * The decimal scale is implied by the PIC clause.
     */
    private void encodeBinary(FieldDef f, int offset, BigDecimal value) {
        Pic pic = f.pic();
        BigDecimal scaled = value.movePointRight(pic.decimalDigits())
                                 .setScale(0, RoundingMode.DOWN);
        long longVal = scaled.longValueExact();

        ByteBuffer buf = ByteBuffer.wrap(data, offset, f.size())
                                   .order(ByteOrder.BIG_ENDIAN);
        switch (f.size()) {
            case 2 -> buf.putShort((short) longVal);
            case 4 -> buf.putInt((int) longVal);
            case 8 -> buf.putLong(longVal);
        }
    }

    private BigDecimal decodeBinary(FieldDef f, int offset) {
        Pic pic = f.pic();
        ByteBuffer buf = ByteBuffer.wrap(data, offset, f.size())
                                   .order(ByteOrder.BIG_ENDIAN);
        long longVal = switch (f.size()) {
            case 2 -> buf.getShort();
            case 4 -> buf.getInt();
            case 8 -> buf.getLong();
            default -> 0;
        };
        return BigDecimal.valueOf(longVal, pic.decimalDigits());
    }

    /**
     * Decode any field to its display representation as a String.
     */
    private String decodeDisplay(FieldDef f, int offset) {
        if (f.isGroup() || !f.isNumeric()) {
            // Alphanumeric: raw bytes as characters
            return new String(data, offset, elementSize(f));
        }
        // Numeric: return the display representation of the numeric value
        BigDecimal val = decodeNumeric(f, offset);
        if (f.pic().decimalDigits() > 0) {
            return val.toPlainString();
        }
        return val.toBigInteger().toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  ARITHMETIC — BigDecimal engine with PIC constraints
    // ═══════════════════════════════════════════════════════════════

    /**
     * ADD value TO field. Returns true if no size error.
     */
    public boolean add(String fieldName, BigDecimal value) {
        return add(fieldName, value, SizeErrorHandler.silent());
    }

    public boolean add(String fieldName, BigDecimal value, SizeErrorHandler handler) {
        FieldDef f = requireNumericField(fieldName);
        BigDecimal current = decodeNumeric(f, f.offset());
        BigDecimal result = current.add(value);
        return storeWithSizeCheck(f, f.offset(), result, handler);
    }

    /**
     * SUBTRACT value FROM field.
     */
    public boolean subtract(String fieldName, BigDecimal value) {
        return subtract(fieldName, value, SizeErrorHandler.silent());
    }

    public boolean subtract(String fieldName, BigDecimal value, SizeErrorHandler handler) {
        FieldDef f = requireNumericField(fieldName);
        BigDecimal current = decodeNumeric(f, f.offset());
        BigDecimal result = current.subtract(value);
        return storeWithSizeCheck(f, f.offset(), result, handler);
    }

    /**
     * MULTIPLY field BY value.
     */
    public boolean multiply(String fieldName, BigDecimal value) {
        return multiply(fieldName, value, SizeErrorHandler.silent());
    }

    public boolean multiply(String fieldName, BigDecimal value, SizeErrorHandler handler) {
        FieldDef f = requireNumericField(fieldName);
        BigDecimal current = decodeNumeric(f, f.offset());
        BigDecimal result = current.multiply(value);
        return storeWithSizeCheck(f, f.offset(), result, handler);
    }

    /**
     * DIVIDE field BY value.
     */
    public boolean divide(String fieldName, BigDecimal value) {
        return divide(fieldName, value, SizeErrorHandler.silent());
    }

    public boolean divide(String fieldName, BigDecimal value, SizeErrorHandler handler) {
        FieldDef f = requireNumericField(fieldName);
        BigDecimal current = decodeNumeric(f, f.offset());
        // Use enough precision to avoid ArithmeticException on non-terminating decimals
        BigDecimal result = current.divide(value, f.pic().decimalDigits() + 5, RoundingMode.HALF_EVEN);
        return storeWithSizeCheck(f, f.offset(), result, handler);
    }

    /**
     * COMPUTE — store a pre-computed result into a field.
     * The caller uses standard Java/BigDecimal arithmetic to compute the value;
     * this method applies PIC constraints and size-error checking.
     */
    public boolean compute(String fieldName, BigDecimal value) {
        return compute(fieldName, value, SizeErrorHandler.silent());
    }

    public boolean compute(String fieldName, BigDecimal value, SizeErrorHandler handler) {
        FieldDef f = requireNumericField(fieldName);
        return storeWithSizeCheck(f, f.offset(), value, handler);
    }

    /**
     * Store a value into a numeric field, checking for size overflow.
     * COBOL size error = the integer part of the result has more digits than the PIC allows.
     */
    private boolean storeWithSizeCheck(FieldDef f, int offset, BigDecimal value,
                                       SizeErrorHandler handler) {
        Pic pic = f.pic();
        // Truncate/round to the PIC's decimal precision
        BigDecimal fitted = value.setScale(pic.decimalDigits(), RoundingMode.DOWN);

        // Check if the integer part exceeds PIC capacity
        BigDecimal intPart = fitted.abs().setScale(0, RoundingMode.DOWN);
        int intDigitCount = intPart.compareTo(BigDecimal.ZERO) == 0 ? 1 : intPart.toBigInteger().toString().length();

        boolean overflow = intDigitCount > pic.integerDigits();
        if (overflow) {
            handler.onSizeError();
            // On size error, COBOL does NOT update the field (it keeps its old value)
            return false;
        }

        encodeNumeric(f, offset, fitted);
        handler.onSuccess();
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONDITIONS (level-88)
    // ═══════════════════════════════════════════════════════════════

    /** Test a level-88 condition by name. */
    public boolean is(String conditionName) {
        Condition cond = conditionsByName.get(conditionName);
        if (cond == null) {
            throw new IllegalArgumentException("No condition named: " + conditionName);
        }
        // Find the field that owns this condition
        for (FieldDef f : fieldsByName.values()) {
            if (f.conditions().containsKey(conditionName)) {
                String currentValue = getString(f.name());
                return cond.test(currentValue);
            }
        }
        // Condition might be standalone/custom
        return cond.test("");
    }

    /** SET a condition — moves the condition's value into the owning field. */
    public void set(String conditionName) {
        Condition cond = conditionsByName.get(conditionName);
        if (cond == null) {
            throw new IllegalArgumentException("No condition named: " + conditionName);
        }
        String value = cond.setValue();
        if (value == null) {
            throw new UnsupportedOperationException(
                "Condition " + conditionName + " has no settable value (range or custom condition)");
        }
        // Find owning field and move the value
        for (FieldDef f : fieldsByName.values()) {
            if (f.conditions().containsKey(conditionName)) {
                moveToField(f, f.offset(), value);
                return;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  REFERENCE MODIFICATION — substring access (1-based, COBOL style)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Reference modification: {@code FIELD(position:length)}.
     * Position is 1-based, as in COBOL.
     */
    public String substring(String fieldName, int position, int length) {
        FieldDef f = requireField(fieldName);
        int start = f.offset() + position - 1; // 1-based to 0-based
        return new String(data, start, length);
    }

    // ═══════════════════════════════════════════════════════════════
    //  FIELD INTROSPECTION
    // ═══════════════════════════════════════════════════════════════

    /** Check if a field exists in this record. */
    public boolean hasField(String fieldName) {
        return fieldsByName.containsKey(fieldName);
    }

    /** Get a field's definition. */
    public FieldDef fieldDef(String fieldName) {
        return requireField(fieldName);
    }

    /** All field names in this record. */
    public Set<String> fieldNames() {
        return Collections.unmodifiableSet(fieldsByName.keySet());
    }

    // ── internal helpers ────────────────────────────────────────────

    private FieldDef requireField(String name) {
        FieldDef f = fieldsByName.get(name);
        if (f == null) {
            throw new IllegalArgumentException("No field named '" + name + "' in record " + this.name);
        }
        return f;
    }

    private FieldDef requireNumericField(String name) {
        FieldDef f = requireField(name);
        if (!f.isNumeric()) {
            throw new IllegalArgumentException("Field '" + name + "' is not numeric");
        }
        return f;
    }

    // ═══════════════════════════════════════════════════════════════
    //  FLUENT BUILDER
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fluent builder for constructing Record definitions.
     * <p>
     * Mirrors the hierarchical structure of COBOL's DATA DIVISION, using
     * method chaining and lambdas for group nesting.
     */
    public static final class Builder {

        private final String recordName;
        private final List<FieldEntry> entries = new ArrayList<>();
        private FieldEntry lastEntry;

        Builder(String recordName) {
            this.recordName = recordName;
        }

        /** Define an elementary field with a PIC clause. */
        public Builder pic(String name, String picString) {
            FieldEntry entry = new FieldEntry(name, Pic.parse(picString), Usage.DISPLAY);
            entries.add(entry);
            lastEntry = entry;
            return this;
        }

        /** Define an elementary field with PIC and Usage. */
        public Builder pic(String name, String picString, Usage usage) {
            FieldEntry entry = new FieldEntry(name, Pic.parse(picString), usage);
            entries.add(entry);
            lastEntry = entry;
            return this;
        }

        /** Set the last field's usage to COMP-3 (packed decimal). */
        public Builder comp3() {
            requireLastEntry().usage = Usage.COMP3;
            return this;
        }

        /** Set the last field's usage to COMP (binary). */
        public Builder comp() {
            requireLastEntry().usage = Usage.COMP;
            return this;
        }

        /** Set the last field's usage to COMP-5 (native binary). */
        public Builder comp5() {
            requireLastEntry().usage = Usage.COMP5;
            return this;
        }

        /** Define a group field containing children defined in the lambda. */
        public Builder group(String name, Consumer<Builder> children) {
            Builder childBuilder = new Builder(name);
            children.accept(childBuilder);
            FieldEntry entry = new FieldEntry(name, childBuilder.entries);
            entries.add(entry);
            lastEntry = entry;
            return this;
        }

        /** REDEFINES — create an alternate view over a previously defined field. */
        public Builder redefines(String targetFieldName, String newName,
                                 Consumer<Builder> children) {
            Builder childBuilder = new Builder(newName);
            children.accept(childBuilder);
            FieldEntry entry = new FieldEntry(newName, childBuilder.entries);
            entry.redefines = targetFieldName;
            entries.add(entry);
            lastEntry = entry;
            return this;
        }

        /** OCCURS — the last-defined field/group repeats this many times. */
        public Builder occurs(int times) {
            requireLastEntry().occurs = times;
            return this;
        }

        /** Add a level-88 condition to the last-defined field. */
        public Builder value88(String conditionName, String... values) {
            requireLastEntry().conditions.put(conditionName,
                Condition.values(conditionName, values));
            return this;
        }

        /** Add a range level-88 condition (VALUE ... THRU ...). */
        public Builder value88Range(String conditionName, String from, String to) {
            requireLastEntry().conditions.put(conditionName,
                Condition.range(conditionName, from, to));
            return this;
        }

        /** Add a custom condition backed by a lambda. */
        public Builder condition(String conditionName, java.util.function.Predicate<String> test) {
            requireLastEntry().conditions.put(conditionName,
                Condition.of(conditionName, test));
            return this;
        }

        /** Set the initial VALUE for the last-defined field. */
        public Builder value(String initialValue) {
            requireLastEntry().initialValue = initialValue;
            return this;
        }

        /**
         * Build the Record: compute offsets, create FieldDefs, allocate the buffer.
         */
        public Record build() {
            Map<String, FieldDef> fieldMap = new LinkedHashMap<>();
            Map<String, Condition> condMap = new LinkedHashMap<>();
            List<FieldDef> topLevel = new ArrayList<>();

            int currentOffset = 0;
            Map<String, Integer> fieldOffsets = new HashMap<>();
            Map<String, Integer> fieldSizes = new HashMap<>();

            // First pass: compute offsets and sizes
            currentOffset = layoutEntries(entries, currentOffset,
                                          fieldOffsets, fieldSizes, null);
            int totalSize = currentOffset;

            // Second pass: build FieldDef objects
            buildFieldDefs(entries, 1, fieldMap, condMap, topLevel,
                           fieldOffsets, fieldSizes);

            // Allocate buffer and create the record
            byte[] buffer = new byte[totalSize];
            Record record = new Record(recordName, totalSize, fieldMap, condMap,
                                       topLevel, buffer);

            // Initialize with spaces (COBOL default for alphanumeric)
            Arrays.fill(buffer, (byte) ' ');

            // Apply initial VALUES
            applyInitialValues(entries, record);

            return record;
        }

        // ── layout engine ───────────────────────────────────────────

        private int layoutEntries(List<FieldEntry> entries, int startOffset,
                                  Map<String, Integer> offsets,
                                  Map<String, Integer> sizes,
                                  String parentRedefines) {
            int offset = startOffset;

            for (FieldEntry e : entries) {
                if (e.redefines != null) {
                    // REDEFINES: use the same offset and size as the target
                    Integer targetOffset = offsets.get(e.redefines);
                    Integer targetSize = sizes.get(e.redefines);
                    if (targetOffset == null) {
                        throw new IllegalArgumentException(
                            "REDEFINES target not found: " + e.redefines);
                    }
                    offsets.put(e.name, targetOffset);
                    // Layout children within the redefined space
                    if (e.children != null) {
                        layoutEntries(e.children, targetOffset, offsets, sizes, e.redefines);
                    }
                    int childSize = computeSize(e);
                    sizes.put(e.name, targetSize); // redefining field uses target's size
                    // Don't advance offset — redefines overlays
                } else if (e.children != null) {
                    // Group item
                    int groupStart = offset;
                    int occurrenceSize;

                    if (e.occurs > 0) {
                        // Layout one occurrence to get its size
                        int oneEnd = layoutEntries(e.children, groupStart, offsets, sizes, null);
                        occurrenceSize = oneEnd - groupStart;
                        // Total size = occurrences * stride
                        int totalArraySize = occurrenceSize * e.occurs;
                        offsets.put(e.name, groupStart);
                        sizes.put(e.name, totalArraySize);
                        offset = groupStart + totalArraySize;
                    } else {
                        int groupEnd = layoutEntries(e.children, groupStart, offsets, sizes, null);
                        offsets.put(e.name, groupStart);
                        sizes.put(e.name, groupEnd - groupStart);
                        offset = groupEnd;
                    }
                } else {
                    // Elementary item
                    int fieldSize = e.pic.storageSize(e.usage);
                    if (e.occurs > 0) {
                        offsets.put(e.name, offset);
                        sizes.put(e.name, fieldSize * e.occurs);
                        offset += fieldSize * e.occurs;
                    } else {
                        offsets.put(e.name, offset);
                        sizes.put(e.name, fieldSize);
                        offset += fieldSize;
                    }
                }
            }
            return offset;
        }

        private int computeSize(FieldEntry e) {
            if (e.children != null) {
                int total = 0;
                for (FieldEntry child : e.children) {
                    total += computeSize(child);
                }
                return total * Math.max(1, e.occurs);
            } else {
                int base = e.pic.storageSize(e.usage);
                return base * Math.max(1, e.occurs);
            }
        }

        private void buildFieldDefs(List<FieldEntry> entries, int level,
                                     Map<String, FieldDef> fieldMap,
                                     Map<String, Condition> condMap,
                                     List<FieldDef> siblings,
                                     Map<String, Integer> offsets,
                                     Map<String, Integer> sizes) {
            for (FieldEntry e : entries) {
                List<FieldDef> children = new ArrayList<>();
                if (e.children != null) {
                    buildFieldDefs(e.children, level + 1, fieldMap, condMap,
                                   children, offsets, sizes);
                }

                int stride = 0;
                if (e.occurs > 0) {
                    stride = sizes.get(e.name) / e.occurs;
                }

                FieldDef fd = new FieldDef(
                    e.name, level, e.pic, e.usage,
                    offsets.get(e.name), sizes.get(e.name),
                    e.children != null, e.redefines,
                    e.occurs, stride,
                    children, e.conditions
                );

                fieldMap.put(e.name, fd);
                siblings.add(fd);

                // Register conditions
                for (Map.Entry<String, Condition> cond : e.conditions.entrySet()) {
                    condMap.put(cond.getKey(), cond.getValue());
                }
            }
        }

        private void applyInitialValues(List<FieldEntry> entries, Record record) {
            for (FieldEntry e : entries) {
                if (e.initialValue != null) {
                    record.move(e.name, e.initialValue);
                }
                if (e.children != null) {
                    applyInitialValues(e.children, record);
                }
            }
        }

        private FieldEntry requireLastEntry() {
            if (lastEntry == null) {
                throw new IllegalStateException("No field defined yet");
            }
            return lastEntry;
        }

        // ── builder internal model ──────────────────────────────────

        private static final class FieldEntry {
            final String name;
            final Pic pic;            // null for groups
            Usage usage;
            final List<FieldEntry> children; // null for elementary
            final Map<String, Condition> conditions = new LinkedHashMap<>();
            String redefines;
            int occurs;
            String initialValue;

            // Elementary
            FieldEntry(String name, Pic pic, Usage usage) {
                this.name = name;
                this.pic = pic;
                this.usage = usage;
                this.children = null;
            }

            // Group
            FieldEntry(String name, List<FieldEntry> children) {
                this.name = name;
                this.pic = null;
                this.usage = Usage.DISPLAY;
                this.children = new ArrayList<>(children);
            }
        }
    }
}
