package com.vibecut.pro.engine.effects

import com.vibecut.pro.engine.model.EffectType
import com.vibecut.pro.engine.model.TransitionType

/**
 * Generates OpenGL ES 2.0 (GLSL 100) vertex & fragment shaders for Media3's `GlProgram`.
 * Covers all 32 [EffectType] GPU effects (with 3 customizable uniforms: `uIntensity`, `uSecondary`, `uTertiary`),
 * clip transitions in [TransitionType], and real-time spatial detail/sharpness/clarity/vignette/grain finishing.
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
     */
    fun buildEffectFragmentShader(type: EffectType): String {
        val effectBody = when (type) {
            EffectType.SHARPEN -> """
                // Pro Unsharp Mask + Noise Guard Edge Threshold
                float radius = mix(0.65, 3.4, uSecondary);
                vec2 stepUv = uTexelSize * radius;
                vec4 center = texture2D(uTexSampler, uv);
                vec4 north  = texture2D(uTexSampler, clamp(uv + vec2(0.0, stepUv.y), 0.0, 1.0));
                vec4 south  = texture2D(uTexSampler, clamp(uv - vec2(0.0, stepUv.y), 0.0, 1.0));
                vec4 east   = texture2D(uTexSampler, clamp(uv + vec2(stepUv.x, 0.0), 0.0, 1.0));
                vec4 west   = texture2D(uTexSampler, clamp(uv - vec2(stepUv.x, 0.0), 0.0, 1.0));
                vec4 ne     = texture2D(uTexSampler, clamp(uv + stepUv, 0.0, 1.0));
                vec4 nw     = texture2D(uTexSampler, clamp(uv + vec2(-stepUv.x, stepUv.y), 0.0, 1.0));
                vec4 se     = texture2D(uTexSampler, clamp(uv + vec2(stepUv.x, -stepUv.y), 0.0, 1.0));
                vec4 sw     = texture2D(uTexSampler, clamp(uv - stepUv, 0.0, 1.0));
                vec4 blurred = (center * 4.0 + (north + south + east + west) * 2.0 + (ne + nw + se + sw)) / 16.0;
                vec3 detail = center.rgb - blurred.rgb;
                float edgeMag = max(max(abs(detail.r), abs(detail.g)), abs(detail.b));
                float guard = smoothstep(uTertiary * 0.12, uTertiary * 0.12 + 0.035, edgeMag);
                float amount = uIntensity * 3.4 * guard;
                vec3 sharpened = clamp(center.rgb + detail * amount, 0.0, 1.0);
                gl_FragColor = vec4(sharpened, center.a);
            """

            EffectType.UNSHARP_PRO -> """
                // Multi-Scale HDR Structure & Micro-Texture Enhancer
                vec4 center = texture2D(uTexSampler, uv);
                vec2 fineStep = uTexelSize * mix(0.8, 2.2, uSecondary);
                vec2 wideStep = fineStep * 2.8;
                vec3 fineAvg = (
                    texture2D(uTexSampler, clamp(uv + vec2(fineStep.x, 0.0), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv - vec2(fineStep.x, 0.0), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv + vec2(0.0, fineStep.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv - vec2(0.0, fineStep.y), 0.0, 1.0)).rgb
                ) * 0.25;
                vec3 wideAvg = (
                    texture2D(uTexSampler, clamp(uv + vec2(wideStep.x, wideStep.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv + vec2(-wideStep.x, wideStep.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv + vec2(wideStep.x, -wideStep.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv + vec2(-wideStep.x, -wideStep.y), 0.0, 1.0)).rgb
                ) * 0.25;
                float luma = dot(center.rgb, vec3(0.2126, 0.7152, 0.0722));
                float midMask = 4.0 * luma * (1.0 - luma);
                float haloClamp = mix(0.35, 0.08, uTertiary);
                vec3 micro = clamp(center.rgb - fineAvg, -haloClamp, haloClamp);
                vec3 local = clamp(center.rgb - wideAvg, -0.25, 0.25) * midMask;
                vec3 outRgb = clamp(center.rgb + micro * (uIntensity * 2.8) + local * (uIntensity * 1.5), 0.0, 1.0);
                gl_FragColor = vec4(outRgb, center.a);
            """

            EffectType.EDGE_SHARPEN -> """
                // Sobel-Guided Adaptive Edge Clarifier + Non-Edge Skin Smoothing
                vec4 center = texture2D(uTexSampler, uv);
                vec2 t = uTexelSize * 1.35;
                vec3 n = texture2D(uTexSampler, clamp(uv + vec2(0.0, t.y), 0.0, 1.0)).rgb;
                vec3 s = texture2D(uTexSampler, clamp(uv - vec2(0.0, t.y), 0.0, 1.0)).rgb;
                vec3 e = texture2D(uTexSampler, clamp(uv + vec2(t.x, 0.0), 0.0, 1.0)).rgb;
                vec3 w = texture2D(uTexSampler, clamp(uv - vec2(t.x, 0.0), 0.0, 1.0)).rgb;
                vec3 avg = (n + s + e + w) * 0.25;
                float edge = length(n - s) + length(e - w);
                float thresh = mix(0.03, 0.28, uSecondary);
                float isEdge = smoothstep(thresh * 0.6, thresh * 1.6, edge);
                vec3 crisp = clamp(center.rgb + (center.rgb - avg) * (uIntensity * 3.5), 0.0, 1.0);
                vec3 smoothSkin = mix(center.rgb, avg, uTertiary * 0.65);
                gl_FragColor = vec4(mix(smoothSkin, crisp, isEdge), center.a);
            """

            EffectType.BLUR -> """
                float spread = uIntensity * mix(2.0, 9.5, uSecondary);
                vec2 s = uTexelSize * spread;
                vec4 base = texture2D(uTexSampler, uv);
                vec4 sum = vec4(0.0);
                sum += texture2D(uTexSampler, clamp(uv + vec2(-s.x, -s.y), 0.0, 1.0));
                sum += texture2D(uTexSampler, clamp(uv + vec2( 0.0, -s.y), 0.0, 1.0));
                sum += texture2D(uTexSampler, clamp(uv + vec2( s.x, -s.y), 0.0, 1.0));
                sum += texture2D(uTexSampler, clamp(uv + vec2(-s.x,  0.0), 0.0, 1.0));
                sum += base;
                sum += texture2D(uTexSampler, clamp(uv + vec2( s.x,  0.0), 0.0, 1.0));
                sum += texture2D(uTexSampler, clamp(uv + vec2(-s.x,  s.y), 0.0, 1.0));
                sum += texture2D(uTexSampler, clamp(uv + vec2( 0.0,  s.y), 0.0, 1.0));
                sum += texture2D(uTexSampler, clamp(uv + vec2( s.x,  s.y), 0.0, 1.0));
                vec4 blurred = sum / 9.0;
                gl_FragColor = mix(base, blurred, clamp(0.35 + uTertiary * 0.65, 0.0, 1.0));
            """

            EffectType.GAUSSIAN_BLUR -> """
                float sigma = uIntensity * mix(2.5, 13.0, uSecondary);
                vec2 d1 = uTexelSize * sigma * 0.65;
                vec2 d2 = uTexelSize * sigma * 1.45;
                vec4 c = texture2D(uTexSampler, uv) * 0.227027;
                c += texture2D(uTexSampler, clamp(uv + vec2(d1.x, 0.0), 0.0, 1.0)) * 0.121621;
                c += texture2D(uTexSampler, clamp(uv - vec2(d1.x, 0.0), 0.0, 1.0)) * 0.121621;
                c += texture2D(uTexSampler, clamp(uv + vec2(0.0, d1.y), 0.0, 1.0)) * 0.121621;
                c += texture2D(uTexSampler, clamp(uv - vec2(0.0, d1.y), 0.0, 1.0)) * 0.121621;
                c += texture2D(uTexSampler, clamp(uv + d2, 0.0, 1.0)) * 0.071622;
                c += texture2D(uTexSampler, clamp(uv - d2, 0.0, 1.0)) * 0.071622;
                c += texture2D(uTexSampler, clamp(uv + vec2(-d2.x, d2.y), 0.0, 1.0)) * 0.071622;
                c += texture2D(uTexSampler, clamp(uv + vec2(d2.x, -d2.y), 0.0, 1.0)) * 0.071622;
                float warm = (uTertiary - 0.5) * 0.10 * uIntensity;
                c.rgb = clamp(c.rgb + vec3(warm, 0.0, -warm), 0.0, 1.0);
                gl_FragColor = c;
            """

            EffectType.MOTION_BLUR -> """
                float angle = uSecondary * 6.2831853;
                float centerKeep = 1.0 - smoothstep(0.0, 0.55, distance(uv, vec2(0.5))) * uTertiary;
                float streak = uIntensity * (1.0 - uTertiary * 0.5 * centerKeep);
                vec2 dir = vec2(cos(angle), sin(angle)) * uTexelSize * streak * 20.0;
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
                float focusRadius = mix(0.18, 0.58, uSecondary);
                float focusY = mix(0.32, 0.72, uTertiary);
                float dist = distance(uv, vec2(0.5, focusY));
                float mask = smoothstep(focusRadius * 0.68, focusRadius * 1.38, dist);
                vec2 bStep = uTexelSize * (uIntensity * 12.0 * mask);
                vec4 bg = sharp * 0.20;
                bg += texture2D(uTexSampler, clamp(uv + vec2(bStep.x, 0.0), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv - vec2(bStep.x, 0.0), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv + vec2(0.0, bStep.y), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv - vec2(0.0, bStep.y), 0.0, 1.0)) * 0.15;
                bg += texture2D(uTexSampler, clamp(uv + bStep, 0.0, 1.0)) * 0.10;
                bg += texture2D(uTexSampler, clamp(uv - bStep, 0.0, 1.0)) * 0.10;
                gl_FragColor = mix(sharp, bg, mask * uIntensity);
            """

            EffectType.RADIAL_ZOOM_BLUR -> """
                vec2 rel = uv - vec2(0.5);
                float dist = length(rel);
                float edgeMask = smoothstep(uSecondary * 0.45, 0.75, dist);
                float strength = uIntensity * edgeMask * 0.24;
                float twist = (uTertiary - 0.5) * uIntensity * edgeMask * 0.35;
                float cs = cos(twist);
                float sn = sin(twist);
                vec2 rotRel = vec2(rel.x * cs - rel.y * sn, rel.x * sn + rel.y * cs);
                vec4 acc = vec4(0.0);
                acc += texture2D(uTexSampler, clamp(vec2(0.5) + rotRel * (1.0 - strength * 0.85), 0.0, 1.0)) * 0.14;
                acc += texture2D(uTexSampler, clamp(vec2(0.5) + rotRel * (1.0 - strength * 0.55), 0.0, 1.0)) * 0.20;
                acc += texture2D(uTexSampler, clamp(vec2(0.5) + rotRel * (1.0 - strength * 0.25), 0.0, 1.0)) * 0.24;
                acc += texture2D(uTexSampler, uv) * 0.26;
                acc += texture2D(uTexSampler, clamp(vec2(0.5) + rotRel * (1.0 + strength * 0.20), 0.0, 1.0)) * 0.16;
                gl_FragColor = acc;
            """

            EffectType.GLOW -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 gStep = uTexelSize * mix(3.0, 9.5, uTertiary);
                vec3 bloom = vec3(0.0);
                bloom += texture2D(uTexSampler, clamp(uv + vec2(gStep.x, 0.0), 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv - vec2(gStep.x, 0.0), 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv + vec2(0.0, gStep.y), 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv - vec2(0.0, gStep.y), 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv + gStep * 0.7, 0.0, 1.0)).rgb;
                bloom += texture2D(uTexSampler, clamp(uv - gStep * 0.7, 0.0, 1.0)).rgb;
                bloom /= 6.0;
                float thresh = mix(0.28, 0.78, uSecondary);
                vec3 hi = max(bloom - vec3(thresh), vec3(0.0)) / max(1.0 - thresh, 0.1);
                gl_FragColor = vec4(clamp(base.rgb + hi * uIntensity * 1.45, 0.0, 1.0), base.a);
            """

            EffectType.NEON_GLOW -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 t = uTexelSize * 1.6;
                float tl = dot(texture2D(uTexSampler, clamp(uv + vec2(-t.x,  t.y), 0.0, 1.0)).rgb, vec3(0.299, 0.587, 0.114));
                float tr = dot(texture2D(uTexSampler, clamp(uv + vec2( t.x,  t.y), 0.0, 1.0)).rgb, vec3(0.299, 0.587, 0.114));
                float bl = dot(texture2D(uTexSampler, clamp(uv + vec2(-t.x, -t.y), 0.0, 1.0)).rgb, vec3(0.299, 0.587, 0.114));
                float br = dot(texture2D(uTexSampler, clamp(uv + vec2( t.x, -t.y), 0.0, 1.0)).rgb, vec3(0.299, 0.587, 0.114));
                float gx = -tl + tr - bl + br;
                float gy =  tl + tr - bl - br;
                float edge = clamp(length(vec2(gx, gy)) * 3.4, 0.0, 1.0);
                float pulseSpd = mix(0.0, 4.5, uTertiary);
                float phase = uSecondary * 6.28318 + uTimeSec * pulseSpd + uv.x * 3.0;
                vec3 neonColor = 0.55 + 0.45 * cos(phase + vec3(0.0, 2.1, 4.2));
                vec3 result = mix(base.rgb, base.rgb * 0.55 + neonColor * edge * 2.3, uIntensity);
                gl_FragColor = vec4(clamp(result, 0.0, 1.0), base.a);
            """

            EffectType.HALATION -> """
                // Kodak Vision3 Red-Orange Highlight Edge Halation
                vec4 base = texture2D(uTexSampler, uv);
                vec2 hStep = uTexelSize * mix(3.5, 11.0, uSecondary);
                vec3 ring = (
                    texture2D(uTexSampler, clamp(uv + vec2(hStep.x, 0.0), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv - vec2(hStep.x, 0.0), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv + vec2(0.0, hStep.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv - vec2(0.0, hStep.y), 0.0, 1.0)).rgb
                ) * 0.25;
                float lumRing = dot(ring, vec3(0.299, 0.587, 0.114));
                float cutoff = mix(0.45, 0.85, uTertiary);
                float halo = smoothstep(cutoff, 1.0, lumRing) * uIntensity;
                vec3 warmHalo = vec3(1.0, 0.28, 0.08) * halo * 0.85;
                vec3 outCol = 1.0 - (1.0 - base.rgb) * (1.0 - clamp(warmHalo, 0.0, 1.0));
                gl_FragColor = vec4(outCol, base.a);
            """

            EffectType.DREAMY_BOKEH -> """
                // Black Pro-Mist Cinema Highlight Diffusion
                vec4 base = texture2D(uTexSampler, uv);
                vec2 mStep = uTexelSize * mix(3.0, 9.0, uSecondary);
                vec3 mist = (
                    texture2D(uTexSampler, clamp(uv + mStep, 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv - mStep, 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv + vec2(-mStep.x, mStep.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(uv + vec2(mStep.x, -mStep.y), 0.0, 1.0)).rgb
                ) * 0.25;
                vec3 warmTint = mix(vec3(1.0), vec3(1.06, 0.99, 0.92), uTertiary);
                vec3 screenMist = 1.0 - (1.0 - base.rgb) * (1.0 - mist * warmTint * 0.55);
                gl_FragColor = vec4(mix(base.rgb, screenMist, uIntensity * 0.85), base.a);
            """

            EffectType.GLITCH -> """
                float speed = floor(uTimeSec * mix(6.0, 24.0, uSecondary));
                float band = floor(uv.y * 22.0);
                float n = fract(sin(dot(vec2(band, speed), vec2(12.9898, 78.233))) * 43758.5453);
                float trigger = step(1.0 - uIntensity * 0.58, n);
                float shift = (n - 0.5) * 0.13 * uIntensity * trigger;
                vec2 gUv = vec2(clamp(uv.x + shift, 0.0, 1.0), uv.y);
                float rgbSplit = 0.022 * uIntensity * uTertiary;
                float r = texture2D(uTexSampler, clamp(gUv + vec2(rgbSplit, 0.0), 0.0, 1.0)).r;
                float g = texture2D(uTexSampler, gUv).g;
                float b = texture2D(uTexSampler, clamp(gUv - vec2(rgbSplit, 0.0), 0.0, 1.0)).b;
                gl_FragColor = vec4(r, g, b, 1.0);
            """

            EffectType.RGB_SPLIT -> """
                float angle = uSecondary * 6.2831853;
                float edgeMask = mix(1.0, smoothstep(0.08, 0.65, distance(uv, vec2(0.5))), uTertiary);
                vec2 offset = vec2(cos(angle), sin(angle)) * (0.028 * uIntensity * edgeMask);
                float r = texture2D(uTexSampler, clamp(uv + offset, 0.0, 1.0)).r;
                vec4 mid = texture2D(uTexSampler, uv);
                float b = texture2D(uTexSampler, clamp(uv - offset, 0.0, 1.0)).b;
                gl_FragColor = vec4(r, mid.g, b, mid.a);
            """

            EffectType.VHS -> """
                float barY = fract(uTimeSec * 0.35);
                float barMask = smoothstep(0.055, 0.0, abs(uv.y - barY));
                float jitter = (fract(sin((uv.y + uTimeSec) * 432.1) * 9876.5) - 0.5) * 0.013 * uIntensity;
                vec2 vUv = vec2(clamp(uv.x + jitter + barMask * 0.032 * uSecondary, 0.0, 1.0), uv.y);
                float bleed = 0.012 * uIntensity * (0.3 + uTertiary);
                float r = texture2D(uTexSampler, clamp(vUv + vec2(bleed, 0.0), 0.0, 1.0)).r;
                float g = texture2D(uTexSampler, vUv).g;
                float b = texture2D(uTexSampler, clamp(vUv - vec2(bleed * 1.2, 0.0), 0.0, 1.0)).b;
                float scan = 0.91 + 0.09 * sin(uv.y * 540.0);
                vec3 vhs = vec3(r, g, b) * scan + vec3(barMask * 0.14 * uIntensity);
                gl_FragColor = vec4(clamp(vhs, 0.0, 1.0), 1.0);
            """

            EffectType.SCANLINES_CRT -> """
                vec2 centered = uv * 2.0 - 1.0;
                float r2 = dot(centered, centered);
                vec2 curved = (centered * (1.0 + 0.16 * uTertiary * uIntensity * r2)) * 0.5 + 0.5;
                if (curved.x < 0.0 || curved.x > 1.0 || curved.y < 0.0 || curved.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    vec4 base = texture2D(uTexSampler, curved);
                    float density = mix(220.0, 720.0, uSecondary);
                    float scan = 1.0 - uIntensity * 0.32 * (0.5 + 0.5 * sin(curved.y * density));
                    float subpx = 0.94 + 0.06 * sin(curved.x * density * 1.5);
                    float vig = smoothstep(1.35, 0.45, length(centered));
                    gl_FragColor = vec4(base.rgb * scan * subpx * mix(1.0, vig, uIntensity * 0.5), base.a);
                }
            """

            EffectType.PIXELATE -> """
                float blocks = mix(140.0, 18.0, uIntensity);
                float aspect = max(0.3, uTexelSize.y / max(uTexelSize.x, 0.00001));
                vec2 grid = vec2(blocks, blocks * aspect);
                vec2 cellUv = (floor(uv * grid) + 0.5) / grid;
                vec4 col = texture2D(uTexSampler, clamp(cellUv, 0.0, 1.0));
                if (uSecondary > 0.02) {
                    float levels = mix(24.0, 4.0, uSecondary);
                    col.rgb = floor(col.rgb * levels + 0.5) / levels;
                }
                vec2 cellLocal = fract(uv * grid);
                float border = step(0.06 * uTertiary, cellLocal.x) * step(0.06 * uTertiary, cellLocal.y);
                col.rgb *= mix(1.0, border, uTertiary * 0.65);
                gl_FragColor = col;
            """

            EffectType.FILM_GRAIN -> """
                vec4 base = texture2D(uTexSampler, uv);
                float scale = mix(460.0, 130.0, uSecondary);
                vec2 seed = floor(uv * scale) + vec2(fract(uTimeSec) * 97.0, fract(uTimeSec * 0.73) * 53.0);
                float noise = fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                float luma = dot(base.rgb, vec3(0.299, 0.587, 0.114));
                float weight = mix(1.0, 1.0 - luma * 0.55, uTertiary);
                vec3 grained = clamp(base.rgb + vec3(noise * uIntensity * 0.28 * weight), 0.0, 1.0);
                gl_FragColor = vec4(grained, base.a);
            """

            EffectType.CINEMATIC -> """
                vec4 base = texture2D(uTexSampler, uv);
                float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
                vec3 shadowsTeal = vec3(0.0, 0.06, 0.11) * (1.0 - luma) * (0.5 + uTertiary);
                vec3 hiWarm = vec3(0.11, 0.05, -0.04) * luma;
                vec3 graded = clamp((base.rgb - 0.5) * (1.0 + 0.24 * uIntensity) + 0.5 + (shadowsTeal + hiWarm) * uIntensity, 0.0, 1.0);
                float barSize = 0.11 * uSecondary * uIntensity;
                float bar = step(barSize, uv.y) * step(uv.y, 1.0 - barSize);
                gl_FragColor = vec4(graded * bar, base.a);
            """

            EffectType.SHAKE -> """
                float freq = mix(10.0, 38.0, uSecondary);
                float dx = sin(uTimeSec * freq) * cos(uTimeSec * freq * 0.73) * 0.036 * uIntensity;
                float dy = cos(uTimeSec * freq * 1.13) * 0.030 * uIntensity;
                float ang = sin(uTimeSec * freq * 0.85) * 0.045 * uIntensity * uTertiary;
                vec2 rel = (uv - 0.5) * (1.0 - 0.09 * uIntensity);
                vec2 rot = vec2(rel.x * cos(ang) - rel.y * sin(ang), rel.x * sin(ang) + rel.y * cos(ang));
                vec2 zoomedUv = rot + 0.5 + vec2(dx, dy);
                gl_FragColor = texture2D(uTexSampler, clamp(zoomedUv, 0.0, 1.0));
            """

            EffectType.ZOOM -> """
                float pulse = 0.5 + 0.5 * sin(uTimeSec * mix(2.0, 11.0, uSecondary));
                pulse = mix(pulse, pulse * pulse * (3.0 - 2.0 * pulse), uTertiary);
                float z = 1.0 + uIntensity * 0.45 * pulse;
                vec2 zUv = (uv - 0.5) / z + 0.5;
                gl_FragColor = texture2D(uTexSampler, clamp(zUv, 0.0, 1.0));
            """

            EffectType.MIRROR_KALEIDOSCOPE -> """
                vec4 orig = texture2D(uTexSampler, uv);
                float center = mix(0.3, 0.7, uTertiary);
                vec2 mUv = uv;
                if (uSecondary < 0.34) {
                    mUv.x = center - abs(uv.x - center);
                } else if (uSecondary < 0.67) {
                    mUv.x = 0.5 - abs(uv.x - 0.5);
                    mUv.y = 0.5 - abs(uv.y - 0.5);
                } else {
                    vec2 d = abs(uv - vec2(0.5));
                    float mn = min(d.x, d.y);
                    float mx = max(d.x, d.y);
                    mUv = clamp(vec2(0.5) - vec2(mx, mn), 0.0, 1.0);
                }
                vec4 mirrored = texture2D(uTexSampler, clamp(mUv, 0.0, 1.0));
                gl_FragColor = mix(orig, mirrored, uIntensity);
            """

            EffectType.FLASH -> """
                vec4 base = texture2D(uTexSampler, uv);
                float rate = mix(2.5, 16.0, uSecondary);
                float strobe = pow(max(0.0, sin(uTimeSec * rate * 3.14159)), 4.0) * uIntensity;
                vec3 flashTint = mix(vec3(0.85, 0.95, 1.0), vec3(1.0, 0.92, 0.80), uTertiary);
                gl_FragColor = vec4(clamp(base.rgb + flashTint * (strobe * 0.85), 0.0, 1.0), base.a);
            """

            EffectType.LIGHT_LEAKS -> """
                vec4 base = texture2D(uTexSampler, uv);
                float spd = mix(0.4, 2.2, uTertiary);
                float wave1 = max(0.0, sin(uv.x * 3.2 + uTimeSec * 1.4 * spd) * cos(uv.y * 2.3 - uTimeSec * 0.8 * spd));
                float corner = smoothstep(0.85, 0.0, distance(uv, vec2(0.10 + 0.25 * sin(uTimeSec * 0.6 * spd), 0.85)));
                vec3 warmColor = mix(vec3(1.0, 0.22, 0.55), vec3(1.0, 0.58, 0.12), uSecondary);
                vec3 leak = warmColor * (wave1 * 0.45 + corner * 0.85) * uIntensity;
                vec3 screened = 1.0 - (1.0 - base.rgb) * (1.0 - clamp(leak, 0.0, 1.0));
                gl_FragColor = vec4(screened, base.a);
            """

            EffectType.CHROMATIC_ABERRATION -> """
                vec2 d = uv - vec2(0.5);
                float r2 = dot(d, d) * mix(1.0, 3.0, uSecondary);
                vec2 shift = d * (r2 * 0.15 + uTertiary * 0.025) * uIntensity;
                float r = texture2D(uTexSampler, clamp(uv + shift, 0.0, 1.0)).r;
                vec4 gSample = texture2D(uTexSampler, uv);
                float b = texture2D(uTexSampler, clamp(uv - shift, 0.0, 1.0)).b;
                gl_FragColor = vec4(r, gSample.g, b, gSample.a);
            """

            EffectType.VIGNETTE -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 d = (uv - vec2(0.5)) * vec2(1.0, mix(0.8, 1.35, uSecondary));
                float dist = length(d);
                float inner = mix(0.15, 0.45, uTertiary);
                float vig = smoothstep(0.86, inner, dist);
                float factor = mix(1.0, vig, uIntensity);
                gl_FragColor = vec4(base.rgb * factor, base.a);
            """

            EffectType.LENS_DISTORTION -> """
                vec2 centered = uv * 2.0 - 1.0;
                float r2 = dot(centered, centered);
                float k = (uSecondary - 0.5) * 1.15 * uIntensity;
                vec2 warped = centered * (1.0 + k * r2);
                vec2 finalUv = warped * 0.5 + 0.5;
                if (finalUv.x < 0.0 || finalUv.x > 1.0 || finalUv.y < 0.0 || finalUv.y > 1.0) {
                    if (uTertiary > 0.5) {
                        gl_FragColor = texture2D(uTexSampler, clamp(finalUv, 0.0, 1.0));
                    } else {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    }
                } else {
                    gl_FragColor = texture2D(uTexSampler, finalUv);
                }
            """

            EffectType.OLD_FILM -> """
                vec4 base = texture2D(uTexSampler, uv);
                float gray = dot(base.rgb, vec3(0.299, 0.587, 0.114));
                vec3 sepia = vec3(gray * 1.12, gray * 0.95, gray * 0.74);
                float flicker = 1.0 - (0.12 * uTertiary) * (0.5 + 0.5 * sin(uTimeSec * 29.0));
                float lineX = fract(sin(floor(uTimeSec * 12.0) * 19.31) * 4375.8);
                float scratch = step(abs(uv.x - lineX), 0.0018 * uSecondary) * 0.55;
                float vig = smoothstep(0.90, 0.30, length(uv - 0.5));
                vec3 film = clamp(sepia * flicker * vig + vec3(scratch), 0.0, 1.0);
                gl_FragColor = vec4(mix(base.rgb, film, uIntensity), base.a);
            """

            EffectType.CARTOON -> """
                vec4 base = texture2D(uTexSampler, uv);
                vec2 t = uTexelSize * mix(1.0, 2.6, uTertiary);
                vec3 n = texture2D(uTexSampler, clamp(uv + vec2(0.0, t.y), 0.0, 1.0)).rgb;
                vec3 s = texture2D(uTexSampler, clamp(uv - vec2(0.0, t.y), 0.0, 1.0)).rgb;
                vec3 e = texture2D(uTexSampler, clamp(uv + vec2(t.x, 0.0), 0.0, 1.0)).rgb;
                vec3 w = texture2D(uTexSampler, clamp(uv - vec2(t.x, 0.0), 0.0, 1.0)).rgb;
                float edge = length(n - s) + length(e - w);
                float ink = 1.0 - smoothstep(0.16, 0.36, edge * (1.0 + uIntensity));
                float levels = mix(3.0, 9.0, uSecondary);
                vec3 poster = floor(base.rgb * levels + 0.5) / levels;
                vec3 cel = poster * ink;
                gl_FragColor = vec4(mix(base.rgb, cel, uIntensity), base.a);
            """

            EffectType.COLOR_DISTORTION -> """
                vec4 base = texture2D(uTexSampler, uv);
                float spd = mix(0.0, 3.2, uTertiary);
                float phase = uSecondary * 6.28318 + uTimeSec * spd;
                vec3 shifted = 0.5 + 0.5 * sin(base.rgb * 6.28318 * (1.0 + uIntensity) + vec3(phase, phase + 2.09, phase + 4.18));
                gl_FragColor = vec4(mix(base.rgb, shifted, uIntensity), base.a);
            """

            EffectType.DUOTONE -> """
                vec4 base = texture2D(uTexSampler, uv);
                float luma = dot(base.rgb, vec3(0.2126, 0.7152, 0.0722));
                float split = smoothstep(0.15, 0.85, (luma - 0.5) * (0.8 + uTertiary * 1.4) + 0.5);
                float ang = uSecondary * 6.28318;
                vec3 darkCol = clamp(0.18 + 0.18 * cos(ang + vec3(3.1, 1.2, 0.0)), 0.0, 1.0);
                vec3 lightCol = clamp(0.72 + 0.28 * cos(ang + vec3(0.0, 1.8, 3.6)), 0.0, 1.0);
                vec3 duo = mix(darkCol, lightCol, split);
                gl_FragColor = vec4(mix(base.rgb, duo, uIntensity), base.a);
            """

            EffectType.POSTERIZE_POP -> """
                vec4 base = texture2D(uTexSampler, uv);
                float avg = (base.r + base.g + base.b) / 3.0;
                vec3 satRgb = clamp(vec3(avg) + (base.rgb - vec3(avg)) * (1.0 + uTertiary * 1.1), 0.0, 1.0);
                float steps = mix(3.0, 10.0, uSecondary);
                vec3 pop = floor(satRgb * steps + 0.5) / steps;
                gl_FragColor = vec4(mix(base.rgb, pop, uIntensity), base.a);
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
            uniform float uTertiary;

            void main() {
                vec2 uv = vTexSamplingCoord;
                $effectBody
            }
        """.trimIndent()
    }

    /**
     * Builds the Master Spatial Detail & Finishing Fragment Shader handling:
     * - Edge-Protected Unsharp-Mask Sharpening (`uGradeSharpness`, `uSharpenRadius`, `uSharpenThreshold`)
     * - Edge-Preserving Bilateral Luma Denoise / Skin Smooth (`uDenoise`)
     * - Midtone Local Contrast Clarity (`uClarity`) & Micro-Texture Structure (`uStructure`)
     * - Optical Vignette (`uVignette`, `uVignetteFeather`) & Organic Film Grain (`uGrain`)
     * - Keyframed Extra Blur (`uKeyframeBlur`)
     * - All 13 Clip Entry / Exit Transitions (`uTransitionType`, `uTransitionProgress`, etc.)
     * - Opacity alpha scaling (`uOpacity`)
     */
    fun buildMasterFinishingFragmentShader(): String = """
        precision highp float;
        varying vec2 vTexSamplingCoord;
        uniform sampler2D uTexSampler;
        uniform vec2 uTexelSize;
        uniform float uTimeSec;
        uniform float uGradeSharpness;
        uniform float uSharpenRadius;
        uniform float uSharpenThreshold;
        uniform float uClarity;
        uniform float uStructure;
        uniform float uDenoise;
        uniform float uVignette;
        uniform float uVignetteFeather;
        uniform float uGrain;
        uniform float uKeyframeBlur;
        uniform float uOpacity;
        uniform int uTransitionType;
        uniform float uTransitionProgress;
        uniform float uTransitionSoftness;
        uniform float uTransitionChromatic;

        vec4 sampleFinished(vec2 coord) {
            vec2 cUv = clamp(coord, 0.0, 1.0);
            vec4 center = texture2D(uTexSampler, cUv);
            vec3 rgb = center.rgb;

            // 1. Edge-Preserving Bilateral Luma Denoise / Skin Smooth
            if (uDenoise > 0.004) {
                vec2 ds = uTexelSize * 1.6;
                vec3 c1 = texture2D(uTexSampler, clamp(cUv + vec2(ds.x, 0.0), 0.0, 1.0)).rgb;
                vec3 c2 = texture2D(uTexSampler, clamp(cUv - vec2(ds.x, 0.0), 0.0, 1.0)).rgb;
                vec3 c3 = texture2D(uTexSampler, clamp(cUv + vec2(0.0, ds.y), 0.0, 1.0)).rgb;
                vec3 c4 = texture2D(uTexSampler, clamp(cUv - vec2(0.0, ds.y), 0.0, 1.0)).rgb;
                float l0 = dot(rgb, vec3(0.299, 0.587, 0.114));
                float w1 = exp(-abs(dot(c1, vec3(0.299, 0.587, 0.114)) - l0) * 18.0);
                float w2 = exp(-abs(dot(c2, vec3(0.299, 0.587, 0.114)) - l0) * 18.0);
                float w3 = exp(-abs(dot(c3, vec3(0.299, 0.587, 0.114)) - l0) * 18.0);
                float w4 = exp(-abs(dot(c4, vec3(0.299, 0.587, 0.114)) - l0) * 18.0);
                vec3 smoothRgb = (rgb + c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4) / (1.0 + w1 + w2 + w3 + w4);
                rgb = mix(rgb, smoothRgb, clamp(uDenoise * 0.9, 0.0, 1.0));
            }

            // 2. Pro Unsharp-Mask Sharpening (Radius + Edge Guard Threshold) + Micro-Structure
            if (uGradeSharpness > 0.004 || uStructure > 0.004) {
                float rad = mix(0.65, 3.6, clamp(uSharpenRadius, 0.0, 1.0));
                vec2 st = uTexelSize * rad;
                vec3 n = texture2D(uTexSampler, clamp(cUv + vec2(0.0, st.y), 0.0, 1.0)).rgb;
                vec3 s = texture2D(uTexSampler, clamp(cUv - vec2(0.0, st.y), 0.0, 1.0)).rgb;
                vec3 e = texture2D(uTexSampler, clamp(cUv + vec2(st.x, 0.0), 0.0, 1.0)).rgb;
                vec3 w = texture2D(uTexSampler, clamp(cUv - vec2(st.x, 0.0), 0.0, 1.0)).rgb;
                vec3 ne = texture2D(uTexSampler, clamp(cUv + st, 0.0, 1.0)).rgb;
                vec3 nw = texture2D(uTexSampler, clamp(cUv + vec2(-st.x, st.y), 0.0, 1.0)).rgb;
                vec3 se = texture2D(uTexSampler, clamp(cUv + vec2(st.x, -st.y), 0.0, 1.0)).rgb;
                vec3 sw = texture2D(uTexSampler, clamp(cUv - st, 0.0, 1.0)).rgb;
                vec3 blur = (rgb * 4.0 + (n + s + e + w) * 2.0 + (ne + nw + se + sw)) / 16.0;
                vec3 hp = rgb - blur;
                float edgeAmp = max(max(abs(hp.r), abs(hp.g)), abs(hp.b));
                float guard = smoothstep(uSharpenThreshold * 0.12, uSharpenThreshold * 0.12 + 0.03, edgeAmp);
                float totalSharp = uGradeSharpness * 3.1 * guard + uStructure * 2.0;
                rgb = clamp(rgb + clamp(hp, -0.26, 0.26) * totalSharp, 0.0, 1.0);
            }

            // 3. Midtone Local Contrast Clarity (-1..+1)
            if (abs(uClarity) > 0.004) {
                vec2 cs = uTexelSize * 4.5;
                vec3 wideBlur = (
                    texture2D(uTexSampler, clamp(cUv + vec2(cs.x, cs.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv + vec2(-cs.x, cs.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv + vec2(cs.x, -cs.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv + vec2(-cs.x, -cs.y), 0.0, 1.0)).rgb
                ) * 0.25;
                float lum = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                float midWeight = 4.0 * lum * (1.0 - lum);
                rgb = clamp(rgb + (rgb - wideBlur) * (uClarity * 1.45 * midWeight), 0.0, 1.0);
            }

            // 4. Keyframe Blur
            if (uKeyframeBlur > 0.004) {
                vec2 bs = uTexelSize * (uKeyframeBlur * 10.0);
                vec3 bAcc = rgb * 0.28;
                bAcc += texture2D(uTexSampler, clamp(cUv + vec2(bs.x, 0.0), 0.0, 1.0)).rgb * 0.18;
                bAcc += texture2D(uTexSampler, clamp(cUv - vec2(bs.x, 0.0), 0.0, 1.0)).rgb * 0.18;
                bAcc += texture2D(uTexSampler, clamp(cUv + vec2(0.0, bs.y), 0.0, 1.0)).rgb * 0.18;
                bAcc += texture2D(uTexSampler, clamp(cUv - vec2(0.0, bs.y), 0.0, 1.0)).rgb * 0.18;
                rgb = bAcc;
            }

            // 5. Optical Vignette (>0 darkens corners, <0 lifts white corners)
            if (abs(uVignette) > 0.004) {
                float dist = length(cUv - vec2(0.5));
                float inner = mix(0.18, 0.48, clamp(uVignetteFeather, 0.0, 1.0));
                float edgeMask = smoothstep(inner, 0.82, dist);
                if (uVignette > 0.0) {
                    rgb *= (1.0 - edgeMask * clamp(uVignette, 0.0, 1.0) * 0.85);
                } else {
                    rgb = mix(rgb, vec3(1.0), edgeMask * clamp(-uVignette, 0.0, 1.0) * 0.75);
                }
            }

            // 6. Organic Film Grain
            if (uGrain > 0.004) {
                vec2 gSeed = floor(cUv * 360.0) + vec2(fract(uTimeSec) * 73.0, fract(uTimeSec * 0.61) * 41.0);
                float n = fract(sin(dot(gSeed, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
                rgb = clamp(rgb + vec3(n * uGrain * 0.22), 0.0, 1.0);
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
                    col.rgb *= p;
                } else if (uTransitionType == 3) {
                    float z = 1.0 + inv * 0.85;
                    vec2 zUv = (uv - 0.5) / z + 0.5;
                    col = sampleFinished(zUv);
                    col.rgb *= smoothstep(0.0, 0.35, p);
                } else if (uTransitionType == 4) {
                    float ang = inv * 1.5707963;
                    vec2 d = uv - 0.5;
                    vec2 rUv = vec2(d.x * cos(ang) - d.y * sin(ang), d.x * sin(ang) + d.y * cos(ang)) / (1.0 + inv * 0.4) + 0.5;
                    col = sampleFinished(rUv);
                    col.rgb *= p;
                } else if (uTransitionType == 5 || uTransitionType == 9 || uTransitionType == 11) {
                    vec2 sUv = uv + vec2(inv, 0.0);
                    if (sUv.x > 1.0 || sUv.x < 0.0) {
                        col = vec4(0.0, 0.0, 0.0, 1.0);
                    } else {
                        col = sampleFinished(sUv);
                    }
                } else if (uTransitionType == 6) {
                    vec2 wStep = uTexelSize * inv * 24.0;
                    vec3 acc = (
                        sampleFinished(uv - wStep).rgb +
                        sampleFinished(uv).rgb +
                        sampleFinished(uv + wStep).rgb
                    ) / 3.0;
                    col.rgb = acc * smoothstep(0.0, 0.5, p);
                } else if (uTransitionType == 7) {
                    float shift = inv * 0.08 * uTransitionChromatic;
                    float r = sampleFinished(uv + vec2(shift, 0.0)).r;
                    float g = col.g;
                    float b = sampleFinished(uv - vec2(shift, 0.0)).b;
                    col.rgb = vec3(r, g, b) * smoothstep(0.0, 0.3, p);
                } else if (uTransitionType == 8) {
                    col.rgb = clamp(col.rgb + vec3(inv * inv * 1.25), 0.0, 1.0);
                } else if (uTransitionType == 10) {
                    float soft = max(0.02, uTransitionSoftness * 0.35);
                    float mask = smoothstep(uv.x - soft, uv.x + soft, p * (1.0 + soft));
                    col.rgb *= mask;
                } else if (uTransitionType == 12) {
                    vec3 burn = vec3(1.0, 0.42, 0.08) * (inv * 1.4);
                    col.rgb = clamp(col.rgb * p + burn * (1.0 - abs(p * 2.0 - 1.0)), 0.0, 1.0);
                } else {
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
