package com.vibecut.pro.engine.color

import com.vibecut.pro.engine.model.*
import kotlin.math.*

/**
 * Baked 3D LUT, 0xFFRRGGBB per lattice point.
 *
 * Indexing is `argbCube[redIndex][greenIndex][blueIndex]`, which is exactly the layout Media3's
 * `SingleColorLut.createFromCube` expects, so the same bake feeds preview and export.
 */
data class CubeLut3D(
    val title: String,
    val size: Int,
    val argbCube: Array<Array<IntArray>>
)

/**
 * The complete CPU color science of ZERO.
 *
 * Two responsibilities:
 *  1. [gradePixelRgb] — the authoritative per-pixel transform. Used to bake 3D LUTs (which are what
 *     the GPU samples), so the shader path and the reference path can never disagree.
 *  2. [resolveEffectiveSpatialConfig] — folds a filter preset's detail parameters (sharpness,
 *     clarity, structure, denoise, vignette, grain) into the finishing-shader uniforms.
 */
object ColorGradingEngine {

    val filterCategories: List<String> = listOf(
        "All", "Natural", "Portrait", "Cinematic", "Film", "Moody", "Retro", "B&W", "Vibrant"
    )

    // ------------------------------------------------------------------ filter library

    private fun preset(
        id: String,
        name: String,
        category: String,
        accent: Long,
        secondary: Long,
        config: ColorGradeConfig
    ) = ColorGradePreset(
        id = id,
        name = name,
        category = category,
        accentHex = accent,
        secondaryHex = secondary,
        config = config
    )

