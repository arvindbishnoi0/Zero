package com.vibecut.pro.engine.model

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * ZERO — core editor model.
 *
 * This module is pure Kotlin (no Android APIs) so the whole editing contract — timeline math,
 * speed mapping, export dimensions, enhancement plans — is unit-testable on the JVM.
 */

enum class AspectRatioPreset(
    val id: String,
    val label: String,
    val subtitle: String,
    val widthRatio: Int,
    val heightRatio: Int
) {
    REELS_9_16("9:16", "9:16", "Reels / Shorts", 9, 16),
    YOUTUBE_16_9("16:9", "16:9", "YouTube / Widescreen", 16, 9),
    SQUARE_1_1("1:1", "1:1", "Square Feed", 1, 1),
    PORTRAIT_4_5("4:5", "4:5", "Instagram Portrait", 4, 5),
    CLASSIC_4_3("4:3", "4:3", "Classic", 4, 3),
    CINEMA_21_9("21:9", "21:9", "Anamorphic", 21, 9),
    CUSTOM("custom", "Custom", "Custom ratio", 9, 16);

    val ratioFloat: Float get() = widthRatio.toFloat() / heightRatio.toFloat()

    /** Short side is the smaller dimension, capped by the preset ratio. */
    fun computeDimensions(shortSidePx: Int): Pair<Int, Int> {
        val shortSide = max(2, shortSidePx)
        return if (widthRatio <= heightRatio) {
            val w = makeEven(shortSide)
            val h = makeEven((shortSide.toDouble() * heightRatio / widthRatio).toInt())
            w to h
        } else {
            val h = makeEven(shortSide)
            val w = makeEven((shortSide.toDouble() * widthRatio / heightRatio).toInt())
            w to h
        }
    }

    /** Dimensions for a preset, honouring the project's custom ratio when this is [CUSTOM]. */
    fun computeDimensionsFor(shortSidePx: Int, customRatio: Float): Pair<Int, Int> {
        if (this != CUSTOM) return computeDimensions(shortSidePx)
        val ratio = sanitizeCustomRatio(customRatio)
        val shortSide = max(2, shortSidePx)
        return if (ratio <= 1f) {
            makeEven(shortSide) to makeEven((shortSide / ratio).toInt())
        } else {
            makeEven((shortSide * ratio).toInt()) to makeEven(shortSide)
        }
    }

    /**
     * Hardware MediaCodec encoders reject non-macroblock-aligned dimensions on many devices,
     * so every export resolution is snapped to a multiple of [alignment] (16px).
     */
    fun computeCodecSafeDimensions(shortSidePx: Int, alignment: Int = 16): Pair<Int, Int> {
        val (rawW, rawH) = computeDimensions(shortSidePx)
        return alignToCodec(rawW, alignment) to alignToCodec(rawH, alignment)
    }

    companion object {
        private fun makeEven(v: Int): Int = if (v % 2 == 0) max(2, v) else max(2, v + 1)

        fun sanitizeCustomRatio(ratio: Float): Float =
            if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(0.25f, 4f) else 9f / 16f

        fun alignToCodec(value: Int, alignment: Int = 16): Int {
            val step = max(2, alignment)
            val rounded = ((value + step / 2) / step) * step
            return max(step, rounded)
        }

        /** Exact dimensions for the project aspect (including CUSTOM) with codec-safe alignment. */
        fun dimensionsForProject(preset: AspectRatioPreset, shortSidePx: Int, customRatio: Float): Pair<Int, Int> {
            val (rawW, rawH) = preset.computeDimensionsFor(shortSidePx, customRatio)
            return alignToCodec(rawW) to alignToCodec(rawH)
        }

        fun fromId(id: String): AspectRatioPreset =
            entries.firstOrNull { it.id == id } ?: REELS_9_16
    }
}

enum class TrackType(val label: String, val isVisual: Boolean, val isAudio: Boolean) {
    VIDEO_MAIN("Main Video", isVisual = true, isAudio = true),
    VIDEO_PIP("Overlay / PIP", isVisual = true, isAudio = true),
    TEXT("Text", isVisual = true, isAudio = false),
    STICKER("Stickers", isVisual = true, isAudio = false),
    AUDIO_MUSIC("Music", isVisual = false, isAudio = true),
    AUDIO_VOICEOVER("Voiceover", isVisual = false, isAudio = true),
    AUDIO_SFX("Sound FX", isVisual = false, isAudio = true);

    val isAudioTrack: Boolean get() = isAudio
    val isVisualTrack: Boolean get() = isVisual
}

enum class MediaKind {
    VIDEO,
    IMAGE,
    AUDIO,
    GENERATED_SFX,
    TEXT_ELEMENT,
    STICKER_ELEMENT
}

data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
    val aspectPresetId: String = "FREE"
) {
    val widthFraction: Float get() = (right - left).coerceIn(0.05f, 1f)
    val heightFraction: Float get() = (bottom - top).coerceIn(0.05f, 1f)
    val isIdentity: Boolean
        get() = abs(left) < 0.001f && abs(top) < 0.001f &&
            abs(right - 1f) < 0.001f && abs(bottom - 1f) < 0.001f

    fun sanitized(): CropRect {
        val l = left.coerceIn(0f, 0.94f)
        val t = top.coerceIn(0f, 0.94f)
        return copy(
            left = l,
            top = t,
            right = right.coerceIn(l + 0.05f, 1f),
            bottom = bottom.coerceIn(t + 0.05f, 1f)
        )
    }
}

/** Real OpenGL blend modes used for overlay / PIP clips in both preview and export. */
enum class BlendMode(val label: String, val glCode: Int) {
    NORMAL("Normal", 0),
    SCREEN("Screen", 1),
    MULTIPLY("Multiply", 2),
    OVERLAY("Overlay", 3),
    ADD("Add", 4),
    DARKEN("Darken", 5),
    LIGHTEN("Lighten", 6);

