# Phantom Cine tools for ImageJ / Fiji

Open Vision Research Phantom `.cine` high-speed videos in ImageJ and Fiji, and save any stack
as an MP4 that PowerPoint plays. Pure Java, no vendor DLLs, Java 8+.

| Jar | What it adds |
|---|---|
| `Cine_Reader-<v>.jar` | *File > Import > Phantom Cine...* (range, every N-th image, virtual stack). In Fiji also *File > Open* and drag-and-drop of `.cine`. |
| `PowerPoint_MP4_Export-<v>.jar` | *File > Save As > PowerPoint MP4...* and *Plugins > Phantom > Batch convert to PowerPoint MP4...* (cine and TIFF). See [docs/MP4_EXPORT.md](docs/MP4_EXPORT.md). Needs `Cine_Reader` for cine input. |
| `Cine_DragDrop_ImageJ1-<v>.jar` | Drag-and-drop / *File > Open* of `.cine` in **plain ImageJ 1.x only**. Do not install it in Fiji (Fiji has its own handler; the main jar covers Fiji). |

> Independent project, not affiliated with Vision Research / AMETEK. "Phantom" is their trademark.

## Install

Download the jars from the [latest release](https://github.com/cpatb/fiji-cine/releases/latest), copy them into
the `plugins` folder of ImageJ or Fiji and restart. Java 8 or newer; Windows, macOS and Linux.

## What you get

* **Raw sensor values.** No gain, gamma, tone curve, white balance or filter is applied (those
  are display settings stored in the file; they are listed under *Image > Show Info*). Display
  range only is fitted to the first frame; the pixels are untouched.
* **Correct orientation.** Unpacked cines are stored bottom-up and packed ones top-down; both open
  top-down, as PCC shows them.
* **Camera image numbers and true times.** Slice labels give the camera image number (trigger = 0)
  and the time from the trigger, taken from the per-image time stamps. The frame interval is set
  from those time stamps, so it is right even for decimated files (PCC leaves the original frame
  rate in a decimated file).
* **Explicit decimation.** "Every N-th image" either keeps multiples of N from the trigger (the
  same images PCC keeps) or starts at the first image of the range.
* Formats: 8- and 16-bit mono, 10-bit packed (linearised to 12-bit with the table from the Cine
  File Format specification), 12-bit packed, 24/48-bit colour, raw colour-filter mosaics (opened
  as mono, no demosaicing).
* Multi-GB files open instantly as virtual stacks.

## Verified

* Decoded pixels equal an independent Python decoder ([Phantastic](https://github.com/cpatb/phantastic), itself checked against PCC's
  own files) on 4 lab recordings and 4 public sample cines (256², 512², 768², 1280×800; 8-bit,
  12-bit, P10): 0 mismatches over more than 1400 frames.
* Opening `drop1.cine` with step 1000 aligned to the trigger gives exactly the images PCC put in
  its own ×1000 file (image numbers, pixel statistics).
* P10 decode matches the vendor decoder on a real P10 file up to the vendor's black/white stretch
  (within 1 DN); row order confirmed against PCC's TIFF export.
* Fiji: the opener is found by Fiji's plugin framework and opens a 2.5k-frame cine; plain
  ImageJ 1.54: menu command and *File > Open* both tested in batch mode.

Not verified: P12L files (no sample; bit order follows every published reader), colour and raw
mosaic cines (synthetic files only), and files with `bFlipV` set.

## Build

JDK 9+ (targets Java 8):

```
SCIJAVA_JAR=path/to/scijava-common.jar ./build.sh path/to/ij.jar
```

Without `SCIJAVA_JAR` the Fiji drag-and-drop hook is left out. Jars go to `dist/`.

Tests: `tests/compare_java_python.py` (needs Phantastic), `tests/*.ijm` (ImageJ batch mode).

## Prior art

A Fiji importer by Masayasu Takayama (MIT, 2026) reportedly also reads 8/16-bit, P10 and P12L cines (found by a literature search, not tested here). The 2012
ImageJ reader by Terry Jordan does not read packed files.

## License

BSD 2-Clause. The bundled JCodec 0.2.5 is BSD 2-Clause (`lib/JCodec-LICENSE.txt`).
