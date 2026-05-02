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
 * Generates Java source code from a parsed {@link CobolProgram} AST.
 * <p>
 * The output uses the cobol4j runtime API: Record.define(), Program.define(),
 * Field references, Arithmetic, etc. The generated code is a single compilable
 * Java class.
 */
public final class JavaEmitter {

    private final CobolProgram program;
    private final StringBuilder out = new StringBuilder();
    private int indent = 0;
    private final String className;

    private JavaEmitter(CobolProgram program) {
        this.program = program;
        this.className = toJavaClassName(program.programId());
    }

    /** Generate Java source from a parsed COBOL program. */
    public static String emit(CobolProgram program) {
        return new JavaEmitter(program).generate();
    }

    private String generate() {
        emitHeader();
        emitClassOpen();
        emitDataDivision();
        emitProcedureDivision();
        emitMainMethod();
        emitClassClose();
        return out.toString();
    }

    // ═══════════════════════════════════════════════════════════════

    private void emitHeader() {
        line("package generated;");
        line("");
        line("import org.cobol4j.Record;");
        line("import org.cobol4j.Program;");
        line("import org.cobol4j.ProgramContext;");
        line("import org.cobol4j.Arithmetic;");
        line("import org.cobol4j.CobolFile;");
        line("import org.cobol4j.SizeErrorHandler;");
        line("import org.cobol4j.Field;");
        line("import org.cobol4j.Decimal;");
        line("");
    }

    private void emitClassOpen() {
        line("public class " + className + " {");
        indent++;
        line("");
    }

    private void emitClassClose() {
        indent--;
        line("}");
    }

    private void emitMainMethod() {
        line("");
        line("public static void main(String[] args) {");
        indent++;
        line("new " + className + "().run();");
        indent--;
        line("}");
    }

    // ── DATA DIVISION → Record definitions ──────────────────────────

    private void emitDataDivision() {
        if (program.dataEntries().isEmpty()) return;

        line("// ── Working Storage ─────────────────────────────────────");
        line("");

        // Group entries by 01/77 level
        List<RecordGroup> groups = groupByLevel01();

        for (RecordGroup group : groups) {
            emitRecordDefinition(group);
        }
        line("");
    }

    private void emitRecordDefinition(RecordGroup group) {
        String recVar = toJavaFieldName(group.name);
        line("private final Record " + recVar + " = Record.define(\"" + group.name + "\")");
        indent++;

        for (CobolProgram.DataEntry entry : group.children) {
            emitDataEntry(entry);
        }

        line(".build();");
        indent--;
        line("");
    }

