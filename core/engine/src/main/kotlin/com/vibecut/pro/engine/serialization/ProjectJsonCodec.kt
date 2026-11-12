package com.vibecut.pro.engine.serialization

import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.templates.ProjectTemplate
import com.vibecut.pro.engine.templates.TemplateCategory
import com.vibecut.pro.engine.templates.TemplateSlotSpec

/**
 * Self-contained lossless JSON serializer & parser for [Project] and [ProjectTemplate].
 * Has zero external runtime reflection dependencies so it runs identically on Android R8/ProGuard
 * release builds and pure JVM unit tests.
 */
object ProjectJsonCodec {

    fun encodeProject(project: Project): String {
        val map = linkedMapOf<String, Any?>(
            "id" to project.id,
            "title" to project.title,
            "aspectRatio" to project.aspectRatio.id,
            "backgroundColorArgb" to project.backgroundColorArgb,
            "magneticTimeline" to project.magneticTimeline,
            "snappingEnabled" to project.snappingEnabled,
            "timelineZoom" to project.timelineZoom,
            "thumbnailUri" to project.thumbnailUri,
            "createdAtMs" to project.createdAtMs,
            "updatedAtMs" to project.updatedAtMs,
            "revision" to project.revision,
            "exportConfig" to encodeExportConfig(project.exportConfig),
            "subtitles" to project.subtitles.map { encodeSubtitle(it) },
            "customColorPresets" to project.customColorPresets.map { encodePreset(it) },
            "tracks" to project.tracks.map { encodeTrack(it) }
        )
        return MiniJson.stringify(map)
    }

    fun decodeProject(json: String): Project {
        val root = MiniJson.parseObject(json)
        val tracksList = root.list("tracks").map { decodeTrack(it.asObj()) }
        return Project(
            id = root.str("id", Project().id),
            title = root.str("title", "Untitled Edit"),
            aspectRatio = AspectRatioPreset.fromId(root.str("aspectRatio", "9:16")),
            backgroundColorArgb = root.long("backgroundColorArgb", 0xFF000000),
            magneticTimeline = root.bool("magneticTimeline", true),
            snappingEnabled = root.bool("snappingEnabled", true),
            timelineZoom = root.float("timelineZoom", 1.0f),
            tracks = tracksList.ifEmpty { Project.defaultTracks() },
            subtitles = root.list("subtitles").map { decodeSubtitle(it.asObj()) },
            customColorPresets = root.list("customColorPresets").map { decodePreset(it.asObj()) },
            exportConfig = root.objOrNull("exportConfig")?.let { decodeExportConfig(it) } ?: ExportConfig(),
            thumbnailUri = root.strOrNull("thumbnailUri"),
            createdAtMs = root.long("createdAtMs", System.currentTimeMillis()),
            updatedAtMs = root.long("updatedAtMs", System.currentTimeMillis()),
            revision = root.int("revision", 1)
        )
    }

    fun encodeTemplate(tpl: ProjectTemplate): String = MiniJson.stringify(
        linkedMapOf(
            "id" to tpl.id,
            "name" to tpl.name,
            "category" to tpl.category.name,
            "description" to tpl.description,
            "aspectRatio" to tpl.aspectRatio.id,
            "accentColorHex" to tpl.accentColorHex,
            "bpm" to tpl.bpm,
            "colorPresetId" to tpl.colorPresetId,
            "defaultHeadlineText" to tpl.defaultHeadlineText,
            "defaultSubtitleText" to tpl.defaultSubtitleText,
            "textAnimation" to tpl.textAnimation.name,
            "suggestedSfxId" to tpl.suggestedSfxId,
            "isUserSaved" to tpl.isUserSaved,
            "slots" to tpl.slots.map { slot ->
                linkedMapOf(
                    "slotDurationMs" to slot.slotDurationMs,
                    "speed" to slot.speed,
                    "speedCurve" to slot.speedCurve.name,
                    "effect" to slot.effect?.name,
                    "effectIntensity" to slot.effectIntensity,
                    "transitionIn" to slot.transitionIn?.name
                )
            }
        )
    )

