package com.vibecut.pro.engine.effects

import com.vibecut.pro.engine.model.EffectType
import com.vibecut.pro.engine.model.TransitionType

/**
 * Generates real OpenGL ES 2.0 (GLSL 100) vertex & fragment shaders for Media3's `GlProgram`.
 * Every effect in [EffectType] (all 22 effects including Pro Unsharp-Mask Sharpen),
 * clip transitions in [TransitionType], and real-time color/sharpness/opacity finishing
 * compile cleanly on Android OpenGL ES 2.0+ GPUs.
 */
object GlslShaderGenerator {

    const val STANDARD_VERTEX_SHADER_ES2: String = """
        attribute vec4 aFramePosition;
        varying vec2 vTexSamplingCoord;
        void main() {
            gl_Position = aFramePosition;
            vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
        }
    """

    /**
     * Builds the dedicated OpenGL ES 2.0 fragment shader for a single [EffectType] pass.
     * Multiple [AppliedEffect] instances on a clip are chained as sequential GPU FBO passes
     * inside Media3's VideoFrameProcessor so any combination of effects can stack cleanly.
     */
    fun buildEffectFragmentShader(type: EffectType): String {
        val effectBody = when (type) {
            EffectType.SHARPEN -> """
                // Pro Unsharp Mask + 8-neighbor High-Pass Detail Kernel
                float radius = mix(0.8, 2.6, uSecondary);
                vec2 stepUv = uTexelSize * radius;
                vec4 center = texture2D(uTexSampler, uv);
                vec4 north  = texture2D(uTexSampler, uv + vec2(0.0, stepUv.y));
                vec4 south  = texture2D(uTexSampler, uv - vec2(0.0, stepUv.y));
                vec4 east   = texture2D(uTexSampler, uv + vec2(stepUv.x, 0.0));
                vec4 west   = texture2D(uTexSampler, uv - vec2(stepUv.x, 0.0));
                vec4 ne     = texture2D(uTexSampler, uv + stepUv);
                vec4 nw     = texture2D(uTexSampler, uv + vec2(-stepUv.x, stepUv.y));
                vec4 se     = texture2D(uTexSampler, uv + vec2(stepUv.x, -stepUv.y));
                vec4 sw     = texture2D(uTexSampler, uv - stepUv);
                vec4 blurred = (center * 4.0 + (north + south + east + west) * 2.0 + (ne + nw + se + sw)) / 16.0;
                vec3 detail = center.rgb - blurred.rgb;
                float amount = uIntensity * 3.2;
                vec3 sharpened = clamp(center.rgb + detail * amount, 0.0, 1.0);
                gl_FragColor = vec4(sharpened, center.a);
            """

            EffectType.BLUR -> """
                float spread = uIntensity * mix(2.0, 9.0, uSecondary);
                vec2 s = uTexelSize * spread;
                vec4 sum = vec4(0.0);
                sum += texture2D(uTexSampler, uv + vec2(-s.x, -s.y));
                sum += texture2D(uTexSampler, uv + vec2( 0.0, -s.y));
                sum += texture2D(uTexSampler, uv + vec2( s.x, -s.y));
                sum += texture2D(uTexSampler, uv + vec2(-s.x,  0.0));
                sum += texture2D(uTexSampler, uv);
                sum += texture2D(uTexSampler, uv + vec2( s.x,  0.0));
                sum += texture2D(uTexSampler, uv + vec2(-s.x,  s.y));
                sum += texture2D(uTexSampler, uv + vec2( 0.0,  s.y));
                sum += texture2D(uTexSampler, uv + vec2( s.x,  s.y));
                gl_FragColor = sum / 9.0;
            """

            EffectType.GAUSSIAN_BLUR -> """
                float sigma = uIntensity * mix(2.5, 12.0, uSecondary);
                vec2 d1 = uTexelSize * sigma * 0.65;
                vec2 d2 = uTexelSize * sigma * 1.45;
                vec4 c = texture2D(uTexSampler, uv) * 0.227027;
                c += texture2D(uTexSampler, uv + vec2(d1.x, 0.0)) * 0.121621;
                c += texture2D(uTexSampler, uv - vec2(d1.x, 0.0)) * 0.121621;
                c += texture2D(uTexSampler, uv + vec2(0.0, d1.y)) * 0.121621;
                c += texture2D(uTexSampler, uv - vec2(0.0, d1.y)) * 0.121621;
                c += texture2D(uTexSampler, uv + d2) * 0.071622;
                c += texture2D(uTexSampler, uv - d2) * 0.071622;
                c += texture2D(uTexSampler, uv + vec2(-d2.x, d2.y)) * 0.071622;
                c += texture2D(uTexSampler, uv + vec2(d2.x, -d2.y)) * 0.071622;
                gl_FragColor = c;
            """

            EffectType.MOTION_BLUR -> """
                float angle = uSecondary * 6.2831853;
                vec2 dir = vec2(cos(angle), sin(angle)) * uTexelSize * uIntensity * 18.0;
                vec4 acc = vec4(0.0);
                acc += texture2D(uTexSampler, clamp(uv - dir * 1.00, 0.0, 1.0)) * 0.10;
                acc += texture2D(uTexSampler, clamp(uv - dir * 0.66, 0.0, 1.0)) * 0.15;
                acc += texture2D(uTexSampler, clamp(uv - dir * 0.33, 0.0, 1.0)) * 0.20;
                acc += texture2D(uTexSampler, uv) * 0.25;
                acc += texture2D(uTexSampler, clamp(uv + dir * 0.33, 0.0, 1.0)) * 0.15;
                acc += texture2D(uTexSampler, clamp(uv + dir * 0.66, 0.0, 1.0)) * 0.10;
                acc += texture2D(uTexSampler, clamp(uv + dir * 1.00, 0.0, 1.0)) * 0.05;
                gl_FragColor = acc;
            """

            EffectType.BACKGROUND_BLUR -> """
                vec4 sharp = texture2D(uTexSampler, uv);
                float focusRadius = mix(0.20, 0.55, uSecondary);
                float dist = distance(uv, vec2(0.5, 0.54));
                float mask = smoothstep(focusRadius * 0.7, focusRadius * 1.35, dist);
                vec2 bStep = uTexelSize * (uIntensity * 11.0 * mask);
                vec4 bg = texture2D(uTexSampler, uv) * 0.20;
                bg += texture2D(uTexSampler, clamp(uv + vec2(bStep.x, 0.0), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv - vec2(bStep.x, 0.0), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv + vec2(0.0, bStep.y), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv - vec2(0.0, bStep.y), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv + bStep, 0.0, 1.0)) * 0.10;
                bg += texture2D(uTexSampler, clamp(uv - bStep, 0.0, 1.0)) * 0.10;
                gl_FragColor = mix(sharp, bg, mask * uIntensity);
            """

            EffectType.GLOW -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 gStep = uTexelSize * 5.5;
                vec3 bloom = vec3(0.0);
                bloom += texture2D(uTexSampler, clamp(uv + vec2(gStep.x, 0.0), 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv - vec2(gStep.x, 0.0), 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv + vec2(0.0, gStep.y), 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv - vec2(0.0, gStep.y), 0.0, 1.0)).rgb;
                bloom *= 0.25;
                float thresh = mix(0.35, 0.75, uSecondary);
                vec3 hi = max(bloom - vec3(thresh), vec3(0.0)) / max(1.0 - thresh, 0.1);
                gl_FragColor = vec4(clamp(base.rgb + hi * uIntensity * 1.4, 0.0, 1.0), base.a);
            """

            EffectType.NEON_GLOW -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 t = uTexelSize * 1.6;
                float tl = dot(texture2D(uTexSampler, uv + vec2(-t.x,  t.y)).rgb, vec3(0.299, 0.587, 0.114));
                float tr = dot(texture2D(uTexSampler, uv + vec2( t.x,  t.y)).rgb, vec3(0.299, 0.587, 0.114));
                float bl = dot(texture2D(uTexSampler, uv + vec2(-t.x, -t.y)).rgb, vec3(0.299, 0.587, 0.114));
                float br = dot(texture2D(uTexSampler, uv + vec2( t.x, -t.y)).rgb, vec3(0.299, 0.587, 0.114));
                float gx = -tl + tr - bl + br;
                float gy =  tl + tr - bl - br;
                float edge = clamp(length(vec2(gx, gy)) * 3.2, 0.0, 1.0);
                float phase = uSecondary * 6.28318 + uTimeSec * 2.0 + uv.x * 3.0;
                vec3 neonColor = 0.55 + 0.45 * cos(phase + vec3(0.0, 2.1, 4.2));
                vec3 result = mix(base.rgb, base.rgb * 0.55 + neonColor * edge * 2.2, uIntensity);
                gl_FragColor = vec4(clamp(result, 0.0, 1.0), base.a);
            """

            EffectType.GLITCH -> """
                float speed = floor(uTimeSec * mix(8.0, 24.0, uSecondary));
                float band = floor(uv.y * 22.0);
                float n = fract(sin(dot(vec2(band, speed), vec2(12.9898, 78.233))) * 43758.5453);
                float trigger = step(1.0 - uIntensity * 0.55, n);
                float shift = (n - 0.5) * 0.12 * uIntensity * trigger;
                vec2 gUv = vec2(clamp(uv.x + shift, 0.0, 1.0), uv.y);
                float r = texture2D(uTexSampler, clamp(gUv + vec2(0.015 * uIntensity, 0.0), 0.0, 1.0)).r;
                float g = texture2D(uTexSampler, gUv).g;
                float b = texture2D(uTexSampler, clamp(gUv - vec2(0.015 * uIntensity, 0.0), 0.0, 1.0)).b;
                gl_FragColor = vec4(r, g, b, 1.0);
            """

            EffectType.RGB_SPLIT -> """
                float angle = uSecondary * 6.2831853;
                vec2 offset = vec2(cos(angle), sin(angle)) * (0.025 * uIntensity);
                float r = texture2D(uTexSampler, clamp(uv + offset, 0.0, 1.0)).r;
                vec4 mid = texture2D(uTexSampler, uv);
                float b = texture2D(uTexSampler, clamp(uv - offset, 0.0, 1.0)).b;
                gl_FragColor = vec4(r, mid.g, b, mid.a);
            """

            EffectType.VHS -> """
                float barY = fract(uTimeSec * 0.35);
                float barMask = smoothstep(0.05, 0.0, abs(uv.y - barY));
                float jitter = (fract(sin((uv.y + uTimeSec) * 432.1) * 9876.5) - 0.5) * 0.012 * uIntensity;
                vec2 vUv = vec2(clamp(uv.x + jitter + barMask * 0.03 * uSecondary, 0.0, 1.0), uv.y);
                float r = texture2D(uTexSampler, clamp(vUv + vec2(0.006 * uIntensity, 0.0), 0.0, 1.0)).r;
                float g = texture2D(uTexSampler, vUv).g;
                float b = texture2D(uTexSampler, clamp(vUv - vec2(0.008 * uIntensity, 0.0), 0.0, 1.0)).b;
                float scan = 0.92 + 0.08 * sin(uv.y * 540.0);
                vec3 vhs = vec3(r, g, b) * scan + vec3(barMask * 0.14 * uIntensity);
                gl_FragColor = vec4(clamp(vhs, 0.0, 1.0), 1.0);
            """

            EffectType.FILM_GRAIN -> """
                vec4 base = texture2D(uTexSampler, uv);
                float scale = mix(420.0, 140.0, uSecondary);
                vec2 seed = floor(uv * scale) + vec2(uTimeSec * 37.0, uTimeSec * 19.0);
                float noise = fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                vec3 grained = clamp(base.rgb + vec3(noise * uIntensity * 0.28), 0.0, 1.0);
                gl_FragColor = vec4(grained, base.a);
            """

            EffectType.CINEMATIC -> """
                vec4 base = texture2D(uTexSampler, uv);
                float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
                vec3 shadowsTeal = vec3(0.0, 0.06, 0.10) * (1.0 - luma);
                vec3 hiWarm = vec3(0.10, 0.05, -0.04) * luma;
                vec3 graded = clamp((base.rgb - 0.5) * (1.0 + 0.22 * uIntensity) + 0.5 + (shadowsTeal + hiWarm) * uIntensity, 0.0, 1.0);
                float barSize = 0.10 * uSecondary * uIntensity;
                float bar = step(barSize, uv.y) * step(uv.y, 1.0 - barSize);
                gl_FragColor = vec4(graded * bar, base.a);
            """

            EffectType.SHAKE -> """
                float freq = mix(14.0, 38.0, uSecondary);
                float dx = sin(uTimeSec * freq) * cos(uTimeSec * freq * 0.73) * 0.035 * uIntensity;
                float dy = cos(uTimeSec * freq * 1.13) * 0.030 * uIntensity;
                vec2 zoomedUv = (uv - 0.5) * (1.0 - 0.08 * uIntensity) + 0.5 + vec2(dx, dy);
                gl_FragColor = texture2D(uTexSampler, clamp(zoomedUv, 0.0, 1.0));
            """

            EffectType.ZOOM -> """
                float pulse = 0.5 + 0.5 * sin(uTimeSec * mix(2.5, 10.0, uSecondary));
                float z = 1.0 + uIntensity * 0.45 * pulse;
                vec2 zUv = (uv - 0.5) / z + 0.5;
                gl_FragColor = texture2D(uTexSampler, clamp(zUv, 0.0, 1.0));
            """

            EffectType.FLASH -> """
                vec4 base = texture2D(uTexSampler, uv);
                float rate = mix(3.0, 16.0, uSecondary);
                float strobe = pow(max(0.0, sin(uTimeSec * rate * 3.14159)), 4.0) * uIntensity;
                gl_FragColor = vec4(clamp(base.rgb + vec3(strobe * 0.85), 0.0, 1.0), base.a);
            """

            EffectType.LIGHT_LEAKS -> """
                vec4 base = texture2D(uTexSampler, uv);
                float wave1 = max(0.0, sin(uv.x * 3.2 + uTimeSec * 1.4) * cos(uv.y * 2.3 - uTimeSec * 0.8));
                float corner = smoothstep(0.85, 0.0, distance(uv, vec2(0.08 + 0.25 * sin(uTimeSec * 0.6), 0.85)));
                vec3 warmColor = mix(vec3(1.0, 0.22, 0.55), vec3(1.0, 0.58, 0.12), uSecondary);
                vec3 leak = warmColor * (wave1 * 0.45 + corner * 0.85) * uIntensity;
                vec3 screened = 1.0 - (1.0 - base.rgb) * (1.0 - clamp(leak, 0.0, 1.0));
                gl_FragColor = vec4(screened, base.a);
            """

            EffectType.CHROMATIC_ABERRATION -> """
                vec2 d = uv - vec2(0.5);
                float r2 = dot(d, d) * mix(1.0, 2.8, uSecondary);
                vec2 shift = d * r2 * 0.14 * uIntensity;
                float r = texture2D(uTexSampler, clamp(uv + shift, 0.0, 1.0)).r;
                vec4 gSample = texture2D(uTexSampler, uv);
                float b = texture2D(uTexSampler, clamp(uv - shift, 0.0, 1.0)).b;
                gl_FragColor = vec4(r, gSample.g, b, gSample.a);
            """

            EffectType.VIGNETTE -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 d = (uv - vec2(0.5)) * vec2(1.0, mix(0.8, 1.35, uSecondary));
                float dist = length(d);
                float vig = smoothstep(0.82, 0.28, dist);
                float factor = mix(1.0, vig, uIntensity);
                gl_FragColor = vec4(base.rgb * factor, base.a);
            """

            EffectType.LENS_DISTORTION -> """
                vec2 centered = uv * 2.0 - 1.0;
                float r2 = dot(centered, centered);
                float k = (uSecondary - 0.5) * 1.1 * uIntensity;
                vec2 warped = centered * (1.0 + k * r2);
                vec2 finalUv = warped * 0.5 + 0.5;
                if (finalUv.x < 0.0 || finalUv.x > 1.0 || finalUv.y < 0.0 || finalUv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = texture2D(uTexSampler, finalUv);
                }
            """

            EffectType.OLD_FILM -> """
                vec4 base = texture2D(uTexSampler, uv);
                float gray = dot(base.rgb, vec3(0.299, 0.587, 0.114));
                vec3 sepia = vec3(gray * 1.12, gray * 0.95, gray * 0.74);
                float flicker = 0.92 + 0.08 * sin(uTimeSec * 29.0);
                float lineX = fract(sin(floor(uTimeSec * 12.0) * 19.31) * 4375.8);
                float scratch = step(abs(uv.x - lineX), 0.0018 * uSecondary) * 0.55;
                float vig = smoothstep(0.90, 0.30, length(uv - 0.5));
                vec3 film = clamp(sepia * flicker * vig + vec3(scratch), 0.0, 1.0);
                gl_FragColor = vec4(mix(base.rgb, film, uIntensity), base.a);
            """

            EffectType.CARTOON -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 t = uTexelSize * 1.5;
                vec3 n = texture2D(uTexSampler, uv + vec2(0.0, t.y)).rgb;
                vec3 s = texture2D(uTexSampler, uv - vec2(0.0, t.y)).rgb;
                vec3 e = texture2D(uTexSampler, uv + vec2(t.x, 0.0)).rgb;
                vec3 w = texture2D(uTexSampler, uv - vec2(t.x, 0.0)).rgb;
                float edge = length(n - s) + length(e - w);
                float ink = 1.0 - smoothstep(0.18, 0.38, edge * (1.0 + uIntensity));
                float levels = mix(3.0, 8.0, uSecondary);
                vec3 poster = floor(base.rgb * levels + 0.5) / levels;
                vec3 cel = poster * ink;
                gl_FragColor = vec4(mix(base.rgb, cel, uIntensity), base.a);
            """

            EffectType.COLOR_DISTORTION -> """
                vec4 base = texture2D(uTexSampler, uv);
                float phase = uSecondary * 6.28318 + uTimeSec * 1.8;
                vec3 shifted = 0.5 + 0.5 * sin(base.rgb * 6.28318 * (1.0 + uIntensity) + vec3(phase, phase + 2.09, phase + 4.18));
                gl_FragColor = vec4(mix(base.rgb, shifted, uIntensity), base.a);
            """
        }

        return """
            precision highp float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uTimeSec;
            uniform float uIntensity;
            uniform float uSecondary;

            void main() {
                vec2 uv = vTexSamplingCoord;
                $effectBody
            }
        """.trimIndent()
    }

