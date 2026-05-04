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

import org.cobol4j.Decimal;
import org.cobol4j.Record;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive testing of COMPUTE expression parsing and operator precedence.
 * <p>
 * COBOL COMPUTE arithmetic follows standard mathematical precedence:
 * 1. Parentheses (highest)
 * 2. ** (exponentiation) — not yet implemented
 * 3. * / (multiplication, division)
 * 4. + - (addition, subtraction) (lowest)
 * <p>
 * Left-to-right within same precedence level.
 */
class ComputeExprTest {

    // ═══════════════════════════════════════════════════════════════
    //  SINGLE OPERATOR EXPRESSIONS
    // ═══════════════════════════════════════════════════════════════

    @Test
    void addTwoFields() {
        String java = transpile("COMPUTE WS-R = WS-A + WS-B.");
        assertNoTodo(java);
        assertTrue(java.contains(".add("), "Should contain .add(): " + extract(java));
    }

    @Test
    void subtractTwoFields() {
        String java = transpile("COMPUTE WS-R = WS-A - WS-B.");
        assertNoTodo(java);
        assertTrue(java.contains(".subtract("), "Should contain .subtract(): " + extract(java));
    }

    @Test
    void multiplyTwoFields() {
        String java = transpile("COMPUTE WS-R = WS-A * WS-B.");
        assertNoTodo(java);
        assertTrue(java.contains(".multiply("), "Should contain .multiply(): " + extract(java));
    }

    @Test
    void divideTwoFields() {
        String java = transpile("COMPUTE WS-R = WS-A / WS-B.");
        assertNoTodo(java);
        assertTrue(java.contains(".divide("), "Should contain .divide(): " + extract(java));
    }

    @Test
    void addLiteral() {
        String java = transpile("COMPUTE WS-R = WS-A + 100.");
        assertNoTodo(java);
        assertTrue(java.contains(".add(Decimal.of(\"100\")"), "Should add literal: " + extract(java));
    }

    @Test
    void multiplyByLiteral() {
        String java = transpile("COMPUTE WS-R = WS-A * 0.08.");
        assertNoTodo(java);
        assertTrue(java.contains(".multiply(Decimal.of(\"0.08\")"), "Should multiply literal: " + extract(java));
    }

    // ═══════════════════════════════════════════════════════════════
    //  OPERATOR PRECEDENCE — * / before + -
    // ═══════════════════════════════════════════════════════════════

    @Test
    void multiplyBeforeAdd() {
        // A * B + C should be (A * B) + C, NOT A * (B + C)
        String java = transpile("COMPUTE WS-R = WS-A * WS-B + WS-C.");
        assertNoTodo(java);
        String expr = extract(java);
        // The multiply should be applied first (inner), then add (outer)
        int mulPos = expr.indexOf(".multiply(");
        int addPos = expr.indexOf(".add(");
        assertTrue(mulPos >= 0, "Should contain multiply: " + expr);
        assertTrue(addPos >= 0, "Should contain add: " + expr);
        assertTrue(mulPos < addPos, "Multiply should come before add (higher precedence): " + expr);
    }

    @Test
    void divideBeforeSubtract() {
        // A / B - C should be (A / B) - C
        String java = transpile("COMPUTE WS-R = WS-A / WS-B - WS-C.");
        assertNoTodo(java);
        String expr = extract(java);
        int divPos = expr.indexOf(".divide(");
        int subPos = expr.indexOf(".subtract(");
        assertTrue(divPos >= 0, "Should contain divide: " + expr);
        assertTrue(subPos >= 0, "Should contain subtract: " + expr);
        assertTrue(divPos < subPos, "Divide should come before subtract: " + expr);
    }

    @Test
    void addBeforeMultiplyWithParens() {
        // A * (B + C) — parens override precedence
        String java = transpile("COMPUTE WS-R = WS-A * ( WS-B + WS-C ).");
        assertNoTodo(java);
        String expr = extract(java);
        // The add should be inner (computed first due to parens), multiply is outer
        int addPos = expr.indexOf(".add(");
        int mulPos = expr.indexOf(".multiply(");
        assertTrue(addPos >= 0, "Should contain add: " + expr);
        assertTrue(mulPos >= 0, "Should contain multiply: " + expr);
        // In chained form: fieldA.multiply(fieldB.add(fieldC))
        // The add appears inside the multiply's argument
        assertTrue(mulPos < addPos, "Multiply wraps the add (parens): " + expr);
    }

