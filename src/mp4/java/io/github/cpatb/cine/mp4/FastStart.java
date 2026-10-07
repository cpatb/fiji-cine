package io.github.cpatb.cine.mp4;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the moov box of an MP4 in front of the media data (what ffmpeg's +faststart does), so a
 * player can start without reading to the end of the file. Top-level order becomes
 * ftyp, moov, then every other box in its original order; every chunk offset (stco/co64) is
 * shifted by the moov size.
 */
final class FastStart {

    private FastStart() {
    }

    private static final class Box {
        final String type;
        final long pos, size;

        Box(String type, long pos, long size) {
            this.type = type;
            this.pos = pos;
            this.size = size;
        }
    }

    static void relocate(File in, File out) throws IOException {
        try (RandomAccessFile ri = new RandomAccessFile(in, "r"); FileChannel ci = ri.getChannel()) {
            List<Box> boxes = topLevel(ci);
            Box ftyp = null, moov = null;
            int moovIdx = -1, mdatIdx = -1;
            for (int i = 0; i < boxes.size(); i++) {
                Box b = boxes.get(i);
                if (b.type.equals("ftyp") && ftyp == null) ftyp = b;
                if (b.type.equals("moov")) { moov = b; moovIdx = i; }
                if (b.type.equals("mdat") && mdatIdx < 0) mdatIdx = i;
            }
            if (ftyp == null || moov == null || mdatIdx < 0) throw new IOException("MP4 lacks ftyp/moov/mdat");
            if (boxes.get(0) != ftyp) throw new IOException("MP4 does not start with ftyp");
            if (moov.size > Integer.MAX_VALUE) throw new IOException("moov too large");
            ByteBuffer mv = ByteBuffer.allocate((int) moov.size);
            readFully(ci, mv, moov.pos);
            if (moovIdx > mdatIdx) {
                // every box that ends up after the moov moves forward by moov.size
                patchOffsets(mv, 8, mv.limit(), moov.size);
            }
            try (RandomAccessFile ro = new RandomAccessFile(out, "rw"); FileChannel co = ro.getChannel()) {
                co.truncate(0);
                copy(ci, ftyp.pos, ftyp.size, co);
                mv.rewind();
                while (mv.hasRemaining()) co.write(mv);
                for (Box b : boxes) {
                    if (b == ftyp || b == moov) continue;
                    copy(ci, b.pos, b.size, co);
                }
            }
        }
    }

    private static List<Box> topLevel(FileChannel ch) throws IOException {
        List<Box> out = new ArrayList<>();
        long pos = 0, end = ch.size();
        ByteBuffer h = ByteBuffer.allocate(16);
        while (pos + 8 <= end) {
            h.clear().limit(8);
            readFully(ch, h, pos);
            long size = h.getInt(0) & 0xFFFFFFFFL;
            String type = new String(new byte[]{h.get(4), h.get(5), h.get(6), h.get(7)}, "ISO-8859-1");
            if (size == 1) {
                h.clear().limit(16);
                readFully(ch, h, pos);
                size = h.getLong(8);
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < 8 || pos + size > end) throw new IOException("Bad box '" + type + "' at " + pos);
            out.add(new Box(type, pos, size));
            pos += size;
        }
        return out;
    }

    /** Walk container boxes in [from, to) of buf and shift stco/co64 entries by delta. */
    private static void patchOffsets(ByteBuffer buf, int from, int to, long delta) throws IOException {
        int p = from;
        while (p + 8 <= to) {
            long size = buf.getInt(p) & 0xFFFFFFFFL;
            String type = new String(new byte[]{buf.get(p + 4), buf.get(p + 5), buf.get(p + 6), buf.get(p + 7)}, "ISO-8859-1");
            int hdr = 8;
            if (size == 1) {
                size = buf.getLong(p + 8);
                hdr = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < hdr || p + size > to) throw new IOException("Bad box '" + type + "' inside moov");
            int body = p + hdr, end = (int) (p + size);
            switch (type) {
                case "trak": case "mdia": case "minf": case "stbl": case "edts": case "dinf":
                    patchOffsets(buf, body, end, delta);
                    break;
                case "stco": {
                    int n = buf.getInt(body + 4);
                    for (int i = 0; i < n; i++) {
                        int at = body + 8 + 4 * i;
                        long v = (buf.getInt(at) & 0xFFFFFFFFL) + delta;
                        if (v > 0xFFFFFFFFL) throw new IOException("chunk offset overflows stco after moving moov");
                        buf.putInt(at, (int) v);
                    }
                    break;
                }
                case "co64": {
                    int n = buf.getInt(body + 4);
                    for (int i = 0; i < n; i++) {
                        int at = body + 8 + 8 * i;
                        buf.putLong(at, buf.getLong(at) + delta);
                    }
                    break;
                }
                default:
                    break;
            }
            p = end;
        }
    }

    private static void readFully(FileChannel ch, ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r < 0) throw new IOException("Unexpected end of MP4");
        }
    }

    private static void copy(FileChannel src, long pos, long n, FileChannel dst) throws IOException {
        long done = 0;
        while (done < n) {
            long t = src.transferTo(pos + done, n - done, dst);
            if (t <= 0) throw new IOException("copy stalled");
            done += t;
        }
    }
}
