package com.vibecut.pro.engine.model

import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class AspectRatioPreset(
    val id: String,
    val label: String,
    val subtitle: String,
    val widthRatio: Int,
    val heightRatio: Int
) {
    REELS_9_16("9:16", "9:16", "Reels / Shorts / TikTok", 9, 16),
    YOUTUBE_16_9("16:9", "16:9", "YouTube / Widescreen", 16, 9),
    SQUARE_1_1("1:1", "1:1", "Instagram Square", 1, 1),
    PORTRAIT_4_5("4:5", "4:5", "Instagram Feed", 4, 5),
    CINEMA_21_9("21:9", "21:9", "Anamorphic Cinema", 21, 9),
    CLASSIC_4_3("4:3", "4:3", "Classic Vintage", 4, 3);

    val ratioFloat: Float get() = widthRatio.toFloat() / heightRatio.toFloat()

    fun computeDimensions(shortSidePx: Int): Pair<Int, Int> {
        return if (widthRatio <= heightRatio) {
            val w = makeEven(shortSidePx)
            val h = makeEven((shortSidePx.toDouble() * heightRatio / widthRatio).toInt())
            w to h
        } else {
            val h = makeEven(shortSidePx)
            val w = makeEven((shortSidePx.toDouble() * widthRatio / heightRatio).toInt())
            w to h
        }
    }

    companion object {
        private fun makeEven(v: Int): Int = if (v % 2 == 0) max(2, v) else max(2, v + 1)
        fun fromId(id: String): AspectRatioPreset =
            entries.firstOrNull { it.id == id } ?: REELS_9_16
    }
}

enum class TrackType(val label: String, val isVisual: Boolean, val isAudio: Boolean) {
    VIDEO_MAIN("Main Video", isVisual = true, isAudio = true),
    VIDEO_PIP("Overlay / PIP", isVisual = true, isAudio = true),
    OVERLAY("FX Overlay", isVisual = true, isAudio = false),
    TEXT("Text & Captions", isVisual = true, isAudio = false),
    STICKER("Stickers", isVisual = true, isAudio = false),
    AUDIO_MUSIC("Music", isVisual = false, isAudio = true),
    AUDIO_VOICEOVER("Voiceover", isVisual = false, isAudio = true),
    AUDIO_SFX("Sound FX", isVisual = false, isAudio = true)
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
}

data class ClipTransform(
    val positionX: Float = 0f,      // Normalized NDC offset [-1f, 1f]
    val positionY: Float = 0f,      // Normalized NDC offset [-1f, 1f]
    val scaleX: Float = 1f,         // [0.1f, 6.0f]
    val scaleY: Float = 1f,         // [0.1f, 6.0f]
    val rotationDegrees: Float = 0f,// [-360f, 360f]
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val opacity: Float = 1f         // [0f, 1f]
) {
    val isIdentity: Boolean
        get() = abs(positionX) < 0.001f &&
            abs(positionY) < 0.001f &&
            abs(scaleX - 1f) < 0.001f &&
            abs(scaleY - 1f) < 0.001f &&
            abs(rotationDegrees % 360f) < 0.01f &&
            !flipHorizontal && !flipVertical &&
            abs(opacity - 1f) < 0.001f
}

enum class SpeedCurvePreset(
    val label: String,
    val defaultPoints: List<SpeedPoint>
) {
    NONE("Constant", emptyList()),
    MONTAGE(
        "Montage",
        listOf(
            SpeedPoint(0.0f, 0.6f),
            SpeedPoint(0.25f, 2.8f),
            SpeedPoint(0.55f, 0.45f),
            SpeedPoint(0.8f, 2.2f),
            SpeedPoint(1.0f, 1.0f)
        )
    ),
    HERO(
        "Hero Time",
        listOf(
            SpeedPoint(0.0f, 1.4f),
            SpeedPoint(0.35f, 2.5f),
            SpeedPoint(0.50f, 0.25f),
            SpeedPoint(0.70f, 0.25f),
            SpeedPoint(1.0f, 1.5f)
        )
    ),
    BULLET(
        "Bullet Slow-Mo",
        listOf(
            SpeedPoint(0.0f, 2.0f),
            SpeedPoint(0.45f, 0.2f),
            SpeedPoint(0.55f, 0.2f),
            SpeedPoint(1.0f, 2.0f)
        )
    ),
    JUMP_CUT(
        "Jump Cut",
        listOf(
            SpeedPoint(0.0f, 0.8f),
            SpeedPoint(0.48f, 4.0f),
            SpeedPoint(0.55f, 0.6f),
            SpeedPoint(1.0f, 1.0f)
        )
    ),
    FLASH_IN(
        "Flash In",
        listOf(
            SpeedPoint(0.0f, 4.5f),
            SpeedPoint(0.25f, 1.2f),
            SpeedPoint(1.0f, 1.0f)
        )
    ),
    FLASH_OUT(
        "Flash Out",
        listOf(
            SpeedPoint(0.0f, 1.0f),
            SpeedPoint(0.75f, 1.2f),
            SpeedPoint(1.0f, 4.5f)
        )
    ),
    CUSTOM(
        "Custom Curve",
        listOf(
            SpeedPoint(0.0f, 1.0f),
            SpeedPoint(0.5f, 1.0f),
            SpeedPoint(1.0f, 1.0f)
        )
    )
}

