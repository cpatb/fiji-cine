package io.github.cpatb.cine.mp4;

/**
 * H.264 level limits that depend only on frame size and frame rate (ITU-T H.264 Table A-1:
 * MaxMBPS, MaxFS; frame width and height each at most sqrt(8 * MaxFS) macroblocks). Bit-rate
 * limits are not checked here because the bit rate is not known before encoding.
 */
final class H264Levels {

    /** level_idc, MaxMBPS (macroblocks/s), MaxFS (macroblocks). */
    private static final int[][] TABLE = {
            {10, 1485, 99}, {11, 3000, 396}, {12, 6000, 396}, {13, 11880, 396},
            {20, 11880, 396}, {21, 19800, 792}, {22, 20250, 1620},
            {30, 40500, 1620}, {31, 108000, 3600}, {32, 216000, 5120},
            {40, 245760, 8192}, {41, 245760, 8192}, {42, 522240, 8704},
            {50, 589824, 22080}, {51, 983040, 36864}, {52, 2073600, 36864}};

    private H264Levels() {
    }

    /** Smallest level_idc whose size/rate limits admit the stream, or -1 if none (even 5.2). */
    static int minimumLevel(int width, int height, double fps) {
        int mbw = (width + 15) / 16, mbh = (height + 15) / 16;
        long fs = (long) mbw * mbh;
        double mbps = fs * fps;
        for (int[] row : TABLE) {
            double side = Math.sqrt(8.0 * row[2]);
            if (fs <= row[2] && mbps <= row[1] && mbw <= side && mbh <= side) return row[0];
        }
        return -1;
    }

    static String name(int levelIdc) {
        return levelIdc < 0 ? "above 5.2" : (levelIdc / 10) + "." + (levelIdc % 10);
    }
}