    val builtInPresets: List<ColorGradePreset> = listOf(
        // ---------- Natural
        preset("preset-vivid-natural", "Vivid Natural", "Natural", 0xFF26C6DA, 0xFF004D40,
            ColorGradeConfig(exposure = 0.06f, contrast = 0.12f, vibrance = 0.28f, saturation = 0.08f, highlights = -0.08f, shadows = 0.10f)),
        preset("preset-clean-bright", "Clean Bright", "Natural", 0xFF80DEEA, 0xFF1E3A5F,
            ColorGradeConfig(exposure = 0.18f, brightness = 0.06f, contrast = 0.08f, whites = 0.12f, shadows = 0.16f, vibrance = 0.16f, temperature = -0.04f)),
        preset("preset-crisp-vivid", "Crisp Vivid HD", "Natural", 0xFF00E5FF, 0xFF0D253F,
            ColorGradeConfig(contrast = 0.18f, saturation = 0.16f, vibrance = 0.22f, exposure = 0.08f, highlights = -0.08f, shadows = 0.10f, dehaze = 0.18f, sharpness = 0.55f, clarity = 0.24f)),
        preset("preset-hdr-pop", "HDR Clarity Pop", "Natural", 0xFF00E676, 0xFF1B5E20,
            ColorGradeConfig(contrast = 0.22f, clarity = 0.42f, structure = 0.22f, sharpness = 0.48f, highlights = -0.18f, shadows = 0.22f, vibrance = 0.18f)),

        // ---------- Portrait
        preset("preset-skin-soft", "Soft Skin", "Portrait", 0xFFFFAB91, 0xFF3E2723,
            ColorGradeConfig(brightness = 0.10f, exposure = 0.10f, shadows = 0.20f, contrast = -0.06f, vibrance = 0.10f, denoise = 0.20f, temperature = 0.04f, highlights = -0.10f)),
        preset("preset-warm-portrait", "Warm Portrait", "Portrait", 0xFFFFCC80, 0xFF4E342E,
            ColorGradeConfig(temperature = 0.14f, tint = 0.04f, exposure = 0.08f, vibrance = 0.18f, fade = 0.10f, grain = 0.10f, highlights = -0.12f)),
        preset("preset-editorial-glow", "Editorial Glow", "Portrait", 0xFFFFE0B2, 0xFF37474F,
            ColorGradeConfig(exposure = 0.14f, fade = 0.22f, contrast = 0.10f, vibrance = 0.12f, clarity = -0.12f, grain = 0.14f, temperature = 0.06f, shadows = 0.16f)),
        preset("preset-studio-white", "Studio Clean", "Portrait", 0xFFECEFF1, 0xFF263238,
            ColorGradeConfig(whites = 0.22f, brightness = 0.10f, contrast = 0.06f, saturation = -0.04f, clarity = 0.14f, dehaze = 0.10f)),

        // ---------- Cinematic
        preset("preset-teal-orange", "Teal & Orange", "Cinematic", 0xFFFF8A65, 0xFF004D5A,
            ColorGradeConfig(
                contrast = 0.20f, saturation = 0.08f, highlights = -0.14f, shadows = 0.12f, temperature = 0.08f,
                colorWheels = ColorWheelsConfig(
                    shadows = ColorWheelTint(0f, 0f, 0.16f),
                    midtones = ColorWheelTint(0.04f, 0f, 0f),
                    highlights = ColorWheelTint(0.10f, 0.02f, -0.06f)
                )
            )),
        preset("preset-blockbuster", "Blockbuster", "Cinematic", 0xFFFF6E40, 0xFF0B1E2D,
            ColorGradeConfig(contrast = 0.28f, saturation = 0.12f, exposure = -0.04f, shadows = -0.06f, highlights = -0.20f, blacks = -0.10f, clarity = 0.22f, vibrance = 0.10f, vignette = 0.26f)),
        preset("preset-noir-film", "Noir", "Cinematic", 0xFFB0BEC5, 0xFF102027,
            ColorGradeConfig(contrast = 0.34f, saturation = -1f, clarity = 0.28f, grain = 0.22f, vignette = 0.34f, blacks = -0.14f, highlights = 0.06f)),
        preset("preset-anamorphic", "Anamorphic Blue", "Cinematic", 0xFF4FC3F7, 0xFF06213A,
            ColorGradeConfig(contrast = 0.22f, saturation = 0.06f, temperature = -0.16f, tint = 0.04f, highlights = 0.08f, shadows = 0.10f, vignette = 0.22f, grain = 0.12f)),
        preset("preset-night-blue", "Night Blue", "Cinematic", 0xFF64B5F6, 0xFF0A1626,
            ColorGradeConfig(temperature = -0.24f, exposure = -0.10f, contrast = 0.18f, shadows = -0.10f, blacks = -0.12f, saturation = -0.06f, vignette = 0.30f)),

        // ---------- Film
        preset("preset-kodak-gold", "Kodak Gold", "Film", 0xFFFFB74D, 0xFF4E342E,
            ColorGradeConfig(temperature = 0.12f, vibrance = 0.20f, contrast = 0.10f, grain = 0.20f, fade = 0.12f, highlights = -0.10f, shadows = 0.08f)),
        preset("preset-fuji-cool", "Fuji Cool", "Film", 0xFF81D4FA, 0xFF1A237E,
            ColorGradeConfig(temperature = -0.10f, saturation = -0.06f, vibrance = 0.14f, contrast = 0.08f, grain = 0.16f, fade = 0.10f)),
        preset("preset-cinestill", "CineStill 800T", "Film", 0xFFFF8A80, 0xFF1B1B2F,
            ColorGradeConfig(temperature = -0.18f, tint = 0.06f, exposure = 0.06f, contrast = 0.14f, grain = 0.26f, fade = 0.16f, highlights = -0.06f)),
        preset("preset-expired-film", "Expired Film", "Film", 0xFFBCAAA4, 0xFF3E2723,
            ColorGradeConfig(temperature = 0.16f, tint = -0.10f, fade = 0.34f, contrast = -0.08f, saturation = -0.20f, grain = 0.28f, vignette = 0.18f)),
        preset("preset-35mm", "35mm Classic", "Film", 0xFFD7CCC8, 0xFF263238,
            ColorGradeConfig(contrast = 0.16f, grain = 0.18f, fade = 0.08f, vibrance = 0.12f, clarity = 0.10f)),

        // ---------- Moody
        preset("preset-moody-dark", "Moody Dark", "Moody", 0xFF78909C, 0xFF12161C,
            ColorGradeConfig(exposure = -0.14f, contrast = 0.22f, shadows = -0.18f, blacks = -0.16f, saturation = -0.14f, clarity = 0.18f, vignette = 0.32f)),
        preset("preset-moody-green", "Forest Mood", "Moody", 0xFF81C784, 0xFF1B2A1E,
            ColorGradeConfig(shadows = -0.12f, contrast = 0.18f, saturation = -0.08f,
                hslBands = HslBand.entries.map {
                    when (it) {
                        HslBand.GREEN -> HslBandAdjustment(it, saturationShift = 0.24f, luminanceShift = -0.06f)
                        HslBand.YELLOW -> HslBandAdjustment(it, hueShift = -0.10f)
                        else -> HslBandAdjustment(it)
                    }
                },
                vignette = 0.24f, grain = 0.10f)),
        preset("preset-moody-grey", "Concrete", "Moody", 0xFF90A4AE, 0xFF1C1F22,
            ColorGradeConfig(saturation = -0.42f, contrast = 0.20f, structure = 0.26f, clarity = 0.20f, temperature = -0.06f, fade = 0.08f)),

        // ---------- Retro
        preset("preset-retro-70s", "70s Sun", "Retro", 0xFFFFB300, 0xFF5D4037,
            ColorGradeConfig(temperature = 0.22f, tint = 0.06f, fade = 0.26f, contrast = -0.10f, vibrance = 0.10f, grain = 0.22f, vignette = 0.14f)),
        preset("preset-vhs-retro", "VHS Tape", "Retro", 0xFFFF80AB, 0xFF311B92,
            ColorGradeConfig(contrast = 0.08f, saturation = 0.16f, fade = 0.30f, grain = 0.32f, temperature = 0.08f, tint = -0.06f, denoise = 0.10f)),
        preset("preset-polaroid", "Polaroid", "Retro", 0xFFCFD8DC, 0xFF455A64,
            ColorGradeConfig(fade = 0.30f, saturation = -0.12f, temperature = 0.10f, whites = 0.14f, contrast = -0.06f, grain = 0.18f)),
        preset("preset-old-postcard", "Old Postcard", "Retro", 0xFFD4A574, 0xFF4E342E,
            ColorGradeConfig(saturation = -0.34f, temperature = 0.20f, fade = 0.24f, contrast = 0.06f, grain = 0.20f, vignette = 0.20f)),

        // ---------- B&W
        preset("preset-bw-classic", "B&W Classic", "B&W", 0xFFE0E0E0, 0xFF212121,
            ColorGradeConfig(saturation = -1f, contrast = 0.18f)),
        preset("preset-bw-silver", "Silver Shadow", "B&W", 0xFFCFD8DC, 0xFF1A1A1A,
            ColorGradeConfig(saturation = -1f, contrast = 0.24f, clarity = 0.26f, grain = 0.16f, blacks = -0.14f, vignette = 0.22f)),
        preset("preset-bw-highcontrast", "B&W Hard", "B&W", 0xFFFFFFFF, 0xFF000000,
            ColorGradeConfig(saturation = -1f, contrast = 0.44f, blacks = -0.20f, whites = 0.16f, grain = 0.10f)),

        // ---------- Vibrant
        preset("preset-neon-pop", "Neon Pop", "Vibrant", 0xFF00E5FF, 0xFF12005E,
            ColorGradeConfig(saturation = 0.34f, vibrance = 0.36f, contrast = 0.18f, exposure = 0.06f, temperature = -0.04f, clarity = 0.16f)),
        preset("preset-summer-heat", "Summer Heat", "Vibrant", 0xFFFFC107, 0xFFBF360C,
            ColorGradeConfig(temperature = 0.20f, saturation = 0.22f, vibrance = 0.24f, exposure = 0.08f, highlights = -0.10f)),
        preset("preset-tropical", "Tropical", "Vibrant", 0xFF00E676, 0xFF006064,
            ColorGradeConfig(saturation = 0.24f, vibrance = 0.30f, contrast = 0.12f, temperature = -0.06f, tint = 0.04f, clarity = 0.18f)),
        preset("preset-candy", "Candy Pop", "Vibrant", 0xFFF48FB1, 0xFF4A148C,
            ColorGradeConfig(saturation = 0.30f, exposure = 0.12f, brightness = 0.06f, fade = 0.14f, vibrance = 0.26f, highlights = 0.06f)),

        // ---------- Delivered extras (title cards, log conversion, broadcast)
        preset("preset-log-convert", "Log to Rec.709", "Natural", 0xFF9E9E9E, 0xFF263238,
            ColorGradeConfig(contrast = 0.26f, saturation = 0.18f, gamma = -0.18f, shadows = 0.10f, highlights = -0.06f)),
        preset("preset-broadcast-safe", "Broadcast Safe", "Natural", 0xFFB0BEC5, 0xFF37474F,
            ColorGradeConfig(contrast = 0.10f, saturation = -0.08f, blacks = 0.06f, whites = -0.06f)),
        preset("preset-dreamy-haze", "Dreamy Haze", "Portrait", 0xFFE1BEE7, 0xFF311B92,
            ColorGradeConfig(fade = 0.28f, exposure = 0.10f, contrast = -0.12f, clarity = -0.24f, vibrance = 0.14f, denoise = 0.14f)),
        preset("preset-matrix-green", "Matrix Green", "Cinematic", 0xFF69F0AE, 0xFF05210F,
            ColorGradeConfig(
                contrast = 0.26f, shadows = -0.10f, exposure = -0.04f, saturation = -0.10f,
                hslBands = HslBand.entries.map {
                    if (it == HslBand.GREEN) HslBandAdjustment(it, saturationShift = 0.50f, luminanceShift = 0.08f)
                    else HslBandAdjustment(it, saturationShift = -0.30f)
                },
                vignette = 0.30f
            ))
    )