data class SpeedPoint(
    val normalizedPosition: Float, // [0f, 1f] within trimmed source segment
    val speedMultiplier: Float     // [0.1f, 10f]
)

enum class EffectCategory(val label: String) {
    SHARPEN_AND_BLUR("Sharpen & Blur"),
    GLOW_AND_LIGHT("Glow & Light"),
    GLITCH_AND_RETRO("Glitch & Retro"),
    CAMERA_AND_MOTION("Motion & Camera"),
    STYLIZE_AND_LENS("Stylize & Lens")
}

enum class EffectType(
    val label: String,
    val category: EffectCategory,
    val defaultIntensity: Float = 0.65f,
    val secondaryLabel: String = "Detail / Radius",
    val defaultSecondary: Float = 0.5f,
    val badgeColorHex: Long = 0xFF7C4DFF
) {
    SHARPEN("Pro Sharpen", EffectCategory.SHARPEN_AND_BLUR, 0.7f, "Radius", 0.5f, 0xFF00E5FF),
    BLUR("Box Blur", EffectCategory.SHARPEN_AND_BLUR, 0.5f, "Spread", 0.5f, 0xFF448AFF),
    GAUSSIAN_BLUR("Gaussian Blur", EffectCategory.SHARPEN_AND_BLUR, 0.55f, "Sigma", 0.6f, 0xFF2979FF),
    MOTION_BLUR("Motion Blur", EffectCategory.SHARPEN_AND_BLUR, 0.6f, "Angle", 0.25f, 0xFF536DFE),
    BACKGROUND_BLUR("Background Blur", EffectCategory.SHARPEN_AND_BLUR, 0.7f, "Subject Focus", 0.45f, 0xFF00B8D4),

    GLOW("Soft Glow", EffectCategory.GLOW_AND_LIGHT, 0.55f, "Threshold", 0.6f, 0xFFFFD740),
    NEON_GLOW("Neon Edge Glow", EffectCategory.GLOW_AND_LIGHT, 0.75f, "Hue Shift", 0.4f, 0xFFE040FB),
    FLASH("Strobe Flash", EffectCategory.GLOW_AND_LIGHT, 0.6f, "Frequency", 0.5f, 0xFFFFFFFF),
    LIGHT_LEAKS("Light Leaks", EffectCategory.GLOW_AND_LIGHT, 0.65f, "Warmth", 0.7f, 0xFFFF6E40),

    GLITCH("Digital Glitch", EffectCategory.GLITCH_AND_RETRO, 0.65f, "Slice Rate", 0.55f, 0xFFFF1744),
    RGB_SPLIT("RGB Split", EffectCategory.GLITCH_AND_RETRO, 0.6f, "Angle", 0.15f, 0xFFFF4081),
    VHS("VHS Tape", EffectCategory.GLITCH_AND_RETRO, 0.7f, "Tracking Jitter", 0.5f, 0xFF7C4DFF),
    FILM_GRAIN("Film Grain", EffectCategory.GLITCH_AND_RETRO, 0.45f, "Grain Size", 0.5f, 0xFFA1887F),
    OLD_FILM("Old Film 16mm", EffectCategory.GLITCH_AND_RETRO, 0.7f, "Scratches", 0.6f, 0xFFD7CCC8),

    CINEMATIC("Cinematic Scope", EffectCategory.CAMERA_AND_MOTION, 0.75f, "Letterbox", 0.5f, 0xFFFFAB00),
    SHAKE("Camera Shake", EffectCategory.CAMERA_AND_MOTION, 0.55f, "Speed", 0.65f, 0xFFFF5252),
    ZOOM("Dynamic Zoom", EffectCategory.CAMERA_AND_MOTION, 0.6f, "Pulse Rate", 0.5f, 0xFF69F0AE),

    CHROMATIC_ABERRATION("Chromatic Aberration", EffectCategory.STYLIZE_AND_LENS, 0.6f, "Edge Falloff", 0.5f, 0xFF18FFFF),
    VIGNETTE("Optical Vignette", EffectCategory.STYLIZE_AND_LENS, 0.55f, "Roundness", 0.6f, 0xFF90A4AE),
    LENS_DISTORTION("Lens Distortion", EffectCategory.STYLIZE_AND_LENS, 0.5f, "Barrel / Pincushion", 0.7f, 0xFF40C4FF),
    CARTOON("Cartoon Cel", EffectCategory.STYLIZE_AND_LENS, 0.75f, "Ink Levels", 0.55f, 0xFFFF9100),
    COLOR_DISTORTION("Color Distortion", EffectCategory.STYLIZE_AND_LENS, 0.65f, "Phase", 0.5f, 0xFFEA80FC)
}

