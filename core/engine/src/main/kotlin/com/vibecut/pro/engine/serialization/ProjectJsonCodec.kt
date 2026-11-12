package com.vibecut.pro.engine.serialization

import com.vibecut.pro.engine.model.*

/**
 * Self-contained lossless JSON serializer/parser for [Project].
 *
 * Deliberately dependency-free (no kotlinx-serialization / reflection) so it behaves identically
 * on Android R8 release builds and in JVM unit tests. All reads are defensive: unknown or missing
 * fields fall back to defaults, which keeps older project revisions loadable.
 */
object ProjectJsonCodec {

    fun encodeProject(project: Project): String = MiniJson.stringify(
        linkedMapOf<String, Any?>(
            "id" to project.id,
            "title" to project.title,
            "aspectRatio" to project.aspectRatio.id,
            "customAspectRatio" to project.customAspectRatio,
            "backgroundColorArgb" to project.backgroundColorArgb,
            "magneticTimeline" to project.magneticTimeline,
            "snappingEnabled" to project.snappingEnabled,
            "timelineZoom" to project.timelineZoom,
            "thumbnailUri" to project.thumbnailUri,
            "createdAtMs" to project.createdAtMs,
            "updatedAtMs" to project.updatedAtMs,
            "revision" to project.revision,
            "exportConfig" to encodeExportConfig(project.exportConfig),
            "enhancement" to encodeEnhancement(project.enhancement),
            "previewQuality" to project.previewQuality.name,
            "subtitles" to project.subtitles.map { encodeSubtitle(it) },
            "customColorPresets" to project.customColorPresets.map { encodePreset(it) },
            "tracks" to project.tracks.map { encodeTrack(it) }
        )
    )

    fun decodeProject(json: String): Project {
        val root = MiniJson.parseObject(json)
        val tracks = root.list("tracks").map { decodeTrack(it.asObj()) }
        return Project(
            id = root.str("id", Project().id),
            title = root.str("title", "Untitled Project"),
            aspectRatio = AspectRatioPreset.fromId(root.str("aspectRatio", "9:16")),
            customAspectRatio = root.float("customAspectRatio", 0.5625f),
            backgroundColorArgb = root.long("backgroundColorArgb", 0xFF000000),
            magneticTimeline = root.bool("magneticTimeline", true),
            snappingEnabled = root.bool("snappingEnabled", true),
            timelineZoom = root.float("timelineZoom", 1f),
            tracks = tracks.ifEmpty { Project.defaultTracks() },
            subtitles = root.list("subtitles").map { decodeSubtitle(it.asObj()) },
            customColorPresets = root.list("customColorPresets").map { decodePreset(it.asObj()) },
            exportConfig = root.objOrNull("exportConfig")?.let { decodeExportConfig(it) } ?: ExportConfig(),
            enhancement = root.objOrNull("enhancement")?.let { decodeEnhancement(it) } ?: EnhancementConfig(),
            previewQuality = runCatching { PreviewQuality.valueOf(root.str("previewQuality", "HIGH")) }
                .getOrDefault(PreviewQuality.HIGH),
            thumbnailUri = root.strOrNull("thumbnailUri"),
            createdAtMs = root.long("createdAtMs", System.currentTimeMillis()),
            updatedAtMs = root.long("updatedAtMs", System.currentTimeMillis()),
            revision = root.int("revision", 1)
        )
    }

    // ------------------------------------------------------------------ tracks & clips

    private fun encodeTrack(t: Track): Map<String, Any?> = linkedMapOf(
        "id" to t.id,
        "name" to t.name,
        "type" to t.type.name,
        "isLocked" to t.isLocked,
        "isHidden" to t.isHidden,
        "isMuted" to t.isMuted,
        "volume" to t.volume,
        "clips" to t.clips.map { encodeClip(it) }
    )

    private fun decodeTrack(o: JsonObj): Track = Track(
        id = o.str("id", "track"),
        name = o.str("name", "Track"),
        type = runCatching { TrackType.valueOf(o.str("type", "VIDEO_MAIN")) }
            .getOrDefault(TrackType.VIDEO_MAIN),
        isLocked = o.bool("isLocked", false),
        isHidden = o.bool("isHidden", false),
        isMuted = o.bool("isMuted", false),
        volume = o.float("volume", 1f),
        clips = o.list("clips").map { decodeClip(it.asObj()) }.sortedBy { it.startTimeOnTrackMs }
    )