    fun findPresetById(
        presetId: String?,
        customPresets: List<ColorGradePreset> = emptyList()
    ): ColorGradePreset? {
        if (presetId.isNullOrBlank()) return null
        return customPresets.firstOrNull { it.id == presetId }
            ?: builtInPresets.firstOrNull { it.id == presetId }
    }

    fun presetsInCategory(category: String, customPresets: List<ColorGradePreset> = emptyList()): List<ColorGradePreset> =
        if (category == "All" || category == "Custom") {
            if (category == "Custom") customPresets else builtInPresets + customPresets
        } else {
            (builtInPresets + customPresets).filter { it.category == category }
        }

    // ------------------------------------------------------------------ preset folding

    /**
     * Folds a filter preset's *spatial* parameters (sharpness, clarity, structure, denoise,
     * vignette, grain) into the manual values, scaled by `lutIntensity`, so a preset that promises
     * "HDR clarity" actually sharpens the finishing shader instead of only baking color.
     */
    fun resolveEffectiveSpatialConfig(
        cfg: ColorGradeConfig,
        customPresets: List<ColorGradePreset> = emptyList()
    ): ColorGradeConfig {
        val preset = findPresetById(cfg.lutPresetId, customPresets) ?: return cfg
        val mix = cfg.lutIntensity.coerceIn(0f, 1f)
        if (mix <= 0.001f) return cfg
        val pc = preset.config
        return cfg.copy(
            sharpness = (cfg.sharpness + pc.sharpness * mix).coerceIn(0f, 1f),
            sharpenRadius = if (pc.sharpness > 0.001f) pc.sharpenRadius else cfg.sharpenRadius,
            sharpenThreshold = if (pc.sharpness > 0.001f) pc.sharpenThreshold else cfg.sharpenThreshold,
            clarity = (cfg.clarity + pc.clarity * mix).coerceIn(-1f, 1f),
            structure = (cfg.structure + pc.structure * mix).coerceIn(0f, 1f),
            denoise = (cfg.denoise + pc.denoise * mix).coerceIn(0f, 1f),
            vignette = (cfg.vignette + pc.vignette * mix).coerceIn(-1f, 1f),
            vignetteFeather = if (pc.vignette > 0.001f) pc.vignetteFeather else cfg.vignetteFeather,
            grain = (cfg.grain + pc.grain * mix).coerceIn(0f, 1f)
        )
    }

