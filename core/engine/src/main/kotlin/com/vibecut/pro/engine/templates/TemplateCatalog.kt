package com.vibecut.pro.engine.templates

import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.timeline.ImportedMediaSource
import com.vibecut.pro.engine.timeline.TimelineEngine
import java.util.UUID
import kotlin.math.min

enum class TemplateCategory(val label: String, val badgeEmoji: String) {
    INSTAGRAM_REELS("Instagram Reels", "📱"),
    YOUTUBE_SHORTS("YouTube Shorts", "⚡"),
    CINEMATIC("Cinematic", "🎬"),
    BIRTHDAY("Birthday", "🎂"),
    WEDDING("Wedding", "💍"),
    TRAVEL("Travel", "✈️"),
    GAMING("Gaming", "🎮"),
    TRENDING("Trending", "🔥"),
    BUSINESS("Business", "💼"),
    MOTIVATION("Motivation", "🦁"),
    SLOW_MOTION("Slow Motion", "⏳"),
    STATUS_VIDEOS("Status Videos", "✨")
}

data class TemplateSlotSpec(
    val slotDurationMs: Long,
    val speed: Float = 1.0f,
    val speedCurve: SpeedCurvePreset = SpeedCurvePreset.NONE,
    val effect: EffectType? = null,
    val effectIntensity: Float = 0.6f,
    val transitionIn: TransitionType? = null
)

data class ProjectTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: TemplateCategory,
    val description: String,
    val aspectRatio: AspectRatioPreset,
    val accentColorHex: Long,
    val bpm: Int,
    val slots: List<TemplateSlotSpec>,
    val colorPresetId: String,
    val defaultHeadlineText: String,
    val defaultSubtitleText: String,
    val textAnimation: TextAnimationType = TextAnimationType.POP_BOUNCE,
    val suggestedSfxId: String = "sfx_whoosh",
    val isUserSaved: Boolean = false
) {
    val totalDurationMs: Long get() = slots.sumOf { it.slotDurationMs }
    val slotCount: Int get() = slots.size
}

object TemplateCatalog {

