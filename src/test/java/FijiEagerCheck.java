import ij.ImagePlus;
import org.scijava.Context;
import org.scijava.io.IOPlugin;
import org.scijava.io.IOService;
import org.scijava.plugin.PluginInfo;
import org.scijava.plugin.PluginService;

/**
 * Mirrors imagej-legacy's DefaultLegacyOpener.getEagerHandler(): in a real SciJava context with
 * Fiji's jars, find the eager IOPlugin that claims the path and open it.
 * Usage: java -cp "Fiji/jars/*;Fiji/plugins/*;target/test" FijiEagerCheck file.cine
 */
public class FijiEagerCheck {
    public static void main(String[] args) throws Exception {
        Context ctx = new Context(IOService.class, PluginService.class);
        try {
            IOService io = ctx.getService(IOService.class);
            PluginService ps = ctx.getService(PluginService.class);
            IOPlugin<?> handler = null;
            for (PluginInfo<IOPlugin<?>> info : io.getPlugins()) {
                if (!info.is("eager")) continue;
                IOPlugin<?> p = ps.createInstance(info);
                System.out.println("eager IOPlugin: " + info.getClassName() + " supportsOpen=" + p.supportsOpen(args[0]));
                if (handler == null && p.supportsOpen(args[0])) handler = p;
            }
            if (handler == null) throw new IllegalStateException("no eager handler claims " + args[0]);
            Object o = handler.open(args[0]);
            ImagePlus imp = (ImagePlus) o;
            System.out.println("OPENED via " + handler.getClass().getName() + ": " + imp.getTitle() + " "
                    + imp.getWidth() + "x" + imp.getHeight() + " n=" + imp.getStackSize() + " bit=" + imp.getBitDepth()
                    + " label=[" + imp.getStack().getSliceLabel(1) + "]");
            System.out.println("not-cine claimed? " + handler.supportsOpen("C:/x/image.tif"));
        } finally {
            ctx.dispose();
        }
        System.exit(0);
    }
}
