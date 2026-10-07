package io.github.cpatb.cine.ij;

import ij.IJ;
import ij.ImagePlus;
import ij.plugin.PlugIn;

/**
 * File &gt; Import &gt; Phantom Cine... Opens a Vision Research .cine as a (virtual) stack of raw
 * sensor values. Argument: optional file path.
 */
public class Cine_Reader implements PlugIn {

    @Override
    public void run(String arg) {
        try {
            ImagePlus imp = CineOpener.open(arg, true);
            if (imp != null) imp.show();
        } catch (Exception e) {
            IJ.error("Phantom Cine Reader", "Could not open cine:\n" + e.getMessage());
            if (IJ.debugMode) IJ.handleException(e);
        }
    }
}
