package io.github.cpatb.cine.mp4;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.plugin.PlugIn;
import io.github.cpatb.cine.ij.CineOpener;
import io.github.cpatb.cine.ij.CineStack;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugins &gt; Phantom &gt; Batch convert to PowerPoint MP4... Converts .cine and .tif/.tiff files
 * to MP4 in a folder the user picks; one MP4 per input, named after it. A .cine is opened as a
 * virtual stack of every stored image (CineOpener, whole file); a TIFF as a virtual stack when
 * ImageJ can, otherwise in memory. "Current display range" means the range ImageJ sets on opening
 * (for a cine: min..max of its first image). Macro keys as for the single export, plus
 * input=[a.cine;b.tif] output=[folder] overwrite.
 */
public class Batch_PowerPoint_MP4 implements PlugIn {

    static final String TITLE = "Batch convert to PowerPoint MP4";

    @Override
    public void run(String arg) {
        String files = "";
        if (Macro.getOptions() == null) {
            JFileChooser fc = new JFileChooser(IJ.getDirectory("current"));
            fc.setMultiSelectionEnabled(true);
            fc.setDialogTitle("Choose .cine / .tif files");
            fc.setFileFilter(new FileNameExtensionFilter("Phantom cine and TIFF stacks", "cine", "tif", "tiff"));
            if (fc.showOpenDialog(IJ.getInstance()) != JFileChooser.APPROVE_OPTION) return;
            StringBuilder sb = new StringBuilder();
            for (File f : fc.getSelectedFiles()) sb.append(sb.length() > 0 ? ";" : "").append(f.getAbsolutePath());
            files = sb.toString();
        }
        Mp4Options o;
        try {
            o = Mp4Options.dialog(TITLE, 0, 0, 65535, true, files);
        } catch (IllegalArgumentException e) {
            IJ.error(TITLE, e.getMessage());
            return;
        }
        if (o == null) return;
        if (o.outputDir.isEmpty()) {
            IJ.error(TITLE, "Choose an output folder.");
            return;
        }
        File outDir = new File(o.outputDir);
        if (!outDir.isDirectory()) {
            IJ.error(TITLE, "Output folder does not exist:\n" + outDir);
            return;
        }
        List<File> inputs = new ArrayList<>();
        for (String s : o.inputFiles.split(";")) if (!s.trim().isEmpty()) inputs.add(new File(s.trim()));
        int done = 0, skipped = 0, failed = 0;
        for (int i = 0; i < inputs.size(); i++) {
            File in = inputs.get(i);
            IJ.showStatus("MP4 " + (i + 1) + "/" + inputs.size() + ": " + in.getName());
            String base = in.getName().replaceFirst("\\.[^.]*$", "");
            File out = new File(outDir, base + ".mp4");
            if (out.exists() && !o.overwrite) {
                IJ.log("PowerPoint MP4: skipped " + in + " (" + out.getName() + " exists; tick Overwrite)");
                skipped++;
                continue;
            }
            ImagePlus imp = null;
            try {
                imp = open(in);
                Mp4Exporter.export(imp, o, out);
                done++;
            } catch (Exception e) {
                failed++;
                IJ.log("PowerPoint MP4 FAILED for " + in + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                close(imp);
            }
            if (IJ.escapePressed()) {
                IJ.log("PowerPoint MP4 batch: stopped (Esc)");
                break;
            }
        }
        IJ.log("PowerPoint MP4 batch: " + done + " written, " + skipped + " skipped, " + failed + " failed");
        IJ.showStatus("PowerPoint MP4 batch: " + done + " written, " + failed + " failed");
    }

    private static ImagePlus open(File f) throws Exception {
        String name = f.getName().toLowerCase();
        if (!f.isFile()) throw new java.io.FileNotFoundException(f.getAbsolutePath());
        if (name.endsWith(".cine")) {
            ImagePlus imp = CineOpener.open(f.getAbsolutePath(), false);
            if (imp == null) throw new java.io.IOException("could not open cine");
            return imp;
        }
        if (name.endsWith(".tif") || name.endsWith(".tiff")) {
            ImagePlus imp = IJ.openVirtual(f.getAbsolutePath());
            if (imp == null || imp.getStackSize() < 1) imp = IJ.openImage(f.getAbsolutePath());
            if (imp == null) throw new java.io.IOException("ImageJ could not open this TIFF");
            return imp;
        }
        throw new IllegalArgumentException("not a .cine or .tif/.tiff file");
    }

    private static void close(ImagePlus imp) {
        if (imp == null) return;
        try {
            if (imp.getStack() instanceof CineStack) ((CineStack) imp.getStack()).cine().close();
        } catch (Exception ignored) {
            // file already closed
        }
        imp.flush();
    }
}