    companion object {
        fun fromName(name: String?): BlendMode =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * Chroma key / green screen. Implemented in the GPU finishing shader (preview + export).
 */
data class ChromaKeyConfig(
    val enabled: Boolean = false,
    /** Sample hue in degrees; 120° = green screen. */
    val hueDeg: Float = 120f,
    /** Half-width of the keyed hue band in degrees. */
    val toleranceDeg: Float = 32f,
    /** Feather/softness applied at the edge of the keyed band. */
    val softness: Float = 0.18f,
    /** Minimum saturation for a pixel to be considered keyed. */
    val minSaturation: Float = 0.22f,
    /** Amount of keying, 0..1. */
    val amount: Float = 1f,
    val spillSuppression: Float = 0.45f
) {
    val isActive: Boolean get() = enabled && amount > 0.01f
}

data class ClipTransform(
    val positionX: Float = 0f,       // normalized [-1, 1]
    val positionY: Float = 0f,       // normalized [-1, 1]
    val scaleX: Float = 1f,          // [0.05, 6]
    val scaleY: Float = 1f,
    val rotationDegrees: Float = 0f, // [-360, 360]
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val opacity: Float = 1f,         // [0, 1]
    val blendMode: BlendMode = BlendMode.NORMAL
) {
    val isIdentity: Boolean
        get() = abs(positionX) < 0.001f &&
            abs(positionY) < 0.001f &&
            abs(scaleX - 1f) < 0.001f &&
            abs(scaleY - 1f) < 0.001f &&
            abs(rotationDegrees % 360f) < 0.01f &&
            !flipHorizontal && !flipVertical &&
            abs(opacity - 1f) < 0.001f &&
            blendMode == BlendMode.NORMAL
}

enum class SpeedCurvePreset(val label: String, val defaultPoints: List<SpeedPoint>) {
    NONE("Constant", emptyList()),
    MONTAGE(
        "Montage",
        listOf(
            SpeedPoint(0.0f, 0.6f),
            SpeedPoint(0.25f, 1.8f),
            SpeedPoint(0.55f, 0.6f),
            SpeedPoint(0.8f, 1.6f),
            SpeedPoint(1.0f, 1.0f)
        )
    ),
    HERO(
        "Hero",
        listOf(
            SpeedPoint(0.0f, 1.3f),
            SpeedPoint(0.35f, 1.9f),
            SpeedPoint(0.5f, 0.35f),
            SpeedPoint(0.7f, 0.35f),
            SpeedPoint(1.0f, 1.3f)
        )
    ),
    BULLET(
        "Bullet Slow-Mo",
        listOf(
            SpeedPoint(0.0f, 1.6f),
            SpeedPoint(0.45f, 0.25f),
            SpeedPoint(0.55f, 0.25f),
            SpeedPoint(1.0f, 1.6f)
        )
    ),
    FLASH_IN(
        "Flash In",
        listOf(
            SpeedPoint(0.0f, 2.5f),
            SpeedPoint(0.25f, 1.1f),
            SpeedPoint(1.0f, 1.0f)
        )
    ),
    FLASH_OUT(
        "Flash Out",
        listOf(
            SpeedPoint(0.0f, 1.0f),
            SpeedPoint(0.75f, 1.1f),
            SpeedPoint(1.0f, 2.5f)
        )
    );

    companion object {
        const val MIN_SPEED = 0.1f
        const val MAX_SPEED = 4.0f
    }
}

data class SpeedPoint(
    val normalizedPosition: Float, // [0, 1] inside the trimmed source span
    val speedMultiplier: Float
)

enum class EffectCategory(val label: String) {
    BLUR("Blur"),
    SHARPEN("Sharpen"),
    LIGHT("Light & Glow"),
    RETRO("Retro & Glitch"),
    CAMERA("Camera"),
    LENS("Lens & Stylize"),
    COLOR("Color FX")
}

/**
 * Every entry here has a real OpenGL ES 2.0 fragment shader in [com.vibecut.pro.engine.effects.GlslShaderGenerator]
 * and is rendered identically in live preview and export.
 *
 * Parameters: [primaryLabel] (intensity), [secondaryLabel], [tertiaryLabel] — all 0..1.
 */
enum class EffectType(
    val label: String,
    val category: EffectCategory,
    val defaultIntensity: Float = 0.6f,
    val primaryLabel: String = "Strength",
    val secondaryLabel: String = "Detail",
    val defaultSecondary: Float = 0.5f,
    val tertiaryLabel: String = "Fine Tune",
    val defaultTertiary: Float = 0.35f,
    val badgeColorHex: Long = 0xFF00E5FF
) {
    // --- Blur ---
    BLUR("Box Blur", EffectCategory.BLUR, 0.5f, "Blur", "Spread", 0.5f, "Mix", 0.8f, 0xFF448AFF),
    GAUSSIAN_BLUR("Gaussian Blur", EffectCategory.BLUR, 0.55f, "Blur", "Sigma", 0.6f, "Warmth", 0.5f, 0xFF2979FF),
    MOTION_BLUR("Motion Blur", EffectCategory.BLUR, 0.6f, "Streak", "Angle", 0.25f, "Center Keep", 0.2f, 0xFF536DFE),
    RADIAL_ZOOM_BLUR("Radial Zoom Blur", EffectCategory.BLUR, 0.55f, "Zoom Streak", "Safe Zone", 0.35f, "Swirl", 0.25f, 0xFF7C4DFF),
    BACKGROUND_BLUR("Background Blur", EffectCategory.BLUR, 0.7f, "Bokeh", "Subject Size", 0.45f, "Focus Y", 0.54f, 0xFF26C6DA),

    // --- Sharpen ---
    SHARPEN("Sharpen", EffectCategory.SHARPEN, 0.6f, "Sharpness", "Radius", 0.45f, "Noise Guard", 0.25f, 0xFF00E5FF),
    UNSHARP_PRO("HDR Structure", EffectCategory.SHARPEN, 0.55f, "Structure", "Radius", 0.5f, "Halo Clamp", 0.4f, 0xFF18FFFF),
    EDGE_SHARPEN("Edge Clarifier", EffectCategory.SHARPEN, 0.65f, "Edge Crisp", "Threshold", 0.35f, "Skin Smooth", 0.3f, 0xFF00B8D4),

    // --- Light & Glow ---
    GLOW("Bloom Glow", EffectCategory.LIGHT, 0.55f, "Glow", "Threshold", 0.55f, "Spread", 0.5f, 0xFFFFD740),
    HALATION("Film Halation", EffectCategory.LIGHT, 0.6f, "Halation", "Spread", 0.55f, "Threshold", 0.6f, 0xFFFF5252),
    LIGHT_LEAKS("Light Leak", EffectCategory.LIGHT, 0.6f, "Leak", "Amber / Rose", 0.7f, "Drift", 0.5f, 0xFFFF6E40),
    FLASH("Strobe Flash", EffectCategory.LIGHT, 0.5f, "Flash", "Rate", 0.5f, "Tint", 0.5f, 0xFFFFFFFF),

    // --- Retro & Glitch ---
    GLITCH("Glitch", EffectCategory.RETRO, 0.6f, "Tear", "Slice Speed", 0.55f, "RGB Offset", 0.55f, 0xFFFF1744),
    RGB_SPLIT("RGB Split", EffectCategory.RETRO, 0.55f, "Separation", "Angle", 0.15f, "Edge Only", 0.25f, 0xFFFF4081),
    VHS("VHS Tape", EffectCategory.RETRO, 0.65f, "Tape Wear", "Tracking", 0.5f, "Color Bleed", 0.55f, 0xFF7C4DFF),
    SCANLINES_CRT("CRT Monitor", EffectCategory.RETRO, 0.55f, "Scanlines", "Density", 0.55f, "Curvature", 0.35f, 0xFF00E676),
    PIXELATE("Pixelate", EffectCategory.RETRO, 0.55f, "Pixel Size", "Color Crunch", 0.3f, "Grid", 0.15f, 0xFFFFAB00),
    FILM_GRAIN("Film Grain", EffectCategory.RETRO, 0.45f, "Grain", "Grain Size", 0.5f, "Shadow Bias", 0.6f, 0xFFA1887F),
    OLD_FILM("Old Film", EffectCategory.RETRO, 0.65f, "Vintage", "Scratches", 0.5f, "Flicker", 0.5f, 0xFFD7CCC8),
    NOISE("Noise", EffectCategory.RETRO, 0.35f, "Noise", "Scale", 0.5f, "Monochrome", 1.0f, 0xFF90A4AE),

    // --- Camera ---
    SHAKE("Camera Shake", EffectCategory.CAMERA, 0.5f, "Power", "Frequency", 0.6f, "Roll", 0.35f, 0xFFFF5252),
    ZOOM("Beat Zoom", EffectCategory.CAMERA, 0.5f, "Depth", "Speed", 0.5f, "Smoothing", 0.5f, 0xFF69F0AE),
    MIRROR_KALEIDOSCOPE("Mirror", EffectCategory.CAMERA, 0.85f, "Mirror Mix", "Mode", 0.35f, "Center", 0.5f, 0xFF00E5FF),

    // --- Lens & Stylize ---
    CHROMATIC_ABERRATION("Lens Fringe", EffectCategory.LENS, 0.55f, "Prism Shift", "Edge Falloff", 0.5f, "Radial Zoom", 0.35f, 0xFF18FFFF),
    VIGNETTE("Vignette", EffectCategory.LENS, 0.55f, "Darkness", "Roundness", 0.6f, "Feather", 0.55f, 0xFF90A4AE),
    LENS_DISTORTION("Fisheye / Lens", EffectCategory.LENS, 0.5f, "Warp", "Barrel / Pinch", 0.75f, "Edge Fill", 0.5f, 0xFF40C4FF),
    WAVE_DISTORTION("Wave Distortion", EffectCategory.LENS, 0.5f, "Amplitude", "Frequency", 0.5f, "Speed", 0.45f, 0xFF7C4DFF),
    POSTERIZE_POP("Posterize", EffectCategory.LENS, 0.6f, "Mix", "Tone Steps", 0.4f, "Saturation", 0.55f, 0xFFFFEA00),
    CARTOON("Cartoon", EffectCategory.LENS, 0.7f, "Cel", "Color Levels", 0.55f, "Ink", 0.5f, 0xFFFF9100),

    // --- Color FX ---
    BLACK_AND_WHITE("Black & White", EffectCategory.COLOR, 0.95f, "Mix", "Contrast", 0.45f, "Tint", 0.5f, 0xFFB0BEC5),
    INVERT("Color Invert", EffectCategory.COLOR, 1.0f, "Mix", "Channel Balance", 0.5f, "Luma Keep", 0.2f, 0xFFE040FB),
    EDGE_DETECTION("Edge Detect", EffectCategory.COLOR, 0.7f, "Mix", "Threshold", 0.35f, "Invert", 0f, 0xFF00E5FF),
    DUOTONE("Duotone", EffectCategory.COLOR, 0.8f, "Mix", "Palette", 0.25f, "Contrast", 0.5f, 0xFFE040FB),
    CINEMATIC("Cinematic Teal", EffectCategory.COLOR, 0.7f, "Tone", "Letterbox", 0.0f, "Contrast", 0.5f, 0xFFFFAB00);

    val hasLetterbox: Boolean get() = this == CINEMATIC

    companion object {
        fun fromName(name: String?): EffectType? = entries.firstOrNull { it.name == name }
    }
}

data class AppliedEffect(
    val id: String = UUID.randomUUID().toString(),
    val type: EffectType,
    val intensity: Float = type.defaultIntensity,
    val secondaryParam: Float = type.defaultSecondary,
    val tertiaryParam: Float = type.defaultTertiary,
    val enabled: Boolean = true
)

enum class HslBand(val label: String, val centerHueDeg: Float, val colorHex: Long) {
    RED("Red", 0f, 0xFFFF5252),
    ORANGE("Orange", 30f, 0xFFFF9800),
    YELLOW("Yellow", 60f, 0xFFFFEB3B),
    GREEN("Green", 120f, 0xFF4CAF50),
    AQUA("Aqua", 180f, 0xFF00BCD4),
    BLUE("Blue", 240f, 0xFF2196F3),
    PURPLE("Purple", 275f, 0xFF9C27B0),
    MAGENTA("Magenta", 315f, 0xFFE91E63)
}

data class HslBandAdjustment(
    val band: HslBand,
    val hueShift: Float = 0f,        // [-1, 1] -> +/- 30 degrees
    val saturationShift: Float = 0f, // [-1, 1]
    val luminanceShift: Float = 0f   // [-1, 1]
) {
    val isNeutral: Boolean
        get() = abs(hueShift) < 0.001f && abs(saturationShift) < 0.001f && abs(luminanceShift) < 0.001f
}

data class CurvePoint(val x: Float, val y: Float)

data class RgbCurvesConfig(
    val master: List<CurvePoint> = defaultCurve(),
    val red: List<CurvePoint> = defaultCurve(),
    val green: List<CurvePoint> = defaultCurve(),
    val blue: List<CurvePoint> = defaultCurve()
) {
    val isIdentity: Boolean
        get() = isDefault(master) && isDefault(red) && isDefault(green) && isDefault(blue)

    companion object {
        fun defaultCurve(): List<CurvePoint> = listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.25f, 0.25f),
            CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.75f),
            CurvePoint(1f, 1f)
        )

