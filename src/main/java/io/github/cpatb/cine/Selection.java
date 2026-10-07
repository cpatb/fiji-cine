package io.github.cpatb.cine;

import java.util.Arrays;

/**
 * Which stored images to keep when opening a range with a step ("decimation").
 *
 * <p>Two explicit rules, so the kept frames are never a surprise:
 * <ul>
 *   <li>{@link Align#TRIGGER} keeps image numbers that are multiples of the step (the trigger
 *       frame, number 0, is always on the grid). This is what PCC does: comparing PCC-decimated
 *       cines with their sources on the test drive (x1000 and x100), every kept frame is the
 *       source image whose number is k*N, pixel-identical and with the same time stamp
 *       (10/10 and 25/25 frames).</li>
 *   <li>{@link Align#FIRST} keeps the first image of the range and every N-th after it.</li>
 * </ul>
 */
public final class Selection {

    public enum Align { TRIGGER, FIRST }

    private Selection() {
    }

    /**
     * Stored indices of the images with camera numbers in [firstNo, lastNo] kept by the rule.
     * The range is clipped to what the file contains.
     */
    public static int[] indices(CineFile cine, int firstNo, int lastNo, int step, Align align) {
        if (step < 1) throw new IllegalArgumentException("step must be >= 1, got " + step);
        int lo = Math.max(firstNo, cine.firstImageNo);
        int hi = Math.min(lastNo, cine.firstImageNo + cine.getImageCount() - 1);
        if (hi < lo) return new int[0];
        int start = (align == Align.TRIGGER) ? (int) (Math.floorDiv((long) lo + step - 1, step) * step) : lo;
        int n = start > hi ? 0 : (hi - start) / step + 1;
        int[] out = new int[n];
        for (int k = 0; k < n; k++) out[k] = cine.indexOf(start + k * step);
        return out;
    }

    /** All stored images. */
    public static int[] all(CineFile cine) {
        int[] out = new int[cine.getImageCount()];
        Arrays.setAll(out, i -> i);
        return out;
    }
}