    private fun encodeClip(c: TimelineClip): Map<String, Any?> = linkedMapOf(
        "id" to c.id,
        "trackId" to c.trackId,
        "sourceUri" to c.sourceUri,
        "sourceName" to c.sourceName,
        "mediaKind" to c.mediaKind.name,
        "sourceDurationMs" to c.sourceDurationMs,
        "startTimeOnTrackMs" to c.startTimeOnTrackMs,
        "trimStartMs" to c.trimStartMs,
        "trimEndMs" to c.trimEndMs,
        "speed" to c.speed,
        "speedCurvePreset" to c.speedCurvePreset.name,
        "speedCurvePoints" to c.speedCurvePoints.map { mapOf("u" to it.normalizedPosition, "s" to it.speedMultiplier) },
        "isReversed" to c.isReversed,
        "isFrozenFrame" to c.isFrozenFrame,
        "freezeFrameSourceMs" to c.freezeFrameSourceMs,
        "crop" to linkedMapOf(
            "l" to c.crop.left, "t" to c.crop.top,
            "r" to c.crop.right, "b" to c.crop.bottom,
            "p" to c.crop.aspectPresetId
        ),
        "transform" to linkedMapOf(
            "px" to c.transform.positionX, "py" to c.transform.positionY,
            "sx" to c.transform.scaleX, "sy" to c.transform.scaleY,
            "rot" to c.transform.rotationDegrees,
            "fh" to c.transform.flipHorizontal, "fv" to c.transform.flipVertical,
            "op" to c.transform.opacity,
            "blend" to c.transform.blendMode.name
        ),
        "chromaKey" to linkedMapOf(
            "en" to c.chromaKey.enabled,
            "hue" to c.chromaKey.hueDeg,
            "tol" to c.chromaKey.toleranceDeg,
            "soft" to c.chromaKey.softness,
            "sat" to c.chromaKey.minSaturation,
            "amt" to c.chromaKey.amount,
            "spill" to c.chromaKey.spillSuppression
        ),
        "volume" to c.volume,
        "isMuted" to c.isMuted,
        "fadeInMs" to c.fadeInMs,
        "fadeOutMs" to c.fadeOutMs,
        "noiseReduction" to c.noiseReduction,
        "voiceEnhancement" to c.voiceEnhancement,
        "audioDucking" to c.audioDucking,
        "effects" to c.effects.map { fx ->
            linkedMapOf(
                "id" to fx.id,
                "type" to fx.type.name,
                "intensity" to fx.intensity,
                "secondary" to fx.secondaryParam,
                "tertiary" to fx.tertiaryParam,
                "enabled" to fx.enabled
            )
        },
        "colorGrade" to encodeColorGrade(c.colorGrade),
        "transitionIn" to c.transitionIn?.let { encodeTransition(it) },
        "keyframes" to c.keyframes.map { kf ->
            linkedMapOf(
                "id" to kf.id,
                "prop" to kf.property.name,
                "t" to kf.timeOffsetMs,
                "v" to kf.value,
                "e" to kf.easing.name
            )
        },
        "textSpec" to c.textSpec?.let { encodeTextSpec(it) },
        "stickerSpec" to c.stickerSpec?.let {
            linkedMapOf("content" to it.content, "label" to it.label, "category" to it.category)
        },
        "waveformPeaks" to c.waveformPeaks,
        "beatMarkersMs" to c.beatMarkersMs,
        "hasAudioTrack" to c.hasAudioTrack,
        "sourceWidth" to c.sourceWidth,
        "sourceHeight" to c.sourceHeight,
        "rotationDegreesMeta" to c.rotationDegreesMeta,
        "mimeType" to c.mimeType,
        "thumbnailUri" to c.thumbnailUri
    )

