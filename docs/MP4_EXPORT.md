# PowerPoint MP4 export

## 1. Function

The plugin `PowerPoint_MP4_Export-<VERSION>.jar` writes an image stack to an MP4 file that PowerPoint can play.
The input can be a Phantom cine, a TIFF stack, or any image stack that is open in ImageJ.
The plugin reads one frame at a time, so a virtual stack of 1 GB to 2 GB does not go into memory.
The plugin does not change the pixels, the display range or the LUT of the open image.

The MP4 file has these properties (measured with `ffprobe`, see Section 6):

- Video codec: H.264. With `ffmpeg`, the profile is High. With the built-in encoder, the profile is Constrained Baseline.
- Pixel format: yuv420p, limited ("TV") range, tagged BT.709.
- Width and height: even numbers.
- Frame rate: constant. The value is the playback frame rate in the dialog.
- B-frames: none.
- Container: MP4, with the `moov` box before the `mdat` box ("fast start").

## 2. Installation

1. Put `Cine_Reader-<VERSION>.jar` and `PowerPoint_MP4_Export-<VERSION>.jar` in the `plugins` folder of ImageJ or Fiji.
2. Start ImageJ again.
3. To use the `ffmpeg` encoder, install an `ffmpeg` that contains `libx264`.
4. Put `ffmpeg` on the PATH, or type its full path in the dialog.

The plugin jar contains JCodec 0.2.5 (BSD 2-clause licence, see `META-INF/LICENSE-JCodec.txt` and `lib/README.md`).
The plugin uses JCodec only when `ffmpeg` is not available, or when you select it.

## 3. Commands

- `File > Save As > PowerPoint MP4...` writes the selected image or stack. A second dialog asks for the output file.
- `Plugins > Phantom > Batch convert to PowerPoint MP4...` writes one MP4 file for each `.cine`, `.tif` or `.tiff` file that you select. The MP4 file has the name of the input file and goes in the output folder that you select.

For a hyperstack, the movie is the time series (or the z series) of the selected channel at the selected position.

The two commands are macro-recordable. The first word of each dialog label is the macro key. Example:

```
run("PowerPoint MP4...", "playback=30 first=1 last=1076 every=1 scaling=[Use current display range (what you see)] minimum=0 maximum=4095 width=0 quality=18 encoder=[ffmpeg if found, else built-in (JCodec)] ffmpeg=[] save=[C:/movies/drop1.mp4]");
run("Batch convert to PowerPoint MP4...", "input=[C:/data/a.cine;C:/data/b.tif] output=[C:/movies] playback=30 first=1 last=0 every=1 scaling=[Auto (0.35% saturated, computed once over sampled frames, fixed for the whole movie)] minimum=0 maximum=65535 width=0 quality=18 encoder=[ffmpeg if found, else built-in (JCodec)] ffmpeg=[]");
```

## 4. Options

| Label | Macro key | Default | Function |
|---|---|---|---|
| Playback frame rate | `playback` | 30 fps | Frame rate of the movie. It is not the recording rate. |
| First frame, Last frame | `first`, `last` | all frames | Frame range, as slice numbers that start at 1. In batch mode, `last=0` gives all frames to the end. |
| Every | `every` | 1 | Step N: the movie contains the first frame and every N-th frame after it. |
| Scaling | `scaling` | display range | How the plugin maps pixel values to 0 to 255 (see Section 5). |
| Minimum, Maximum | `minimum`, `maximum` | display range | The range for explicit scaling. |
| Width cap | `width` | 0 (no cap) | If the image is wider than this value, the plugin makes it smaller with bilinear interpolation and keeps the aspect ratio. |
| Quality (CRF) | `quality` | 18 | `ffmpeg`: the x264 CRF. JCodec: a fixed QP. A lower value gives a higher quality and a larger file. |
| Encoder | `encoder` | `ffmpeg` if found | "ffmpeg only" stops with an error if the plugin cannot find `ffmpeg`. |
| ffmpeg executable | `ffmpeg` | empty | Full path to `ffmpeg`. If the field is empty, the plugin examines the folders in the PATH. ImageJ keeps the value (key `pptmp4.ffmpeg`). |
| Burn in slice label | `burn` | off | Writes "frame N" and the slice label (for a cine: image number and time) in the top-left corner. |
| Input files, Output folder, Overwrite | `input`, `output`, `overwrite` | batch only | Input files have a ";" between them. The plugin does not replace an MP4 file that exists, unless you select Overwrite. |

## 5. Scaling

The plugin uses one fixed linear map for all frames of a movie:

`out = floor((v - min) * 255 / (max - min) + 0.5)`, then limited to the range 0 to 255.

There is no auto-contrast for each frame. Thus, the brightness of the movie does not change from frame to frame, and one pixel value gives the same gray level in all frames.

The plugin calculates `min` and `max` one time, before the first frame:

- "Use current display range": the display range of the image. After `File > Import > Phantom Cine...`, this range is the minimum and maximum of the first image. For an RGB image, ImageJ applies the display range to the pixels. Thus, the map does not change the RGB values.
- "Auto": the plugin makes one histogram from a maximum of 50 frames at equal intervals in the selected range. Then it removes 0.35 % of the pixels (0.175 % at each end).
- "Explicit": the minimum and maximum in the dialog.

The Log window shows the `min` and `max` that the plugin used.

For a gray image, the plugin then applies the LUT of the image. An inverted gray LUT gives 255 minus the value. A color LUT gives an RGB movie. RGB input uses the same map on each channel.

The plugin writes gray as Y = 16 + 219 × value / 255 with neutral chroma (128). This limited range is the standard range for video. A player changes Y back to the value. The plugin does not write full-range ("Y = value") files, because PowerPoint possibly does not read the full-range flag.

## 6. Size, padding and PowerPoint

- H.264 with 4:2:0 chroma needs an even width and height. If a dimension is an odd number, the plugin adds one column or row. The added column or row is a copy of the last column or row. The plugin does not remove pixels.
- The plugin changes the image size only when you set a width cap.
- If the frame size and frame rate need an H.264 level above 5.1, the Log window shows a warning. Set a width cap or a lower frame rate.
- The plugin was not tested in PowerPoint. The tests measured only the container and codec properties in Section 1, with `ffprobe`.

## 7. Limits

- The plugin writes 8-bit movies only. The movie does not keep the full bit depth of the data.
- 4:2:0 chroma decreases the color resolution by 2 in each direction. Thus, color edges in an RGB movie become less clear. On a synthetic RGB test stack, the PSNR was 25 dB to 36 dB, and an uncompressed 4:2:0 conversion of the same stack gave the same values.
- At quality 18, the built-in JCodec encoder gave a file 2.7 times larger than `ffmpeg` on a 1076-frame cine (18.6 MB and 7.0 MB). Its PSNR was 2.9 dB higher.
- The built-in encoder writes a temporary file (`<output>.jcodec-tmp`) in the output folder, then moves the `moov` box to the start of the file.
- For a 32-bit image, the Auto range uses a histogram with 65536 bins between the minimum and maximum of the sampled frames.
- The plugin has no audio and no scale bar.
- A multichannel composite image gives a movie of the selected channel only. To get all channels, use `Image > Color > Stack to RGB` first.
