package io.github.cpatb.cine.mp4;

import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.LUT;

/**
 * The single fixed linear map used for a whole movie:
 * {@code out = clamp(floor((v - min) * 255 / (max - min) + 0.5), 0, 255)}.
 * RGB input is mapped channel by channel with the same min/max. For gray input the image's LUT
 * is then applied to the 8-bit index: identity -> gray output, inverted gray -> 255 - index,
 * any other LUT -> RGB output through that LUT. Works on copies; never writes to the source.
 */
final class FrameMap {

    /** Fraction of pixels saturated in Auto mode, split equally between the two tails. */
    static final double AUTO_SATURATED = 0.0035;
    /** Frames sampled (evenly) for the Auto range. */
    static final int AUTO_SAMPLES = 50;

    final double min, max;
    private final double scale;
    private final int bitDepth;
    private final byte[] lut;       // integer input value -> 8-bit index (8/16-bit and RGB channels)
    private final int[] colorLut;   // null unless a non-gray LUT is applied
    private final boolean invert;

    FrameMap(int bitDepth, double min, double max, LUT imageLut) {
        if (!(max > min)) throw new IllegalArgumentException("Scaling needs max > min (got " + min + ", " + max + ")");
        this.bitDepth = bitDepth;
        this.min = min;
        this.max = max;
        this.scale = 255.0 / (max - min);
        int n = bitDepth == 16 ? 65536 : (bitDepth == 8 || bitDepth == 24) ? 256 : 0;
        lut = n > 0 ? new byte[n] : null;
        for (int v = 0; v < n; v++) lut[v] = (byte) map(v);
        boolean inv = false;
        int[] cl = null;
        if (bitDepth != 24 && imageLut != null && imageLut.getMapSize() == 256) {
            byte[] r = new byte[256], g = new byte[256], b = new byte[256];
            imageLut.getReds(r);
            imageLut.getGreens(g);
            imageLut.getBlues(b);
            boolean ident = true, inverted = true;
            for (int i = 0; i < 256; i++) {
                int ri = r[i] & 0xFF, gi = g[i] & 0xFF, bi = b[i] & 0xFF;
                if (ri != i || gi != i || bi != i) ident = false;
                if (ri != 255 - i || gi != 255 - i || bi != 255 - i) inverted = false;
            }
            if (!ident && inverted) {
                inv = true;
            } else if (!ident) {
                cl = new int[256];
                for (int i = 0; i < 256; i++) cl[i] = (r[i] & 0xFF) << 16 | (g[i] & 0xFF) << 8 | (b[i] & 0xFF);
            }
        }
        invert = inv;
        colorLut = cl;
    }

    /** The map for one value. */
    int map(double v) {
        if (Double.isNaN(v)) return 0;
        double x = Math.floor((v - min) * scale + 0.5);
        return x <= 0 ? 0 : x >= 255 ? 255 : (int) x;
    }

    boolean rgbOutput() {
        return bitDepth == 24 || colorLut != null;
    }

    boolean invertedLut() {
        return invert;
    }

    boolean colorLut() {
        return colorLut != null;
    }

    /** 8-bit gray copy (only when !rgbOutput()). */
    byte[] toGray(ImageProcessor ip) {
        int n = ip.getWidth() * ip.getHeight();
        byte[] out = new byte[n];
        Object px = ip.getPixels();
        if (px instanceof byte[]) {
            byte[] p = (byte[]) px;
            for (int i = 0; i < n; i++) out[i] = lut[p[i] & 0xFF];
        } else if (px instanceof short[]) {
            short[] p = (short[]) px;
            for (int i = 0; i < n; i++) out[i] = lut[p[i] & 0xFFFF];
        } else if (px instanceof float[]) {
            float[] p = (float[]) px;
            for (int i = 0; i < n; i++) out[i] = (byte) map(p[i]);
        } else {
            throw new IllegalArgumentException("Unsupported pixel type for gray output: " + px.getClass());
        }
        if (invert) for (int i = 0; i < n; i++) out[i] = (byte) (255 - (out[i] & 0xFF));
        return out;
    }