data class AppliedEffect(
    val id: String = UUID.randomUUID().toString(),
    val type: EffectType,
    val intensity: Float = type.defaultIntensity,     // [0f, 1f]
    val secondaryParam: Float = type.defaultSecondary,// [0f, 1f]
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
    val hueShift: Float = 0f,        // [-1f, 1f] -> +/- 30 degrees
    val saturationShift: Float = 0f, // [-1f, 1f]
    val luminanceShift: Float = 0f   // [-1f, 1f]
) {
    val isNeutral: Boolean
        get() = abs(hueShift) < 0.001f && abs(saturationShift) < 0.001f && abs(luminanceShift) < 0.001f
}

data class CurvePoint(
    val x: Float, // [0f, 1f]
    val y: Float  // [0f, 1f]
)

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

        private fun isDefault(pts: List<CurvePoint>): Boolean =
            pts.all { abs(it.x - it.y) < 0.002f }
    }
}

data class ColorWheelTint(
    val rOffset: Float = 0f, // [-1f, 1f]
    val gOffset: Float = 0f, // [-1f, 1f]
    val bOffset: Float = 0f  // [-1f, 1f]
) {
    val isNeutral: Boolean
        get() = abs(rOffset) < 0.001f && abs(gOffset) < 0.001f && abs(bOffset) < 0.001f
}

data class ColorWheelsConfig(
    val shadows: ColorWheelTint = ColorWheelTint(),
    val midtones: ColorWheelTint = ColorWheelTint(),
    val highlights: ColorWheelTint = ColorWheelTint()
) {
    val isNeutral: Boolean
        get() = shadows.isNeutral && midtones.isNeutral && highlights.isNeutral
}

data class ColorGradeConfig(
    val brightness: Float = 0f,   // [-1f, 1f]
    val contrast: Float = 0f,     // [-1f, 1f]
    val saturation: Float = 0f,   // [-1f, 1f]
    val exposure: Float = 0f,     // [-2f, 2f] EV stops
    val temperature: Float = 0f,  // [-1f, 1f] cool <-> warm
    val tint: Float = 0f,         // [-1f, 1f] green <-> magenta
    val highlights: Float = 0f,   // [-1f, 1f]
    val shadows: Float = 0f,      // [-1f, 1f]
    val sharpness: Float = 0f,    // [0f, 1f] dedicated grading sharpness
    val fade: Float = 0f,         // [0f, 1f] matte film fade
    val hslBands: List<HslBandAdjustment> = HslBand.entries.map { HslBandAdjustment(it) },
    val curves: RgbCurvesConfig = RgbCurvesConfig(),
    val colorWheels: ColorWheelsConfig = ColorWheelsConfig(),
    val lutPresetId: String? = null,
    val customCubeLutUri: String? = null,
    val lutIntensity: Float = 1.0f // [0f, 1f]
) {
    val isNeutral: Boolean
        get() = abs(brightness) < 0.001f &&
            abs(contrast) < 0.001f &&
            abs(saturation) < 0.001f &&
            abs(exposure) < 0.001f &&
            abs(temperature) < 0.001f &&
            abs(tint) < 0.001f &&
            abs(highlights) < 0.001f &&
            abs(shadows) < 0.001f &&
            abs(sharpness) < 0.001f &&
            abs(fade) < 0.001f &&
            hslBands.all { it.isNeutral } &&
            curves.isIdentity &&
            colorWheels.isNeutral &&
            lutPresetId == null &&
            customCubeLutUri == null
}

