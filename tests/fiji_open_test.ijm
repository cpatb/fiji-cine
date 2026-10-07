// Fiji GUI check of File>Open on a .cine (the drag-and-drop code path). Writes result, then quits.
// Usage: fiji -macro fiji_open_test.ijm "path|outfile"
a = split(getArgument(), "|");
open(a[0]);
s = "OPENED " + getTitle() + " " + getWidth() + "x" + getHeight() + " n=" + nSlices + " bit=" + bitDepth()
    + " label=[" + getInfo("slice.label") + "]";
File.saveString(s, a[1]);
run("Quit");
eval("script", "System.exit(0);");