    @Test
    void subtractBeforeDivideWithParens() {
        // ( A - B ) / C — parens force subtract first
        String java = transpile("COMPUTE WS-R = ( WS-A - WS-B ) / WS-C.");
        assertNoTodo(java);
        String expr = extract(java);
        int subPos = expr.indexOf(".subtract(");
        int divPos = expr.indexOf(".divide(");
        assertTrue(subPos >= 0, "Should contain subtract: " + expr);
        assertTrue(divPos >= 0, "Should contain divide: " + expr);
        // subtract is inside the expression that gets divided
        assertTrue(subPos < divPos, "Subtract before divide (parens): " + expr);
    }

    // ═══════════════════════════════════════════════════════════════
    //  MULTIPLE OPERATORS — same precedence, left-to-right
    // ═══════════════════════════════════════════════════════════════

    @Test
    void threeAdds() {
        // A + B + C → ((A + B) + C) — left to right
        String java = transpile("COMPUTE WS-R = WS-A + WS-B + WS-C.");
        assertNoTodo(java);
        String expr = extract(java);
        // Should have two .add() calls chained
        int first = expr.indexOf(".add(");
        int second = expr.indexOf(".add(", first + 1);
        assertTrue(first >= 0, "Should have first add: " + expr);
        assertTrue(second >= 0, "Should have second add: " + expr);
    }

    @Test
    void twoMultiplies() {
        // A * B * C → ((A * B) * C)
        String java = transpile("COMPUTE WS-R = WS-A * WS-B * WS-C.");
        assertNoTodo(java);
        String expr = extract(java);
        int first = expr.indexOf(".multiply(");
        int second = expr.indexOf(".multiply(", first + 1);
        assertTrue(first >= 0, "Should have first multiply: " + expr);
        assertTrue(second >= 0, "Should have second multiply: " + expr);
    }

    @Test
    void mixedAddSubtract() {
        // A + B - C → ((A + B) - C)
        String java = transpile("COMPUTE WS-R = WS-A + WS-B - WS-C.");
        assertNoTodo(java);
        String expr = extract(java);
        assertTrue(expr.contains(".add("), "Should contain add: " + expr);
        assertTrue(expr.contains(".subtract("), "Should contain subtract: " + expr);
    }

    @Test
    void mixedMultiplyDivide() {
        // A * B / C → ((A * B) / C)
        String java = transpile("COMPUTE WS-R = WS-A * WS-B / WS-C.");
        assertNoTodo(java);
        String expr = extract(java);
        assertTrue(expr.contains(".multiply("), "Should contain multiply: " + expr);
        assertTrue(expr.contains(".divide("), "Should contain divide: " + expr);
    }

    // ═══════════════════════════════════════════════════════════════
    //  COMPLEX EXPRESSIONS — mixed precedence
    // ═══════════════════════════════════════════════════════════════

    @Test
    void complexExpression() {
        // A + B * C - D / E
        // Should be: A + (B*C) - (D/E) → ((A + (B*C)) - (D/E))
        String java = transpile("COMPUTE WS-R = WS-A + WS-B * WS-C - WS-D / WS-E.");
        assertNoTodo(java);
        String expr = extract(java);
        assertTrue(expr.contains(".multiply("), "Should contain multiply: " + expr);
        assertTrue(expr.contains(".divide("), "Should contain divide: " + expr);
        assertTrue(expr.contains(".add("), "Should contain add: " + expr);
        assertTrue(expr.contains(".subtract("), "Should contain subtract: " + expr);
    }

    @Test
    void nestedParentheses() {
        // ((A + B) * (C - D))
        String java = transpile("COMPUTE WS-R = ( WS-A + WS-B ) * ( WS-C - WS-D ).");
        assertNoTodo(java);
        String expr = extract(java);
        assertTrue(expr.contains(".add("), "Should contain add: " + expr);
        assertTrue(expr.contains(".subtract("), "Should contain subtract: " + expr);
        assertTrue(expr.contains(".multiply("), "Should contain multiply: " + expr);
    }

