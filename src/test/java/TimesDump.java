import io.github.cpatb.cine.CineFile;

import java.io.File;

/** Prints relative times (s, 12 decimals) of every n-th image, for comparison with Phantastic. */
public class TimesDump {
    public static void main(String[] args) throws Exception {
        try (CineFile c = new CineFile(new File(args[0]))) {
            double[] t = c.relativeTimes();
            int step = Math.max(1, t.length / 50);
            for (int i = 0; i < t.length; i += step) System.out.println(i + " " + String.format("%.12f", t[i]));
        }
    }
}