    private fun decodeClip(o: JsonObj): TimelineClip {
        val cropObj = o.objOrNull("crop")
        val tfObj = o.objOrNull("transform")
        val chromaObj = o.objOrNull("chromaKey")
        val kind = runCatching { MediaKind.valueOf(o.str("mediaKind", "VIDEO")) }.getOrDefault(MediaKind.VIDEO)
        return TimelineClip(
            id = o.str("id", "clip"),
            trackId = o.str("trackId", "track-video-main"),
            sourceUri = o.str("sourceUri", ""),
            sourceName = o.str("sourceName", "Clip"),
            mediaKind = kind,
            sourceDurationMs = o.long("sourceDurationMs", 5000L),
            startTimeOnTrackMs = o.long("startTimeOnTrackMs", 0L),
            trimStartMs = o.long("trimStartMs", 0L),
            trimEndMs = o.long("trimEndMs", 5000L),
            speed = o.float("speed", 1f),
            speedCurvePreset = runCatching { SpeedCurvePreset.valueOf(o.str("speedCurvePreset", "NONE")) }
                .getOrDefault(SpeedCurvePreset.NONE),
            speedCurvePoints = o.list("speedCurvePoints").mapNotNull { raw ->
                val p = raw.asObj()
                if (p.map.isEmpty()) null else SpeedPoint(p.float("u", 0f), p.float("s", 1f))
            },
            isReversed = o.bool("isReversed", false),
            isFrozenFrame = o.bool("isFrozenFrame", false),
            freezeFrameSourceMs = o.long("freezeFrameSourceMs", 0L),
            crop = if (cropObj != null) {
                CropRect(
                    left = cropObj.float("l", 0f),
                    top = cropObj.float("t", 0f),
                    right = cropObj.float("r", 1f),
                    bottom = cropObj.float("b", 1f),
                    aspectPresetId = cropObj.str("p", "FREE")
                )
            } else CropRect(),
            transform = if (tfObj != null) {
                ClipTransform(
                    positionX = tfObj.float("px", 0f),
                    positionY = tfObj.float("py", 0f),
                    scaleX = tfObj.float("sx", 1f),
                    scaleY = tfObj.float("sy", 1f),
                    rotationDegrees = tfObj.float("rot", 0f),
                    flipHorizontal = tfObj.bool("fh", false),
                    flipVertical = tfObj.bool("fv", false),
                    opacity = tfObj.float("op", 1f),
                    blendMode = BlendMode.fromName(tfObj.str("blend", "NORMAL"))
                )
            } else ClipTransform(),
            chromaKey = if (chromaObj != null) {
                ChromaKeyConfig(
                    enabled = chromaObj.bool("en", false),
                    hueDeg = chromaObj.float("hue", 120f),
                    toleranceDeg = chromaObj.float("tol", 32f),
                    softness = chromaObj.float("soft", 0.18f),
                    minSaturation = chromaObj.float("sat", 0.22f),
                    amount = chromaObj.float("amt", 1f),
                    spillSuppression = chromaObj.float("spill", 0.45f)
                )
            } else ChromaKeyConfig(),
            volume = o.float("volume", 1f),
            isMuted = o.bool("isMuted", false),
            fadeInMs = o.long("fadeInMs", 0L),
            fadeOutMs = o.long("fadeOutMs", 0L),
            noiseReduction = o.float("noiseReduction", 0f),
            voiceEnhancement = o.float("voiceEnhancement", 0f),
            audioDucking = o.bool("audioDucking", false),
            effects = o.list("effects").mapNotNull { raw ->
                val fx = raw.asObj()
                val type = EffectType.fromName(fx.strOrNull("type")) ?: return@mapNotNull null
                AppliedEffect(
                    id = fx.str("id", "fx"),
                    type = type,
                    intensity = fx.float("intensity", type.defaultIntensity),
                    secondaryParam = fx.float("secondary", type.defaultSecondary),
                    tertiaryParam = fx.float("tertiary", type.defaultTertiary),
                    enabled = fx.bool("enabled", true)
                )
            },
            colorGrade = o.objOrNull("colorGrade")?.let { decodeColorGrade(it) } ?: ColorGradeConfig(),
            transitionIn = o.objOrNull("transitionIn")?.let { decodeTransition(it) },
            keyframes = o.list("keyframes").mapNotNull { raw ->
                val k = raw.asObj()
                val prop = runCatching { KeyframeProperty.valueOf(k.str("prop", "OPACITY")) }.getOrNull()
                    ?: return@mapNotNull null
                Keyframe(
                    id = k.str("id", "kf"),
                    property = prop,
                    timeOffsetMs = k.long("t", 0L),
                    value = k.float("v", prop.defaultValue),
                    easing = EasingCurve.fromName(k.str("e", "EASE_IN_OUT"))
                )
            },
            textSpec = o.objOrNull("textSpec")?.let { decodeTextSpec(it) },
            stickerSpec = o.objOrNull("stickerSpec")?.let {
                StickerSpec(it.str("content", "🔥"), it.str("label", "Sticker"), it.str("category", "Trending"))
            },
            waveformPeaks = o.list("waveformPeaks").map { (it as? Number)?.toFloat() ?: 0f },
            beatMarkersMs = o.list("beatMarkersMs").map { (it as? Number)?.toLong() ?: 0L },
            hasAudioTrack = o.bool("hasAudioTrack", kind != MediaKind.IMAGE),
            sourceWidth = o.int("sourceWidth", 1080),
            sourceHeight = o.int("sourceHeight", 1920),
            rotationDegreesMeta = o.int("rotationDegreesMeta", 0),
            mimeType = o.str("mimeType", ""),
            thumbnailUri = o.strOrNull("thumbnailUri")
        )
    }

