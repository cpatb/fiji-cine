import io.github.cpatb.cine.CineFile;

import java.io.File;
import java.security.MessageDigest;

/**
 * Prints an MD5 of every decoded frame (little-endian 16-bit, or bytes for 8-bit / RGB) so the
 * Java decoder can be compared frame-by-frame with an independent decoder.
 * Usage: java -cp ... CineDump file.cine [maxFrames]
 */
public class CineDump {
    public static void main(String[] args) throws Exception {
        try (CineFile c = new CineFile(new File(args[0]))) {
            int n = c.getImageCount();
            int max = args.length > 1 ? Integer.parseInt(args[1]) : n;
            int step = Math.max(1, n / Math.max(1, max));
            System.out.println("meta " + c.width + "x" + c.height + " n=" + n + " first=" + c.firstImageNo
                    + " packing=" + c.packing + " fps=" + c.setup.frameRate + " bits=" + c.effectiveBits()
                    + " black=" + c.setup.blackLevel + " white=" + c.setup.whiteLevel);
            for (int i = 0; i < n; i += step) {
                Object px = c.readFrame(i);
                MessageDigest md = MessageDigest.getInstance("MD5");
                if (px instanceof short[]) {
                    short[] s = (short[]) px;
                    byte[] b = new byte[2 * s.length];
                    for (int k = 0; k < s.length; k++) {
                        b[2 * k] = (byte) s[k];
                        b[2 * k + 1] = (byte) (s[k] >> 8);
                    }
                    md.update(b);
                } else {
                    md.update((byte[]) px);
                }
                StringBuilder hex = new StringBuilder();
                for (byte x : md.digest()) hex.append(String.format("%02x", x));
                System.out.println(i + " " + hex);
            }
        }
    }
}
