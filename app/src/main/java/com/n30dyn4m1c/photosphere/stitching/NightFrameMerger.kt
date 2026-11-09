package com.n30dyn4m1c.photosphere.stitching

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import org.opencv.calib3d.Calib3d
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfDMatch
import org.opencv.core.DMatch
import org.opencv.core.TermCriteria
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Night Mode Frame Merger.
 *
 * Aligns and merges a burst of frames (8-16) into one clean frame using:
 * - ECC (Enhanced Correlation Coefficient) for fast sub-pixel alignment
 * - Per-pixel median merge for ghost-free results
 * - Weighted average fallback for devices without median support
 *
 * Runs entirely on-device using OpenCV (already in deps).
 */
object NightFrameMerger {
    private const val TAG = "NightFrameMerger"

    data class Config(
        val framesPerTarget: Int = 12,
        val maxExposureTimeMs: Long = 333,
        val maxIso: Int = 3200,
        val mergeMethod: MergeMethod = MergeMethod.Median,
        val alignMethod: AlignMethod = AlignMethod.ECC,
        val minFramesForMerge: Int = 4,
        /** Downscale factor for alignment (1.0 = full res, 0.5 = half). Speeds up ECC significantly. */
        val alignDownscale: Float = 0.5f,
        /** Tile size for median merge (process in tiles to bound memory). */
        val medianTileSize: Int = 512,
    )

    enum class MergeMethod { Median, WeightedAverage }
    enum class AlignMethod { ECC, ORB, None }

    data class MergeResult(
        val outputFile: File,
        val framesMerged: Int,
        val framesRejected: Int,
        val mergeTimeMs: Long,
    )

    /**
     * Main entry point: aligns and merges a burst of frames into one clean frame.
     *
     * @param files Burst frame files (same scene, same pose, different noise realizations)
     * @param config Merge configuration
     * @return Merged frame file, or null on failure
     */
    fun mergeBurst(
        files: List<File>,
        config: Config = Config(),
    ): File? {
        val startTime = System.currentTimeMillis()

        if (files.size < config.minFramesForMerge) {
            Log.w(TAG, "Insufficient frames: ${files.size} < ${config.minFramesForMerge}")
            return null
        }

        return try {
            // 1. Load frames as 32-bit float linear RGB
            val frames = loadFramesLinear(files)
            if (frames.size < config.minFramesForMerge) return null

            // 2. Align all frames to reference (first frame)
            val alignedFrames = alignFrames(frames, config)
            if (alignedFrames.size < config.minFramesForMerge) return null

            // 3. Merge aligned frames
            val merged = mergeFrames(alignedFrames, config)

            // 4. Convert back: linear → sRGB → 8-bit JPEG
            val outputFile = saveMerged(merged, files[0])

            // Cleanup
            frames.forEach { it.release() }
            alignedFrames.forEach { it.release() }
            merged.release()

            val result = MergeResult(
                outputFile = outputFile,
                framesMerged = alignedFrames.size,
                framesRejected = frames.size - alignedFrames.size,
                mergeTimeMs = System.currentTimeMillis() - startTime,
            )
            Log.i(TAG, "Night merge: ${result.framesMerged} frames, ${result.mergeTimeMs}ms")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Night merge failed", e)
            null
        }
    }

    // =========================================================================
    // LOAD: Decode JPEG → 32-bit float linear RGB
    // =========================================================================

