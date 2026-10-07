package io.github.cpatb.cine.mp4;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;

import java.io.File;

/**
 * File &gt; Save As &gt; PowerPoint MP4... Exports the current image or stack. Macro example:
 * {@code run("PowerPoint MP4...", "playback=30 first=1 last=100 every=1 scaling=[Use current display range (what you see)]
 * minimum=0 maximum=4095 width=0 quality=18 encoder=[ffmpeg if found, else built-in (JCodec)] ffmpeg=[] save=[C:/out/x.mp4]");}
 */
public class PowerPoint_MP4_Export implements PlugIn {

    static final String TITLE = "PowerPoint MP4 Export";

    @Override
    public void run(String arg) {
        ImagePlus imp = IJ.getImage();
        if (imp == null) return;
        try {
            int n = Mp4Exporter.movieSlices(imp).length;
            Mp4Options o = Mp4Options.dialog(TITLE, n, imp.getDisplayRangeMin(), imp.getDisplayRangeMax(), false, null);
            if (o == null) return;
            String base = imp.getTitle().replaceFirst("\\.[A-Za-z0-9]{1,5}$", "");
            // The title's first word ("Save") is the macro key for the output path.
            SaveDialog sd = new SaveDialog("Save as PowerPoint MP4", base, ".mp4");
            if (sd.getFileName() == null) return;
            String name = sd.getFileName();
            if (!name.toLowerCase().endsWith(".mp4")) name += ".mp4";
            File out = new File(sd.getDirectory(), name);
            long t0 = System.currentTimeMillis();
            Mp4Exporter.export(imp, o, out);
            IJ.showStatus("PowerPoint MP4 written in " + (System.currentTimeMillis() - t0) / 1000.0 + " s: " + out.getName());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            IJ.log("PowerPoint MP4 export FAILED: " + msg);
            IJ.error(TITLE, msg);
            if (Macro.getOptions() != null) Macro.abort();
        }
    }
}
