package com.vibecut.pro.engine.color

import com.vibecut.pro.engine.model.*
import kotlin.math.*

data class CubeLut3D(
    val title: String,
    val size: Int,
    /** Packed 0xFFRRGGBB or ARGB int[size][size][size] compatible with Media3 SingleColorLut.createFromCube */
    val argbCube: Array<Array<IntArray>>
)

object ColorGradingEngine {

    val builtInPresets: List<ColorGradePreset> = listOf(
        ColorGradePreset(
            id = "preset-teal-orange",
            name = "Teal & Orange",
            category = "Cinematic",
            accentHex = 0xFF00BCD4,
            config = ColorGradeConfig(
                contrast = 0.22f,
                saturation = 0.14f,
                temperature = 0.12f,
                highlights = -0.10f,
                shadows = 0.08f,
                sharpness = 0.20f,
                colorWheels = ColorWheelsConfig(
                    shadows = ColorWheelTint(-0.14f, 0.04f, 0.16f),
                    highlights = ColorWheelTint(0.16f, 0.05f, -0.12f)
                )
            )
        ),
        ColorGradePreset(
            id = "preset-kodak-2383",
            name = "Kodak 2383 Print",
            category = "Film",
            accentHex = 0xFFFFB300,
            config = ColorGradeConfig(
                contrast = 0.28f,
                saturation = 0.08f,
                temperature = 0.16f,
                tint = 0.04f,
                highlights = -0.15f,
                shadows = -0.06f,
                fade = 0.08f,
                colorWheels = ColorWheelsConfig(
                    shadows = ColorWheelTint(-0.04f, 0.02f, 0.08f),
                    midtones = ColorWheelTint(0.06f, 0.03f, -0.03f),
                    highlights = ColorWheelTint(0.12f, 0.08f, -0.06f)
                )
            )
        ),
        ColorGradePreset(
            id = "preset-fuji-eterna",
            name = "Fuji Eterna Soft",
            category = "Film",
            accentHex = 0xFF26A69A,
            config = ColorGradeConfig(
                contrast = -0.08f,
                saturation = -0.06f,
                temperature = -0.05f,
                highlights = -0.22f,
                shadows = 0.18f,
                fade = 0.12f
            )
        ),
        ColorGradePreset(
            id = "preset-crisp-sharp",
            name = "Crisp Vivid Sharp",
            category = "Pro Detail",
            accentHex = 0xFF00E5FF,
            config = ColorGradeConfig(
                contrast = 0.18f,
                saturation = 0.20f,
                exposure = 0.08f,
                highlights = -0.08f,
                shadows = 0.10f,
                sharpness = 0.65f
            )
        ),
        ColorGradePreset(
            id = "preset-moody-noir",
            name = "Moody Dark",
            category = "Cinematic",
            accentHex = 0xFF78909C,
            config = ColorGradeConfig(
                brightness = -0.06f,
                contrast = 0.30f,
                saturation = -0.35f,
                temperature = -0.12f,
                shadows = -0.15f,
                fade = 0.14f
            )
        ),
        ColorGradePreset(
            id = "preset-cyberpunk",
            name = "Cyberpunk Neon",
            category = "Stylized",
            accentHex = 0xFFE040FB,
            config = ColorGradeConfig(
                contrast = 0.25f,
                saturation = 0.32f,
                temperature = -0.20f,
                tint = 0.28f,
                sharpness = 0.30f,
                colorWheels = ColorWheelsConfig(
                    shadows = ColorWheelTint(0.05f, -0.12f, 0.24f),
                    highlights = ColorWheelTint(0.20f, -0.08f, 0.22f)
                )
            )
        ),
        ColorGradePreset(
            id = "preset-golden-hour",
            name = "Golden Hour",
            category = "Warm",
            accentHex = 0xFFFF9100,
            config = ColorGradeConfig(
                exposure = 0.12f,
                contrast = 0.10f,
                saturation = 0.18f,
                temperature = 0.34f,
                tint = 0.06f,
                highlights = 0.08f,
                fade = 0.05f
            )
        ),
        ColorGradePreset(
            id = "preset-vintage-90s",
            name = "Vintage 90s",
            category = "Retro",
            accentHex = 0xFFD4E157,
            config = ColorGradeConfig(
                contrast = -0.05f,
                saturation = -0.14f,
                temperature = 0.18f,
                fade = 0.22f,
                colorWheels = ColorWheelsConfig(
                    shadows = ColorWheelTint(0.06f, 0.08f, -0.04f)
                )
            )
        ),
        ColorGradePreset(
            id = "preset-bleach-bypass",
            name = "Bleach Bypass",
            category = "Cinematic",
            accentHex = 0xFFB0BEC5,
            config = ColorGradeConfig(
                contrast = 0.42f,
                saturation = -0.48f,
                highlights = 0.10f,
                shadows = -0.18f,
                sharpness = 0.38f
            )
        ),
        ColorGradePreset(
            id = "preset-nordic-cold",
            name = "Nordic Ice",
            category = "Cool",
            accentHex = 0xFF40C4FF,
            config = ColorGradeConfig(
                contrast = 0.15f,
                saturation = -0.12f,
                temperature = -0.32f,
                highlights = 0.06f,
                shadows = 0.08f
            )
        ),
        ColorGradePreset(
            id = "preset-pastel-dream",
            name = "Pastel Dream",
            category = "Lifestyle",
            accentHex = 0xFFF48FB1,
            config = ColorGradeConfig(
                brightness = 0.08f,
                contrast = -0.14f,
                saturation = 0.10f,
                highlights = -0.18f,
                shadows = 0.24f,
                fade = 0.10f
            )
        ),
        ColorGradePreset(
            id = "preset-matte-urban",
            name = "Matte Urban",
            category = "Street",
            accentHex = 0xFF8D6E63,
            config = ColorGradeConfig(
                contrast = 0.20f,
                saturation = -0.18f,
                shadows = 0.12f,
                sharpness = 0.28f,
                fade = 0.25f
            )
        ),
        ColorGradePreset(
            id = "preset-bw-silver",
            name = "Silver Monochrome",
            category = "B&W",
            accentHex = 0xFFEEEEEE,
            config = ColorGradeConfig(
                contrast = 0.34f,
                saturation = -1.0f,
                highlights = 0.08f,
                shadows = -0.10f,
                sharpness = 0.32f
            )
        ),
        ColorGradePreset(
            id = "preset-hdr-pop",
            name = "HDR Clarity Pop",
            category = "Pro Detail",
            accentHex = 0xFF69F0AE,
            config = ColorGradeConfig(
                exposure = 0.10f,
                contrast = 0.24f,
                saturation = 0.25f,
                highlights = -0.20f,
                shadows = 0.22f,
                sharpness = 0.55f
            )
        )
    )