    val builtInTemplates: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            id = "tpl-reels-beat-drop",
            name = "Neon Beat Drop Reel",
            category = TemplateCategory.INSTAGRAM_REELS,
            description = "Fast 4-clip beat sync with RGB split, crisp sharpen & flash transitions.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFFE040FB,
            bpm = 128,
            slots = listOf(
                TemplateSlotSpec(1400L, effect = EffectType.SHARPEN, effectIntensity = 0.65f),
                TemplateSlotSpec(950L, effect = EffectType.RGB_SPLIT, transitionIn = TransitionType.FLASH),
                TemplateSlotSpec(950L, speedCurve = SpeedCurvePreset.MONTAGE, transitionIn = TransitionType.ZOOM),
                TemplateSlotSpec(1800L, effect = EffectType.GLOW, transitionIn = TransitionType.GLITCH)
            ),
            colorPresetId = "preset-cyberpunk",
            defaultHeadlineText = "NEW VIBE UNLOCKED",
            defaultSubtitleText = "Watch till the drop 🔥"
        ),
        ProjectTemplate(
            id = "tpl-reels-aesthetic",
            name = "Aesthetic Daily Mini-Vlog",
            category = TemplateCategory.INSTAGRAM_REELS,
            description = "Warm Fuji film tones, subtle film grain & clean vlog typography.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFFFFAB91,
            bpm = 100,
            slots = listOf(
                TemplateSlotSpec(2000L, effect = EffectType.FILM_GRAIN, effectIntensity = 0.35f),
                TemplateSlotSpec(1800L, transitionIn = TransitionType.DISSOLVE),
                TemplateSlotSpec(2200L, effect = EffectType.LIGHT_LEAKS, transitionIn = TransitionType.DISSOLVE)
            ),
            colorPresetId = "preset-fuji-eterna",
            defaultHeadlineText = "spend the day with me",
            defaultSubtitleText = "coffee • golden hour • studio",
            textAnimation = TextAnimationType.TYPEWRITER
        ),
        ProjectTemplate(
            id = "tpl-shorts-hook",
            name = "High-Retention Shorts Hook",
            category = TemplateCategory.YOUTUBE_SHORTS,
            description = "Crisp vivid sharpness, punch-zoom cuts & bold retention captions.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFFFF1744,
            bpm = 135,
            slots = listOf(
                TemplateSlotSpec(1500L, effect = EffectType.SHARPEN, effectIntensity = 0.75f),
                TemplateSlotSpec(1500L, effect = EffectType.ZOOM, transitionIn = TransitionType.SWIPE),
                TemplateSlotSpec(2000L, effect = EffectType.SHARPEN, transitionIn = TransitionType.FLASH)
            ),
            colorPresetId = "preset-crisp-sharp",
            defaultHeadlineText = "3 SECRETS PROS USE",
            defaultSubtitleText = "Don't skip step #3 ⚡"
        ),
        ProjectTemplate(
            id = "tpl-cinema-scope",
            name = "Anamorphic 2.39 Trailer",
            category = TemplateCategory.CINEMATIC,
            description = "Kodak 2383 print LUT, letterbox scope bars, sub-boom & wide title tracking.",
            aspectRatio = AspectRatioPreset.CINEMA_21_9,
            accentColorHex = 0xFFFFB300,
            bpm = 90,
            slots = listOf(
                TemplateSlotSpec(2800L, effect = EffectType.CINEMATIC, effectIntensity = 0.8f),
                TemplateSlotSpec(2400L, speedCurve = SpeedCurvePreset.HERO, transitionIn = TransitionType.FADE),
                TemplateSlotSpec(3000L, effect = EffectType.CINEMATIC, transitionIn = TransitionType.CINEMATIC)
            ),
            colorPresetId = "preset-kodak-2383",
            defaultHeadlineText = "BEYOND THE HORIZON",
            defaultSubtitleText = "IN ULTRA HD",
            textAnimation = TextAnimationType.CINEMATIC_TRACKING,
            suggestedSfxId = "sfx_boom"
        ),
        ProjectTemplate(
            id = "tpl-birthday-sparkle",
            name = "Birthday Celebration Bash",
            category = TemplateCategory.BIRTHDAY,
            description = "Warm golden glow, light leaks, pop-bounce celebration titles & confetti energy.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFFFFD54F,
            bpm = 122,
            slots = listOf(
                TemplateSlotSpec(1800L, effect = EffectType.LIGHT_LEAKS, effectIntensity = 0.6f),
                TemplateSlotSpec(1600L, effect = EffectType.GLOW, transitionIn = TransitionType.SLIDE),
                TemplateSlotSpec(2200L, effect = EffectType.SHARPEN, transitionIn = TransitionType.FLASH)
            ),
            colorPresetId = "preset-golden-hour",
            defaultHeadlineText = "HAPPY BIRTHDAY 🎂✨",
            defaultSubtitleText = "Cheers to another iconic year!"
        ),
        ProjectTemplate(
            id = "tpl-wedding-eternal",
            name = "Royal Wedding Film",
            category = TemplateCategory.WEDDING,
            description = "Dreamy soft bloom, slow-motion elegance, pastel highlights & cinema burn.",
            aspectRatio = AspectRatioPreset.YOUTUBE_16_9,
            accentColorHex = 0xFFF48FB1,
            bpm = 84,
            slots = listOf(
                TemplateSlotSpec(3000L, speed = 0.6f, effect = EffectType.GLOW, effectIntensity = 0.45f),
                TemplateSlotSpec(2800L, speed = 0.5f, effect = EffectType.LIGHT_LEAKS, transitionIn = TransitionType.CINEMATIC),
                TemplateSlotSpec(3200L, speed = 0.7f, effect = EffectType.VIGNETTE, transitionIn = TransitionType.DISSOLVE)
            ),
            colorPresetId = "preset-pastel-dream",
            defaultHeadlineText = "FOREVER BEGINS TODAY",
            defaultSubtitleText = "Our Wedding Story 💍",
            textAnimation = TextAnimationType.FADE_UP
        ),
        ProjectTemplate(
            id = "tpl-travel-wanderlust",
            name = "Wanderlust Travel Montage",
            category = TemplateCategory.TRAVEL,
            description = "Teal & Orange grade, whip-blur transitions, speed ramps & location stamp.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFF00BCD4,
            bpm = 118,
            slots = listOf(
                TemplateSlotSpec(1600L, speedCurve = SpeedCurvePreset.FLASH_OUT, effect = EffectType.SHARPEN),
                TemplateSlotSpec(1500L, speedCurve = SpeedCurvePreset.FLASH_IN, transitionIn = TransitionType.BLUR),
                TemplateSlotSpec(1500L, effect = EffectType.CINEMATIC, transitionIn = TransitionType.SWIPE),
                TemplateSlotSpec(2200L, speedCurve = SpeedCurvePreset.HERO, transitionIn = TransitionType.ZOOM)
            ),
            colorPresetId = "preset-teal-orange",
            defaultHeadlineText = "UDAIPUR • INDIA",
            defaultSubtitleText = "26.9° N, 70.9° E ✈️"
        ),
        ProjectTemplate(
            id = "tpl-gaming-clutch",
            name = "FPS Clutch Montage",
            category = TemplateCategory.GAMING,
            description = "Camera shake on impact, chromatic aberration, bullet slow-mo & neon HUD.",
            aspectRatio = AspectRatioPreset.YOUTUBE_16_9,
            accentColorHex = 0xFF00E676,
            bpm = 145,
            slots = listOf(
                TemplateSlotSpec(1400L, speedCurve = SpeedCurvePreset.BULLET, effect = EffectType.SHARPEN, effectIntensity = 0.85f),
                TemplateSlotSpec(1100L, effect = EffectType.SHAKE, transitionIn = TransitionType.GLITCH),
                TemplateSlotSpec(1600L, effect = EffectType.NEON_GLOW, transitionIn = TransitionType.ZOOM)
            ),
            colorPresetId = "preset-hdr-pop",
            defaultHeadlineText = "1v4 CLUTCH ACE 🎮",
            defaultSubtitleText = "60 FPS ULTRA MONTAGE",
            suggestedSfxId = "sfx_bass_drop"
        ),
        ProjectTemplate(
            id = "tpl-trending-vhs-glitch",
            name = "90s Retro VHS Flashback",
            category = TemplateCategory.TRENDING,
            description = "Authentic VHS tracking lines, RGB split, tape rewind SFX & vintage grade.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFF7C4DFF,
            bpm = 124,
            slots = listOf(
                TemplateSlotSpec(1600L, effect = EffectType.VHS, effectIntensity = 0.7f),
                TemplateSlotSpec(1400L, effect = EffectType.GLITCH, transitionIn = TransitionType.GLITCH),
                TemplateSlotSpec(1800L, effect = EffectType.OLD_FILM, transitionIn = TransitionType.FLASH)
            ),
            colorPresetId = "preset-vintage-90s",
            defaultHeadlineText = "REWIND THE TAPE 📼",
            defaultSubtitleText = "REC ● 1998",
            suggestedSfxId = "sfx_rewind"
        ),
        ProjectTemplate(
            id = "tpl-business-promo",
            name = "Clean Brand Product Launch",
            category = TemplateCategory.BUSINESS,
            description = "Studio sharp clarity, smooth push transitions & corporate lower-third.",
            aspectRatio = AspectRatioPreset.PORTRAIT_4_5,
            accentColorHex = 0xFF2979FF,
            bpm = 112,
            slots = listOf(
                TemplateSlotSpec(2200L, effect = EffectType.SHARPEN, effectIntensity = 0.55f),
                TemplateSlotSpec(2200L, transitionIn = TransitionType.PUSH),
                TemplateSlotSpec(2400L, transitionIn = TransitionType.WIPE)
            ),
            colorPresetId = "preset-crisp-sharp",
            defaultHeadlineText = "NEXT-GEN CRAFTSMANSHIP",
            defaultSubtitleText = "Available Worldwide • Shop Now",
            textAnimation = TextAnimationType.FADE_UP
        ),
        ProjectTemplate(
            id = "tpl-motivation-grit",
            name = "Dark Discipline & Grit",
            category = TemplateCategory.MOTIVATION,
            description = "Bleach-bypass high contrast, vignette focus, deep boom & bold quote.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFFB0BEC5,
            bpm = 96,
            slots = listOf(
                TemplateSlotSpec(2400L, effect = EffectType.VIGNETTE, effectIntensity = 0.65f),
                TemplateSlotSpec(2200L, effect = EffectType.SHARPEN, effectIntensity = 0.7f, transitionIn = TransitionType.FADE),
                TemplateSlotSpec(2600L, effect = EffectType.FILM_GRAIN, transitionIn = TransitionType.FLASH)
            ),
            colorPresetId = "preset-bleach-bypass",
            defaultHeadlineText = "OUTWORK YESTERDAY.",
            defaultSubtitleText = "Focus • Discipline • Execution",
            suggestedSfxId = "sfx_boom"
        ),
        ProjectTemplate(
            id = "tpl-slowmo-velvet",
            name = "Velvet 0.25x Speed Ramp",
            category = TemplateCategory.SLOW_MOTION,
            description = "Hero-time variable speed curves, optical vignette & silky dissolves.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFF18FFFF,
            bpm = 88,
            slots = listOf(
                TemplateSlotSpec(2800L, speedCurve = SpeedCurvePreset.HERO, effect = EffectType.SHARPEN),
                TemplateSlotSpec(2800L, speedCurve = SpeedCurvePreset.BULLET, effect = EffectType.GLOW, transitionIn = TransitionType.DISSOLVE)
            ),
            colorPresetId = "preset-nordic-cold",
            defaultHeadlineText = "FROZEN IN MOTION",
            defaultSubtitleText = "Hero Speed Curve • 60fps"
        ),
        ProjectTemplate(
            id = "tpl-status-hindi-glow",
            name = "Desi Attitude / Shayari Status",
            category = TemplateCategory.STATUS_VIDEOS,
            description = "Neon glow aura, sharp portrait detail & Hindi/English animated lyrics.",
            aspectRatio = AspectRatioPreset.REELS_9_16,
            accentColorHex = 0xFFFF9100,
            bpm = 110,
            slots = listOf(
                TemplateSlotSpec(2200L, effect = EffectType.SHARPEN, effectIntensity = 0.7f),
                TemplateSlotSpec(2200L, effect = EffectType.NEON_GLOW, effectIntensity = 0.55f, transitionIn = TransitionType.ZOOM),
                TemplateSlotSpec(2400L, effect = EffectType.LIGHT_LEAKS, transitionIn = TransitionType.FLASH)
            ),
            colorPresetId = "preset-teal-orange",
            defaultHeadlineText = "वक़्त आने दे बता देंगे ✨",
            defaultSubtitleText = "VibeCut Pro Status",
            textAnimation = TextAnimationType.KARAOKE_GLOW
        )
    )

    /**
     * Instantiates a complete multi-track [Project] from a [ProjectTemplate] and selected media sources.
     */
    fun applyTemplate(
        template: ProjectTemplate,
        sources: List<ImportedMediaSource>,
        customHeadline: String = template.defaultHeadlineText,
        customSubtitle: String = template.defaultSubtitleText,
        musicSource: ImportedMediaSource? = null
    ): Project {
        val colorConfig = ColorGradeConfig(
            lutPresetId = ColorGradingEngine.builtInPresets
                .firstOrNull { it.id == template.colorPresetId }?.id,
            lutIntensity = 0.90f
        )

        var project = Project(
            title = template.name,
            aspectRatio = template.aspectRatio
        )

        val mainTrack = project.tracks.first { it.type == TrackType.VIDEO_MAIN }
        if (sources.isNotEmpty()) {
            var cursorMs = 0L
            val builtClips = template.slots.mapIndexed { idx, slot ->
                val src = sources[idx % sources.size]
                val trimEnd = min(src.durationMs, (slot.slotDurationMs * slot.speed).toLong().coerceAtLeast(300L))
                val fxList = if (slot.effect != null) {
                    listOf(AppliedEffect(type = slot.effect, intensity = slot.effectIntensity))
                } else emptyList()

                val clip = TimelineClip(
                    trackId = mainTrack.id,
                    sourceUri = src.uri,
                    sourceName = src.displayName,
                    mediaKind = src.mediaKind,
                    sourceDurationMs = if (src.mediaKind == MediaKind.IMAGE) maxOf(30_000L, src.durationMs) else src.durationMs,
                    startTimeOnTrackMs = cursorMs,
                    trimStartMs = 0L,
                    trimEndMs = trimEnd,
                    speed = slot.speed,
                    speedCurvePreset = slot.speedCurve,
                    effects = fxList,
                    colorGrade = colorConfig,
                    transitionIn = slot.transitionIn?.let { ClipTransition(it, 420L) },
                    waveformPeaks = src.waveformPeaks,
                    hasAudioTrack = src.hasAudioTrack && src.mediaKind != MediaKind.IMAGE,
                    sourceWidth = src.width,
                    sourceHeight = src.height,
                    rotationDegreesMeta = src.rotationDegreesMeta,
                    mimeType = src.mimeType,
                    thumbnailUri = src.thumbnailUri
                )
                cursorMs += clip.timelineDurationMs
                clip
            }
            project = TimelineEngine.updateTrack(project, mainTrack.id) {
                it.copy(clips = TimelineEngine.reflowMagneticClips(builtClips))
            }
        }

        // Headline text layer
        if (customHeadline.isNotBlank()) {
            project = TimelineEngine.addTextClip(
                project = project,
                textSpec = TextStyleSpec(
                    text = customHeadline,
                    fontSizeSp = 32f,
                    textColorArgb = 0xFFFFFFFF,
                    gradientEndColorArgb = template.accentColorHex,
                    useGradient = true,
                    outlineWidth = 3f,
                    animationIn = template.textAnimation
                ),
                startMs = 150L,
                durationMs = min(2_800L, template.totalDurationMs)
            )
        }

        // Subtitle text layer
        if (customSubtitle.isNotBlank()) {
            val secondStart = min(1_200L, template.totalDurationMs / 2)
            project = TimelineEngine.updateTrack(
                TimelineEngine.addTextClip(
                    project = project,
                    textSpec = TextStyleSpec(
                        text = customSubtitle,
                        fontSizeSp = 20f,
                        textColorArgb = 0xFFFFF8E1,
                        backgroundColorArgb = 0x99000000,
                        animationIn = TextAnimationType.FADE_UP
                    ),
                    startMs = secondStart,
                    durationMs = (template.totalDurationMs - secondStart).coerceAtLeast(1_200L)
                ),
                "track-text"
            ) { t ->
                t.copy(
                    clips = t.clips.mapIndexed { idx, c ->
                        if (idx == 1) c.copy(transform = c.transform.copy(positionY = -0.58f)) else c
                    }
                )
            }
        }

        // Optional background music
        if (musicSource != null) {
            project = TimelineEngine.importMediaClips(
                project = project,
                items = listOf(musicSource.copy(durationMs = min(musicSource.durationMs, project.totalDurationMs.coerceAtLeast(3_000L)))),
                targetTrackType = TrackType.AUDIO_MUSIC,
                insertAtPlayheadMs = 0L
            )
        }
        return project
    }

    /**
     * Converts any edited [Project] into a reusable custom [ProjectTemplate].
     */
    fun extractTemplateFromProject(
        project: Project,
        templateName: String,
        category: TemplateCategory = TemplateCategory.TRENDING
    ): ProjectTemplate {
        val mainClips = project.tracks.firstOrNull { it.type == TrackType.VIDEO_MAIN }?.clips.orEmpty()
        val slots = if (mainClips.isNotEmpty()) {
            mainClips.map { clip ->
                TemplateSlotSpec(
                    slotDurationMs = clip.timelineDurationMs,
                    speed = clip.speed,
                    speedCurve = clip.speedCurvePreset,
                    effect = clip.effects.firstOrNull()?.type,
                    effectIntensity = clip.effects.firstOrNull()?.intensity ?: 0.6f,
                    transitionIn = clip.transitionIn?.type
                )
            }
        } else {
            listOf(TemplateSlotSpec(2_500L, effect = EffectType.SHARPEN))
        }
        val firstText = project.tracks.firstOrNull { it.type == TrackType.TEXT }
            ?.clips?.firstOrNull()?.textSpec?.text ?: project.title

        return ProjectTemplate(
            name = templateName,
            category = category,
            description = "Saved user template (${slots.size} slots • ${project.aspectRatio.label})",
            aspectRatio = project.aspectRatio,
            accentColorHex = 0xFF00E5FF,
            bpm = 120,
            slots = slots,
            colorPresetId = mainClips.firstOrNull()?.colorGrade?.lutPresetId ?: "preset-teal-orange",
            defaultHeadlineText = firstText,
            defaultSubtitleText = "",
            isUserSaved = true
        )
    }
}