    // ------------------------------------------------------------------ color

    private fun encodeColorGrade(g: ColorGradeConfig): Map<String, Any?> = linkedMapOf(
        "brightness" to g.brightness,
        "contrast" to g.contrast,
        "saturation" to g.saturation,
        "vibrance" to g.vibrance,
        "exposure" to g.exposure,
        "temperature" to g.temperature,
        "tint" to g.tint,
        "hueShift" to g.hueShift,
        "highlights" to g.highlights,
        "shadows" to g.shadows,
        "whites" to g.whites,
        "blacks" to g.blacks,
        "gamma" to g.gamma,
        "sharpness" to g.sharpness,
        "sharpenRadius" to g.sharpenRadius,
        "sharpenThreshold" to g.sharpenThreshold,
        "clarity" to g.clarity,
        "structure" to g.structure,
        "dehaze" to g.dehaze,
        "denoise" to g.denoise,
        "vignette" to g.vignette,
        "vignetteFeather" to g.vignetteFeather,
        "grain" to g.grain,
        "fade" to g.fade,
        "lutPresetId" to g.lutPresetId,
        "customCubeLutUri" to g.customCubeLutUri,
        "lutIntensity" to g.lutIntensity,
        "hsl" to g.hslBands.map {
            linkedMapOf("b" to it.band.name, "h" to it.hueShift, "s" to it.saturationShift, "l" to it.luminanceShift)
        },
        "wheels" to linkedMapOf(
            "sr" to g.colorWheels.shadows.rOffset, "sg" to g.colorWheels.shadows.gOffset, "sb" to g.colorWheels.shadows.bOffset,
            "mr" to g.colorWheels.midtones.rOffset, "mg" to g.colorWheels.midtones.gOffset, "mb" to g.colorWheels.midtones.bOffset,
            "hr" to g.colorWheels.highlights.rOffset, "hg" to g.colorWheels.highlights.gOffset, "hb" to g.colorWheels.highlights.bOffset
        ),
        "curves" to linkedMapOf(
            "m" to g.curves.master.map { listOf(it.x, it.y) },
            "r" to g.curves.red.map { listOf(it.x, it.y) },
            "g" to g.curves.green.map { listOf(it.x, it.y) },
            "b" to g.curves.blue.map { listOf(it.x, it.y) }
        )
    )