        fun sContrastCurve(): List<CurvePoint> = listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.25f, 0.18f),
            CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.82f),
            CurvePoint(1f, 1f)
        )

        fun matteFadeCurve(): List<CurvePoint> = listOf(
            CurvePoint(0f, 0.09f),
            CurvePoint(0.25f, 0.24f),
            CurvePoint(0.5f, 0.5f),
            CurvePoint(0.75f, 0.77f),
            CurvePoint(1f, 0.96f)
        )

        private fun isDefault(pts: List<CurvePoint>): Boolean =
            pts.all { abs(it.x - it.y) < 0.004f }
    }
}

data class ColorWheelTint(
    val rOffset: Float = 0f, // [-1, 1]
    val gOffset: Float = 0f,
    val bOffset: Float = 0f
) {
    val isNeutral: Boolean
        get() = abs(rOffset) < 0.001f && abs(gOffset) < 0.001f && abs(bOffset) < 0.001f
}

data class ColorWheelsConfig(
    val shadows: ColorWheelTint = ColorWheelTint(),
    val midtones: ColorWheelTint = ColorWheelTint(),
    val highlights: ColorWheelTint = ColorWheelTint()
) {
    val isNeutral: Boolean get() = shadows.isNeutral && midtones.isNeutral && highlights.isNeutral
    val isIdentity: Boolean get() = isNeutral
}

