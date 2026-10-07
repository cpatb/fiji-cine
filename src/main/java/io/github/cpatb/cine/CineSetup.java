package io.github.cpatb.cine;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The subset of the cine SETUP block that matters for reading and describing a recording.
 *
 * <p>Offsets are relative to the start of SETUP (header field {@code OffSetup}) and follow the
 * Vision Research "Cine File Format" layout. SETUP has grown over the years; the block's own
 * {@code Length} field (offset 142) says how many bytes are present, and any field that lies
 * beyond it is reported as absent rather than read from whatever follows. On every file on the
 * test drive {@code Length} = 10412, which ends before {@code CreatedBy}/{@code RecBPP}.
 */
public final class CineSetup {

    /** Bytes of SETUP actually present in the file. */
    public final int length;

    public final int frameRate;          // u32 @ 768, frames per second (integer)
    public final int postTrigger;        // u32 @ 780, frames recorded after the trigger
    public final int cameraVersion;      // u32 @ 792
    public final int firmwareVersion;    // u32 @ 796
    public final int softwareVersion;    // u32 @ 800
    public final int cfa;                // u32 @ 808, colour filter array code (0 = mono)
    public final int realBpp;            // u32 @ 896, true bit depth of the sensor data
    public final int serial;             // u32 @ 743
    public final int shutterNs;          // u32 @ 1568, exposure in ns
    public final int edrShutterNs;       // u32 @ 1572
    public final int frameDelayNs;       // u32 @ 1576
    public final int imPosXAcq, imPosYAcq, imWidthAcq, imHeightAcq; // @ 1580..1592
    public final String description;     // char[4096] @ 1596
    public final boolean enableColor;    // u32 @ 788
    public final boolean flipH, flipV;   // u32 @ 756, 760 (display hints)
    public final int rotate;             // i32 @ 884 (display hint)
    public final int blackLevel;         // i32 @ 5732
    public final int whiteLevel;         // i32 @ 5736
    public final String lensDescription; // char[256] @ 5740
    public final float lensAperture, lensFocusDistance, lensFocalLength;
    // Image-processing parameters. These are *display* settings stored with the file; the raw
    // pixel data are never altered by them. They are exposed so that a user can see exactly what
    // PCC would apply on export.
    public final float fOffset, fGain, fSaturation, fHue, fGamma, fGammaR, fGammaB, fFlare;
    public final float fPedestalR, fPedestalG, fPedestalB, fChroma;
    public final String toneLabel;
    public final float[] tonePoints;     // (x, y) pairs, TonePoints of them
    public final boolean enableMatrices;
    public final float[] cmUser;         // 3x3
    public final float fGain16to8;
    public final String cineName;
    public final float fGainR, fGainG, fGainB;
    public final float[] cmCalib;        // 3x3
    public final float fWbTemp, fWbCc;
    public final String calibrationInfo, opticalFilter;

    /** Every field above as display strings, in file order (for ImageJ "Info"). */
    public final Map<String, String> asMap = new LinkedHashMap<>();

