# Third-party jars

`build.sh` puts every jar in this folder on the compile class path of the MP4 exporter and
extracts its classes into `dist/PowerPoint_MP4_Export-<VERSION>.jar` (no relocation), so the
plugin is one jar.

| File | Source | Version | SHA-256 | Licence |
|---|---|---|---|---|
| `jcodec-0.2.5.jar` | Maven Central, `org.jcodec:jcodec:0.2.5` (https://repo1.maven.org/maven2/org/jcodec/jcodec/0.2.5/jcodec-0.2.5.jar) | 0.2.5 | `890329dad124e8b739c1d6602a59a53c8a474daddff265c2561e21c498496c81` | BSD 2-clause ("FreeBSD"), see `JCodec-LICENSE.txt` |

The download was checked against Maven Central's published SHA-1
(`0b1968a3dbf46aa5ac9630ce9e3cbf67c4940d08`, matched) on 2026-10-07. `org.jcodec:jcodec-javase`
is not needed (it only adds AWT helpers) and is not used.

`JCodec-LICENSE.txt` is the `LICENSE` file of https://github.com/jcodec/jcodec (master branch;
the jar itself carries no licence file and there is no `v0.2.5` tag on GitHub). It is copied
into the plugin jar as `META-INF/LICENSE-JCodec.txt`.
