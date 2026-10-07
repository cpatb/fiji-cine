package io.github.cpatb.cine.ij;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import io.github.cpatb.cine.CineFile;
import io.github.cpatb.cine.Selection;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Builds an ImagePlus from a cine file; shared by the menu command and the drag-and-drop hook. */
public final class CineOpener {

    private static final String[] ALIGN_CHOICES = {
            "Multiples of step (trigger = 0; same frames as PCC)",
            "First image of range, then every step-th"};

    private CineOpener() {
    }

    /**
     * Open {@code path}. With {@code ask}, shows the range/step dialog (macro-recordable);
     * otherwise opens every image as a virtual stack. Returns null if cancelled.
     */
    public static ImagePlus open(String path, boolean ask) throws IOException {
        if (path == null || path.isEmpty()) {
            OpenDialog od = new OpenDialog("Open Phantom cine", null);
            if (od.getFileName() == null) return null;
            path = od.getDirectory() + od.getFileName();
        }
        CineFile cine = new CineFile(new File(path));
        boolean keep = false;
        try {
            int first = cine.firstImageNo;
            int last = cine.firstImageNo + cine.getImageCount() - 1;
            int step = 1;
            Selection.Align align = Selection.Align.TRIGGER;
            boolean virtual = true;
            if (ask) {
                GenericDialog gd = new GenericDialog("Phantom Cine Reader");
                gd.addMessage(cine.file.getName() + "\n" + cine.width + " x " + cine.height + ", "
                        + cine.getImageCount() + " images (" + first + " to " + last + "), "
                        + cine.setup.frameRate + " fps, " + cine.effectiveBits() + "-bit "
                        + cine.packing + (cine.isRawCfa() ? ", raw CFA mosaic" : ""));
                gd.addNumericField("First image", first, 0, 10, "(trigger = 0)");
                gd.addNumericField("Last image", last, 0, 10, "");
                gd.addNumericField("Every", 1, 0, 10, "th image (decimation step N)");
                gd.addChoice("Alignment", ALIGN_CHOICES, ALIGN_CHOICES[0]);
                gd.addCheckbox("Virtual stack (read frames on demand)", true);
                gd.addMessage("Pixel values are the raw sensor numbers: no gain, gamma, tone curve,\n"
                        + "white balance or filtering is applied. Rows are shown top-down.");
                gd.showDialog();
                if (gd.wasCanceled()) return null;
                first = (int) gd.getNextNumber();
                last = (int) gd.getNextNumber();
                step = (int) gd.getNextNumber();
                align = gd.getNextChoiceIndex() == 0 ? Selection.Align.TRIGGER : Selection.Align.FIRST;
                virtual = gd.getNextBoolean();
                if (step < 1 || gd.invalidNumber()) {
                    IJ.error("Phantom Cine Reader", "Step must be a whole number >= 1.");
                    return null;
                }
            }
            int[] idx = Selection.indices(cine, first, last, step, align);
            if (idx.length == 0) {
                IJ.error("Phantom Cine Reader", "No images in the requested range.");
                return null;
            }
            CineStack vs = new CineStack(cine, idx);
            ImagePlus imp;
            String title = cine.file.getName();
            if (virtual) {
                imp = new ImagePlus(title, vs);
                keep = true;  // the virtual stack keeps the file open until its window closes
                final ImagePlus owner = imp;
                ImagePlus.addImageListener(new ij.ImageListener() {
                    @Override public void imageOpened(ImagePlus i) { }
                    @Override public void imageUpdated(ImagePlus i) { }
                    @Override public void imageClosed(ImagePlus i) {
                        if (i != owner) return;
                        ImagePlus.removeImageListener(this);
                        try { cine.close(); } catch (IOException ignored) { /* nothing left to read */ }
                    }
                });
            } else {
                long bytes = (long) idx.length * cine.width * cine.height * (vs.getBitDepth() == 8 ? 1 : vs.getBitDepth() == 16 ? 2 : 4);
                if (bytes > IJ.maxMemory() - IJ.currentMemory()) {
                    IJ.error("Phantom Cine Reader", "Not enough memory to load " + (bytes >> 20)
                            + " MB; use a virtual stack, a step, or a smaller range.");
                    return null;
                }
                ImageStack st = new ImageStack(cine.width, cine.height);
                for (int n = 1; n <= idx.length; n++) {
                    IJ.showProgress(n, idx.length);
                    ImageProcessor ip = CineStack.decode(cine, idx[n - 1]);
                    st.addSlice(vs.getSliceLabel(n), ip);
                }
                imp = new ImagePlus(title, st);
            }
            decorate(imp, cine, idx, step, align);
            return imp;
        } finally {
            if (!keep) cine.close();
        }
    }

    /** Calibration, metadata and display range. Never changes pixel values. */
    static void decorate(ImagePlus imp, CineFile cine, int[] idx, int step, Selection.Align align) throws IOException {
        imp.setDimensions(1, 1, idx.length);
        Calibration cal = imp.getCalibration();
        double[] t = cine.relativeTimes();
        if (idx.length > 1) {
            cal.frameInterval = (t[idx[idx.length - 1]] - t[idx[0]]) / (idx.length - 1);
        } else if (cine.setup.frameRate > 0) {
            cal.frameInterval = 1.0 / cine.setup.frameRate;
        }
        cal.setTimeUnit("s");
        cal.fps = 30;  // playback speed for ImageJ's animator only
        // No spatial calibration: biXPelsPerMeter describes the sensor, not the object plane.

        StringBuilder info = new StringBuilder("Phantom cine (read by Cine Reader, raw values)\n");
        for (Map.Entry<String, String> e : cine.describe().entrySet()) {
            info.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        info.append("Opened images = ").append(idx.length).append(", step ").append(step)
                .append(", alignment ").append(align).append('\n');
        info.append("Opened image numbers = ").append(cine.imageNumber(idx[0])).append(" .. ")
                .append(cine.imageNumber(idx[idx.length - 1])).append('\n');
        boolean stamped = cine.hasCompleteTimes();
        info.append("Time source = ").append(stamped ? "per-image time stamps (tag 1002)"
                : "image number / SETUP frame rate (NO complete time stamps; wrong for decimated files)").append('\n');
        if (!stamped) {
            IJ.log("Cine Reader: " + cine.file.getName() + " has no complete per-image time stamps; times use "
                    + "image number / " + cine.setup.frameRate + " fps, which is wrong if the file was decimated.");
        }
        imp.setProperty("Info", info.toString());
        imp.setProp("cine.frameRate", cine.setup.frameRate);
        imp.setProp("cine.firstImageNumber", cine.imageNumber(idx[0]));
        imp.setProp("cine.step", step);
        imp.setProp("cine.exposureNs", cine.setup.shutterNs);
        imp.setProp("cine.realBpp", cine.effectiveBits());

        FileInfo fi = new FileInfo();
        fi.fileFormat = FileInfo.UNKNOWN;
        fi.directory = cine.file.getParent() + File.separator;
        fi.fileName = cine.file.getName();
        fi.width = cine.width;
        fi.height = cine.height;
        fi.nImages = idx.length;
        fi.frameInterval = cal.frameInterval;
        imp.setFileInfo(fi);
        // Display range only (pixels untouched): fit to the first opened frame.
        imp.resetDisplayRange();
    }
}