    /**
     * Builds the Master Finishing Fragment Shader handling:
     * - Dedicated Color Grading Sharpness (unsharp kernel)
     * - Keyframed Extra Blur
     * - Clip Entry / Exit Transitions (Fade, Dissolve, Zoom, Spin, Swipe, Blur, Glitch,
     *   Flash, Slide, Wipe, Push, Cinematic Burn, Custom)
     * - Opacity alpha scaling
     */
    fun buildMasterFinishingFragmentShader(): String = """
        precision highp float;
        varying vec2 vTexSamplingCoord;
        uniform sampler2D uTexSampler;
        uniform vec2 uTexelSize;
        uniform float uGradeSharpness;
        uniform float uKeyframeBlur;
        uniform float uOpacity;
        uniform int uTransitionType;
        uniform float uTransitionProgress; // 0.0 = mid-transition peak, 1.0 = normal clip
        uniform float uTransitionSoftness;
        uniform float uTransitionChromatic;

        vec4 sampleFinished(vec2 coord) {
            vec2 cUv = clamp(coord, 0.0, 1.0);
            vec4 center = texture2D(uTexSampler, cUv);
            vec3 rgb = center.rgb;

            if (uGradeSharpness > 0.005) {
                vec2 st = uTexelSize * 1.25;
                vec3 avg = (
                    texture2D(uTexSampler, clamp(cUv + vec2(st.x, 0.0), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv - vec2(st.x, 0.0), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv + vec2(0.0, st.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv - vec2(0.0, st.y), 0.0, 1.0)).rgb
                ) * 0.25;
                rgb = clamp(rgb + (rgb - avg) * (uGradeSharpness * 2.8), 0.0, 1.0);
            }

            if (uKeyframeBlur > 0.005) {
                vec2 bs = uTexelSize * (uKeyframeBlur * 10.0);
                vec3 bAcc = rgb * 0.28;
                bAcc += texture2D(uTexSampler, clamp(cUv + vec2(bs.x, 0.0), 0.0, 1.0)).rgb * 0.18;
                bAcc += texture2D(uTexSampler, clamp(cUv - vec2(bs.x, 0.0), 0.0, 1.0)).rgb * 0.18;
                bAcc += texture2D(uTexSampler, clamp(cUv + vec2(0.0, bs.y), 0.0, 1.0)).rgb * 0.18;
                bAcc += texture2D(uTexSampler, clamp(cUv - vec2(0.0, bs.y), 0.0, 1.0)).rgb * 0.18;
                rgb = bAcc;
            }
            return vec4(rgb, center.a);
        }

        void main() {
            vec2 uv = vTexSamplingCoord;
            float p = clamp(uTransitionProgress, 0.0, 1.0);
            float inv = 1.0 - p;
            vec4 col = sampleFinished(uv);

            if (p < 0.999 && uTransitionType > 0) {
                if (uTransitionType == 1 || uTransitionType == 2) {
                    // FADE / DISSOLVE
                    col.rgb *= p;
                } else if (uTransitionType == 3) {
                    // ZOOM PUNCH
                    float z = 1.0 + inv * 0.85;
                    vec2 zUv = (uv - 0.5) / z + 0.5;
                    col = sampleFinished(zUv);
                    col.rgb *= smoothstep(0.0, 0.35, p);
                } else if (uTransitionType == 4) {
                    // SPIN WHIRL
                    float ang = inv * 1.5707963;
                    vec2 d = uv - 0.5;
                    vec2 rUv = vec2(d.x * cos(ang) - d.y * sin(ang), d.x * sin(ang) + d.y * cos(ang)) / (1.0 + inv * 0.4) + 0.5;
                    col = sampleFinished(rUv);
                    col.rgb *= p;
                } else if (uTransitionType == 5 || uTransitionType == 9 || uTransitionType == 11) {
                    // SWIPE / SLIDE / PUSH
                    vec2 sUv = uv + vec2(inv, 0.0);
                    if (sUv.x > 1.0 || sUv.x < 0.0) {
                        col = vec4(0.0, 0.0, 0.0, 1.0);
                    } else {
                        col = sampleFinished(sUv);
                    }
                } else if (uTransitionType == 6) {
                    // BLUR WHIP
                    vec2 wStep = uTexelSize * inv * 24.0;
                    vec3 acc = (
                        sampleFinished(uv - wStep).rgb +
                        sampleFinished(uv).rgb +
                        sampleFinished(uv + wStep).rgb
                    ) / 3.0;
                    col.rgb = acc * smoothstep(0.0, 0.5, p);
                } else if (uTransitionType == 7) {
                    // GLITCH TRANSITION
                    float shift = inv * 0.08 * uTransitionChromatic;
                    float r = sampleFinished(uv + vec2(shift, 0.0)).r;
                    float g = col.g;
                    float b = sampleFinished(uv - vec2(shift, 0.0)).b;
                    col.rgb = vec3(r, g, b) * smoothstep(0.0, 0.3, p);
                } else if (uTransitionType == 8) {
                    // WHITE FLASH
                    col.rgb = clamp(col.rgb + vec3(inv * inv * 1.25), 0.0, 1.0);
                } else if (uTransitionType == 10) {
                    // LINEAR WIPE
                    float soft = max(0.02, uTransitionSoftness * 0.35);
                    float mask = smoothstep(uv.x - soft, uv.x + soft, p * (1.0 + soft));
                    col.rgb *= mask;
                } else if (uTransitionType == 12) {
                    // CINEMATIC BURN
                    vec3 burn = vec3(1.0, 0.42, 0.08) * (inv * 1.4);
                    col.rgb = clamp(col.rgb * p + burn * (1.0 - abs(p * 2.0 - 1.0)), 0.0, 1.0);
                } else {
                    // CUSTOM PRO
                    float shift = inv * 0.05 * uTransitionChromatic;
                    vec2 zUv = (uv - 0.5) / (1.0 + inv * 0.35) + 0.5;
                    float r = sampleFinished(zUv + vec2(shift, 0.0)).r;
                    float g = sampleFinished(zUv).g;
                    float b = sampleFinished(zUv - vec2(shift, 0.0)).b;
                    col.rgb = vec3(r, g, b) * p;
                }
            }

            gl_FragColor = vec4(col.rgb * uOpacity, col.a * uOpacity);
        }
    """.trimIndent()

    fun transitionCode(type: TransitionType?): Int = when (type) {
        null -> 0
        TransitionType.FADE -> 1
        TransitionType.DISSOLVE -> 2
        TransitionType.ZOOM -> 3
        TransitionType.SPIN -> 4
        TransitionType.SWIPE -> 5
        TransitionType.BLUR -> 6
        TransitionType.GLITCH -> 7
        TransitionType.FLASH -> 8
        TransitionType.SLIDE -> 9
        TransitionType.WIPE -> 10
        TransitionType.PUSH -> 11
        TransitionType.CINEMATIC -> 12
        TransitionType.CUSTOM -> 13
    }
}
