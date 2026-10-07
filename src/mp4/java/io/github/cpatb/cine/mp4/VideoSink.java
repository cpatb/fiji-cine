package io.github.cpatb.cine.mp4;

import java.io.IOException;

/** An H.264/MP4 writer fed one frame at a time (frames already at the final, even size). */
interface VideoSink {

    /** One 8-bit gray frame, width*height bytes, rows top-down. */
    void writeGray(byte[] gray) throws IOException;

    /** One RGB frame, width*height*3 bytes (R, G, B interleaved), rows top-down. */
    void writeRgb(byte[] rgb) throws IOException;

    /** Complete the file; throws with the encoder's message on failure. */
    void finish() throws IOException;

    /** Stop and delete the partial output. Safe to call after a failure. */
    void abort();

    /** Short description for the log. */
    String describe();
}