    fun decodeTemplate(json: String): ProjectTemplate {
        val o = MiniJson.parseObject(json)
        return ProjectTemplate(
            id = o.str("id", "tpl-custom"),
            name = o.str("name", "Custom Template"),
            category = runCatching { TemplateCategory.valueOf(o.str("category", "TRENDING")) }
                .getOrDefault(TemplateCategory.TRENDING),
            description = o.str("description", ""),
            aspectRatio = AspectRatioPreset.fromId(o.str("aspectRatio", "9:16")),
            accentColorHex = o.long("accentColorHex", 0xFF00E5FF),
            bpm = o.int("bpm", 120),
            colorPresetId = o.str("colorPresetId", "preset-teal-orange"),
            defaultHeadlineText = o.str("defaultHeadlineText", ""),
            defaultSubtitleText = o.str("defaultSubtitleText", ""),
            textAnimation = runCatching { TextAnimationType.valueOf(o.str("textAnimation", "POP_BOUNCE")) }
                .getOrDefault(TextAnimationType.POP_BOUNCE),
            suggestedSfxId = o.str("suggestedSfxId", "sfx_whoosh"),
            isUserSaved = o.bool("isUserSaved", true),
            slots = o.list("slots").map { raw ->
                val s = raw.asObj()
                TemplateSlotSpec(
                    slotDurationMs = s.long("slotDurationMs", 2000L),
                    speed = s.float("speed", 1f),
                    speedCurve = runCatching { SpeedCurvePreset.valueOf(s.str("speedCurve", "NONE")) }
                        .getOrDefault(SpeedCurvePreset.NONE),
                    effect = s.strOrNull("effect")?.let { n -> runCatching { EffectType.valueOf(n) }.getOrNull() },
                    effectIntensity = s.float("effectIntensity", 0.6f),
                    transitionIn = s.strOrNull("transitionIn")?.let { n -> runCatching { TransitionType.valueOf(n) }.getOrNull() }
                )
            }
        )
    }

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
        volume = o.float("volume", 1.0f),
        clips = o.list("clips").map { decodeClip(it.asObj()) }
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
            "op" to c.transform.opacity
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
        "transitionOut" to c.transitionOut?.let { encodeTransition(it) },
        "keyframes" to c.keyframes.map { kf ->
            linkedMapOf(
                "id" to kf.id,
                "prop" to kf.property.name,
                "t" to kf.timeOffsetMs,
                "v" to kf.value,
                "e" to kf.easing.name,
                "c1x" to kf.customC1x, "c1y" to kf.customC1y,
                "c2x" to kf.customC2x, "c2y" to kf.customC2y
            )
        },
        "textSpec" to c.textSpec?.let { encodeTextSpec(it) },
        "stickerSpec" to c.stickerSpec?.let { linkedMapOf("content" to it.content, "label" to it.label, "category" to it.category) },
        "waveformPeaks" to c.waveformPeaks,
        "beatMarkersMs" to c.beatMarkersMs,
        "upscaleTargetHeight" to c.upscaleTargetHeight,
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
        val decodedKind = runCatching { MediaKind.valueOf(o.str("mediaKind", "VIDEO")) }.getOrDefault(MediaKind.VIDEO)
        return TimelineClip(
            id = o.str("id", "clip"),
            trackId = o.str("trackId", "track-video-main"),
            sourceUri = o.str("sourceUri", ""),
            sourceName = o.str("sourceName", "Clip"),
            mediaKind = decodedKind,
            sourceDurationMs = o.long("sourceDurationMs", 5000L),
            startTimeOnTrackMs = o.long("startTimeOnTrackMs", 0L),
            trimStartMs = o.long("trimStartMs", 0L),
            trimEndMs = o.long("trimEndMs", 5000L),
            speed = o.float("speed", 1f),
            speedCurvePreset = runCatching { SpeedCurvePreset.valueOf(o.str("speedCurvePreset", "NONE")) }
                .getOrDefault(SpeedCurvePreset.NONE),
            speedCurvePoints = o.list("speedCurvePoints").map {
                val p = it.asObj()
                SpeedPoint(p.float("u", 0f), p.float("s", 1f))
            },
            isReversed = o.bool("isReversed", false),
            isFrozenFrame = o.bool("isFrozenFrame", false),
            freezeFrameSourceMs = o.long("freezeFrameSourceMs", 0L),
            crop = if (cropObj != null) CropRect(
                left = cropObj.float("l", 0f),
                top = cropObj.float("t", 0f),
                right = cropObj.float("r", 1f),
                bottom = cropObj.float("b", 1f),
                aspectPresetId = cropObj.str("p", "FREE")
            ) else CropRect(),
            transform = if (tfObj != null) ClipTransform(
                positionX = tfObj.float("px", 0f),
                positionY = tfObj.float("py", 0f),
                scaleX = tfObj.float("sx", 1f),
                scaleY = tfObj.float("sy", 1f),
                rotationDegrees = tfObj.float("rot", 0f),
                flipHorizontal = tfObj.bool("fh", false),
                flipVertical = tfObj.bool("fv", false),
                opacity = tfObj.float("op", 1f)
            ) else ClipTransform(),
            volume = o.float("volume", 1f),
            isMuted = o.bool("isMuted", false),
            fadeInMs = o.long("fadeInMs", 0L),
            fadeOutMs = o.long("fadeOutMs", 0L),
            noiseReduction = o.float("noiseReduction", 0f),
            voiceEnhancement = o.float("voiceEnhancement", 0f),
            audioDucking = o.bool("audioDucking", false),
            effects = o.list("effects").mapNotNull { raw ->
                val fx = raw.asObj()
                val type = runCatching { EffectType.valueOf(fx.str("type", "SHARPEN")) }.getOrNull()
                    ?: return@mapNotNull null
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
            transitionOut = o.objOrNull("transitionOut")?.let { decodeTransition(it) },
            keyframes = o.list("keyframes").mapNotNull { raw ->
                val k = raw.asObj()
                val prop = runCatching { KeyframeProperty.valueOf(k.str("prop", "OPACITY")) }.getOrNull()
                    ?: return@mapNotNull null
                val easing = runCatching { EasingCurve.valueOf(k.str("e", "EASE_IN_OUT")) }
                    .getOrDefault(EasingCurve.EASE_IN_OUT)
                Keyframe(
                    id = k.str("id", "kf"),
                    property = prop,
                    timeOffsetMs = k.long("t", 0L),
                    value = k.float("v", prop.defaultValue),
                    easing = easing,
                    customC1x = k.float("c1x", easing.c1x),
                    customC1y = k.float("c1y", easing.c1y),
                    customC2x = k.float("c2x", easing.c2x),
                    customC2y = k.float("c2y", easing.c2y)
                )
            },
            textSpec = o.objOrNull("textSpec")?.let { decodeTextSpec(it) },
            stickerSpec = o.objOrNull("stickerSpec")?.let {
                StickerSpec(it.str("content", "🔥"), it.str("label", "Sticker"), it.str("category", "Trending"))
            },
            waveformPeaks = o.list("waveformPeaks").map { (it as? Number)?.toFloat() ?: 0f },
            beatMarkersMs = o.list("beatMarkersMs").map { (it as? Number)?.toLong() ?: 0L },
            upscaleTargetHeight = o.intOrNull("upscaleTargetHeight"),
            hasAudioTrack = o.bool("hasAudioTrack", decodedKind != MediaKind.IMAGE),
            sourceWidth = o.int("sourceWidth", 1080),
            sourceHeight = o.int("sourceHeight", 1920),
            rotationDegreesMeta = o.int("rotationDegreesMeta", 0),
            mimeType = o.str("mimeType", ""),
            thumbnailUri = o.strOrNull("thumbnailUri")
        )
    }

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
        "hsl" to g.hslBands.map { linkedMapOf("b" to it.band.name, "h" to it.hueShift, "s" to it.saturationShift, "l" to it.luminanceShift) },
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
            colorWheels = if (w != null) ColorWheelsConfig(
                shadows = ColorWheelTint(w.float("sr", 0f), w.float("sg", 0f), w.float("sb", 0f)),
                midtones = ColorWheelTint(w.float("mr", 0f), w.float("mg", 0f), w.float("mb", 0f)),
                highlights = ColorWheelTint(w.float("hr", 0f), w.float("hg", 0f), w.float("hb", 0f))
            ) else ColorWheelsConfig(),
            curves = if (c != null) RgbCurvesConfig(
                master = parseCurve("m"),
                red = parseCurve("r"),
                green = parseCurve("g"),
                blue = parseCurve("b")
            ) else RgbCurvesConfig()
        )
    }

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
        "is3d" to t.is3dExtruded,
        "depth" to t.extrudeDepth,
        "spacing" to t.letterSpacingEm,
        "anim" to t.animationIn.name,
        "animDur" to t.animationDurationMs,
        "lang" to t.languageTag,
        "tplId" to t.templateId
    )

    private fun decodeTextSpec(o: JsonObj): TextStyleSpec = TextStyleSpec(
        text = o.str("text", "VibeCut Pro"),
        fontFamilyName = o.str("font", "Sans-Serif Bold"),
        fontSizeSp = o.float("size", 28f),
        textColorArgb = o.long("color", 0xFFFFFFFF),
        gradientEndColorArgb = o.long("gradEnd", 0xFF00E5FF),
        useGradient = o.bool("useGrad", false),
        shadowRadius = o.float("shadowR", 6f),
        shadowColorArgb = o.long("shadowC", 0xCC000000),
        outlineWidth = o.float("outlineW", 2f),
        outlineColorArgb = o.long("outlineC", 0xFF000000),
        backgroundColorArgb = o.long("bgC", 0x00000000),
        is3dExtruded = o.bool("is3d", false),
        extrudeDepth = o.float("depth", 4f),
        letterSpacingEm = o.float("spacing", 0.02f),
        animationIn = runCatching { TextAnimationType.valueOf(o.str("anim", "POP_BOUNCE")) }
            .getOrDefault(TextAnimationType.POP_BOUNCE),
        animationDurationMs = o.long("animDur", 450L),
        languageTag = o.str("lang", "en-US"),
        templateId = o.strOrNull("tplId")
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

    private fun encodeExportConfig(e: ExportConfig): Map<String, Any?> = linkedMapOf(
        "resolution" to e.resolution.name,
        "frameRate" to e.frameRate.name,
        "bitrateTier" to e.bitrateTier.name,
        "customBitrateMbps" to e.customBitrateMbps,
        "codec" to e.codec.name,
        "audioOnly" to e.audioOnly,
        "exportAsPhotoFrame" to e.exportAsPhotoFrame,
        "audioBitrateKbps" to e.audioBitrateKbps,
        "saveToGallery" to e.saveToGallery,
        "runInBackground" to e.runInBackground
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
        saveToGallery = o.bool("saveToGallery", true),
        runInBackground = o.bool("runInBackground", false)
    )

    private class JsonObj(val map: Map<String, Any?>) {
        fun str(k: String, def: String): String = (map[k] as? String) ?: def
        fun strOrNull(k: String): String? = map[k] as? String
        fun int(k: String, def: Int): Int = (map[k] as? Number)?.toInt() ?: def
        fun intOrNull(k: String): Int? = (map[k] as? Number)?.toInt()
        fun long(k: String, def: Long): Long = (map[k] as? Number)?.toLong() ?: def
        fun float(k: String, def: Float): Float = (map[k] as? Number)?.toFloat() ?: def
        fun bool(k: String, def: Boolean): Boolean = (map[k] as? Boolean) ?: def
        fun list(k: String): List<Any?> = (map[k] as? List<*>) ?: emptyList()
        fun objOrNull(k: String): JsonObj? = (map[k] as? Map<*, *>)?.let {
            @Suppress("UNCHECKED_CAST")
            JsonObj(it as Map<String, Any?>)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObj(): JsonObj =
        JsonObj((this as? Map<String, Any?>) ?: emptyMap())

    private object MiniJson {
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
                    else -> {
                        if (ch.code < 0x20) {
                            sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                        } else {
                            sb.append(ch)
                        }
                    }
                }
            }
            sb.append('"')
        }

        fun parseObject(src: String): JsonObj {
            val parser = Parser(src)
            val v = parser.parseValue()
            @Suppress("UNCHECKED_CAST")
            return JsonObj((v as? Map<String, Any?>) ?: emptyMap())
        }

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
                    val value = parseValue()
                    map[key] = value
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
                if (s[pos] == '"') pos++
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
                            'u' -> {
                                if (pos + 4 <= s.length) {
                                    val hex = s.substring(pos, pos + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    pos += 4
                                }
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
}
