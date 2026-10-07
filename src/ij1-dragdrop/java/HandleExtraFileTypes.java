import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;
import io.github.cpatb.cine.ij.CineOpener;

/**
 * Drag-and-drop / File &gt; Open support for .cine in <b>plain ImageJ 1.x only</b>.
 *
 * <p>ImageJ 1.x hands any file type it does not recognise to a plugin class named
 * {@code HandleExtraFileTypes}. Plain ImageJ ships none, so this one can claim .cine files.
 * Do NOT install this jar in Fiji: Fiji ships its own HandleExtraFileTypes and the two would
 * clash. (In Fiji, use File &gt; Import &gt; Phantom Cine..., or the SciJava opener in the main jar.)
 */
public class HandleExtraFileTypes extends ImagePlus implements PlugIn {

    @Override
    public void run(String path) {
        if (path == null || !path.toLowerCase().endsWith(".cine")) return;  // width stays 0: not ours
        try {
            ImagePlus imp = CineOpener.open(path, false);
            if (imp == null) return;
            setStack(imp.getTitle(), imp.getStack());
            setCalibration(imp.getCalibration());
            setProperty("Info", imp.getProperty("Info"));
            setFileInfo(imp.getOriginalFileInfo());
            for (String key : imp.getPropertiesAsArray() == null ? new String[0] : propKeys(imp)) {
                setProp(key, imp.getProp(key));
            }
            setDisplayRange(imp.getDisplayRangeMin(), imp.getDisplayRangeMax());
        } catch (Exception e) {
            IJ.error("Phantom Cine Reader", "Could not open cine:\n" + e.getMessage());
            width = -1;  // tell the Opener we handled it, so it does not add a second error
        }
    }

    private static String[] propKeys(ImagePlus imp) {
        String[] kv = imp.getPropertiesAsArray();
        String[] keys = new String[kv.length / 2];
        for (int i = 0; i < keys.length; i++) keys[i] = kv[2 * i];
        return keys;
    }
}
