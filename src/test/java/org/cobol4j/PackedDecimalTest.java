package org.cobol4j;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class PackedDecimalTest {

    @Test
    void encodePositive() {
        Pic pic = Pic.parse("S9(5)V99"); // 7 digits → 4 bytes
        byte[] buf = new byte[4];
        PackedDecimal.encode(new BigDecimal("12345.67"), pic, buf, 0, 4);

        // 1234567 packed: digits paired left-to-right, sign in last nibble
        // [0x12, 0x34, 0x56, 0x7C]  (C = positive)
        assertEquals(0x12, buf[0] & 0xFF);
        assertEquals(0x34, buf[1] & 0xFF);
        assertEquals(0x56, buf[2] & 0xFF);
        assertEquals(0x7C, buf[3] & 0xFF);
    }

    @Test
    void encodeNegative() {
        Pic pic = Pic.parse("S9(5)V99");
        byte[] buf = new byte[4];
        PackedDecimal.encode(new BigDecimal("-12345.67"), pic, buf, 0, 4);

        assertEquals(0x12, buf[0] & 0xFF);
        assertEquals(0x34, buf[1] & 0xFF);
        assertEquals(0x56, buf[2] & 0xFF);
        assertEquals(0x7D, buf[3] & 0xFF); // D = negative
    }

    @Test
    void encodeUnsigned() {
        Pic pic = Pic.parse("9(5)"); // 5 digits → 3 bytes
        byte[] buf = new byte[3];
        PackedDecimal.encode(new BigDecimal("12345"), pic, buf, 0, 3);

        // 12345 unsigned: packed right-to-left into 3 bytes
        // 5 digits + sign nibble = 6 nibbles = 3 bytes
        // Layout: [0x12, 0x34, 0x5F]  (F = unsigned)
        assertEquals(0x12, buf[0] & 0xFF);
        assertEquals(0x34, buf[1] & 0xFF);
        assertEquals(0x5F, buf[2] & 0xFF);
    }

    @Test
    void decodeRoundtrip() {
        Pic pic = Pic.parse("S9(7)V99"); // 9 digits → 5 bytes
        byte[] buf = new byte[5];
        BigDecimal original = new BigDecimal("1234567.89");

        PackedDecimal.encode(original, pic, buf, 0, 5);
        BigDecimal decoded = PackedDecimal.decode(pic, buf, 0, 5);

        assertEquals(0, original.compareTo(decoded));
    }

    @Test
    void decodeNegativeRoundtrip() {
        Pic pic = Pic.parse("S9(5)V99");
        byte[] buf = new byte[4];
        BigDecimal original = new BigDecimal("-999.99");

        PackedDecimal.encode(original, pic, buf, 0, 4);
        BigDecimal decoded = PackedDecimal.decode(pic, buf, 0, 4);

        assertEquals(0, original.compareTo(decoded));
    }

    @Test
    void encodeZero() {
        Pic pic = Pic.parse("S9(5)V99");
        byte[] buf = new byte[4];
        PackedDecimal.encode(BigDecimal.ZERO, pic, buf, 0, 4);
        BigDecimal decoded = PackedDecimal.decode(pic, buf, 0, 4);
        assertEquals(0, BigDecimal.ZERO.compareTo(decoded));
    }

    @Test
    void encodeWithOffset() {
        // Verify encoding works when the field isn't at buffer position 0
        Pic pic = Pic.parse("S9(3)"); // 3 digits → 2 bytes
        byte[] buf = new byte[10];
        PackedDecimal.encode(new BigDecimal("123"), pic, buf, 5, 2);

        // Bytes at position 5-6 should hold the packed value
        assertEquals(0x12, buf[5] & 0xFF);
        assertEquals(0x3C, buf[6] & 0xFF);

        BigDecimal decoded = PackedDecimal.decode(pic, buf, 5, 2);
        assertEquals(0, new BigDecimal("123").compareTo(decoded));
    }
}
