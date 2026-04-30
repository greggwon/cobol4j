package org.cobol4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * Fluent API for COBOL's INSPECT verb.
 * <p>
 * INSPECT examines a field character-by-character, optionally tallying occurrences
 * and/or replacing characters based on matching rules.
 * <pre>{@code
 * // INSPECT FIELD TALLYING COUNT FOR ALL "A"
 * int count = Inspect.on(record, "MESSAGE")
 *     .tallyAll("A")
 *     .count();
 *
 * // INSPECT FIELD REPLACING LEADING ZEROS BY SPACES
 * Inspect.on(record, "AMOUNT-DISPLAY")
 *     .replaceLeading('0', ' ')
 *     .apply();
 *
 * // Custom replacement logic via lambda
 * Inspect.on(record, "DATA")
 *     .replaceAll(ch -> ch == '.' ? ',' : ch)
 *     .apply();
 * }</pre>
 */
public final class Inspect {

    private final Record record;
    private final String fieldName;
    private final List<TallyRule> tallyRules = new ArrayList<>();
    private final List<ReplaceRule> replaceRules = new ArrayList<>();
    private IntUnaryOperator customReplacer;

    private Inspect(Record record, String fieldName) {
        this.record = record;
        this.fieldName = fieldName;
    }

    /** Begin an INSPECT operation on a field in a record. */
    public static Inspect on(Record record, String fieldName) {
        return new Inspect(record, fieldName);
    }

    // ── TALLYING ────────────────────────────────────────────────────

    /** TALLYING ... FOR ALL <literal> */
    public Inspect tallyAll(String literal) {
        tallyRules.add(new TallyRule(TallyType.ALL, literal));
        return this;
    }

    /** TALLYING ... FOR LEADING <char> */
    public Inspect tallyLeading(char c) {
        tallyRules.add(new TallyRule(TallyType.LEADING, String.valueOf(c)));
        return this;
    }

    /** TALLYING ... FOR CHARACTERS */
    public Inspect tallyCharacters() {
        tallyRules.add(new TallyRule(TallyType.CHARACTERS, null));
        return this;
    }

    /** Execute tallying and return the count. */
    public int count() {
        String value = record.getString(fieldName);
        int tally = 0;
        for (TallyRule rule : tallyRules) {
            tally += rule.apply(value);
        }
        return tally;
    }

    // ── REPLACING ───────────────────────────────────────────────────

    /** REPLACING ALL <from> BY <to> */
    public Inspect replaceAll(char from, char to) {
        replaceRules.add(new ReplaceRule(ReplaceType.ALL, from, to));
        return this;
    }

    /** REPLACING LEADING <from> BY <to> */
    public Inspect replaceLeading(char from, char to) {
        replaceRules.add(new ReplaceRule(ReplaceType.LEADING, from, to));
        return this;
    }

    /** REPLACING FIRST <from> BY <to> */
    public Inspect replaceFirst(char from, char to) {
        replaceRules.add(new ReplaceRule(ReplaceType.FIRST, from, to));
        return this;
    }

    /** Custom character-level replacement via lambda (char → char). */
    public Inspect replaceAll(IntUnaryOperator replacer) {
        this.customReplacer = replacer;
        return this;
    }

    /** Execute replacements and write the result back to the field. */
    public void apply() {
        String value = record.getString(fieldName);
        char[] chars = value.toCharArray();

        // Apply rule-based replacements
        for (ReplaceRule rule : replaceRules) {
            rule.apply(chars);
        }

        // Apply custom lambda replacement
        if (customReplacer != null) {
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) customReplacer.applyAsInt(chars[i]);
            }
        }

        record.move(fieldName, new String(chars));
    }

    // ── Internal rule types ─────────────────────────────────────────

    private enum TallyType { ALL, LEADING, CHARACTERS }

    private record TallyRule(TallyType type, String literal) {
        int apply(String value) {
            return switch (type) {
                case CHARACTERS -> value.length();
                case ALL -> {
                    int count = 0;
                    int idx = 0;
                    while ((idx = value.indexOf(literal, idx)) >= 0) {
                        count++;
                        idx += literal.length();
                    }
                    yield count;
                }
                case LEADING -> {
                    int count = 0;
                    char c = literal.charAt(0);
                    for (int i = 0; i < value.length(); i++) {
                        if (value.charAt(i) == c) count++;
                        else break;
                    }
                    yield count;
                }
            };
        }
    }

    private enum ReplaceType { ALL, LEADING, FIRST }

    private record ReplaceRule(ReplaceType type, char from, char to) {
        void apply(char[] chars) {
            switch (type) {
                case ALL -> {
                    for (int i = 0; i < chars.length; i++) {
                        if (chars[i] == from) chars[i] = to;
                    }
                }
                case LEADING -> {
                    for (int i = 0; i < chars.length; i++) {
                        if (chars[i] == from) chars[i] = to;
                        else break;
                    }
                }
                case FIRST -> {
                    for (int i = 0; i < chars.length; i++) {
                        if (chars[i] == from) { chars[i] = to; break; }
                    }
                }
            }
        }
    }
}