    CineSetup(ByteBuffer raw) {
        ByteBuffer b = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        length = Math.min(b.capacity(), u16(b, 142));
        frameRate = i32(b, 768);
        postTrigger = i32(b, 780);
        enableColor = i32(b, 788) != 0;
        cameraVersion = i32(b, 792);
        firmwareVersion = i32(b, 796);
        softwareVersion = i32(b, 800);
        cfa = i32(b, 808);
        realBpp = i32(b, 896);
        serial = i32(b, 743);
        flipH = i32(b, 756) != 0;
        flipV = i32(b, 760) != 0;
        rotate = i32(b, 884);
        shutterNs = i32(b, 1568);
        edrShutterNs = i32(b, 1572);
        frameDelayNs = i32(b, 1576);
        imPosXAcq = i32(b, 1580);
        imPosYAcq = i32(b, 1584);
        imWidthAcq = i32(b, 1588);
        imHeightAcq = i32(b, 1592);
        description = str(b, 1596, 4096);
        blackLevel = i32(b, 5732);
        whiteLevel = i32(b, 5736);
        lensDescription = str(b, 5740, 256);
        lensAperture = f32(b, 5996);
        lensFocusDistance = f32(b, 6000);
        lensFocalLength = f32(b, 6004);
        fOffset = f32(b, 6008);
        fGain = f32(b, 6012);
        fSaturation = f32(b, 6016);
        fHue = f32(b, 6020);
        fGamma = f32(b, 6024);
        fGammaR = f32(b, 6028);
        fGammaB = f32(b, 6032);
        fFlare = f32(b, 6036);
        fPedestalR = f32(b, 6040);
        fPedestalG = f32(b, 6044);
        fPedestalB = f32(b, 6048);
        fChroma = f32(b, 6052);
        toneLabel = str(b, 6056, 256);
        int nTone = i32(b, 6312);
        nTone = Math.max(0, Math.min(nTone, 32));
        tonePoints = new float[2 * nTone];
        for (int i = 0; i < 2 * nTone; i++) tonePoints[i] = f32(b, 6316 + 4 * i);
        enableMatrices = i32(b, 6828) != 0;
        cmUser = f32s(b, 6832, 9);
        fGain16to8 = f32(b, 6900);
        cineName = str(b, 6984, 256);
        fGainR = f32(b, 7240);
        fGainG = f32(b, 7244);
        fGainB = f32(b, 7248);
        cmCalib = f32s(b, 7252, 9);
        fWbTemp = f32(b, 7288);
        fWbCc = f32(b, 7292);
        calibrationInfo = str(b, 7296, 1024);
        opticalFilter = str(b, 8320, 1024);

        put("SETUP length (bytes present)", length);
        put("Frame rate (fps)", frameRate);
        put("Exposure (ns)", shutterNs);
        put("EDR exposure (ns)", edrShutterNs);
        put("Frame delay (ns)", frameDelayNs);
        put("Post-trigger frames", postTrigger);
        put("Real bit depth", realBpp);
        put("Black level", blackLevel);
        put("White level", whiteLevel);
        put("CFA code (0 = mono)", cfa);
        put("Colour enabled", enableColor);
        put("Camera serial", serial);
        put("Camera version", cameraVersion);
        put("Firmware version", firmwareVersion);
        put("Software version", softwareVersion);
        put("Acquisition window (x, y, w, h)",
                imPosXAcq + ", " + imPosYAcq + ", " + imWidthAcq + ", " + imHeightAcq);
        put("Lens", lensDescription);
        put("Lens aperture", lensAperture);
        put("Lens focus distance", lensFocusDistance);
        put("Lens focal length", lensFocalLength);
        put("Description", description);
        put("Cine name", cineName);
        put("Calibration info", calibrationInfo);
        put("Optical filter", opticalFilter);
        put("[display] flip H / flip V / rotate", flipH + " / " + flipV + " / " + rotate);
        put("[display] fOffset", fOffset);
        put("[display] fGain", fGain);
        put("[display] fGamma (R, B)", fGamma + " (" + fGammaR + ", " + fGammaB + ")");
        put("[display] fFlare", fFlare);
        put("[display] fSaturation", fSaturation);
        put("[display] fHue", fHue);
        put("[display] fChroma", fChroma);
        put("[display] pedestal R/G/B", fPedestalR + " / " + fPedestalG + " / " + fPedestalB);
        put("[display] tone curve label", toneLabel);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tonePoints.length; i += 2) {
            sb.append('(').append(tonePoints[i]).append(", ").append(tonePoints[i + 1]).append(") ");
        }
        put("[display] tone curve points (x, y)", sb.toString().trim());
        put("[display] fGain16_8", fGain16to8);
        put("[display] WB gain R/G/B", fGainR + " / " + fGainG + " / " + fGainB);
        put("[display] WB temp / Cc", fWbTemp + " / " + fWbCc);
        put("[display] matrices enabled", enableMatrices);
    }

    private void put(String k, Object v) {
        asMap.put(k, String.valueOf(v));
    }

    // ---- guarded little-endian accessors: a field beyond `length` reads as absent ----

    private boolean has(int off, int size) {
        return off + size <= length;
    }

    private int u16(ByteBuffer b, int off) {
        return off + 2 <= b.capacity() ? (b.getShort(off) & 0xFFFF) : 0;
    }

    private int i32(ByteBuffer b, int off) {
        return has(off, 4) ? b.getInt(off) : 0;
    }

    private float f32(ByteBuffer b, int off) {
        return has(off, 4) ? b.getFloat(off) : Float.NaN;
    }

    private float[] f32s(ByteBuffer b, int off, int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = f32(b, off + 4 * i);
        return out;
    }

    private String str(ByteBuffer b, int off, int n) {
        if (!has(off, 1)) return "";
        int m = Math.min(n, length - off);
        byte[] s = new byte[m];
        for (int i = 0; i < m; i++) s[i] = b.get(off + i);
        int end = 0;
        while (end < m && s[end] != 0) end++;
        // Cine strings are 8-bit; ISO-8859-1 maps every byte and never throws.
        return new String(s, 0, end, StandardCharsets.ISO_8859_1).trim();
    }
}