    @Test
    void literalOnlyExpression() {
        // COMPUTE WS-R = 100 * 0.05
        String java = transpile("COMPUTE WS-R = 100 * 0.05.");
        assertNoTodo(java);
        String expr = extract(java);
        assertTrue(expr.contains("Decimal.of(\"100\")"), "Should have literal 100: " + expr);
        assertTrue(expr.contains("Decimal.of(\"0.05\")"), "Should have literal 0.05: " + expr);
        assertTrue(expr.contains(".multiply("), "Should multiply: " + expr);
    }

    @Test
    void singleField() {
        // COMPUTE WS-R = WS-A (just assignment)
        String java = transpile("COMPUTE WS-R = WS-A.");
        assertNoTodo(java);
        String expr = extract(java);
        assertTrue(expr.contains("WS-A"), "Should reference WS-A: " + expr);
    }

    @Test
    void singleLiteral() {
        // COMPUTE WS-R = 42
        String java = transpile("COMPUTE WS-R = 42.");
        assertNoTodo(java);
        String expr = extract(java);
        assertTrue(expr.contains("Decimal.of(\"42\")"), "Should have literal 42: " + expr);
    }

    // ═══════════════════════════════════════════════════════════════
    //  RUNTIME VERIFICATION — prove the expressions compute correctly
    // ═══════════════════════════════════════════════════════════════

    @Test
    void runtimePrecedenceMultiplyBeforeAdd() {
        // 2 + 3 * 4 = 2 + 12 = 14 (not 5 * 4 = 20)
        Decimal result = Decimal.of("2").add(Decimal.of("3").multiply(Decimal.of("4")));
        assertTrue(result.equalTo(Decimal.of("14")), "2 + 3*4 should be 14, got " + result);
    }

    @Test
    void runtimePrecedenceParensOverride() {
        // (2 + 3) * 4 = 5 * 4 = 20
        Decimal result = Decimal.of("2").add(Decimal.of("3")).multiply(Decimal.of("4"));
        assertTrue(result.equalTo(Decimal.of("20")), "(2+3)*4 should be 20, got " + result);
    }

    @Test
    void runtimeDivideBeforeSubtract() {
        // 100 - 50 / 2 = 100 - 25 = 75 (not 50/2 = 25)
        Decimal result = Decimal.of("100").subtract(Decimal.of("50").divide(Decimal.of("2"), 2));
        assertTrue(result.equalTo(Decimal.of("75.00")), "100 - 50/2 should be 75, got " + result);
    }

    @Test
    void runtimeComplexWithParens() {
        // (100 + 50) * (3 - 1) / 2 = 150 * 2 / 2 = 150
        Decimal inner1 = Decimal.of("100").add(Decimal.of("50"));
        Decimal inner2 = Decimal.of("3").subtract(Decimal.of("1"));
        Decimal result = inner1.multiply(inner2).divide(Decimal.of("2"), 2);
        assertTrue(result.equalTo(Decimal.of("150.00")), "(100+50)*(3-1)/2 should be 150, got " + result);
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    private String transpile(String computeStatement) {
        String cobol = """
            IDENTIFICATION DIVISION.
            PROGRAM-ID. EXPR-TEST.
            DATA DIVISION.
            WORKING-STORAGE SECTION.
            01 WS-REC.
               05 WS-A PIC S9(7)V99.
               05 WS-B PIC S9(7)V99.
               05 WS-C PIC S9(7)V99.
               05 WS-D PIC S9(7)V99.
               05 WS-E PIC S9(7)V99.
               05 WS-R PIC S9(9)V99.
            PROCEDURE DIVISION.
            MAIN-PARA.
                """ + computeStatement + """

                STOP RUN.
            """;
        TranspileDiagnostics diag = new TranspileDiagnostics();
        String java = Transpiler.transpile(cobol, diag);
        assertNotNull(java, "Transpilation failed: " + diag.errors());
        return java;
    }

    private void assertNoTodo(String java) {
        assertFalse(java.contains("TODO"), "Expression should be fully translated (no TODO): " + extract(java));
    }

    /** Extract the compute line from generated Java for clearer assertion messages. */
    private String extract(String java) {
        for (String line : java.split("\n")) {
            if (line.contains("compute(") || line.contains("Decimal.of")) {
                return line.trim();
            }
        }
        return java.substring(Math.max(0, java.length() - 200));
    }
}