data class ColorGradePreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: String = "Cinematic",
    val accentHex: Long = 0xFFFF9800,
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
    EFFECT_INTENSITY("FX Intensity", 0f, 1f, 0.7f),
    COLOR_INTENSITY("Color Grade Mix", 0f, 1f, 1f),
    TEXT_PROGRESS("Text Animation", 0f, 1f, 1f)
}

enum class EasingCurve(
    val label: String,
    val c1x: Float,
    val c1y: Float,
    val c2x: Float,
    val c2y: Float
) {
    LINEAR("Linear", 0f, 0f, 1f, 1f),
    EASE_IN("Ease In", 0.42f, 0f, 1f, 1f),
    EASE_OUT("Ease Out", 0f, 0f, 0.58f, 1f),
    EASE_IN_OUT("Ease In-Out", 0.42f, 0f, 0.58f, 1f),
    CUBIC_BOUNCE("Snap Overshoot", 0.34f, 1.45f, 0.64f, 1f),
    BEZIER_CUSTOM("Custom Bezier", 0.25f, 0.1f, 0.25f, 1f)
}

data class Keyframe(
    val id: String = UUID.randomUUID().toString(),
    val property: KeyframeProperty,
    val timeOffsetMs: Long, // Offset from start of clip [0..clipDurationMs]
    val value: Float,
    val easing: EasingCurve = EasingCurve.EASE_IN_OUT,
    val customC1x: Float = easing.c1x,
    val customC1y: Float = easing.c1y,
    val customC2x: Float = easing.c2x,
    val customC2y: Float = easing.c2y
)

enum class TransitionType(
    val label: String,
    val category: String,
    val defaultDurationMs: Long = 500L
) {
    FADE("Fade to Black", "Basic", 500L),
    DISSOLVE("Cross Dissolve", "Basic", 600L),
    ZOOM("Zoom Punch", "Camera", 450L),
    SPIN("Spin Whirl", "Camera", 500L),
    SWIPE("Smooth Swipe", "Slide", 450L),
    BLUR("Blur Whip", "Basic", 500L),
    GLITCH("Glitch Cut", "FX", 400L),
    FLASH("White Flash", "Light", 350L),
    SLIDE("Directional Slide", "Slide", 500L),
    WIPE("Linear Wipe", "Mask", 550L),
    PUSH("Frame Push", "Slide", 500L),
    CINEMATIC("Cinema Burn", "Light", 700L),
    CUSTOM("Custom Pro", "Custom", 600L)
}

data class ClipTransition(
    val type: TransitionType = TransitionType.DISSOLVE,
    val durationMs: Long = type.defaultDurationMs, // [100L..3000L]
    val directionAngleDeg: Float = 0f,             // Used by Swipe/Slide/Wipe/Push/Custom
    val softness: Float = 0.35f,                   // Used by Wipe/Custom
    val chromaticAmount: Float = 0.4f              // Used by Glitch/Zoom/Custom
)

enum class TextAnimationType(val label: String) {
    NONE("None"),
    TYPEWRITER("Typewriter"),
    POP_BOUNCE("Pop Bounce"),
    FADE_UP("Fade Up"),
    GLITCH_IN("Cyber Glitch"),
    KARAOKE_GLOW("Karaoke Highlight"),
    CINEMATIC_TRACKING("Cinema Wide")
}

