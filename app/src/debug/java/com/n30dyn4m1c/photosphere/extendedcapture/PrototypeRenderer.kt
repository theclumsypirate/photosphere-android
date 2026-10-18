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
 * - 50/50 cross-fade across an ownership border only where the two frames
 *   disagree by less than [AGREE_DIFF] per channel,
 * - Telea inpainting of the slivers an almost-complete sphere leaves.
 *
 * Every operation is an OpenCV call with the same parameters the script uses
 * (`imread`, `INTER_AREA`, `INTER_LINEAR`, `BORDER_CONSTANT`, `INPAINT_TELEA`),
 * and the JPEG is decoded by OpenCV itself, so the pixels that go in are the
 * pixels the script puts in.
 *
 * Frames are streamed one at a time. Nothing keeps every decoded frame in
 * memory: the loop holds only the running winner and the two best weights per
 * pixel, which is what keeps a 62-frame grid inside a phone's budget.
 */
internal object PrototypeRenderer {

    private const val DEFAULT_OUTPUT_WIDTH = 2048
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

        // Second best, kept only because the border cross-fade needs it. t1 is
        // always the winner (the winner is the global maximum), so only the
        // second is read back.
        val top2Weight = FloatArray(n) { -1f }
        val top2Image = ByteArray(n * 3)
        val top1Weight = FloatArray(n) { -1f }
        val top1Image = ByteArray(n * 3)

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
                buildMaps(
                    frame = frame,
                    fw = fw,
                    fh = fh,
                    fx = fx,
                    fy = fy,
                    cx = cx,
                    cy = cy,
                    su = su,
                    cu = cu,
                    sv = sv,
                    cv = cv,
                    outW = outW,
                    outH = outH,
                    leverMeters = leverMeters,
                    sceneMeters = sceneMeters,
                    mapXf = mapXf,
                    mapYf = mapYf,
                    inside = inside,
                )

                mapX.put(0, 0, mapXf)
                mapY.put(0, 0, mapYf)
                val half = decodeHalf(frame.file, fw2, fh2)
                if (half == null) {
                    Logging.w("Prototype: could not decode ${frame.file.name}")
                    onFrame(index + 1, frames.size)
                    return@forEachIndexed
                }
                decoded++
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

                val frameIndex = index.toShort()
                for (p in 0 until n) {
                    val w = if (inside[p] != 0.toByte()) weight[p] else 0f
                    val base = p * 3

                    if (w > bestWeight[p]) {
                        bestWeight[p] = w
                        bestIndex[p] = frameIndex
                        winImage[base] = imageBytes[base]
                        winImage[base + 1] = imageBytes[base + 1]
                        winImage[base + 2] = imageBytes[base + 2]
                    }

                    if (w > ALT_MIN_WEIGHT) {
                        if (w > top1Weight[p]) {
                            top2Weight[p] = top1Weight[p]
                            top2Image[base] = top1Image[base]
                            top2Image[base + 1] = top1Image[base + 1]
                            top2Image[base + 2] = top1Image[base + 2]
                            top1Weight[p] = w
                            top1Image[base] = imageBytes[base]
                            top1Image[base + 1] = imageBytes[base + 1]
                            top1Image[base + 2] = imageBytes[base + 2]
                        } else if (w > top2Weight[p]) {
                            top2Weight[p] = w
                            top2Image[base] = imageBytes[base]
                            top2Image[base + 1] = imageBytes[base + 1]
                            top2Image[base + 2] = imageBytes[base + 2]
                        }
                    }
                }

                onFrame(index + 1, frames.size)
            }

            crossfadeBorders(outW, outH, bestIndex, top2Weight, winImage, top2Image)
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
     * The script's ownership-border cross-fade: an owner paints alone except on
     * the one-pixel border between owners, and there only a 50/50 blend with the
     * second-best frame, and only where the two agree to within [AGREE_DIFF].
     *
     * [winImage] is the owner's pixels and is blended in place.
     */
    private fun crossfadeBorders(
        outW: Int,
        outH: Int,
        bestIndex: ShortArray,
        top2Weight: FloatArray,
        winImage: ByteArray,
        top2Image: ByteArray,
    ) {
        for (v in 0 until outH) {
            val row = v * outW
            for (u in 0 until outW) {
                val p = row + u
                if (bestIndex[p] < 0) continue
                val border = (v > 0 && bestIndex[p - outW] != bestIndex[p]) ||
                    (u > 0 && bestIndex[p - 1] != bestIndex[p])
                if (!border || top2Weight[p] <= ALT_MIN_WEIGHT) continue

                val base = p * 3
                val d = (abs((winImage[base].toInt() and 0xFF) - (top2Image[base].toInt() and 0xFF)) +
                    abs((winImage[base + 1].toInt() and 0xFF) - (top2Image[base + 1].toInt() and 0xFF)) +
                    abs((winImage[base + 2].toInt() and 0xFF) - (top2Image[base + 2].toInt() and 0xFF))) / 3.0
                if (d < AGREE_DIFF) {
                    winImage[base] = (((winImage[base].toInt() and 0xFF) +
                        (top2Image[base].toInt() and 0xFF)) / 2).toByte()
                    winImage[base + 1] = (((winImage[base + 1].toInt() and 0xFF) +
                        (top2Image[base + 1].toInt() and 0xFF)) / 2).toByte()
                    winImage[base + 2] = (((winImage[base + 2].toInt() and 0xFF) +
                        (top2Image[base + 2].toInt() and 0xFF)) / 2).toByte()
                }
            }
        }
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
