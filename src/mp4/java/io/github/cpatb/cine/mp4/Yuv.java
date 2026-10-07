package io.github.cpatb.cine.mp4;

/**
 * Full-range 8-bit gray/RGB to limited-range ("TV", Y 16..235, Cb/Cr 16..240) BT.709 YUV 4:2:0,
 * written in JCodec's signed plane convention (stored byte = value - 128). Gray input gives
 * neutral chroma (128) exactly. Chroma is the mean of each 2x2 block (sizes must be even).
 */
final class Yuv {

    private static final double KR = 0.2126, KB = 0.0722, KG = 1 - KR - KB;

    private Yuv() {
    }

    /** Y = 16 + 219 v / 255, rounded half up. */
    static int limitedY(int v) {
        return 16 + (219 * v + 127) / 255;
    }

    static void rgbToYuv420(byte[] rgb, int w, int h, byte[] yp, byte[] up, byte[] vp) {
        int cw = w / 2;
        for (int cy = 0; cy < h / 2; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                double cbSum = 0, crSum = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int i = (2 * cy + dy) * w + 2 * cx + dx;
                        int r = rgb[3 * i] & 0xFF, g = rgb[3 * i + 1] & 0xFF, b = rgb[3 * i + 2] & 0xFF;
                        double yf = KR * r + KG * g + KB * b;          // 0..255
                        int y = (int) Math.floor(16 + 219.0 / 255.0 * yf + 0.5);
                        yp[i] = (byte) (clamp(y, 16, 235) - 128);
                        cbSum += (b - yf) / (2 * (1 - KB));            // -127.5..127.5
                        crSum += (r - yf) / (2 * (1 - KR));
                    }
                }
                int cb = (int) Math.floor(128 + 224.0 / 255.0 * cbSum / 4 + 0.5);
                int cr = (int) Math.floor(128 + 224.0 / 255.0 * crSum / 4 + 0.5);
                up[cy * cw + cx] = (byte) (clamp(cb, 16, 240) - 128);
                vp[cy * cw + cx] = (byte) (clamp(cr, 16, 240) - 128);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
