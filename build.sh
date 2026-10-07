#!/usr/bin/env bash
# Build the plugin jars with a JDK >= 9 (bytecode targets Java 8, so ImageJ 1.x's bundled JRE runs it).
# Usage: ./build.sh path/to/ij.jar      (or set IJ_JAR)
set -euo pipefail
cd "$(dirname "$0")"
IJ_JAR="${1:-${IJ_JAR:-}}"
[ -n "$IJ_JAR" ] || { echo "usage: ./build.sh path/to/ij.jar   (or set IJ_JAR)"; exit 2; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
VERSION=$(cat VERSION)
OUT=target
[ -d "$OUT" ] && find "$OUT" -mindepth 1 -delete
mkdir -p "$OUT/main" "$OUT/ij1" "$OUT/mp4" dist
# Optional Fiji hook (File>Open / drag-and-drop): needs scijava-common on the class path.
# SCIJAVA_JAR=path/to/scijava-common-*.jar ./build.sh ij.jar
SRCS=$(find src/main/java -name '*.java'); CP="$IJ_JAR"
if [ -n "${SCIJAVA_JAR:-}" ]; then SRCS="$SRCS $(find src/fiji/java -name '*.java')"; CP="$CP$SEP$SCIJAVA_JAR"; fi
"$JAVAC" --release 8 -Xlint:-options -encoding UTF-8 -cp "$CP" -d "$OUT/main" $SRCS
cp -r src/main/resources/. "$OUT/main/"
"$JAR" cf "dist/Cine_Reader-$VERSION.jar" -C "$OUT/main" .
"$JAVAC" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ_JAR$SEP$OUT/main" -d "$OUT/ij1" src/ij1-dragdrop/java/*.java
cp -r src/ij1-dragdrop/resources/. "$OUT/ij1/"
"$JAR" cf "dist/Cine_DragDrop_ImageJ1-$VERSION.jar" -C "$OUT/ij1" .
if [ -d src/mp4/java ]; then
  LIBS=$(find lib -name '*.jar' 2>/dev/null | tr '\n' "$SEP")
  "$JAVAC" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ_JAR$SEP$OUT/main$SEP$LIBS" -d "$OUT/mp4" $(find src/mp4/java -name '*.java')
  cp -r src/mp4/resources/. "$OUT/mp4/"
  # Shade the third-party jars (JCodec, BSD-2; see lib/README.md) into the plugin jar, no relocation.
  for L in $(find lib -name '*.jar' 2>/dev/null); do (cd "$OUT/mp4" && "$JAR" xf "../../$L"); done
  rm -f "$OUT/mp4/META-INF/MANIFEST.MF"
  mkdir -p "$OUT/mp4/META-INF" && cp lib/JCodec-LICENSE.txt "$OUT/mp4/META-INF/LICENSE-JCodec.txt"
  "$JAR" cf "dist/PowerPoint_MP4_Export-$VERSION.jar" -C "$OUT/mp4" .
fi
echo "built:"; ls -l dist