data class TextStyleSpec(
    val text: String = "VibeCut Pro",
    val fontFamilyName: String = "Sans-Serif Bold",
    val fontSizeSp: Float = 28f,
    val textColorArgb: Long = 0xFFFFFFFF,
    val gradientEndColorArgb: Long = 0xFF00E5FF,
    val useGradient: Boolean = false,
    val shadowRadius: Float = 6f,
    val shadowColorArgb: Long = 0xCC000000,
    val outlineWidth: Float = 2f,
    val outlineColorArgb: Long = 0xFF000000,
    val backgroundColorArgb: Long = 0x00000000,
    val is3dExtruded: Boolean = false,
    val extrudeDepth: Float = 4f,
    val letterSpacingEm: Float = 0.02f,
    val animationIn: TextAnimationType = TextAnimationType.POP_BOUNCE,
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
    val volume: Float = 1.0f,           // [0f, 2f]
    val isMuted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val noiseReduction: Float = 0f,     // [0f, 1f]
    val voiceEnhancement: Float = 0f,   // [0f, 1f]
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
    val upscaleTargetHeight: Int? = null
) {
    /** Raw trimmed span in source media milliseconds before speed scaling. */
    val trimmedSourceDurationMs: Long
        get() = max(50L, trimEndMs - trimStartMs)

    /** Effective timeline duration accounting for constant speed or integrated speed curve. */
    val timelineDurationMs: Long
        get() {
            if (isFrozenFrame || mediaKind == MediaKind.IMAGE ||
                mediaKind == MediaKind.TEXT_ELEMENT || mediaKind == MediaKind.STICKER_ELEMENT
            ) {
                return trimmedSourceDurationMs
            }
            val rawMs = trimmedSourceDurationMs.toDouble()
            val activePoints = when {
                speedCurvePoints.size >= 2 -> speedCurvePoints
                speedCurvePreset != SpeedCurvePreset.NONE -> speedCurvePreset.defaultPoints
                else -> emptyList()
            }
            if (activePoints.size >= 2) {
                val sorted = activePoints.sortedBy { it.normalizedPosition }
                var totalFactor = 0.0
                val steps = 40
                for (i in 0 until steps) {
                    val u = (i + 0.5f) / steps.toFloat()
                    val spd = sampleSpeedCurve(sorted, u).coerceIn(0.1f, 10.0f)
                    totalFactor += (1.0 / spd) / steps
                }
                return max(50L, (rawMs * totalFactor).roundToLong())
            }
            val safeSpeed = speed.coerceIn(0.1f, 10.0f)
            return max(50L, (rawMs / safeSpeed).roundToLong())
        }

    val endTimeOnTrackMs: Long
        get() = startTimeOnTrackMs + timelineDurationMs

    companion object {
        fun sampleSpeedCurve(sortedPoints: List<SpeedPoint>, u: Float): Float {
            if (sortedPoints.isEmpty()) return 1.0f
            val clamped = u.coerceIn(0f, 1f)
            if (clamped <= sortedPoints.first().normalizedPosition) {
                return sortedPoints.first().speedMultiplier
            }
            if (clamped >= sortedPoints.last().normalizedPosition) {
                return sortedPoints.last().speedMultiplier
            }
            for (i in 0 until sortedPoints.lastIndex) {
                val a = sortedPoints[i]
                val b = sortedPoints[i + 1]
                if (clamped in a.normalizedPosition..b.normalizedPosition) {
                    val span = (b.normalizedPosition - a.normalizedPosition).coerceAtLeast(0.0001f)
                    val t = (clamped - a.normalizedPosition) / span
                    // Smooth cosine/hermite blend for natural VN-style speed ramp
                    val smoothT = t * t * (3f - 2f * t)
                    return a.speedMultiplier + (b.speedMultiplier - a.speedMultiplier) * smoothT
                }
            }
            return sortedPoints.last().speedMultiplier
        }
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
    val durationMs: Long
        get() = clips.maxOfOrNull { it.endTimeOnTrackMs } ?: 0L
}

enum class ExportResolution(val label: String, val shortSidePx: Int, val badge: String) {
    SD_480P("480p SD", 480, "SD"),
    HD_720P("720p HD", 720, "HD"),
    FHD_1080P("1080p Full HD", 1080, "FHD"),
    QHD_2K("2K QHD", 1440, "2K"),
    UHD_4K("4K Ultra HD", 2160, "4K")
}

enum class ExportFrameRate(val fps: Int, val label: String) {
    FPS_24(24, "24 FPS (Cinema)"),
    FPS_25(25, "25 FPS (PAL)"),
    FPS_30(30, "30 FPS (Standard)"),
    FPS_50(50, "50 FPS (Smooth PAL)"),
    FPS_60(60, "60 FPS (Ultra Smooth)")
}

enum class BitrateTier(val label: String, val bitsPerPixelPerFrame: Float) {
    LOW("Low (Smaller Size)", 0.06f),
    MEDIUM("Medium (Balanced)", 0.11f),
    HIGH("High (Studio Quality)", 0.18f),
    CUSTOM("Custom Mbps", 0.14f)
}

enum class VideoCodecOption(val label: String, val mimeType: String) {
    H264("H.264 (AVC - Universal)", "video/avc"),
    H265("H.265 (HEVC - High Efficiency)", "video/hevc")
}

data class ExportConfig(
    val resolution: ExportResolution = ExportResolution.FHD_1080P,
    val frameRate: ExportFrameRate = ExportFrameRate.FPS_30,
    val bitrateTier: BitrateTier = BitrateTier.HIGH,
    val customBitrateMbps: Float = 16.0f,
    val codec: VideoCodecOption = VideoCodecOption.H264,
    val audioOnly: Boolean = false,
    val audioBitrateKbps: Int = 192,
    val saveToGallery: Boolean = true,
    val runInBackground: Boolean = false
) {
    fun targetVideoBitrateBps(aspectRatio: AspectRatioPreset): Int {
        if (bitrateTier == BitrateTier.CUSTOM) {
            return (customBitrateMbps.coerceIn(1f, 120f) * 1_000_000f).toInt()
        }
        val (w, h) = aspectRatio.computeDimensions(resolution.shortSidePx)
        val pixelsPerSec = w.toLong() * h.toLong() * frameRate.fps.toLong()
        val raw = (pixelsPerSec * bitrateTier.bitsPerPixelPerFrame).toLong()
        val hevcEfficiency = if (codec == VideoCodecOption.H265) 0.78 else 1.0
        return (raw * hevcEfficiency).toLong().coerceIn(1_000_000L, 120_000_000L).toInt()
    }

    fun estimatedFileSizeMb(durationMs: Long, aspectRatio: AspectRatioPreset): Float {
        val seconds = (durationMs / 1000f).coerceAtLeast(1f)
        val audioBps = audioBitrateKbps * 1000L
        val videoBps = if (audioOnly) 0L else targetVideoBitrateBps(aspectRatio).toLong()
        val totalBytes = ((videoBps + audioBps) * seconds) / 8f
        return totalBytes / (1024f * 1024f)
    }
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Untitled Edit",
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.REELS_9_16,
    val backgroundColorArgb: Long = 0xFF000000,
    val magneticTimeline: Boolean = true,
    val snappingEnabled: Boolean = true,
    val timelineZoom: Float = 1.0f, // [0.25f, 5.0f]
    val tracks: List<Track> = defaultTracks(),
    val subtitles: List<SubtitleCue> = emptyList(),
    val customColorPresets: List<ColorGradePreset> = emptyList(),
    val exportConfig: ExportConfig = ExportConfig(),
    val thumbnailUri: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val revision: Int = 1
) {
    val totalDurationMs: Long
        get() = tracks.filter { !it.isHidden }.maxOfOrNull { it.durationMs } ?: 0L

    fun findClip(clipId: String): TimelineClip? =
        tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == clipId }

    fun findTrackForClip(clipId: String): Track? =
        tracks.firstOrNull { track -> track.clips.any { it.id == clipId } }

    companion object {
        fun defaultTracks(): List<Track> = listOf(
            Track(id = "track-video-main", name = "Main Video", type = TrackType.VIDEO_MAIN),
            Track(id = "track-video-pip", name = "PIP / Overlay", type = TrackType.VIDEO_PIP),
            Track(id = "track-text", name = "Text & Captions", type = TrackType.TEXT),
            Track(id = "track-sticker", name = "Stickers", type = TrackType.STICKER),
            Track(id = "track-audio-music", name = "Music", type = TrackType.AUDIO_MUSIC),
            Track(id = "track-audio-voice", name = "Voiceover", type = TrackType.AUDIO_VOICEOVER),
            Track(id = "track-audio-sfx", name = "Sound FX", type = TrackType.AUDIO_SFX)
        )
    }
}