/**
 * Complete color grade. Every field is a real parameter of the GPU color pipeline
 * (baked 3D LUT for color + finishing shader for spatial detail) in preview AND export.
 */
data class ColorGradeConfig(
    // Light & tone
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val exposure: Float = 0f,       // EV stops, [-2, 2]
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val hueShift: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val gamma: Float = 0f,
    // Detail & optics
    val sharpness: Float = 0f,
    val sharpenRadius: Float = 0.35f,
    val sharpenThreshold: Float = 0.12f,
    val clarity: Float = 0f,
    val structure: Float = 0f,
    val dehaze: Float = 0f,
    val denoise: Float = 0f,
    val vignette: Float = 0f,
    val vignetteFeather: Float = 0.5f,
    val grain: Float = 0f,
    val fade: Float = 0f,
    // Pro grading
    val hslBands: List<HslBandAdjustment> = HslBand.entries.map { HslBandAdjustment(it) },
    val curves: RgbCurvesConfig = RgbCurvesConfig(),
    val colorWheels: ColorWheelsConfig = ColorWheelsConfig(),
    val lutPresetId: String? = null,
    val customCubeLutUri: String? = null,
    val lutIntensity: Float = 1.0f
) {
    val hasColorLutChanges: Boolean
        get() = abs(brightness) >= 0.001f ||
            abs(contrast) >= 0.001f ||
            abs(saturation) >= 0.001f ||
            abs(vibrance) >= 0.001f ||
            abs(exposure) >= 0.001f ||
            abs(temperature) >= 0.001f ||
            abs(tint) >= 0.001f ||
            abs(hueShift) >= 0.001f ||
            abs(highlights) >= 0.001f ||
            abs(shadows) >= 0.001f ||
            abs(whites) >= 0.001f ||
            abs(blacks) >= 0.001f ||
            abs(gamma) >= 0.001f ||
            abs(dehaze) >= 0.001f ||
            abs(fade) >= 0.001f ||
            hslBands.any { !it.isNeutral } ||
            !curves.isIdentity ||
            !colorWheels.isNeutral ||
            (lutPresetId != null && lutIntensity > 0.001f) ||
            (!customCubeLutUri.isNullOrBlank() && lutIntensity > 0.001f)

    val hasSpatialDetailChanges: Boolean
        get() = sharpness > 0.003f ||
            abs(clarity) >= 0.003f ||
            structure > 0.003f ||
            denoise > 0.003f ||
            abs(vignette) >= 0.003f ||
            grain > 0.003f

    val hasActiveFilter: Boolean
        get() = (lutPresetId != null || !customCubeLutUri.isNullOrBlank()) && lutIntensity > 0.005f

    val isNeutral: Boolean
        get() = !hasColorLutChanges && !hasSpatialDetailChanges &&
            lutPresetId == null && customCubeLutUri.isNullOrBlank()

    val isIdentity: Boolean get() = isNeutral

    val activeAdjustCount: Int
        get() = listOf(
            abs(exposure) > 0.005f,
            abs(brightness) > 0.005f,
            abs(contrast) > 0.005f,
            abs(highlights) > 0.005f,
            abs(shadows) > 0.005f,
            abs(whites) > 0.005f,
            abs(blacks) > 0.005f,
            abs(gamma) > 0.005f,
            abs(saturation) > 0.005f,
            abs(vibrance) > 0.005f,
            abs(temperature) > 0.005f,
            abs(tint) > 0.005f,
            abs(hueShift) > 0.005f,
            abs(vignette) > 0.005f,
            grain > 0.005f,
            fade > 0.005f,
            hslBands.any { !it.isNeutral },
            !curves.isIdentity,
            !colorWheels.isNeutral
        ).count { it }

    fun resetDetailOnly(): ColorGradeConfig = copy(
        sharpness = 0f,
        sharpenRadius = 0.35f,
        sharpenThreshold = 0.12f,
        clarity = 0f,
        structure = 0f,
        dehaze = 0f,
        denoise = 0f
    )

    fun resetLight(): ColorGradeConfig = copy(
        brightness = 0f,
        contrast = 0f,
        exposure = 0f,
        highlights = 0f,
        shadows = 0f,
        whites = 0f,
        blacks = 0f,
        gamma = 0f,
        fade = 0f
    )

    fun resetColor(): ColorGradeConfig = copy(
        saturation = 0f,
        vibrance = 0f,
        temperature = 0f,
        tint = 0f,
        hueShift = 0f
    )

    fun resetAll(): ColorGradeConfig = ColorGradeConfig()

    companion object {
        fun defaultHslBands(): List<HslBandAdjustment> = HslBand.entries.map { HslBandAdjustment(it) }
    }
}

