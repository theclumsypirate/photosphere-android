package com.n30dyn4m1c.photosphere.metadata

import android.util.Log
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt

/**
 * The Google Photo Sphere (GPano) properties that mark a JPEG as a 360° photo.
 *
 * Every length is in pixels of the image the metadata is attached to. The
 * `CroppedArea*` group describes which part of the full sphere the pixels
 * actually cover: for a full equirectangular frame it is the whole image, which
 * is what [forFullPano] produces. A partial capture keeps the full-pano
 * dimensions at the size the *complete* sphere would have been and places the
 * covered rectangle inside it — [forSphereRegion] expresses a ring capture that
 * way, so a viewer renders the band the frames actually cover and limits
 * panning to it instead of showing the black wedges where nothing was shot.
 */
data class GPanoMetadata(
    /** Width the complete sphere occupies. */
    val fullPanoWidthPixels: Int,
    /** Height the complete sphere occupies; half [fullPanoWidthPixels] at 2:1. */
    val fullPanoHeightPixels: Int,
    /** Width of the region of the sphere this image holds. */
    val croppedAreaImageWidthPixels: Int,
    /** Height of the region of the sphere this image holds. */
    val croppedAreaImageHeightPixels: Int,
    /** Left edge of that region within the full sphere. */
    val croppedAreaLeftPixels: Int,
    /** Top edge of that region within the full sphere. */
    val croppedAreaTopPixels: Int,
    /** Projection the pixels are in. Viewers only understand [PROJECTION_EQUIRECTANGULAR]. */
    val projectionType: String = PROJECTION_EQUIRECTANGULAR,
    /** Whether viewers should open this in a pannable sphere viewer. */
    val usePanoramaViewer: Boolean = true,
) {
    init {
        require(fullPanoWidthPixels > 0 && fullPanoHeightPixels > 0) {
            "full pano must have area, was ${fullPanoWidthPixels}x$fullPanoHeightPixels"
        }
        require(croppedAreaImageWidthPixels > 0 && croppedAreaImageHeightPixels > 0) {
            "cropped area must have area, was " +
                "${croppedAreaImageWidthPixels}x$croppedAreaImageHeightPixels"
        }
        require(croppedAreaLeftPixels >= 0 && croppedAreaTopPixels >= 0) {
            "cropped area origin must be inside the sphere, was " +
                "($croppedAreaLeftPixels, $croppedAreaTopPixels)"
        }
        require(croppedAreaLeftPixels + croppedAreaImageWidthPixels <= fullPanoWidthPixels) {
            "cropped area runs past the right edge of the sphere"
        }
        require(croppedAreaTopPixels + croppedAreaImageHeightPixels <= fullPanoHeightPixels) {
            "cropped area runs past the bottom edge of the sphere"
        }
        require(projectionType.isNotBlank()) { "projectionType must not be blank" }
    }

    companion object {
        /** The only projection Google Photos, Facebook and friends will render. */
        const val PROJECTION_EQUIRECTANGULAR = "equirectangular"

        /**
         * Metadata for an image that *is* the whole sphere: the cropped area
         * covers all of it, anchored at the origin.
         */
        fun forFullPano(imageWidth: Int, imageHeight: Int): GPanoMetadata = GPanoMetadata(
            fullPanoWidthPixels = imageWidth,
            fullPanoHeightPixels = imageHeight,
            croppedAreaImageWidthPixels = imageWidth,
            croppedAreaImageHeightPixels = imageHeight,
            croppedAreaLeftPixels = 0,
            croppedAreaTopPixels = 0,
        )

        /**
         * Metadata for an image that holds a *region* of the sphere, mapped
         * equirectangularly.
         *
         * The image covers [longitudeSpanDegrees] of longitude centred on
         * [centerLongitudeDegrees] and [latitudeSpanDegrees] of latitude centred
         * on [centerLatitudeDegrees]. A viewer maps the full-pano dimensions to
         * the whole 360°×180° sphere and renders the cropped area inside it, so
         * a ring capture — one band all the way around the horizon — is expressed
         * as the matching horizontal slice of a full sphere, and panning is
         * limited to the band the frames actually covered. A full sphere passed
         * through here resolves to the same values as [forFullPano].
         */
        fun forSphereRegion(
            imageWidth: Int,
            imageHeight: Int,
            longitudeSpanDegrees: Float,
            centerLongitudeDegrees: Float,
            latitudeSpanDegrees: Float,
            centerLatitudeDegrees: Float,
        ): GPanoMetadata {
            require(longitudeSpanDegrees in 1f..360f) { "longitude span: $longitudeSpanDegrees" }
            require(latitudeSpanDegrees in 1f..180f) { "latitude span: $latitudeSpanDegrees" }
            require(centerLatitudeDegrees in -90f..90f) {
                "centre latitude: $centerLatitudeDegrees"
            }
            val fullWidth = (imageWidth * 360f / longitudeSpanDegrees).roundToInt()
            val fullHeight = (imageHeight * 180f / latitudeSpanDegrees).roundToInt()
            // Longitude is periodic: a centre of 180° describes the same sphere
            // as -180°, and folding the centre into [-180, 180) keeps the
            // cropped rectangle inside the full-pano bounds. (The cropped area
            // is a *rectangle* in equirectangular space, so a region straddling
            // the ±180° seam has to be expressed on one side of it — the same
            // unwrap the canvas itself performs.)
            val center = ((centerLongitudeDegrees + 180f) % 360f + 360f) % 360f - 180f
            val left = ((center + 180f) / 360f * fullWidth).roundToInt() - imageWidth / 2
            val top = ((90f - centerLatitudeDegrees - latitudeSpanDegrees / 2f) / 180f * fullHeight)
                .roundToInt()
            return GPanoMetadata(
                fullPanoWidthPixels = fullWidth,
                fullPanoHeightPixels = fullHeight,
                croppedAreaImageWidthPixels = imageWidth,
                croppedAreaImageHeightPixels = imageHeight,
                // The clamps are rounding guards: the maths above keeps the
                // rectangle inside the sphere, but a round at the edge could
                // still land one pixel past it.
                croppedAreaLeftPixels = left.coerceIn(0, (fullWidth - imageWidth).coerceAtLeast(0)),
                croppedAreaTopPixels = top.coerceIn(0, (fullHeight - imageHeight).coerceAtLeast(0)),
            )
        }
    }
}

