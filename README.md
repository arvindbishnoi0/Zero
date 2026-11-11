# VibeCut Pro — Professional Android Multi-Track Video Studio

**VibeCut Pro** is a native Android (`Kotlin` + `Jetpack Compose` + `Media3` + `OpenGL ES 2.0` + `MediaCodec` + `Room` + `ML Kit`) multi-track video editing studio inspired by **VN Video Editor** and **CapCut**.

Designed as a **100% self-contained, local-first studio** (no login/auth walls, no admin bloat, no mandatory cloud backend), all video rendering, GPU shader processing, 3D LUT color grading, PCM audio DSP, project autosave, and on-device AI analysis execute directly on the Android device.

---

## Architecture & Modules

```text
VibeCutPro/
├── core/engine/                 # Pure-Kotlin deterministic editing, DSP, shader, color & AI core
│   └── src/main/kotlin/com/vibecut/pro/engine/
│       ├── model/               # Immutable Project, Track, TimelineClip, SpeedCurve, LUT & Export specs
│       ├── timeline/            # Multi-track magnetic timeline, split/trim/cut/freeze & 100-step Undo/Redo
│       ├── effects/             # 22 OpenGL ES 2.0 (GLSL 100) fragment shader compiler (incl. Pro Sharpen)
│       ├── color/               # 8-Band HSL, Monotone Cubic RGB Curves, Color Wheels & .cube 3D LUT parser/baker
│       ├── keyframes/           # Newton-Raphson Cubic Bezier & Easing keyframe interpolator
│       ├── audio/               # PCM waveform extractor, Energy-Flux Beat/BPM detector, Biquad DSP & WAV SFX synth
│       ├── subtitles/           # SRT parser/exporter, Text Templates & Voice Activity Detection (VAD)
│       ├── ai/                  # Chi-Square scene cuts, highlight scorer, auto-edit, tracking & script-to-video
│       ├── templates/           # 12-Category Template Marketplace & custom template extractor
│       └── serialization/       # Zero-reflection lossless JSON codec for Room persistence & revision history
│
└── app/                         # Native Android Studio Application (API 26 – API 35)
    └── src/main/java/com/vibecut/pro/
        ├── ai/                  # ML Kit Face Detection, Selfie Segmentation, SpeechRecognizer (hi-IN / en-US)
        ├── data/                # Room Database (projects, revision snapshots, custom templates) + DataStore
        ├── di/                  # Hilt Dependency Injection module
        ├── media/
        │   ├── shader/          # Media3 BaseGlShaderProgram, MatrixTransformation, SingleColorLut & SceneOverlay
        │   ├── audio/           # Streaming MediaExtractor+MediaCodec analyzer, M4A audio extractor & Voiceover
        │   ├── player/          # Dual-ExoPlayer live GPU shader preview & multi-track audio mixer
        │   ├── sample/          # Hardware MediaCodec H.264 sample MP4 clip generator for instant testing
        │   └── export/          # Media3 Transformer multi-sequence MP4/M4A exporter + WorkManager + MediaStore
        └── ui/                  # Dark-theme Jetpack Compose studio screens, timeline & 8 inspector panels
```

---

## Key Capabilities

1. **Multi-Track Magnetic Timeline Engine**
   - Multiple Video (`Main Video`, `PIP / Overlay`), Audio (`Music`, `Voiceover`, `Sound FX`), `Text`, `Sticker`, and `FX Overlay` tracks.
   - Frame-by-frame stepping, pinch/button zoom (`25%`–`500%`), timeline snapping, magnetic ripple reflow, track **Lock**, **Hide**, and **Mute**.
   - Trim, Split (speed-curve aware), Cut middle segment, Crop (`Free`, `85%`, `70%`, `21:9`, `1:1`), Rotate 90°, Flip H/V, Reverse, Duplicate, Reorder, Merge, Constant Speed (`0.1x`–`6.0x`), **6 VN-style Speed Curves** (`Montage`, `Hero`, `Bullet`, `Jump Cut`, `Flash In`, `Flash Out`), **Freeze Frame**, and **100-step Undo / Redo** with automatic Room autosave & revision restore.

2. **22 Real OpenGL ES 2.0 GPU Effects (Multi-Effect Stackable)**
   - **Sharpen & Blur**: *Pro Sharpen* (Unsharp-Mask 8-neighbor high-pass kernel), *Box Blur*, *Gaussian Blur*, *Motion Blur*, *Background Blur*.
   - **Glow & Light**: *Soft Glow*, *Neon Edge Glow* (Sobel halo), *Strobe Flash*, *Light Leaks*.
   - **Glitch & Retro**: *Digital Glitch*, *RGB Split*, *VHS Tape*, *Film Grain*, *Old Film 16mm*.
   - **Camera & Stylize**: *Cinematic Scope*, *Camera Shake*, *Dynamic Zoom*, *Chromatic Aberration*, *Optical Vignette*, *Lens Distortion*, *Cartoon Cel*, *Color Distortion*.
   - Stack multiple GPU effects on the same clip with independent intensity & secondary controls.