    private fun decodeColorGrade(o: JsonObj): ColorGradeConfig {
        val w = o.objOrNull("wheels")
        val c = o.objOrNull("curves")
        fun parseCurve(key: String): List<CurvePoint> {
            val arr = c?.list(key).orEmpty()
            if (arr.isEmpty()) return RgbCurvesConfig.defaultCurve()
            return arr.mapNotNull { item ->
                val pair = item as? List<*> ?: return@mapNotNull null
                if (pair.size >= 2) {
                    CurvePoint((pair[0] as? Number)?.toFloat() ?: 0f, (pair[1] as? Number)?.toFloat() ?: 0f)
                } else null
            }.ifEmpty { RgbCurvesConfig.defaultCurve() }
        }

        val parsedHsl = o.list("hsl").mapNotNull { item ->
            val ho = item.asObj()
            val band = runCatching { HslBand.valueOf(ho.str("b", "RED")) }.getOrNull() ?: return@mapNotNull null
            HslBandAdjustment(band, ho.float("h", 0f), ho.float("s", 0f), ho.float("l", 0f))
        }
        val completeHsl = HslBand.entries.map { band ->
            parsedHsl.firstOrNull { it.band == band } ?: HslBandAdjustment(band)
        }

        return ColorGradeConfig(
            brightness = o.float("brightness", 0f),
            contrast = o.float("contrast", 0f),
            saturation = o.float("saturation", 0f),
            vibrance = o.float("vibrance", 0f),
            exposure = o.float("exposure", 0f),
            temperature = o.float("temperature", 0f),
            tint = o.float("tint", 0f),
            hueShift = o.float("hueShift", 0f),
            highlights = o.float("highlights", 0f),
            shadows = o.float("shadows", 0f),
            whites = o.float("whites", 0f),
            blacks = o.float("blacks", 0f),
            gamma = o.float("gamma", 0f),
            sharpness = o.float("sharpness", 0f),
            sharpenRadius = o.float("sharpenRadius", 0.35f),
            sharpenThreshold = o.float("sharpenThreshold", 0.12f),
            clarity = o.float("clarity", 0f),
            structure = o.float("structure", 0f),
            dehaze = o.float("dehaze", 0f),
            denoise = o.float("denoise", 0f),
            vignette = o.float("vignette", 0f),
            vignetteFeather = o.float("vignetteFeather", 0.5f),
            grain = o.float("grain", 0f),
            fade = o.float("fade", 0f),
            lutPresetId = o.strOrNull("lutPresetId"),
            customCubeLutUri = o.strOrNull("customCubeLutUri"),
            lutIntensity = o.float("lutIntensity", 1f),
            hslBands = completeHsl,
            colorWheels = if (w != null) {
                ColorWheelsConfig(
                    shadows = ColorWheelTint(w.float("sr", 0f), w.float("sg", 0f), w.float("sb", 0f)),
                    midtones = ColorWheelTint(w.float("mr", 0f), w.float("mg", 0f), w.float("mb", 0f)),
                    highlights = ColorWheelTint(w.float("hr", 0f), w.float("hg", 0f), w.float("hb", 0f))
                )
            } else ColorWheelsConfig(),
            curves = if (c != null) {
                RgbCurvesConfig(
                    master = parseCurve("m"),
                    red = parseCurve("r"),
                    green = parseCurve("g"),
                    blue = parseCurve("b")
                )
            } else RgbCurvesConfig()
        )
    }

    private fun encodePreset(p: ColorGradePreset): Map<String, Any?> = linkedMapOf(
        "id" to p.id,
        "name" to p.name,
        "category" to p.category,
        "accentHex" to p.accentHex,
        "secondaryHex" to p.secondaryHex,
        "isUserCustom" to p.isUserCustom,
        "config" to encodeColorGrade(p.config)
    )

    private fun decodePreset(o: JsonObj): ColorGradePreset = ColorGradePreset(
        id = o.str("id", "preset"),
        name = o.str("name", "Custom Preset"),
        category = o.str("category", "Custom"),
        accentHex = o.long("accentHex", 0xFF00E5FF),
        secondaryHex = o.long("secondaryHex", 0xFF1E293B),
        isUserCustom = o.bool("isUserCustom", true),
        config = o.objOrNull("config")?.let { decodeColorGrade(it) } ?: ColorGradeConfig()
    )

    // ------------------------------------------------------------------ misc

    private fun encodeTransition(t: ClipTransition): Map<String, Any?> = linkedMapOf(
        "type" to t.type.name,
        "durationMs" to t.durationMs,
        "angle" to t.directionAngleDeg,
        "softness" to t.softness,
        "chromatic" to t.chromaticAmount
    )

    private fun decodeTransition(o: JsonObj): ClipTransition {
        val type = runCatching { TransitionType.valueOf(o.str("type", "DISSOLVE")) }
            .getOrDefault(TransitionType.DISSOLVE)
        return ClipTransition(
            type = type,
            durationMs = o.long("durationMs", type.defaultDurationMs),
            directionAngleDeg = o.float("angle", 0f),
            softness = o.float("softness", 0.35f),
            chromaticAmount = o.float("chromatic", 0.4f)
        )
    }

