package io.github.cpatb.cine.mp4;

import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.codecs.h264.encode.RateControl;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.codecs.h264.io.model.VUIParameters;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.VideoEncoder;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.muxer.MP4Muxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Built-in fallback encoder: JCodec 0.2.5 (pure Java, BSD-2). JCodec's H.264 encoder writes
 * Constrained Baseline profile (I and P frames, CAVLC, no B-frames) at a fixed QP. This class
 * converts frames to limited-range BT.709 YUV 4:2:0 itself, adds a VUI that says so, sets the
 * level from the frame size and rate, muxes to MP4 in a temporary file next to the output and then
 * moves the moov box to the front ({@link FastStart}).
 */
final class JCodecSink implements VideoSink {

    private final File out, tmp;
    private final int w, h;
    private final FrameRate rate;
    private final int qp;
    private final FileChannelWrapper ch;
    private final MP4Muxer muxer;
    private final MuxerTrack track;
    private final H264Encoder enc;
    private long frameNo;
    private boolean closed;

    JCodecSink(File out, int w, int h, FrameRate rate, int qp) throws IOException {
        if ((w & 1) != 0 || (h & 1) != 0) throw new IllegalArgumentException("JCodec needs even sizes");
        this.out = out;
        this.tmp = new File(out.getAbsolutePath() + ".jcodec-tmp");
        this.w = w;
        this.h = h;
        this.rate = rate;
        this.qp = qp;
        int level = Math.max(30, H264Levels.minimumLevel(w, h, rate.value()));
        enc = new TaggedEncoder(new FixedQp(qp), level < 0 ? 52 : level);
        enc.setKeyInterval((int) Math.max(1, Math.round(rate.value())));
        ch = NIOUtils.writableChannel(tmp);
        muxer = MP4Muxer.createMP4MuxerToChannel(ch);
        track = muxer.addVideoTrack(Codec.H264, VideoCodecMeta.createSimpleVideoCodecMeta(new Size(w, h), ColorSpace.YUV420));
    }

    @Override
    public void writeGray(byte[] gray) throws IOException {
        Picture pic = Picture.create(w, h, ColorSpace.YUV420J);
        byte[] y = pic.getPlaneData(0);
        for (int i = 0; i < y.length; i++) y[i] = (byte) (Yuv.limitedY(gray[i] & 0xFF) - 128);
        // chroma planes are created zero-filled, i.e. 128 = neutral
        encode(pic);
    }

    @Override
    public void writeRgb(byte[] rgb) throws IOException {
        Picture pic = Picture.create(w, h, ColorSpace.YUV420J);
        Yuv.rgbToYuv420(rgb, w, h, pic.getPlaneData(0), pic.getPlaneData(1), pic.getPlaneData(2));
        encode(pic);
    }

    private void encode(Picture pic) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(w * h * 2 + (1 << 16));
        VideoEncoder.EncodedFrame ef = enc.encodeFrame(pic, buf);
        Packet pkt = Packet.createPacket(ef.getData(), frameNo * rate.den, rate.num, rate.den, frameNo,
                ef.isKeyFrame() ? Packet.FrameType.KEY : Packet.FrameType.INTER, null);
        track.addFrame(pkt);
        frameNo++;
    }

    @Override
    public void finish() throws IOException {
        try {
            muxer.finish();
        } finally {
            ch.close();
            closed = true;
        }
        try {
            FastStart.relocate(tmp, out);
        } catch (IOException e) {
            out.delete();
            throw e;
        } finally {
            tmp.delete();
        }
    }

    @Override
    public void abort() {
        if (!closed) {
            try {
                ch.close();
            } catch (IOException ignored) {
                // nothing to save
            }
            closed = true;
        }
        tmp.delete();
        out.delete();
    }

    @Override
    public String describe() {
        return "built-in JCodec 0.2.5 (Constrained Baseline, fixed QP " + qp + ")";
    }

    /** Constant QP for I and P slices; steps QP up only if a macroblock would overflow the buffer. */
    private static final class FixedQp implements RateControl {
        private final int qp;
        private int bitsPerMb;

        FixedQp(int qp) {
            this.qp = Math.max(0, Math.min(51, qp));
        }

        @Override
        public int startPicture(Size sz, int maxSize, SliceType sliceType) {
            int mbs = ((sz.getWidth() + 15) >> 4) * ((sz.getHeight() + 15) >> 4);
            bitsPerMb = (int) Math.min(Integer.MAX_VALUE, ((long) maxSize << 3) / mbs);
            return qp;
        }

        @Override
        public int initialQpDelta() {
            return 0;
        }

        @Override
        public int accept(int bits) {
            return bits >= bitsPerMb ? 1 : 0;
        }
    }

    /** JCodec's encoder with a correct level, constrained-baseline flags and a colour VUI. */
    private static final class TaggedEncoder extends H264Encoder {
        private final int level;

        TaggedEncoder(RateControl rc, int level) {
            super(rc);
            this.level = level;
        }

        @Override
        public SeqParameterSet initSPS(Size sz) {
            SeqParameterSet sps = super.initSPS(sz);
            sps.levelIdc = level;
            sps.constraintSet0Flag = true;  // conforms to Baseline
            sps.constraintSet1Flag = true;  // and to Main: i.e. Constrained Baseline
            VUIParameters v = new VUIParameters();
            v.videoSignalTypePresentFlag = true;
            v.videoFormat = 5;              // unspecified
            v.videoFullRangeFlag = false;   // limited ("TV") range
            v.colourDescriptionPresentFlag = true;
            v.colourPrimaries = 1;          // BT.709
            v.transferCharacteristics = 1;  // BT.709
            v.matrixCoefficients = 1;       // BT.709
            sps.vuiParams = v;
            return sps;
        }
    }
}
