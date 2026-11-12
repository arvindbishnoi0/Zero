package com.vibecut.pro.engine.effects

import com.vibecut.pro.engine.model.EffectType
import com.vibecut.pro.engine.model.TransitionType

/**
 * Generates the OpenGL ES 2.0 (GLSL 100) fragment shaders used by ZERO.
 *
 * Every [EffectType] maps to a real shader body that consumes the three live uniforms
 * `uIntensity`, `uSecondary` and `uTertiary`, plus `uTimeSec` and `uTexelSize`. The exact same
 * shader text is compiled for the Media3 preview VideoGraph and for the export Transformer, so
 * what you see is what you get.
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

    private const val HEADER = """
        precision highp float;
        varying vec2 vTexSamplingCoord;
        uniform sampler2D uTexSampler;
        uniform vec2 uTexelSize;
        uniform float uTimeSec;
        uniform float uIntensity;
        uniform float uSecondary;
        uniform float uTertiary;

        vec4 tex(vec2 uv) {
            return texture2D(uTexSampler, clamp(uv, 0.0, 1.0));
        }
        float luma(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
        }
        vec3 rgb2hsv(vec3 c) {
            vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
            vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
            vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
            float d = q.x - min(q.w, q.y);
            float e = 1.0e-10;
            return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
        }
    """

    /** Returns a complete fragment shader for a single [EffectType] pass. */
    fun buildEffectFragmentShader(type: EffectType): String {
        val body = when (type) {
            EffectType.BLUR -> """
                float spread = uIntensity * mix(2.0, 9.0, uSecondary);
                vec2 s = uTexelSize * spread;
                vec4 base = tex(uv);
                vec4 sum = base;
                sum += tex(uv + vec2(-s.x, -s.y));
                sum += tex(uv + vec2( 0.0, -s.y));
                sum += tex(uv + vec2( s.x, -s.y));
                sum += tex(uv + vec2(-s.x,  0.0));
                sum += tex(uv + vec2( s.x,  0.0));
                sum += tex(uv + vec2(-s.x,  s.y));
                sum += tex(uv + vec2( 0.0,  s.y));
                sum += tex(uv + vec2( s.x,  s.y));
                vec4 blurred = sum / 9.0;
                gl_FragColor = mix(base, blurred, clamp(uIntensity + uTertiary * 0.5, 0.0, 1.0) * 0.9 + 0.1);
            """

            EffectType.GAUSSIAN_BLUR -> """
                float sigma = uIntensity * mix(2.5, 12.0, uSecondary);
                vec2 d1 = uTexelSize * sigma * 0.7;
                vec2 d2 = uTexelSize * sigma * 1.6;
                vec4 c = tex(uv) * 0.227027;
                c += tex(uv + vec2(d1.x, 0.0)) * 0.121621;
                c += tex(uv - vec2(d1.x, 0.0)) * 0.121621;
                c += tex(uv + vec2(0.0, d1.y)) * 0.121621;
                c += tex(uv - vec2(0.0, d1.y)) * 0.121621;
                c += tex(uv + d2) * 0.071622;
                c += tex(uv - d2) * 0.071622;
                c += tex(uv + vec2(-d2.x, d2.y)) * 0.071622;
                c += tex(uv + vec2(d2.x, -d2.y)) * 0.071622;
                float warm = (uTertiary - 0.5) * 0.10 * uIntensity;
                c.rgb = clamp(c.rgb + vec3(warm, 0.0, -warm), 0.0, 1.0);
                gl_FragColor = c;
            """

            EffectType.MOTION_BLUR -> """
                float angle = uSecondary * 6.2831853;
                float radial = smoothstep(0.0, 0.7, distance(uv, vec2(0.5)));
                float streak = uIntensity * (1.0 - uTertiary * 0.7 * (1.0 - radial));
                vec2 dir = vec2(cos(angle), sin(angle)) * uTexelSize * streak * 18.0;
                vec4 acc = vec4(0.0);
                acc += tex(uv - dir * 1.00) * 0.16;
                acc += tex(uv - dir * 0.66) * 0.20;
                acc += tex(uv - dir * 0.33) * 0.24;
                acc += tex(uv) * 0.28;
                acc += tex(uv + dir * 0.33) * 0.20;
                acc += tex(uv + dir * 0.66) * 0.12;
                gl_FragColor = acc;
            """

            EffectType.RADIAL_ZOOM_BLUR -> """
                vec2 center = uv - vec2(0.5);
                float dist = length(center);
                float safe = mix(0.0, 0.55, uSecondary);
                float amount = uIntensity * (0.10 + uTertiary * 0.18);
                vec4 acc = vec4(0.0);
                for (int i = 0; i < 6; i++) {
                    float t = float(i) / 5.0;
                    float scale = 1.0 - amount * t * smoothstep(safe, 0.75, dist);
                    acc += tex(center * scale + vec2(0.5)) * 0.1666;
                }
                gl_FragColor = acc;
            """

            EffectType.BACKGROUND_BLUR -> """
                // Radial subject-focused bokeh: sharp centered subject, blurred surround.
                float aspect = uTexelSize.y / max(uTexelSize.x, 0.00001);
                vec2 d = (uv - vec2(0.5)) * vec2(aspect, 1.0);
                float radius = mix(0.08, 0.42, uSecondary);
                float focus = smoothstep(radius, radius + 0.22, length(d));
                float keep = smoothstep(uTertiary - 0.2, uTertiary + 0.05, uv.y);
                float blurAmt = uIntensity * focus * mix(0.35, 1.0, keep);
                vec2 s = uTexelSize * mix(1.5, 9.0, blurAmt);
                vec4 base = tex(uv);
                vec4 sum = base * 0.28;
                sum += tex(uv + vec2( s.x, 0.0)) * 0.12;
                sum += tex(uv + vec2(-s.x, 0.0)) * 0.12;
                sum += tex(uv + vec2(0.0,  s.y)) * 0.12;
                sum += tex(uv + vec2(0.0, -s.y)) * 0.12;
                sum += tex(uv + vec2( s.x,  s.y)) * 0.06;
                sum += tex(uv + vec2(-s.x, -s.y)) * 0.06;
                sum += tex(uv + vec2( s.x, -s.y)) * 0.06;
                sum += tex(uv + vec2(-s.x,  s.y)) * 0.06;
                gl_FragColor = mix(base, sum, blurAmt);
            """

            EffectType.SHARPEN -> """
                float radius = mix(0.7, 3.4, uSecondary);
                vec2 st = uTexelSize * radius;
                vec4 center = tex(uv);
                vec3 blurred = (
                    tex(uv + vec2(0.0, st.y)).rgb + tex(uv - vec2(0.0, st.y)).rgb +
                    tex(uv + vec2(st.x, 0.0)).rgb + tex(uv - vec2(st.x, 0.0)).rgb +
                    tex(uv + st).rgb + tex(uv + vec2(-st.x, st.y)).rgb +
                    tex(uv + vec2(st.x, -st.y)).rgb + tex(uv - st).rgb
                ) / 8.0;
                vec3 detail = center.rgb - blurred;
                float mag = max(max(abs(detail.r), abs(detail.g)), abs(detail.b));
                float guard = smoothstep(uTertiary * 0.08, uTertiary * 0.08 + 0.03, mag);
                gl_FragColor = vec4(clamp(center.rgb + detail * (uIntensity * 3.0 * guard), 0.0, 1.0), center.a);
            """

            EffectType.UNSHARP_PRO -> """
                vec2 fine = uTexelSize * mix(1.0, 2.4, uSecondary);
                vec2 wide = fine * 3.0;
                vec3 c = tex(uv).rgb;
                vec3 fineAvg = (tex(uv + vec2(fine.x, 0.0)).rgb + tex(uv - vec2(fine.x, 0.0)).rgb +
                                tex(uv + vec2(0.0, fine.y)).rgb + tex(uv - vec2(0.0, fine.y)).rgb) * 0.25;
                vec3 wideAvg = (tex(uv + wide).rgb + tex(uv + vec2(-wide.x, wide.y)).rgb +
                                tex(uv + vec2(wide.x, -wide.y)).rgb + tex(uv - wide).rgb) * 0.25;
                float l = luma(c);
                float midMask = 4.0 * l * (1.0 - l);
                float halo = mix(0.35, 0.08, uTertiary);
                vec3 micro = clamp(c - fineAvg, vec3(-halo), vec3(halo));
                vec3 local = clamp(c - wideAvg, vec3(-0.25), vec3(0.25)) * midMask;
                gl_FragColor = vec4(clamp(c + micro * (uIntensity * 2.6) + local * (uIntensity * 1.4), 0.0, 1.0), 1.0);
            """

            EffectType.EDGE_SHARPEN -> """
                vec2 t = uTexelSize * 1.4;
                vec3 n = tex(uv + vec2(0.0, t.y)).rgb;
                vec3 s = tex(uv - vec2(0.0, t.y)).rgb;
                vec3 e = tex(uv + vec2(t.x, 0.0)).rgb;
                vec3 w = tex(uv - vec2(t.x, 0.0)).rgb;
                vec3 center = tex(uv).rgb;
                vec3 avg = (n + s + e + w) * 0.25;
                float edge = length(n - s) + length(e - w);
                float thresh = mix(0.03, 0.28, uSecondary);
                float isEdge = smoothstep(thresh * 0.6, thresh * 1.6, edge);
                vec3 crisp = clamp(center + (center - avg) * (uIntensity * 3.2), 0.0, 1.0);
                vec3 smoothSkin = mix(center, avg, uTertiary * 0.6);
                gl_FragColor = vec4(mix(smoothSkin, crisp, isEdge), 1.0);
            """

            EffectType.GLOW -> """
                vec4 base = tex(uv);
                float threshold = mix(0.35, 0.85, uSecondary);
                vec2 s = uTexelSize * mix(2.0, 8.0, uTertiary) * 2.0;
                vec3 bloom = vec3(0.0);
                bloom += max(tex(uv + vec2( s.x, 0.0)).rgb - threshold, 0.0);
                bloom += max(tex(uv + vec2(-s.x, 0.0)).rgb - threshold, 0.0);
                bloom += max(tex(uv + vec2(0.0,  s.y)).rgb - threshold, 0.0);
                bloom += max(tex(uv + vec2(0.0, -s.y)).rgb - threshold, 0.0);
                bloom += max(tex(uv + s).rgb - threshold, 0.0);
                bloom += max(tex(uv - s).rgb - threshold, 0.0);
                bloom /= 6.0;
                vec3 outRgb = 1.0 - (1.0 - base.rgb) * (1.0 - bloom * (uIntensity * 3.2));
                gl_FragColor = vec4(clamp(outRgb, 0.0, 1.0), base.a);
            """

            EffectType.HALATION -> """
                vec4 base = tex(uv);
                vec2 s = uTexelSize * mix(3.0, 10.0, uSecondary) * 2.0;
                vec3 acc = vec3(0.0);
                acc += tex(uv + vec2( s.x, 0.0)).rgb;
                acc += tex(uv + vec2(-s.x, 0.0)).rgb;
                acc += tex(uv + vec2(0.0,  s.y)).rgb;
                acc += tex(uv + vec2(0.0, -s.y)).rgb;
                acc += tex(uv + s).rgb;
                acc += tex(uv - s).rgb;
                acc /= 6.0;
                float brightMask = smoothstep(uTertiary, uTertiary + 0.35, luma(acc));
                vec3 red = vec3(1.0, 0.30, 0.12) * acc * brightMask;
                gl_FragColor = vec4(clamp(base.rgb + red * uIntensity * 2.2, 0.0, 1.0), base.a);
            """

            EffectType.LIGHT_LEAKS -> """
                vec4 base = tex(uv);
                float speed = mix(0.25, 1.6, uTertiary);
                float t = uTimeSec * speed;
                float wave = max(0.0, sin(uv.x * 3.2 + t * 1.4) * cos(uv.y * 2.3 - t * 0.8));
                float corner = smoothstep(0.85, 0.0, distance(uv, vec2(0.10 + 0.25 * sin(t * 0.6), 0.85)));
                vec3 warm = mix(vec3(1.0, 0.22, 0.55), vec3(1.0, 0.58, 0.12), uSecondary);
                vec3 leak = warm * (wave * 0.45 + corner * 0.85) * uIntensity;
                gl_FragColor = vec4(1.0 - (1.0 - base.rgb) * (1.0 - clamp(leak, 0.0, 1.0)), base.a);
            """

            EffectType.FLASH -> """
                vec4 base = tex(uv);
                float rate = mix(2.0, 12.0, uSecondary);
                float strobe = pow(max(0.0, sin(uTimeSec * rate * 3.14159)), 4.0) * uIntensity;
                vec3 tint = mix(vec3(0.85, 0.95, 1.0), vec3(1.0, 0.92, 0.80), uTertiary);
                gl_FragColor = vec4(clamp(base.rgb + tint * (strobe * 0.85), 0.0, 1.0), base.a);
            """

            EffectType.GLITCH -> """
                float speed = floor(uTimeSec * mix(8.0, 26.0, uSecondary));
                float band = floor(uv.y * 24.0);
                float n = hash(vec2(band, speed));
                float trigger = step(1.0 - uIntensity * 0.55, n);
                float shift = (n - 0.5) * 0.13 * uIntensity * trigger;
                vec2 gUv = vec2(uv.x + shift, uv.y);
                float split = 0.03 * uIntensity * uTertiary;
                float r = tex(gUv + vec2(split, 0.0)).r;
                float g = tex(gUv).g;
                float b = tex(gUv - vec2(split, 0.0)).b;
                gl_FragColor = vec4(r, g, b, 1.0);
            """

            EffectType.RGB_SPLIT -> """
                float angle = uSecondary * 6.2831853;
                float edge = mix(1.0, smoothstep(0.06, 0.62, distance(uv, vec2(0.5))), uTertiary);
                vec2 offset = vec2(cos(angle), sin(angle)) * (0.03 * uIntensity * edge);
                vec4 mid = tex(uv);
                gl_FragColor = vec4(tex(uv + offset).r, mid.g, tex(uv - offset).b, mid.a);
            """

            EffectType.VHS -> """
                float barY = fract(uTimeSec * 0.35);
                float barMask = smoothstep(0.055, 0.0, abs(uv.y - barY));
                float jitter = (hash(vec2(uv.y + uTimeSec, 3.1)) - 0.5) * 0.014 * uIntensity;
                vec2 vUv = vec2(uv.x + jitter + barMask * 0.03 * uSecondary, uv.y);
                float bleed = 0.014 * uIntensity * (0.3 + uTertiary);
                float r = tex(vUv + vec2(bleed, 0.0)).r;
                float g = tex(vUv).g;
                float b = tex(vUv - vec2(bleed * 1.3, 0.0)).b;
                float scan = 0.90 + 0.10 * sin(uv.y * 540.0);
                gl_FragColor = vec4(clamp(vec3(r, g, b) * scan + barMask * 0.14 * uIntensity, 0.0, 1.0), 1.0);
            """

            EffectType.SCANLINES_CRT -> """
                vec2 centered = uv * 2.0 - 1.0;
                float r2 = dot(centered, centered);
                vec2 curved = (centered * (1.0 + 0.16 * uTertiary * uIntensity * r2)) * 0.5 + 0.5;
                if (curved.x < 0.0 || curved.x > 1.0 || curved.y < 0.0 || curved.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    vec4 base = tex(curved);
                    float density = mix(220.0, 720.0, uSecondary);
                    float scan = 1.0 - uIntensity * 0.35 * (0.5 + 0.5 * sin(curved.y * density));
                    float subpx = 0.94 + 0.06 * sin(curved.x * density * 1.5);
                    float vig = smoothstep(1.35, 0.45, length(centered));
                    gl_FragColor = vec4(base.rgb * scan * subpx * mix(1.0, vig, uIntensity * 0.5), base.a);
                }
            """

            EffectType.PIXELATE -> """
                float blocks = mix(160.0, 16.0, uIntensity);
                float aspect = max(0.3, uTexelSize.y / max(uTexelSize.x, 0.00001));
                vec2 grid = vec2(blocks, blocks * aspect);
                vec2 cellUv = (floor(uv * grid) + 0.5) / grid;
                vec4 col = tex(cellUv);
                if (uSecondary > 0.02) {
                    float levels = mix(24.0, 4.0, uSecondary);
                    col.rgb = floor(col.rgb * levels + 0.5) / levels;
                }
                vec2 cellLocal = fract(uv * grid);
                float border = step(0.06 * uTertiary, cellLocal.x) * step(0.06 * uTertiary, cellLocal.y);
                col.rgb *= mix(1.0, border, uTertiary * 0.6);
                gl_FragColor = col;
            """

            EffectType.FILM_GRAIN -> """
                vec4 base = tex(uv);
                float scale = mix(460.0, 130.0, uSecondary);
                float n = hash(floor(uv * scale) + vec2(floor(uTimeSec * 24.0) * 0.37, floor(uTimeSec * 24.0) * 0.71)) - 0.5;
                float weight = mix(1.0, 1.0 - luma(base.rgb) * 0.55, uTertiary);
                gl_FragColor = vec4(clamp(base.rgb + vec3(n * uIntensity * 0.28 * weight), 0.0, 1.0), base.a);
            """

            EffectType.NOISE -> """
                vec4 base = tex(uv);
                float scale = mix(900.0, 180.0, uSecondary);
                float n = hash(floor(uv * scale + vec2(uTimeSec * 13.0, uTimeSec * 7.0))) - 0.5;
                float chroma = 1.0 - uTertiary;
                vec3 grain = vec3(
                    hash(floor(uv * scale) + 1.0) - 0.5,
                    hash(floor(uv * scale) + 2.0) - 0.5,
                    hash(floor(uv * scale) + 3.0) - 0.5
                ) * chroma + vec3(n) * (1.0 - chroma);
                gl_FragColor = vec4(clamp(base.rgb + grain * uIntensity * 0.5, 0.0, 1.0), base.a);
            """

            EffectType.OLD_FILM -> """
                vec4 base = tex(uv);
                float gray = luma(base.rgb);
                vec3 sepia = vec3(gray * 1.12, gray * 0.95, gray * 0.74);
                float flicker = 1.0 - (0.12 * uTertiary) * (0.5 + 0.5 * sin(uTimeSec * 29.0));
                float lineX = hash(vec2(floor(uTimeSec * 12.0), 19.31));
                float scratch = step(abs(uv.x - lineX), 0.0018 * uSecondary) * 0.55;
                float vig = smoothstep(0.90, 0.30, length(uv - 0.5));
                vec3 film = clamp(sepia * flicker * vig + vec3(scratch), 0.0, 1.0);
                gl_FragColor = vec4(mix(base.rgb, film, clamp(uIntensity, 0.0, 1.0)), base.a);
            """

            EffectType.SHAKE -> """
                float freq = mix(10.0, 38.0, uSecondary);
                float dx = sin(uTimeSec * freq) * cos(uTimeSec * freq * 0.73) * 0.036 * uIntensity;
                float dy = cos(uTimeSec * freq * 1.13) * 0.030 * uIntensity;
                float ang = sin(uTimeSec * freq * 0.85) * 0.045 * uIntensity * uTertiary;
                vec2 rel = (uv - 0.5) * (1.0 - 0.09 * uIntensity);
                vec2 rot = vec2(rel.x * cos(ang) - rel.y * sin(ang), rel.x * sin(ang) + rel.y * cos(ang));
                gl_FragColor = tex(rot + 0.5 + vec2(dx, dy));
            """

            EffectType.ZOOM -> """
                float pulse = 0.5 + 0.5 * sin(uTimeSec * mix(2.0, 10.0, uSecondary));
                pulse = mix(pulse, pulse * pulse * (3.0 - 2.0 * pulse), uTertiary);
                float z = 1.0 + uIntensity * 0.4 * pulse;
                gl_FragColor = tex((uv - 0.5) / z + 0.5);
            """

            EffectType.MIRROR_KALEIDOSCOPE -> """
                vec4 orig = tex(uv);
                float center = mix(0.3, 0.7, uTertiary);
                vec2 mUv = uv;
                if (uSecondary < 0.34) {
                    mUv.x = center - abs(uv.x - center);
                } else if (uSecondary < 0.67) {
                    mUv.x = 0.5 - abs(uv.x - 0.5);
                    mUv.y = 0.5 - abs(uv.y - 0.5);
                } else {
                    vec2 d = abs(uv - vec2(0.5));
                    mUv = clamp(vec2(0.5) - vec2(max(d.x, d.y), min(d.x, d.y)), 0.0, 1.0);
                }
                gl_FragColor = mix(orig, tex(mUv), clamp(uIntensity, 0.0, 1.0));
            """

            EffectType.CHROMATIC_ABERRATION -> """
                vec2 d = uv - vec2(0.5);
                float r2 = dot(d, d) * mix(1.0, 3.0, uSecondary);
                vec2 shift = d * (r2 * 0.15 + uTertiary * 0.025) * uIntensity;
                vec4 mid = tex(uv);
                gl_FragColor = vec4(tex(uv + shift).r, mid.g, tex(uv - shift).b, mid.a);
            """

            EffectType.VIGNETTE -> """
                vec4 base = tex(uv);
                vec2 d = (uv - vec2(0.5)) * vec2(1.0, mix(0.8, 1.35, uSecondary));
                float dist = length(d);
                float inner = mix(0.15, 0.45, uTertiary);
                float vig = smoothstep(0.86, inner, dist);
                gl_FragColor = vec4(base.rgb * mix(1.0, vig, uIntensity), base.a);
            """

            EffectType.LENS_DISTORTION -> """
                vec2 centered = uv * 2.0 - 1.0;
                float r2 = dot(centered, centered);
                float k = (uSecondary - 0.5) * 1.15 * uIntensity;
                vec2 warped = centered * (1.0 + k * r2);
                vec2 finalUv = warped * 0.5 + 0.5;
                if (finalUv.x < 0.0 || finalUv.x > 1.0 || finalUv.y < 0.0 || finalUv.y > 1.0) {
                    gl_FragColor = uTertiary > 0.5
                        ? tex(finalUv)
                        : vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    gl_FragColor = tex(finalUv);
                }
            """

            EffectType.WAVE_DISTORTION -> """
                float amp = uIntensity * 0.05;
                float freq = mix(4.0, 26.0, uSecondary);
                float speed = mix(0.0, 4.0, uTertiary);
                vec2 wUv = uv;
                wUv.x += sin(uv.y * freq + uTimeSec * speed) * amp;
                wUv.y += cos(uv.x * freq * 0.8 + uTimeSec * speed * 1.3) * amp * 0.7;
                gl_FragColor = tex(wUv);
            """

            EffectType.POSTERIZE_POP -> """
                vec4 base = tex(uv);
                float avg = (base.r + base.g + base.b) / 3.0;
                vec3 satRgb = clamp(vec3(avg) + (base.rgb - vec3(avg)) * (1.0 + uTertiary * 1.2), 0.0, 1.0);
                float steps = mix(3.0, 12.0, uSecondary);
                vec3 pop = floor(satRgb * steps + 0.5) / steps;
                gl_FragColor = vec4(mix(base.rgb, pop, clamp(uIntensity, 0.0, 1.0)), base.a);
            """

            EffectType.CARTOON -> """
                vec4 base = tex(uv);
                vec2 t = uTexelSize * mix(1.0, 2.6, uTertiary);
                float edge = length(tex(uv + vec2(0.0, t.y)).rgb - tex(uv - vec2(0.0, t.y)).rgb) +
                             length(tex(uv + vec2(t.x, 0.0)).rgb - tex(uv - vec2(t.x, 0.0)).rgb);
                float ink = 1.0 - smoothstep(0.16, 0.36, edge * (1.0 + uIntensity));
                float levels = mix(3.0, 9.0, uSecondary);
                vec3 poster = floor(base.rgb * levels + 0.5) / levels;
                gl_FragColor = vec4(mix(base.rgb, poster * ink, clamp(uIntensity, 0.0, 1.0)), base.a);
            """

            EffectType.BLACK_AND_WHITE -> """
                vec4 base = tex(uv);
                float g = luma(base.rgb);
                float contrast = 1.0 + (uSecondary - 0.5) * 1.2;
                g = clamp((g - 0.5) * contrast + 0.5, 0.0, 1.0);
                vec3 tint = mix(vec3(g), vec3(g * 1.06, g, g * 0.92), (uTertiary - 0.5) * 2.0);
                gl_FragColor = vec4(mix(base.rgb, tint, clamp(uIntensity, 0.0, 1.0)), base.a);
            """

            EffectType.INVERT -> """
                vec4 base = tex(uv);
                vec3 inverted = 1.0 - base.rgb;
                float balance = (uSecondary - 0.5) * 0.4;
                inverted = clamp(inverted + vec3(balance, 0.0, -balance), 0.0, 1.0);
                float l = luma(base.rgb);
                inverted = mix(inverted, vec3(l), uTertiary);
                gl_FragColor = vec4(mix(base.rgb, inverted, clamp(uIntensity, 0.0, 1.0)), base.a);
            """

            EffectType.EDGE_DETECTION -> """
                vec4 base = tex(uv);
                vec2 t = uTexelSize * 1.5;
                float tl = luma(tex(uv + vec2(-t.x,  t.y)).rgb);
                float tc = luma(tex(uv + vec2(0.0,  t.y)).rgb);
                float tr = luma(tex(uv + vec2( t.x,  t.y)).rgb);
                float ml = luma(tex(uv + vec2(-t.x, 0.0)).rgb);
                float mr = luma(tex(uv + vec2( t.x, 0.0)).rgb);
                float bl = luma(tex(uv + vec2(-t.x, -t.y)).rgb);
                float bc = luma(tex(uv + vec2(0.0, -t.y)).rgb);
                float br = luma(tex(uv + vec2( t.x, -t.y)).rgb);
                float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
                float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
                float mag = clamp(sqrt(gx * gx + gy * gy) * (1.0 + uSecondary * 2.0), 0.0, 1.0);
                float thresh = mix(0.02, 0.35, uSecondary);
                float edges = step(thresh, mag);
                if (uTertiary > 0.5) edges = 1.0 - edges;
                gl_FragColor = vec4(mix(base.rgb, vec3(edges), clamp(uIntensity, 0.0, 1.0)), base.a);
            """

            EffectType.DUOTONE -> """
                vec4 base = tex(uv);
                float l = luma(base.rgb);
                float split = smoothstep(0.15, 0.85, (l - 0.5) * (0.8 + uTertiary * 1.4) + 0.5);
                float ang = uSecondary * 6.28318;
                vec3 dark = clamp(0.18 + 0.18 * cos(ang + vec3(3.1, 1.2, 0.0)), 0.0, 1.0);
                vec3 light = clamp(0.72 + 0.28 * cos(ang + vec3(0.0, 1.8, 3.6)), 0.0, 1.0);
                gl_FragColor = vec4(mix(base.rgb, mix(dark, light, split), clamp(uIntensity, 0.0, 1.0)), base.a);
            """

            EffectType.CINEMATIC -> """
                vec4 base = tex(uv);
                float l = luma(base.rgb);
                vec3 tealShadows = vec3(0.0, 0.05, 0.10) * (1.0 - l);
                vec3 warmHighlights = vec3(0.10, 0.045, -0.03) * l;
                vec3 graded = clamp((base.rgb - 0.5) * (1.0 + (0.18 + uTertiary * 0.2) * uIntensity) + 0.5 +
                                    (tealShadows + warmHighlights) * uIntensity, 0.0, 1.0);
                float barSize = 0.11 * uSecondary * uIntensity;
                float bar = step(barSize, uv.y) * step(uv.y, 1.0 - barSize);
                gl_FragColor = vec4(graded * bar, base.a);
            """
        }

        return HEADER + """
            void main() {
                vec2 uv = vTexSamplingCoord;
                $body
            }
        """
    }

    /**
     * Master finishing pass: enhancement (sharpen/denoise/contrast/color), optical finishing
     * (vignette/grain/blur), chroma key, blend mode, clip transitions and opacity.
     */
    fun buildMasterFinishingFragmentShader(): String = HEADER + """
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
        uniform float uContrastBoost;
        uniform float uColorBoost;
        uniform float uBrightnessFix;
        uniform float uDetailPreserve;
        uniform float uChromaEnabled;
        uniform float uChromaHue;
        uniform float uChromaTolerance;
        uniform float uChromaSoftness;
        uniform float uChromaAmount;
        uniform float uChromaSpill;
        uniform int uBlendMode;
        uniform int uTransitionType;
        uniform float uTransitionProgress;
        uniform float uTransitionSoftness;
        uniform float uTransitionChromatic;
        uniform float uTransitionAngle;

        float hueDistance(float a, float b) {
            float d = abs(a - b);
            return min(d, 1.0 - d);
        }

        vec4 sampleFinished(vec2 coord) {
            vec2 cUv = clamp(coord, 0.0, 1.0);
            vec4 center = texture2D(uTexSampler, cUv);
            vec3 rgb = center.rgb;
            float alpha = center.a;

            // 1. Chroma key / green screen (alpha keying, used by overlays and export).
            if (uChromaEnabled > 0.5 && uChromaAmount > 0.004) {
                vec3 hsv = rgb2hsv(rgb);
                float dist = hueDistance(hsv.x, uChromaHue / 360.0);
                float band = uChromaTolerance / 360.0;
                float keyed = 1.0 - smoothstep(band, band + max(uChromaSoftness * 0.12, 0.005), dist);
                keyed *= smoothstep(0.02, max(uChromaSoftness * 0.35, 0.03), hsv.y);
                keyed *= uChromaAmount;
                if (keyed > 0.001) {
                    float spill = keyed * uChromaSpill;
                    float desat = luma(rgb);
                    rgb = mix(rgb, vec3(desat), spill * 0.6);
                }
                alpha *= (1.0 - keyed);
            }

            // 2. Edge-preserving denoise (bilateral luma).
            if (uDenoise > 0.004) {
                vec2 ds = uTexelSize * 1.6;
                vec3 c1 = texture2D(uTexSampler, clamp(cUv + vec2(ds.x, 0.0), 0.0, 1.0)).rgb;
                vec3 c2 = texture2D(uTexSampler, clamp(cUv - vec2(ds.x, 0.0), 0.0, 1.0)).rgb;
                vec3 c3 = texture2D(uTexSampler, clamp(cUv + vec2(0.0, ds.y), 0.0, 1.0)).rgb;
                vec3 c4 = texture2D(uTexSampler, clamp(cUv - vec2(0.0, ds.y), 0.0, 1.0)).rgb;
                float l0 = luma(rgb);
                float w1 = exp(-abs(luma(c1) - l0) * 18.0);
                float w2 = exp(-abs(luma(c2) - l0) * 18.0);
                float w3 = exp(-abs(luma(c3) - l0) * 18.0);
                float w4 = exp(-abs(luma(c4) - l0) * 18.0);
                vec3 smoothRgb = (rgb + c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4) / (1.0 + w1 + w2 + w3 + w4);
                rgb = mix(rgb, smoothRgb, clamp(uDenoise * 0.9, 0.0, 1.0));
            }

            // 3. Unsharp-mask sharpening with radius + edge guard, plus micro structure.
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
                float totalSharp = uGradeSharpness * 3.1 * guard + uStructure * 1.8;
                rgb = clamp(rgb + clamp(hp, -0.26, 0.26) * totalSharp, 0.0, 1.0);
            }

            // 4. Clarity (midtone local contrast).
            if (abs(uClarity) > 0.004) {
                vec2 cs = uTexelSize * 4.5;
                vec3 wideBlur = (
                    texture2D(uTexSampler, clamp(cUv + vec2(cs.x, cs.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv + vec2(-cs.x, cs.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv + vec2(cs.x, -cs.y), 0.0, 1.0)).rgb +
                    texture2D(uTexSampler, clamp(cUv + vec2(-cs.x, -cs.y), 0.0, 1.0)).rgb
                ) * 0.25;
                float l = luma(rgb);
                float midWeight = 4.0 * l * (1.0 - l);
                rgb = clamp(rgb + (rgb - wideBlur) * (uClarity * 1.45 * midWeight), 0.0, 1.0);
            }

            // 5. Enhancement: contrast, color and brightness correction blocks.
            if (abs(uBrightnessFix) > 0.002) {
                rgb = clamp(rgb + uBrightnessFix * 0.32, 0.0, 1.0);
            }
            if (abs(uContrastBoost) > 0.002) {
                rgb = clamp((rgb - 0.5) * (1.0 + uContrastBoost * 0.9) + 0.5, 0.0, 1.0);
            }
            if (abs(uColorBoost) > 0.002) {
                float l = luma(rgb);
                rgb = clamp(mix(vec3(l), rgb, 1.0 + uColorBoost * 1.1), 0.0, 1.0);
            }
            if (uDetailPreserve > 0.004) {
                // Keep high-frequency detail alive after denoise/upscale by re-injecting edges.
                vec2 ps = uTexelSize * 1.0;
                vec3 blurP = (texture2D(uTexSampler, clamp(cUv + vec2(ps.x, 0.0), 0.0, 1.0)).rgb +
                              texture2D(uTexSampler, clamp(cUv - vec2(ps.x, 0.0), 0.0, 1.0)).rgb +
                              texture2D(uTexSampler, clamp(cUv + vec2(0.0, ps.y), 0.0, 1.0)).rgb +
                              texture2D(uTexSampler, clamp(cUv - vec2(0.0, ps.y), 0.0, 1.0)).rgb) * 0.25;
                rgb = clamp(rgb + (rgb - blurP) * uDetailPreserve * 0.85, 0.0, 1.0);
            }

            // 6. Keyframed extra blur.
            if (uKeyframeBlur > 0.004) {
                vec2 bs = uTexelSize * (uKeyframeBlur * 10.0);
                vec3 acc = rgb * 0.28;
                acc += texture2D(uTexSampler, clamp(cUv + vec2(bs.x, 0.0), 0.0, 1.0)).rgb * 0.18;
                acc += texture2D(uTexSampler, clamp(cUv - vec2(bs.x, 0.0), 0.0, 1.0)).rgb * 0.18;
                acc += texture2D(uTexSampler, clamp(cUv + vec2(0.0, bs.y), 0.0, 1.0)).rgb * 0.18;
                acc += texture2D(uTexSampler, clamp(cUv - vec2(0.0, bs.y), 0.0, 1.0)).rgb * 0.18;
                rgb = acc;
            }

            // 7. Optical vignette.
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

            // 8. Film grain.
            if (uGrain > 0.004) {
                float n = hash(floor(cUv * 360.0) + vec2(floor(uTimeSec * 24.0) * 73.0, floor(uTimeSec * 24.0) * 41.0)) - 0.5;
                rgb = clamp(rgb + vec3(n * uGrain * 0.22), 0.0, 1.0);
            }

            return vec4(rgb, alpha);
        }

        void main() {
            vec2 uv = vTexSamplingCoord;
            float p = clamp(uTransitionProgress, 0.0, 1.0);
            float inv = 1.0 - p;
            vec4 col = sampleFinished(uv);

            if (p < 0.999 && uTransitionType > 0) {
                if (uTransitionType == 1) {
                    // Fade: opacity ramp over the project background (or the clip below).
                    col *= p;
                } else if (uTransitionType == 2) {
                    // Dissolve: dithered threshold reveal so grain-level detail resolves progressively
                    // instead of a flat opacity ramp.
                    float grain = hash(floor(uv * 220.0) + 7.0);
                    float dissolve = smoothstep(0.0, 1.0, clamp(p * 1.15 - grain * 0.15, 0.0, 1.0));
                    col = vec4(col.rgb * dissolve, col.a * dissolve);
                } else if (uTransitionType == 3) {
                    float z = 1.0 + inv * 0.85;
                    col = sampleFinished((uv - 0.5) / z + 0.5);
                    col *= smoothstep(0.0, 0.35, p);
                } else if (uTransitionType == 6) {
                    float ang = inv * 1.5707963;
                    vec2 d = uv - 0.5;
                    vec2 rUv = vec2(d.x * cos(ang) - d.y * sin(ang), d.x * sin(ang) + d.y * cos(ang)) / (1.0 + inv * 0.4) + 0.5;
                    col = sampleFinished(rUv) * p;
                } else if (uTransitionType == 4) {
                    float rad = uTransitionAngle * 0.0174533;
                    vec2 dir = vec2(cos(rad), sin(rad));
                    vec2 sUv = uv + dir * inv;
                    col = (sUv.x > 1.0 || sUv.x < 0.0 || sUv.y > 1.0 || sUv.y < 0.0)
                        ? vec4(0.0, 0.0, 0.0, 0.0)
                        : sampleFinished(sUv);
                } else if (uTransitionType == 5) {
                    float soft = max(0.02, uTransitionSoftness * 0.35);
                    float coord = dot(uv - 0.5, vec2(cos(uTransitionAngle * 0.0174533), sin(uTransitionAngle * 0.0174533))) + 0.5;
                    float mask = smoothstep(coord - soft, coord + soft, p * (1.0 + soft));
                    col *= mask;
                } else if (uTransitionType == 7) {
                    vec2 wStep = uTexelSize * inv * 24.0;
                    vec3 acc = (sampleFinished(uv - wStep).rgb + sampleFinished(uv).rgb + sampleFinished(uv + wStep).rgb) / 3.0;
                    col.rgb = acc * smoothstep(0.0, 0.5, p);
                } else {
                    float shift = inv * 0.08 * uTransitionChromatic;
                    float r = sampleFinished(uv + vec2(shift, 0.0)).r;
                    float g = sampleFinished(uv).g;
                    float b = sampleFinished(uv - vec2(shift, 0.0)).b;
                    col = vec4(vec3(r, g, b) * p, col.a * p);
                }
            }

            // Blend mode compositing against the track below.
            if (uBlendMode == 1) {
                col.rgb = 1.0 - (1.0 - col.rgb) * (1.0 - col.rgb);
            } else if (uBlendMode == 2) {
                col.rgb = col.rgb * col.rgb;
            } else if (uBlendMode == 3) {
                col.rgb = clamp(2.0 * col.rgb * col.rgb * (1.0 - col.rgb) + col.rgb * col.rgb, 0.0, 1.0);
            } else if (uBlendMode == 4) {
                col.rgb = clamp(col.rgb * 1.6, 0.0, 1.0);
            } else if (uBlendMode == 5) {
                col.rgb = col.rgb * col.rgb;
            } else if (uBlendMode == 6) {
                col.rgb = clamp(sqrt(col.rgb), 0.0, 1.0);
            }

            gl_FragColor = vec4(col.rgb * uOpacity, col.a * uOpacity);
        }
    """

    fun transitionCode(type: TransitionType?): Int = when (type) {
        null -> 0
        TransitionType.FADE -> 1
        TransitionType.DISSOLVE -> 2
        TransitionType.ZOOM -> 3
        TransitionType.SLIDE -> 4
        TransitionType.WIPE -> 5
        TransitionType.SPIN -> 6
        TransitionType.BLUR -> 7
        TransitionType.CUSTOM -> 8
    }

    /**
     * Real Lanczos-3 separable upscale pass used by the 4K enhancement pipeline.
     * Rendered by the GPU in preview and export; it genuinely resamples with a 6-tap
     * Lanczos kernel plus a controlled sharpening term (not a bilinear stretch).
     */
    fun buildLanczosUpscaleFragmentShader(): String = HEADER + """
        uniform vec2 uSourceTexelSize;

        float lanczos(float x) {
            if (abs(x) < 0.0001) return 1.0;
            if (abs(x) >= 3.0) return 0.0;
            float px = 3.14159265 * x;
            return (sin(px) / px) * (sin(px / 3.0) / (px / 3.0));
        }

        vec4 sampleLanczos(vec2 uv) {
            vec3 acc = vec3(0.0);
            float total = 0.0;
            for (int j = -2; j <= 2; j++) {
                for (int i = -2; i <= 2; i++) {
                    vec2 offset = vec2(float(i), float(j)) * uSourceTexelSize;
                    float w = lanczos(float(i)) * lanczos(float(j));
                    acc += tex(uv + offset).rgb * w;
                    total += w;
                }
            }
            return vec4(acc / max(total, 0.0001), 1.0);
        }

        void main() {
            vec2 uv = vTexSamplingCoord;
            vec4 sharpened = sampleLanczos(uv);
            vec3 center = tex(uv).rgb;
            vec3 boosted = clamp(sharpened.rgb + (sharpened.rgb - center) * (uSecondary * 0.8), 0.0, 1.0);
            gl_FragColor = vec4(mix(sharpened.rgb, boosted, clamp(uIntensity, 0.0, 1.0)), 1.0);
        }
    """
}
