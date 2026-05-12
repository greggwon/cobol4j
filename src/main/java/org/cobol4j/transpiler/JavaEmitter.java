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

import org.cobol4j.Pic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final Map<String, FieldInfo> symbolTable;

    /** Type information for a field, built from DATA DIVISION. */
    private record FieldInfo(String name, String recordName, boolean numeric,
                              boolean isGroup, boolean isCondition) {}

    private int fillerCounter = 0;

    private JavaEmitter(CobolProgram program, TranspileDiagnostics diag) {
        this.program = program;
        this.className = toJavaClassName(program.programId());
        this.diag = diag;
        this.symbolTable = buildSymbolTable();
    }

    /** Build a symbol table from all DATA DIVISION entries. */
    private Map<String, FieldInfo> buildSymbolTable() {
        Map<String, FieldInfo> table = new java.util.LinkedHashMap<>();
        // Implicit COBOL registers — not in any record but referenced as fields
        table.put("SQLCODE", new FieldInfo("SQLCODE", "__IMPLICIT__", true, false, false));
        table.put("RETURN-CODE", new FieldInfo("RETURN-CODE", "__IMPLICIT__", true, false, false));
        table.put("SORT-RETURN", new FieldInfo("SORT-RETURN", "__IMPLICIT__", true, false, false));
        List<RecordGroup> groups = groupByLevel01();
        for (RecordGroup g : groups) {
            String recName = g.name;
            // The 01-level itself
            table.put(recName, new FieldInfo(recName, recName, false, true, false));
            for (CobolProgram.DataEntry e : g.children) {
                boolean isNumeric = false;
                boolean isGroup = (e.pic() == null && !isPointerUsage(e));
                if (e.pic() != null) {
                    Pic pic = Pic.parse(e.pic());
                    isNumeric = pic.isNumeric();
                }
                table.put(e.name(), new FieldInfo(e.name(), recName, isNumeric, isGroup, false));
                // Register condition names
                for (CobolProgram.Condition88 cond : e.conditions()) {
                    table.put(cond.name(), new FieldInfo(cond.name(), recName, false, false, true));
                }
            }
        }
        return table;
    }

    /** Look up whether a field is numeric. */
    /**
     * Map implicit COBOL registers to Java read expressions.
     * Returns null if the name is not an implicit register.
     */
    private String implicitRegister(String name) {
        return switch (name.toUpperCase()) {
            case "SQLCODE"     -> "Decimal.of(session.sqlCode())";
            case "RETURN-CODE" -> "Decimal.of(System.getProperty(\"cobol4j.returnCode\", \"0\"))";
            case "SORT-RETURN" -> "Decimal.ZERO";
            default -> null;
        };
    }

    private boolean isImplicitRegister(String name) {
        return implicitRegister(name) != null;
    }

    /** Generate a MOVE TO for an implicit register. */
    private String emitImplicitRegisterMove(String target, String value) {
        return switch (target.toUpperCase()) {
            case "RETURN-CODE" -> "System.setProperty(\"cobol4j.returnCode\", String.valueOf(" + value + "));";
            case "SQLCODE"     -> "// MOVE to SQLCODE — read-only register, no-op";
            default            -> "// MOVE to " + target + " — implicit register, no-op";
        };
    }

    private boolean isNumericField(String fieldName) {
        String name = fieldName.contains("(") ? fieldName.substring(0, fieldName.indexOf('(')) : fieldName;
        FieldInfo info = symbolTable.get(name);
        return info != null && info.numeric;
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
        if (program.dataEntries().isEmpty() && program.fileBindings().isEmpty()) return;

        // Emit CobolFile declarations for FILE SECTION FD entries
        if (!program.fileBindings().isEmpty()) {
            line("// ── File Descriptions ───────────────────────────────────");
            line("");
            for (CobolProgram.FileBinding fb : program.fileBindings()) {
                String fileVar = toJavaFieldName(fb.fileName());
                String recVar = toJavaFieldName(fb.recordName());
                StringBuilder sb = new StringBuilder();
                sb.append("private CobolFile ").append(fileVar)
                  .append(" = CobolFile.sequential(\"").append(fb.fileName()).append("\")");
                if (fb.recordSize() > 0) {
                    sb.append("\n").append("    ".repeat(indent + 1))
                      .append(".recordSize(").append(fb.recordSize()).append(")");
                }
                sb.append("\n").append("    ".repeat(indent + 1)).append(".build();");
                line(sb.toString());
                line("");
            }
        }

        if (!program.dataEntries().isEmpty()) {
            line("// ── Working Storage ─────────────────────────────────────");
            line("");

            // Group entries by 01/77 level
            List<RecordGroup> groups = groupByLevel01();

            for (RecordGroup group : groups) {
                emitRecordDefinition(group);
            }
            line("");
        }
    }

    private void emitRecordDefinition(RecordGroup group) {
        String recVar = toJavaFieldName(group.name);
        line("private final Record " + recVar + " = Record.define(\"" + group.name + "\")");
        indent++;

        emitDataEntries(group.children, 0, group.children.size());

        line(".build();");
        indent--;
        line("");
    }

    /**
     * Emit a range of data entries, respecting group nesting.
     * When a group item is encountered (pic == null, not level-88),
     * its children (entries with higher level numbers) are emitted
     * inside a .group() lambda.
     */
    private void emitDataEntries(List<CobolProgram.DataEntry> entries, int start, int end) {
        int i = start;
        while (i < end) {
            CobolProgram.DataEntry entry = entries.get(i);
            if (entry.level() == 88) { i++; continue; } // handled inline

            if (entry.pic() == null && !isPointerUsage(entry)) {
                // Group item — find its children (entries with higher level number)
                int groupLevel = entry.level();
                int childStart = i + 1;
                int childEnd = childStart;
                while (childEnd < end && entries.get(childEnd).level() > groupLevel) {
                    childEnd++;
                }

                if (entry.redefines() != null) {
                    // REDEFINES — overlay on existing field
                    line(".redefines(\"" + entry.redefines() + "\", \"" + entry.name() + "\", g -> g");
                    indent++;
                    emitDataEntries(entries, childStart, childEnd);
                    indent--;
                    line(")");
                } else {
                    // Regular group
                    line(".group(\"" + entry.name() + "\", g -> g");
                    indent++;
                    emitDataEntries(entries, childStart, childEnd);
                    indent--;
                    line(")");
                }
                if (entry.occurs() > 0) {
                    line(".occurs(" + entry.occurs() + ")");
                }

                i = childEnd;
            } else {
                emitElementaryEntry(entry);
                i++;
            }
        }
    }

    private boolean isPointerUsage(CobolProgram.DataEntry entry) {
        return entry.usage() != null
            && (entry.usage().equals("POINTER") || entry.usage().equals("FUNCTION-POINTER"));
    }

    private void emitElementaryEntry(CobolProgram.DataEntry entry) {
        if (isPointerUsage(entry) && entry.pic() == null) {
            line(".pic(\"" + entry.name() + "\", \"X(256)\") // " + entry.usage());
            return;
        }

        if (entry.pic() == null) {
            // Should not happen — groups are handled above
            throw new Transpiler.TranspileException(
                "Elementary entry '" + entry.name() + "' has no PIC clause");
        }

        // Generate unique names for FILLER fields
        String fieldName = entry.name();
        if (fieldName.equalsIgnoreCase("FILLER")) {
            fieldName = "FILLER-" + (++fillerCounter);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(".pic(\"").append(fieldName).append("\", \"").append(entry.pic()).append("\")");

        if (entry.usage() != null) {
            String u = entry.usage().toUpperCase().replace("-", "");
            switch (u) {
                case "COMP3", "COMPUTATIONAL3", "PACKEDDECIMAL" -> sb.append(".comp3()");
                case "COMP", "COMP4", "COMPUTATIONAL", "COMPUTATIONAL4", "BINARY" -> sb.append(".comp()");
                case "COMP5", "COMPUTATIONAL5" -> sb.append(".comp5()");
                case "POINTER", "FUNCTIONPOINTER" -> sb.append(" // " + entry.usage());
            }
        }

        if (entry.signClause() != null) {
            switch (entry.signClause()) {
                case "LEADING" -> sb.append(".signLeading()");
                case "TRAILING_SEPARATE" -> sb.append(".signTrailingSeparate()");
                case "LEADING_SEPARATE" -> sb.append(".signLeadingSeparate()");
            }
        }

        if (entry.occurs() > 0) {
            sb.append(".occurs(").append(entry.occurs()).append(")");
        }

        if (entry.value() != null) {
            sb.append(".value(").append(formatValue(entry.value())).append(")");
        }

        if (entry.isGlobal()) sb.append(" // GLOBAL");
        if (entry.isExternal()) sb.append(" // EXTERNAL");

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

        // Bind files to their records
        for (CobolProgram.FileBinding fb : program.fileBindings()) {
            line(".file(" + toJavaFieldName(fb.fileName()) + ", "
                + toJavaFieldName(fb.recordName()) + ")");
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
        else if (stmt instanceof Statement.Accept a) emitAccept(a);
        else if (stmt instanceof Statement.Open o) line("ctx.open(" + toJavaFieldName(o.fileName()) + ", CobolFile.OpenMode." + o.mode().toUpperCase().replace("-", "") + ");");
        else if (stmt instanceof Statement.Close c) line("ctx.close(" + toJavaFieldName(c.fileName()) + ");");
        else if (stmt instanceof Statement.Read r) emitRead(r);
        else if (stmt instanceof Statement.Write w) emitWrite(w);
        else if (stmt instanceof Statement.GoTo g) line("ctx.goTo(\"" + g.paragraph() + "\");");
        else if (stmt instanceof Statement.StopRun s) line("ctx.stopRun();");
        else if (stmt instanceof Statement.ExitParagraph e) line("ctx.exitParagraph();");
        else if (stmt instanceof Statement.SetCondition sc) line(findRecordFor(sc.conditionName()) + ".set(\"" + sc.conditionName() + "\");");
        else if (stmt instanceof Statement.Invoke inv) emitInvoke(inv);
        else if (stmt instanceof Statement.CodecVerb cv) emitCodecVerb(cv);
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
        else if (stmt instanceof Statement.Continue c) line("// CONTINUE");
        else if (stmt instanceof Statement.SetIndex si) emitSetIndex(si);
        else if (stmt instanceof Statement.SetIndexTo sit) emitSetIndexTo(sit);
        else if (stmt instanceof Statement.Start st) emitStart(st);
        else if (stmt instanceof Statement.Release rel) emitRelease(rel);
        else if (stmt instanceof Statement.ReturnStmt ret) emitReturn(ret);
        else if (stmt instanceof Statement.Cancel cn) emitCancel(cn);
        else if (stmt instanceof Statement.Alter al) emitAlter(al);
        else if (stmt instanceof Statement.Enter en) emitEnter(en);
        else if (stmt instanceof Statement.Generate gen) emitGenerate(gen);
        else if (stmt instanceof Statement.Terminate term) emitTerminate(term);
        else if (stmt instanceof Statement.Suppress sup) emitSuppress();
        else if (stmt instanceof Statement.Initiate ini) emitInitiate(ini);
        else if (stmt instanceof Statement.UseDeclarative use) emitUseDeclarative(use);
        else if (stmt instanceof Statement.Unsupported u) emitUnsupported(u);
        // WhenClause, CallParam, StringSource, SearchWhen, SortKey handled within parent
    }

    // ── SET index ──────────────────────────────────────────────────

    private void emitSetIndex(Statement.SetIndex si) {
        String rec = recRef(si.indexName());
        String val = emitExpr(si.value());
        if (si.direction().equalsIgnoreCase("UP")) {
            line(rec + ".add(\"" + si.indexName() + "\", " + val + ");");
        } else {
            line(rec + ".subtract(\"" + si.indexName() + "\", " + val + ");");
        }
    }

    private void emitSetIndexTo(Statement.SetIndexTo sit) {
        line(recRef(sit.indexName()) + ".move(\"" + sit.indexName() + "\", " + emitExpr(sit.value()) + ");");
    }

    // ── START — keyed file positioning ─────────────────────────────

    private void emitStart(Statement.Start st) {
        String fileVar = toJavaFieldName(st.fileName());
        String condition = switch (st.condition()) {
            case "GREATER" -> "CobolFile.StartCondition.GREATER";
            case "NOT_LESS" -> "CobolFile.StartCondition.NOT_LESS";
            default -> "CobolFile.StartCondition.EQUAL";
        };
        if (st.keyName() != null) {
            line(fileVar + ".start(\"" + st.keyName() + "\", "
                + recRef(st.keyName()) + ".getString(\"" + st.keyName() + "\").trim(), "
                + condition + ");");
        } else {
            line(fileVar + ".start(" + condition + ");");
        }
    }

    // ── RELEASE / RETURN — SORT input/output ───────────────────────

    private void emitRelease(Statement.Release rel) {
        String recVar = toJavaFieldName(rel.recordName());
        if (rel.from() != null) {
            line(recVar + ".loadFrom(" + recRef(rel.from()) + ".buffer());");
        }
        line("sortInput.release(); // RELEASE " + rel.recordName());
    }

    private void emitReturn(Statement.ReturnStmt ret) {
        String intoVar = ret.into() != null ? toJavaFieldName(ret.into()) : null;
        line("if (sortOutput.returnRecord()) {");
        indent++;
        if (intoVar != null) {
            line(intoVar + ".loadFrom(sortOutput.currentRecord());");
        }
        if (ret.notAtEnd() != null) {
            for (Statement s : ret.notAtEnd()) emitStatement(s);
        }
        indent--;
        line("} else {");
        indent++;
        if (ret.atEnd() != null) {
            for (Statement s : ret.atEnd()) emitStatement(s);
        }
        indent--;
        line("}");
    }

    // ── No-op verbs (logged at runtime) ────────────────────────────

    private void emitCancel(Statement.Cancel cn) {
        line("LOG.warning(\"CANCEL " + cn.programName()
            + " — no-op in Java (classloader manages program lifecycle)\");");
    }

    private void emitAlter(Statement.Alter al) {
        line("LOG.warning(\"ALTER " + al.paragraph() + " TO " + al.target()
            + " at COBOL line " + al.line()
            + " — not supported. Refactor to IF/EVALUATE.\");");
    }

    private void emitEnter(Statement.Enter en) {
        line("LOG.warning(\"ENTER " + en.language()
            + " at COBOL line " + en.line()
            + " — dialect-specific, no-op in Java\");");
    }

    // ── Report Writer (delegates to service interface) ──────────────

    private void emitGenerate(Statement.Generate gen) {
        line("LOG.warning(\"GENERATE " + gen.reportOrDetail()
            + " — Report Writer not yet implemented. "
            + "Provide a ReportService implementation.\");");
    }

    private void emitTerminate(Statement.Terminate term) {
        line("LOG.warning(\"TERMINATE " + term.reportName()
            + " — Report Writer not yet implemented.\");");
    }

    private void emitSuppress() {
        line("LOG.warning(\"SUPPRESS PRINTING — Report Writer not yet implemented.\");");
    }

    private void emitInitiate(Statement.Initiate ini) {
        line("LOG.warning(\"INITIATE " + ini.reportName()
            + " — Report Writer not yet implemented.\");");
    }

    // ── USE declarative ────────────────────────────────────────────

    private void emitUseDeclarative(Statement.UseDeclarative use) {
        line("LOG.warning(\"USE AFTER " + use.scope()
            + " ON " + (use.fileName() != null ? use.fileName() : "ALL FILES")
            + " at COBOL line " + use.line()
            + " — Declaratives not yet implemented. "
            + "Register a file error handler via CobolFile.onError().\");");
    }

    // ── Unsupported (compile error marker) ─────────────────────────

    private void emitUnsupported(Statement.Unsupported u) {
        line("// COBOL line " + u.line() + ": " + u.rawCobol());
        line("// " + u.hint());
        line("COBOL4J_UNSUPPORTED_" + u.verb() + "; // " + u.rawCobol());
    }

    private void emitMove(Statement.Move m) {
        for (String target : m.targets()) {
            String val = emitExpr(m.source());
            // Implicit registers (RETURN-CODE, SQLCODE, etc.)
            if (isImplicitRegister(target)) {
                line(emitImplicitRegisterMove(target, val));
                continue;
            }
            if (target.contains("(") && target.contains(")")) {
                // Subscripted or ref-mod target
                String fieldName = target.substring(0, target.indexOf('('));
                String inside = target.substring(target.indexOf('(') + 1, target.indexOf(')'));
                if (inside.contains(":")) {
                    // MOVE value TO FIELD(pos:len) — reference modification on target
                    String[] parts = inside.split(":");
                    line(recRef(fieldName) + ".moveSubstring(\"" + fieldName + "\", "
                        + parts[0] + ", " + parts[1] + ", " + val + ");");
                } else {
                    // MOVE value TO FIELD(idx) — subscript, 1-based to 0-based
                    String idx = emitSubscriptString(inside);
                    line(recRef(fieldName) + ".move(\"" + fieldName + "\", " + idx + ", " + val + ");");
                }
            } else {
                line(recRef(target) + ".move(\"" + target + "\", " + val + ");");
            }
        }
    }

    /** Convert a subscript string (from readOperand) to a 0-based int expression. */
    private String emitSubscriptString(String sub) {
        if (sub.matches("\\d+")) {
            return String.valueOf(Integer.parseInt(sub) - 1);
        }
        // Field reference as subscript
        return "(int) " + recRef(sub) + ".getLong(\"" + sub + "\") - 1";
    }

    private void emitMoveCorr(Statement.MoveCorresponding m) {
        line(recRef(m.target()) + ".moveCorresponding(" + recRef(m.source()) + ");");
    }

    private void emitAdd(Statement.Add a) {
        // Sum all sources into one expression
        String sourceSum = emitSourceSum(a.sources());

        if (!a.givingTargets().isEmpty()) {
            // ADD source1 [source2 ...] GIVING target1 [target2 ...]
            // Each target = source1 + source2 + ...
            String handler = emitSizeErrorHandler(a.onSizeError(), a.notOnSizeError());
            for (String giving : a.givingTargets()) {
                if (a.sources().size() == 2) {
                    line("Arithmetic.add(" + emitExpr(a.sources().get(0)) + ", " + emitExpr(a.sources().get(1)) + ")");
                } else {
                    line("Arithmetic.add(" + sourceSum + ", Decimal.of(\"0\"))");
                }
                line("    .giving(" + recRef(giving) + ".field(\"" + giving + "\"))");
                if (a.rounded()) line("    .rounded()");
                if (!handler.isEmpty()) line("    .onSizeError(" + handler + ")");
                line("    .execute();");
            }
        } else {
            // ADD source1 [source2 ...] TO target1 target2 ...
            // each target += sum of all sources
            String handler = emitSizeErrorHandler(a.onSizeError(), a.notOnSizeError());
            for (String target : a.targets()) {
                line(recRef(target) + ".add(\"" + target + "\", " + sourceSum
                    + (handler.isEmpty() ? "" : ", " + handler) + ");");
            }
        }
    }

    private void emitSubtract(Statement.Subtract s) {
        // Sum all subtrahends — SUBTRACT a b FROM c means c = c - (a + b)
        String subSum = emitSourceSum(s.subtrahends());

        if (!s.givingTargets().isEmpty()) {
            // SUBTRACT sub1 [sub2 ...] FROM minuend GIVING target1 [target2 ...]
            // Each target = minuend - (sub1 + sub2 + ...)
            String minuend = s.targets().isEmpty()
                ? "Decimal.of(\"0\")"
                : fieldGet(s.targets().get(0));
            String handler = emitSizeErrorHandler(s.onSizeError(), s.notOnSizeError());
            for (String giving : s.givingTargets()) {
                line("Arithmetic.subtract(" + subSum + ", " + minuend + ")");
                line("    .giving(" + recRef(giving) + ".field(\"" + giving + "\"))");
                if (s.rounded()) line("    .rounded()");
                if (!handler.isEmpty()) line("    .onSizeError(" + handler + ")");
                line("    .execute();");
            }
        } else {
            // SUBTRACT sub1 [sub2 ...] FROM target1 target2 ...
            // each target -= sum of all subtrahends
            String handler = emitSizeErrorHandler(s.onSizeError(), s.notOnSizeError());
            for (String target : s.targets()) {
                line(recRef(target) + ".subtract(\"" + target + "\", " + subSum
                    + (handler.isEmpty() ? "" : ", " + handler) + ");");
            }
        }
    }

    /** Fold a list of Expr sources into a single sum expression. */
    private String emitSourceSum(List<Expr> sources) {
        if (sources.size() == 1) return emitExpr(sources.get(0));
        // Chain: source1.add(source2).add(source3)...
        StringBuilder sb = new StringBuilder(emitExpr(sources.get(0)));
        for (int i = 1; i < sources.size(); i++) {
            sb.append(".add(").append(emitExpr(sources.get(i))).append(")");
        }
        return sb.toString();
    }

    private void emitMultiply(Statement.Multiply m) {
        if (!m.givingTargets().isEmpty()) {
            String handler = emitSizeErrorHandler(m.onSizeError(), m.notOnSizeError());
            for (String giving : m.givingTargets()) {
                line("Arithmetic.multiply(" + emitExpr(m.a()) + ", " + emitExpr(m.by()) + ")");
                line("    .giving(" + recRef(giving) + ".field(\"" + giving + "\"))");
                if (m.rounded()) line("    .rounded()");
                if (!handler.isEmpty()) line("    .onSizeError(" + handler + ")");
                line("    .execute();");
            }
        } else {
            String byName = (m.by() instanceof Expr.FieldRef fr) ? fr.name() : "RESULT";
            line(recRef(byName) + ".multiply(\"" + byName + "\", " + emitExpr(m.a()) + ");");
        }
    }

    private void emitDivide(Statement.Divide d) {
        if (!d.givingTargets().isEmpty()) {
            String handler = emitSizeErrorHandler(d.onSizeError(), d.notOnSizeError());
            for (String giving : d.givingTargets()) {
                line("Arithmetic.divide(" + emitExpr(d.dividend()) + ", " + emitExpr(d.divisor()) + ")");
                line("    .giving(" + recRef(giving) + ".field(\"" + giving + "\"))");
                if (d.remainder() != null) {
                    line("    .remainder(" + recRef(d.remainder()) + ".field(\"" + d.remainder() + "\"))");
                }
                if (d.rounded()) line("    .rounded()");
                if (!handler.isEmpty()) line("    .onSizeError(" + handler + ")");
                line("    .execute();");
            }
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
        if (ip.isTimes()) {
            // PERFORM n TIMES ... END-PERFORM
            line("for (int _i = 0; _i < " + ip.times() + "; _i++) {");
            indent++;
            for (Statement s : ip.body()) emitStatement(s);
            indent--;
            line("}");
        } else if (ip.isVarying()) {
            // PERFORM VARYING field FROM x BY y UNTIL cond ... END-PERFORM
            String rec = recRef(ip.varying());
            line(rec + ".move(\"" + ip.varying() + "\", Decimal.of(\"" + ip.from() + "\"));");
            line("while (!(" + emitCondition(ip.until()) + ")) {");
            indent++;
            for (Statement s : ip.body()) emitStatement(s);
            line(rec + ".add(\"" + ip.varying() + "\", Decimal.of(\"" + ip.by() + "\"));");
            indent--;
            line("}");
        } else if (ip.until() != null) {
            // PERFORM UNTIL cond ... END-PERFORM
            line("while (!(" + emitCondition(ip.until()) + ")) {");
            indent++;
            for (Statement s : ip.body()) emitStatement(s);
            indent--;
            line("}");
        } else {
            // No condition — shouldn't happen in valid COBOL
            line("{ // inline perform");
            indent++;
            for (Statement s : ip.body()) emitStatement(s);
            indent--;
            line("}");
        }
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

    private void emitInvoke(Statement.Invoke inv) {
        String obj = toJavaFieldName(inv.object());
        String method = inv.method(); // preserve exactly as the programmer wrote it

        // Build argument list
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < inv.args().size(); i++) {
            if (i > 0) args.append(", ");
            String arg = inv.args().get(i);
            // Pass field values — numeric as Decimal, alpha as String
            args.append(recRef(arg) + ".getDecimal(\"" + arg + "\")");
        }

        if (inv.method().equalsIgnoreCase("new")) {
            // Factory/constructor: INVOKE ClassName "new" RETURNING obj
            String className = toJavaClassName(inv.object());
            if (inv.returning() != null) {
                line("var " + toJavaFieldName(inv.returning()) + " = new " + className + "(" + args + ");");
            } else {
                line("new " + className + "(" + args + ");");
            }
        } else {
            // Method call: INVOKE object "method" USING args RETURNING result
            String call = obj + "." + method + "(" + args + ")";
            if (inv.returning() != null) {
                line(recRef(inv.returning()) + ".move(\"" + inv.returning() + "\", " + call + ");");
            } else {
                line(call + ";");
            }
        }
    }


    private void emitCodecVerb(Statement.CodecVerb cv) {
        String codec = cv.format().toLowerCase(); // "xml" or "json"
        String rec = recRef(cv.record());
        if (cv.action().equalsIgnoreCase("GENERATE")) {
            line(recRef(cv.target()) + ".move(\"" + cv.target()
                + "\", org.cobol4j.codec.CodecRegistry.instance().to"
                + (codec.equals("xml") ? "Xml" : "Json") + "(" + rec + "));");
        } else {
            // PARSE
            line("org.cobol4j.codec.CodecRegistry.instance().from"
                + (codec.equals("xml") ? "Xml" : "Json") + "("
                + recRef(cv.target()) + ".getString(\"" + cv.target() + "\").trim(), " + rec + ");");
        }
    }

    private void emitAccept(Statement.Accept a) {
        String rec = recRef(a.target());
        if (a.isFromSystem()) {
            // ACCEPT target FROM DATE / TIME / DAY / DAY-OF-WEEK
            String expr = switch (a.from().toUpperCase()) {
                case "DATE" -> "Intrinsic.currentDate().substring(0, 8)";
                case "TIME" -> "Intrinsic.currentDate().substring(8, 14)";
                case "DAY" -> "Intrinsic.currentDate().substring(0, 4) + Intrinsic.currentDate().substring(4, 7)";
                case "DAY-OF-WEEK" -> "String.valueOf(java.time.LocalDate.now().getDayOfWeek().getValue())";
                default -> "Intrinsic.currentDate()";
            };
            line(rec + ".move(\"" + a.target() + "\", " + expr + ");");
        } else {
            line("ctx.accept(" + rec + ", \"" + a.target() + "\");");
        }
    }

    private void emitWrite(Statement.Write w) {
        String fileVar = findFileForRecord(w.recordName());
        String recVar = toJavaFieldName(w.recordName());
        if (w.from() != null) {
            // WRITE record FROM source — load source into record, then write
            // FROM may reference an 01-level record or a field within a record
            String fromRef = isRecordName(w.from())
                ? toJavaFieldName(w.from())
                : recRef(w.from());
            line(recVar + ".loadFrom(" + fromRef + ".buffer());");
        }
        if (w.advanceLines() > 0) {
            // WRITE AFTER ADVANCING n LINES — write blank lines first
            for (int i = 0; i < w.advanceLines(); i++) {
                line(fileVar + ".write(" + recVar + "); // advance");
            }
        }
        line(fileVar + ".write(" + recVar + ");");
    }

    /** Find the file variable name associated with a record name. */
    /** Check if a name is a top-level (01/77) record name, not a field within a record. */
    private boolean isRecordName(String name) {
        List<RecordGroup> groups = groupByLevel01();
        return groups.stream().anyMatch(g -> g.name.equalsIgnoreCase(name));
    }

    private String findFileForRecord(String recordName) {
        for (var fb : program.fileBindings()) {
            if (fb.recordName().equalsIgnoreCase(recordName)) {
                return toJavaFieldName(fb.fileName());
            }
        }
        // Fallback: use the record name as the file variable
        return toJavaFieldName(recordName);
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
        String sql = s.sqlText().trim();
        String upper = sql.toUpperCase();

        // Transaction control — simple, no host variables
        if (upper.equals("COMMIT")) {
            line("sql.commit();");
            return;
        }
        if (upper.equals("ROLLBACK")) {
            line("sql.rollback();");
            return;
        }

        // Detect statement type
        if (upper.startsWith("SELECT")) {
            emitExecSqlSelect(sql);
        } else if (upper.startsWith("INSERT") || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE")) {
            emitExecSqlDml(sql);
        } else if (upper.startsWith("DECLARE") && upper.contains("CURSOR")) {
            emitExecSqlDeclareCursor(sql);
        } else if (upper.startsWith("OPEN")) {
            emitExecSqlOpenCursor(sql);
        } else if (upper.startsWith("FETCH")) {
            emitExecSqlFetch(sql);
        } else if (upper.startsWith("CLOSE")) {
            emitExecSqlCloseCursor(sql);
        } else {
            // Unknown SQL — emit as comment with warning
            diag.warning("emitter", 0, "EXEC SQL",
                "Unrecognized SQL statement type: " + sql);
            line("// EXEC SQL: " + sql);
        }
    }

    /** SELECT ... INTO :host-vars ... WHERE :host-vars */
    private void emitExecSqlSelect(String sql) {
        // Extract INTO clause: everything between INTO and FROM
        String upper = sql.toUpperCase();
        int intoStart = upper.indexOf(" INTO ");
        int fromStart = upper.indexOf(" FROM ");

        List<String> intoVars = new ArrayList<>();
        String cleanSql;

        if (intoStart >= 0 && fromStart > intoStart) {
            // Extract host variables from INTO clause
            String intoClause = sql.substring(intoStart + 6, fromStart).trim();
            for (String part : intoClause.split(",")) {
                String var = part.trim();
                if (var.startsWith(":")) {
                    intoVars.add(var.substring(1).trim());
                }
            }
            // Remove INTO clause from SQL
            cleanSql = sql.substring(0, intoStart) + " " + sql.substring(fromStart);
        } else {
            cleanSql = sql;
        }

        // Find and replace remaining :HOST-VARs (input params)
        List<String> inputVars = new ArrayList<>();
        cleanSql = extractAndReplaceHostVars(cleanSql, inputVars);

        // Emit the select call
        line("sql.select(\"" + escapeJavaString(cleanSql.trim()) + "\")");
        indent++;
        for (String var : inputVars) {
            String rec = recRef(var);
            line(".param(" + rec + ", \"" + var + "\")");
        }
        if (!intoVars.isEmpty()) {
            StringBuilder into = new StringBuilder(".into(" + recRef(intoVars.get(0)));
            for (String var : intoVars) {
                into.append(", \"").append(var).append("\"");
            }
            into.append(")");
            line(into.toString());
        }
        line(".execute();");
        indent--;
    }

    /** INSERT, UPDATE, DELETE with :host-vars */
    private void emitExecSqlDml(String sql) {
        List<String> inputVars = new ArrayList<>();
        String cleanSql = extractAndReplaceHostVars(sql, inputVars);

        line("sql.execute(\"" + escapeJavaString(cleanSql.trim()) + "\")");
        indent++;
        for (String var : inputVars) {
            String rec = recRef(var);
            line(".param(" + rec + ", \"" + var + "\")");
        }
        line(".execute();");
        indent--;
    }

    /** DECLARE cursor-name CURSOR FOR SELECT ... */
    private void emitExecSqlDeclareCursor(String sql) {
        // Extract cursor name and the SELECT statement
        // Find " CURSOR " as a standalone keyword (not part of a name like CUST-CURSOR)
        String upper = sql.toUpperCase();
        int declareEnd = upper.indexOf(" CURSOR ");
        if (declareEnd < 0) declareEnd = upper.indexOf(" CURSOR");
        String cursorName = sql.substring(8, declareEnd).trim(); // between "DECLARE " and " CURSOR"
        int forIdx = upper.indexOf(" FOR ", declareEnd);
        String selectSql = forIdx >= 0 ? sql.substring(forIdx + 5).trim() : "";

        List<String> inputVars = new ArrayList<>();
        selectSql = extractAndReplaceHostVars(selectSql, inputVars);

        line("SqlCursor " + toJavaFieldName(cursorName) + " = sql.declareCursor(\""
            + cursorName + "\", \"" + escapeJavaString(selectSql) + "\");");
    }

    /** OPEN cursor-name */
    private void emitExecSqlOpenCursor(String sql) {
        String cursorName = sql.substring(5).trim(); // after "OPEN "
        line("sql.open(" + toJavaFieldName(cursorName) + ");");
    }

    /** FETCH cursor-name INTO :host-vars */
    private void emitExecSqlFetch(String sql) {
        String upper = sql.toUpperCase();
        int intoIdx = upper.indexOf(" INTO ");
        String cursorName;
        List<String> intoVars = new ArrayList<>();

        if (intoIdx >= 0) {
            cursorName = sql.substring(6, intoIdx).trim(); // between FETCH and INTO
            String intoClause = sql.substring(intoIdx + 6).trim();
            for (String part : intoClause.split(",")) {
                String var = part.trim();
                if (var.startsWith(":")) {
                    intoVars.add(var.substring(1).trim());
                }
            }
        } else {
            cursorName = sql.substring(6).trim();
        }

        StringBuilder fetch = new StringBuilder("sql.fetch(" + toJavaFieldName(cursorName) + ")");
        if (!intoVars.isEmpty()) {
            fetch.append(".into(").append(recRef(intoVars.get(0)));
            for (String var : intoVars) {
                fetch.append(", \"").append(var).append("\"");
            }
            fetch.append(")");
        }
        fetch.append(".execute();");
        line(fetch.toString());
    }

    /** CLOSE cursor-name */
    private void emitExecSqlCloseCursor(String sql) {
        String cursorName = sql.substring(6).trim(); // after "CLOSE "
        line("sql.close(" + toJavaFieldName(cursorName) + ");");
    }

    /**
     * Find all :HOST-VAR references in SQL, replace with ?, collect var names.
     * Handles ": VAR" (space after colon) since token-joining inserts spaces.
     */
    private String extractAndReplaceHostVars(String sql, List<String> vars) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            if (sql.charAt(i) == ':') {
                // Skip optional whitespace after colon
                int start = i + 1;
                while (start < sql.length() && sql.charAt(start) == ' ') start++;
                if (start < sql.length() && Character.isLetter(sql.charAt(start))) {
                    // Found a host variable
                    int end = start;
                    while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end))
                           || sql.charAt(end) == '-' || sql.charAt(end) == '_')) {
                        end++;
                    }
                    String varName = sql.substring(start, end);
                    vars.add(varName);
                    result.append('?');
                    i = end;
                } else {
                    result.append(sql.charAt(i));
                    i++;
                }
            } else {
                result.append(sql.charAt(i));
                i++;
            }
        }
        return result.toString();
    }

    private String escapeJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
        boolean hasPointer = ss.pointer() != null && !ss.pointer().isEmpty();
        if (hasPointer) {
            // Capture the return value (new pointer position) from execute()
            line("{");
            indent++;
            line("int _stringPtr = CobolString.into(" + rec + ", \"" + ss.into() + "\")");
        } else {
            line("CobolString.into(" + rec + ", \"" + ss.into() + "\")");
        }
        indent++;
        if (hasPointer) {
            line(".withPointer((int) " + recRef(ss.pointer()) + ".getLong(\"" + ss.pointer() + "\"))");
        }
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
        if (hasPointer) {
            line(recRef(ss.pointer()) + ".move(\"" + ss.pointer() + "\", (long) _stringPtr);");
            indent--;
            line("}");
        }
    }

    private void emitUnstringStmt(Statement.UnstringStmt us) {
        String rec = recRef(us.source());
        boolean hasTally = us.tallyField() != null && !us.tallyField().isEmpty();
        boolean hasPointer = us.pointer() != null && !us.pointer().isEmpty();
        if (hasTally) {
            // Capture the return value (field count) from execute()
            line("{");
            indent++;
            line("int _unstringCount = CobolUnstring.from(" + rec + ", \"" + us.source() + "\")");
        } else {
            line("CobolUnstring.from(" + rec + ", \"" + us.source() + "\")");
        }
        indent++;
        for (int i = 0; i < us.delimiters().size(); i++) {
            String d = us.delimiters().get(i);
            if (i == 0) line(".delimitedBy(" + emitValueExpr(d) + ")");
            else line(".orDelimitedBy(" + emitValueExpr(d) + ")");
        }
        for (String field : us.into()) {
            line(".into(" + recRef(field) + ", \"" + field + "\")");
        }
        if (hasPointer) {
            line(".withPointer((int) " + recRef(us.pointer()) + ".getLong(\"" + us.pointer() + "\"))");
        }
        line(".execute();");
        indent--;
        if (hasTally) {
            line(recRef(us.tallyField()) + ".move(\"" + us.tallyField() + "\", (long) _unstringCount);");
            indent--;
            line("}");
        }
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
            // Strip subscript from condition field — SEARCH provides its own index via idx
            String condField = w.condition();
            if (condField.contains("(")) condField = condField.substring(0, condField.indexOf('('));

            if (w.conditionRight() != null) {
                String rightVal = w.conditionRight();
                String rightExpr;
                if (rightVal.startsWith("\"")) {
                    // Literal value — use directly
                    rightExpr = rightVal;
                } else {
                    if (rightVal.contains("(")) rightVal = rightVal.substring(0, rightVal.indexOf('('));
                    rightExpr = recRef(rightVal) + ".getString(\"" + rightVal + "\").trim()";
                }
                line(".when(idx -> " + rec + ".getString(\"" + condField + "\", idx).trim().equals("
                    + rightExpr + "), idx -> {");
            } else {
                line(".when(idx -> " + rec + ".is(\"" + condField + "\"), idx -> {");
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
        boolean hasExceptionHandlers = !call.onException().isEmpty() || !call.notOnException().isEmpty();

        if (hasExceptionHandlers) {
            line("try {");
            indent++;
        }

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
                // Unknown CALL target — emit a LOG.warning and a generic call pattern
                // The user can replace this with a real program reference
                line("LOG.warning(\"CALL '" + target
                    + "' — no built-in mapping. Provide a Program instance for '"
                    + target + "'.\");");
                line("// CALL \"" + target + "\" — replace with: ctx.call(programRef"
                    + (params.isEmpty() ? "" : ", fields...") + ");");
            }
        }

        if (hasExceptionHandlers) {
            if (!call.notOnException().isEmpty()) {
                // NOT ON EXCEPTION runs when no exception occurs
                for (Statement s : call.notOnException()) emitStatement(s);
            }
            indent--;
            line("} catch (Exception _cobol4jCallEx) {");
            indent++;
            if (!call.onException().isEmpty()) {
                for (Statement s : call.onException()) emitStatement(s);
            }
            indent--;
            line("}");
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
        if (expr == null) throw new Transpiler.TranspileException("Null expression in emitter");
        if (expr instanceof Expr.NumericLit n) return "Decimal.of(\"" + n.value() + "\")";
        if (expr instanceof Expr.StringLit s) return "\"" + s.value() + "\"";
        if (expr instanceof Expr.Figurative f) return switch (f.name()) {
            case "SPACES" -> "\" \"";
            case "ZEROS" -> "Decimal.ZERO";
            case "HIGH-VALUES" -> "\"\\u00FF\"";
            case "LOW-VALUES" -> "\"\\u0000\"";
            default -> throw new Transpiler.TranspileException(
                "Unknown figurative constant: " + f.name());
        };
        if (expr instanceof Expr.FunctionCall fc) return emitFunctionCall(fc);
        if (expr instanceof Expr.FieldRef fr) return emitFieldRef(fr);
        if (expr instanceof Expr.BinaryOp op) return emitBinaryOp(op);
        if (expr instanceof Expr.Negate neg) return emitExpr(neg.operand()) + ".negate()";
        throw new Transpiler.TranspileException(
            "Unrecognized expression type: " + expr.getClass().getSimpleName());
    }

    private String emitFieldRef(Expr.FieldRef fr) {
        // Implicit COBOL registers — not defined in any record
        String implicit = implicitRegister(fr.name());
        if (implicit != null) return implicit;

        if (fr.isRefMod()) {
            return recRef(fr.name()) + ".substring(\"" + fr.name() + "\", "
                + emitRefModArg(fr.refModPos()) + ", " + emitRefModArg(fr.refModLen()) + ")";
        }
        String rec = recRef(fr.name());
        if (isNumericField(fr.name())) {
            if (fr.isSubscripted()) {
                return rec + ".getDecimal(\"" + fr.name() + "\", " + emitSubscript(fr.subscript()) + ")";
            }
            return rec + ".getDecimal(\"" + fr.name() + "\")";
        } else {
            if (fr.isSubscripted()) {
                return rec + ".getString(\"" + fr.name() + "\", " + emitSubscript(fr.subscript()) + ").trim()";
            }
            return rec + ".getString(\"" + fr.name() + "\").trim()";
        }
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

            // Alphanumeric comparisons use String.equals/compareTo
            boolean leftIsAlpha = leftFieldName != null && !isNumericField(leftFieldName);
            boolean rightIsString = (right instanceof Expr.StringLit);
            if (leftIsAlpha || rightIsString) {
                String op = switch (s.operator()) {
                    case "="  -> ".equals(" + rightExpr + ")";
                    case ">"  -> ".compareTo(" + rightExpr + ") > 0";
                    case "<"  -> ".compareTo(" + rightExpr + ") < 0";
                    case ">=" -> ".compareTo(" + rightExpr + ") >= 0";
                    case "<=" -> ".compareTo(" + rightExpr + ") <= 0";
                    default   -> ".equals(" + rightExpr + ")";
                };
                return prefix + leftExpr + op;
            }

            // Numeric comparisons use Decimal methods
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

        throw new Transpiler.TranspileException(
            "Unrecognized condition type: " + cond.getClass().getSimpleName());
    }

    private String emitValueExpr(String val) {
        if (val == null) throw new Transpiler.TranspileException("Null value in emitValueExpr");
        if (val.startsWith("\"")) return val; // string literal
        if (val.equals("SPACES") || val.equals("SPACE")) return "\" \"";
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
        if (fieldName == null) throw new Transpiler.TranspileException("Null field name in fieldGet");
        if (fieldName.matches("-?\\d+\\.?\\d*")) return "Decimal.of(\"" + fieldName + "\")";
        String implicit = implicitRegister(fieldName);
        if (implicit != null) return implicit;
        if (isNumericField(fieldName)) {
            return recRef(fieldName) + ".getDecimal(\"" + fieldName + "\")";
        }
        return recRef(fieldName) + ".getString(\"" + fieldName + "\").trim()";
    }


    private String emitSizeErrorHandler(List<Statement> onErr, List<Statement> notErr) {
        if ((onErr == null || onErr.isEmpty()) && (notErr == null || notErr.isEmpty())) return "";
        StringBuilder sb = new StringBuilder();
        if (notErr != null && !notErr.isEmpty()) {
            sb.append("SizeErrorHandler.of(() -> { ");
            for (Statement s : onErr) {
                sb.append(emitStatementInline(s)).append(" ");
            }
            sb.append("}, () -> { ");
            for (Statement s : notErr) {
                sb.append(emitStatementInline(s)).append(" ");
            }
            sb.append("})");
        } else {
            sb.append("SizeErrorHandler.onError(() -> { ");
            for (Statement s : onErr) {
                sb.append(emitStatementInline(s)).append(" ");
            }
            sb.append("})");
        }
        return sb.toString();
    }

    /** Emit a single statement as an inline string (for lambdas). */
    private String emitStatementInline(Statement stmt) {
        if (stmt instanceof Statement.Move m) {
            String val = emitExpr(m.source());
            return recRef(m.targets().get(0)) + ".move(\"" + m.targets().get(0) + "\", " + val + ");";
        }
        if (stmt instanceof Statement.SetCondition sc) {
            return findRecordFor(sc.conditionName()) + ".set(\"" + sc.conditionName() + "\");";
        }
        if (stmt instanceof Statement.Display d) {
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < d.items().size(); i++) {
                if (i > 0) args.append(", ");
                args.append(emitExpr(d.items().get(i)));
            }
            return "/* display */ System.out.println(" + args + ");";
        }
        throw new Transpiler.TranspileException(
            "Unsupported statement in SIZE ERROR handler: " + stmt.getClass().getSimpleName()
            + " — only MOVE, SET, and DISPLAY are supported inline");
    }

    // ── Naming helpers ──────────────────────────────────────────────

    private String recRef(String fieldOrRecName) {
        // Strip subscript/refmod if present: "FIELD(1)" → "FIELD"
        String name = fieldOrRecName;
        if (name.contains("(")) name = name.substring(0, name.indexOf('('));

        // Find which record contains this field or condition name
        List<RecordGroup> groups = groupByLevel01();
        if (groups.size() == 1) return toJavaFieldName(groups.get(0).name);
        String searchName = name;

        // Look up in symbol table
        FieldInfo info = symbolTable.get(searchName);
        if (info != null) {
            if ("__IMPLICIT__".equals(info.recordName)) {
                // Implicit register — doesn't belong to a record variable
                // Return a placeholder that emitFieldRef/fieldGet will override
                return "/* implicit:" + searchName + " */";
            }
            return toJavaFieldName(info.recordName);
        }

        // Not found — fail hard with context
        throw new Transpiler.TranspileException(
            "Field or condition '" + searchName + "' (from '" + fieldOrRecName
            + "') not found in any record. "
            + "Available records and fields: " + groups.stream()
                .map(g -> g.name + g.children.stream().map(e -> e.name()).toList())
                .toList());
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
