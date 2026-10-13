# The finished sphere

**GPano metadata.** [`GPanoXmpInjector`](../app/src/main/java/com/n30dyn4m1c/photosphere/metadata/GPanoXmpInjector.kt)
is what makes the output a *360 photo* rather than a wide picture: Google
Photos, Facebook and friends switch to a sphere viewer on an XMP packet in the
`GPano` namespace, and `ExifInterface` writes EXIF only. It walks the JPEG's
marker segments, drops any XMP already present, splices an `APP1` segment in
after JFIF/Exif, and copies every remaining byte — tables, frame header and the
entropy-coded scan — through verbatim. **Nothing is decoded or re-encoded**, so
tagging costs no image quality on a file that has already been through one
generation of JPEG on the way in. Eight properties are written:
`UsePanoramaViewer`, `ProjectionType`, `FullPano{Width,Height}Pixels` and the
four `CroppedArea*` values, which cover the whole image because the renderer
draws straight into a full 2:1 canvas and leaves what the capture never reached
black. It is plain `java.io`, so it is covered by local unit tests that assert
the image data comes out byte-identical.

**The result screen.** [`PanoramaResultScreen`](../app/src/main/java/com/n30dyn4m1c/photosphere/result/PanoramaResultScreen.kt)
shows the sphere flat — the black wedges at the poles are the parts the run
never reached, and they are worth seeing before deciding to keep it — over three
actions: **Export to gallery**, **Share**, and **New photo**. Nothing has been
published at this point, which is deliberate: a run that came out badly should
not have to be deleted out of the camera roll afterwards. "New photo" discards
the cached JPEG and returns to capture with an empty buffer; the system back
gesture does the same thing.

Because that cached JPEG is the only copy until an export lands, both routes out
**confirm first** while the sphere is unsaved — "New photo" sits directly beside
"Share", and a stray tap on it (or a back gesture) would otherwise throw away
several minutes of standing in one spot turning around, silently. Once the photo
has been written to the gallery the question stops being worth asking and the
prompt no longer appears.

**Export.** [`MediaExporter`](../app/src/main/java/com/n30dyn4m1c/photosphere/storage/MediaExporter.kt)
copies the file into `Pictures/360Panoramas` through MediaStore. From API 29 the
row is inserted with `IS_PENDING = 1`, the bytes are streamed into the URI
MediaStore hands back, and `IS_PENDING` is cleared only once the copy finishes,
so a half-written JPEG is never visible in the gallery. On API 26–28 there is no
`RELATIVE_PATH` and no pending flag, so the file is written into the public
Pictures directory and the row points at it with `DATA` — that path is why
`WRITE_EXTERNAL_STORAGE` is requested on exactly those versions. Either way it
is a byte copy, not a re-encode, which is what keeps the GPano packet intact.

**Share** sends the same JPEG through `Intent.ACTION_SEND`. The cache is
private, and a `file://` URI would throw `FileUriExposedException` on anything
since API 24, so it travels as a `content://` URI from the app's `FileProvider`
(`res/xml/file_paths.xml` exposes `cacheDir/spheres/` and nothing else) with a
read grant attached.
