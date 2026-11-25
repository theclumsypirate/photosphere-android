package com.n30dyn4m1c.photosphere.harness

import android.graphics.Bitmap
import com.n30dyn4m1c.photosphere.stitching.SeamMode
import java.io.File

internal data class HarnessConfig(
    val id: String,
    val label: String,
    val seamMode: SeamMode,
    val useRefinement: Boolean,
    val distortionEnabled: Boolean,
    val perBlockExposure: Boolean,
    val graphSubsetSelection: Boolean,
    val localWarpAlignment: Boolean,
    val perceptualSeamCost: Boolean,
    val movingObjectSeamMask: Boolean,
    val maxInputDimension: Int,
    val maxOutputWidth: Int,
    val unsharpAmount: Float,
)

internal data class HarnessMetrics(
    val coverage: Float,
    val sharpness: Float,
    val seamPenalty: Float,
    val exposureBalance: Float,
    val meanLuma: Float,
    val runtimeMs: Long,
    val pixels: Int,
)

internal data class HarnessResult(
    val config: HarnessConfig,
    val metrics: HarnessMetrics,
    val score: Float,
    val bitmapFile: File,
    val width: Int,
    val height: Int,
    val error: String? = null,
)

internal data class HarnessRunState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val currentLabel: String = "",
    val results: List<HarnessResult> = emptyList(),
    val bestId: String? = null,
    val message: String? = null,
)

internal fun Bitmap.safeRecycle() {
    if (!isRecycled) runCatching { recycle() }
}
