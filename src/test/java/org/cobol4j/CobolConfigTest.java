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
package org.cobol4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CobolConfigTest {

    @Test
    void specialNamesDecimalPointIsComma() {
        CobolConfig config = CobolConfig.defaults().decimalPointIsComma(true);
        // Verify the config
        assertEquals(',', config.decimalChar());
        assertEquals('.', config.thousandsSeparator());
        assertTrue(config.isDecimalPointComma());
    }

    @Test
    void defaultConfig() {
        CobolConfig config = CobolConfig.defaults();
        assertEquals('.', config.decimalChar());
        assertEquals(',', config.thousandsSeparator());
        assertEquals('$', config.currencySign());
        assertFalse(config.isDecimalPointComma());
    }

    @Test
    void customCurrencySign() {
        CobolConfig config = CobolConfig.defaults().currencySign('\u20AC'); // Euro sign
        assertEquals('\u20AC', config.currencySign());
    }

    @Test
    void editedNumericFormatsCorrectly() {
        // With the dot fix, formatting should place digits correctly
        Record rec = Record.define("T")
            .pic("AMT", "Z(5)9.99")
            .build();
        rec.move("AMT", Decimal.of("1234.56"));
        String result = rec.getString("AMT");
        assertTrue(result.contains("1234"), "Should contain 1234: '" + result + "'");
        assertTrue(result.contains("56"), "Should contain 56: '" + result + "'");
    }

    @Test
    void editedNumericWithDecimalPointIsComma() {
        // Formatting with DECIMAL-POINT IS COMMA should use comma as decimal separator
        CobolConfig config = CobolConfig.defaults().decimalPointIsComma(true);
        Pic pic = Pic.parse("Z(5)9.99");
        java.math.BigDecimal value = new java.math.BigDecimal("1234.56");
        String result = EditedNumeric.format(value, pic, config);
        // The '.' in the PIC becomes ',' in output when DECIMAL-POINT IS COMMA
        assertTrue(result.contains(","), "Should contain comma as decimal: '" + result + "'");
        assertTrue(result.contains("1234"), "Should contain 1234: '" + result + "'");
        assertTrue(result.contains("56"), "Should contain 56: '" + result + "'");
    }

    @Test
    void editedNumericWithThousandsSeparatorSwapped() {
        // When DECIMAL-POINT IS COMMA, the ',' in PIC becomes '.' as thousands separator
        CobolConfig config = CobolConfig.defaults().decimalPointIsComma(true);
        Pic pic = Pic.parse("$$$,$$9.99");
        java.math.BigDecimal value = new java.math.BigDecimal("1234.56");
        String result = EditedNumeric.format(value, pic, config);
        // '.' in PIC becomes ',' (decimal), ',' in PIC becomes '.' (thousands)
        assertTrue(result.contains("."), "Should contain '.' as thousands sep: '" + result + "'");
        assertTrue(result.contains(","), "Should contain ',' as decimal: '" + result + "'");
    }
}
