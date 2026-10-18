package com.n30dyn4m1c.photosphere.extendedcapture

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.util.Logging
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * A Kotlin port of `tools/pano_proto/stitch_closed.py`, the script the extended
 * capture reference grid was proven with.
 *
 * Debug only: referenced from `app/src/debug` alone. It exists because the
 * production renderer resolves overlaps with multi-band blending, and that is
 * not what the prototype does. The prototype is pure geometry:
 *
 * - pinhole intrinsics from the frame size and field of view,
 * - a pivot-model warp (the scene sits at [SCENE_METERS], the lens [LEVER_METERS]
 *   off the turn axis),
 * - decode, then resize every frame to half size with `INTER_AREA`,
 * - winner takes all by feather weight (no gain, no multiband),
 * - ownership-border cross-fade: for each owner, a 50/50 blend of the owner's
 *   frame with the best other frame on the border pixels inside a 9x9 zone of
 *   that owner, only where the two disagree by less than [AGREE_DIFF] per
 *   channel, accumulated in float and in ascending owner order,
 * - Telea inpainting of the slivers an almost-complete sphere leaves.
 *
 * Every operation is an OpenCV call with the same parameters the script uses
 * (`imread`, `INTER_AREA`, `INTER_LINEAR`, `BORDER_CONSTANT`, `INPAINT_TELEA`),
 * and the JPEG is decoded by OpenCV itself, so the pixels that go in are the
 * pixels the script puts in.
 *
 * Frames are streamed one at a time, twice: once for the winner pass and once
 * for the border pass. Nothing keeps every decoded frame in memory: the loop
 * holds the running winner and the second best per pixel, then only each
 * frame's pixels at the ownership border, which is what keeps a 62-frame grid
 * at 4096 inside a phone's budget.
 */
internal object PrototypeRenderer {

    private const val DEFAULT_OUTPUT_WIDTH = 2048

    /**
     * The width of the higher resolution rerun the extended capture screen can
     * ask for after a normal stitch. `tools/pano_proto/out/stairs_perfect.jpg`
     * is rendered at this width, so a rerun here is the one that can be byte
     * compared against it.
     */
    internal const val HIGH_RESOLUTION_OUTPUT_WIDTH = 4096
    private const val FEATHER_PIXELS = 80
    private const val LEVER_METERS = 0.35
    private const val SCENE_METERS = 3.5

    /** The script's `w2 > 0.05`: the least weight a second frame may carry. */
    private const val ALT_MIN_WEIGHT = 0.05f

    /** The script's `d < 12`: per-pixel channel agreement, or a hard edge. */
    private const val AGREE_DIFF = 12.0

    /** The script's `41 px` near-coverage dilation and `21 px` inpaint radius. */
    private const val FILL_DILATE = 41
    private const val INPAINT_RADIUS = 21.0

    /** The script's `IMWRITE_JPEG_QUALITY 95`, for the raw comparison file. */
    private const val SPHERE_JPEG_QUALITY = 95