/**
 * Writes GPano XMP into a stitched equirectangular JPEG.
 *
 * Without this the output is just a wide picture: what makes a viewer open a
 * file as a pannable 360° photo is an XMP packet in the `GPano` namespace, and
 * neither `ExifInterface` nor `Bitmap.compress` can put one there.
 *
 * **The pixels are never touched.** The JPEG is not decoded and not re-encoded —
 * this walks the file's marker segments, drops any XMP packet already present,
 * splices in a new `APP1` segment, and copies every remaining byte (including
 * the whole entropy-coded scan) through verbatim. Re-encoding to add metadata
 * would cost a generation of JPEG loss on an image that has already been through
 * one on the way in.
 *
 * ### Where the segment goes
 *
 * A JPEG is `SOI` followed by marker segments; the metadata ones (`APPn`) sit at
 * the front, before the quantisation tables and the scan. The new packet is
 * placed after the last `APP0`/`APP1` — that is, after JFIF and after EXIF if
 * they are there — which is where readers expect to find it, and before anything
 * heavier such as an ICC profile.
 *
 * Everything here is plain `java.io`, so it is unit-testable off-device.
 */
object GPanoXmpInjector {

    /** The Photo Sphere namespace itself. */
    const val GPANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/"

    private const val RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    /** Identifies an `APP1` segment as carrying XMP rather than EXIF. */
    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/"

    /**
     * Second half of a packet too large for one segment. Never written here —
     * the GPano packet is a few hundred bytes — but recognised so that replacing
     * someone else's XMP cannot leave an orphaned continuation behind.
     */
    private const val XMP_EXTENSION_NAMESPACE = "http://ns.adobe.com/xmp/extension/"

    /** Tool marker written into the packet, for anyone inspecting the file later. */
    private const val TOOLKIT = "PhotoSphere"

    private val XMP_SIGNATURE = "$XMP_NAMESPACE\u0000".toByteArray(Charsets.US_ASCII)
    private val XMP_EXTENSION_SIGNATURE =
        "$XMP_EXTENSION_NAMESPACE\u0000".toByteArray(Charsets.US_ASCII)

    private const val MARKER_PREFIX = 0xFF
    private const val MARKER_SOI = 0xD8
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_APP15 = 0xEF
    private const val MARKER_COM = 0xFE

    /** The two bytes of a segment's length field, which the field itself counts. */
    private const val SEGMENT_LENGTH_BYTES = 2

