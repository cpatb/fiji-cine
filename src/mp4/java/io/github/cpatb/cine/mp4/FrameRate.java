package io.github.cpatb.cine.mp4;

/** Playback frame rate as an exact fraction num/den (integer and NTSC x/1.001 rates are exact). */
final class FrameRate {

    final int num, den;

    private FrameRate(int num, int den) {
        long g = gcd(num, den);
        this.num = (int) (num / g);
        this.den = (int) (den / g);
    }

    static FrameRate of(double fps) {
        if (!(fps > 0)) throw new IllegalArgumentException("fps must be > 0");
        long r = Math.round(fps);
        if (Math.abs(fps - r) < 1e-9) return new FrameRate((int) r, 1);
        long ntsc = Math.round(fps * 1.001);
        if (Math.abs(fps - ntsc / 1.001) < 1e-6) return new FrameRate((int) (ntsc * 1000), 1001);
        return new FrameRate((int) Math.round(fps * 1000), 1000);
    }

    double value() {
        return (double) num / den;
    }

    @Override
    public String toString() {
        return num + "/" + den;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }
}