data class ColorGradePreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "Cinematic",
    val accentHex: Long = 0xFFFF9800,
    val secondaryHex: Long = 0xFF1E293B,
    val config: ColorGradeConfig,
    val isUserCustom: Boolean = false
)

enum class KeyframeProperty(
    val label: String,
    val minValue: Float,
    val maxValue: Float,
    val defaultValue: Float
) {
    POSITION_X("Position X", -1f, 1f, 0f),
    POSITION_Y("Position Y", -1f, 1f, 0f),
    SCALE("Scale", 0.1f, 5.0f, 1.0f),
    ROTATION("Rotation", -360f, 360f, 0f),
    OPACITY("Opacity", 0f, 1f, 1f),
    VOLUME("Volume", 0f, 2f, 1f),
    BLUR("Blur", 0f, 1f, 0f),
    EFFECT_INTENSITY("FX Intensity", 0f, 1f, 1f),
    COLOR_INTENSITY("Grade Mix", 0f, 1f, 1f),
    TEXT_PROGRESS("Text Animation", 0f, 1f, 1f);

    companion object {
        /** Properties that produce a visual keyframe and therefore need a preview refresh. */
        val visual: Set<KeyframeProperty> = setOf(
            POSITION_X, POSITION_Y, SCALE, ROTATION, OPACITY, BLUR, EFFECT_INTENSITY, COLOR_INTENSITY, TEXT_PROGRESS
        )
    }
}

enum class EasingCurve(val label: String, val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float) {
    LINEAR("Linear", 0f, 0f, 1f, 1f),
    EASE_IN("Ease In", 0.42f, 0f, 1f, 1f),
    EASE_OUT("Ease Out", 0f, 0f, 0.58f, 1f),
    EASE_IN_OUT("Ease In-Out", 0.42f, 0f, 0.58f, 1f),
    SNAP("Snap Overshoot", 0.34f, 1.45f, 0.64f, 1f);

    companion object {
        fun fromName(name: String?): EasingCurve =
            entries.firstOrNull { it.name == name } ?: EASE_IN_OUT
    }
}

data class Keyframe(
    val id: String = UUID.randomUUID().toString(),
    val property: KeyframeProperty,
    val timeOffsetMs: Long,
    val value: Float,
    val easing: EasingCurve = EasingCurve.EASE_IN_OUT
)

enum class TransitionType(
    val label: String,
    val defaultDurationMs: Long = 500L
) {
    FADE("Fade", 500L),
    DISSOLVE("Dissolve", 600L),
    ZOOM("Zoom", 450L),
    SLIDE("Slide", 500L),
    WIPE("Wipe", 550L),
    BLUR("Blur", 500L),
    SPIN("Spin", 500L),
    CUSTOM("Custom", 600L)
}

data class ClipTransition(
    val type: TransitionType = TransitionType.DISSOLVE,
    val durationMs: Long = type.defaultDurationMs,
    /** Direction for SLIDE / WIPE / CUSTOM, degrees. */
    val directionAngleDeg: Float = 0f,
    /** Edge softness for WIPE / CUSTOM. */
    val softness: Float = 0.35f,
    /** Chromatic amount for CUSTOM / ZOOM. */
    val chromaticAmount: Float = 0.4f
)

enum class TextAnimationType(val label: String) {
    NONE("None"),
    TYPEWRITER("Typewriter"),
    POP_BOUNCE("Pop Bounce"),
    FADE_UP("Fade Up"),
    SLIDE_IN("Slide In"),
    KARAOKE("Karaoke")
}

