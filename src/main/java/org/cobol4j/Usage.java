package org.cobol4j;

/**
 * COBOL USAGE clause — determines the internal storage format of a numeric field.
 *
 * DISPLAY is the default: one byte per digit, human-readable.
 * COMP/COMP-4 is native binary (halfword/fullword/doubleword).
 * COMP-3 is packed decimal (two digits per byte, sign in last nibble).
 * COMP-5 is native binary with full range of the storage bytes.
 */
public enum Usage {

    DISPLAY,
    COMP,
    COMP3,
    COMP4,
    COMP5;

    /** Alias: BINARY is synonymous with COMP. */
    public static final Usage BINARY = COMP;

    /** Alias: PACKED_DECIMAL is synonymous with COMP-3. */
    public static final Usage PACKED_DECIMAL = COMP3;

    /**
     * Parse a COBOL USAGE string (e.g., "COMP-3", "PACKED-DECIMAL", "BINARY").
     */
    public static Usage parse(String text) {
        String upper = text.toUpperCase().replace("-", "").replace(" ", "").trim();
        return switch (upper) {
            case "DISPLAY" -> DISPLAY;
            case "COMP", "COMP4", "COMPUTATIONAL", "COMPUTATIONAL4", "BINARY" -> COMP;
            case "COMP3", "COMPUTATIONAL3", "PACKEDDECIMAL" -> COMP3;
            case "COMP5", "COMPUTATIONAL5" -> COMP5;
            default -> throw new IllegalArgumentException("Unknown USAGE: " + text);
        };
    }
}
