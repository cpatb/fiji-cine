package io.github.cpatb.cine.fiji;

import ij.ImagePlus;
import io.github.cpatb.cine.ij.CineOpener;

import java.io.IOException;

import org.scijava.io.AbstractIOPlugin;
import org.scijava.io.IOPlugin;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.plugin.Attr;
import org.scijava.plugin.Plugin;

/**
 * Fiji: makes File &gt; Open and drag-and-drop open .cine files.
 *
 * <p>Fiji (imagej-legacy) asks "eager" SciJava I/O plugins before the classic ImageJ opener,
 * whose own fallback (Bio-Formats) cannot read cines. The whole file opens as a virtual stack;
 * use File &gt; Import &gt; Phantom Cine... to choose a range or a decimation step.
 * In plain ImageJ 1.x this class is never loaded.
 */
@Plugin(type = IOPlugin.class, attrs = @Attr(name = "eager"))
public class CineIOPlugin extends AbstractIOPlugin<ImagePlus> {

    @Override
    public Class<ImagePlus> getDataType() {
        return ImagePlus.class;
    }

    private static boolean isCine(String path) {
        return path != null && path.toLowerCase().endsWith(".cine");
    }

    @Override
    public boolean supportsOpen(String source) {
        return isCine(source);
    }

    @Override
    public boolean supportsOpen(Location source) {
        return source instanceof FileLocation && isCine(((FileLocation) source).getFile().getPath());
    }

    @Override
    public ImagePlus open(String source) throws IOException {
        return CineOpener.open(source, false);
    }

    @Override
    public ImagePlus open(Location source) throws IOException {
        if (!(source instanceof FileLocation)) throw new IOException("Only local .cine files are supported");
        return open(((FileLocation) source).getFile().getPath());
    }
}
