package org.cobol4j;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * COBOL arithmetic with GIVING, ROUNDED, and REMAINDER support.
 * <p>
 * In COBOL, arithmetic can store results in a different field (GIVING),
 * round instead of truncate (ROUNDED), and capture division remainders.
 * This class provides fluent builders for these operations using {@link Field}
 * references for type-safe, by-reference parameter passing.
 * <pre>{@code
 * Field price = rec.field("PRICE");
 * Field qty   = rec.field("QTY");
 * Field total = rec.field("TOTAL");
 * Field avg   = rec.field("AVERAGE");
 * Field rem   = rec.field("REMAINDER");
 *
 * // MULTIPLY PRICE BY QTY GIVING TOTAL ROUNDED
 * Arithmetic.multiply(price.get(), qty.get())
 *           .giving(total)
 *           .rounded()
 *           .execute();
 *
 * // DIVIDE TOTAL BY QTY GIVING AVERAGE REMAINDER REM
 * Arithmetic.divide(total.get(), qty.get())
 *           .giving(avg)
 *           .remainder(rem)
 *           .execute();
 *
 * // ADD A B C GIVING TOTAL ON SIZE ERROR ...
 * Arithmetic.add(a.get(), b.get(), c.get())
 *           .giving(total)
 *           .onSizeError(() -> rec.move("ERR-FLAG", "Y"))
 *           .execute();
 * }</pre>
 */
public final class Arithmetic {

    private Arithmetic() {}

    /** ADD operands, producing a sum to store via GIVING. */
    public static GivingBuilder add(BigDecimal... operands) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal op : operands) sum = sum.add(op);
        return new GivingBuilder(sum);
    }

    /**
     * SUBTRACT subtrahend FROM minuend, producing a difference.
     * {@code SUBTRACT A FROM B GIVING C} → {@code subtract(A, B).giving(C)}
     */
    public static GivingBuilder subtract(BigDecimal subtrahend, BigDecimal minuend) {
        return new GivingBuilder(minuend.subtract(subtrahend));
    }

    /** MULTIPLY operands, producing a product. */
    public static GivingBuilder multiply(BigDecimal... operands) {
        BigDecimal product = BigDecimal.ONE;
        for (BigDecimal op : operands) product = product.multiply(op);
        return new GivingBuilder(product);
    }

    /**
     * DIVIDE dividend BY divisor.
     * {@code DIVIDE A BY B GIVING C} → {@code divide(A, B).giving(C)}
     */
    public static DivideBuilder divide(BigDecimal dividend, BigDecimal divisor) {
        return new DivideBuilder(dividend, divisor);
    }

    // ═══════════════════════════════════════════════════════════════

    /**
     * Builder for GIVING operations (ADD, SUBTRACT, MULTIPLY).
     */
    public static class GivingBuilder {
        protected final BigDecimal result;
        protected Field givingField;
        protected boolean rounded;
        protected SizeErrorHandler handler = SizeErrorHandler.silent();

        GivingBuilder(BigDecimal result) {
            this.result = result;
        }

        /** GIVING — store the result in this field. */
        public GivingBuilder giving(Field field) {
            this.givingField = field;
            return this;
        }

        /** ROUNDED — use HALF_UP rounding instead of truncation. */
        public GivingBuilder rounded() {
            this.rounded = true;
            return this;
        }

        /** ON SIZE ERROR / NOT ON SIZE ERROR handlers. */
        public GivingBuilder onSizeError(SizeErrorHandler handler) {
            this.handler = handler;
            return this;
        }

        /** Execute the operation: compute result and store in GIVING field. */
        public void execute() {
            if (givingField == null) {
                throw new IllegalStateException("No GIVING field specified");
            }
            Record rec = givingField.record();
            FieldDef fd = givingField.def();
            RoundingMode mode = rounded ? RoundingMode.HALF_UP : RoundingMode.DOWN;

            Pic pic = fd.pic();
            BigDecimal fitted = result.setScale(pic.decimalDigits(), mode);
            BigDecimal intPart = fitted.abs().setScale(0, RoundingMode.DOWN);
            int intDigitCount = intPart.compareTo(BigDecimal.ZERO) == 0
                ? 1 : intPart.toBigInteger().toString().length();

            if (intDigitCount > pic.integerDigits()) {
                handler.onSizeError();
            } else {
                rec.compute(fd.name(), fitted);
                handler.onSuccess();
            }
        }
    }

    /**
     * Builder for DIVIDE with optional REMAINDER.
     */
    public static final class DivideBuilder extends GivingBuilder {
        private final BigDecimal dividend;
        private final BigDecimal divisor;
        private Field remainderField;

        DivideBuilder(BigDecimal dividend, BigDecimal divisor) {
            super(null); // result computed at execute time
            this.dividend = dividend;
            this.divisor = divisor;
        }

        @Override
        public DivideBuilder giving(Field field) {
            this.givingField = field;
            return this;
        }

        @Override
        public DivideBuilder rounded() {
            this.rounded = true;
            return this;
        }

        @Override
        public DivideBuilder onSizeError(SizeErrorHandler handler) {
            this.handler = handler;
            return this;
        }

        /** REMAINDER — store the division remainder in this field. */
        public DivideBuilder remainder(Field field) {
            this.remainderField = field;
            return this;
        }

        @Override
        public void execute() {
            if (givingField == null) {
                throw new IllegalStateException("No GIVING field specified");
            }
            Pic quotientPic = givingField.def().pic();
            int scale = quotientPic.decimalDigits();
            RoundingMode mode = rounded ? RoundingMode.HALF_UP : RoundingMode.DOWN;

            // Compute quotient with enough precision
            BigDecimal quotient = dividend.divide(divisor, scale + 5, RoundingMode.DOWN);
            BigDecimal fitted = quotient.setScale(scale, mode);

            // Check size
            BigDecimal intPart = fitted.abs().setScale(0, RoundingMode.DOWN);
            int intDigitCount = intPart.compareTo(BigDecimal.ZERO) == 0
                ? 1 : intPart.toBigInteger().toString().length();

            if (intDigitCount > quotientPic.integerDigits()) {
                handler.onSizeError();
                return;
            }

            givingField.record().compute(givingField.def().name(), fitted);

            // REMAINDER = dividend - (truncated_quotient * divisor)
            if (remainderField != null) {
                BigDecimal truncatedQ = fitted.setScale(scale, RoundingMode.DOWN);
                BigDecimal remainder = dividend.subtract(truncatedQ.multiply(divisor));
                remainderField.record().compute(remainderField.def().name(), remainder);
            }

            handler.onSuccess();
        }
    }
}
