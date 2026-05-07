/*
 * cobol4j - COBOL Runtime Semantics as a Java DSL
 * Copyright (C) 2026 Gregg Wonderly
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
package org.cobol4j.transpiler;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for COBOL source.
 * <p>
 * Parses the token stream produced by {@link Lexer} into a {@link CobolProgram} AST.
 * Supports a practical subset of COBOL covering the most common constructs in
 * enterprise programs.
 */
public final class Parser {

    private final List<Token> tokens;
    private final TranspileDiagnostics diag;
    private int pos;

    private Parser(List<Token> tokens, TranspileDiagnostics diag) {
        this.tokens = tokens;
        this.diag = diag;
        this.pos = 0;
    }

    /** Parse a token stream into a CobolProgram AST. */
    public static CobolProgram parse(List<Token> tokens) {
        return parse(tokens, new TranspileDiagnostics());
    }

    /** Parse with diagnostics collection. */
    public static CobolProgram parse(List<Token> tokens, TranspileDiagnostics diag) {
        return new Parser(tokens, diag).parseProgram();
    }

    /** Convenience: lex and parse source text. */
    public static CobolProgram parse(String source) {
        return parse(Lexer.tokenize(source), new TranspileDiagnostics());
    }

    /** Convenience: lex and parse with diagnostics. */
    public static CobolProgram parse(String source, TranspileDiagnostics diag) {
        return parse(Lexer.tokenize(source), diag);
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOP-LEVEL STRUCTURE
    // ═══════════════════════════════════════════════════════════════

    private CobolProgram parseProgram() {
        String programId = "UNNAMED";
        List<CobolProgram.DataEntry> dataEntries = new ArrayList<>();
        List<CobolProgram.Paragraph> paragraphs = new ArrayList<>();
        List<CobolProgram.FileBinding> fileBindings = new ArrayList<>();

        // Parse divisions in order
        if (matchWord("IDENTIFICATION")) {
            programId = parseIdentificationDivision();
        }
        if (matchWord("ENVIRONMENT")) {
            diag.info("parser", current().line(), "ENVIRONMENT DIVISION",
                "Environment Division skipped — file assignments must be configured in Java code.");
            skipDivision();
        }
        if (matchWord("DATA")) {
            dataEntries = parseDataDivision(fileBindings);
        }
        if (matchWord("PROCEDURE")) {
            paragraphs = parseProcedureDivision();
        }

        return new CobolProgram(programId, dataEntries, paragraphs, fileBindings);
    }

    // ── IDENTIFICATION DIVISION ─────────────────────────────────────

    private String parseIdentificationDivision() {
        expect("DIVISION");
        consumePeriod();
        String id = "UNNAMED";
        if (matchWord("PROGRAM-ID")) {
            consumePeriod();  // consume the period after PROGRAM-ID
            id = current().value();
            advance(); // consume the program name
            consumePeriod(); // consume the period after the name
        }
        // Skip remaining identification entries (AUTHOR, DATE-WRITTEN, etc.)
        while (!atEnd() && !peek("ENVIRONMENT") && !peek("DATA") && !peek("PROCEDURE")) {
            advance();
        }
        return id;
    }

    // ── DATA DIVISION ───────────────────────────────────────────────

    private List<CobolProgram.DataEntry> parseDataDivision(List<CobolProgram.FileBinding> fileBindings) {
        expect("DIVISION");
        consumePeriod();
        List<CobolProgram.DataEntry> entries = new ArrayList<>();

        while (!atEnd() && !peek("PROCEDURE")) {
            if (matchWord("FILE")) {
                expect("SECTION");
                consumePeriod();
                parseFileSection(entries, fileBindings);
                continue;
            }
            if (matchWord("WORKING-STORAGE") || matchWord("LOCAL-STORAGE")
                || matchWord("LINKAGE")) {
                expect("SECTION");
                consumePeriod();
                continue;
            }

            // Check for level number
            if (current().type() == Token.Type.NUMBER) {
                int level = Integer.parseInt(current().value());
                if (level >= 1 && level <= 88) {
                    entries.addAll(parseDataEntry(level));
                    continue;
                }
            }
            advance();
        }
        return entries;
    }

    /**
     * Parse FILE SECTION: one or more FD entries, each followed by its record definitions.
     * <pre>
     * FILE SECTION.
     * FD file-name [RECORD CONTAINS n CHARACTERS] [BLOCK CONTAINS ...] [LABEL RECORDS ...].
     * 01 record-name.
     *    05 field-name PIC ...
     * </pre>
     */
    private void parseFileSection(List<CobolProgram.DataEntry> entries,
                                  List<CobolProgram.FileBinding> fileBindings) {
        while (!atEnd() && !peek("WORKING-STORAGE") && !peek("LOCAL-STORAGE")
               && !peek("LINKAGE") && !peek("PROCEDURE")) {
            if (matchWord("FD")) {
                String fileName = current().value();
                advance(); // consume file name

                int recordSize = 0;
                // Skip FD clauses until period
                while (!atEnd() && !atPeriod()) {
                    if (matchWord("RECORD")) {
                        consumeIf("CONTAINS");
                        if (current().type() == Token.Type.NUMBER) {
                            recordSize = Integer.parseInt(current().value());
                            advance();
                        }
                        consumeIf("CHARACTERS");
                        consumeIf("CHARACTER");
                    } else {
                        // Skip other FD clauses: BLOCK CONTAINS, LABEL RECORDS, DATA RECORDS, etc.
                        advance();
                    }
                }
                consumePeriod();

                // Parse record definitions under this FD — they are 01-level entries
                String firstRecordName = null;
                while (!atEnd() && current().type() == Token.Type.NUMBER) {
                    int level = Integer.parseInt(current().value());
                    if (level < 1 || level > 88) break;

                    // Capture the 01-level record name before parsing the entry
                    if (level == 1 && firstRecordName == null) {
                        // The name follows the level number
                        if (pos + 1 < tokens.size()) {
                            firstRecordName = tokens.get(pos + 1).value();
                        }
                    }

                    entries.addAll(parseDataEntry(level));
                }

                if (firstRecordName != null) {
                    fileBindings.add(new CobolProgram.FileBinding(fileName, firstRecordName, recordSize));
                }
            } else if (matchWord("SD")) {
                // Sort/Merge file descriptors — skip like FD but don't bind
                advance(); // consume sort file name
                while (!atEnd() && !atPeriod()) advance();
                consumePeriod();
                // Skip record definitions under SD
                while (!atEnd() && current().type() == Token.Type.NUMBER) {
                    int level = Integer.parseInt(current().value());
                    if (level < 1 || level > 88) break;
                    entries.addAll(parseDataEntry(level));
                }
            } else {
                advance();
            }
        }
    }

    private List<CobolProgram.DataEntry> parseDataEntry(int level) {
        advance(); // consume level number

        if (level == 88) {
            return parse88Level();
        }

        String name = current().value();
        advance(); // consume name

        String pic = null;
        String usage = null;
        String value = null;
        String redefines = null;
        int occurs = 0;
        String dependingOn = null;
        String signClause = null;
        boolean isGlobal = false;
        boolean isExternal = false;

        // Parse clauses until period
        while (!atEnd() && !atPeriod()) {
            if (matchWord("PIC") || matchWord("PICTURE")) {
                consumeIf("IS");
                pic = parsePicString();
            } else if (matchWord("USAGE")) {
                consumeIf("IS");
                usage = parseUsage();
            } else if (matchWord("COMP") || matchWord("COMP-3") || matchWord("COMP-4")
                       || matchWord("COMP-5") || matchWord("COMPUTATIONAL")
                       || matchWord("COMPUTATIONAL-3") || matchWord("BINARY")
                       || matchWord("PACKED-DECIMAL")) {
                usage = tokens.get(pos - 1).value(); // the word we just matched
            } else if (matchWord("POINTER")) {
                usage = "POINTER";
            } else if (matchWord("FUNCTION-POINTER")) {
                usage = "FUNCTION-POINTER";
            } else if (matchWord("SIGN")) {
                consumeIf("IS");
                signClause = parseSignClause();
            } else if (matchWord("VALUE")) {
                consumeIf("IS");
                value = parseValueLiteral();
            } else if (matchWord("REDEFINES")) {
                redefines = current().value();
                advance();
            } else if (matchWord("OCCURS")) {
                occurs = Integer.parseInt(current().value());
                advance(); // consume count
                consumeIf("TIMES");
                if (matchWord("DEPENDING")) {
                    expect("ON");
                    dependingOn = current().value();
                    advance();
                }
            } else if (matchWord("GLOBAL")) {
                isGlobal = true;
                diag.info("parser", current().line(), "GLOBAL",
                    "GLOBAL clause on " + name + " — informational in transpiled code.");
            } else if (matchWord("EXTERNAL")) {
                isExternal = true;
                diag.info("parser", current().line(), "EXTERNAL",
                    "EXTERNAL clause on " + name + " — informational in transpiled code.");
            } else if (matchWord("IS") && (peek("GLOBAL") || peek("EXTERNAL"))) {
                // Handle "IS GLOBAL" / "IS EXTERNAL" variant
                continue;
            } else {
                diag.warning("parser", current().line(), current().value(),
                    "Unrecognized data clause '" + current().value()
                    + "' in field " + name + " — clause ignored.");
                advance();
            }
        }
        consumePeriod();

        // Collect any following 88-level entries
        List<CobolProgram.Condition88> conditions = new ArrayList<>();
        while (!atEnd() && current().type() == Token.Type.NUMBER
               && current().value().equals("88")) {
            List<CobolProgram.DataEntry> conds = parseDataEntry(88);
            for (CobolProgram.DataEntry c : conds) {
                conditions.addAll(c.conditions());
            }
        }

        CobolProgram.DataEntry entry = new CobolProgram.DataEntry(
            level, name, pic, usage, value, redefines, occurs, dependingOn,
            conditions, signClause, isGlobal, isExternal);
        return List.of(entry);
    }

    /**
     * Parse the SIGN clause: LEADING | TRAILING, optionally followed by SEPARATE [CHARACTER].
     * Returns one of: "TRAILING", "LEADING", "TRAILING_SEPARATE", "LEADING_SEPARATE".
     */
    private String parseSignClause() {
        boolean leading = false;
        if (matchWord("LEADING")) {
            leading = true;
        } else if (matchWord("TRAILING")) {
            leading = false;
        } else {
            // Default to trailing if neither specified
            return "TRAILING";
        }
        boolean separate = false;
        if (matchWord("SEPARATE")) {
            separate = true;
            consumeIf("CHARACTER");
        }
        if (leading) {
            return separate ? "LEADING_SEPARATE" : "LEADING";
        } else {
            return separate ? "TRAILING_SEPARATE" : "TRAILING";
        }
    }

    private List<CobolProgram.DataEntry> parse88Level() {
        String name = current().value();
        advance(); // consume condition name

        List<String> values = new ArrayList<>();
        String thruFrom = null, thruTo = null;

        expect("VALUE");
        consumeIf("IS");
        consumeIf("ARE");

        // Parse one or more values
        values.add(parseValueLiteral());
        while (!atEnd() && !atPeriod() && !peek("THRU") && !peek("THROUGH")) {
            if (current().type() == Token.Type.STRING || current().type() == Token.Type.NUMBER
                || current().type() == Token.Type.WORD) {
                values.add(parseValueLiteral());
            } else {
                break;
            }
        }

        if (matchWord("THRU") || matchWord("THROUGH")) {
            thruFrom = values.get(0);
            thruTo = parseValueLiteral();
            values.clear();
        }

        consumePeriod();

        CobolProgram.Condition88 cond = new CobolProgram.Condition88(name, values, thruFrom, thruTo);
        CobolProgram.DataEntry entry = new CobolProgram.DataEntry(
            88, name, null, null, null, null, 0, null, List.of(cond));
        return List.of(entry);
    }

    private String parsePicString() {
        StringBuilder sb = new StringBuilder();
        // PIC strings can contain multiple tokens: S9(7)V99
        while (!atEnd() && !atPeriod() && isPicChar(current())) {
            sb.append(current().value());
            advance();
        }
        return sb.toString();
    }

    private boolean isPicChar(Token t) {
        if (t.type() == Token.Type.WORD) {
            // PIC characters: S, 9, X, A, V, Z, B, P, N, etc.
            String v = t.value();
            // Reject COBOL keywords that could follow a PIC string
            if (v.equals("SIGN") || v.equals("VALUE") || v.equals("USAGE")
                || v.equals("COMP") || v.equals("COMP-3") || v.equals("COMP-4")
                || v.equals("COMP-5") || v.equals("COMPUTATIONAL")
                || v.equals("BINARY") || v.equals("PACKED-DECIMAL")
                || v.equals("OCCURS") || v.equals("REDEFINES")
                || v.equals("IS") || v.equals("TRAILING") || v.equals("LEADING")
                || v.equals("SEPARATE") || v.equals("CHARACTER")
                || v.equals("GLOBAL") || v.equals("EXTERNAL")
                || v.equals("POINTER") || v.equals("FUNCTION-POINTER")) {
                return false;
            }
            return v.matches("[SNXA9VZPB*+\\-$/,0]+")
                || v.matches("[SNXA9VZPB*+\\-$/,0]+(\\(\\d+\\))+");
        }
        if (t.type() == Token.Type.NUMBER) return true; // digits in PIC
        if (t.type() == Token.Type.LPAREN || t.type() == Token.Type.RPAREN) return true;
        return false;
    }

    private String parseUsage() {
        String u = current().value();
        advance();
        return u;
    }

    private String parseValueLiteral() {
        Token t = current();
        advance();
        if (t.type() == Token.Type.STRING) return "\"" + t.value() + "\"";
        if (t.type() == Token.Type.NUMBER) return t.value();
        if (t.is("SPACES") || t.is("SPACE")) return "SPACES";
        if (t.is("ZEROS") || t.is("ZEROES") || t.is("ZERO")) return "ZEROS";
        if (t.is("HIGH-VALUES") || t.is("HIGH-VALUE")) return "HIGH-VALUES";
        if (t.is("LOW-VALUES") || t.is("LOW-VALUE")) return "LOW-VALUES";
        return "\"" + t.value() + "\"";
    }

    // ── PROCEDURE DIVISION ──────────────────────────────────────────

    private List<CobolProgram.Paragraph> parseProcedureDivision() {
        expect("DIVISION");
        consumeIf("USING");  // skip USING clause if present
        while (!atEnd() && !atPeriod()) advance();
        consumePeriod();

        List<CobolProgram.Paragraph> paragraphs = new ArrayList<>();

        while (!atEnd()) {
            // A paragraph starts with a WORD followed by a PERIOD
            if (current().type() == Token.Type.WORD && pos + 1 < tokens.size()
                && tokens.get(pos + 1).type() == Token.Type.PERIOD) {
                String name = current().value();
                advance(); // consume name
                consumePeriod();
                List<Statement> stmts = parseStatements();
                paragraphs.add(new CobolProgram.Paragraph(name, stmts));
            } else {
                advance();
            }
        }
        return paragraphs;
    }

    private List<Statement> parseStatements() {
        List<Statement> stmts = new ArrayList<>();
        while (!atEnd()) {
            // Check if next is a paragraph header (WORD followed by PERIOD at start)
            if (current().type() == Token.Type.WORD && pos + 1 < tokens.size()
                && tokens.get(pos + 1).type() == Token.Type.PERIOD
                && !isVerb(current().value())) {
                break; // next paragraph starts
            }
            try {
                Statement stmt = parseStatement();
                if (stmt != null) stmts.add(stmt);
            } catch (ParseException e) {
                // Expected parse failure — already reported via diag.
                // Recover to next sync point and continue.
                skipToSyncPoint();
            } catch (RuntimeException e) {
                // Unexpected failure (NPE, index bounds, etc.) — capture it
                // so the user sees what broke and the parser keeps going.
                diag.error("parser", current().line(), current().value(),
                    "Internal parser error near '" + current().value()
                    + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
                skipToSyncPoint();
            }
        }
        return stmts;
    }

    private Statement parseStatement() {
        Token t = current();
        if (t.type() == Token.Type.PERIOD) { advance(); return null; }
        if (t.type() == Token.Type.EOF) return null;

        return switch (t.value().toUpperCase()) {
            // ── Implemented verbs ───────────────────────────────────
            case "MOVE"       -> parseMove();
            case "ADD"        -> parseAdd();
            case "SUBTRACT"   -> parseSubtract();
            case "MULTIPLY"   -> parseMultiply();
            case "DIVIDE"     -> parseDivide();
            case "COMPUTE"    -> parseCompute();
            case "IF"         -> parseIf();
            case "EVALUATE"   -> parseEvaluate();
            case "PERFORM"    -> parsePerform();
            case "DISPLAY"    -> parseDisplay();
            case "ACCEPT"     -> parseAccept();
            case "OPEN"       -> parseOpen();
            case "CLOSE"      -> parseClose();
            case "READ"       -> parseRead();
            case "WRITE"      -> parseWrite();
            case "GO"         -> parseGoTo();
            case "STOP"       -> parseStopRun();
            case "SET"        -> parseSet();
            case "INITIALIZE" -> parseInitialize();
            case "EXIT"       -> parseExit();
            case "EXEC"       -> parseExecSql();
            case "CALL"       -> parseCall();
            case "INVOKE"     -> parseInvoke();
            case "CONTINUE"   -> { advance(); consumeStatementEnd(); yield new Statement.Continue(); }

            case "INSPECT"    -> parseInspect();
            case "STRING"     -> parseString();
            case "UNSTRING"   -> parseUnstring();
            case "SEARCH"     -> parseSearch();
            case "SORT"       -> parseSort();
            case "REWRITE"    -> parseRewrite();
            case "DELETE"     -> parseDelete();
            case "MERGE"      -> parseMerge();
            case "XML"        -> parseXmlJsonVerb("XML");
            case "JSON"       -> parseXmlJsonVerb("JSON");

            // ── Implemented as synonyms ────────────────────────────
            case "GOBACK"     -> { advance(); consumeStatementEnd(); yield new Statement.StopRun(); }

            // ── Known but not yet fully implemented ────────────────
            case "START"      -> parseUnsupported("START",
                "Keyed file positioning. Use CobolFile API: file.start(mode, key)");
            case "ALTER"      -> parseUnsupported("ALTER",
                "Modify GO TO target at runtime. Refactor to use IF/EVALUATE instead");
            case "CANCEL"     -> parseUnsupported("CANCEL",
                "Release called program. In Java, this is managed by the classloader");

            // ── Truly unknown ───────────────────────────────────────
            default -> {
                String rawCobol = captureToSyncPoint(t);
                diag.warning("parser", t.line(), t.value(),
                    "Unrecognized verb '" + t.value()
                    + "' — a COBOL4J_UNSUPPORTED marker will be generated");
                yield new Statement.Unsupported(t.value(), rawCobol, t.line(),
                    "Unknown COBOL verb. Check spelling or dialect-specific extensions");
            }
        };
    }

    // ── Statement parsers ───────────────────────────────────────────

    private Statement parseMove() {
        advance(); // consume MOVE
        if (matchWord("CORRESPONDING") || matchWord("CORR")) {
            String source = current().value(); advance();
            expect("TO");
            String target = current().value(); advance();
            consumeStatementEnd();
            return new Statement.MoveCorresponding(source, target);
        }
        Expr source = parseExpr();
        expect("TO");
        List<String> targets = new ArrayList<>();
        while (!atEnd() && !atPeriod()
               && !isExceptionPhrase()
               && !isVerb(current().value())
               && !isScopeTerminator(current().value())) {
            targets.add(readOperand()); // handles FIELD(subscript) and FIELD(pos:len)
        }
        consumeStatementEnd();
        return new Statement.Move(source, targets);
    }

    private Statement parseAdd() {
        advance(); // consume ADD
        List<Expr> sources = new ArrayList<>();
        while (!atEnd() && !peek("TO") && !peek("GIVING")) {
            sources.add(parseExpr());
        }
        List<String> targets = new ArrayList<>();
        String giving = null;
        boolean rounded = false;
        if (matchWord("TO")) {
            while (!atEnd() && !atPeriod() && !peek("GIVING") && !peek("ROUNDED")
                   && !peek("ON") && !peek("NOT")
                   && !isVerb(current().value())
                   && !isScopeTerminator(current().value())) {
                targets.add(readOperand());
            }
        }
        List<String> givingTargets = parseGivingTargets();
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Add(sources, targets, givingTargets, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseSubtract() {
        advance(); // consume SUBTRACT
        List<Expr> subtrahends = new ArrayList<>();
        while (!atEnd() && !peek("FROM")) {
            subtrahends.add(parseExpr());
        }
        expect("FROM");
        List<String> targets = new ArrayList<>();
        while (!atEnd() && !atPeriod() && !peek("GIVING") && !peek("ROUNDED")
               && !peek("ON") && !peek("NOT")
               && !isVerb(current().value())
               && !isScopeTerminator(current().value())) {
            targets.add(readOperand());
        }
        boolean rounded = false;
        List<String> givingTargets = parseGivingTargets();
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Subtract(subtrahends, targets, givingTargets, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseMultiply() {
        advance(); // consume MULTIPLY
        Expr a = parseExpr();
        expect("BY");
        Expr by = parseExpr();
        boolean rounded = false;
        List<String> givingTargets = parseGivingTargets();
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Multiply(a, by, givingTargets, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseDivide() {
        advance(); // consume DIVIDE
        Expr dividend = parseExpr();
        String divisorWord = current().value(); advance(); // INTO or BY
        Expr divisor = parseExpr();
        // Swap if "DIVIDE X INTO Y" (COBOL: Y / X)
        if (divisorWord.equalsIgnoreCase("INTO")) {
            Expr temp = dividend; dividend = divisor; divisor = temp;
        }
        String remainder = null;
        boolean rounded = false;
        List<String> givingTargets = parseGivingTargets();
        if (matchWord("REMAINDER")) { remainder = readOperand(); }
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Divide(dividend, divisor, givingTargets, remainder, rounded, onSize.get(0), onSize.get(1));
    }

    /** Parse GIVING target1 [target2 ...] — returns empty list if no GIVING. */
    private List<String> parseGivingTargets() {
        if (!matchWord("GIVING")) return List.of();
        List<String> targets = new ArrayList<>();
        while (!atEnd() && !atPeriod() && !peek("ROUNDED") && !peek("REMAINDER")
               && !peek("ON") && !peek("NOT")
               && !isVerb(current().value())
               && !isScopeTerminator(current().value())) {
            targets.add(readOperand());
        }
        return targets;
    }

    private Statement parseCompute() {
        advance(); // consume COMPUTE
        String target = current().value(); advance();
        boolean rounded = false;
        if (matchWord("ROUNDED")) rounded = true;
        expect("=");
        Expr expression = parseArithmeticExpr();
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Compute(target, expression, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseIf() {
        advance(); // consume IF
        Statement.Condition cond = parseCondition();
        consumeIf("THEN");
        List<Statement> thenBlock = new ArrayList<>();
        List<Statement> elseBlock = new ArrayList<>();

        while (!atEnd() && !peek("ELSE") && !peek("END-IF")) {
            Statement s = parseStatement();
            if (s != null) thenBlock.add(s);
        }
        if (matchWord("ELSE")) {
            while (!atEnd() && !peek("END-IF")) {
                Statement s = parseStatement();
                if (s != null) elseBlock.add(s);
            }
        }
        matchWord("END-IF");
        consumeStatementEnd();
        return new Statement.If(cond, thenBlock, elseBlock);
    }

    /**
     * Parse EVALUATE statement.
     * Grammar:
     *   evaluate-stmt  := EVALUATE subject when-group+ END-EVALUATE
     *   subject        := TRUE | identifier | literal
     *   when-group     := WHEN (OTHER | when-value) statement*
     *   when-value     := literal | identifier (condition name or field value)
     */
    private Statement parseEvaluate() {
        advance(); // consume EVALUATE

        // Parse subject: TRUE, a field name, or a literal
        String subject = parseEvaluateSubject();

        List<Statement.WhenClause> whens = new ArrayList<>();
        List<Statement> whenOther = new ArrayList<>();

        // Parse WHEN clauses until END-EVALUATE or period
        while (!atEnd() && !atPeriod() && peek("WHEN")) {
            advance(); // consume WHEN

            // Check for WHEN OTHER (default case)
            if (peek("OTHER")) {
                advance(); // consume OTHER
                whenOther = parseWhenBody();
                break; // OTHER is always last
            }

            // Collect WHEN values — may be multiple (fall-through):
            //   WHEN "A" WHEN "B" WHEN "C" → same body for all
            //   WHEN 1 THRU 10 → range
            List<Statement.WhenValue> values = new ArrayList<>();
            values.add(parseOneWhenValue());

            // Check for additional WHEN values (fall-through) with no body between them
            while (!atEnd() && peek("WHEN")
                   && !isWhenFollowedByBody()) {
                advance(); // consume WHEN
                if (peek("OTHER")) break; // don't consume OTHER here
                values.add(parseOneWhenValue());
            }

            // Parse the WHEN body — statements until next WHEN or END-EVALUATE
            List<Statement> body = parseWhenBody();

            whens.add(new Statement.WhenClause(values, body));
        }

        matchWord("END-EVALUATE");
        consumeStatementEnd();
        return new Statement.Evaluate(subject, whens, whenOther);
    }

    private String parseEvaluateSubject() {
        Token t = current();
        if (t.is("TRUE")) {
            advance();
            return "TRUE";
        }
        if (t.type() == Token.Type.STRING) {
            String val = "\"" + t.value() + "\"";
            advance();
            return val;
        }
        if (t.type() == Token.Type.NUMBER) {
            String val = t.value();
            advance();
            return val;
        }
        // Identifier (field name)
        String name = t.value();
        advance();
        return name;
    }

    private Statement.WhenValue parseOneWhenValue() {
        String value = parseWhenValueToken();
        // Check for THRU / THROUGH (range)
        if (matchWord("THRU") || matchWord("THROUGH")) {
            String thruEnd = parseWhenValueToken();
            return new Statement.WhenValue(value, thruEnd);
        }
        return new Statement.WhenValue(value);
    }

    private String parseWhenValueToken() {
        Token t = current();
        if (t.type() == Token.Type.STRING) {
            String val = "\"" + t.value() + "\"";
            advance();
            return val;
        }
        if (t.type() == Token.Type.NUMBER) {
            String val = t.value();
            advance();
            return val;
        }
        String name = t.value();
        advance();
        return name;
    }

    /**
     * Peek ahead to determine if the next WHEN is a fall-through (another value)
     * or starts a new clause with a body. If the token after WHEN's value is
     * a verb or another WHEN, it's fall-through. If it's a statement, there's a body.
     */
    private boolean isWhenFollowedByBody() {
        // Save position to peek ahead
        int saved = pos;
        if (!peek("WHEN")) return true;
        advance(); // skip WHEN
        if (peek("OTHER")) { pos = saved; return true; } // WHEN OTHER has a body
        // Skip the value token(s)
        if (current().type() == Token.Type.STRING || current().type() == Token.Type.NUMBER
            || current().type() == Token.Type.WORD) {
            advance(); // skip value
            // Check for THRU
            if (peek("THRU") || peek("THROUGH")) {
                advance(); advance(); // skip THRU + end value
            }
        }
        // If next is WHEN or END-EVALUATE → this WHEN has no body (fall-through)
        boolean hasBody = !peek("WHEN") && !peek("END-EVALUATE") && !atPeriod();
        pos = saved;
        return hasBody;
    }

    private List<Statement> parseWhenBody() {
        List<Statement> body = new ArrayList<>();
        while (!atEnd() && !atPeriod()
               && !peek("WHEN")
               && !peek("END-EVALUATE")) {
            try {
                Statement s = parseStatement();
                if (s != null) body.add(s);
            } catch (ParseException e) {
                skipToSyncPoint();
            } catch (RuntimeException e) {
                diag.error("parser", current().line(), current().value(),
                    "Error in EVALUATE WHEN body: " + e.getMessage());
                skipToSyncPoint();
            }
        }
        return body;
    }

    private Statement parsePerform() {
        advance(); // consume PERFORM

        // Check for inline PERFORM (no paragraph name — UNTIL/VARYING/TIMES comes immediately)
        if (peek("UNTIL") || peek("VARYING")) {
            return parseInlinePerform();
        }

        String paragraph = current().value(); advance();
        String thru = null;
        Statement.PerformType type = Statement.PerformType.SIMPLE;
        String varying = null, from = null, by = null;
        Statement.Condition until = null;

        if (matchWord("THRU") || matchWord("THROUGH")) {
            thru = current().value(); advance();
            type = Statement.PerformType.THRU;
        }
        if (current().type() == Token.Type.NUMBER && peek(1, "TIMES")) {
            from = current().value(); advance();
            advance(); // consume TIMES
            type = Statement.PerformType.TIMES;
        } else if (matchWord("UNTIL")) {
            until = parseCondition();
            type = Statement.PerformType.UNTIL;
        } else if (matchWord("VARYING")) {
            varying = current().value(); advance();
            expect("FROM"); from = readOperand();
            expect("BY"); by = readOperand();
            expect("UNTIL");
            until = parseCondition();
            type = Statement.PerformType.VARYING;
        }
        consumeStatementEnd();
        return new Statement.Perform(paragraph, thru, type, varying, from, by, until);
    }

    private Statement parseInlinePerform() {
        Statement.Condition until = null;
        if (matchWord("UNTIL")) {
            until = parseCondition();
        }
        // Collect inline statements until END-PERFORM
        List<Statement> body = new ArrayList<>();
        while (!atEnd() && !peek("END-PERFORM")) {
            try {
                Statement s = parseStatement();
                if (s != null) body.add(s);
            } catch (ParseException e) {
                skipToSyncPoint();
            }
        }
        matchWord("END-PERFORM");
        consumeStatementEnd();
        return new Statement.InlinePerform(until, body);
    }

    private Statement parseDisplay() {
        advance(); // consume DISPLAY
        List<Expr> items = new ArrayList<>();
        while (!atEnd() && !atPeriod()
               && !peek("UPON")
               && !isExceptionPhrase()
               && !isVerb(current().value())
               && !isScopeTerminator(current().value())) {
            items.add(parseExpr());
        }
        // Parse optional UPON device-name
        String upon = null;
        if (matchWord("UPON")) {
            upon = current().value();
            advance(); // consume device name (CONSOLE, SYSPUNCH, SYSOUT, etc.)
        }
        consumeStatementEnd();
        return new Statement.Display(items, upon);
    }

    private Statement parseAccept() {
        advance(); // consume ACCEPT
        String target = current().value(); advance();
        // ACCEPT target FROM DATE / TIME / DAY / DAY-OF-WEEK
        String from = null;
        if (matchWord("FROM")) {
            if (peek("DATE") || peek("TIME") || peek("DAY") || peek("DAY-OF-WEEK")) {
                from = current().value(); advance();
            }
        }
        consumeStatementEnd();
        return new Statement.Accept(target, from);
    }

    private Statement parseOpen() {
        advance(); // consume OPEN
        String mode = current().value(); advance(); // INPUT, OUTPUT, I-O, EXTEND
        String file = current().value(); advance();
        consumeStatementEnd();
        return new Statement.Open(mode, file);
    }

    private Statement parseClose() {
        advance(); // consume CLOSE
        String file = current().value(); advance();
        consumeStatementEnd();
        return new Statement.Close(file);
    }

    private Statement parseRead() {
        advance(); // consume READ
        String file = current().value(); advance();
        String into = null;
        List<Statement> atEnd = new ArrayList<>();
        List<Statement> notAtEnd = new ArrayList<>();

        if (matchWord("INTO")) { into = current().value(); advance(); }
        if (matchWord("AT") && matchWord("END")) {
            while (!atEnd() && !peek("NOT") && !peek("END-READ") && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) atEnd.add(s);
            }
        }
        if (matchWord("NOT") && matchWord("AT") && matchWord("END")) {
            while (!atEnd() && !peek("END-READ") && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) notAtEnd.add(s);
            }
        }
        matchWord("END-READ");
        consumeStatementEnd();
        return new Statement.Read(file, into, atEnd, notAtEnd);
    }

    private Statement parseWrite() {
        advance(); // consume WRITE
        String rec = current().value(); advance();
        String from = null;
        if (matchWord("FROM")) { from = current().value(); advance(); }
        consumeStatementEnd();
        return new Statement.Write(rec, from);
    }

    private Statement parseGoTo() {
        advance(); // consume GO
        matchWord("TO");
        String target = current().value(); advance();
        consumeStatementEnd();
        return new Statement.GoTo(target);
    }

    private Statement parseStopRun() {
        advance(); // consume STOP
        expect("RUN");
        consumeStatementEnd();
        return new Statement.StopRun();
    }

    private Statement parseSet() {
        advance(); // consume SET
        String name = current().value(); advance();

        // SET index-name UP BY / DOWN BY value
        if (peek("UP") || peek("DOWN")) {
            String direction = current().value(); advance(); // UP or DOWN
            expect("BY");
            Expr value = parseExpr();
            consumeStatementEnd();
            return new Statement.SetIndex(name, direction, value);
        }

        expect("TO");
        advance(); // consume TRUE or value
        consumeStatementEnd();
        return new Statement.SetCondition(name);
    }

    private Statement parseInitialize() {
        advance(); // consume INITIALIZE
        String target = current().value(); advance();
        consumeStatementEnd();
        return new Statement.Initialize(target);
    }

    private Statement parseExit() {
        advance(); // consume EXIT
        if (matchWord("PARAGRAPH")) {
            consumeStatementEnd();
            return new Statement.ExitParagraph();
        }
        if (matchWord("PROGRAM")) {
            consumeStatementEnd();
            return new Statement.StopRun(); // EXIT PROGRAM = return from subprogram
        }
        consumeStatementEnd();
        return null; // EXIT alone = no-op
    }

    private Statement parseCall() {
        advance(); // consume CALL
        // Target: either a string literal ("open") or a variable (PROGRAM-NAME)
        String target;
        if (current().type() == Token.Type.STRING) {
            target = current().value();
        } else {
            target = current().value();
        }
        advance();

        // Parse USING clause
        List<Statement.CallParam> params = new ArrayList<>();
        if (matchWord("USING")) {
            Statement.PassMode mode = Statement.PassMode.BY_REFERENCE; // COBOL default
            while (!atEnd() && !atPeriod() && !peek("RETURNING") && !peek("END-CALL")
                   && !peek("ON") && !peek("NOT")
                   && !isVerb(current().value())) {
                if (matchWord("BY")) {
                    if (matchWord("REFERENCE")) mode = Statement.PassMode.BY_REFERENCE;
                    else if (matchWord("CONTENT")) mode = Statement.PassMode.BY_CONTENT;
                    else if (matchWord("VALUE")) mode = Statement.PassMode.BY_VALUE;
                    continue;
                }
                String paramValue;
                if (current().type() == Token.Type.STRING) {
                    paramValue = "\"" + current().value() + "\"";
                } else if (current().type() == Token.Type.NUMBER) {
                    paramValue = current().value();
                } else {
                    paramValue = current().value();
                }
                advance();
                params.add(new Statement.CallParam(paramValue, mode));
            }
        }

        // Parse RETURNING clause
        String returning = null;
        if (matchWord("RETURNING")) {
            returning = current().value();
            advance();
        }

        // Parse ON EXCEPTION / NOT ON EXCEPTION
        List<Statement> onException = new ArrayList<>();
        List<Statement> notOnException = new ArrayList<>();
        if (matchWord("ON") && matchWord("EXCEPTION")) {
            while (!atEnd() && !peek("NOT") && !peek("END-CALL") && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) onException.add(s);
            }
        }
        if (matchWord("NOT") && matchWord("ON") && matchWord("EXCEPTION")) {
            while (!atEnd() && !peek("END-CALL") && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) notOnException.add(s);
            }
        }

        matchWord("END-CALL");
        consumeStatementEnd();
        return new Statement.Call(target, params, returning, onException, notOnException);
    }

    // ── INSPECT ──────────────────────────────────────────────────

    private Statement parseInspect() {
        advance(); // consume INSPECT
        String target = current().value(); advance();

        String before = null, after = null;

        if (matchWord("TALLYING")) {
            String tallyField = current().value(); advance();
            expect("FOR");
            String tallyType = "ALL"; // default
            if (matchWord("ALL")) tallyType = "ALL";
            else if (matchWord("LEADING")) tallyType = "LEADING";
            else if (matchWord("CHARACTERS")) {
                tallyType = "CHARACTERS";
                parseScopeModifiers(); // consume BEFORE/AFTER
                consumeStatementEnd();
                return new Statement.InspectTallying(target, tallyField, tallyType, null, null, null);
            }
            String tallyArg = parseValueLiteral();
            String[] scope = parseScopeModifiers();
            consumeStatementEnd();
            return new Statement.InspectTallying(target, tallyField, tallyType, tallyArg,
                scope[0], scope[1]);
        }

        if (matchWord("REPLACING")) {
            String replaceType = "ALL";
            if (matchWord("ALL")) replaceType = "ALL";
            else if (matchWord("LEADING")) replaceType = "LEADING";
            else if (matchWord("FIRST")) replaceType = "FIRST";
            String from = parseValueLiteral();
            expect("BY");
            String to = parseValueLiteral();
            String[] scope = parseScopeModifiers();
            consumeStatementEnd();
            return new Statement.InspectReplacing(target, replaceType, from, to,
                scope[0], scope[1]);
        }

        if (matchWord("CONVERTING")) {
            String from = parseValueLiteral();
            expect("TO");
            String to = parseValueLiteral();
            String[] scope = parseScopeModifiers();
            consumeStatementEnd();
            return new Statement.InspectConverting(target, from, to, scope[0], scope[1]);
        }

        // Fallback: skip to period
        diag.warning("parser", current().line(), "INSPECT",
            "INSPECT variant not recognized — skipped.");
        skipToSyncPoint();
        return null;
    }

    private String[] parseScopeModifiers() {
        String before = null, after = null;
        if (matchWord("BEFORE")) {
            consumeIf("INITIAL");
            before = parseValueLiteral();
        }
        if (matchWord("AFTER")) {
            consumeIf("INITIAL");
            after = parseValueLiteral();
        }
        return new String[]{before, after};
    }

    // ── STRING ──────────────────────────────────────────────────

    private Statement parseString() {
        advance(); // consume STRING
        List<Statement.StringSource> sources = new ArrayList<>();

        while (!atEnd() && !peek("INTO")) {
            String value;
            if (current().type() == Token.Type.STRING) {
                value = "\"" + current().value() + "\"";
                advance();
            } else {
                value = current().value();
                advance();
            }
            String delimiter = null;
            if (matchWord("DELIMITED")) {
                expect("BY");
                if (matchWord("SIZE")) {
                    delimiter = "SIZE";
                } else if (matchWord("SPACES") || matchWord("SPACE")) {
                    delimiter = "SPACES";
                } else {
                    delimiter = parseValueLiteral();
                }
            }
            sources.add(new Statement.StringSource(value, delimiter));
        }

        expect("INTO");
        String into = current().value(); advance();
        String pointer = null;
        if (matchWord("WITH")) {
            expect("POINTER");
            pointer = current().value(); advance();
        }
        // Skip ON OVERFLOW / NOT ON OVERFLOW for now
        while (!atEnd() && !atPeriod() && !peek("END-STRING") && !isVerb(current().value())) {
            advance();
        }
        matchWord("END-STRING");
        consumeStatementEnd();
        return new Statement.StringStmt(into, sources, pointer);
    }

    // ── UNSTRING ────────────────────────────────────────────────

    private Statement parseUnstring() {
        advance(); // consume UNSTRING
        String source = current().value(); advance();

        List<String> delimiters = new ArrayList<>();
        if (matchWord("DELIMITED")) {
            expect("BY");
            if (matchWord("ALL")) { /* optional ALL */ }
            delimiters.add(parseValueLiteral());
            while (matchWord("OR")) {
                if (matchWord("ALL")) { /* optional ALL */ }
                delimiters.add(parseValueLiteral());
            }
        }

        List<String> into = new ArrayList<>();
        expect("INTO");
        while (!atEnd() && !atPeriod() && !peek("WITH") && !peek("TALLYING")
               && !peek("END-UNSTRING") && !peek("ON") && !isVerb(current().value())) {
            into.add(current().value());
            advance();
        }

        String pointer = null;
        if (matchWord("WITH")) {
            expect("POINTER");
            pointer = current().value(); advance();
        }
        String tally = null;
        if (matchWord("TALLYING")) {
            consumeIf("IN");
            tally = current().value(); advance();
        }
        matchWord("END-UNSTRING");
        consumeStatementEnd();
        return new Statement.UnstringStmt(source, delimiters, into, pointer, tally);
    }

    // ── SEARCH ──────────────────────────────────────────────────

    private Statement parseSearch() {
        advance(); // consume SEARCH
        boolean searchAll = matchWord("ALL");
        String table = current().value(); advance();

        List<Statement> atEnd = new ArrayList<>();
        List<Statement.SearchWhen> whenClauses = new ArrayList<>();

        if (matchWord("AT") && matchWord("END")) {
            while (!atEnd() && !peek("WHEN") && !peek("END-SEARCH") && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) atEnd.add(s);
            }
        }

        while (matchWord("WHEN")) {
            String left = readOperand(); // handles FIELD(subscript)
            String right = null;
            if (matchWord("=") || matchWord("EQUAL")) {
                consumeIf("TO");
                right = readOperand();
            }
            List<Statement> body = new ArrayList<>();
            while (!atEnd() && !peek("WHEN") && !peek("END-SEARCH") && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) body.add(s);
            }
            whenClauses.add(new Statement.SearchWhen(left, right, body));
        }

        matchWord("END-SEARCH");
        consumeStatementEnd();
        return new Statement.SearchStmt(table, whenClauses, atEnd);
    }

    // ── SORT ────────────────────────────────────────────────────

    private Statement parseSort() {
        advance(); // consume SORT
        String sortFile = current().value(); advance();

        List<Statement.SortKey> keys = new ArrayList<>();
        while (matchWord("ON") || matchWord("ASCENDING") || matchWord("DESCENDING")) {
            boolean ascending;
            if (current().is("ASCENDING") || tokens.get(pos - 1).is("ASCENDING")) {
                ascending = true;
                if (current().is("ASCENDING")) advance();
            } else {
                ascending = false;
                if (current().is("DESCENDING")) advance();
            }
            consumeIf("KEY");
            while (!atEnd() && !peek("ON") && !peek("ASCENDING") && !peek("DESCENDING")
                   && !peek("WITH") && !peek("DUPLICATES")
                   && !peek("USING") && !peek("GIVING") && !peek("INPUT")
                   && !peek("OUTPUT") && !atPeriod()) {
                keys.add(new Statement.SortKey(current().value(), ascending));
                advance();
            }
        }

        // Parse optional WITH DUPLICATES IN ORDER
        boolean duplicatesInOrder = false;
        if (matchWord("WITH")) {
            if (matchWord("DUPLICATES")) {
                consumeIf("IN");
                consumeIf("ORDER");
                duplicatesInOrder = true;
                diag.info("parser", current().line(), "SORT DUPLICATES",
                    "WITH DUPLICATES IN ORDER acknowledged — Java's sort is already stable (TimSort).");
            }
        } else if (matchWord("DUPLICATES")) {
            consumeIf("IN");
            consumeIf("ORDER");
            duplicatesInOrder = true;
            diag.info("parser", current().line(), "SORT DUPLICATES",
                "DUPLICATES IN ORDER acknowledged — Java's sort is already stable (TimSort).");
        }

        String using = null, giving = null;
        boolean hasInputProc = false, hasOutputProc = false;
        String inputProc = null, outputProc = null;

        if (matchWord("USING")) { using = current().value(); advance(); }
        if (matchWord("INPUT")) {
            expect("PROCEDURE");
            consumeIf("IS");
            hasInputProc = true;
            inputProc = current().value(); advance();
        }
        if (matchWord("GIVING")) { giving = current().value(); advance(); }
        if (matchWord("OUTPUT")) {
            expect("PROCEDURE");
            consumeIf("IS");
            hasOutputProc = true;
            outputProc = current().value(); advance();
        }

        consumeStatementEnd();
        return new Statement.SortStmt(sortFile, keys, using, giving,
            hasInputProc, inputProc, hasOutputProc, outputProc, duplicatesInOrder);
    }

    // ── MERGE ────────────────────────────────────────────────────

    private Statement parseMerge() {
        advance(); // consume MERGE — same structure as SORT
        String mergeFile = current().value(); advance();

        List<Statement.SortKey> keys = new ArrayList<>();
        while (matchWord("ON") || matchWord("ASCENDING") || matchWord("DESCENDING")) {
            boolean ascending;
            if (current().is("ASCENDING") || tokens.get(pos - 1).is("ASCENDING")) {
                ascending = true;
                if (current().is("ASCENDING")) advance();
            } else {
                ascending = false;
                if (current().is("DESCENDING")) advance();
            }
            consumeIf("KEY");
            while (!atEnd() && !peek("ON") && !peek("ASCENDING") && !peek("DESCENDING")
                   && !peek("USING") && !peek("GIVING") && !peek("OUTPUT") && !atPeriod()) {
                keys.add(new Statement.SortKey(current().value(), ascending));
                advance();
            }
        }

        String using = null, giving = null;
        boolean hasOutputProc = false;
        String outputProc = null;

        if (matchWord("USING")) {
            using = current().value(); advance();
            // MERGE can have multiple USING files — collect them
            while (!atEnd() && !peek("GIVING") && !peek("OUTPUT") && !atPeriod()
                   && !isVerb(current().value())) {
                advance(); // additional USING files
            }
        }
        if (matchWord("GIVING")) { giving = current().value(); advance(); }
        if (matchWord("OUTPUT")) {
            expect("PROCEDURE");
            consumeIf("IS");
            hasOutputProc = true;
            outputProc = current().value(); advance();
        }

        consumeStatementEnd();
        return new Statement.SortStmt(mergeFile, keys, using, giving,
            false, null, hasOutputProc, outputProc);
    }

    // ── REWRITE / DELETE ────────────────────────────────────────

    private Statement parseRewrite() {
        advance(); // consume REWRITE
        String rec = current().value(); advance();
        String from = null;
        if (matchWord("FROM")) { from = current().value(); advance(); }
        consumeStatementEnd();
        return new Statement.Rewrite(rec, from);
    }

    private Statement parseDelete() {
        advance(); // consume DELETE
        String file = current().value(); advance();
        consumeStatementEnd();
        return new Statement.Delete(file);
    }

    /**
     * Parse XML GENERATE/PARSE or JSON GENERATE/PARSE.
     * Grammar:
     *   XML GENERATE target FROM record [COUNT IN counter] END-XML
     *   XML PARSE source INTO record END-XML
     *   JSON GENERATE target FROM record [COUNT IN counter] END-JSON
     *   JSON PARSE source INTO record END-JSON
     */
    private Statement parseXmlJsonVerb(String format) {
        advance(); // consume XML or JSON
        String action = current().value(); advance(); // GENERATE or PARSE

        if (action.equalsIgnoreCase("GENERATE")) {
            String target = current().value(); advance();
            expect("FROM");
            String record = current().value(); advance();
            // Skip optional COUNT IN, NAME, TYPE, SUPPRESS, etc.
            while (!atEnd() && !atPeriod()
                   && !peek("END-XML") && !peek("END-JSON")
                   && !isVerb(current().value())) {
                advance();
            }
            matchWord("END-XML"); matchWord("END-JSON");
            consumeStatementEnd();
            return new Statement.CodecVerb(format, "GENERATE", record, target);
        } else if (action.equalsIgnoreCase("PARSE")) {
            String source = current().value(); advance();
            String record = null;
            if (matchWord("INTO")) {
                record = current().value(); advance();
            }
            while (!atEnd() && !atPeriod()
                   && !peek("END-XML") && !peek("END-JSON")
                   && !isVerb(current().value())) {
                advance();
            }
            matchWord("END-XML"); matchWord("END-JSON");
            consumeStatementEnd();
            return new Statement.CodecVerb(format, "PARSE", record, source);
        }

        // Unknown action
        diag.warning("parser", current().line(), format,
            "Unknown " + format + " action: " + action);
        skipToSyncPoint();
        return null;
    }

    /**
     * Parse INVOKE — OO COBOL method call.
     * Grammar:
     *   INVOKE object "method" [USING arg ...] [RETURNING result]
     *   INVOKE className "new" [USING arg ...] RETURNING newObject
     */
    private Statement parseInvoke() {
        advance(); // consume INVOKE
        String object = current().value(); advance(); // object or class name

        // Method name is a string literal
        String method;
        if (current().type() == Token.Type.STRING) {
            method = current().value();
            advance();
        } else {
            method = current().value();
            advance();
        }

        // Parse USING arguments
        List<String> args = new ArrayList<>();
        if (matchWord("USING")) {
            while (!atEnd() && !atPeriod() && !peek("RETURNING")
                   && !isVerb(current().value())
                   && !isScopeTerminator(current().value())) {
                args.add(readOperand());
            }
        }

        // Parse RETURNING
        String returning = null;
        if (matchWord("RETURNING")) {
            returning = current().value();
            advance();
        }

        consumeStatementEnd();
        return new Statement.Invoke(object, method, args, returning);
    }

    private Statement parseExecSql() {
        advance(); // consume EXEC
        expect("SQL");
        StringBuilder sql = new StringBuilder();
        while (!atEnd() && !peek("END-EXEC")) {
            sql.append(current().value()).append(" ");
            advance();
        }
        matchWord("END-EXEC");
        consumeStatementEnd();
        return new Statement.ExecSql(sql.toString().trim());
    }

    // ── Condition parsing ───────────────────────────────────────────
    // Grammar:
    //   condition      := or-condition
    //   or-condition   := and-condition (OR and-condition)*
    //   and-condition  := primary-condition (AND primary-condition)*
    //   primary-condition := NOT primary-condition
    //                      | identifier relop operand
    //                      | condition-name (88-level test)

    private Statement.Condition parseCondition() {
        return parseOrCondition();
    }

    private Statement.Condition parseOrCondition() {
        Statement.Condition left = parseAndCondition();
        while (peek("OR") && !peekAhead("EQUAL") && !peekAhead("EQUAL")) {
            // Distinguish "OR" as boolean connector from "GREATER THAN OR EQUAL TO"
            // If OR is followed by EQUAL, it's part of >=, not a boolean OR
            advance(); // consume OR
            Statement.Condition right = parseAndCondition();
            left = new Statement.Condition.Or(left, right);
        }
        return left;
    }

    private Statement.Condition parseAndCondition() {
        Statement.Condition left = parsePrimaryCondition();
        while (matchWord("AND")) {
            Statement.Condition right = parsePrimaryCondition();
            left = new Statement.Condition.And(left, right);
        }
        return left;
    }

    private Statement.Condition parsePrimaryCondition() {
        // NOT — unary negation, recurses into parsePrimaryCondition
        if (matchWord("NOT")) {
            Statement.Condition inner = parsePrimaryCondition();
            // Apply negation: for Simple/ConditionName, flip their negated flag.
            // For compound (And/Or), wrap it — we need a negate helper.
            return negateCondition(inner);
        }

        // Parenthesized sub-condition — recurse to top level
        if (current().type() == Token.Type.LPAREN) {
            advance(); // consume (
            Statement.Condition inner = parseOrCondition();
            if (current().type() == Token.Type.RPAREN) advance(); // consume )
            return inner;
        }

        // Atom: either a condition-name or a relational comparison
        String left = current().value();

        // Standalone condition name (88-level): no operator follows
        // Check without consuming — if the token after the field name (and any subscript)
        // is a condition-ender, it's just a condition name
        if (isConditionEnd(1) && current().type() != Token.Type.WORD) {
            advance();
            return new Statement.Condition.ConditionName(left, false);
        }
        if (current().type() == Token.Type.WORD && isConditionEnd(1)
            && (pos + 1 >= tokens.size() || tokens.get(pos + 1).type() != Token.Type.LPAREN)) {
            advance();
            return new Statement.Condition.ConditionName(left, false);
        }

        Expr leftExpr = parseExpr(); // consume field name + any (subscript)

        // IS/ARE noise words, possibly followed by NOT or class condition
        boolean negated = false;
        if (matchWord("IS") || matchWord("ARE")) {
            negated = matchWord("NOT");
            // Class conditions: IS NUMERIC, IS ALPHABETIC
            if (peek("NUMERIC")) { advance(); return new Statement.Condition.Simple(leftExpr, "IS-NUMERIC", null, negated); }
            if (peek("ALPHABETIC")) { advance(); return new Statement.Condition.Simple(leftExpr, "IS-ALPHABETIC", null, negated); }
        } else if (matchWord("NOT")) {
            negated = true;
        }

        // Parse the relational operator
        String operator = parseRelationalOperator();
        if (operator == null) {
            // No operator found — it's a condition-name test
            String condName = (leftExpr instanceof Expr.FieldRef fr) ? fr.name() : left;
            return new Statement.Condition.ConditionName(condName, negated);
        }

        Expr rightExpr = parseExpr();
        return new Statement.Condition.Simple(leftExpr, operator, rightExpr, negated);
    }

    private Statement.Condition negateCondition(Statement.Condition cond) {
        // For leaf conditions, flip their negated flag.
        // For compound conditions, wrap in Not.
        if (cond instanceof Statement.Condition.Simple s) {
            return new Statement.Condition.Simple(s.left(), s.operator(), s.right(), !s.negated());
        }
        if (cond instanceof Statement.Condition.ConditionName cn) {
            return new Statement.Condition.ConditionName(cn.name(), !cn.negated());
        }
        // For And, Or, Not — wrap in Not
        return new Statement.Condition.Not(cond);
    }

    /**
     * Check if the token at current+offset signals the end of a simple condition
     * (meaning the current token is a standalone condition name).
     */
    private boolean isConditionEnd(int lookAhead) {
        int idx = pos + lookAhead;
        if (idx >= tokens.size()) return true;
        Token t = tokens.get(idx);
        if (t.type() == Token.Type.PERIOD) return true;
        if (t.type() == Token.Type.EOF) return true;
        String v = t.value();
        if (isVerb(v)) return true;
        if (isScopeTerminator(v)) return true;
        if (v.equals("AND") || v.equals("OR")) return true;
        if (v.equals("AFTER")) return true;
        return false;
    }

    /**
     * Look ahead past current position to check if "OR" at pos+1 is followed
     * by "EQUAL" (making it part of "GREATER THAN OR EQUAL TO").
     */
    private boolean peekAhead(String word) {
        int idx = pos + 1;
        return idx < tokens.size() && tokens.get(idx).is(word);
    }

    private String parseRelationalOperator() {
        if (matchWord("EQUAL") || matchWord("=")) {
            consumeIf("TO");
            return "=";
        } else if (matchWord("GREATER")) {
            consumeIf("THAN");
            if (matchWord("OR")) {
                consumeIf("EQUAL");
                consumeIf("TO");
                return ">=";
            }
            return ">";
        } else if (matchWord("LESS")) {
            consumeIf("THAN");
            if (matchWord("OR")) {
                consumeIf("EQUAL");
                consumeIf("TO");
                return "<=";
            }
            return "<";
        } else if (matchWord(">=")) {
            return ">=";
        } else if (matchWord("<=")) {
            return "<=";
        } else if (matchWord(">")) {
            return ">";
        } else if (matchWord("<")) {
            return "<";
        } else if (current().type() == Token.Type.GREATER) {
            advance();
            if (current().type() == Token.Type.EQUALS) { advance(); return ">="; }
            return ">";
        } else if (current().type() == Token.Type.LESS) {
            advance();
            if (current().type() == Token.Type.EQUALS) { advance(); return "<="; }
            return "<";
        } else if (current().type() == Token.Type.EQUALS) {
            advance();
            return "=";
        }
        return null; // no operator found
    }

    // ── ON SIZE ERROR parsing ───────────────────────────────────────

    private List<List<Statement>> parseSizeError() {
        List<Statement> onErr = new ArrayList<>();
        List<Statement> notErr = new ArrayList<>();
        if (matchWord("ON") && matchWord("SIZE") && matchWord("ERROR")) {
            while (!atEnd() && !peek("NOT") && !peek("END-ADD") && !peek("END-SUBTRACT")
                   && !peek("END-MULTIPLY") && !peek("END-DIVIDE") && !peek("END-COMPUTE")
                   && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) onErr.add(s);
            }
        }
        if (matchWord("NOT") && matchWord("ON") && matchWord("SIZE") && matchWord("ERROR")) {
            while (!atEnd() && !peek("END-ADD") && !peek("END-SUBTRACT")
                   && !peek("END-MULTIPLY") && !peek("END-DIVIDE") && !peek("END-COMPUTE")
                   && !atPeriod()) {
                Statement s = parseStatement();
                if (s != null) notErr.add(s);
            }
        }
        // Consume scope terminators
        matchWord("END-ADD"); matchWord("END-SUBTRACT");
        matchWord("END-MULTIPLY"); matchWord("END-DIVIDE"); matchWord("END-COMPUTE");
        return List.of(onErr, notErr);
    }

    // ── Expression parsing ────────────────────────────────────────────

    /**
     * Parse a single expression: literal, figurative constant, FUNCTION call, or field reference.
     * Does NOT handle arithmetic operators (use parseArithmeticExpr for that).
     */
    private Expr parseExpr() {
        Token t = current();

        // String literal
        if (t.type() == Token.Type.STRING) {
            advance();
            return new Expr.StringLit(t.value());
        }

        // Numeric literal
        if (t.type() == Token.Type.NUMBER) {
            advance();
            return new Expr.NumericLit(t.value());
        }

        // Unary minus before a number
        if (t.type() == Token.Type.MINUS && pos + 1 < tokens.size()
            && tokens.get(pos + 1).type() == Token.Type.NUMBER) {
            advance(); // consume -
            String val = current().value();
            advance();
            return new Expr.NumericLit("-" + val);
        }

        // Figurative constants
        if (t.isAny("SPACES", "SPACE")) { advance(); return Expr.Figurative.SPACES; }
        if (t.isAny("ZEROS", "ZEROES", "ZERO")) { advance(); return Expr.Figurative.ZEROS; }
        if (t.isAny("HIGH-VALUES", "HIGH-VALUE")) { advance(); return Expr.Figurative.HIGH_VALUES; }
        if (t.isAny("LOW-VALUES", "LOW-VALUE")) { advance(); return Expr.Figurative.LOW_VALUES; }

        // FUNCTION call
        if (t.is("FUNCTION")) {
            advance(); // consume FUNCTION
            String funcName = current().value(); advance();
            List<Expr> args = new ArrayList<>();
            if (!atEnd() && current().type() == Token.Type.LPAREN) {
                advance(); // (
                while (!atEnd() && current().type() != Token.Type.RPAREN) {
                    args.add(parseExpr());
                    if (!atEnd() && current().type() == Token.Type.COMMA) advance();
                }
                if (!atEnd() && current().type() == Token.Type.RPAREN) advance(); // )
            }
            return new Expr.FunctionCall(funcName, args);
        }

        // Field reference (possibly subscripted or ref-mod)
        String name = t.value(); advance();
        if (!atEnd() && current().type() == Token.Type.LPAREN) {
            advance(); // consume (
            Expr first = parseExpr();
            if (!atEnd() && current().type() == Token.Type.COLON) {
                advance(); // consume :
                Expr len = parseExpr();
                if (!atEnd() && current().type() == Token.Type.RPAREN) advance();
                return Expr.FieldRef.refMod(name, first, len);
            }
            if (!atEnd() && current().type() == Token.Type.RPAREN) advance();
            return Expr.FieldRef.subscripted(name, first);
        }
        return new Expr.FieldRef(name);
    }

    /**
     * Parse an arithmetic expression (for COMPUTE RHS).
     * Handles precedence: additive < multiplicative < power < unary < factor.
     */
    private Expr parseArithmeticExpr() {
        return parseAdditiveExpr();
    }

    private Expr parseAdditiveExpr() {
        Expr left = parseMultiplicativeExpr();
        while (!atEnd() && !atPeriod()
               && !peek("ON") && !peek("NOT")
               && !isVerb(current().value())
               && !isScopeTerminator(current().value())) {
            if (current().type() == Token.Type.PLUS) {
                advance();
                Expr right = parseMultiplicativeExpr();
                left = new Expr.BinaryOp(left, "+", right);
            } else if (current().type() == Token.Type.MINUS) {
                advance();
                Expr right = parseMultiplicativeExpr();
                left = new Expr.BinaryOp(left, "-", right);
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parseMultiplicativeExpr() {
        Expr left = parsePowerExpr();
        while (!atEnd() && !atPeriod()) {
            if (current().type() == Token.Type.STAR) {
                // Check for ** (exponentiation — should be caught by parsePowerExpr, but handle just in case)
                if (pos + 1 < tokens.size() && tokens.get(pos + 1).type() == Token.Type.STAR) {
                    break; // Let parsePowerExpr handle **
                }
                advance();
                Expr right = parsePowerExpr();
                left = new Expr.BinaryOp(left, "*", right);
            } else if (current().type() == Token.Type.SLASH) {
                advance();
                Expr right = parsePowerExpr();
                left = new Expr.BinaryOp(left, "/", right);
            } else {
                break;
            }
        }
        return left;
    }

    private Expr parsePowerExpr() {
        Expr left = parseUnaryExpr();
        while (!atEnd() && !atPeriod()
               && current().type() == Token.Type.STAR
               && pos + 1 < tokens.size() && tokens.get(pos + 1).type() == Token.Type.STAR) {
            advance(); advance(); // consume **
            Expr right = parseUnaryExpr();
            left = new Expr.BinaryOp(left, "**", right);
        }
        // Also check for word "**" if lexer produces it as a single token
        while (!atEnd() && !atPeriod() && current().is("**")) {
            advance();
            Expr right = parseUnaryExpr();
            left = new Expr.BinaryOp(left, "**", right);
        }
        return left;
    }

    private Expr parseUnaryExpr() {
        if (!atEnd() && current().type() == Token.Type.MINUS) {
            advance();
            Expr operand = parseArithFactor();
            return new Expr.Negate(operand);
        }
        if (!atEnd() && current().type() == Token.Type.PLUS) {
            advance(); // unary + is a no-op
        }
        return parseArithFactor();
    }

    private Expr parseArithFactor() {
        // Parenthesized sub-expression
        if (!atEnd() && current().type() == Token.Type.LPAREN) {
            advance(); // consume (
            Expr inner = parseAdditiveExpr();
            if (!atEnd() && current().type() == Token.Type.RPAREN) advance(); // consume )
            return inner;
        }
        // Otherwise delegate to parseExpr for atoms (literals, field refs, functions)
        return parseExpr();
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private String readOperand() {
        String val = current().value();
        advance();
        // Consume trailing subscript or reference modification: FIELD(idx) or FIELD(pos:len)
        if (!atEnd() && current().type() == Token.Type.LPAREN) {
            StringBuilder sub = new StringBuilder(val);
            sub.append("(");
            advance(); // consume (
            while (!atEnd() && current().type() != Token.Type.RPAREN) {
                sub.append(current().value());
                advance();
            }
            if (!atEnd() && current().type() == Token.Type.RPAREN) {
                sub.append(")");
                advance(); // consume )
            }
            val = sub.toString();
        }
        return val;
    }

    private Token current() {
        return pos < tokens.size() ? tokens.get(pos) : new Token(Token.Type.EOF, "", 0);
    }

    private void advance() { if (pos < tokens.size()) pos++; }

    private boolean atEnd() { return pos >= tokens.size() || current().type() == Token.Type.EOF; }

    private boolean atPeriod() { return current().type() == Token.Type.PERIOD; }

    private boolean peek(String word) {
        return current().is(word);
    }

    private boolean peek(int offset, String word) {
        int idx = pos + offset;
        return idx < tokens.size() && tokens.get(idx).is(word);
    }

    /**
     * Detect "NOT ON EXCEPTION" or "NOT ON SIZE ERROR" or "NOT AT END" as a two/three-token
     * lookahead. This prevents tokens like NOT from being consumed as display items or
     * field targets when they're actually the start of an exception/error phrase.
     */
    private boolean isExceptionPhrase() {
        if (peek("NOT") && (peek(1, "ON") || peek(1, "AT"))) return true;
        return false;
    }

    private boolean matchWord(String word) {
        if (current().is(word)) { advance(); return true; }
        // Also check token type for symbolic tokens
        if (word.equals("=") && current().type() == Token.Type.EQUALS) { advance(); return true; }
        if (word.equals(">") && current().type() == Token.Type.GREATER) { advance(); return true; }
        if (word.equals("<") && current().type() == Token.Type.LESS) { advance(); return true; }
        return false;
    }

    private void expect(String word) {
        if (!matchWord(word)) {
            diag.error("parser", current().line(), current().value(),
                "Expected '" + word + "' but found '" + current().value() + "'");
            throw new ParseException("Expected '" + word + "' but found " + current(), current().line());
        }
    }

    private void consumePeriod() {
        if (atPeriod()) advance();
    }

    private void consumeIf(String word) { matchWord(word); }

    private void consumeStatementEnd() {
        // In COBOL, statements end at period or before next verb / scope terminator
        // We opportunistically consume a period if present
        if (atPeriod()) advance();
    }

    /**
     * Error recovery: skip forward to the next safe synchronization point.
     * This is either:
     * - The next period (sentence terminator)
     * - The next recognized verb (start of a new statement we can try to parse)
     * - A paragraph header (WORD followed by PERIOD)
     *
     * This prevents cascading errors: if we can't parse INSPECT, we skip its
     * arguments rather than misreading them as verb names and generating
     * spurious "unrecognized verb" errors for TALLYING, REPLACING, etc.
     */
    private void skipToSyncPoint() {
        // Always advance at least one token — prevents infinite loops
        // when the current token is itself a verb we can't parse.
        if (!atEnd()) advance();

        while (!atEnd()) {
            if (atPeriod()) {
                advance();
                return;
            }
            // Stop before the next recognized verb so the main loop can try it
            if (current().type() == Token.Type.WORD && isVerb(current().value())) {
                return;
            }
            advance();
        }
    }

    /** Parse a known-but-unsupported verb into an Unsupported AST node. */
    private Statement parseUnsupported(String verb, String hint) {
        int line = current().line();
        String rawCobol = captureToSyncPoint(current());
        diag.warning("parser", line, verb,
            verb + " verb not yet implemented — a placeholder will be generated");
        return new Statement.Unsupported(verb, rawCobol, line, hint);
    }

    /** Capture tokens from current position to the next sync point as raw text. */
    private String captureToSyncPoint(Token start) {
        StringBuilder sb = new StringBuilder(start.value());
        if (!atEnd()) advance(); // move past the verb
        while (!atEnd()) {
            if (atPeriod()) { advance(); break; }
            if (current().type() == Token.Type.WORD && isVerb(current().value())) break;
            sb.append(" ").append(current().value());
            advance();
        }
        return sb.toString();
    }

    private void skipDivision() {
        // Skip until next DIVISION keyword
        expect("DIVISION");
        consumePeriod();
        while (!atEnd() && !peek("DATA") && !peek("PROCEDURE")) {
            advance();
        }
    }

    private static boolean isVerb(String word) {
        return switch (word.toUpperCase()) {
            case "MOVE", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "COMPUTE",
                 "IF", "EVALUATE", "PERFORM", "DISPLAY", "ACCEPT",
                 "OPEN", "CLOSE", "READ", "WRITE", "REWRITE", "DELETE",
                 "GO", "STOP", "SET", "INITIALIZE", "INSPECT",
                 "STRING", "UNSTRING", "SEARCH", "SORT", "MERGE",
                 "CALL", "INVOKE", "EXIT", "EXEC", "CONTINUE",
                 "XML", "JSON",
                 "GOBACK", "START", "ALTER", "CANCEL" -> true;
            default -> false;
        };
    }

    /**
     * Scope terminators end a block within a statement — they're not verbs,
     * but token-collecting loops must stop before them or they'll be consumed
     * as field names, targets, or operands.
     */
    private static boolean isScopeTerminator(String word) {
        return switch (word.toUpperCase()) {
            case "ELSE", "END-IF", "END-EVALUATE", "END-PERFORM",
                 "END-READ", "END-WRITE", "END-REWRITE", "END-DELETE",
                 "END-ADD", "END-SUBTRACT", "END-MULTIPLY", "END-DIVIDE",
                 "END-COMPUTE", "END-CALL", "END-STRING", "END-UNSTRING",
                 "END-SEARCH", "END-EXEC",
                 "WHEN", "ON",
                 "AT", "INTO",
                 "THEN" -> true;
            default -> false;
        };
    }

    public static class ParseException extends RuntimeException {
        public final int line;
        public ParseException(String message, int line) {
            super("Line " + line + ": " + message);
            this.line = line;
        }
    }
}
