package io.github.cpatb.cine.mp4;

import ij.Prefs;
import ij.gui.GenericDialog;

/**
 * Export settings and the shared, macro-recordable dialog. Every label starts with a unique word,
 * which is the macro key: playback, first, last, every, scaling, minimum, maximum, width, quality,
 * encoder, ffmpeg, burn (batch adds input, output, overwrite).
 */
public final class Mp4Options {

    public static final String[] SCALING = {
            "Use current display range (what you see)",
            "Auto (0.35% saturated, computed once over sampled frames, fixed for the whole movie)",
            "Explicit minimum and maximum (below)"};
    public static final int SCALE_DISPLAY = 0, SCALE_AUTO = 1, SCALE_EXPLICIT = 2;

    public static final String[] ENCODERS = {
            "ffmpeg if found, else built-in (JCodec)",
            "ffmpeg only",
            "Built-in (JCodec) only"};
    public static final int ENC_AUTO = 0, ENC_FFMPEG = 1, ENC_JCODEC = 2;

    static final String PREF_FFMPEG = "pptmp4.ffmpeg";

    public double fps = 30;
    /** 1-based, inclusive; last = 0 means "to the end". */
    public int first = 1, last = 0, step = 1;
    public int scaling = SCALE_DISPLAY;
    public double min = 0, max = 255;
    /** 0 = keep the size. */
    public int maxWidth = 0;
    public int crf = 18;
    public int encoder = ENC_AUTO;
    public String ffmpegPath = "";
    public boolean burnLabel = false;
    // batch only
    public String inputFiles = "";
    public String outputDir = "";
    public boolean overwrite = false;

    /**
     * Shows the dialog. {@code nFrames} is the frame count of the open image (0 in batch mode);
     * {@code dispMin/dispMax} seed the explicit range. Returns null if cancelled or invalid.
     */
    public static Mp4Options dialog(String title, int nFrames, double dispMin, double dispMax,
                                    boolean batch, String files) {
        Mp4Options o = new Mp4Options();
        o.ffmpegPath = Prefs.get(PREF_FFMPEG, "");
        GenericDialog gd = new GenericDialog(title);
        if (batch) {
            gd.addStringField("Input files (separated by ;)", files == null ? "" : files, 50);
            gd.addDirectoryField("Output folder", "", 50);
            gd.addCheckbox("Overwrite existing MP4 files", false);
        }
        gd.addNumericField("Playback frame rate", o.fps, 3, 8, "fps");
        gd.addNumericField("First frame", 1, 0, 8, "");
        gd.addNumericField("Last frame", batch ? 0 : nFrames, 0, 8, batch ? "(0 = to the end)" : "of " + nFrames);
        gd.addNumericField("Every", 1, 0, 8, "th frame (step N)");
        gd.addChoice("Scaling", SCALING, SCALING[SCALE_DISPLAY]);
        gd.addNumericField("Minimum (explicit scaling)", dispMin, 3, 10, "");
        gd.addNumericField("Maximum (explicit scaling)", dispMax, 3, 10, "");
        gd.addMessage("One fixed linear map for the whole movie: out = 255 (v - min) / (max - min),\n"
                + "rounded and clipped to 0..255. No per-frame auto-contrast, so brightness does not flicker.");
        gd.addNumericField("Width cap", 0, 0, 8, "px (0 = keep size; larger frames are downsized, bilinear)");
        gd.addNumericField("Quality (CRF)", o.crf, 0, 8, "0-51, lower = better (JCodec: fixed QP)");
        gd.addChoice("Encoder", ENCODERS, ENCODERS[ENC_AUTO]);
        gd.addStringField("ffmpeg executable (blank = search PATH)", o.ffmpegPath, 40);
        gd.addCheckbox("Burn in slice label (frame number / time)", false);
        gd.showDialog();
        if (gd.wasCanceled()) return null;
        if (batch) {
            o.inputFiles = gd.getNextString().trim();
            o.outputDir = gd.getNextString().trim();
            o.overwrite = gd.getNextBoolean();
        }
        o.fps = gd.getNextNumber();
        o.first = (int) gd.getNextNumber();
        o.last = (int) gd.getNextNumber();
        o.step = (int) gd.getNextNumber();
        o.scaling = gd.getNextChoiceIndex();
        o.min = gd.getNextNumber();
        o.max = gd.getNextNumber();
        o.maxWidth = (int) gd.getNextNumber();
        o.crf = (int) gd.getNextNumber();
        o.encoder = gd.getNextChoiceIndex();
        o.ffmpegPath = gd.getNextString().trim();
        o.burnLabel = gd.getNextBoolean();
        if (gd.invalidNumber()) throw new IllegalArgumentException("A numeric field is not a number.");
        o.validate();
        Prefs.set(PREF_FFMPEG, o.ffmpegPath);
        return o;
    }

    void validate() {
        if (!(fps > 0 && fps <= 1000)) throw new IllegalArgumentException("Frame rate must be in (0, 1000] fps.");
        if (step < 1) throw new IllegalArgumentException("Step must be a whole number >= 1.");
        if (first < 1) throw new IllegalArgumentException("First frame must be >= 1.");
        if (last != 0 && last < first) throw new IllegalArgumentException("Last frame is before the first frame.");
        if (maxWidth < 0) throw new IllegalArgumentException("Width cap must be >= 0.");
        if (crf < 0 || crf > 51) throw new IllegalArgumentException("Quality (CRF) must be 0..51.");
        if (scaling == SCALE_EXPLICIT && !(max > min)) {
            throw new IllegalArgumentException("Explicit scaling needs maximum > minimum.");
        }
    }
}