    /**
     * Renders [frames] into one equirectangular sphere, the way the script does.
     *
     * [nativeWidth] and [nativeHeight] are the upright frame size the intrinsics
     * and the half-size decode are derived from; the script takes them as `--fw`
     * and `--fh`. [onFrame] is called once per frame, from this thread.
     *
     * [rawJpegFile], when given, gets a second copy of the canvas encoded by
     * OpenCV (`imwrite`, quality 95, no metadata) - the same encoder and settings
     * the script writes with, for a byte-level comparison. The returned bitmap is
     * the one the app goes on to store.
     */
    fun render(
        frames: List<SphereFrame>,
        nativeWidth: Int,
        nativeHeight: Int,
        horizontalFovDegrees: Double,
        verticalFovDegrees: Double,
        outputWidth: Int = DEFAULT_OUTPUT_WIDTH,
        leverMeters: Double = LEVER_METERS,
        sceneMeters: Double = SCENE_METERS,
        rawJpegFile: File? = null,
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Bitmap {
        require(frames.isNotEmpty()) { "no frames" }
        require(nativeWidth >= 8 && nativeHeight >= 8) { "frame too small" }

        val started = SystemClock.elapsedRealtime()
        val fw = nativeWidth
        val fh = nativeHeight
        val fw2 = fw / 2
        val fh2 = fh / 2
        val outW = outputWidth
        val outH = outputWidth / 2
        val n = outW * outH

        Logging.i(
            (
                "Prototype render: %d frames, out=%dx%d, native=%dx%d, " +
                    "fov=%.2fx%.2f deg, lever=%.2f, scene=%.2f"
                ).format(
                Locale.US, frames.size, outW, outH, fw, fh,
                horizontalFovDegrees, verticalFovDegrees, leverMeters, sceneMeters,
            ),
        )

        val fx = (fw / 2.0) / tan(Math.toRadians(horizontalFovDegrees / 2.0))
        val fy = (fh / 2.0) / tan(Math.toRadians(verticalFovDegrees / 2.0))
        val cx = fw / 2.0
        val cy = fh / 2.0

        // sin/cos of the column and row angles, so the per-pixel direction is
        // three multiplies and no trigonometry.
        val su = DoubleArray(outW)
        val cu = DoubleArray(outW)
        for (u in 0 until outW) {
            val angle = (u.toDouble() / outW - 0.5) * 2.0 * Math.PI
            su[u] = sin(angle)
            cu[u] = cos(angle)
        }
        val sv = DoubleArray(outH)
        val cv = DoubleArray(outH)
        for (v in 0 until outH) {
            val angle = (0.5 - v.toDouble() / outH) * Math.PI
            sv[v] = sin(angle)
            cv[v] = cos(angle)
        }

        val feather = buildFeather(fw2, fh2, min(FEATHER_PIXELS, fw / 8))

        val bestWeight = FloatArray(n) { -1f }
        val bestIndex = ShortArray(n) { -1 }
        val winImage = ByteArray(n * 3)

        // The second best frame, kept only because the border cross-fade needs
        // it. Its pixels at a border pixel are read back from the per-frame
        // border samples, so only its index and weight are kept, not a whole
        // image copy.
        val top2Weight = FloatArray(n) { -1f }
        val top2Index = ShortArray(n) { -1 }

        val mapXf = FloatArray(n)
        val mapYf = FloatArray(n)
        val inside = ByteArray(n)
        val weight = FloatArray(n)
        val imageBytes = ByteArray(n * 3)

        val mapX = Mat(outH, outW, CvType.CV_32FC1)
        val mapY = Mat(outH, outW, CvType.CV_32FC1)
        val weightMat = Mat(outH, outW, CvType.CV_32FC1)
        val sampled = Mat(outH, outW, CvType.CV_8UC3)

        var decoded = 0
        try {
            frames.forEachIndexed { index, frame ->
                if (!sampleFrame(
                        frame, fw, fh, fx, fy, cx, cy, su, cu, sv, cv, outW, outH,
                        leverMeters, sceneMeters, feather, mapX, mapY, weightMat,
                        sampled, mapXf, mapYf, inside, weight, imageBytes,
                    )
                ) {
                    Logging.w("Prototype: could not decode ${frame.file.name}")
                    onFrame(index + 1, frames.size)
                    return@forEachIndexed
                }
                decoded++

                val frameIndex = index.toShort()
                for (p in 0 until n) {
                    val w = if (inside[p] != 0.toByte()) weight[p] else 0f
                    val base = p * 3

                    if (w > bestWeight[p]) {
                        // The frame that was best drops to second best.
                        top2Weight[p] = bestWeight[p]
                        top2Index[p] = bestIndex[p]
                        bestWeight[p] = w
                        bestIndex[p] = frameIndex
                        winImage[base] = imageBytes[base]
                        winImage[base + 1] = imageBytes[base + 1]
                        winImage[base + 2] = imageBytes[base + 2]
                    } else if (w > top2Weight[p]) {
                        top2Weight[p] = w
                        top2Index[p] = frameIndex
                    }
                }

                onFrame(index + 1, frames.size)
            }

            crossfadeBorders(
                frames, fw, fh, fx, fy, cx, cy, su, cu, sv, cv, feather,
                leverMeters, sceneMeters, outW, outH, bestWeight, bestIndex,
                winImage, top2Weight, top2Index, mapX, mapY, weightMat, sampled,
                mapXf, mapYf, inside, weight, imageBytes,
            )
            val canvas = buildCanvas(outW, outH, n, bestWeight, winImage, rawJpegFile)
            val covered = bestWeight.count { it > 0f }
            val percent = if (n == 0) 0 else covered * 100 / n
            Logging.i(
                "Prototype render done: decoded=%d/%d, covered=%d%%, raw=%s, %dms".format(
                    Locale.US, decoded, frames.size, percent,
                    rawJpegFile?.name ?: "none",
                    SystemClock.elapsedRealtime() - started,
                ),
            )
            return canvas
        } finally {
            mapX.release()
            mapY.release()
            weightMat.release()
            sampled.release()
            feather.release()
        }
    }

    /** The script's `feather` array: a linear ramp to zero on all four edges. */
    private fun buildFeather(width: Int, height: Int, r: Int): Mat {
        val feather = Mat(height, width, CvType.CV_32FC1)
        val arr = FloatArray(width * height) { 1f }
        if (r > 0) {
            val rise = if (r == 1) floatArrayOf(0f) else FloatArray(r) { it.toFloat() / (r - 1) }
            val fall = if (r == 1) floatArrayOf(1f) else FloatArray(r) { 1f - it.toFloat() / (r - 1) }
            for (v in 0 until height) {
                val row = v * width
                for (u in 0 until width) {
                    var f = 1f
                    if (u < r) f *= rise[u]
                    if (u >= width - r) f *= fall[u - (width - r)]
                    if (v < r) f *= rise[v]
                    if (v >= height - r) f *= fall[v - (height - r)]
                    arr[row + u] = f
                }
            }
        }
        feather.put(0, 0, arr)
        return feather
    }

    /**
     * The script's `warp_one` reprojection, minus the sampling: fills the remap
     * maps and the inside mask for one frame.
     *
     * `adj = SCENE * dir - LEVER * fwd`, normalised; `z = adj . fwd`; the frame
     * pixel is `adj . right / z` and `adj . up / z` scaled by the focal lengths.
     * `z <= 0.02` and anything off the frame is outside.
     */
    @Suppress("LongParameterList")
    private fun buildMaps(
        frame: SphereFrame,
        fw: Int,
        fh: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        su: DoubleArray,
        cu: DoubleArray,
        sv: DoubleArray,
        cv: DoubleArray,
        outW: Int,
        outH: Int,
        leverMeters: Double,
        sceneMeters: Double,
        mapXf: FloatArray,
        mapYf: FloatArray,
        inside: ByteArray,
    ) {
        val yaw = Math.toRadians(frame.pose.yawDegrees.toDouble())
        val elev = Math.toRadians(-frame.pose.pitchDegrees.toDouble())
        val roll = Math.toRadians(frame.pose.rollDegrees.toDouble())

        val sy = sin(yaw)
        val cyaw = cos(yaw)
        val ce = cos(elev)
        val fxDir = sy * ce
        val fyDir = cyaw * ce
        val fzDir = sin(elev)

        val r0x = cyaw
        val r0y = -sy
        val u0x = r0y * fzDir
        val u0y = -r0x * fzDir
        val u0z = r0x * fyDir - r0y * fxDir

        val cr = cos(roll)
        val sr = sin(roll)
        val rx = r0x * cr - u0x * sr
        val ry = r0y * cr - u0y * sr
        val rz = -u0z * sr
        val ux = u0x * cr + r0x * sr
        val uy = u0y * cr + r0y * sr
        val uz = u0z * cr

        var p = 0
        for (v in 0 until outH) {
            val cvv = cv[v]
            val svv = sv[v]
            for (u in 0 until outW) {
                val dx = su[u] * cvv
                val dy = cu[u] * cvv
                val dz = svv

                val adx = sceneMeters * dx - leverMeters * fxDir
                val ady = sceneMeters * dy - leverMeters * fyDir
                val adz = sceneMeters * dz - leverMeters * fzDir
                val norm = sqrt(adx * adx + ady * ady + adz * adz)

                if (norm == 0.0) {
                    mapXf[p] = -1f
                    mapYf[p] = -1f
                    inside[p] = 0.toByte()
                    p++
                    continue
                }
                val nx = adx / norm
                val ny = ady / norm
                val nz = adz / norm

                val z = nx * fxDir + ny * fyDir + nz * fzDir
                val safeZ = if (z > 1e-9) z else 1e-9
                val x = (nx * rx + ny * ry + nz * rz) / safeZ
                val y = (nx * ux + ny * uy + nz * uz) / safeZ
                val px = x * fx + cx
                val py = cy - y * fy

                mapXf[p] = (px * 0.5).toFloat()
                mapYf[p] = (py * 0.5).toFloat()
                inside[p] = if (z > 0.02 && px >= 0.0 && px < fw && py >= 0.0 && py < fh) {
                    1.toByte()
                } else {
                    0.toByte()
                }
                p++
            }
        }
    }

    /**
     * The script's ownership-border cross-fade, ported exactly.
     *
     * The script does not blend each border pixel once with its second best.
     * For every owner it takes the border pixels inside a 9x9 neighbourhood of
     * that owner's region, compares the owner's frame with the best other frame
     * there, and blends 50/50 in float where the two agree to within
     * [AGREE_DIFF]. Owners are processed in ascending index order, so a pixel
     * at a junction gets blended more than once. Keeping the accumulation in
     * float and truncating only at the end is what makes the bytes match.
     *
     * The 9x9 zone means any frame's pixels may be read at a zone pixel, so the
     * sampled image of every frame is collected at the border pixels in a
     * second pass.
     */
    @Suppress("LongParameterList")
    private fun crossfadeBorders(
        frames: List<SphereFrame>,
        fw: Int,
        fh: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        su: DoubleArray,
        cu: DoubleArray,
        sv: DoubleArray,
        cv: DoubleArray,
        feather: Mat,
        leverMeters: Double,
        sceneMeters: Double,
        outW: Int,
        outH: Int,
        bestWeight: FloatArray,
        bestIndex: ShortArray,
        winImage: ByteArray,
        top2Weight: FloatArray,
        top2Index: ShortArray,
        mapX: Mat,
        mapY: Mat,
        weightMat: Mat,
        sampled: Mat,
        mapXf: FloatArray,
        mapYf: FloatArray,
        inside: ByteArray,
        weight: FloatArray,
        imageBytes: ByteArray,
    ) {
        val n = outW * outH

        val border = BooleanArray(n)
        var borderCount = 0
        for (v in 0 until outH) {
            val row = v * outW
            for (u in 0 until outW) {
                val p = row + u
                if (bestIndex[p] < 0) continue
                if ((v > 0 && bestIndex[p - outW] != bestIndex[p]) ||
                    (u > 0 && bestIndex[p - 1] != bestIndex[p])
                ) {
                    border[p] = true
                    borderCount++
                }
            }
        }
        if (borderCount == 0) return

        val borderIndices = IntArray(borderCount)
        val posInBorder = IntArray(n) { -1 }
        var bi = 0
        for (p in 0 until n) {
            if (border[p]) {
                borderIndices[bi] = p
                posInBorder[p] = bi
                bi++
            }
        }

        // The script only iterates owners that own a border pixel.
        val borderOwner = BooleanArray(frames.size)
        for (k in 0 until borderCount) {
            val o = bestIndex[borderIndices[k]].toInt()
            if (o >= 0) borderOwner[o] = true
        }

        // The sampled image of every frame, but only at the border pixels, so a
        // zone pixel can read any frame there.
        val frameBorder = Array(frames.size) { ByteArray(borderCount * 3) }
        for (index in frames.indices) {
            if (!sampleFrame(
                    frames[index], fw, fh, fx, fy, cx, cy, su, cu, sv, cv, outW, outH,
                    leverMeters, sceneMeters, feather, mapX, mapY, weightMat, sampled,
                    mapXf, mapYf, inside, weight, imageBytes,
                )
            ) {
                continue
            }
            val dest = frameBorder[index]
            for (k in 0 until borderCount) {
                val base = borderIndices[k] * 3
                val d = k * 3
                dest[d] = imageBytes[base]
                dest[d + 1] = imageBytes[base + 1]
                dest[d + 2] = imageBytes[base + 2]
            }
        }

        // zone[idx]: the border pixels inside a 9x9 neighbourhood of idx.
        val zones = Array(frames.size) { ArrayList<Int>() }
        val seen = IntArray(frames.size) { -1 }
        var stamp = 0
        for (k in 0 until borderCount) {
            val p = borderIndices[k]
            val v = p / outW
            val u = p % outW
            stamp++
            val v0 = maxOf(0, v - 4)
            val v1 = minOf(outH - 1, v + 4)
            val u0 = maxOf(0, u - 4)
            val u1 = minOf(outW - 1, u + 4)
            for (vv in v0..v1) {
                val row = vv * outW
                for (uu in u0..u1) {
                    val o = bestIndex[row + uu].toInt()
                    if (o >= 0 && borderOwner[o] && seen[o] != stamp) {
                        seen[o] = stamp
                        zones[o].add(p)
                    }
                }
            }
        }

        // Only border pixels are ever blended, so a border-sized float buffer is
        // enough; every other pixel stays the winner's bytes untouched.
        val canvas = FloatArray(borderCount * 3)
        for (k in 0 until borderCount) {
            val base = borderIndices[k] * 3
            val d = k * 3
            canvas[d] = (winImage[base].toInt() and 0xFF).toFloat()
            canvas[d + 1] = (winImage[base + 1].toInt() and 0xFF).toFloat()
            canvas[d + 2] = (winImage[base + 2].toInt() and 0xFF).toFloat()
        }

        for (idx in frames.indices) {
            if (!borderOwner[idx]) continue
            val zone = zones[idx]
            if (zone.isEmpty()) continue
            val img = frameBorder[idx]
            for (p in zone) {
                val ownHere = bestIndex[p].toInt() == idx
                val altWeight = if (ownHere) top2Weight[p] else bestWeight[p]
                if (altWeight <= ALT_MIN_WEIGHT) continue
                val k = posInBorder[p]
                val sb = k * 3
                val altFrame = if (ownHere) frameBorder[top2Index[p].toInt()] else winImage
                val ab = if (ownHere) sb else p * 3
                val a0 = altFrame[ab]
                val a1 = altFrame[ab + 1]
                val a2 = altFrame[ab + 2]
                val d = (abs((img[sb].toInt() and 0xFF) - (a0.toInt() and 0xFF)) +
                    abs((img[sb + 1].toInt() and 0xFF) - (a1.toInt() and 0xFF)) +
                    abs((img[sb + 2].toInt() and 0xFF) - (a2.toInt() and 0xFF))) / 3.0
                if (d < AGREE_DIFF) {
                    canvas[sb] = 0.5f * canvas[sb] + 0.5f * (a0.toInt() and 0xFF)
                    canvas[sb + 1] = 0.5f * canvas[sb + 1] + 0.5f * (a1.toInt() and 0xFF)
                    canvas[sb + 2] = 0.5f * canvas[sb + 2] + 0.5f * (a2.toInt() and 0xFF)
                }
            }
        }

        for (k in 0 until borderCount) {
            val base = borderIndices[k] * 3
            val d = k * 3
            winImage[base] = clipByte(canvas[d])
            winImage[base + 1] = clipByte(canvas[d + 1])
            winImage[base + 2] = clipByte(canvas[d + 2])
        }
    }

    /** The script's `np.clip(x, 0, 255).astype(uint8)`: clamp, then truncate. */
    private fun clipByte(x: Float): Byte = when {
        x < 0f -> 0
        x > 255f -> 255
        else -> x.toInt()
    }.toByte()

    /**
     * Builds the maps for one frame and samples its image and feather weight
     * into the shared scratch buffers, returning false when the frame cannot be
     * decoded. Used for the winner pass and again for the cross-fade's border
     * pass.
     */
    @Suppress("LongParameterList")
    private fun sampleFrame(
        frame: SphereFrame,
        fw: Int,
        fh: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        su: DoubleArray,
        cu: DoubleArray,
        sv: DoubleArray,
        cv: DoubleArray,
        outW: Int,
        outH: Int,
        leverMeters: Double,
        sceneMeters: Double,
        feather: Mat,
        mapX: Mat,
        mapY: Mat,
        weightMat: Mat,
        sampled: Mat,
        mapXf: FloatArray,
        mapYf: FloatArray,
        inside: ByteArray,
        weight: FloatArray,
        imageBytes: ByteArray,
    ): Boolean {
        buildMaps(
            frame = frame, fw = fw, fh = fh, fx = fx, fy = fy, cx = cx, cy = cy,
            su = su, cu = cu, sv = sv, cv = cv, outW = outW, outH = outH,
            leverMeters = leverMeters, sceneMeters = sceneMeters,
            mapXf = mapXf, mapYf = mapYf, inside = inside,
        )
        mapX.put(0, 0, mapXf)
        mapY.put(0, 0, mapYf)

        val half = decodeHalf(frame.file, fw / 2, fh / 2)
        if (half == null) return false
        try {
            Imgproc.remap(
                half, sampled, mapX, mapY, Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT, Scalar(0.0),
            )
        } finally {
            half.release()
        }
        Imgproc.remap(
            feather, weightMat, mapX, mapY, Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT, Scalar(0.0),
        )
        weightMat.get(0, 0, weight)
        sampled.get(0, 0, imageBytes)
        return true
    }

    /**
     * Clips the painted canvas to bytes, fills the slivers near coverage with
     * Telea inpainting (the script's final step), and turns it into a bitmap.
     */
    private fun buildCanvas(
        outW: Int,
        outH: Int,
        n: Int,
        bestWeight: FloatArray,
        winImage: ByteArray,
        rawJpegFile: File?,
    ): Bitmap {
        val canvas = Mat(outH, outW, CvType.CV_8UC3)
        canvas.put(0, 0, winImage)

        var holes = false
        val coveredBytes = ByteArray(n)
        for (p in 0 until n) {
            if (bestWeight[p] > 0f) {
                coveredBytes[p] = 1.toByte()
            } else {
                coveredBytes[p] = 0.toByte()
                holes = true
            }
        }

        val inpainted = Mat()
        try {
            if (holes) {
                val covered = Mat(outH, outW, CvType.CV_8UC1)
                covered.put(0, 0, coveredBytes)
                val kernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, Size(FILL_DILATE.toDouble(), FILL_DILATE.toDouble()),
                )
                val nearCoverage = Mat()
                Imgproc.dilate(covered, nearCoverage, kernel)
                val nearBytes = ByteArray(n)
                nearCoverage.get(0, 0, nearBytes)

                val maskBytes = ByteArray(n)
                var any = false
                for (p in 0 until n) {
                    if (bestWeight[p] == 0f && (nearBytes[p].toInt() and 0xFF) > 0) {
                        maskBytes[p] = 255.toByte()
                        any = true
                    }
                }
                if (any) {
                    val mask = Mat(outH, outW, CvType.CV_8UC1)
                    mask.put(0, 0, maskBytes)
                    Photo.inpaint(canvas, mask, inpainted, INPAINT_RADIUS, Photo.INPAINT_TELEA)
                    val filled = ByteArray(n * 3)
                    inpainted.get(0, 0, filled)
                    for (p in 0 until n) {
                        if (maskBytes[p].toInt() != 0) {
                            val base = p * 3
                            winImage[base] = filled[base]
                            winImage[base + 1] = filled[base + 1]
                            winImage[base + 2] = filled[base + 2]
                        }
                    }
                    canvas.put(0, 0, winImage)
                    mask.release()
                }
                covered.release()
                nearCoverage.release()
                kernel.release()
            }

            if (rawJpegFile != null) {
                val wrote = Imgcodecs.imwrite(
                    rawJpegFile.absolutePath,
                    canvas,
                    MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, SPHERE_JPEG_QUALITY),
                )
                if (!wrote) {
                    Logging.w("Prototype: could not write ${rawJpegFile.name}")
                }
            }

            val rgba = Mat()
            try {
                Imgproc.cvtColor(canvas, rgba, Imgproc.COLOR_BGR2RGBA)
                val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgba, bitmap)
                return bitmap
            } finally {
                rgba.release()
            }
        } finally {
            inpainted.release()
            canvas.release()
        }
    }

    /**
     * The script's `read_upright`: OpenCV `imread`, EXIF orientation applied,
     * then halved with `INTER_AREA`.
     *
     * The image stays in OpenCV's BGR order, because that is the order the
     * script works in all the way to `imwrite`. `imread` is the same call the
     * script makes, so the decoded pixels match.
     */
    private fun decodeHalf(file: File, fw2: Int, fh2: Int): Mat? {
        val full = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        if (full.empty()) {
            full.release()
            return null
        }
        val upright = applyExifRotation(full, file)
        if (upright !== full) full.release()
        val half = Mat()
        try {
            Imgproc.resize(
                upright, half, Size(fw2.toDouble(), fh2.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA,
            )
            return half
        } finally {
            upright.release()
        }
    }

    /**
     * The script's `read_upright` orientation fix, on the `imread` result: EXIF
     * `274` value 3 is a 180, 6 a clockwise quarter turn, 8 a counter-clockwise
     * one. Flips are not part of the script and are not applied here either.
     */
    private fun applyExifRotation(image: Mat, file: File): Mat {
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val code = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_180 -> Core.ROTATE_180
            ExifInterface.ORIENTATION_ROTATE_90 -> Core.ROTATE_90_CLOCKWISE
            ExifInterface.ORIENTATION_ROTATE_270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return image
        }
        val rotated = Mat()
        Core.rotate(image, rotated, code)
        return rotated
    }
}
