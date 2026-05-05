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
    private final TranspileDiagnostics diag;

    private JavaEmitter(CobolProgram program, TranspileDiagnostics diag) {
        this.program = program;
        this.className = toJavaClassName(program.programId());
        this.diag = diag;
    }

    /** Generate Java source from a parsed COBOL program. */
    public static String emit(CobolProgram program) {
        return emit(program, new TranspileDiagnostics());
    }

    /** Generate with diagnostics collection. */
    public static String emit(CobolProgram program, TranspileDiagnostics diag) {
        return new JavaEmitter(program, diag).generate();
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
        line("import org.cobol4j.Intrinsic;");
        line("import org.cobol4j.Inspect;");
        line("import org.cobol4j.CobolString;");
        line("import org.cobol4j.CobolUnstring;");
        line("import org.cobol4j.Search;");
        line("import org.cobol4j.CobolSort;");
        line("import org.cobol4j.interop.SystemCall;");
        line("");
    }

    private void emitClassOpen() {
        line("public class " + className + " {");
        indent++;
        line("");
        line("private static final String LOGGER_NAME;");
        line("static {");
        indent++;
        line("String env = System.getProperty(\"cobol4j.logger\",");
        line("    System.getenv().getOrDefault(\"COBOL4J_LOGGER\", \"cobol4j\"));");
        line("LOGGER_NAME = (env == null || env.isEmpty()) ? \"cobol4j\" : env;");
        indent--;
        line("}");
        line("private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(LOGGER_NAME);");
        line("private final SystemCall sys = SystemCall.defaultInstance();");
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

        if (entry.signClause() != null) {
            switch (entry.signClause()) {
                case "LEADING" -> sb.append(".signLeading()");
                case "TRAILING_SEPARATE" -> sb.append(".signTrailingSeparate()");
                case "LEADING_SEPARATE" -> sb.append(".signLeadingSeparate()");
                // TRAILING is the default, no call needed
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

        line(".onDisplay(LOG::info)");

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
        else if (stmt instanceof Statement.InlinePerform ip) emitInlinePerform(ip);
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
        else if (stmt instanceof Statement.Call call) emitCall(call);
        else if (stmt instanceof Statement.InspectTallying it) emitInspectTallying(it);
        else if (stmt instanceof Statement.InspectReplacing ir) emitInspectReplacing(ir);
        else if (stmt instanceof Statement.InspectConverting ic) emitInspectConverting(ic);
        else if (stmt instanceof Statement.StringStmt ss) emitStringStmt(ss);
        else if (stmt instanceof Statement.UnstringStmt us) emitUnstringStmt(us);
        else if (stmt instanceof Statement.SearchStmt sr) emitSearchStmt(sr);
        else if (stmt instanceof Statement.SortStmt so) emitSortStmt(so);
        else if (stmt instanceof Statement.Rewrite rw) line("ctx.rewrite(" + toJavaFieldName(rw.recordName()) + ", " + recRef(rw.recordName()) + ");");
        else if (stmt instanceof Statement.Delete dl) line("ctx.delete(" + toJavaFieldName(dl.fileName()) + ");");
        // WhenClause, CallParam, StringSource, SearchWhen, SortKey handled within parent
    }

    private void emitMove(Statement.Move m) {
        for (String target : m.targets()) {
            String val = emitExpr(m.source());
            line(recRef(target) + ".move(\"" + target + "\", " + val + ");");
        }
    }

    private void emitMoveCorr(Statement.MoveCorresponding m) {
        line(recRef(m.target()) + ".moveCorresponding(" + recRef(m.source()) + ");");
    }

    private void emitAdd(Statement.Add a) {
        String value = emitExpr(a.sources().get(0));

        if (a.giving() != null) {
            String firstTarget = a.targets().isEmpty() ? "0" : a.targets().get(0);
            line("Arithmetic.add(" + value + ", " + fieldGet(firstTarget) + ")");
            line("    .giving(" + recRef(a.giving()) + ".field(\"" + a.giving() + "\"))");
            if (a.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            // ADD value TO target1 target2 target3 — each target gets the value added
            String handler = emitSizeErrorHandler(a.onSizeError(), a.notOnSizeError());
            for (String target : a.targets()) {
                line(recRef(target) + ".add(\"" + target + "\", " + value
                    + (handler.isEmpty() ? "" : ", " + handler) + ");");
            }
        }
    }

    private void emitSubtract(Statement.Subtract s) {
        String value = emitExpr(s.subtrahends().get(0));
        if (s.giving() != null) {
            String firstTarget = s.targets().isEmpty() ? "0" : s.targets().get(0);
            line("Arithmetic.subtract(" + value + ", " + fieldGet(firstTarget) + ")");
            line("    .giving(" + recRef(s.giving()) + ".field(\"" + s.giving() + "\"))");
            if (s.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            // SUBTRACT value FROM target1 target2 target3
            for (String target : s.targets()) {
                line(recRef(target) + ".subtract(\"" + target + "\", " + value + ");");
            }
        }
    }

    private void emitMultiply(Statement.Multiply m) {
        if (m.giving() != null) {
            line("Arithmetic.multiply(" + emitExpr(m.a()) + ", " + emitExpr(m.by()) + ")");
            line("    .giving(" + recRef(m.giving()) + ".field(\"" + m.giving() + "\"))");
            if (m.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            String byName = (m.by() instanceof Expr.FieldRef fr) ? fr.name() : "RESULT";
            line(recRef(byName) + ".multiply(\"" + byName + "\", " + emitExpr(m.a()) + ");");
        }
    }

    private void emitDivide(Statement.Divide d) {
        if (d.giving() != null) {
            line("Arithmetic.divide(" + emitExpr(d.dividend()) + ", " + emitExpr(d.divisor()) + ")");
            line("    .giving(" + recRef(d.giving()) + ".field(\"" + d.giving() + "\"))");
            if (d.remainder() != null) {
                line("    .remainder(" + recRef(d.remainder()) + ".field(\"" + d.remainder() + "\"))");
            }
            if (d.rounded()) line("    .rounded()");
            line("    .execute();");
        } else {
            String dividendName = (d.dividend() instanceof Expr.FieldRef fr) ? fr.name() : "RESULT";
            line(recRef(dividendName) + ".divide(\"" + dividendName + "\", " + emitExpr(d.divisor()) + ");");
        }
    }

    private void emitCompute(Statement.Compute c) {
        String expr = emitExpr(c.expression());
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
            // EVALUATE TRUE — WHEN values are condition names or boolean expressions
            line("ctx.evaluateTrue()");
            for (Statement.WhenClause w : e.whenClauses()) {
                String condition = emitEvaluateTrueConditions(w.values());
                line("    .whenTrue(() -> " + condition + ", () -> {");
                indent += 2;
                for (Statement s : w.body()) emitStatement(s);
                indent -= 2;
                line("    })");
            }
        } else {
            // EVALUATE field — compare against WHEN values
            line("ctx.evaluate(" + recRef(subject) + ".getString(\"" + subject + "\").trim())");
            for (Statement.WhenClause w : e.whenClauses()) {
                String predicate = emitWhenPredicate(w.values());
                line("    .when(" + predicate + ", () -> {");
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

    /** Emit condition(s) for EVALUATE TRUE — OR-connected if multiple values. */
    private String emitEvaluateTrueConditions(List<Statement.WhenValue> values) {
        if (values.size() == 1) {
            return emitEvaluateTrueCondition(values.get(0).value());
        }
        // Multiple values = OR
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(" || ");
            sb.append(emitEvaluateTrueCondition(values.get(i).value()));
        }
        sb.append(")");
        return sb.toString();
    }

    /** Emit a WHEN predicate for EVALUATE field — handles single value, range, and fall-through. */
    private String emitWhenPredicate(List<Statement.WhenValue> values) {
        if (values.size() == 1 && !values.get(0).isRange()) {
            // Simple single value
            return emitValueExpr(values.get(0).value());
        }
        // Multiple values or ranges — use a Predicate lambda
        StringBuilder sb = new StringBuilder("(java.util.function.Predicate<Object>) v -> (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(" || ");
            Statement.WhenValue wv = values.get(i);
            if (wv.isRange()) {
                // v >= from && v <= to (using string comparison)
                sb.append("(((Comparable)v).compareTo(").append(emitValueExpr(wv.value())).append(") >= 0")
                  .append(" && ((Comparable)v).compareTo(").append(emitValueExpr(wv.thruEnd())).append(") <= 0)");
            } else {
                sb.append("v.equals(").append(emitValueExpr(wv.value())).append(")");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private void emitInlinePerform(Statement.InlinePerform ip) {
        if (ip.until() != null) {
            line("ctx.performUntil(() -> " + emitCondition(ip.until()) + ", () -> {");
        } else {
            line("while (true) {"); // no condition = infinite (shouldn't happen in valid COBOL)
        }
        indent++;
        for (Statement s : ip.body()) emitStatement(s);
        indent--;
        line("});");
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
            args.append(emitExpr(d.items().get(i)));
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
        diag.warning("emitter", 0, "EXEC SQL",
            "Embedded SQL requires manual translation to CobolSql/SqlSession API. "
            + "SQL text: " + s.sqlText());
        line("// EXEC SQL: " + s.sqlText());
        line("// TODO: Translate to CobolSql/SqlSession API");
    }

    // ── INSPECT ──────────────────────────────────────────────────

    private void emitInspectTallying(Statement.InspectTallying it) {
        String rec = recRef(it.target());
        StringBuilder sb = new StringBuilder();
        sb.append("Inspect.on(").append(rec).append(", \"").append(it.target()).append("\")");
        switch (it.tallyType()) {
            case "ALL" -> sb.append(".tallyAll(").append(emitValueExpr(it.tallyArg())).append(")");
            case "LEADING" -> sb.append(".tallyLeading(").append(emitValueExpr(it.tallyArg())).append(".charAt(0))");
            case "CHARACTERS" -> sb.append(".tallyCharacters()");
        }
        if (it.before() != null) sb.append(".before(").append(emitValueExpr(it.before())).append(")");
        if (it.after() != null) sb.append(".after(").append(emitValueExpr(it.after())).append(")");
        line(rec + ".move(\"" + it.tallyField() + "\", (long) " + sb + ".count());");
    }

    private void emitInspectReplacing(Statement.InspectReplacing ir) {
        String rec = recRef(ir.target());
        StringBuilder sb = new StringBuilder();
        sb.append("Inspect.on(").append(rec).append(", \"").append(ir.target()).append("\")");
        String from = emitValueExpr(ir.from());
        String to = emitValueExpr(ir.to());
        switch (ir.replaceType()) {
            case "ALL" -> sb.append(".replaceAll(").append(from).append(".charAt(0), ").append(to).append(".charAt(0))");
            case "LEADING" -> sb.append(".replaceLeading(").append(from).append(".charAt(0), ").append(to).append(".charAt(0))");
            case "FIRST" -> sb.append(".replaceFirst(").append(from).append(".charAt(0), ").append(to).append(".charAt(0))");
        }
        if (ir.before() != null) sb.append(".before(").append(emitValueExpr(ir.before())).append(")");
        if (ir.after() != null) sb.append(".after(").append(emitValueExpr(ir.after())).append(")");
        line(sb + ".apply();");
    }

    private void emitInspectConverting(Statement.InspectConverting ic) {
        String rec = recRef(ic.target());
        StringBuilder sb = new StringBuilder();
        sb.append("Inspect.on(").append(rec).append(", \"").append(ic.target()).append("\")");
        sb.append(".converting(").append(emitValueExpr(ic.from())).append(", ").append(emitValueExpr(ic.to())).append(")");
        if (ic.before() != null) sb.append(".before(").append(emitValueExpr(ic.before())).append(")");
        if (ic.after() != null) sb.append(".after(").append(emitValueExpr(ic.after())).append(")");
        line(sb + ".apply();");
    }

    // ── STRING / UNSTRING ───────────────────────────────────────

    private void emitStringStmt(Statement.StringStmt ss) {
        String rec = recRef(ss.into());
        line("CobolString.into(" + rec + ", \"" + ss.into() + "\")");
        indent++;
        for (Statement.StringSource src : ss.sources()) {
            String val = src.value();
            if (val.startsWith("\"")) {
                line(".literal(" + val + ")");
            } else {
                String srcRec = recRef(val);
                String delim = src.delimiter();
                if (delim == null || "SIZE".equals(delim)) {
                    line(".from(" + srcRec + ", \"" + val + "\").delimitedBySize()");
                } else if ("SPACES".equals(delim)) {
                    line(".from(" + srcRec + ", \"" + val + "\").delimitedBySpaces()");
                } else {
                    line(".from(" + srcRec + ", \"" + val + "\").delimitedBy(" + emitValueExpr(delim) + ")");
                }
            }
        }
        line(".execute();");
        indent--;
    }

    private void emitUnstringStmt(Statement.UnstringStmt us) {
        String rec = recRef(us.source());
        line("CobolUnstring.from(" + rec + ", \"" + us.source() + "\")");
        indent++;
        for (int i = 0; i < us.delimiters().size(); i++) {
            String d = us.delimiters().get(i);
            if (i == 0) line(".delimitedBy(" + emitValueExpr(d) + ")");
            else line(".orDelimitedBy(" + emitValueExpr(d) + ")");
        }
        for (String field : us.into()) {
            line(".into(" + recRef(field) + ", \"" + field + "\")");
        }
        line(".execute();");
        indent--;
    }

    // ── SEARCH ──────────────────────────────────────────────────

    private void emitSearchStmt(Statement.SearchStmt sr) {
        String rec = recRef(sr.table());
        line("Search.table(" + rec + ", \"" + sr.table() + "\")");
        indent++;
        if (!sr.atEnd().isEmpty()) {
            line(".atEnd(() -> {");
            indent++;
            for (Statement s : sr.atEnd()) emitStatement(s);
            indent--;
            line("})");
        }
        for (Statement.SearchWhen w : sr.whenClauses()) {
            if (w.conditionRight() != null) {
                line(".when(idx -> " + rec + ".getString(\"" + w.condition() + "\", idx).trim().equals("
                    + emitValueExpr(w.conditionRight()) + "), idx -> {");
            } else {
                line(".when(idx -> " + rec + ".is(\"" + w.condition() + "\"), idx -> {");
            }
            indent++;
            for (Statement s : w.body()) emitStatement(s);
            indent--;
            line("})");
        }
        line(".execute();");
        indent--;
    }

    // ── SORT ────────────────────────────────────────────────────

    private void emitSortStmt(Statement.SortStmt so) {
        line("CobolSort.on(" + toJavaFieldName(so.sortFile()) + ")");
        indent++;
        for (Statement.SortKey key : so.keys()) {
            if (key.ascending()) {
                line(".ascending(\"" + key.field() + "\")");
            } else {
                line(".descending(\"" + key.field() + "\")");
            }
        }
        if (so.hasInputProc()) {
            line(".inputProcedure(input -> {");
            indent++;
            line("ctx.perform(\"" + so.inputProc() + "\");");
            indent--;
            line("})");
        }
        if (so.hasOutputProc()) {
            line(".outputProcedure(output -> {");
            indent++;
            line("ctx.perform(\"" + so.outputProc() + "\");");
            indent--;
            line("})");
        }
        line(".execute();");
        indent--;
    }

    private void emitCall(Statement.Call call) {
        String target = call.target().toLowerCase().replace("\"", "");
        String returning = call.returning();
        List<Statement.CallParam> params = call.params();

        String retAssign = "";
        if (returning != null) {
            retAssign = recRef(returning) + ".move(\"" + returning + "\", Decimal.of(";
        }

        // Map POSIX/system functions to SystemCall methods
        switch (target) {
            case "open" -> {
                String path = emitCallParam(params, 0);
                String flags = params.size() > 1 ? emitCallParam(params, 1) : "SystemCall.O_RDONLY";
                String call_ = "sys.open(" + path + ", " + flags + ")";
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", (long) " + call_ + ");");
                } else {
                    line(call_ + ";");
                }
            }
            case "close" -> {
                String fd = emitCallParam(params, 0);
                line("sys.close((int) " + fd + ");");
            }
            case "read" -> {
                String fd = emitCallParam(params, 0);
                String buf = emitCallParam(params, 1);
                String len = emitCallParam(params, 2);
                String call_ = "sys.read((int) " + fd + ", " + buf + ".rawBuffer(), " + len + ")";
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", (long) " + call_ + ");");
                } else {
                    line(call_ + ";");
                }
            }
            case "write" -> {
                String fd = emitCallParam(params, 0);
                String buf = emitCallParam(params, 1);
                String len = emitCallParam(params, 2);
                String call_ = "sys.write((int) " + fd + ", " + buf + ".rawBuffer(), " + len + ")";
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", (long) " + call_ + ");");
                } else {
                    line(call_ + ";");
                }
            }
            case "getenv" -> {
                String name = emitCallParam(params, 0);
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", sys.getenv(" + name + "));");
                } else {
                    line("sys.getenv(" + name + ");");
                }
            }
            case "system" -> {
                String cmd = emitCallParam(params, 0);
                String call_ = "sys.system(" + cmd + ")";
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", (long) " + call_ + ");");
                } else {
                    line(call_ + ";");
                }
            }
            case "socket" -> {
                String domain = emitCallParam(params, 0);
                String type = emitCallParam(params, 1);
                String call_ = "sys.socket((int) " + domain + ", (int) " + type + ")";
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", (long) " + call_ + ");");
                } else {
                    line(call_ + ";");
                }
            }
            case "connect" -> {
                String fd = emitCallParam(params, 0);
                String host = emitCallParam(params, 1);
                String port = emitCallParam(params, 2);
                line("sys.connect((int) " + fd + ", " + host + ", (int) " + port + ");");
            }
            case "send" -> {
                String fd = emitCallParam(params, 0);
                String buf = emitCallParam(params, 1);
                String len = emitCallParam(params, 2);
                String call_ = "sys.send((int) " + fd + ", " + buf + ".rawBuffer(), " + len + ")";
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", (long) " + call_ + ");");
                } else {
                    line(call_ + ";");
                }
            }
            case "recv" -> {
                String fd = emitCallParam(params, 0);
                String buf = emitCallParam(params, 1);
                String len = emitCallParam(params, 2);
                String call_ = "sys.recv((int) " + fd + ", " + buf + ".rawBuffer(), " + len + ")";
                if (returning != null) {
                    line(recRef(returning) + ".move(\"" + returning + "\", (long) " + call_ + ");");
                } else {
                    line(call_ + ";");
                }
            }
            default -> {
                // Unknown CALL target — record as error, emit comment preserving original
                diag.error("emitter", 0, "CALL \"" + target + "\"",
                    "No SystemCall mapping for '" + target + "'. "
                    + "This CALL cannot be translated automatically. "
                    + "Provide a custom SystemCall implementation or translate manually.");
                line("// ERROR: CALL \"" + target + "\" not supported in this implementation");
                StringBuilder args = new StringBuilder();
                for (int i = 0; i < params.size(); i++) {
                    if (i > 0) args.append(", ");
                    args.append(emitCallParam(params, i));
                }
                line("// Original: CALL \"" + target + "\" USING " + args
                    + (returning != null ? " RETURNING " + returning : "") + "");
                line("throw new UnsupportedOperationException(\"CALL \\\"" + target
                    + "\\\" not supported\");");
            }
        }
    }

    private String emitCallParam(List<Statement.CallParam> params, int index) {
        if (index >= params.size()) return "0";
        String val = params.get(index).value();
        if (val.startsWith("\"")) return val; // string literal
        if (val.matches("-?\\d+\\.?\\d*")) return val; // numeric literal
        // Field reference — get the value
        return recRef(val) + ".getString(\"" + val + "\").trim()";
    }

    // ── Expression helpers ──────────────────────────────────────────

    /**
     * Walk an Expr tree and produce Java source code.
     */
    private String emitExpr(Expr expr) {
        if (expr == null) return "null";
        if (expr instanceof Expr.NumericLit n) return "Decimal.of(\"" + n.value() + "\")";
        if (expr instanceof Expr.StringLit s) return "\"" + s.value() + "\"";
        if (expr instanceof Expr.Figurative f) return switch (f.name()) {
            case "SPACES" -> "\"\"";
            case "ZEROS" -> "Decimal.ZERO";
            case "HIGH-VALUES" -> "\"\\u00FF\"";
            case "LOW-VALUES" -> "\"\\u0000\"";
            default -> "\"\"";
        };
        if (expr instanceof Expr.FunctionCall fc) return emitFunctionCall(fc);
        if (expr instanceof Expr.FieldRef fr) return emitFieldRef(fr);
        if (expr instanceof Expr.BinaryOp op) return emitBinaryOp(op);
        if (expr instanceof Expr.Negate neg) return emitExpr(neg.operand()) + ".negate()";
        return "null";
    }

    private String emitFieldRef(Expr.FieldRef fr) {
        if (fr.isRefMod()) {
            return recRef(fr.name()) + ".substring(\"" + fr.name() + "\", "
                + emitRefModArg(fr.refModPos()) + ", " + emitRefModArg(fr.refModLen()) + ")";
        }
        if (fr.isSubscripted()) {
            return recRef(fr.name()) + ".getDecimal(\"" + fr.name() + "\", " + emitSubscript(fr.subscript()) + ")";
        }
        return recRef(fr.name()) + ".getDecimal(\"" + fr.name() + "\")";
    }

    /** Emit a reference modification argument as a raw integer. */
    private String emitRefModArg(Expr expr) {
        if (expr instanceof Expr.NumericLit n) return n.value();
        if (expr instanceof Expr.FieldRef fr) return "(int) " + emitFieldRef(fr) + ".toLong()";
        return "(int) " + emitExpr(expr) + ".toLong()";
    }

    /** Emit a subscript as an int expression (0-based for Java, but COBOL is 1-based). */
    private String emitSubscript(Expr expr) {
        if (expr instanceof Expr.NumericLit n) {
            // COBOL subscripts are 1-based, Record uses 0-based
            int val = Integer.parseInt(n.value()) - 1;
            return String.valueOf(val);
        }
        if (expr instanceof Expr.FieldRef fr) {
            // Field value as subscript — convert to 0-based
            return "(int) " + recRef(fr.name()) + ".getLong(\"" + fr.name() + "\") - 1";
        }
        return "(int) " + emitExpr(expr) + ".toLong() - 1";
    }

    private String emitFunctionCall(Expr.FunctionCall fc) {
        return switch (fc.name().toUpperCase()) {
            case "CURRENT-DATE" -> "Intrinsic.currentDate()";
            case "LENGTH" -> fc.args().isEmpty() ? "Intrinsic.length()"
                : "Intrinsic.length(" + emitExpr(fc.args().get(0)) + ")";
            case "UPPER-CASE" -> fc.args().isEmpty() ? "Intrinsic.upperCase(\"\")"
                : "Intrinsic.upperCase(" + emitExpr(fc.args().get(0)) + ")";
            case "LOWER-CASE" -> fc.args().isEmpty() ? "Intrinsic.lowerCase(\"\")"
                : "Intrinsic.lowerCase(" + emitExpr(fc.args().get(0)) + ")";
            case "TRIM" -> fc.args().isEmpty() ? "Intrinsic.trim(\"\")"
                : "Intrinsic.trim(" + emitExpr(fc.args().get(0)) + ")";
            default -> "Intrinsic." + fc.name().toLowerCase().replace("-", "") + "("
                + String.join(", ", fc.args().stream().map(this::emitExpr).toList()) + ")";
        };
    }

    private String emitBinaryOp(Expr.BinaryOp op) {
        String left = emitExpr(op.left());
        String right = emitExpr(op.right());
        return switch (op.operator()) {
            case "+" -> left + ".add(" + right + ")";
            case "-" -> left + ".subtract(" + right + ")";
            case "*" -> left + ".multiply(" + right + ")";
            case "/" -> left + ".divide(" + right + ", 10)";
            case "**" -> left + ".pow(" + right + ".toInt())";
            default -> left;
        };
    }

    /**
     * For EVALUATE TRUE, the WHEN value is a condition name or expression
     * that should produce a boolean, not a value comparison.
     */
    private String emitEvaluateTrueCondition(String value) {
        if (value == null) return "true";
        // Strip quotes if parseValueLiteral wrapped it
        String clean = value;
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() > 2) {
            clean = clean.substring(1, clean.length() - 1);
        }
        // If it's a COBOL identifier (could be an 88-level condition name), emit as is()
        if (clean.matches("[A-Z][A-Z0-9-]*")) {
            return findRecordFor(clean) + ".is(\"" + clean + "\")";
        }
        // If it's a numeric literal, just return it as a boolean expression
        if (clean.matches("-?\\d+\\.?\\d*")) {
            return clean + " != 0";
        }
        // Fallback: treat as an expression
        return emitValueExpr(value) + " != null";
    }

    private String emitCondition(Statement.Condition cond) {
        if (cond == null) return "true";

        if (cond instanceof Statement.Condition.Not not) {
            return "!(" + emitCondition(not.inner()) + ")";
        }
        if (cond instanceof Statement.Condition.And and) {
            return "(" + emitCondition(and.left()) + " && " + emitCondition(and.right()) + ")";
        }
        if (cond instanceof Statement.Condition.Or or) {
            return "(" + emitCondition(or.left()) + " || " + emitCondition(or.right()) + ")";
        }
        if (cond instanceof Statement.Condition.ConditionName cn) {
            String prefix = cn.negated() ? "!" : "";
            return prefix + findRecordFor(cn.name()) + ".is(\"" + cn.name() + "\")";
        }
        if (cond instanceof Statement.Condition.Simple s) {
            String prefix = s.negated() ? "!" : "";
            Expr left = s.left();
            Expr right = s.right();

            // Extract field name for special-case handling
            String leftFieldName = (left instanceof Expr.FieldRef fr) ? fr.name() : null;

            // Class conditions (no right operand)
            if ("IS-NUMERIC".equals(s.operator())) {
                if (leftFieldName != null) {
                    return prefix + recRef(leftFieldName) + ".getString(\"" + leftFieldName + "\").trim().matches(\"[0-9.+-]*\")";
                }
                return prefix + emitExpr(left) + ".toString().matches(\"[0-9.+-]*\")";
            }
            if ("IS-ALPHABETIC".equals(s.operator())) {
                if (leftFieldName != null) {
                    return prefix + recRef(leftFieldName) + ".getString(\"" + leftFieldName + "\").trim().matches(\"[A-Za-z ]*\")";
                }
                return prefix + emitExpr(left) + ".toString().matches(\"[A-Za-z ]*\")";
            }

            // Figurative constants in comparisons
            if (right instanceof Expr.Figurative fig && leftFieldName != null) {
                return switch (fig.name()) {
                    case "SPACES" -> prefix + recRef(leftFieldName) + ".getString(\"" + leftFieldName + "\").trim().isEmpty()";
                    case "ZEROS" -> prefix + recRef(leftFieldName) + ".getDecimal(\"" + leftFieldName + "\").isZero()";
                    case "HIGH-VALUES" -> prefix + "java.util.Arrays.equals(" + recRef(leftFieldName)
                        + ".getBytes(\"" + leftFieldName + "\"), new byte[]{(byte)0xFF})";
                    case "LOW-VALUES" -> prefix + "java.util.Arrays.equals(" + recRef(leftFieldName)
                        + ".getBytes(\"" + leftFieldName + "\"), new byte[" + recRef(leftFieldName)
                        + ".fieldDef(\"" + leftFieldName + "\").size()])";
                    default -> prefix + emitExpr(left) + ".equalTo(" + emitExpr(right) + ")";
                };
            }

            String leftExpr = emitExpr(left);
            String rightExpr = emitExpr(right);
            String op = switch (s.operator()) {
                case "="  -> ".equalTo(" + rightExpr + ")";
                case ">"  -> ".greaterThan(" + rightExpr + ")";
                case "<"  -> ".lessThan(" + rightExpr + ")";
                case ">=" -> ".greaterOrEqual(" + rightExpr + ")";
                case "<=" -> ".lessOrEqual(" + rightExpr + ")";
                default   -> ".equalTo(" + rightExpr + ")";
            };
            return prefix + leftExpr + op;
        }

        return "true"; // fallback
    }

    private String emitValueExpr(String val) {
        if (val == null) return "null";
        if (val.startsWith("\"")) return val; // string literal
        if (val.equals("SPACES") || val.equals("SPACE")) return "\"\"";
        if (val.equals("ZEROS") || val.equals("ZEROES")) return "Decimal.ZERO";
        if (val.matches("-?\\d+\\.?\\d*")) return "Decimal.of(\"" + val + "\")";
        // Reference modification: FIELD(pos:len)
        if (val.matches(".*\\(\\d+:\\d+\\)")) {
            String field = val.substring(0, val.indexOf('('));
            String inside = val.substring(val.indexOf('(') + 1, val.indexOf(')'));
            String[] parts = inside.split(":");
            return recRef(field) + ".substring(\"" + field + "\", " + parts[0] + ", " + parts[1] + ")";
        }
        // Assume it's a field reference
        return fieldGet(val);
    }

    private String fieldGet(String fieldName) {
        if (fieldName == null) return "null";
        if (fieldName.matches("-?\\d+\\.?\\d*")) return "Decimal.of(\"" + fieldName + "\")";
        return recRef(fieldName) + ".getDecimal(\"" + fieldName + "\")";
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