data class TextStyleSpec(
    val text: String = "Zero Text",
    val fontFamilyName: String = "Sans Bold",
    val fontSizeSp: Float = 28f,
    val textColorArgb: Long = 0xFFFFFFFF,
    val gradientEndColorArgb: Long = 0xFF00E5FF,
    val useGradient: Boolean = false,
    val shadowRadius: Float = 6f,
    val shadowColorArgb: Long = 0xCC000000,
    val outlineWidth: Float = 0f,
    val outlineColorArgb: Long = 0xFF000000,
    val backgroundColorArgb: Long = 0x00000000,
    val letterSpacingEm: Float = 0.02f,
    val animationIn: TextAnimationType = TextAnimationType.FADE_UP,
    val animationDurationMs: Long = 450L,
    val languageTag: String = "en-US",
    val templateId: String? = null
)

data class StickerSpec(
    val content: String = "🔥",
    val label: String = "Fire",
    val category: String = "Trending"
)

data class SubtitleCue(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val languageTag: String = "en-US",
    val confidence: Float = 1.0f
)

data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val trackId: String,
    val sourceUri: String,
    val sourceName: String,
    val mediaKind: MediaKind = MediaKind.VIDEO,
    val sourceDurationMs: Long = 5_000L,
    val startTimeOnTrackMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 5_000L,
    val speed: Float = 1.0f,
    val speedCurvePreset: SpeedCurvePreset = SpeedCurvePreset.NONE,
    val speedCurvePoints: List<SpeedPoint> = emptyList(),
    val isReversed: Boolean = false,
    val isFrozenFrame: Boolean = false,
    val freezeFrameSourceMs: Long = 0L,
    val crop: CropRect = CropRect(),
    val transform: ClipTransform = ClipTransform(),
    val chromaKey: ChromaKeyConfig = ChromaKeyConfig(),
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val noiseReduction: Float = 0f,
    val voiceEnhancement: Float = 0f,
    val audioDucking: Boolean = false,
    val effects: List<AppliedEffect> = emptyList(),
    val colorGrade: ColorGradeConfig = ColorGradeConfig(),
    val transitionIn: ClipTransition? = null,
    val transitionOut: ClipTransition? = null,
    val keyframes: List<Keyframe> = emptyList(),
    val textSpec: TextStyleSpec? = null,
    val stickerSpec: StickerSpec? = null,
    val waveformPeaks: List<Float> = emptyList(),
    val beatMarkersMs: List<Long> = emptyList(),
    val hasAudioTrack: Boolean = true,
    val sourceWidth: Int = 1080,
    val sourceHeight: Int = 1920,
    val rotationDegreesMeta: Int = 0,
    val mimeType: String = "",
    val thumbnailUri: String? = null
) {
    val effectiveTrimEndMs: Long
        get() = if (trimEndMs > trimStartMs) trimEndMs else max(trimStartMs + 50L, sourceDurationMs)

    val trimmedSourceDurationMs: Long
        get() = max(50L, effectiveTrimEndMs - trimStartMs)

    val activeSpeedPoints: List<SpeedPoint>
        get() = when {
            speedCurvePoints.size >= 2 -> speedCurvePoints.sortedBy { it.normalizedPosition }
            speedCurvePreset != SpeedCurvePreset.NONE -> speedCurvePreset.defaultPoints.sortedBy { it.normalizedPosition }
            else -> emptyList()
        }

    val hasSpeedRamp: Boolean get() = activeSpeedPoints.size >= 2

    val clampedSpeed: Float
        get() = speed.coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED)

    val timelineDurationMs: Long
        get() {
            if (isFrozenFrame || mediaKind == MediaKind.IMAGE ||
                mediaKind == MediaKind.TEXT_ELEMENT || mediaKind == MediaKind.STICKER_ELEMENT
            ) {
                return trimmedSourceDurationMs
            }
            val rawMs = trimmedSourceDurationMs.toDouble()
            if (hasSpeedRamp) {
                return max(50L, (rawMs * TimelineClip.averageInverseSpeed(activeSpeedPoints)).roundToLong())
            }
            return max(50L, (rawMs / clampedSpeed).roundToLong())
        }

    val endTimeOnTrackMs: Long get() = startTimeOnTrackMs + timelineDurationMs

    fun instantaneousSpeedAt(normalizedOffset: Float): Float {
        if (isFrozenFrame || mediaKind == MediaKind.IMAGE) return 1f
        val pts = activeSpeedPoints
        return if (pts.size >= 2) {
            sampleSpeedCurve(pts, normalizedOffset).coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED)
        } else {
            clampedSpeed
        }
    }

    /**
     * Maps a timeline offset inside this clip to an absolute source-media timestamp.
     * This is the single source of truth used by preview seeking, splitting, trimming,
     * and export so speed ramps + reverse never drift.
     *
     * Time-remap contract: a source fraction `u` occupies `rawSpan * (1/speed(u))` of the
     * timeline, so `timelineOffset = sourceOffset * mean(1/speed)` and this method is the exact
     * inverse of [mapSourceMsToTimelineOffset].
     */
    fun mapTimelineOffsetToSourceMs(localTimelineOffsetMs: Long): Long {
        if (isFrozenFrame) return freezeFrameSourceMs.coerceIn(0L, sourceDurationMs)
        if (mediaKind == MediaKind.IMAGE) return localTimelineOffsetMs.coerceIn(0L, trimmedSourceDurationMs)
        val offset = localTimelineOffsetMs.coerceIn(0L, timelineDurationMs)
        val srcOffset = if (hasSpeedRamp) {
            (offset.toDouble() / TimelineClip.averageInverseSpeed(activeSpeedPoints)).roundToLong()
        } else {
            (offset * clampedSpeed).roundToLong()
        }
        val sourceOffset = srcOffset.coerceIn(0L, trimmedSourceDurationMs)
        return if (isReversed) {
            (effectiveTrimEndMs - sourceOffset).coerceIn(trimStartMs, effectiveTrimEndMs)
        } else {
            (trimStartMs + sourceOffset).coerceIn(trimStartMs, effectiveTrimEndMs)
        }
    }

    /** Inverse of [mapTimelineOffsetToSourceMs]: absolute source time -> local timeline offset. */
    fun mapSourceMsToTimelineOffset(sourceMs: Long): Long {
        if (isFrozenFrame) return 0L
        val trimmedTarget = if (isReversed) {
            (effectiveTrimEndMs - sourceMs).coerceIn(0L, trimmedSourceDurationMs)
        } else {
            (sourceMs - trimStartMs).coerceIn(0L, trimmedSourceDurationMs)
        }
        val timeline = if (hasSpeedRamp) {
            trimmedTarget.toDouble() * TimelineClip.averageInverseSpeed(activeSpeedPoints)
        } else {
            trimmedTarget.toDouble() / clampedSpeed
        }
        return timeline.roundToLong().coerceIn(0L, timelineDurationMs)
    }

    /** Timeline offset at which the playback clock (Media3) advances by [hostSpeed]. */
    fun playbackSpeedAt(localTimelineOffsetMs: Long): Float {
        val norm = if (timelineDurationMs > 0) localTimelineOffsetMs.toFloat() / timelineDurationMs else 0f
        return instantaneousSpeedAt(norm)
    }

    val isVisual: Boolean
        get() = mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE

    companion object {
        fun sampleSpeedCurve(sortedPoints: List<SpeedPoint>, u: Float): Float {
            if (sortedPoints.isEmpty()) return 1f
            val clamped = u.coerceIn(0f, 1f)
            if (clamped <= sortedPoints.first().normalizedPosition) return sortedPoints.first().speedMultiplier
            if (clamped >= sortedPoints.last().normalizedPosition) return sortedPoints.last().speedMultiplier
            for (i in 0 until sortedPoints.lastIndex) {
                val a = sortedPoints[i]
                val b = sortedPoints[i + 1]
                if (clamped in a.normalizedPosition..b.normalizedPosition) {
                    val span = (b.normalizedPosition - a.normalizedPosition).coerceAtLeast(0.0001f)
                    val t = (clamped - a.normalizedPosition) / span
                    val smooth = t * t * (3f - 2f * t)
                    return a.speedMultiplier + (b.speedMultiplier - a.speedMultiplier) * smooth
                }
            }
            return sortedPoints.last().speedMultiplier
        }

        /**
         * Mean of 1/speed over the curve. Bumping this constant invalidates every derived
         * timeline duration, so keep it fixed — changing it would silently retime saved projects.
         */
        fun averageInverseSpeed(points: List<SpeedPoint>): Double {
            if (points.size < 2) return 1.0
            var sum = 0.0
            val steps = 120
            for (i in 0 until steps) {
                val u = (i + 0.5f) / steps.toFloat()
                sum += 1.0 / sampleSpeedCurve(points, u).coerceIn(SpeedCurvePreset.MIN_SPEED, SpeedCurvePreset.MAX_SPEED)
            }
            return (sum / steps).coerceAtLeast(0.0001)
        }

        /** Time compression ratio (timeline duration / source duration) for a speed ramp. */
        fun speedRampFactor(points: List<SpeedPoint>): Double = averageInverseSpeed(points)
    }
}

