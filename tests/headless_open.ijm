// Headless check of File>Import>Phantom Cine... : opens a range with a step, prints what ImageJ sees.
// Usage: fiji --headless --console -macro headless_open.ijm "path|first|last|every|align|virtual"
args = split(getArgument(), "|");
path = args[0];
opts = "open=[" + path + "] first=" + args[1] + " last=" + args[2] + " every=" + args[3];
if (args[4] == "first") opts = opts + " alignment=[First image of range, then every step-th]";
else opts = opts + " alignment=[Multiples of step (trigger = 0; same frames as PCC)]";
if (args[5] == "1") opts = opts + " virtual";
setBatchMode(true);
run("Phantom Cine...", opts);
print("TITLE " + getTitle());
print("SIZE " + getWidth() + " " + getHeight() + " " + nSlices + " bitDepth=" + bitDepth());
Stack.getUnits(xu, yu, zu, tu, vu);
print("FRAMEINTERVAL " + Stack.getFrameInterval() + " " + tu);
for (s = 1; s <= minOf(nSlices, 3); s++) {
    setSlice(s);
    getStatistics(area, mean, min, max);
    print("SLICE " + s + " label=[" + getInfo("slice.label") + "] mean=" + d2s(mean, 6) + " min=" + min + " max=" + max
          + " px(10,20)=" + getPixel(10, 20));
}
print("PROP frameRate=" + Property.get("cine.frameRate") + " first=" + Property.get("cine.firstImageNumber"));
close("*");