    /**
     * Evaluates a piecewise monotone cubic curve y = f(x) for x in [0, 1].
     */
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

        // Secants & tangents (Fritsch-Carlson monotone cubic interpolation)
        val dx = FloatArray(n - 1)
        val dy = FloatArray(n - 1)
        val m = FloatArray(n - 1)
        for (i in 0 until n - 1) {
            dx[i] = (sorted[i + 1].x - sorted[i].x).coerceAtLeast(1e-4f)
            dy[i] = sorted[i + 1].y - sorted[i].y
            m[i] = dy[i] / dx[i]
        }
        val tangents = FloatArray(n)
        tangents[0] = m[0]
        tangents[n - 1] = m[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = if (m[i - 1] * m[i] <= 0f) 0f else (m[i - 1] + m[i]) * 0.5f
        }

        for (i in 0 until n - 1) {
            if (clamped in sorted[i].x..sorted[i + 1].x) {
                val h = dx[i]
                val t = (clamped - sorted[i].x) / h
                val t2 = t * t
                val t3 = t2 * t
                val h00 = 2f * t3 - 3f * t2 + 1f
                val h10 = t3 - 2f * t2 + t
                val h01 = -2f * t3 + 3f * t2
                val h11 = t3 - t2
                val y = h00 * sorted[i].y + h10 * h * tangents[i] +
                    h01 * sorted[i + 1].y + h11 * h * tangents[i + 1]
                return y.coerceIn(0f, 1f)
            }
        }
        return clamped
    }

    /**
     * Transforms a single normalized RGB pixel [0..1] through full ColorGradeConfig
     * (Exposure, Temp/Tint, Brightness/Contrast, Highlights/Shadows, Fade, Color Wheels,
     * 8-Band HSL, and RGB Curves). Used both to bake 3D LUT cubes for Media3 SingleColorLut
     * and for preview swatch / histogram rendering.
     */
    fun gradePixelRgb(rIn: Float, gIn: Float, bIn: Float, cfg: ColorGradeConfig): FloatArray {
        // 1. Exposure (EV stops)
        val expMult = 2.0.pow(cfg.exposure.toDouble()).toFloat()
        var r = (rIn * expMult).coerceIn(0f, 1f)
        var g = (gIn * expMult).coerceIn(0f, 1f)
        var b = (bIn * expMult).coerceIn(0f, 1f)

        // 2. White balance: Temperature (blue <-> amber) & Tint (green <-> magenta)
        r = (r + cfg.temperature * 0.14f + cfg.tint * 0.06f).coerceIn(0f, 1f)
        g = (g - cfg.tint * 0.12f).coerceIn(0f, 1f)
        b = (b - cfg.temperature * 0.14f + cfg.tint * 0.06f).coerceIn(0f, 1f)

        // 3. Luminance-masked Shadows & Highlights
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val shadowWeight = (1f - luma) * (1f - luma)
        val highlightWeight = luma * luma
        val shLift = cfg.shadows * 0.28f * shadowWeight + cfg.highlights * 0.28f * highlightWeight
        r = (r + shLift).coerceIn(0f, 1f)
        g = (g + shLift).coerceIn(0f, 1f)
        b = (b + shLift).coerceIn(0f, 1f)

        // 4. Brightness, Contrast & Matte Fade
        val contrastFactor = (1f + cfg.contrast * 0.85f).coerceAtLeast(0.05f)
        r = ((r - 0.5f) * contrastFactor + 0.5f + cfg.brightness * 0.4f).coerceIn(0f, 1f)
        g = ((g - 0.5f) * contrastFactor + 0.5f + cfg.brightness * 0.4f).coerceIn(0f, 1f)
        b = ((b - 0.5f) * contrastFactor + 0.5f + cfg.brightness * 0.4f).coerceIn(0f, 1f)

        if (cfg.fade > 0.001f) {
            val floor = cfg.fade * 0.18f
            r = floor + r * (1f - floor)
            g = floor + g * (1f - floor)
            b = floor + b * (1f - floor)
        }

        // 5. 3-Way Color Wheels (Shadows / Midtones / Highlights)
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

        // 6. Global Saturation + 8-Band HSL
        val hasHsl = cfg.hslBands.any { !it.isNeutral }
        if (abs(cfg.saturation) > 0.001f || hasHsl) {
            val hsl = rgbToHsl(r, g, b)
            var hue = hsl[0]
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
            val rgbOut = hslToRgb(hue, sat, light)
            r = rgbOut[0]
            g = rgbOut[1]
            b = rgbOut[2]
        }

        // 7. RGB + Master Tone Curves
        if (!cfg.curves.isIdentity) {
            val mr = evaluateToneCurve(cfg.curves.master, r)
            val mg = evaluateToneCurve(cfg.curves.master, g)
            val mb = evaluateToneCurve(cfg.curves.master, b)
            r = evaluateToneCurve(cfg.curves.red, mr)
            g = evaluateToneCurve(cfg.curves.green, mg)
            b = evaluateToneCurve(cfg.curves.blue, mb)
        }

        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /**
     * Bakes a ColorGradeConfig into a 3D ARGB Cube LUT (default N = 17, 4,913 lattice points)
     * directly usable with Media3's `SingleColorLut.createFromCube(cube.argbCube)`.
     */
    fun bakeTo3dCube(
        cfg: ColorGradeConfig,
        size: Int = 17,
        mixIntensity: Float = 1.0f
    ): CubeLut3D {
        val n = size.coerceIn(4, 65)
        val mix = mixIntensity.coerceIn(0f, 1f)
        val cube = Array(n) { rIdx ->
            Array(n) { gIdx ->
                IntArray(n) { bIdx ->
                    val r0 = rIdx.toFloat() / (n - 1)
                    val g0 = gIdx.toFloat() / (n - 1)
                    val b0 = bIdx.toFloat() / (n - 1)
                    val graded = gradePixelRgb(r0, g0, b0, cfg)
                    val rf = ((r0 + (graded[0] - r0) * mix) * 255f).roundToInt().coerceIn(0, 255)
                    val gf = ((g0 + (graded[1] - g0) * mix) * 255f).roundToInt().coerceIn(0, 255)
                    val bf = ((b0 + (graded[2] - b0) * mix) * 255f).roundToInt().coerceIn(0, 255)
                    (0xFF shl 24) or (rf shl 16) or (gf shl 8) or bf
                }
            }
        }
        return CubeLut3D(title = "VibeCutGrade", size = n, argbCube = cube)
    }

    /**
     * Parses a standard Adobe / Resolve `.cube` 3D LUT text file into [CubeLut3D].
     */
    fun parseAdobeCubeLut(content: String, lutIntensity: Float = 1.0f): CubeLut3D {
        var title = "Custom LUT"
        var size = 0
        val triplets = ArrayList<FloatArray>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.startsWith("TITLE", ignoreCase = true) -> {
                    title = line.substringAfter(" ").trim().trim('"')
                }
                line.startsWith("LUT_3D_SIZE", ignoreCase = true) -> {
                    size = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: 0
                }
                line.startsWith("DOMAIN_", ignoreCase = true) ||
                    line.startsWith("LUT_1D_", ignoreCase = true) -> Unit
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val r = parts[0].toFloatOrNull()
                        val g = parts[1].toFloatOrNull()
                        val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            triplets.add(floatArrayOf(r, g, b))
                        }
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
        // Standard .cube order: Red changes fastest, then Green, then Blue
        var idx = 0
        for (bIdx in 0 until size) {
            for (gIdx in 0 until size) {
                for (rIdx in 0 until size) {
                    val rgb = triplets[idx++]
                    val r0 = rIdx.toFloat() / (size - 1)
                    val g0 = gIdx.toFloat() / (size - 1)
                    val b0 = bIdx.toFloat() / (size - 1)
                    val rf = ((r0 + (rgb[0].coerceIn(0f, 1f) - r0) * mix) * 255f).roundToInt().coerceIn(0, 255)
                    val gf = ((g0 + (rgb[1].coerceIn(0f, 1f) - g0) * mix) * 255f).roundToInt().coerceIn(0, 255)
                    val bf = ((b0 + (rgb[2].coerceIn(0f, 1f) - b0) * mix) * 255f).roundToInt().coerceIn(0, 255)
                    cube[rIdx][gIdx][bIdx] = (0xFF shl 24) or (rf shl 16) or (gf shl 8) or bf
                }
            }
        }
        return CubeLut3D(title = title, size = size, argbCube = cube)
    }

    private fun angularDistanceDeg(a: Float, b: Float): Float {
        val diff = abs(a - b) % 360f
        return if (diff > 180f) 360f - diff else diff
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) * 0.5f
        if (abs(maxC - minC) < 1e-5f) return floatArrayOf(0f, 0f, l)
        val d = maxC - minC
        val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
        var h = when (maxC) {
            r -> (g - b) / d + (if (g < b) 6f else 0f)
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        h *= 60f
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s < 1e-5f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val hk = (h % 360f) / 360f
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
        }
    }
}