    /** True when the finishing shader must run for this grade (spatial params after preset folding). */
    fun needsFinishingPass(cfg: ColorGradeConfig, customPresets: List<ColorGradePreset> = emptyList()): Boolean =
        resolveEffectiveSpatialConfig(cfg, customPresets).hasSpatialDetailChanges

    // ------------------------------------------------------------------ tone curves

    /** Piecewise monotone cubic (Fritsch–Carlson style tangents) evaluation of y = f(x). */
    fun evaluateToneCurve(points: List<CurvePoint>, x: Float): Float {
        if (points.isEmpty()) return x.coerceIn(0f, 1f)
        val sorted = points.sortedBy { it.x }
        val clamped = x.coerceIn(0f, 1f)
        if (clamped <= sorted.first().x) return sorted.first().y.coerceIn(0f, 1f)
        if (clamped >= sorted.last().x) return sorted.last().y.coerceIn(0f, 1f)

        val n = sorted.size
        if (n == 2) {
            val t = (clamped - sorted[0].x) / (sorted[1].x - sorted[0].x).coerceAtLeast(1e-4f)
            return (sorted[0].y + (sorted[1].y - sorted[0].y) * t).coerceIn(0f, 1f)
        }

        val dx = FloatArray(n - 1)
        val m = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            dx[i] = (sorted[i + 1].x - sorted[i].x).coerceAtLeast(1e-4f)
            m[i] = (sorted[i + 1].y - sorted[i].y) / dx[i]
        }
        val tangents = FloatArray(n)
        tangents[0] = m[0]
        tangents[n - 1] = m[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = if (m[i - 1] * m[i] <= 0f) 0f else (m[i - 1] + m[i]) * 0.5f
        }