    private fun encodeTextSpec(t: TextStyleSpec): Map<String, Any?> = linkedMapOf(
        "text" to t.text,
        "font" to t.fontFamilyName,
        "size" to t.fontSizeSp,
        "color" to t.textColorArgb,
        "gradEnd" to t.gradientEndColorArgb,
        "useGrad" to t.useGradient,
        "shadowR" to t.shadowRadius,
        "shadowC" to t.shadowColorArgb,
        "outlineW" to t.outlineWidth,
        "outlineC" to t.outlineColorArgb,
        "bgC" to t.backgroundColorArgb,
        "spacing" to t.letterSpacingEm,
        "anim" to t.animationIn.name,
        "animDur" to t.animationDurationMs,
        "lang" to t.languageTag
    )

    private fun decodeTextSpec(o: JsonObj): TextStyleSpec = TextStyleSpec(
        text = o.str("text", "Zero Text"),
        fontFamilyName = o.str("font", "Sans Bold"),
        fontSizeSp = o.float("size", 28f),
        textColorArgb = o.long("color", 0xFFFFFFFF),
        gradientEndColorArgb = o.long("gradEnd", 0xFF00E5FF),
        useGradient = o.bool("useGrad", false),
        shadowRadius = o.float("shadowR", 6f),
        shadowColorArgb = o.long("shadowC", 0xCC000000),
        outlineWidth = o.float("outlineW", 0f),
        outlineColorArgb = o.long("outlineC", 0xFF000000),
        backgroundColorArgb = o.long("bgC", 0x00000000),
        letterSpacingEm = o.float("spacing", 0.02f),
        animationIn = runCatching { TextAnimationType.valueOf(o.str("anim", "FADE_UP")) }
            .getOrDefault(TextAnimationType.FADE_UP),
        animationDurationMs = o.long("animDur", 450L),
        languageTag = o.str("lang", "en-US")
    )

    private fun encodeSubtitle(s: SubtitleCue): Map<String, Any?> = linkedMapOf(
        "id" to s.id,
        "text" to s.text,
        "start" to s.startTimeMs,
        "end" to s.endTimeMs,
        "lang" to s.languageTag,
        "conf" to s.confidence
    )

    private fun decodeSubtitle(o: JsonObj): SubtitleCue = SubtitleCue(
        id = o.str("id", "sub"),
        text = o.str("text", ""),
        startTimeMs = o.long("start", 0L),
        endTimeMs = o.long("end", 1500L),
        languageTag = o.str("lang", "en-US"),
        confidence = o.float("conf", 1f)
    )

    private fun encodeExportConfig(e: ExportConfig): Map<String, Any?> = linkedMapOf(
        "resolution" to e.resolution.name,
        "frameRate" to e.frameRate.name,
        "bitrateTier" to e.bitrateTier.name,
        "customBitrateMbps" to e.customBitrateMbps,
        "codec" to e.codec.name,
        "audioOnly" to e.audioOnly,
        "exportAsPhotoFrame" to e.exportAsPhotoFrame,
        "audioBitrateKbps" to e.audioBitrateKbps,
        "saveToGallery" to e.saveToGallery
    )

    private fun decodeExportConfig(o: JsonObj): ExportConfig = ExportConfig(
        resolution = runCatching { ExportResolution.valueOf(o.str("resolution", "FHD_1080P")) }
            .getOrDefault(ExportResolution.FHD_1080P),
        frameRate = runCatching { ExportFrameRate.valueOf(o.str("frameRate", "FPS_30")) }
            .getOrDefault(ExportFrameRate.FPS_30),
        bitrateTier = runCatching { BitrateTier.valueOf(o.str("bitrateTier", "HIGH")) }
            .getOrDefault(BitrateTier.HIGH),
        customBitrateMbps = o.float("customBitrateMbps", 16f),
        codec = runCatching { VideoCodecOption.valueOf(o.str("codec", "H264")) }
            .getOrDefault(VideoCodecOption.H264),
        audioOnly = o.bool("audioOnly", false),
        exportAsPhotoFrame = o.bool("exportAsPhotoFrame", false),
        audioBitrateKbps = o.int("audioBitrateKbps", 192),
        saveToGallery = o.bool("saveToGallery", true)
    )

