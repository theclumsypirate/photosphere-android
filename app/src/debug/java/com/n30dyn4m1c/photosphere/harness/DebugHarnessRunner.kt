package com.n30dyn4m1c.photosphere.harness

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.n30dyn4m1c.photosphere.camera.FieldOfView
import com.n30dyn4m1c.photosphere.camera.SphereOptics
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.SeamMode
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object DebugHarnessRunner {

    fun defaultConfigs(): List<HarnessConfig> {
        val configs = mutableListOf<HarnessConfig>()
        var id = 1
        fun add(
            label: String, seam: SeamMode, refine: Boolean, dist: Boolean,
            block: Boolean, graph: Boolean, warp: Boolean, perceptual: Boolean, mask: Boolean,
            maxIn: Int = 1280, maxOut: Int = 4096, unsharp: Float = 0f,
        ) {
            configs += HarnessConfig((id++).toString(), label, seam, refine, dist, block, graph, warp, perceptual, mask, maxIn, maxOut, unsharp)
        }

        // Baselines
        add("Blend/sensor", SeamMode.Blend, false, true, false, false, false, false, false)
        add("Blend/refine", SeamMode.Blend, true, true, false, false, false, false, false)
        add("Carve/refine", SeamMode.Carve, true, true, false, false, false, false, false)
        add("Winner/refine", SeamMode.WinnerTakeAll, true, true, false, false, false, false, false)

        // Distortion A/B
        add("Blend/refine/no-dist", SeamMode.Blend, true, false, false, false, false, false, false)
        add("Carve/refine/no-dist", SeamMode.Carve, true, false, false, false, false, false, false)

        // Single debug toggles on top of carve/refine
        add("Carve+block", SeamMode.Carve, true, true, true, false, false, false, false)
        add("Carve+graph", SeamMode.Carve, true, true, false, true, false, false, false)
        add("Carve+warp", SeamMode.Carve, true, true, false, false, true, false, false)
        add("Carve+perceptual", SeamMode.Carve, true, true, false, false, false, true, false)
        add("Carve+mask", SeamMode.Carve, true, true, false, false, false, false, true)

        // Winner/Blend single toggles
        add("Winner+block", SeamMode.WinnerTakeAll, true, true, true, false, false, false, false)
        add("Winner+warp", SeamMode.WinnerTakeAll, true, true, false, false, true, false, false)
        add("Blend+block", SeamMode.Blend, true, true, true, false, false, false, false)
        add("Blend+warp", SeamMode.Blend, true, true, false, false, true, false, false)

        // Pairwise useful combos
        add("Carve+block+warp", SeamMode.Carve, true, true, true, false, true, false, false)
        add("Carve+block+graph", SeamMode.Carve, true, true, true, true, false, false, false)
        add("Carve+warp+graph", SeamMode.Carve, true, true, false, true, true, false, false)
        add("Carve+perceptual+mask", SeamMode.Carve, true, true, false, false, false, true, true)
        add("Winner+block+warp", SeamMode.WinnerTakeAll, true, true, true, false, true, false, false)

        // Kitchen sink
        add("Carve/all-debug", SeamMode.Carve, true, true, true, true, true, true, true)
        add("Winner/all-debug", SeamMode.WinnerTakeAll, true, true, true, true, true, true, true)
        add("Blend/all-debug", SeamMode.Blend, true, true, true, true, true, true, true)

        // Resolution/unsharp sensitivity (common practical variants)
        add("Carve/refine/1024in", SeamMode.Carve, true, true, false, false, false, false, false, 1024, 4096, 0f)
        add("Carve/refine/1536in", SeamMode.Carve, true, true, false, false, false, false, false, 1536, 4096, 0f)
        add("Carve/refine/6k", SeamMode.Carve, true, true, false, false, false, false, false, 1280, 6144, 0f)
        add("Carve/refine/unsharp", SeamMode.Carve, true, true, false, false, false, false, false, 1280, 4096, 0.35f)
        add("Winner/refine/6k", SeamMode.WinnerTakeAll, true, true, false, false, false, false, false, 1280, 6144, 0f)

        return configs
    }

    suspend fun run(
        context: Context,
        frames: List<SphereFrame>,
        fieldOfView: FieldOfView,
        optics: SphereOptics,
        configs: List<HarnessConfig> = defaultConfigs(),
        onState: (HarnessRunState) -> Unit,
    ): HarnessRunState = withContext(Dispatchers.Default) {
        val outDir = File(context.cacheDir, "harness_results").apply {
            deleteRecursively(); mkdirs()
        }
        val results = mutableListOf<HarnessResult>()
        var state = HarnessRunState(running = true, total = configs.size)
        onState(state)
        for ((i, cfg) in configs.withIndex()) {
            currentCoroutineContext().ensureActive()
            state = state.copy(completed = i, currentLabel = cfg.label)
            onState(state)
            val started = System.currentTimeMillis()
            val stitched = PhotoSphereStitcher.stitchPhotos(
                frames = frames,
                horizontalFovDegrees = fieldOfView.horizontalDegrees,
                verticalFovDegrees = fieldOfView.verticalDegrees,
                radialDistortion = if (cfg.distortionEnabled) optics.radialDistortion else null,
                maxInputDimension = cfg.maxInputDimension,
                maxOutputWidth = cfg.maxOutputWidth,
                unsharpAmount = cfg.unsharpAmount,
                pivot = com.n30dyn4m1c.photosphere.stitching.PivotModel.None,
                portraitRotationDegrees = optics.portraitRotationDegrees,
                useRefinement = cfg.useRefinement,
                seamMode = cfg.seamMode,
                debugColorFrames = false,
                movingObjectSeamMask = cfg.movingObjectSeamMask,
                perBlockExposure = cfg.perBlockExposure,
                graphSubsetSelection = cfg.graphSubsetSelection,
                localWarpAlignment = cfg.localWarpAlignment,
                perceptualSeamCost = cfg.perceptualSeamCost,
            )
            val runtime = System.currentTimeMillis() - started
            val result = stitched.fold(
                onSuccess = { bmp ->
                    val metrics = analyze(bmp, runtime)
                    val file = File(outDir, "${cfg.id}_${cfg.label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.jpg")
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    val r = HarnessResult(cfg, metrics, score(metrics), file, bmp.width, bmp.height)
                    bmp.safeRecycle()
                    r
                },
                onFailure = { e ->
                    HarnessResult(cfg, HarnessMetrics(0f, 0f, 1f, 0f, 0f, runtime, 0), 0f, File(outDir, "${cfg.id}_error.txt"), 0, 0, e.message ?: "error")
                }
            )
            results += result
            val best = results.filter { it.error == null }.maxByOrNull { it.score }?.config?.id
            state = state.copy(completed = i + 1, results = results.toList(), bestId = best)
            onState(state)
        }
        state.copy(running = false, currentLabel = "Done", message = "Best: #${state.bestId}")
    }

    private fun score(m: HarnessMetrics): Float {
        // Weighted score 0..100; higher is better.
        val coverage = m.coverage.coerceIn(0f, 1f)
        val sharp = (m.sharpness / 120f).coerceIn(0f, 1f)
        val seam = (1f - m.seamPenalty.coerceIn(0f, 1f))
        val exposure = m.exposureBalance.coerceIn(0f, 1f)
        val lumaPenalty = when {
            m.meanLuma < 0.08f -> m.meanLuma / 0.08f
            m.meanLuma > 0.95f -> (1f - m.meanLuma) / 0.05f
            else -> 1f
        }
        return (coverage * 30f + sharp * 25f + seam * 20f + exposure * 15f + lumaPenalty * 10f)
    }

    private fun analyze(bitmap: Bitmap, runtimeMs: Long): HarnessMetrics {
        val w = bitmap.width
        val h = bitmap.height
        val step = max(1, min(w, h) / 512)
        val sw = w / step
        val sh = h / step
        val pixels = IntArray(sw * sh)
        var pi = 0
        for (y in 0 until sh) for (x in 0 until sw) pixels[pi++] = bitmap.getPixel(x * step, y * step)
        var covered = 0
        var lumaSum = 0.0
        var lumaSq = 0.0
        val lumas = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xff) / 255f
            val g = ((c shr 8) and 0xff) / 255f
            val b = (c and 0xff) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            lumas[i] = l
            lumaSum += l
            lumaSq += l * l
            if (((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff) > 8) covered++
        }
        val n = pixels.size.coerceAtLeast(1)
        val meanLuma = (lumaSum / n).toFloat()
        val variance = (lumaSq / n - (lumaSum / n) * (lumaSum / n)).coerceAtLeast(0.0)
        val exposureBalance = (1.0 / (1.0 + sqrt(variance) * 2.5)).toFloat()
        var lapSum = 0.0
        var lapCount = 0
        var seamSum = 0.0
        var seamCount = 0
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val idx = y * sw + x
                val c = lumas[idx]
                val lap = abs(4 * c - lumas[idx - 1] - lumas[idx + 1] - lumas[idx - sw] - lumas[idx + sw])
                lapSum += lap
                lapCount++
                val dx = abs(lumas[idx + 1] - lumas[idx - 1])
                val dy = abs(lumas[idx + sw] - lumas[idx - sw])
                val localContrast = dx + dy
                if (localContrast > 0.18f) { seamSum += localContrast; seamCount++ }
            }
        }
        val sharpness = if (lapCount > 0) (lapSum / lapCount * 1000f).toFloat() else 0f
        val seamPenalty = if (seamCount > 0) (seamSum / seamCount).toFloat().coerceIn(0f, 1f) else 0f
        return HarnessMetrics(
            coverage = covered.toFloat() / n,
            sharpness = sharpness,
            seamPenalty = seamPenalty,
            exposureBalance = exposureBalance,
            meanLuma = meanLuma,
            runtimeMs = runtimeMs,
            pixels = w * h,
        )
    }

    fun loadFramesFromFiles(files: List<File>): List<SphereFrame> {
        return files.mapIndexed { i, f -> SphereFrame(file = f, pose = CameraPose(0f, 0f, 0f), index = i) }
    }
}