data class Track(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: TrackType,
    val isLocked: Boolean = false,
    val isHidden: Boolean = false,
    val isMuted: Boolean = false,
    val volume: Float = 1.0f,
    val clips: List<TimelineClip> = emptyList()
) {
    val durationMs: Long get() = clips.maxOfOrNull { it.endTimeOnTrackMs } ?: 0L
}

enum class ExportResolution(val label: String, val shortSidePx: Int, val badge: String) {
    HD_720P("720p HD", 720, "720p"),
    FHD_1080P("1080p Full HD", 1080, "1080p"),
    QHD_2K("2K QHD", 1440, "2K"),
    UHD_4K("4K Ultra HD", 2160, "4K")
}

enum class ExportFrameRate(val fps: Int, val label: String) {
    FPS_24(24, "24 fps"),
    FPS_25(25, "25 fps"),
    FPS_30(30, "30 fps"),
    FPS_50(50, "50 fps"),
    FPS_60(60, "60 fps")
}

enum class BitrateTier(val label: String, val bitsPerPixelPerFrame: Float) {
    LOW("Low", 0.07f),
    MEDIUM("Medium", 0.12f),
    HIGH("High", 0.20f),
    CUSTOM("Custom", 0.14f)
}

enum class VideoCodecOption(val label: String, val mimeType: String) {
    H264("H.264", "video/avc"),
    H265("H.265 / HEVC", "video/hevc")
}

data class ExportConfig(
    val resolution: ExportResolution = ExportResolution.FHD_1080P,
    val frameRate: ExportFrameRate = ExportFrameRate.FPS_30,
    val bitrateTier: BitrateTier = BitrateTier.HIGH,
    val customBitrateMbps: Float = 16f,
    val codec: VideoCodecOption = VideoCodecOption.H264,
    val audioOnly: Boolean = false,
    val exportAsPhotoFrame: Boolean = false,
    val audioBitrateKbps: Int = 192,
    val saveToGallery: Boolean = true
) {
    fun targetVideoBitrateBps(aspect: AspectRatioPreset, customRatio: Float = 0.5625f): Int {
        if (bitrateTier == BitrateTier.CUSTOM) {
            return (customBitrateMbps.coerceIn(1f, 120f) * 1_000_000f).toInt()
        }
        val (w, h) = aspect.computeDimensionsFor(resolution.shortSidePx, customRatio)
        val pixelsPerSec = w.toLong() * h.toLong() * frameRate.fps.toLong()
        val raw = (pixelsPerSec * bitrateTier.bitsPerPixelPerFrame).toLong()
        val hevcEfficiency = if (codec == VideoCodecOption.H265) 0.78 else 1.0
        return (raw * hevcEfficiency).toLong().coerceIn(1_000_000L, 120_000_000L).toInt()
    }

    fun estimatedFileSizeMb(durationMs: Long, aspect: AspectRatioPreset, customRatio: Float = 0.5625f): Float {
        if (exportAsPhotoFrame) {
            val (w, h) = aspect.computeDimensionsFor(resolution.shortSidePx, customRatio)
            return ((w.toLong() * h.toLong() * 0.45f) / (1024f * 1024f)).coerceIn(0.4f, 12f)
        }
        val seconds = (durationMs / 1000f).coerceAtLeast(1f)
        val audioBps = audioBitrateKbps * 1000L
        val videoBps = if (audioOnly) 0L else targetVideoBitrateBps(aspect, customRatio).toLong()
        val totalBytes = ((videoBps + audioBps) * seconds) / 8f
        return totalBytes / (1024f * 1024f)
    }
}

