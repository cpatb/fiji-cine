package io.github.cpatb.cine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Unpacking of the two packed Phantom pixel formats. */
final class Packed {

    /**
     * 10-bit packed codes are not linear: the camera stores a companded code that maps to a
     * 12-bit linear value through a fixed 1024-entry table: Vision Research "Cine File Format"
     * (2011), Appendix 1, which states the table may be copied.
     */
    static final int[] LINLUT10 = loadLut();

    private Packed() {
    }

    /**
     * 4 pixels in 5 bytes, most-significant bit first:
     * p0 = b0[7:0]b1[7:6], p1 = b1[5:0]b2[7:4], p2 = b2[3:0]b3[7:2], p3 = b3[1:0]b4[7:0].
     * Output is the linearised 12-bit value.
     */
    static void unpack10(byte[] s, int src, short[] out, int dst, int n) {
        for (int x = 0; x < n; x += 4, src += 5) {
            int b0 = s[src] & 0xFF, b1 = s[src + 1] & 0xFF, b2 = s[src + 2] & 0xFF;
            int b3 = s[src + 3] & 0xFF, b4 = s[src + 4] & 0xFF;
            int[] p = {b0 << 2 | b1 >> 6, (b1 & 0x3F) << 4 | b2 >> 4, (b2 & 0x0F) << 6 | b3 >> 2, (b3 & 0x03) << 8 | b4};
            for (int k = 0; k < 4 && x + k < n; k++) out[dst + x + k] = (short) LINLUT10[p[k]];
        }
    }

    /**
     * P12L (biCompression 1024), 2 pixels in 3 bytes, MSB first:
     * p0 = b0 &lt;&lt; 4 | b1 &gt;&gt; 4, p1 = (b1 &amp; 0x0F) &lt;&lt; 8 | b2. Linear (no LUT).
     * Bit order as in every published reader; no real P12L file was available to verify it.
     */
    static void unpack12L(byte[] s, int src, short[] out, int dst, int n) {
        for (int x = 0; x < n; x += 2, src += 3) {
            int b0 = s[src] & 0xFF, b1 = s[src + 1] & 0xFF, b2 = s[src + 2] & 0xFF;
            out[dst + x] = (short) (b0 << 4 | b1 >> 4);
            if (x + 1 < n) out[dst + x + 1] = (short) ((b1 & 0x0F) << 8 | b2);
        }
    }

    private static int[] loadLut() {
        int[] lut = new int[1024];
        try (InputStream in = Packed.class.getResourceAsStream("linlut10.txt")) {
            if (in == null) throw new IllegalStateException("linlut10.txt missing from the jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            int i = 0;
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                for (String tok : line.split("[,\\s]+")) {
                    if (!tok.isEmpty()) lut[i++] = Integer.parseInt(tok);
                }
            }
            if (i != 1024) throw new IllegalStateException("linlut10.txt has " + i + " entries, expected 1024");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return lut;
    }
}
