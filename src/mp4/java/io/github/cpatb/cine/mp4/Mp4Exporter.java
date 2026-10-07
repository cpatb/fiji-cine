package io.github.cpatb.cine.mp4;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.process.LUT;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;

/**
 * Streams the frames of an ImagePlus to an H.264 MP4 that PowerPoint can play. Frames are read one
 * at a time (virtual stacks stay virtual), converted to 8-bit on copies with one fixed linear map,
 * optionally downsized (bilinear) and labelled, padded to even size by repeating the last column/row, and
 * handed to the encoder. The ImagePlus and its pixels are never modified.
 */
public final class Mp4Exporter {

    private Mp4Exporter() {
    }

    /**
     * Stack indices that make up the movie: the whole stack, or for a hyperstack the time series
     * (else the z series) of the current channel and position.
     */
    public static int[] movieSlices(ImagePlus imp) {
        int c = imp.getNChannels(), z = imp.getNSlices(), t = imp.getNFrames();
        int size = imp.getStackSize();
        if (c * z * t != size || (c == 1 && (z == 1 || t == 1))) {
            int[] all = new int[size];
            for (int i = 0; i < size; i++) all[i] = i + 1;
            return all;
        }
        boolean overTime = t > 1;
        int n = overTime ? t : z;
        int[] out = new int[n];
        for (int k = 0; k < n; k++) {
            out[k] = overTime ? imp.getStackIndex(imp.getC(), imp.getZ(), k + 1)
                    : imp.getStackIndex(imp.getC(), k + 1, imp.getT());
        }
        return out;
    }