3. **Professional Color Grading & 3D LUTs**
   - Dedicated **Sharpness**, Exposure (EV), Brightness, Contrast, Saturation, Temperature, Tint, Highlights, Shadows, and Matte Fade.
   - **8-Band HSL Mixer**, interactive touch **RGB Tone Curves** (`Master`, `R`, `G`, `B`), **3-Way Color Wheels** (`Shadows`, `Midtones`, `Highlights`), **14 Cinematic Presets**, custom preset saving, and **Adobe `.cube` 3D LUT import** baked directly into `androidx.media3.effect.SingleColorLut`.

4. **Keyframe Animation & 13 Transitions**
   - Keyframes for `Position X/Y`, `Scale`, `Rotation`, `Opacity`, `Volume`, `Blur`, `FX Intensity`, `Color Mix`, and `Text Animation` with `Linear`, `Ease In`, `Ease Out`, `Ease In-Out`, `Snap Overshoot`, and `Custom Cubic Bezier` curves.
   - 13 GPU clip transitions (`Fade`, `Dissolve`, `Zoom`, `Spin`, `Swipe`, `Blur`, `Glitch`, `Flash`, `Slide`, `Wipe`, `Push`, `Cinema Burn`, `Custom Pro`) with adjustable duration & one-tap *Apply to All*.

5. **Audio Editing Studio & Offline WAV Sound FX**
   - Import music, **lossless Video-to-Audio (`.m4a`) extraction**, live **Voiceover Recorder** with peak dB meter, **12 built-in procedurally synthesized 44.1kHz WAV Sound Effects**, real-time 16-bit PCM **Noise Reduction** (90Hz high-pass + downward expander gate), **Voice Enhancement** (2.8kHz vocal clarity exciter), **Audio Ducking**, and **Onset Energy-Flux Beat Detection & Beat Sync**.

6. **Text, Stickers & Hindi / English Auto-Captions**
   - Unlimited text layers, custom fonts, dual-stop gradients, shadows, outlines, pill backgrounds, **3D Extruded Text**, 7 animations, 8 templates, **Hindi (`hi-IN`) & English (`en-US`) Speech-to-Text dictation**, VAD speech timing scanner, and `.SRT` import/export.

7. **Modular On-Device AI Suite (17 Tools)**
   - Powered by **Google ML Kit** (Face Detection & Selfie Subject Segmentation), **Chi-Square frame histogram cut detection**, **Sobel sharpness scoring**, and **PCM transient analysis** — plus a clean, transparent `ExternalGenAiGateway` interface for optional cloud neural inpainting.

8. **Hardware Export Engine (`Media3 Transformer` + `MediaCodec`)**
   - Resolutions: `480p`, `720p`, `1080p`, `2K (1440p)`, `4K (2160p)`.
   - Frame rates: `24`, `25`, `30`, `50`, `60` FPS.
   - Bitrates: `Low`, `Medium`, `High`, and `Custom Mbps` (`2–80 Mbps`).
   - Codecs: `H.264 (AVC)` and `H.265 (HEVC)` with live hardware capability detection, `Audio-Only (.m4a)` export, progress & cancellation, `WorkManager` background export, `MediaStore` Gallery save, and social media sharing.

---

## Setup & Build Instructions

### Prerequisites
- **Android Studio Ladybug (2024.2.1)** or newer
- **JDK 17** (bundled with Android Studio)
- **Android SDK 35** (`minSdk = 26`, `targetSdk = 35`)

### Build & Run
1. Clone this repository and open the root folder in **Android Studio**.
2. Let Gradle sync dependencies (`Media3 1.5.1`, `Compose BOM`, `Room`, `Hilt`, `WorkManager`, `ML Kit`).
3. Build from CLI or Android Studio:
   ```bash
   # Run pure-Kotlin editing, shader, LUT, DSP & AI unit tests
   ./gradlew :core:engine:test testDebugUnitTest

   # Build installable Debug APK
   ./gradlew assembleDebug
   ```
4. Install `app/build/outputs/apk/debug/app-debug.apk` on any Android 8.0+ phone or emulator.
   - *Tip for fresh emulators*: Tap **"Instant Demo Reel (Generate 3 H.264 Test Clips)"** on the Home Studio screen to synthesize three hardware-encoded 720p MP4 clips on-device and immediately test splitting, GPU sharpening, 3D LUTs, beat sync, and 4K export!
