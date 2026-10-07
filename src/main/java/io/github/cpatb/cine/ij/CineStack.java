package io.github.cpatb.cine.ij;

import ij.IJ;
import ij.VirtualStack;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import io.github.cpatb.cine.CineFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A virtual ImageJ stack over a selection of images of a cine file. Frames are decoded on demand
 * with a small LRU cache, so multi-gigabyte recordings open instantly.
 *
 * <p>The selection is an explicit list of stored indices; the decimation rule that produced it is
 * chosen by the caller (see {@link Selection}) and recorded in each slice label.
 *
 * <p>48-bit colour cines are shown as 8-bit RGB (top byte of each 16-bit sample after scaling by
 * the real bit depth); use "Load into memory" with a mono file, or the Phantastic library, for
 * full-depth colour. Mono data are never rescaled.
 */
public class CineStack extends VirtualStack {

    private static final int CACHE_FRAMES = 16;

    private final CineFile cine;
    private final int[] indices;
    private final double[] relTimes;
    /**
     * Cached pixel arrays (not processors). Each getProcessor call wraps the cached array in a NEW
     * processor: ImagePlus adopts the processor it gets for the first slice as its own and later
     * calls setPixels on it when the user changes slice, so handing out a shared cached processor
     * let slice 1's cache entry take on the pixels of whatever slice was shown last.
     */
    private final Map<Integer, Object> cache = new LinkedHashMap<Integer, Object>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Object> e) {
            return size() > CACHE_FRAMES;
        }
    };

    public CineStack(CineFile cine, int[] indices) throws IOException {
        super(cine.width, cine.height, null, cine.file.getParent());
        this.cine = cine;
        this.indices = indices.clone();
        this.relTimes = cine.relativeTimes();
    }

    public CineFile cine() {
        return cine;
    }

    /** Stored index of 1-based slice n. */
    public int storedIndex(int n) {
        return indices[n - 1];
    }

    @Override
    public int getSize() {
        return indices.length;
    }

    @Override
    public int getBitDepth() {
        switch (cine.packing) {
            case MONO8: return 8;
            case BGR24: case BGR48: return 24;
            default: return 16;
        }
    }

    @Override
    public synchronized ImageProcessor getProcessor(int n) {
        return wrap(getPixels(n));
    }

    /** A fresh processor around the given pixel array (never a cached processor object). */
    private ImageProcessor wrap(Object px) {
        int w = cine.width, h = cine.height;
        if (px instanceof byte[]) return new ByteProcessor(w, h, (byte[]) px);
        if (px instanceof short[]) return new ShortProcessor(w, h, (short[]) px, null);
        if (px instanceof int[]) return new ColorProcessor(w, h, (int[]) px);
        if (px instanceof float[]) return new ij.process.FloatProcessor(w, h, (float[]) px);
        throw new IllegalStateException("Unexpected pixel type " + px.getClass());
    }

    @Override
    public String getSliceLabel(int n) {
        int i = indices[n - 1];
        return label(cine.imageNumber(i), relTimes[i]);
    }

    static String label(int imageNumber, double tRel) {
        return String.format("image %d, t = %.9f s", imageNumber, tRel);
    }

    @Override
    public void deleteSlice(int n) {
        throw new UnsupportedOperationException("Cine virtual stacks are read-only; duplicate first");
    }

    @Override
    public void addSlice(String sliceLabel, Object pixels) {
        throw new UnsupportedOperationException("Cine virtual stacks are read-only; duplicate first");
    }

    @Override
    public synchronized Object getPixels(int n) {
        Object px = cache.get(n);
        if (px == null) {
            try {
                px = decode(cine, indices[n - 1]).getPixels();
            } catch (IOException e) {
                // Never substitute a blank frame: a dark slice in a stack looks like data.
                throw new IllegalStateException("Cannot read image " + cine.imageNumber(indices[n - 1])
                        + " of " + cine.file.getName() + ": " + e.getMessage(), e);
            }
            cache.put(n, px);
        }
        return px;
    }

    /**
     * Read-only stack: edits are not kept (as for any ImageJ virtual stack). Keeping them in the
     * LRU cache would let an evicted slice silently revert to file data mid-processing.
     * Duplicate the stack (Image &gt; Duplicate) to edit it.
     */
    @Override
    public synchronized void setPixels(Object pixels, int n) {
        // intentionally empty
    }

    /** Decode one stored image into an ImageJ processor without altering sample values (mono). */
    public static ImageProcessor decode(CineFile cine, int index) throws IOException {
        Object px = cine.readFrame(index);
        int w = cine.width, h = cine.height;
        switch (cine.packing) {
            case MONO8:
                return new ByteProcessor(w, h, (byte[]) px);
            case MONO16: case PACKED10: case PACKED12L:
                return new ShortProcessor(w, h, (short[]) px, null);
            case BGR24: {
                byte[] b = (byte[]) px;
                int[] rgb = new int[w * h];
                for (int i = 0, j = 0; i < rgb.length; i++, j += 3) {
                    rgb[i] = (b[j] & 0xFF) << 16 | (b[j + 1] & 0xFF) << 8 | (b[j + 2] & 0xFF);
                }
                return new ColorProcessor(w, h, rgb);
            }
            case BGR48: {
                short[] s = (short[]) px;
                int shift = Math.max(0, cine.effectiveBits() - 8);
                int[] rgb = new int[w * h];
                for (int i = 0, j = 0; i < rgb.length; i++, j += 3) {
                    int r = Math.min(255, (s[j] & 0xFFFF) >> shift);
                    int g = Math.min(255, (s[j + 1] & 0xFFFF) >> shift);
                    int b = Math.min(255, (s[j + 2] & 0xFFFF) >> shift);
                    rgb[i] = r << 16 | g << 8 | b;
                }
                return new ColorProcessor(w, h, rgb);
            }
            default:
                throw new IllegalStateException(String.valueOf(cine.packing));
        }
    }
}
