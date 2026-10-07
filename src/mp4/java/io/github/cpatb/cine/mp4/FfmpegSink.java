package io.github.cpatb.cine.mp4;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pipes raw frames to an external ffmpeg (libx264) through stdin: no temporary image files.
 * ffmpeg's console output is drained on its own thread so a full pipe cannot deadlock.
 *
 * <p>Output: H.264 High profile, yuv420p, limited ("TV") range tagged BT.709, constant frame rate,
 * no B-frames, keyframe about every second, moov atom first (+faststart).
 */
final class FfmpegSink implements VideoSink {

    private static final int MAX_LOG = 64 * 1024;

    private final File out;
    private final List<String> cmd;
    private final Process proc;
    private final OutputStream stdin;
    private final Thread drain;
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private final int frameBytes;

    FfmpegSink(String exe, File out, int w, int h, boolean rgb, FrameRate rate, int crf) throws IOException {
        this.out = out;
        this.frameBytes = w * h * (rgb ? 3 : 1);
        int gop = (int) Math.max(1, Math.round(rate.value()));
        cmd = new ArrayList<>(Arrays.asList(exe, "-hide_banner", "-nostdin", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", rgb ? "rgb24" : "gray", "-s", w + "x" + h,
                "-framerate", rate.toString(), "-i", "-",
                "-an",
                // gray/rgb (full range) -> limited-range BT.709 YUV 4:2:0; no resizing happens here
                "-vf", "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p",
                "-c:v", "libx264", "-preset", "medium", "-crf", String.valueOf(crf),
                "-profile:v", "high", "-pix_fmt", "yuv420p", "-bf", "0", "-g", String.valueOf(gop),
                "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
                "-r", rate.toString(), "-movflags", "+faststart",
                out.getAbsolutePath()));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        proc = pb.start();
        stdin = new BufferedOutputStream(proc.getOutputStream(), 1 << 20);
        drain = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[8192];
                try (InputStream in = proc.getInputStream()) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        synchronized (log) {
                            if (log.size() < MAX_LOG) log.write(buf, 0, Math.min(r, MAX_LOG - log.size()));
                        }
                    }
                } catch (IOException ignored) {
                    // process ended
                }
            }
        }, "ffmpeg-output");
        drain.setDaemon(true);
        drain.start();
    }

    @Override
    public void writeGray(byte[] gray) throws IOException {
        write(gray);
    }

    @Override
    public void writeRgb(byte[] rgb) throws IOException {
        write(rgb);
    }

    private void write(byte[] b) throws IOException {
        if (b.length != frameBytes) throw new IllegalArgumentException("frame has " + b.length + " bytes, expected " + frameBytes);
        try {
            stdin.write(b);
        } catch (IOException e) {
            throw new IOException("ffmpeg stopped accepting frames:\n" + waitAndLog(), e);
        }
    }

    @Override
    public void finish() throws IOException {
        try {
            stdin.close();
        } catch (IOException e) {
            throw new IOException("ffmpeg failed while finishing:\n" + waitAndLog(), e);
        }
        int rc = waitFor();
        if (rc != 0) {
            out.delete();
            throw new IOException("ffmpeg exited with code " + rc + ":\n" + logText() + "\nCommand: " + String.join(" ", cmd));
        }
    }

    private String waitAndLog() {
        waitFor();
        return logText();
    }

    private int waitFor() {
        try {
            if (!proc.waitFor(10, TimeUnit.MINUTES)) {
                proc.destroy();
                return -1;
            }
            drain.join(5000);
            return proc.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroy();
            return -1;
        }
    }

    private String logText() {
        synchronized (log) {
            String s = new String(log.toByteArray()).trim();
            return s.isEmpty() ? "(ffmpeg printed nothing)" : s;
        }
    }

    @Override
    public void abort() {
        try {
            stdin.close();
        } catch (IOException ignored) {
            // already broken
        }
        proc.destroy();
        try {
            proc.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.delete();
    }

    @Override
    public String describe() {
        return "ffmpeg/libx264 (" + cmd.get(0) + ")";
    }

    // ------------------------------------------------------------------ discovery

    /**
     * The ffmpeg to use: the explicit path if it names an existing file, otherwise the first
     * ffmpeg(.exe) on PATH. Returns null if none is found or it lacks libx264 (reason in why[0]).
     */
    static String find(String explicit, String[] why) {
        List<String> candidates = new ArrayList<>();
        if (explicit != null && !explicit.isEmpty()) {
            File f = new File(explicit);
            if (f.isDirectory()) f = new File(f, exeName());
            if (f.isFile()) candidates.add(f.getAbsolutePath());
            else why[0] = "ffmpeg path not found: " + explicit;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                File f = new File(dir.replace("\"", ""), exeName());
                if (f.isFile()) candidates.add(f.getAbsolutePath());
            }
        }
        if (candidates.isEmpty()) {
            if (why[0] == null) why[0] = "no ffmpeg on PATH";
            return null;
        }
        for (String c : candidates) {
            if (hasLibx264(c)) return c;
            why[0] = "ffmpeg at " + c + " has no libx264 encoder";
        }
        return null;
    }

    private static String exeName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";
    }

    private static boolean hasLibx264(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-hide_banner", "-encoders").redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try (InputStream in = p.getInputStream()) {
                int r;
                while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroy();
                return false;
            }
            return p.exitValue() == 0 && new String(b.toByteArray()).contains("libx264");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