    private void emitDataEntry(CobolProgram.DataEntry entry) {
        if (entry.level() == 88) return; // handled inline with parent

        if (entry.pic() == null) {
            // Group item — we flatten for now (groups need lambda syntax)
            // For simplicity, emit as a comment
            line("// GROUP: " + entry.name() + " (level " + entry.level() + ")");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(".pic(\"").append(entry.name()).append("\", \"").append(entry.pic()).append("\")");

        if (entry.usage() != null) {
            String u = entry.usage().toUpperCase().replace("-", "");
            switch (u) {
                case "COMP3", "COMPUTATIONAL3", "PACKEDDECIMAL" -> sb.append(".comp3()");
                case "COMP", "COMP4", "COMPUTATIONAL", "COMPUTATIONAL4", "BINARY" -> sb.append(".comp()");
                case "COMP5", "COMPUTATIONAL5" -> sb.append(".comp5()");
            }
        }

        if (entry.occurs() > 0) {
            sb.append(".occurs(").append(entry.occurs()).append(")");
        }

        if (entry.value() != null) {
            sb.append(".value(").append(formatValue(entry.value())).append(")");
        }

        line(sb.toString());

        // Emit 88-level conditions
        for (CobolProgram.Condition88 cond : entry.conditions()) {
            if (cond.thruTo() != null) {
                line("    .value88Range(\"" + cond.name() + "\", "
                    + formatValue(cond.thruFrom()) + ", " + formatValue(cond.thruTo()) + ")");
            } else {
                StringBuilder vals = new StringBuilder();
                vals.append("    .value88(\"").append(cond.name()).append("\"");
                for (String v : cond.values()) {
                    vals.append(", ").append(formatValue(v));
                }
                vals.append(")");
                line(vals.toString());
            }
        }
    }

    // ── PROCEDURE DIVISION → Program definition ─────────────────────

    private void emitProcedureDivision() {
        if (program.paragraphs().isEmpty()) return;

        line("// ── Procedure Division ───────────────────────────────────");
        line("");
        line("public void run() {");
        indent++;

        // Emit record variable declarations as local refs
        List<RecordGroup> groups = groupByLevel01();
        for (RecordGroup g : groups) {
            String var = toJavaFieldName(g.name);
            // Field references for frequently used fields could go here
        }

        line("Program.define(\"" + program.programId() + "\")");
        indent++;

        for (RecordGroup g : groups) {
            line(".workingStorage(" + toJavaFieldName(g.name) + ")");
        }

        line(".onDisplay(s -> {})  // configure as needed");

        for (CobolProgram.Paragraph para : program.paragraphs()) {
            emitParagraph(para);
        }

        line(".build()");
        line(".run();");
        indent--;
        indent--;
        line("}");
    }

    private void emitParagraph(CobolProgram.Paragraph para) {
        line(".paragraph(\"" + para.name() + "\", ctx -> {");
        indent++;
        for (Statement stmt : para.statements()) {
            emitStatement(stmt);
        }
        indent--;
        line("})");
    }

    // ── Statement emission ──────────────────────────────────────────

    private void emitStatement(Statement stmt) {
        if (stmt instanceof Statement.Move m) emitMove(m);
        else if (stmt instanceof Statement.MoveCorresponding m) emitMoveCorr(m);
        else if (stmt instanceof Statement.Initialize i) line(recRef(i.target()) + ".initialize();");
        else if (stmt instanceof Statement.Add a) emitAdd(a);
        else if (stmt instanceof Statement.Subtract s) emitSubtract(s);
        else if (stmt instanceof Statement.Multiply m) emitMultiply(m);
        else if (stmt instanceof Statement.Divide d) emitDivide(d);
        else if (stmt instanceof Statement.Compute c) emitCompute(c);
        else if (stmt instanceof Statement.If i) emitIf(i);
        else if (stmt instanceof Statement.Evaluate e) emitEvaluate(e);
        else if (stmt instanceof Statement.Perform p) emitPerform(p);
        else if (stmt instanceof Statement.Display d) emitDisplay(d);
        else if (stmt instanceof Statement.Accept a) line("ctx.accept(" + recRef(a.target()) + ", \"" + a.target() + "\");");
        else if (stmt instanceof Statement.Open o) line("ctx.open(" + toJavaFieldName(o.fileName()) + ", CobolFile.OpenMode." + o.mode().toUpperCase().replace("-", "") + ");");
        else if (stmt instanceof Statement.Close c) line("ctx.close(" + toJavaFieldName(c.fileName()) + ");");
        else if (stmt instanceof Statement.Read r) emitRead(r);
        else if (stmt instanceof Statement.Write w) line("ctx.write(" + toJavaFieldName(w.recordName()) + ", " + recRef(w.recordName()) + ");");
        else if (stmt instanceof Statement.GoTo g) line("ctx.goTo(\"" + g.paragraph() + "\");");
        else if (stmt instanceof Statement.StopRun s) line("ctx.stopRun();");
        else if (stmt instanceof Statement.ExitParagraph e) line("ctx.exitParagraph();");
        else if (stmt instanceof Statement.SetCondition sc) line(findRecordFor(sc.conditionName()) + ".set(\"" + sc.conditionName() + "\");");
        else if (stmt instanceof Statement.ExecSql sq) emitExecSql(sq);
        // WhenClause handled within Evaluate
    }

    private void emitMove(Statement.Move m) {
        for (String target : m.targets()) {
            String val = emitValueExpr(m.source());
            line(recRef(target) + ".move(\"" + target + "\", " + val + ");");
        }
    }

    private void emitMoveCorr(Statement.MoveCorresponding m) {
        line(recRef(m.target()) + ".moveCorresponding(" + recRef(m.source()) + ");");
    }

    private void emitAdd(Statement.Add a) {
        String value = a.sources().size() == 1 ? emitValueExpr(a.sources().get(0))
            : "Decimal.of(\"" + a.sources().get(0) + "\")";

        if (a.giving() != null) {
            line("Arithmetic.add(" + value + ", " + fieldGet(a.to()) + ")");
            line("    .giving(" + recRef(a.giving()) + ".field(\"" + a.giving() + "\"))");
            if (a.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            String handler = emitSizeErrorHandler(a.onSizeError(), a.notOnSizeError());
            line(recRef(a.to()) + ".add(\"" + a.to() + "\", " + value
                + (handler.isEmpty() ? "" : ", " + handler) + ");");
        }
    }

    private void emitSubtract(Statement.Subtract s) {
        String value = emitValueExpr(s.subtrahends().get(0));
        if (s.giving() != null) {
            line("Arithmetic.subtract(" + value + ", " + fieldGet(s.from()) + ")");
            line("    .giving(" + recRef(s.giving()) + ".field(\"" + s.giving() + "\"))");
            if (s.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            line(recRef(s.from()) + ".subtract(\"" + s.from() + "\", " + value + ");");
        }
    }

    private void emitMultiply(Statement.Multiply m) {
        if (m.giving() != null) {
            line("Arithmetic.multiply(" + fieldGet(m.a()) + ", " + fieldGet(m.by()) + ")");
            line("    .giving(" + recRef(m.giving()) + ".field(\"" + m.giving() + "\"))");
            if (m.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            line(recRef(m.by()) + ".multiply(\"" + m.by() + "\", " + fieldGet(m.a()) + ");");
        }
    }

    private void emitDivide(Statement.Divide d) {
        if (d.giving() != null) {
            line("Arithmetic.divide(" + fieldGet(d.dividend()) + ", " + fieldGet(d.divisor()) + ")");
            line("    .giving(" + recRef(d.giving()) + ".field(\"" + d.giving() + "\"))");
            if (d.remainder() != null) {
                line("    .remainder(" + recRef(d.remainder()) + ".field(\"" + d.remainder() + "\"))");
            }
            if (d.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            line(recRef(d.dividend()) + ".divide(\"" + d.dividend() + "\", " + fieldGet(d.divisor()) + ");");
        }
    }

    private void emitCompute(Statement.Compute c) {
        // Convert COBOL arithmetic expression to Decimal expression
        String expr = convertArithmeticExpr(c.expression());
        line(recRef(c.target()) + ".compute(\"" + c.target() + "\", " + expr + ");");
    }

    private void emitIf(Statement.If i) {
        line("if (" + emitCondition(i.condition()) + ") {");
        indent++;
        for (Statement s : i.thenBlock()) emitStatement(s);
        indent--;
        if (!i.elseBlock().isEmpty()) {
            line("} else {");
            indent++;
            for (Statement s : i.elseBlock()) emitStatement(s);
            indent--;
        }
        line("}");
    }

    private void emitEvaluate(Statement.Evaluate e) {
        String subject = e.subject().equalsIgnoreCase("TRUE") ? "" : e.subject();
        if (subject.isEmpty()) {
            // EVALUATE TRUE — use evaluateTrue()
            line("ctx.evaluateTrue()");
            for (Statement.WhenClause w : e.whenClauses()) {
                line("    .whenTrue(() -> " + emitValueExpr(w.value()) + ", () -> {");
                indent += 2;
                for (Statement s : w.body()) emitStatement(s);
                indent -= 2;
                line("    })");
            }
        } else {
            line("ctx.evaluate(" + fieldGet(subject) + ")");
            for (Statement.WhenClause w : e.whenClauses()) {
                line("    .when(" + emitValueExpr(w.value()) + ", () -> {");
                indent += 2;
                for (Statement s : w.body()) emitStatement(s);
                indent -= 2;
                line("    })");
            }
        }
        if (!e.whenOther().isEmpty()) {
            line("    .whenOther(() -> {");
            indent += 2;
            for (Statement s : e.whenOther()) emitStatement(s);
            indent -= 2;
            line("    })");
        }
        line("    .execute();");
    }

    private void emitPerform(Statement.Perform p) {
        switch (p.type()) {
            case SIMPLE -> line("ctx.perform(\"" + p.paragraph() + "\");");
            case THRU -> line("ctx.perform(\"" + p.paragraph() + "\", \"" + p.thru() + "\");");
            case TIMES -> line("ctx.performTimes(\"" + p.paragraph() + "\", " + p.from() + ");");
            case UNTIL -> line("ctx.performUntil(\"" + p.paragraph() + "\", () -> " + emitCondition(p.until()) + ");");
            case VARYING -> {
                String rec = findRecordFor(p.varying());
                line("ctx.performVarying(\"" + p.paragraph() + "\", " + rec + ", \""
                    + p.varying() + "\", " + p.from() + ", " + p.by() + ", () -> "
                    + emitCondition(p.until()) + ");");
            }
        }
    }

    private void emitDisplay(Statement.Display d) {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < d.items().size(); i++) {
            if (i > 0) args.append(", ");
            String item = d.items().get(i);
            if (item.startsWith("\"")) {
                args.append(item);
            } else {
                args.append(fieldGet(item));
            }
        }
        line("ctx.display(" + args + ");");
    }

    private void emitRead(Statement.Read r) {
        String file = toJavaFieldName(r.fileName());
        StringBuilder sb = new StringBuilder();
        sb.append("ctx.read(").append(file).append(")");
        if (r.into() != null) {
            sb.append(".into(").append(recRef(r.into())).append(")");
        }
        line(sb.toString());
        if (!r.atEnd().isEmpty()) {
            line("    .atEnd(() -> {");
            indent += 2;
            for (Statement s : r.atEnd()) emitStatement(s);
            indent -= 2;
            line("    })");
        }
        if (!r.notAtEnd().isEmpty()) {
            line("    .notAtEnd(() -> {");
            indent += 2;
            for (Statement s : r.notAtEnd()) emitStatement(s);
            indent -= 2;
            line("    })");
        }
        line("    .execute();");
    }

    private void emitExecSql(Statement.ExecSql s) {
        line("// EXEC SQL: " + s.sqlText());
        line("// TODO: Implement SQL translation");
    }

    // ── Expression helpers ──────────────────────────────────────────

    private String emitCondition(Statement.Condition cond) {
        if (cond == null) return "true";
        String prefix = cond.negated() ? "!" : "";
        if ("IS-TRUE".equals(cond.operator())) {
            return prefix + findRecordFor(cond.left()) + ".is(\"" + cond.left() + "\")";
        }
        String left = fieldGet(cond.left());
        String right = emitValueExpr(cond.right());
        String op = switch (cond.operator()) {
            case "=" -> ".compareTo(" + right + ") == 0";
            case ">" -> ".compareTo(" + right + ") > 0";
            case "<" -> ".compareTo(" + right + ") < 0";
            default -> " == " + right;
        };
        return prefix + "(" + left + op + ")";
    }

    private String emitValueExpr(String val) {
        if (val == null) return "null";
        if (val.startsWith("\"")) return val; // string literal
        if (val.equals("SPACES") || val.equals("SPACE")) return "\"\"";
        if (val.equals("ZEROS") || val.equals("ZEROES")) return "Decimal.ZERO";
        if (val.matches("-?\\d+\\.?\\d*")) return "Decimal.of(\"" + val + "\")";
        // Assume it's a field reference
        return fieldGet(val);
    }

    private String fieldGet(String fieldName) {
        if (fieldName == null) return "null";
        if (fieldName.matches("-?\\d+\\.?\\d*")) return "Decimal.of(\"" + fieldName + "\")";
        return recRef(fieldName) + ".getDecimal(\"" + fieldName + "\")";
    }

    private String convertArithmeticExpr(String expr) {
        // Simple: wrap the whole expression as a BigDecimal computation comment
        // A real implementation would parse the expression tree
        return "Decimal.of(\"0\") /* TODO: " + expr + " */";
    }

    private String emitSizeErrorHandler(List<Statement> onErr, List<Statement> notErr) {
        if (onErr == null || onErr.isEmpty()) return "";
        return "SizeErrorHandler.onError(() -> { /* size error */ })";
    }

    // ── Naming helpers ──────────────────────────────────────────────

    private String recRef(String fieldOrRecName) {
        // Find which record contains this field
        // For simplicity, use the first 01-level record
        List<RecordGroup> groups = groupByLevel01();
        if (groups.size() == 1) return toJavaFieldName(groups.get(0).name);
        // Try to find by field name
        for (RecordGroup g : groups) {
            for (CobolProgram.DataEntry e : g.children) {
                if (e.name().equalsIgnoreCase(fieldOrRecName)) {
                    return toJavaFieldName(g.name);
                }
            }
        }
        return groups.isEmpty() ? "rec" : toJavaFieldName(groups.get(0).name);
    }

    private String findRecordFor(String fieldName) {
        return recRef(fieldName);
    }

    // ── Formatting ──────────────────────────────────────────────────

    private void line(String text) {
        out.append("    ".repeat(indent)).append(text).append("\n");
    }

    private static String toJavaClassName(String cobolName) {
        StringBuilder sb = new StringBuilder();
        boolean capitalize = true;
        for (char c : cobolName.toCharArray()) {
            if (c == '-' || c == '_') {
                capitalize = true;
            } else {
                sb.append(capitalize ? Character.toUpperCase(c) : Character.toLowerCase(c));
                capitalize = false;
            }
        }
        return sb.toString();
    }

    static String toJavaFieldName(String cobolName) {
        String javaClass = toJavaClassName(cobolName);
        return Character.toLowerCase(javaClass.charAt(0)) + javaClass.substring(1);
    }

    private String formatValue(String val) {
        if (val == null) return "null";
        if (val.startsWith("\"")) return val;
        if (val.equals("SPACES")) return "\" \"";
        if (val.equals("ZEROS")) return "\"0\"";
        if (val.matches("-?\\d+\\.?\\d*")) return "\"" + val + "\"";
        return "\"" + val + "\"";
    }

    // ── Record grouping ─────────────────────────────────────────────

    private List<RecordGroup> groupByLevel01() {
        List<RecordGroup> groups = new ArrayList<>();
        RecordGroup current = null;
        for (CobolProgram.DataEntry entry : program.dataEntries()) {
            if (entry.level() == 1 || entry.level() == 77) {
                if (current != null) groups.add(current);
                current = new RecordGroup(entry.name(), new ArrayList<>());
                if (entry.pic() != null) {
                    current.children.add(entry);
                }
            } else if (current != null) {
                current.children.add(entry);
            }
        }
        if (current != null) groups.add(current);
        return groups;
    }

    private static class RecordGroup {
        final String name;
        final List<CobolProgram.DataEntry> children;
        RecordGroup(String name, List<CobolProgram.DataEntry> children) {
            this.name = name;
            this.children = children;
        }
    }
}
