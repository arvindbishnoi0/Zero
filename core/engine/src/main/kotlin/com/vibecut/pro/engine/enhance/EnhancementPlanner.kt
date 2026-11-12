package com.vibecut.pro.engine.enhance

import com.vibecut.pro.engine.model.AspectRatioPreset
import com.vibecut.pro.engine.model.EnhancementConfig
import com.vibecut.pro.engine.model.UpscaleEngine
import kotlin.math.max
import kotlin.math.min

/**
 * The concrete GPU plan for the "4K / video enhancer" module.
 *
 * IMPORTANT PRODUCT HONESTY: ZERO does not bundle an AI super-resolution model. The enhancer is a
 * real, high-quality conventional pipeline — Lanczos-3 resampling on the GPU, edge-preserving
 * denoise, unsharp detail recovery and tone correction — applied the same way in preview and
 * export. [EnhancementConfig.engine] and the UI state this explicitly. Resampling cannot invent
 * detail that the source does not contain; the plan reports how much of the output is genuine
 * source detail ([realDetailRatio]) so the UI never claims "true 4K".
 */
data class EnhancementPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val upscaleFactor: Float,
    val engine: UpscaleEngine,
    val isAiEnhanced: Boolean,
    /** Fraction of the output resolution that comes from real captured detail (<= 1.0). */
    val realDetailRatio: Float,
    val needsUpscale: Boolean,
    val sharpen: Float,
    val denoise: Float,
    val contrastBoost: Float,
    val colorBoost: Float,
    val detailPreserve: Float,
    val brightnessFix: Float,
    val costNote: String
) {
    val upscaleLabel: String
        get() = if (!needsUpscale) {
            "No resampling (source already ${sourceWidth}x$sourceHeight)"
        } else {
            String.format(java.util.Locale.US, "%.2fx Lanczos-3 upscale", upscaleFactor)
        }

    val detailHonestyLabel: String
        get() = if (!needsUpscale) {
            "Native resolution — full detail preserved"
        } else {
            val percent = (realDetailRatio * 100f).toInt().coerceIn(0, 100)
            "~$percent% of output detail is real; the rest is interpolated (not AI-generated)"
        }
}

object EnhancementPlanner {

    /** Output heights offered by the enhancer / exporter. */
    val supportedHeights: List<Int> = listOf(720, 1080, 1440, 2160)

    fun plan(
        config: EnhancementConfig,
        aspect: AspectRatioPreset,
        customRatio: Float,
        sourceWidth: Int,
        sourceHeight: Int
    ): EnhancementPlan {
        val targetShortSide = config.targetHeight.coerceIn(240, 2160)
        val (outW, outH) = AspectRatioPreset.dimensionsForProject(aspect, targetShortSide, customRatio)
        val safeSourceW = max(2, sourceWidth)
        val safeSourceH = max(2, sourceHeight)
        // Compare by the area actually covered after fit-cropping to the project aspect.
        val srcArea = safeSourceW.toDouble() * safeSourceH.toDouble()
        val outArea = outW.toDouble() * outH.toDouble()
        val linearFactor = Math.sqrt(outArea / max(1.0, srcArea)).toFloat()
        val needsUpscale = linearFactor > 1.01f
        // Real detail ratio: how much of the output raster is backed by source pixels.
        val realDetail = min(1f, 1f / max(1f, linearFactor * linearFactor))
        val costNote = when {
            config.targetHeight >= 2160 -> "4K render: high memory/GPU load; keep the app in the foreground."
            config.targetHeight >= 1440 -> "2K render: moderate load."
            else -> "Standard render."
        }
        return EnhancementPlan(
            sourceWidth = safeSourceW,
            sourceHeight = safeSourceH,
            outputWidth = outW,
            outputHeight = outH,
            upscaleFactor = linearFactor,
            engine = config.engine,
            isAiEnhanced = false,
            realDetailRatio = realDetail,
            needsUpscale = needsUpscale,
            sharpen = if (config.enabled) config.sharpen.coerceIn(0f, 1f) else 0f,
            denoise = if (config.enabled) config.denoise.coerceIn(0f, 1f) else 0f,
            contrastBoost = if (config.enabled) config.contrastBoost.coerceIn(-1f, 1f) else 0f,
            colorBoost = if (config.enabled) config.colorBoost.coerceIn(-1f, 1f) else 0f,
            detailPreserve = if (config.enabled) config.detailPreserve.coerceIn(0f, 1f) else 0f,
            brightnessFix = if (config.enabled) config.brightnessFix.coerceIn(-1f, 1f) else 0f,
            costNote = costNote
        )
    }

    /**
     * Memory estimate (bytes) for one RGBA preview/export frame at the planned resolution,
     * used to pick a safe proxy resolution on low-memory devices before allocating textures.
     */
    fun estimatedFrameBytes(plan: EnhancementPlan): Long = plan.outputWidth.toLong() * plan.outputHeight.toLong() * 4L

    fun safeTargetHeight(plan: EnhancementPlan, availableMemoryBytes: Long): Int {
        var height = plan.outputHeight
        val (w, h) = plan.outputWidth to plan.outputHeight
        val needed = w.toLong() * h.toLong() * 4L * 4L // pipeline keeps a few intermediate textures
        if (needed > availableMemoryBytes) {
            // Step down through the supported ladder until it fits.
            val ladder = supportedHeights.reversed()
            for (candidate in ladder) {
                val (cw, ch) = AspectRatioPreset.dimensionsForProject(
                    AspectRatioPreset.CUSTOM,
                    candidate,
                    plan.outputWidth.toFloat() / plan.outputHeight
                )
                if (cw.toLong() * ch.toLong() * 16L <= availableMemoryBytes) {
                    height = ch
                    break
                }
            }
        }
        return height
    }
}
