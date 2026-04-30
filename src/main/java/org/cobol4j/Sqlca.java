package org.cobol4j;

/**
 * COBOL SQL Communication Area (SQLCA).
 * <p>
 * After every EXEC SQL statement, COBOL programs check SQLCODE to determine
 * the outcome. This class holds that state.
 * <ul>
 *   <li>{@code SQLCODE = 0}   — successful execution</li>
 *   <li>{@code SQLCODE = 100} — no data found (SELECT returned no rows, or FETCH past end)</li>
 *   <li>{@code SQLCODE < 0}   — error</li>
 *   <li>{@code SQLCODE > 0 && != 100} — warning</li>
 * </ul>
 */
public final class Sqlca {

    public static final int SUCCESS    = 0;
    public static final int NOT_FOUND  = 100;

    private int sqlCode;
    private String sqlState = "00000";
    private String sqlErrm = "";
    private long sqlErrd3;  // rows affected (SQLERRD(3) in COBOL)

    public int sqlCode()       { return sqlCode; }
    public String sqlState()   { return sqlState; }
    public String sqlErrm()    { return sqlErrm; }
    public long rowsAffected() { return sqlErrd3; }

    public boolean isSuccess()  { return sqlCode == SUCCESS; }
    public boolean isNotFound() { return sqlCode == NOT_FOUND; }
    public boolean isError()    { return sqlCode < 0; }
    public boolean isWarning()  { return sqlCode > 0 && sqlCode != NOT_FOUND; }

    void set(int code, String state, String errm) {
        this.sqlCode = code;
        this.sqlState = state != null ? state : "00000";
        this.sqlErrm = errm != null ? errm : "";
    }

    void set(int code, String state, String errm, long rowsAffected) {
        set(code, state, errm);
        this.sqlErrd3 = rowsAffected;
    }

    void reset() {
        set(SUCCESS, "00000", "");
        this.sqlErrd3 = 0;
    }

    @Override
    public String toString() {
        return "SQLCA[code=" + sqlCode + ", state=" + sqlState
            + ", errm=" + sqlErrm + ", rows=" + sqlErrd3 + "]";
    }
}