        for (i in 0 until n - 1) {
            if (clamped <= sorted[i + 1].x) {
                val h = dx[i]
                val t = (clamped - sorted[i].x) / h
                val t2 = t * t
                val t3 = t2 * t
                val y = (2f * t3 - 3f * t2 + 1f) * sorted[i].y +
                    (t3 - 2f * t2 + t) * h * tangents[i] +
                    (-2f * t3 + 3f * t2) * sorted[i + 1].y +
                    (t3 - t2) * h * tangents[i + 1]
                return y.coerceIn(0f, 1f)
            }
        }
        return clamped
    }

    // ------------------------------------------------------------------ reference pixel transform

    /**
     * Transforms one normalized RGB pixel through the full grade:
     * preset pass (blended by `lutIntensity`) → manual light/tone/color pass → curves.
     * Spatial parameters (sharpness, clarity, grain, vignette, denoise) are intentionally not
     * applied here — they are neighbourhood operations and live in the finishing shader.
     */
    fun gradePixelRgb(
        rIn: Float,
        gIn: Float,
        bIn: Float,
        cfg: ColorGradeConfig,
        customPresets: List<ColorGradePreset> = emptyList()
    ): FloatArray {
        var r = rIn.coerceIn(0f, 1f)
        var g = gIn.coerceIn(0f, 1f)
        var b = bIn.coerceIn(0f, 1f)

        val activePreset = findPresetById(cfg.lutPresetId, customPresets)
        if (activePreset != null && cfg.lutIntensity > 0.001f) {
            val p = applyAdjustments(r, g, b, activePreset.config)
            val mix = cfg.lutIntensity.coerceIn(0f, 1f)
            r += (p[0] - r) * mix
            g += (p[1] - g) * mix
            b += (p[2] - b) * mix
        }

        val manual = applyAdjustments(r, g, b, cfg.copy(lutPresetId = null))
        return floatArrayOf(manual[0].coerceIn(0f, 1f), manual[1].coerceIn(0f, 1f), manual[2].coerceIn(0f, 1f))
    }

    private fun applyAdjustments(rIn: Float, gIn: Float, bIn: Float, cfg: ColorGradeConfig): FloatArray {
        // 1. Exposure in EV stops
        val expMult = 2.0.pow(cfg.exposure.toDouble()).toFloat()
        var r = (rIn * expMult).coerceIn(0f, 1f)
        var g = (gIn * expMult).coerceIn(0f, 1f)
        var b = (bIn * expMult).coerceIn(0f, 1f)

        // 2. Dehaze / fog
        if (abs(cfg.dehaze) > 0.001f) {
            val minCh = min(r, min(g, b))
            if (cfg.dehaze > 0f) {
                val strength = cfg.dehaze * 0.55f
                val t = (1f - strength * minCh).coerceIn(0.22f, 1f)
                val airlight = 0.88f
                r = ((r - airlight * (1f - t)) / t).coerceIn(0f, 1f)
                g = ((g - airlight * (1f - t)) / t).coerceIn(0f, 1f)
                b = ((b - airlight * (1f - t)) / t).coerceIn(0f, 1f)
            } else {
                val fog = (-cfg.dehaze) * 0.42f
                r = (r + (0.86f - r) * fog).coerceIn(0f, 1f)
                g = (g + (0.88f - g) * fog).coerceIn(0f, 1f)
                b = (b + (0.92f - b) * fog).coerceIn(0f, 1f)
            }
        }

        // 3. White balance
        r = (r + cfg.temperature * 0.15f + cfg.tint * 0.06f).coerceIn(0f, 1f)
        g = (g - cfg.tint * 0.13f).coerceIn(0f, 1f)
        b = (b - cfg.temperature * 0.15f + cfg.tint * 0.06f).coerceIn(0f, 1f)

        // 4. Luminance-masked shadows / highlights / blacks / whites
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val shadowW = (1f - luma) * (1f - luma)
        val highlightW = luma * luma
        val blackW = (1f - luma).pow(4)
        val whiteW = luma.pow(4)
        val tonalShift = cfg.shadows * 0.30f * shadowW +
            cfg.highlights * 0.30f * highlightW +
            cfg.blacks * 0.24f * blackW +
            cfg.whites * 0.24f * whiteW
        r = (r + tonalShift).coerceIn(0f, 1f)
        g = (g + tonalShift).coerceIn(0f, 1f)
        b = (b + tonalShift).coerceIn(0f, 1f)

        // 5. Gamma
        if (abs(cfg.gamma) > 0.001f) {
            val gammaExp = 2.0.pow((-cfg.gamma * 0.65).toDouble()).toFloat()
            r = r.coerceAtLeast(1e-5f).pow(gammaExp).coerceIn(0f, 1f)
            g = g.coerceAtLeast(1e-5f).pow(gammaExp).coerceIn(0f, 1f)
            b = b.coerceAtLeast(1e-5f).pow(gammaExp).coerceIn(0f, 1f)
        }

        // 6. Brightness, contrast, matte fade
        val contrastFactor = (1f + cfg.contrast * 0.85f).coerceAtLeast(0.05f)
        r = ((r - 0.5f) * contrastFactor + 0.5f + cfg.brightness * 0.42f).coerceIn(0f, 1f)
        g = ((g - 0.5f) * contrastFactor + 0.5f + cfg.brightness * 0.42f).coerceIn(0f, 1f)
        b = ((b - 0.5f) * contrastFactor + 0.5f + cfg.brightness * 0.42f).coerceIn(0f, 1f)

        if (cfg.fade > 0.001f) {
            val floorLift = cfg.fade * 0.18f
            r = floorLift + r * (1f - floorLift)
            g = floorLift + g * (1f - floorLift)
            b = floorLift + b * (1f - floorLift)
        }

        // 7. Three-way color wheels
        if (!cfg.colorWheels.isNeutral) {
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val wS = (1f - lum * 2f).coerceIn(0f, 1f)
            val wH = ((lum - 0.5f) * 2f).coerceIn(0f, 1f)
            val wM = (1f - wS - wH).coerceIn(0f, 1f)
            val cw = cfg.colorWheels
            r = (r + (cw.shadows.rOffset * wS + cw.midtones.rOffset * wM + cw.highlights.rOffset * wH) * 0.22f).coerceIn(0f, 1f)
            g = (g + (cw.shadows.gOffset * wS + cw.midtones.gOffset * wM + cw.highlights.gOffset * wH) * 0.22f).coerceIn(0f, 1f)
            b = (b + (cw.shadows.bOffset * wS + cw.midtones.bOffset * wM + cw.highlights.bOffset * wH) * 0.22f).coerceIn(0f, 1f)
        }

        // 8. Vibrance (skin-protected)
        if (abs(cfg.vibrance) > 0.001f) {
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val currentSat = maxC - minC
            val avg = (r + g + b) / 3f
            val skinProtect = if (r > g && g > b) 0.55f else 1.0f
            val vibBoost = cfg.vibrance * (1f - currentSat) * skinProtect * 1.15f
            r = (avg + (r - avg) * (1f + vibBoost)).coerceIn(0f, 1f)
            g = (avg + (g - avg) * (1f + vibBoost)).coerceIn(0f, 1f)
            b = (avg + (b - avg) * (1f + vibBoost)).coerceIn(0f, 1f)
        }

        // 9. Saturation, hue shift, 8-band HSL
        val hasHsl = cfg.hslBands.any { !it.isNeutral }
        if (abs(cfg.saturation) > 0.001f || abs(cfg.hueShift) > 0.001f || hasHsl) {
            val hsl = rgbToHsl(r, g, b)
            var hue = (hsl[0] + cfg.hueShift * 180f + 360f) % 360f
            var sat = hsl[1]
            var light = hsl[2]

            if (hasHsl && sat > 0.02f) {
                for (adj in cfg.hslBands) {
                    if (adj.isNeutral) continue
                    val dist = angularDistanceDeg(hue, adj.band.centerHueDeg)
                    val influence = (1f - dist / 45f).coerceIn(0f, 1f)
                    if (influence > 0f) {
                        hue = (hue + adj.hueShift * 30f * influence + 360f) % 360f
                        sat = (sat * (1f + adj.saturationShift * influence)).coerceIn(0f, 1f)
                        light = (light + adj.luminanceShift * 0.25f * influence).coerceIn(0f, 1f)
                    }
                }
            }
            sat = (sat * (1f + cfg.saturation)).coerceIn(0f, 1f)
            val out = hslToRgb(hue, sat, light)
            r = out[0]
            g = out[1]
            b = out[2]
        }

        // 10. RGB + master curves
        if (!cfg.curves.isIdentity) {
            r = evaluateToneCurve(cfg.curves.red, evaluateToneCurve(cfg.curves.master, r))
            g = evaluateToneCurve(cfg.curves.green, evaluateToneCurve(cfg.curves.master, g))
            b = evaluateToneCurve(cfg.curves.blue, evaluateToneCurve(cfg.curves.master, b))
        }

        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    // ------------------------------------------------------------------ LUT baking

    /**
     * Bakes [cfg] into a 3D LUT of edge length [size] (17³ ≈ 4.9k samples by default).
     * [mixIntensity] blends the baked result back toward identity.
     */
    fun bakeTo3dCube(
        cfg: ColorGradeConfig,
        size: Int = 17,
        mixIntensity: Float = 1.0f,
        customPresets: List<ColorGradePreset> = emptyList()
    ): CubeLut3D {
        val n = size.coerceIn(2, 65)
        val mix = mixIntensity.coerceIn(0f, 1f)
        val cube = Array(n) { rIdx ->
            Array(n) { gIdx ->
                IntArray(n) { bIdx ->
                    val r0 = rIdx.toFloat() / (n - 1)
                    val g0 = gIdx.toFloat() / (n - 1)
                    val b0 = bIdx.toFloat() / (n - 1)
                    val graded = gradePixelRgb(r0, g0, b0, cfg, customPresets)
                    pack(r0 + (graded[0] - r0) * mix, g0 + (graded[1] - g0) * mix, b0 + (graded[2] - b0) * mix)
                }
            }
        }
        return CubeLut3D(title = "VibeCutGrade", size = n, argbCube = cube)
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** Parses an Adobe / Resolve `.cube` 3D LUT. Throws [IllegalArgumentException] on bad data. */
    fun parseAdobeCubeLut(content: String, lutIntensity: Float = 1.0f): CubeLut3D {
        var title = "Custom LUT"
        var size = 0
        val triplets = ArrayList<FloatArray>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.startsWith("TITLE", ignoreCase = true) ->
                    title = line.substringAfter(" ").trim().trim('"').ifBlank { "Custom LUT" }
                line.startsWith("LUT_3D_SIZE", ignoreCase = true) ->
                    size = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
                line.startsWith("DOMAIN_", ignoreCase = true) ||
                    line.startsWith("LUT_1D_", ignoreCase = true) -> Unit
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) triplets.add(floatArrayOf(r, g, b))
                    }
                }
            }
        }

        require(size in 2..65) { "Invalid or missing LUT_3D_SIZE in .cube file" }
        val expected = size * size * size
        require(triplets.size >= expected) {
            "Incomplete .cube data: expected $expected entries, found ${triplets.size}"
        }

        val mix = lutIntensity.coerceIn(0f, 1f)
        val cube = Array(size) { Array(size) { IntArray(size) } }
        var idx = 0
        // .cube files are ordered blue-slowest: r fastest, then g, then b.
        for (bIdx in 0 until size) {
            for (gIdx in 0 until size) {
                for (rIdx in 0 until size) {
                    val rgb = triplets[idx++]
                    val r0 = rIdx.toFloat() / (size - 1)
                    val g0 = gIdx.toFloat() / (size - 1)
                    val b0 = bIdx.toFloat() / (size - 1)
                    cube[rIdx][gIdx][bIdx] = pack(
                        r0 + (rgb[0] - r0) * mix,
                        g0 + (rgb[1] - g0) * mix,
                        b0 + (rgb[2] - b0) * mix
                    )
                }
            }
        }
        return CubeLut3D(title = title, size = size, argbCube = cube)
    }

    // ------------------------------------------------------------------ helpers

    /** Neutral-grade check used by the UI to show a "graded" badge on clips. */
    fun describeGrade(cfg: ColorGradeConfig, customPresets: List<ColorGradePreset> = emptyList()): String {
        if (cfg.isNeutral && cfg.customCubeLutUri.isNullOrBlank()) return "Original"
        val presetName = findPresetById(cfg.lutPresetId, customPresets)?.name
        val count = cfg.activeAdjustCount
        return when {
            presetName != null && count > 1 -> "$presetName +$count"
            presetName != null -> presetName
            count > 0 -> "$count adjustments"
            else -> "Custom LUT"
        }
    }

    private fun angularDistanceDeg(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) * 0.5f
        if (maxC - minC < 1e-6f) return floatArrayOf(0f, 0f, l)
        val d = maxC - minC
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
            g -> ((b - r) / d + 2f) * 60f
            else -> ((r - g) / d + 4f) * 60f
        }
        return floatArrayOf(h % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    private fun hslToRgb(hIn: Float, s: Float, l: Float): FloatArray {
        val h = ((hIn % 360f) + 360f) % 360f
        if (s <= 1e-6f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hk = h / 360f
        return floatArrayOf(
            hueToChannel(p, q, hk + 1f / 3f),
            hueToChannel(p, q, hk),
            hueToChannel(p, q, hk - 1f / 3f)
        )
    }

    private fun hueToChannel(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }.coerceIn(0f, 1f)
    }
}