    private fun loadFramesLinear(files: List<File>): List<Mat> {
        val frames = mutableListOf<Mat>()
        for (file in files) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: continue
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            // Ensure 3-channel RGB
            if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
            } else if (mat.channels() == 1) {
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_GRAY2RGB)
            }
            // Convert to 32-bit float [0,1]
            mat.convertTo(mat, CvType.CV_32FC3, 1.0 / 255.0)
            // sRGB → linear (gamma 2.2)
            Core.pow(mat, 2.2, mat)
            frames.add(mat)
        }
        return frames
    }

    // =========================================================================
    // ALIGN: ECC (fast) or ORB (robust)
    // =========================================================================

    private fun alignFrames(frames: List<Mat>, config: Config): List<Mat> {
        val reference = frames[0]
        val aligned = mutableListOf<Mat>()
        aligned.add(reference.clone()) // Reference frame stays as-is

        // Precompute reference grayscale at alignment resolution
        val (refGray, refSize) = prepareGrayForAlign(reference, config.alignDownscale)

        for (i in 1 until frames.size) {
            val frame = frames[i]
            val alignedFrame = when (config.alignMethod) {
                AlignMethod.ECC -> alignECC(frame, refGray, refSize, reference.size())
                AlignMethod.ORB -> alignORB(frame, reference)
                AlignMethod.None -> frame.clone()
            }
            aligned.add(alignedFrame)
        }
        return aligned
    }

    private fun prepareGrayForAlign(mat: Mat, downscale: Float): Pair<Mat, Size> {
        val targetSize = Size(
            (mat.cols() * downscale).toDouble(),
            (mat.rows() * downscale).toDouble()
        )
        val resized = Mat()
        Imgproc.resize(mat, resized, targetSize, 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGB2GRAY)
        gray.convertTo(gray, CvType.CV_32F)
        return Pair(gray, targetSize)
    }

    /**
     * ECC alignment: fast, sub-pixel, good for handheld micro-motion.
     * Returns aligned frame at ORIGINAL resolution.
     */
    private fun alignECC(frame: Mat, refGray: Mat, refSize: Size, originalSize: Size): Mat {
        val frameResized = Mat()
        Imgproc.resize(frame, frameResized, refSize, 0.0, 0.0, Imgproc.INTER_AREA)
        val frameGray = Mat()
        Imgproc.cvtColor(frameResized, frameGray, Imgproc.COLOR_RGB2GRAY)
        frameGray.convertTo(frameGray, CvType.CV_32F)

        val warpMatrix = Mat.eye(2, 3, CvType.CV_32F)
        val criteria = TermCriteria(
            TermCriteria.COUNT + TermCriteria.EPS,
            50,  // Max iterations (reduced for speed)
            1e-4
        )

        try {
            val cc = Video.findTransformECC(
                refGray, frameGray, warpMatrix,
                Video.MOTION_TRANSLATION, // Translation only (fast, sufficient for handheld)
                criteria
            )
            if (cc > 0.85) { // Good correlation
                val aligned = Mat()
                // Scale warp matrix back to original resolution
                val scaleX = originalSize.width / refSize.width
                val scaleY = originalSize.height / refSize.height
                val fullWarp = Mat.eye(2, 3, CvType.CV_32F)
                warpMatrix.copyTo(fullWarp)
                fullWarp.put(0, 2, warpMatrix.get(0, 2)[0] * scaleX) // tx
                fullWarp.put(1, 2, warpMatrix.get(1, 2)[0] * scaleY) // ty

                Imgproc.warpAffine(
                    frame, aligned, fullWarp, originalSize,
                    Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP,
                    Core.BORDER_REFLECT_101
                )
                return aligned
            }
        } catch (e: Exception) {
            Log.w(TAG, "ECC alignment failed for frame", e)
        } finally {
            frameResized.release()
            frameGray.release()
        }
        return frame.clone()
    }

    /**
     * ORB alignment: more robust for larger motions, slower.
     * Returns aligned frame at original resolution.
     */
    private fun alignORB(frame: Mat, reference: Mat): Mat {
        val refGray = Mat()
        val frameGray = Mat()
        Imgproc.cvtColor(reference, refGray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.cvtColor(frame, frameGray, Imgproc.COLOR_RGB2GRAY)

        val orb = ORB.create(500) // Fewer features for speed
        val kp1 = MatOfKeyPoint()
        val kp2 = MatOfKeyPoint()
        val desc1 = Mat()
        val desc2 = Mat()

        try {
            orb.detectAndCompute(refGray, Mat(), kp1, desc1)
            orb.detectAndCompute(frameGray, Mat(), kp2, desc2)

            if (desc1.empty() || desc2.empty() || kp1.toList().size < 10) {
                return frame.clone()
            }

            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            val knnMatches = mutableListOf<MatOfDMatch>()
            matcher.knnMatch(desc1, desc2, knnMatches, 2)

            // Lowe's ratio test
            val goodMatches = mutableListOf<DMatch>()
            for (m in knnMatches) {
                val arr = m.toArray()
                if (arr.size == 2 && arr[0].distance < 0.75 * arr[1].distance) {
                    goodMatches.add(arr[0])
                }
            }

            if (goodMatches.size >= 8) {
                val srcPts = MatOfPoint2f()
                val dstPts = MatOfPoint2f()
                val kp1Arr = kp1.toArray()
                val kp2Arr = kp2.toArray()

                val srcPtsArr = goodMatches.map { kp1Arr[it.queryIdx].pt }.toTypedArray()
                val dstPtsArr = goodMatches.map { kp2Arr[it.trainIdx].pt }.toTypedArray()
                srcPts.fromArray(*srcPtsArr)
                dstPts.fromArray(*dstPtsArr)

                val homography = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 2.0)
                if (!homography.empty()) {
                    val aligned = Mat()
                    Imgproc.warpPerspective(frame, aligned, homography, frame.size(),
                        Imgproc.INTER_LINEAR, Core.BORDER_REFLECT_101)
                    return aligned
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ORB alignment failed", e)
        } finally {
            refGray.release()
            frameGray.release()
            kp1.release()
            kp2.release()
            desc1.release()
            desc2.release()
        }
        return frame.clone()
    }

    // =========================================================================
    // MERGE: Per-pixel median (ghost-free) or weighted average
    // =========================================================================

    private fun mergeFrames(frames: List<Mat>, config: Config): Mat {
        return when (config.mergeMethod) {
            MergeMethod.Median -> mergeMedianTiled(frames, config.medianTileSize)
            MergeMethod.WeightedAverage -> mergeWeightedAverage(frames)
        }
    }

    /**
     * Per-pixel median merge using tile-based processing to bound memory.
     * This is the gold standard for night mode: removes moving objects (ghosts),
     * reduces noise by sqrt(N), preserves detail.
     */
    private fun mergeMedianTiled(frames: List<Mat>, tileSize: Int): Mat {
        val height = frames[0].rows()
        val width = frames[0].cols()
        val merged = Mat.zeros(height, width, CvType.CV_32FC3)

        val tileY = 0 until height step tileSize
        val tileX = 0 until width step tileSize

        // Pre-allocate working mats per tile
        val tileFrames = frames.map { Mat() }.toMutableList()

        for (y in tileY) {
            val tileH = minOf(tileSize, height - y)
            for (x in tileX) {
                val tileW = minOf(tileSize, width - x)
                val roi = Rect(x, y, tileW, tileH)

                // Extract tile from each frame
                for (i in frames.indices) {
                    frames[i].submat(roi).copyTo(tileFrames[i])
                }

                // Compute per-pixel median for this tile
                val tileMerged = computeTileMedian(tileFrames, frames.size)

                // Write back
                tileMerged.copyTo(merged.submat(roi))
                tileMerged.release()
            }
        }

        tileFrames.forEach { it.release() }
        return merged
    }

    /**
     * Computes per-pixel median across N frames for a single tile.
     * Uses sorting per pixel (accurate, memory-bounded by tile size).
     */
    private fun computeTileMedian(tileFrames: List<Mat>, frameCount: Int): Mat {
        val h = tileFrames[0].rows()
        val w = tileFrames[0].cols()
        val channels = 3
        val medianTile = Mat.zeros(h, w, CvType.CV_32FC3)

        // For each channel, collect pixel values across frames
        for (c in 0 until channels) {
            // Extract channel c from all frames → List<Mat> of single-channel tiles
            val channelTiles = tileFrames.map { frame ->
                val ch = Mat()
                Core.extractChannel(frame, ch, c)
                ch
            }

            // For each pixel position, compute median across frames
            val medianChannel = Mat.zeros(h, w, CvType.CV_32F)
            val pixelValues = FloatArray(frameCount)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    // Gather values at (x,y) from all frames
                    for (f in 0 until frameCount) {
                        pixelValues[f] = channelTiles[f].get(y, x)[0].toFloat()
                    }
                    // Sort and take median
                    pixelValues.sort()
                    val median = if (frameCount % 2 == 0) {
                        (pixelValues[frameCount / 2 - 1] + pixelValues[frameCount / 2]) * 0.5f
                    } else {
                        pixelValues[frameCount / 2]
                    }
                    medianChannel.put(y, x, median.toDouble())
                }
            }

            // Insert median channel back
            Core.insertChannel(medianChannel, medianTile, c)
            channelTiles.forEach { it.release() }
            medianChannel.release()
        }
        return medianTile
    }

    /**
     * Weighted average merge: faster, less memory, but doesn't remove ghosts.
     * Weights by sharpness (Laplacian variance) so sharper frames contribute more.
     */
    private fun mergeWeightedAverage(frames: List<Mat>): Mat {
        val sharpness = frames.map { computeSharpness(it) }
        val maxSharpness = sharpness.maxOrNull() ?: 1.0
        // Normalize weights, add small epsilon to avoid zero weight
        val weights = sharpness.map { (it + 0.01 * maxSharpness) / (maxSharpness + 0.01 * maxSharpness) }
        val sumWeights = weights.sum()
        val normWeights = weights.map { it / sumWeights }

        val merged = Mat.zeros(frames[0].size(), frames[0].type())
        for (i in frames.indices) {
            Core.addWeighted(merged, 1.0, frames[i], normWeights[i], 0.0, merged)
        }
        return merged
    }

    private fun computeSharpness(mat: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        val laplacian = Mat()
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        Core.meanStdDev(laplacian, mean, stddev)
        gray.release()
        laplacian.release()
        mean.release()
        stddev.release()
        val m = mean.toArray().firstOrNull() ?: 0.0
        return m * m // Variance
    }

    // =========================================================================
    // SAVE: Linear → sRGB → 8-bit JPEG
    // =========================================================================

    private fun saveMerged(mergedLinear: Mat, referenceFile: File): File {
        val outputDir = referenceFile.parentFile!!
        val outputFile = File(outputDir, "night_merged_${referenceFile.name}")

        // Linear → sRGB (gamma 1/2.2)
        Core.pow(mergedLinear, 1.0 / 2.2, mergedLinear)
        // Clamp to [0,1]
        Core.min(mergedLinear, Scalar(1.0), mergedLinear)
        Core.max(mergedLinear, Scalar(0.0), mergedLinear)
        // Float → 8-bit
        mergedLinear.convertTo(mergedLinear, CvType.CV_8UC3, 255.0)

        val bitmap = Bitmap.createBitmap(mergedLinear.cols(), mergedLinear.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mergedLinear, bitmap)
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return outputFile
    }
}