    /** RGB copy as packed ints (only when rgbOutput()). */
    int[] toRgb(ImageProcessor ip) {
        int n = ip.getWidth() * ip.getHeight();
        int[] out = new int[n];
        Object px = ip.getPixels();
        if (px instanceof int[]) {
            int[] p = (int[]) px;
            for (int i = 0; i < n; i++) {
                int c = p[i];
                out[i] = (lut[(c >> 16) & 0xFF] & 0xFF) << 16 | (lut[(c >> 8) & 0xFF] & 0xFF) << 8 | (lut[c & 0xFF] & 0xFF);
            }
        } else {
            byte[] g = toGray(ip);
            for (int i = 0; i < n; i++) out[i] = colorLut[g[i] & 0xFF];
        }
        return out;
    }

    /**
     * Auto range: histogram pooled over up to {@link #AUTO_SAMPLES} frames taken evenly from the
     * export list (sample k is list position k*(n-1)/(m-1), integer division), with
     * {@link #AUTO_SATURATED} of the pixels saturated, half in each tail. Integer data use exact
     * per-value bins; RGB pools the three channels; float data use 65536 bins between the sampled
     * extremes. Returns {min, max}.
     */
    static double[] autoRange(ImageStack stack, int[] slices, int bitDepth) {
        int n = slices.length, m = Math.min(n, AUTO_SAMPLES);
        int[] sample = new int[m];
        for (int k = 0; k < m; k++) sample[k] = slices[m == 1 ? 0 : (int) ((long) k * (n - 1) / (m - 1))];
        if (bitDepth == 32) return autoFloat(stack, sample);
        int bins = bitDepth == 16 ? 65536 : 256;
        long[] h = new long[bins];
        long total = 0;
        for (int s : sample) {
            Object px = stack.getProcessor(s).getPixels();
            if (px instanceof byte[]) {
                for (byte v : (byte[]) px) h[v & 0xFF]++;
                total += ((byte[]) px).length;
            } else if (px instanceof short[]) {
                for (short v : (short[]) px) h[v & 0xFFFF]++;
                total += ((short[]) px).length;
            } else {
                for (int c : (int[]) px) {
                    h[(c >> 16) & 0xFF]++;
                    h[(c >> 8) & 0xFF]++;
                    h[c & 0xFF]++;
                }
                total += 3L * ((int[]) px).length;
            }
        }
        long threshold = (long) (total * AUTO_SATURATED / 2);
        int lo = 0, hi = bins - 1;
        long c = 0;
        for (int i = 0; i < bins; i++) {
            c += h[i];
            if (c > threshold) { lo = i; break; }
        }
        c = 0;
        for (int i = bins - 1; i >= 0; i--) {
            c += h[i];
            if (c > threshold) { hi = i; break; }
        }
        if (hi <= lo) {  // nearly constant data: fall back to the sampled extremes
            int a = 0, b = bins - 1;
            while (a < bins && h[a] == 0) a++;
            while (b > 0 && h[b] == 0) b--;
            lo = a;
            hi = Math.max(b, a + 1);
        }
        return new double[]{lo, hi};
    }

    private static double[] autoFloat(ImageStack stack, int[] sample) {
        double fmin = Double.POSITIVE_INFINITY, fmax = Double.NEGATIVE_INFINITY;
        long total = 0;
        for (int s : sample) {
            for (float v : (float[]) stack.getProcessor(s).getPixels()) {
                if (Float.isNaN(v) || Float.isInfinite(v)) continue;
                if (v < fmin) fmin = v;
                if (v > fmax) fmax = v;
                total++;
            }
        }
        if (total == 0) return new double[]{0, 1};
        if (!(fmax > fmin)) return new double[]{fmin, fmin + 1};
        int bins = 65536;
        double w = (fmax - fmin) / bins;
        long[] h = new long[bins];
        for (int s : sample) {
            for (float v : (float[]) stack.getProcessor(s).getPixels()) {
                if (Float.isNaN(v) || Float.isInfinite(v)) continue;
                int i = (int) ((v - fmin) / w);
                h[Math.min(bins - 1, Math.max(0, i))]++;
            }
        }
        long threshold = (long) (total * AUTO_SATURATED / 2);
        int lo = 0, hi = bins - 1;
        long c = 0;
        for (int i = 0; i < bins; i++) { c += h[i]; if (c > threshold) { lo = i; break; } }
        c = 0;
        for (int i = bins - 1; i >= 0; i--) { c += h[i]; if (c > threshold) { hi = i; break; } }
        double a = fmin + lo * w, b = fmin + (hi + 1) * w;
        return b > a ? new double[]{a, b} : new double[]{fmin, fmax};
    }
}