    private fun encodeEnhancement(e: EnhancementConfig): Map<String, Any?> = linkedMapOf(
        "enabled" to e.enabled,
        "sharpen" to e.sharpen,
        "denoise" to e.denoise,
        "contrast" to e.contrastBoost,
        "color" to e.colorBoost,
        "detail" to e.detailPreserve,
        "brightness" to e.brightnessFix,
        "targetHeight" to e.targetHeight,
        "engine" to e.engine.name
    )

    private fun decodeEnhancement(o: JsonObj): EnhancementConfig = EnhancementConfig(
        enabled = o.bool("enabled", false),
        sharpen = o.float("sharpen", 0.45f),
        denoise = o.float("denoise", 0.25f),
        contrastBoost = o.float("contrast", 0.12f),
        colorBoost = o.float("color", 0.10f),
        detailPreserve = o.float("detail", 0.55f),
        brightnessFix = o.float("brightness", 0f),
        targetHeight = o.int("targetHeight", 1080),
        engine = runCatching { UpscaleEngine.valueOf(o.str("engine", "LANCZOS")) }
            .getOrDefault(UpscaleEngine.LANCZOS)
    )

    // ------------------------------------------------------------------ mini JSON

    @PublishedApi
    internal class JsonObj(val map: Map<String, Any?>) {
        fun str(k: String, def: String): String = (map[k] as? String) ?: def
        fun strOrNull(k: String): String? = map[k] as? String
        fun int(k: String, def: Int): Int = (map[k] as? Number)?.toInt() ?: def
        fun long(k: String, def: Long): Long = (map[k] as? Number)?.toLong() ?: def
        fun float(k: String, def: Float): Float = (map[k] as? Number)?.toFloat() ?: def
        fun bool(k: String, def: Boolean): Boolean = (map[k] as? Boolean) ?: def
        fun list(k: String): List<Any?> = (map[k] as? List<*>) ?: emptyList()
        fun objOrNull(k: String): JsonObj? = (map[k] as? Map<*, *>)?.let { raw ->
            @Suppress("UNCHECKED_CAST")
            JsonObj(raw as Map<String, Any?>)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun Any?.asObj(): JsonObj = JsonObj((this as? Map<String, Any?>) ?: emptyMap())
}

/**
 * Minimal JSON reader/writer shared by the project codec and the preset store.
 * Handles only what ZERO serializes: objects, arrays, strings, numbers, booleans, null.
 */
object MiniJson {

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("0") else sb.append(v)
            }
            is String -> writeEscapedString(sb, v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, item) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeEscapedString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, item)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, item)
                }
                sb.append(']')
            }
            else -> writeEscapedString(sb, v.toString())
        }
    }

    private fun writeEscapedString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    fun parseValue(src: String): Any? = Parser(src).parseValue()

    fun parseObject(src: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return (parseValue(src) as? Map<String, Any?>) ?: emptyMap()
    }

    fun parseArray(src: String): List<Any?> = (parseValue(src) as? List<Any?>) ?: emptyList()

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWs()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> parseMap()
                '[' -> parseList()
                '"' -> parseString()
                't' -> { pos += 4; true }
                'f' -> { pos += 5; false }
                'n' -> { pos += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseMap(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (pos < s.length) {
                skipWs()
                val key = parseString()
                skipWs()
                if (pos < s.length && s[pos] == ':') pos++
                map[key] = parseValue()
                skipWs()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == '}') {
                    pos++
                    break
                } else break
            }
            return map
        }

        private fun parseList(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (pos < s.length) {
                list.add(parseValue())
                skipWs()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == ']') {
                    pos++
                    break
                } else break
            }
            return list
        }

        private fun parseString(): String {
            if (pos < s.length && s[pos] == '"') pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                if (ch == '"') break
                if (ch == '\\' && pos < s.length) {
                    when (val esc = s[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (pos + 4 <= s.length) {
                            val hex = s.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < s.length && s[pos] in "0123456789+-eE.") pos++
            val token = s.substring(start, pos)
            return if ('.' in token || 'e' in token || 'E' in token) {
                token.toDoubleOrNull() ?: 0.0
            } else {
                token.toLongOrNull() ?: 0L
            }
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
