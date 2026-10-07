// File>Open / drag-and-drop path (plain ImageJ 1.x with Cine_DragDrop_ImageJ1 installed).
setBatchMode(true);
open(getArgument());
print("OPENED " + getTitle() + " " + getWidth() + "x" + getHeight() + " n=" + nSlices + " bit=" + bitDepth()
      + " label=[" + getInfo("slice.label") + "]");
