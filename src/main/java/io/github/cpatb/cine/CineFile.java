package io.github.cpatb.cine;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clean-room reader for Vision Research Phantom {@code .cine} files.
 *
 * <p>Layout (all little-endian), per the published Vision Research "Cine File Format":
 * <pre>
 *   CINEFILEHEADER (44 B) | BITMAPINFOHEADER (40 B) | SETUP (Length B) | tagged blocks
 *   | int64 image offsets[ImageCount] | per image: AnnotationSize u32, annotation, ImageSize u32, pixels
 * </pre>
 *
 * <p>Frames are returned <b>exactly as recorded</b>: no LUT, gain, gamma, tone curve, white
 * balance, colour matrix, filter or demosaic is applied. The only transforms are the ones the
 * storage format itself requires: unpacking packed pixels, mapping 10-bit packed codes through the
 * Phantom linearisation table (the stored codes are not linear), and turning bottom-up storage
 * into top-down rows. Frame numbering is the camera's: {@link #firstImageNo} is the number of the
 * first stored image, the trigger frame is 0, and pre-trigger frames are negative.
 *
 * <p>Thread-safe for concurrent {@link #readFrame} calls (positional channel reads).
 */
public final class CineFile implements Closeable {

    /** Pixel storage variants this reader understands. */
    public enum Packing { MONO8, MONO16, PACKED10, PACKED12L, BGR24, BGR48 }

    public static final int HEADER_SIZE = 44;
    public static final int BITMAPINFO_SIZE = 40;
    /** BITMAPINFOHEADER.biCompression codes used by Phantom cines. */
    public static final int BI_RGB = 0, BI_PACKED = 256, BI_PACKED_12L = 1024;
    /** CINEFILEHEADER.Compression: 0 grey/colour-interpolated, 2 raw (uninterpolated CFA). */
    public static final int CC_RGB = 0, CC_JPEG = 1, CC_UNINT = 2;
    /** Tagged-block types read here. */
    public static final int TAG_TIME_ONLY = 1002, TAG_EXPOSURE_ONLY = 1003;

    public final File file;
    // CINEFILEHEADER
    public final int compression, version, firstMovieImage, firstImageNo;
    public final long totalImageCount, imageCount;
    public final long offImageHeader, offSetup, offImageOffsets;
    /** Trigger time as TIME64: seconds since 1970-01-01 UTC plus fraction/2^32. */
    public final long triggerSeconds, triggerFraction;
    // BITMAPINFOHEADER
    public final int width, height, bitCount, biCompression, sizeImage, xPelsPerMeter, yPelsPerMeter;
    public final long clrUsed, clrImportant;
    public final boolean bottomUp;
    public final Packing packing;
    /** Bytes per stored row, derived from biSizeImage when it is consistent. */
    public final int stride;

    public final CineSetup setup;
    private final long[] imageOffsets;
    /** Tagged blocks: type -> {data offset, data length}. */
    private final Map<Integer, long[]> blocks;
    private long[] times;        // lazily decoded tag 1002 (TIME64)
    private long[] exposures;    // lazily decoded tag 1003 (raw units, see exposureSeconds)
    private final FileChannel ch;

    public CineFile(File file) throws IOException {
        this.file = file;
        this.ch = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        try {
            ByteBuffer h = read(0, HEADER_SIZE + BITMAPINFO_SIZE);
            if (h.get(0) != 'C' || h.get(1) != 'I') {
                throw new IOException("Not a Phantom cine file (missing 'CI' signature): " + file);
            }
            int headerSize = h.getShort(2) & 0xFFFF;
            compression = h.getShort(4) & 0xFFFF;
            version = h.getShort(6) & 0xFFFF;
            firstMovieImage = h.getInt(8);
            totalImageCount = h.getInt(12) & 0xFFFFFFFFL;
            firstImageNo = h.getInt(16);
            imageCount = h.getInt(20) & 0xFFFFFFFFL;
            offImageHeader = h.getInt(24) & 0xFFFFFFFFL;
            offSetup = h.getInt(28) & 0xFFFFFFFFL;
            offImageOffsets = h.getInt(32) & 0xFFFFFFFFL;
            triggerFraction = h.getInt(36) & 0xFFFFFFFFL;
            triggerSeconds = h.getInt(40) & 0xFFFFFFFFL;
            if (headerSize != HEADER_SIZE) {
                throw new IOException("Unexpected CINEFILEHEADER size " + headerSize);
            }
            if (compression == CC_JPEG) {
                throw new IOException("JPEG-compressed cines are not supported");
            }

            ByteBuffer bi = read(offImageHeader, BITMAPINFO_SIZE);
            width = bi.getInt(4);
            int biHeight = bi.getInt(8);
            bitCount = bi.getShort(14) & 0xFFFF;
            biCompression = bi.getInt(16);
            sizeImage = bi.getInt(20);
            xPelsPerMeter = bi.getInt(24);
            yPelsPerMeter = bi.getInt(28);
            clrUsed = bi.getInt(32) & 0xFFFFFFFFL;
            clrImportant = bi.getInt(36) & 0xFFFFFFFFL;
            height = Math.abs(biHeight);
            if (width <= 0 || height <= 0) throw new IOException("Bad image size " + width + "x" + biHeight);

            // Packed files carry biBitCount = 16 (verified on a real PCC P10 file), so packing is
            // keyed on biCompression and the stored bits per pixel come from the codec.
            if (biCompression == BI_PACKED) packing = Packing.PACKED10;
            else if (biCompression == BI_PACKED_12L) packing = Packing.PACKED12L;
            else if (biCompression == BI_RGB && bitCount == 8) packing = Packing.MONO8;
            else if (biCompression == BI_RGB && bitCount == 16) packing = Packing.MONO16;
            else if (biCompression == BI_RGB && bitCount == 24) packing = Packing.BGR24;
            else if (biCompression == BI_RGB && bitCount == 48) packing = Packing.BGR48;
            else throw new IOException("Unsupported pixel format: biBitCount=" + bitCount
                    + " biCompression=" + biCompression);
            int storedBits = packing == Packing.PACKED10 ? 10 : packing == Packing.PACKED12L ? 12 : bitCount;
            int minStride = (int) (((long) width * storedBits + 7) / 8);
            stride = (sizeImage > 0 && sizeImage % height == 0 && sizeImage / height >= minStride)
                    ? sizeImage / height : minStride;

            ByteBuffer lenBuf = read(offSetup + 140, 4);
            if (lenBuf.get(0) != 'S' || lenBuf.get(1) != 'T') {
                throw new IOException("SETUP block has no 'ST' mark; file too old or damaged");
            }
            int setupLen = lenBuf.getShort(2) & 0xFFFF;
            setup = new CineSetup(read(offSetup, setupLen));
            // Spec: BI_RGB data are stored bottom-up, packed data top-down (BI_RGB confirmed on real
            // files against PCC's TIFF export). SETUP.bFlipV inverts it, as in FFmpeg's demuxer
            // (no bFlipV = 1 file was available to confirm).
            boolean packed = packing == Packing.PACKED10 || packing == Packing.PACKED12L;
            bottomUp = !packed ^ setup.flipV;

            blocks = readBlocks(offSetup + setupLen, offImageOffsets);

            if (imageCount > Integer.MAX_VALUE) throw new IOException("Too many images: " + imageCount);
            ByteBuffer off = read(offImageOffsets, 8 * (int) imageCount);
            imageOffsets = new long[(int) imageCount];
            for (int i = 0; i < imageCount; i++) imageOffsets[i] = off.getLong(8 * i);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    public CineFile(String path) throws IOException {
        this(new File(path));
    }

    // ------------------------------------------------------------------ metadata

    public int getImageCount() {
        return imageOffsets.length;
    }

    /** Camera image number of stored index i (trigger = 0). */
    public int imageNumber(int index) {
        return firstImageNo + index;
    }

    /** Stored index of camera image number n, or -1 if that image is not in this file. */
    public int indexOf(int imageNumber) {
        long i = (long) imageNumber - firstImageNo;
        return (i >= 0 && i < imageOffsets.length) ? (int) i : -1;
    }

    public boolean isColor() {
        return packing == Packing.BGR24 || packing == Packing.BGR48;
    }

    /** True when frames are an uninterpolated colour-filter mosaic (returned as mono). */
    public boolean isRawCfa() {
        return compression == CC_UNINT;
    }

    /** Significant bits per sample in returned frames (10-bit packed decodes to 12-bit). */
    public int effectiveBits() {
        // P10 decodes to 12-bit linear whatever SETUP.RealBPP says (a real P10 file stores 10).
        if (packing == Packing.PACKED10) return 12;
        if (setup.realBpp > 0 && setup.realBpp <= 16) return setup.realBpp;
        switch (packing) {
            case MONO8: case BGR24: return 8;
            case PACKED10: case PACKED12L: return 12;
            default: return 16;
        }
    }

    /** Recording frame rate in fps from SETUP. */
    public double frameRate() {
        return setup.frameRate;
    }

    /** Unmodifiable view of the tagged blocks present: type -> {offset, length}. */
    public Map<Integer, long[]> blocks() {
        return Collections.unmodifiableMap(blocks);
    }

    /**
     * Absolute time of each stored image (tag 1002, TIME64 per image), in seconds since
     * 1970-01-01 UTC, or null if the file carries no per-image times.
     */
    public synchronized double[] imageTimes() throws IOException {
        long[] raw = imageTimes64();
        if (raw == null) return null;
        double[] out = new double[raw.length];
        // unsigned: seconds >= 2^31 (after 2038) must not turn negative
        for (int i = 0; i < raw.length; i++) out[i] = (raw[i] >>> 32) + (raw[i] & 0xFFFFFFFFL) / 4294967296.0;
        return out;
    }

    /**
     * True when every stored image has a time stamp (tag 1002). If the block is missing or
     * shorter than the image count, {@link #relativeTimes} uses imageNumber / frameRate for ALL
     * images (never a mix of the two time bases). That fallback is wrong for a decimated file,
     * whose SETUP.FrameRate is the recording rate.
     */
    public boolean hasCompleteTimes() throws IOException {
        long[] t = imageTimes64();
        return t != null && t.length == imageOffsets.length;
    }

    /** Per-image TIME64 values (seconds << 32 | fraction), exactly as stored, or null. */
    public synchronized long[] imageTimes64() throws IOException {
        if (times == null && blocks.containsKey(TAG_TIME_ONLY)) {
            long[] b = blocks.get(TAG_TIME_ONLY);
            int n = (int) Math.min(b[1] / 8, imageOffsets.length);
            ByteBuffer t = read(b[0], 8 * n);
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                long frac = t.getInt(8 * i) & 0xFFFFFFFFL;
                long sec = t.getInt(8 * i + 4) & 0xFFFFFFFFL;
                out[i] = sec << 32 | frac;
            }
            times = out;
        }
        return times;
    }

    /** Trigger time in seconds since 1970-01-01 UTC. */
    public double triggerTime() {
        return triggerSeconds + triggerFraction / 4294967296.0;
    }

    /**
     * Time of each stored image relative to the trigger, in seconds. Uses the per-image time
     * stamps when present, otherwise imageNumber / frameRate.
     */
    public double[] relativeTimes() throws IOException {
        // Subtract in integer TIME64 units: absolute times as doubles resolve only ~0.24 us today.
        long[] abs = hasCompleteTimes() ? imageTimes64() : null;
        long trig = triggerSeconds << 32 | triggerFraction;
        double[] out = new double[imageOffsets.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = abs != null ? (abs[i] - trig) / 4294967296.0 : imageNumber(i) / frameRate();
        }
        return out;
    }

    /**
     * Raw per-image exposure values from tag 1003 (uint32 each), or null. Per the format
     * documentation the unit is seconds * 2^32 (one public sample: 99000.1 ns vs ShutterNs 99000);
     * on the lab files it reads 830.7 ns against ShutterNs 1000, i.e. the camera's measured value,
     * not the setting. Returned unconverted.
     */
    public synchronized long[] exposuresRaw() throws IOException {
        if (exposures == null && blocks.containsKey(TAG_EXPOSURE_ONLY)) {
            long[] b = blocks.get(TAG_EXPOSURE_ONLY);
            int n = (int) Math.min(b[1] / 4, imageOffsets.length);
            ByteBuffer t = read(b[0], 4 * n);
            long[] out = new long[n];
            for (int i = 0; i < n; i++) out[i] = t.getInt(4 * i) & 0xFFFFFFFFL;
            exposures = out;
        }
        return exposures;
    }

    /** Human-readable metadata in a stable order (header, bitmap, setup). */
    public Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("File", file.getAbsolutePath());
        m.put("Cine version", String.valueOf(version));
        m.put("Compression code", compression + (compression == CC_UNINT ? " (raw CFA mosaic)" : ""));
        m.put("Image size", width + " x " + height);
        m.put("Storage", packing + ", biBitCount " + bitCount + ", biCompression " + biCompression
                + (bottomUp ? ", bottom-up rows" : ", top-down rows"));
        m.put("Stored images", String.valueOf(imageOffsets.length));
        m.put("First stored image number", String.valueOf(firstImageNo));
        m.put("Last stored image number", String.valueOf(firstImageNo + imageOffsets.length - 1));
        m.put("First movie image / total in camera", firstMovieImage + " / " + totalImageCount);
        m.put("Trigger time (UTC s since 1970)", String.format("%.9f", triggerTime()));
        m.put("Sensor pixels per metre (x, y)", xPelsPerMeter + ", " + yPelsPerMeter);
        m.put("biClrImportant", String.valueOf(clrImportant));
        m.put("Tagged blocks", blocks.keySet().toString());
        m.putAll(setup.asMap);
        return m;
    }

    // ------------------------------------------------------------------ pixels

    /** Offset in the file of the first pixel byte of stored image {@code index}. */
    public long pixelOffset(int index) throws IOException {
        long p = imageOffsets[index];
        long annotationSize = read(p, 4).getInt(0) & 0xFFFFFFFFL;
        if (annotationSize < 8) throw new IOException("Bad annotation size " + annotationSize + " at image " + index);
        return p + annotationSize;
    }

    /** The undecoded bytes of one stored image, exactly as on disk. */
    public byte[] readStoredBytes(int index) throws IOException {
        int n = stride * height;
        ByteBuffer b = read(pixelOffset(index), n);
        byte[] out = new byte[n];
        b.get(out);
        return out;
    }

    /**
     * Decode stored image {@code index} into top-down rows.
     *
     * @return byte[w*h] (MONO8), short[w*h] (MONO16, PACKED10 -> 12-bit linear, PACKED12L),
     *         byte[3*w*h] RGB-interleaved (BGR24), or short[3*w*h] RGB-interleaved (BGR48).
     *         Shorts are unsigned sample values stored in Java's signed type.
     */
    public Object readFrame(int index) throws IOException {
        if (index < 0 || index >= imageOffsets.length) {
            throw new IndexOutOfBoundsException("image index " + index + " of " + imageOffsets.length);
        }
        byte[] s = readStoredBytes(index);
        int w = width, h = height;
        switch (packing) {
            case MONO8: {
                byte[] out = new byte[w * h];
                for (int y = 0; y < h; y++) System.arraycopy(s, row(y) * stride, out, y * w, w);
                return out;
            }
            case MONO16: {
                short[] out = new short[w * h];
                for (int y = 0; y < h; y++) {
                    int src = row(y) * stride, dst = y * w;
                    for (int x = 0; x < w; x++, src += 2) {
                        out[dst + x] = (short) ((s[src] & 0xFF) | (s[src + 1] & 0xFF) << 8);
                    }
                }
                return out;
            }
            case PACKED10: {
                short[] out = new short[w * h];
                for (int y = 0; y < h; y++) Packed.unpack10(s, row(y) * stride, out, y * w, w);
                return out;
            }
            case PACKED12L: {
                short[] out = new short[w * h];
                for (int y = 0; y < h; y++) Packed.unpack12L(s, row(y) * stride, out, y * w, w);
                return out;
            }
            case BGR24: {
                byte[] out = new byte[3 * w * h];
                for (int y = 0; y < h; y++) {
                    int src = row(y) * stride, dst = 3 * y * w;
                    for (int x = 0; x < w; x++, src += 3, dst += 3) {
                        out[dst] = s[src + 2];
                        out[dst + 1] = s[src + 1];
                        out[dst + 2] = s[src];
                    }
                }
                return out;
            }
            case BGR48: {
                short[] out = new short[3 * w * h];
                for (int y = 0; y < h; y++) {
                    int src = row(y) * stride, dst = 3 * y * w;
                    for (int x = 0; x < w; x++, src += 6, dst += 3) {
                        out[dst] = (short) ((s[src + 4] & 0xFF) | (s[src + 5] & 0xFF) << 8);
                        out[dst + 1] = (short) ((s[src + 2] & 0xFF) | (s[src + 3] & 0xFF) << 8);
                        out[dst + 2] = (short) ((s[src] & 0xFF) | (s[src + 1] & 0xFF) << 8);
                    }
                }
                return out;
            }
            default:
                throw new IllegalStateException(String.valueOf(packing));
        }
    }

    /** Stored row index holding top-down row y. */
    private int row(int y) {
        return bottomUp ? height - 1 - y : y;
    }

    // ------------------------------------------------------------------ io helpers

    private Map<Integer, long[]> readBlocks(long start, long end) throws IOException {
        Map<Integer, long[]> out = new LinkedHashMap<>();
        long pos = start;
        while (pos + 8 <= end) {
            ByteBuffer b = read(pos, 8);
            long size = b.getInt(0) & 0xFFFFFFFFL;
            int type = b.getShort(4) & 0xFFFF;
            if (size < 8 || pos + size > end) break;  // padding or end of tagged area
            out.put(type, new long[]{pos + 8, size - 8});
            pos += size;
        }
        return out;
    }

    private ByteBuffer read(long pos, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r < 0) throw new EOFException("Unexpected end of file at " + (pos + b.position()) + " in " + file);
        }
        b.flip();
        return b;
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