/** Preview render quality. PERFORMANCE halves the decode size for low-end devices. */
enum class PreviewQuality(val label: String, val proxyHeight: Int?) {
    HIGH("High quality", null),
    PERFORMANCE("Performance (720p proxy)", 720),
    BATTERY("Battery saver (540p proxy)", 540)
}

enum class UpscaleEngine(val label: String, val description: String, val isAi: Boolean) {
    LANCZOS("Lanczos-3 (conventional)", "Real multi-tap Lanczos-3 resampling with detail-preserving sharpening.", false)
}

/**
 * 4K enhancement. These are conventional (non-AI) enhancement controls applied by real GPU
 * shaders in both preview and export; no AI super-resolution model is bundled with the app,
 * and the UI states that explicitly.
 */
data class EnhancementConfig(
    val enabled: Boolean = false,
    val sharpen: Float = 0.45f,
    val denoise: Float = 0.25f,
    val contrastBoost: Float = 0.12f,
    val colorBoost: Float = 0.10f,
    val detailPreserve: Float = 0.55f,
    val brightnessFix: Float = 0f,
    val targetHeight: Int = 1080,
    val engine: UpscaleEngine = UpscaleEngine.LANCZOS
) {
    val isActive: Boolean
        get() = enabled && (sharpen > 0.001f || denoise > 0.001f || abs(contrastBoost) > 0.001f ||
            abs(colorBoost) > 0.001f || abs(brightnessFix) > 0.001f)

    val summary: String
        get() = if (!enabled) "Off" else "${targetHeight}p • sharpen ${(sharpen * 100).roundToLong()}% • denoise ${(denoise * 100).roundToLong()}%"
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Untitled Project",
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.REELS_9_16,
    val customAspectRatio: Float = 9f / 16f,
    val backgroundColorArgb: Long = 0xFF000000,
    val magneticTimeline: Boolean = true,
    val snappingEnabled: Boolean = true,
    val timelineZoom: Float = 1.0f,
    val tracks: List<Track> = defaultTracks(),
    val subtitles: List<SubtitleCue> = emptyList(),
    val customColorPresets: List<ColorGradePreset> = emptyList(),
    val exportConfig: ExportConfig = ExportConfig(),
    val enhancement: EnhancementConfig = EnhancementConfig(),
    val previewQuality: PreviewQuality = PreviewQuality.HIGH,
    val thumbnailUri: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val revision: Int = 1
) {
    val effectiveAspectRatio: Float
        get() = if (aspectRatio == AspectRatioPreset.CUSTOM) {
            AspectRatioPreset.sanitizeCustomRatio(customAspectRatio)
        } else {
            aspectRatio.ratioFloat
        }

    val totalDurationMs: Long
        get() = tracks.filterNot { it.isHidden }.maxOfOrNull { it.durationMs } ?: 0L

    val mainVideoClips: List<TimelineClip>
        get() = tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN && !it.isHidden }?.clips.orEmpty()

    val allClips: List<TimelineClip>
        get() = tracks.flatMap { it.clips }

    fun trackByType(type: TrackType): Track? = tracks.firstOrNull { it.type == type }

    fun findClip(clipId: String): TimelineClip? =
        tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == clipId }

    fun findTrackForClip(clipId: String): Track? =
        tracks.firstOrNull { track -> track.clips.any { it.id == clipId } }

    /** Main clip visible at [timelineMs]; falls back to the closest preceding clip. */
    fun findMainClipAt(timelineMs: Long): TimelineClip? {
        val clips = mainVideoClips
        if (clips.isEmpty()) return null
        val hit = clips.firstOrNull { timelineMs >= it.startTimeOnTrackMs && timelineMs < it.endTimeOnTrackMs }
        if (hit != null) return hit
        return clips.lastOrNull { timelineMs >= it.startTimeOnTrackMs } ?: clips.first()
    }

    fun clipsAt(timelineMs: Long, predicate: (TrackType) -> Boolean): List<TimelineClip> =
        tracks.filter { predicate(it.type) && !it.isHidden }
            .flatMap { it.clips }
            .filter { timelineMs >= it.startTimeOnTrackMs && timelineMs < it.endTimeOnTrackMs }

    companion object {
        fun defaultTracks(): List<Track> = listOf(
            Track(id = "track-video-main", name = "Main Video", type = TrackType.VIDEO_MAIN),
            Track(id = "track-video-pip", name = "Overlay", type = TrackType.VIDEO_PIP),
            Track(id = "track-text", name = "Text", type = TrackType.TEXT),
            Track(id = "track-sticker", name = "Stickers", type = TrackType.STICKER),
            Track(id = "track-audio-music", name = "Music", type = TrackType.AUDIO_MUSIC),
            Track(id = "track-audio-voice", name = "Voiceover", type = TrackType.AUDIO_VOICEOVER),
            Track(id = "track-audio-sfx", name = "SFX", type = TrackType.AUDIO_SFX)
        )
    }
}