    /** Export; returns a one-paragraph summary (also written to the Log window). */
    public static String export(ImagePlus imp, Mp4Options o, File out) throws IOException {
        int[] movie = movieSlices(imp);
        int n = movie.length;
        int last = o.last == 0 ? n : Math.min(o.last, n);
        if (o.first > n) throw new IllegalArgumentException("First frame " + o.first + " is beyond the last frame (" + n + ")");
        int count = (last - o.first) / o.step + 1;
        int[] sel = new int[count];
        for (int k = 0; k < count; k++) sel[k] = movie[o.first - 1 + k * o.step];

        int bitDepth = imp.getBitDepth();
        ImageStack stack = imp.getStack();
        double min, max;
        String how;
        if (o.scaling == Mp4Options.SCALE_AUTO) {
            double[] r = FrameMap.autoRange(stack, sel, bitDepth);
            min = r[0];
            max = r[1];
            how = "auto (" + String.format(java.util.Locale.ROOT, "%.2f", FrameMap.AUTO_SATURATED * 100) + "% saturated over " + Math.min(count, FrameMap.AUTO_SAMPLES) + " sampled frames)";
        } else if (o.scaling == Mp4Options.SCALE_EXPLICIT) {
            min = o.min;
            max = o.max;
            how = "explicit";
        } else if (bitDepth == 24) {
            min = 0;
            max = 255;
            how = "display (RGB: pixels as shown, identity map)";
        } else {
            min = imp.getDisplayRangeMin();
            max = imp.getDisplayRangeMax();
            how = "current display range";
        }
        LUT lut = null;
        if (bitDepth != 24) lut = imp.isComposite() ? ((CompositeImage) imp).getChannelLut() : imp.getProcessor().getLut();
        FrameMap map = new FrameMap(bitDepth, min, max, lut);
        boolean rgb = map.rgbOutput();

        int srcW = imp.getWidth(), srcH = imp.getHeight();
        int dstW = srcW, dstH = srcH;
        boolean resized = o.maxWidth > 0 && srcW > o.maxWidth;
        if (resized) {
            dstW = o.maxWidth;
            dstH = Math.max(1, (int) Math.round((double) srcH * o.maxWidth / srcW));
        }
        int encW = dstW + (dstW & 1), encH = dstH + (dstH & 1);
        FrameRate rate = FrameRate.of(o.fps);

        VideoSink sink = openSink(o, out, encW, encH, rgb, rate);
        IJ.log("PowerPoint MP4: " + imp.getTitle() + " -> " + out.getAbsolutePath());
        int lvl = H264Levels.minimumLevel(encW, encH, rate.value());
        if (lvl < 0 || lvl > 51) {
            IJ.log("  WARNING: " + encW + "x" + encH + " at " + rate + " fps needs H.264 level " + H264Levels.name(lvl)
                    + ", above 5.1; PowerPoint may not play it. Use a width cap or a lower frame rate.");
        }
        byte[] frame = new byte[encW * encH * (rgb ? 3 : 1)];
        boolean ok = false;
        try {
            for (int k = 0; k < count; k++) {
                if (IJ.escapePressed()) throw new IOException("Cancelled (Esc)");
                IJ.showProgress(k, count);
                int s = sel[k];
                ImageProcessor src = stack.getProcessor(s);
                ImageProcessor ip = rgb ? new ColorProcessor(srcW, srcH, map.toRgb(src))
                        : new ByteProcessor(srcW, srcH, map.toGray(src));
                if (resized) {
                    ip.setInterpolationMethod(ImageProcessor.BILINEAR);
                    ip = ip.resize(dstW, dstH);
                }
                if (o.burnLabel) burn(ip, o.first + k * o.step, stack.getSliceLabel(s));
                pack(ip, rgb, encW, encH, frame);
                if (rgb) sink.writeRgb(frame);
                else sink.writeGray(frame);
            }
            sink.finish();
            ok = true;
        } finally {
            IJ.showProgress(1.0);
            if (!ok) sink.abort();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  frames ").append(count).append(" (").append(o.first).append("..").append(last)
                .append(" step ").append(o.step).append(" of ").append(n).append("), ").append(rate).append(" fps\n");
        sb.append("  map: ").append(how).append(", min = ").append(min).append(", max = ").append(max)
                .append(" -> 0..255, one fixed linear map for all frames")
                .append(map.invertedLut() ? ", inverted LUT applied" : "")
                .append(map.colorLut() ? ", colour LUT applied (RGB output)" : "").append('\n');
        sb.append("  size: ").append(srcW).append("x").append(srcH);
        if (resized) sb.append(" -> ").append(dstW).append("x").append(dstH).append(" (bilinear, width cap)");
        if (encW != dstW || encH != dstH) sb.append(" -> ").append(encW).append("x").append(encH).append(" (last column/row repeated to reach even size)");
        sb.append(", ").append(rgb ? "RGB" : "gray").append('\n');
        sb.append("  encoder: ").append(sink.describe()).append(", quality ").append(o.crf)
                .append(", H.264 level needed ").append(H264Levels.name(lvl));
        IJ.log(sb.toString());
        return sb.toString();
    }

    private static VideoSink openSink(Mp4Options o, File out, int w, int h, boolean rgb, FrameRate rate) throws IOException {
        if (o.encoder != Mp4Options.ENC_JCODEC) {
            String[] why = new String[1];
            String exe = FfmpegSink.find(o.ffmpegPath, why);
            if (exe != null) return new FfmpegSink(exe, out, w, h, rgb, rate, o.crf);
            if (o.encoder == Mp4Options.ENC_FFMPEG) throw new IOException("ffmpeg not usable: " + why[0]);
            IJ.log("  ffmpeg not usable (" + why[0] + "); using the built-in JCodec encoder");
        }
        return new JCodecSink(out, w, h, rate, o.crf);
    }

    private static void burn(ImageProcessor ip, int frameNo, String label) {
        String text = "frame " + frameNo;
        if (label != null && !label.trim().isEmpty()) text += "   " + label.split("\n")[0].trim();
        int size = Math.max(9, Math.min(24, ip.getHeight() / 20));
        ip.setFont(new Font("SansSerif", Font.PLAIN, size));
        ip.setAntialiasedText(true);
        ip.setColor(Color.white);
        ip.drawString(text, 2, size + 2, Color.black);
    }

    /**
     * Copy into the even-sized frame buffer. An extra column/row (odd sizes only) repeats the last
     * column/row: with 4:2:0 chroma the pad shares a chroma sample with the last data column, so a
     * black pad would desaturate that column in RGB movies (measured: 15 dB vs 43 dB on it).
     */
    private static void pack(ImageProcessor ip, boolean rgb, int encW, int encH, byte[] dst) {
        int w = ip.getWidth(), h = ip.getHeight();
        int bpp = rgb ? 3 : 1;
        if (!rgb) {
            byte[] p = (byte[]) ip.getPixels();
            for (int y = 0; y < h; y++) System.arraycopy(p, y * w, dst, y * encW, w);
        } else {
            int[] p = (int[]) ip.getPixels();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = p[y * w + x], j = 3 * (y * encW + x);
                    dst[j] = (byte) (c >> 16);
                    dst[j + 1] = (byte) (c >> 8);
                    dst[j + 2] = (byte) c;
                }
            }
        }
        if (encW > w) {
            for (int y = 0; y < h; y++) {
                int row = y * encW * bpp;
                System.arraycopy(dst, row + (w - 1) * bpp, dst, row + w * bpp, bpp);
            }
        }
        if (encH > h) System.arraycopy(dst, (h - 1) * encW * bpp, dst, h * encW * bpp, encW * bpp);
    }
}
