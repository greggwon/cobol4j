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
    private int pos;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /** Parse a token stream into a CobolProgram AST. */
    public static CobolProgram parse(List<Token> tokens) {
        return new Parser(tokens).parseProgram();
    }

    /** Convenience: lex and parse source text. */
    public static CobolProgram parse(String source) {
        return parse(Lexer.tokenize(source));
    }

    // ═══════════════════════════════════════════════════════════════
    //  TOP-LEVEL STRUCTURE
    // ═══════════════════════════════════════════════════════════════

    private CobolProgram parseProgram() {
        String programId = "UNNAMED";
        List<CobolProgram.DataEntry> dataEntries = new ArrayList<>();
        List<CobolProgram.Paragraph> paragraphs = new ArrayList<>();

        // Parse divisions in order
        if (matchWord("IDENTIFICATION")) {
            programId = parseIdentificationDivision();
        }
        if (matchWord("ENVIRONMENT")) {
            skipDivision(); // skip for now
        }
        if (matchWord("DATA")) {
            dataEntries = parseDataDivision();
        }
        if (matchWord("PROCEDURE")) {
            paragraphs = parseProcedureDivision();
        }

        return new CobolProgram(programId, dataEntries, paragraphs);
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

    private List<CobolProgram.DataEntry> parseDataDivision() {
        expect("DIVISION");
        consumePeriod();
        List<CobolProgram.DataEntry> entries = new ArrayList<>();

        // Skip to WORKING-STORAGE SECTION or PROCEDURE DIVISION
        while (!atEnd() && !peek("PROCEDURE")) {
            if (matchWord("WORKING-STORAGE") || matchWord("LOCAL-STORAGE")
                || matchWord("FILE") || matchWord("LINKAGE")) {
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
            } else {
                advance(); // skip unrecognized clause words
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
            level, name, pic, usage, value, redefines, occurs, dependingOn, conditions);
        return List.of(entry);
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
            // PIC characters: S, 9, X, A, V, Z, B, P, etc.
            String v = t.value();
            return v.matches("[SXA9VZPB*+\\-$/,0]+") || v.matches("[SXA9VZPB*+\\-$/,0]+(\\(\\d+\\))?.*");
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
            Statement stmt = parseStatement();
            if (stmt != null) stmts.add(stmt);
        }
        return stmts;
    }

    private Statement parseStatement() {
        Token t = current();
        if (t.type() == Token.Type.PERIOD) { advance(); return null; }
        if (t.type() == Token.Type.EOF) return null;

        return switch (t.value().toUpperCase()) {
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
            default -> { advance(); yield null; } // skip unrecognized
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
        // Handle MOVE FUNCTION xxx TO ... (intrinsic function call)
        String source;
        if (peek("FUNCTION")) {
            advance(); // consume FUNCTION
            StringBuilder funcExpr = new StringBuilder("FUNCTION ");
            while (!atEnd() && !peek("TO")) {
                funcExpr.append(current().value());
                advance();
            }
            source = funcExpr.toString();
        } else {
            source = parseValueLiteral();
        }
        expect("TO");
        List<String> targets = new ArrayList<>();
        while (!atEnd() && !atPeriod() && !isVerb(current().value())) {
            targets.add(current().value());
            advance();
        }
        consumeStatementEnd();
        return new Statement.Move(source, targets);
    }

    private Statement parseAdd() {
        advance(); // consume ADD
        List<String> sources = new ArrayList<>();
        while (!atEnd() && !peek("TO") && !peek("GIVING")) {
            sources.add(readOperand());
        }
        String to = null, giving = null;
        boolean rounded = false;
        if (matchWord("TO")) { to = readOperand(); }
        if (matchWord("GIVING")) { giving = readOperand(); }
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Add(sources, to, giving, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseSubtract() {
        advance(); // consume SUBTRACT
        List<String> subtrahends = new ArrayList<>();
        while (!atEnd() && !peek("FROM")) {
            subtrahends.add(readOperand());
        }
        expect("FROM");
        String from = readOperand();
        String giving = null;
        boolean rounded = false;
        if (matchWord("GIVING")) { giving = readOperand(); }
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Subtract(subtrahends, from, giving, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseMultiply() {
        advance(); // consume MULTIPLY
        String a = readOperand();
        expect("BY");
        String by = readOperand();
        String giving = null;
        boolean rounded = false;
        if (matchWord("GIVING")) { giving = readOperand(); }
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Multiply(a, by, giving, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseDivide() {
        advance(); // consume DIVIDE
        String dividend = readOperand();
        String divisorWord = current().value(); advance(); // INTO or BY
        String divisor = readOperand();
        // Swap if "DIVIDE X INTO Y" (COBOL: Y / X)
        if (divisorWord.equalsIgnoreCase("INTO")) {
            String temp = dividend; dividend = divisor; divisor = temp;
        }
        String giving = null, remainder = null;
        boolean rounded = false;
        if (matchWord("GIVING")) { giving = readOperand(); }
        if (matchWord("REMAINDER")) { remainder = readOperand(); }
        if (matchWord("ROUNDED")) rounded = true;
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Divide(dividend, divisor, giving, remainder, rounded, onSize.get(0), onSize.get(1));
    }

    private Statement parseCompute() {
        advance(); // consume COMPUTE
        String target = current().value(); advance();
        boolean rounded = false;
        if (matchWord("ROUNDED")) rounded = true;
        expect("=");
        // Collect expression tokens until period or ON SIZE ERROR
        StringBuilder expr = new StringBuilder();
        while (!atEnd() && !atPeriod() && !peek("ON") && !peek("NOT")) {
            expr.append(current().value()).append(" ");
            advance();
        }
        var onSize = parseSizeError();
        consumeStatementEnd();
        return new Statement.Compute(target, expr.toString().trim(), rounded, onSize.get(0), onSize.get(1));
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

    private Statement parseEvaluate() {
        advance(); // consume EVALUATE
        String subject = current().value(); advance();
        List<Statement.WhenClause> whens = new ArrayList<>();
        List<Statement> whenOther = new ArrayList<>();

        while (matchWord("WHEN")) {
            if (matchWord("OTHER")) {
                while (!atEnd() && !peek("END-EVALUATE")) {
                    Statement s = parseStatement();
                    if (s != null) whenOther.add(s);
                }
                break;
            }
            String value = parseValueLiteral();
            List<Statement> body = new ArrayList<>();
            while (!atEnd() && !peek("WHEN") && !peek("END-EVALUATE")) {
                Statement s = parseStatement();
                if (s != null) body.add(s);
            }
            whens.add(new Statement.WhenClause(value, body));
        }
        matchWord("END-EVALUATE");
        consumeStatementEnd();
        return new Statement.Evaluate(subject, whens, whenOther);
    }

    private Statement parsePerform() {
        advance(); // consume PERFORM
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

    private Statement parseDisplay() {
        advance(); // consume DISPLAY
        List<String> items = new ArrayList<>();
        while (!atEnd() && !atPeriod() && !isVerb(current().value())) {
            if (current().type() == Token.Type.STRING) {
                items.add("\"" + current().value() + "\"");
            } else {
                items.add(current().value());
            }
            advance();
        }
        consumeStatementEnd();
        return new Statement.Display(items);
    }

    private Statement parseAccept() {
        advance(); // consume ACCEPT
        String target = current().value(); advance();
        consumeStatementEnd();
        return new Statement.Accept(target);
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
        consumeStatementEnd();
        return null; // EXIT alone = no-op
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

    private Statement.Condition parseCondition() {
        boolean negated = matchWord("NOT");
        String left = current().value(); advance();

        // Simple condition-name test (88-level): just the name
        if (atEnd() || atPeriod() || isVerb(current().value())
            || peek("THEN") || peek("END-IF") || peek("END-EVALUATE")
            || peek("AFTER") || peek("WHEN")) {
            return new Statement.Condition(left, "IS-TRUE", null, negated);
        }

        String operator;
        if (matchWord("IS") || matchWord("ARE")) {
            negated = negated || matchWord("NOT");
        }

        if (matchWord("EQUAL") || matchWord("=")) {
            consumeIf("TO");
            operator = "=";
        } else if (matchWord("GREATER")) {
            consumeIf("THAN");
            operator = ">";
        } else if (matchWord("LESS")) {
            consumeIf("THAN");
            operator = "<";
        } else if (matchWord(">")) {
            operator = ">";
        } else if (matchWord("<")) {
            operator = "<";
        } else if (current().type() == Token.Type.GREATER) {
            operator = ">"; advance();
        } else if (current().type() == Token.Type.LESS) {
            operator = "<"; advance();
        } else if (current().type() == Token.Type.EQUALS) {
            operator = "="; advance();
        } else {
            // Default: assume it's a condition-name test
            return new Statement.Condition(left, "IS-TRUE", null, negated);
        }

        String right = readOperand();
        return new Statement.Condition(left, operator, right, negated);
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

    // ── Helpers ─────────────────────────────────────────────────────

    private String readOperand() {
        String val = current().value();
        advance();
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
                 "CALL", "EXIT", "EXEC", "CONTINUE" -> true;
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