    /** Largest value that field can hold, and so the largest segment. */
    private const val MAX_SEGMENT_LENGTH = 0xFFFF

    /** Suffix of the file written beside the target while it is being rebuilt. */
    private const val TEMP_SUFFIX = ".gpano.tmp"

    /**
     * Injects full-sphere GPano metadata into [file], in place.
     *
     * [imageWidth] and [imageHeight] must be the JPEG's real pixel dimensions;
     * they are what a viewer uses to map pixels onto the sphere, so a wrong value
     * shows up as a panorama that does not close or that is squashed at the
     * poles.
     *
     * Blocking I/O over the whole file — call off the main thread. Failures come
     * back as a failed [Result] rather than an exception: a sphere whose metadata
     * could not be written is still a perfectly good image, and losing it over a
     * header would be the worse outcome.
     */
    fun inject(file: File, imageWidth: Int, imageHeight: Int): Result<File> =
        inject(file, GPanoMetadata.forFullPano(imageWidth, imageHeight))

    /** Injects [metadata] into [file], in place. See the overload above. */
    fun inject(file: File, metadata: GPanoMetadata): Result<File> = runCatching {
        val temp = file.resolveSibling(file.name + TEMP_SUFFIX)
        try {
            BufferedInputStream(FileInputStream(file)).use { source ->
                BufferedOutputStream(FileOutputStream(temp)).use { sink ->
                    inject(source, sink, metadata)
                }
            }
            if (!temp.renameTo(file)) {
                // Some filesystems refuse to rename onto an existing name. The
                // original is fully readable until this point; replace it
                // atomically rather than delete-then-rename, which would open a
                // window where neither copy exists if the second call failed.
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    throw IOException("Could not move ${temp.name} onto ${file.name}", e)
                }
            }
            Log.i(Logging.TAG, "GPano injected into ${file.name} (${metadata.projectionType})")
            file
        } finally {
            if (temp.exists()) temp.delete()
        }
    }.onFailure { error ->
        Log.e(Logging.TAG, "GPano inject failed for ${file.name}", error)
    }

    /**
     * Copies the JPEG on [source] to [sink] with [metadata] spliced into it.
     *
     * Neither stream is closed. Throws [IOException] if [source] is not a JPEG,
     * ends mid-segment, or the packet will not fit in one `APP1` segment.
     */
    @Throws(IOException::class)
    fun inject(source: InputStream, sink: OutputStream, metadata: GPanoMetadata) {
        val packet = buildXmpPacket(metadata).toByteArray(Charsets.UTF_8)
        val segmentLength = SEGMENT_LENGTH_BYTES + XMP_SIGNATURE.size + packet.size
        if (segmentLength > MAX_SEGMENT_LENGTH) {
            throw IOException("XMP packet is $segmentLength bytes, too large for one segment")
        }

        if (source.read() != MARKER_PREFIX || source.read() != MARKER_SOI) {
            throw IOException("Not a JPEG: the file does not start with SOI")
        }
        sink.write(MARKER_PREFIX)
        sink.write(MARKER_SOI)

        val (leading, nextMarker) = readLeadingSegments(source)

        // After JFIF and EXIF, before an ICC profile or anything else.
        val insertAt = leading.indexOfLast {
            it.marker == MARKER_APP0 || it.marker == MARKER_APP1
        } + 1

        leading.take(insertAt).forEach { it.writeTo(sink) }
        writeXmpSegment(sink, packet)
        leading.drop(insertAt).forEach { it.writeTo(sink) }

        // From the first non-metadata marker on, the file is copied byte for
        // byte: tables, frame header, and the entropy-coded scan untouched.
        sink.write(MARKER_PREFIX)
        sink.write(nextMarker)
        source.copyTo(sink)
    }

    /**
     * The XMP packet, as the text that goes into the segment.
     *
     * `UsePanoramaViewer` is the switch a viewer actually reads; the rest tells
     * it how to wrap the pixels around the sphere.
     */
    fun buildXmpPacket(metadata: GPanoMetadata): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"$TOOLKIT\">\n")
        append("  <rdf:RDF xmlns:rdf=\"$RDF_NAMESPACE\">\n")
        append("    <rdf:Description rdf:about=\"\"\n")
        append("        xmlns:GPano=\"$GPANO_NAMESPACE\"\n")
        append("        GPano:UsePanoramaViewer=\"${metadata.usePanoramaViewer.asXmpBoolean()}\"\n")
        append("        GPano:ProjectionType=\"${metadata.projectionType.escapeXmlAttribute()}\"\n")
        append("        GPano:FullPanoWidthPixels=\"${metadata.fullPanoWidthPixels}\"\n")
        append("        GPano:FullPanoHeightPixels=\"${metadata.fullPanoHeightPixels}\"\n")
        append(
            "        GPano:CroppedAreaImageWidthPixels=" +
                "\"${metadata.croppedAreaImageWidthPixels}\"\n"
        )
        append(
            "        GPano:CroppedAreaImageHeightPixels=" +
                "\"${metadata.croppedAreaImageHeightPixels}\"\n"
        )
        append("        GPano:CroppedAreaLeftPixels=\"${metadata.croppedAreaLeftPixels}\"\n")
        append("        GPano:CroppedAreaTopPixels=\"${metadata.croppedAreaTopPixels}\"/>\n")
        append("  </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        // "r" for read-only: no padding follows, so nothing may edit the packet
        // in place. Rewriting the segment, as this class does, is still fine.
        append("<?xpacket end=\"r\"?>")
    }

    /** One marker segment, held while the metadata block is reordered. */
    private class Segment(val marker: Int, val payload: ByteArray) {
        fun writeTo(sink: OutputStream) {
            val length = SEGMENT_LENGTH_BYTES + payload.size
            sink.write(MARKER_PREFIX)
            sink.write(marker)
            sink.write(length ushr 8)
            sink.write(length and 0xFF)
            sink.write(payload)
        }
    }

    /**
     * Reads the run of metadata segments at the head of the file, dropping any
     * XMP already there, and stops on the first marker that is not one.
     *
     * Returns those segments plus the marker it stopped on, which the caller
     * still has to write — it has been consumed from [source] but belongs to the
     * verbatim tail.
     */
    private fun readLeadingSegments(source: InputStream): Pair<List<Segment>, Int> {
        val segments = ArrayList<Segment>()
        while (true) {
            val prefix = source.read()
            if (prefix == -1) throw IOException("JPEG ended before any image data")
            if (prefix != MARKER_PREFIX) {
                throw IOException("Expected a marker, found 0x${prefix.toString(16)}")
            }

            // Any number of 0xFF bytes may pad the gap before a marker.
            var marker = source.read()
            while (marker == MARKER_PREFIX) marker = source.read()
            if (marker == -1) throw IOException("JPEG ended on a bare marker prefix")

            val isMetadata = marker in MARKER_APP0..MARKER_APP15 || marker == MARKER_COM
            if (!isMetadata) return segments to marker

            val length = (source.readOrThrow() shl 8) or source.readOrThrow()
            if (length < SEGMENT_LENGTH_BYTES) {
                throw IOException("Segment 0x${marker.toString(16)} declares $length bytes")
            }
            val payload = source.readExactly(length - SEGMENT_LENGTH_BYTES)

            // Replacing rather than appending: two XMP packets in one file is
            // undefined, and readers generally take the first one they find.
            val isExistingXmp = marker == MARKER_APP1 &&
                (payload.startsWith(XMP_SIGNATURE) || payload.startsWith(XMP_EXTENSION_SIGNATURE))
            if (!isExistingXmp) segments += Segment(marker, payload)
        }
    }

    private fun writeXmpSegment(sink: OutputStream, packet: ByteArray) {
        val length = SEGMENT_LENGTH_BYTES + XMP_SIGNATURE.size + packet.size
        sink.write(MARKER_PREFIX)
        sink.write(MARKER_APP1)
        sink.write(length ushr 8)
        sink.write(length and 0xFF)
        sink.write(XMP_SIGNATURE)
        sink.write(packet)
    }

    /** XMP booleans are the words, capitalised — not `1`/`0` or lowercase. */
    private fun Boolean.asXmpBoolean(): String = if (this) "True" else "False"

    private fun String.escapeXmlAttribute(): String = buildString(length) {
        this@escapeXmlAttribute.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private fun ByteArray.startsWith(signature: ByteArray): Boolean =
        size >= signature.size && signature.indices.all { this[it] == signature[it] }

    private fun InputStream.readOrThrow(): Int =
        read().also { if (it == -1) throw EOFException("JPEG ended inside a segment header") }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) throw EOFException("JPEG ended $offset/$count bytes into a segment")
            offset += read
        }
        return bytes
    }
}
