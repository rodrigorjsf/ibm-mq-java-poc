package com.example.ibmmq.report;

/**
 * Allocation-light hex helpers for the {@code byte[]} MQMD fields recovered from a report (issue #19).
 *
 * <p>Raw {@code byte[]} values (AccountingToken, CorrelId bytes, MsgId bytes) break record value-equality
 * and log as garbage; we persist and log them as lowercase hex {@code String}s instead. These helpers are
 * null-safe (a {@code null} array maps to a {@code null} string and vice-versa) so they compose with the
 * fully-null-safe MQMD extraction path.</p>
 */
public final class HexBytes {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HexBytes() {
        // Utility class — no instances.
    }

    /**
     * Encodes a byte array as a lowercase hex string.
     *
     * @param bytes the bytes (may be {@code null}).
     * @return the lowercase hex string, or {@code null} when {@code bytes} is {@code null}.
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /**
     * Decodes a hex string back to bytes (defensive fallback when a byte[] MQMD property arrives as a hex
     * {@code String} rather than a {@code byte[]} object).
     *
     * @param hex the hex string (may be {@code null}/blank or odd-length).
     * @return the decoded bytes, or {@code null} when {@code hex} is {@code null}/blank or malformed
     *         (odd-length / non-hex). Never throws.
     */
    public static byte[] fromHex(String hex) {
        if (hex == null) {
            return null;
        }
        String trimmed = hex.trim();
        int len = trimmed.length();
        if (len == 0 || (len & 1) == 1) {
            return null;
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(trimmed.charAt(i), 16);
            int lo = Character.digit(trimmed.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) {
                return null; // non-hex char — treat as malformed rather than throwing.
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
