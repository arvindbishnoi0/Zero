package com.vibecut.pro.ui.editor.panels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun pct(v: Float): String = "${(v * 100).roundToInt()}"
private fun signed(v: Float): String {
    val n = (v * 100).roundToInt()
    return if (n > 0) "+$n" else "$n"
}
private fun dec(v: Float): String = String.format(Locale.US, "%.2f", v)

// =============================================================== TRANSFORM / CROP

@Composable
fun TransformSheetContent(clip: TimelineClip, viewModel: EditorViewModel) {
    val tf = clip.transform
    var subTab by remember { mutableIntStateOf(0) }

    StudioChipRow {
        StudioChip("Transform", subTab == 0) { subTab = 0 }
        StudioChip("Crop", subTab == 1) { subTab = 1 }
        StudioChip("Blend", subTab == 2) { subTab = 2 }
    }

    if (subTab == 0) {
        StudioCard(
            title = "Geometry",
            onReset = if (!tf.isIdentity) {
                { viewModel.updateSelectedTransform(ClipTransform()) }
            } else null
        ) {
            StudioSlider(
                label = "Scale",
                value = abs(kotlin.math.max(tf.scaleX, tf.scaleY)),
                valueRange = 0.2f..4f,
                defaultValue = 1f,
                valueText = "${pct(abs(tf.scaleX))}%"
            ) { s ->
                val sx = if (tf.scaleX < 0) -s else s
                val sy = if (tf.scaleY < 0) -s else s
                viewModel.updateSelectedTransform(tf.copy(scaleX = sx, scaleY = sy))
            }
            StudioSlider(
                label = "Rotation",
                value = tf.rotationDegrees,
                valueRange = -180f..180f,
                defaultValue = 0f,
                valueText = "${tf.rotationDegrees.roundToInt()}°"
            ) { deg -> viewModel.updateSelectedTransform(tf.copy(rotationDegrees = deg)) }
            StudioSlider(
                label = "Position X",
                value = tf.positionX,
                valueRange = -1f..1f,
                defaultValue = 0f,
                valueText = signed(tf.positionX)
            ) { v -> viewModel.updateSelectedTransform(tf.copy(positionX = v)) }
            StudioSlider(
                label = "Position Y",
                value = tf.positionY,
                valueRange = -1f..1f,
                defaultValue = 0f,
                valueText = signed(tf.positionY)
            ) { v -> viewModel.updateSelectedTransform(tf.copy(positionY = v)) }
            StudioSlider(
                label = "Opacity",
                value = tf.opacity,
                valueRange = 0f..1f,
                defaultValue = 1f,
                valueText = "${pct(tf.opacity)}%"
            ) { v -> viewModel.updateSelectedTransform(tf.copy(opacity = v)) }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioChip(if (tf.flipHorizontal) "Flip H ✓" else "Flip H", tf.flipHorizontal) {
                    viewModel.flipSelectedHorizontal()
                }
                StudioChip(if (tf.flipVertical) "Flip V ✓" else "Flip V", tf.flipVertical) {
                    viewModel.flipSelectedVertical()
                }
                StudioChip("Rotate 90°") { viewModel.rotateSelected90(true) }
                StudioChip("Rotate -90°") { viewModel.rotateSelected90(false) }
            }
        }
    } else if (subTab == 1) {
        val crop = clip.crop
        StudioCard(
            title = "Crop",
            onReset = if (!crop.isIdentity) {
                { viewModel.setCropOnSelected(CropRect()) }
            } else null
        ) {
            StudioChipRow {
                StudioChip("Full", crop.isIdentity) { viewModel.setCropOnSelected(CropRect()) }
                StudioChip("Square", abs(crop.left - 0.15f) < 0.01f) {
                    viewModel.setCropOnSelected(CropRect(0.15f, 0.15f, 0.85f, 0.85f))
                }
                StudioChip("Cinema", abs(crop.top - 0.12f) < 0.01f) {
                    viewModel.setCropOnSelected(CropRect(0f, 0.12f, 1f, 0.88f))
                }
                StudioChip("Tight", abs(crop.left - 0.05f) < 0.01f && abs(crop.top - 0.05f) < 0.01f) {
                    viewModel.setCropOnSelected(CropRect(0.05f, 0.05f, 0.95f, 0.95f))
                }
            }
            StudioSlider("Left", crop.left, 0f..0.45f, 0f, "${pct(crop.left)}%") {
                viewModel.setCropOnSelected(crop.copy(left = it.coerceAtMost(crop.right - 0.1f)))
            }
            StudioSlider("Right", crop.right, 0.55f..1f, 1f, "${pct(crop.right)}%") {
                viewModel.setCropOnSelected(crop.copy(right = it.coerceAtLeast(crop.left + 0.1f)))
            }
            StudioSlider("Top", crop.top, 0f..0.45f, 0f, "${pct(crop.top)}%") {
                viewModel.setCropOnSelected(crop.copy(top = it.coerceAtMost(crop.bottom - 0.1f)))
            }
            StudioSlider("Bottom", crop.bottom, 0.55f..1f, 1f, "${pct(crop.bottom)}%") {
                viewModel.setCropOnSelected(crop.copy(bottom = it.coerceAtLeast(crop.top + 0.1f)))
            }
        }
    } else {
        StudioCard(title = "Blend Mode") {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BlendMode.entries.forEach { mode ->
                    StudioChip(mode.label, tf.blendMode == mode) {
                        viewModel.updateSelectedTransform(tf.copy(blendMode = mode))
                    }
                }
            }
            Text(
                "Blend modes composite overlay/PIP clips against the tracks below them, in both preview and export.",
                fontSize = 10.sp,
                color = TextTertiary
            )
        }
    }
}

// =============================================================== SPEED & TIME

@Composable
fun SpeedSheetContent(clip: TimelineClip, project: Project, viewModel: EditorViewModel) {
    StudioCard(
        title = "Playback Speed",
        badge = String.format(Locale.US, "%.2fx", clip.clampedSpeed),
        onReset = if (abs(clip.speed - 1f) > 0.01f || clip.speedCurvePreset != SpeedCurvePreset.NONE) {
            { viewModel.updateSelectedSpeed(1f, SpeedCurvePreset.NONE) }
        } else null
    ) {
        StudioSlider(
            label = "Speed",
            value = clip.speed,
            valueRange = 0.1f..5f,
            defaultValue = 1f,
            valueText = String.format(Locale.US, "%.2fx", clip.speed)
        ) { viewModel.updateSelectedSpeed(it, SpeedCurvePreset.NONE) }

        StudioChipRow {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f).forEach { s ->
                StudioChip("${s}x", !clip.hasSpeedRamp && abs(clip.speed - s) < 0.02f) {
                    viewModel.updateSelectedSpeed(s, SpeedCurvePreset.NONE)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip(if (clip.isReversed) "Reverse ✓" else "Reverse", clip.isReversed) {
                viewModel.toggleReverseSelected()
            }
            StudioChip("Freeze 2s") { viewModel.insertFreezeFrame(2_000L) }
        }
    }

    StudioCard(title = "Speed Ramp", badge = "Velocity") {
        StudioChipRow {
            SpeedCurvePreset.entries.forEach { preset ->
                StudioChip(preset.label, clip.speedCurvePreset == preset) {
                    viewModel.updateSelectedSpeed(
                        if (preset == SpeedCurvePreset.NONE) 1f else clip.speed,
                        preset,
                        preset.defaultPoints
                    )
                }
            }
        }
        Text(
            "Ramps retime the clip on the timeline. Export renders the ramp as constant-speed slices, so the exported timing matches what you see in the preview.",
            fontSize = 10.sp,
            color = TextTertiary
        )
    }

    StudioCard(title = "Trim") {
        val max = clip.sourceDurationMs.coerceAtLeast(300L).toFloat()
        RangeSlider(
            value = clip.trimStartMs.coerceIn(0L, (max - 100).toLong()).toFloat()..
                clip.effectiveTrimEndMs.coerceIn(clip.trimStartMs + 100L, max.toLong()).toFloat(),
            onValueChange = { r -> viewModel.trimSelectedClip(r.start.toLong(), r.endInclusive.toLong()) },
            valueRange = 0f..max,
            colors = SliderDefaults.colors(
                thumbColor = ZeroAccent,
                activeTrackColor = ZeroAccent,
                inactiveTrackColor = ZeroStroke
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("In ${"%.2f".format(clip.trimStartMs / 1000f)}s", fontSize = 10.sp, color = TextSecondary)
            Text("Out ${"%.2f".format(clip.effectiveTrimEndMs / 1000f)}s", fontSize = 10.sp, color = TextSecondary)
            Text("Length ${"%.2f".format(clip.timelineDurationMs / 1000f)}s", fontSize = 10.sp, color = ZeroAccent)
        }
    }
}

// =============================================================== EFFECTS

@Composable
fun EffectsSheetContent(clip: TimelineClip, viewModel: EditorViewModel) {
    var selectedCategory by remember { mutableStateOf<EffectCategory?>(null) }

    StudioCard(title = "Effect Stack", badge = "${clip.effects.count { it.enabled }}/${clip.effects.size}") {
        if (clip.effects.isEmpty()) {
            Text(
                "No effects yet. Pick one below — it is applied to the live GPU pipeline immediately.",
                fontSize = 11.sp,
                color = TextTertiary
            )
        } else {
            clip.effects.forEachIndexed { index, fx ->
                EffectStackRow(
                    effect = fx,
                    index = index,
                    lastIndex = clip.effects.lastIndex,
                    onToggle = { enabled ->
                        viewModel.updateEffectOnSelected(fx.copy(enabled = enabled))
                    },
                    onMoveUp = { viewModel.reorderEffectOnSelected(fx.id, -1) },
                    onMoveDown = { viewModel.reorderEffectOnSelected(fx.id, 1) },
                    onRemove = { viewModel.removeEffectFromSelected(fx.id) },
                    onSelect = { selectedCategory = null }
                )
                if (fx.enabled) {
                    StudioSlider(
                        label = fx.type.primaryLabel,
                        value = fx.intensity,
                        valueRange = 0f..1f,
                        defaultValue = fx.type.defaultIntensity,
                        valueText = pct(fx.intensity)
                    ) { v -> viewModel.updateEffectOnSelected(fx.copy(intensity = v)) }
                    StudioSlider(
                        label = fx.type.secondaryLabel,
                        value = fx.secondaryParam,
                        valueRange = 0f..1f,
                        defaultValue = fx.type.defaultSecondary,
                        valueText = pct(fx.secondaryParam)
                    ) { v -> viewModel.updateEffectOnSelected(fx.copy(secondaryParam = v)) }
                    StudioSlider(
                        label = fx.type.tertiaryLabel,
                        value = fx.tertiaryParam,
                        valueRange = 0f..1f,
                        defaultValue = fx.type.defaultTertiary,
                        valueText = pct(fx.tertiaryParam)
                    ) { v -> viewModel.updateEffectOnSelected(fx.copy(tertiaryParam = v)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioChip("Apply to all clips") { viewModel.applyEffectsToAllClips() }
                StudioChip("Clear all") { viewModel.clearEffectsOnSelected() }
            }
        }
    }

    StudioCard(title = "Add Effect", badge = "${EffectType.entries.size} GPU effects") {
        StudioChipRow {
            StudioChip("All", selectedCategory == null) { selectedCategory = null }
            EffectCategory.entries.forEach { cat ->
                StudioChip(cat.label, selectedCategory == cat) { selectedCategory = cat }
            }
        }
        val visible = EffectType.entries.filter { selectedCategory == null || it.category == selectedCategory }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visible.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { type ->
                        val applied = clip.effects.any { it.type == type }
                        EffectTile(
                            type = type,
                            applied = applied,
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.addEffectToSelected(type) }
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EffectStackRow(
    effect: AppliedEffect,
    index: Int,
    lastIndex: Int,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ZeroSurfaceHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (effect.enabled) ZeroAccent else TextTertiary)
        )
        Text(
            "${index + 1}. ${effect.type.label}",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (effect.enabled) TextPrimary else TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = "Move up",
            tint = if (index > 0) TextSecondary else TextTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp).clickable(enabled = index > 0, onClick = onMoveUp)
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Move down",
            tint = if (index < lastIndex) TextSecondary else TextTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp).clickable(enabled = index < lastIndex, onClick = onMoveDown)
        )
        Icon(
            if (effect.enabled) Icons.Default.Visibility else Icons.Default.VisibilityOff,
            contentDescription = "Toggle effect",
            tint = if (effect.enabled) ZeroAccent else TextTertiary,
            modifier = Modifier.size(17.dp).clickable { onToggle(!effect.enabled) }
        )
        Icon(
            Icons.Default.Close,
            contentDescription = "Remove effect",
            tint = ZeroCoral,
            modifier = Modifier.size(17.dp).clickable(onClick = onRemove)
        )
    }
}

@Composable
private fun EffectTile(
    type: EffectType,
    applied: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = Color(type.badgeColorHex)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (applied) accent.copy(alpha = 0.18f) else ZeroSurfaceHigh)
            .border(1.dp, if (applied) accent else ZeroStroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (applied) accent else accent.copy(alpha = 0.22f))
        )
        Text(
            type.label,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (applied) accent else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (applied) {
            Text("ON", fontSize = 7.5.sp, fontWeight = FontWeight.Bold, color = accent)
        }
    }
}

// =============================================================== FILTERS

@Composable
fun FiltersSheetContent(clip: TimelineClip, project: Project, viewModel: EditorViewModel) {
    val grade = clip.colorGrade
    var category by remember { mutableStateOf("All") }
    var showSaveDialog by remember { mutableStateOf(false) }
    val cubePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCubeLutUriOnSelected(uri)
    }

    val allPresets = remember(project.customColorPresets) {
        project.customColorPresets + ColorGradingEngine.builtInPresets
    }
    val activePresetId = grade.lutPresetId
    val activePreset = allPresets.firstOrNull { it.id == activePresetId }

    StudioCard(title = "Look", badge = activePreset?.name ?: "None") {
        StudioSlider(
            label = "Filter Strength",
            value = grade.lutIntensity,
            valueRange = 0f..1f,
            defaultValue = 1f,
            valueText = "${pct(grade.lutIntensity)}%"
        ) { viewModel.updateFilterIntensityOnSelected(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            StudioChip("None", activePresetId == null) { viewModel.applyColorPresetOnSelected(null) }
            StudioChip("Save as preset") { showSaveDialog = true }
            StudioChip("Import .cube") { cubePicker.launch(arrayOf("*/*")) }
        }
    }

    StudioChipRow {
        StudioChip("All", category == "All") { category = "All" }
        allPresets.map { it.category }.distinct().forEach { cat ->
            StudioChip(cat, category == cat) { category = cat }
        }
    }

    val filtered = if (category == "All") allPresets else allPresets.filter { it.category == category }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filtered.forEach { preset ->
            PresetSwatch(
                preset = preset,
                selected = preset.id == activePresetId,
                onClick = { viewModel.applyColorPresetOnSelected(preset) }
            )
        }
    }

    if (showSaveDialog) {
        var name by remember { mutableStateOf("My Look") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save custom preset") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveCustomColorPreset(name)
                    showSaveDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PresetSwatch(preset: ColorGradePreset, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(preset.accentHex), Color(preset.secondaryHex))
                    )
                )
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) ZeroAccent else ZeroStroke,
                    shape = RoundedCornerShape(10.dp)
                )
        )
        Text(
            preset.name,
            fontSize = 9.sp,
            color = if (selected) ZeroAccent else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// =============================================================== ADJUST (color)

@Composable
fun AdjustSheetContent(clip: TimelineClip, viewModel: EditorViewModel) {
    val g = clip.colorGrade
    var tab by remember { mutableIntStateOf(0) }

    StudioChipRow {
        StudioChip("Light", tab == 0) { tab = 0 }
        StudioChip("Color", tab == 1) { tab = 1 }
        StudioChip("HSL", tab == 2) { tab = 2 }
        StudioChip("Curves", tab == 3) { tab = 3 }
        StudioChip("Wheels", tab = 4) { tab = 4 }
    }

    when (tab) {
        0 -> StudioCard(
            title = "Light & Tone",
            onReset = {
                viewModel.updateColorGradeOnSelected(g.resetLight(), "reset_light")
            }
        ) {
            StudioSlider("Exposure", g.exposure, -2f..2f, 0f, String.format(Locale.US, "%+.2f EV", g.exposure)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(exposure = v), "exposure")
            }
            StudioSlider("Brightness", g.brightness, -1f..1f, 0f, signed(g.brightness)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(brightness = v), "brightness")
            }
            StudioSlider("Contrast", g.contrast, -1f..1f, 0f, signed(g.contrast)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(contrast = v), "contrast")
            }
            StudioSlider("Highlights", g.highlights, -1f..1f, 0f, signed(g.highlights)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(highlights = v), "highlights")
            }
            StudioSlider("Shadows", g.shadows, -1f..1f, 0f, signed(g.shadows)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(shadows = v), "shadows")
            }
            StudioSlider("Whites", g.whites, -1f..1f, 0f, signed(g.whites)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(whites = v), "whites")
            }
            StudioSlider("Blacks", g.blacks, -1f..1f, 0f, signed(g.blacks)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(blacks = v), "blacks")
            }
            StudioSlider("Gamma", g.gamma, -1f..1f, 0f, signed(g.gamma)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(gamma = v), "gamma")
            }
            StudioSlider("Fade", g.fade, 0f..1f, 0f, pct(g.fade)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(fade = v), "fade")
            }
        }

        1 -> StudioCard(
            title = "Color & White Balance",
            onReset = { viewModel.updateColorGradeOnSelected(g.resetColor(), "reset_color") }
        ) {
            StudioSlider("Saturation", g.saturation, -1f..1f, 0f, signed(g.saturation)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(saturation = v), "saturation")
            }
            StudioSlider("Vibrance", g.vibrance, -1f..1f, 0f, signed(g.vibrance)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(vibrance = v), "vibrance")
            }
            StudioSlider("Temperature", g.temperature, -1f..1f, 0f, signed(g.temperature)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(temperature = v), "temperature")
            }
            StudioSlider("Tint", g.tint, -1f..1f, 0f, signed(g.tint)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(tint = v), "tint")
            }
            StudioSlider("Hue Shift", g.hueShift, -1f..1f, 0f, signed(g.hueShift)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(hueShift = v), "hue")
            }
            StudioSlider("Dehaze", g.dehaze, -0.5f..1f, 0f, signed(g.dehaze)) { v ->
                viewModel.updateColorGradeOnSelected(g.copy(dehaze = v), "dehaze")
            }
        }

        2 -> StudioCard(
            title = "HSL Bands",
            onReset = { viewModel.resetColorSectionOnSelected("hsl") }
        ) {
            HSL_BANDS_FOR_UI.forEach { band ->
                val adj = g.hslBands.firstOrNull { it.band == band } ?: HslBandAdjustment(band)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(band.colorHex))
                    )
                    Text(band.label, fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
                StudioSlider(
                    label = "Hue",
                    value = adj.hueShift,
                    valueRange = -1f..1f,
                    defaultValue = 0f,
                    valueText = signed(adj.hueShift)
                ) { v ->
                    viewModel.updateColorGradeOnSelected(
                        g.copy(hslBands = g.hslBands.map { if (it.band == band) it.copy(hueShift = v) else it }),
                        "hsl_hue_${band.name}"
                    )
                }
                StudioSlider(
                    label = "Saturation",
                    value = adj.saturationShift,
                    valueRange = -1f..1f,
                    defaultValue = 0f,
                    valueText = signed(adj.saturationShift)
                ) { v ->
                    viewModel.updateColorGradeOnSelected(
                        g.copy(hslBands = g.hslBands.map { if (it.band == band) it.copy(saturationShift = v) else it }),
                        "hsl_sat_${band.name}"
                    )
                }
                StudioSlider(
                    label = "Luminance",
                    value = adj.luminanceShift,
                    valueRange = -1f..1f,
                    defaultValue = 0f,
                    valueText = signed(adj.luminanceShift)
                ) { v ->
                    viewModel.updateColorGradeOnSelected(
                        g.copy(hslBands = g.hslBands.map { if (it.band == band) it.copy(luminanceShift = v) else it }),
                        "hsl_lum_${band.name}"
                    )
                }
            }
        }

        3 -> StudioCard(title = "RGB Curves", onReset = { viewModel.resetColorSectionOnSelected("curves") }) {
            Text(
                "Curve editing uses the channel presets below; each preset is a real tone curve evaluated in the 3D LUT.",
                fontSize = 10.sp,
                color = TextTertiary
            )
            listOf("master", "red", "green", "blue").forEach { channel ->
                Text(
                    channel.replaceFirstChar { it.uppercase() },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                StudioChipRow {
                    CurvePresetOption.entries.forEach { option ->
                        val current = when (channel) {
                            "master" -> g.curves.master
                            "red" -> g.curves.red
                            "green" -> g.curves.green
                            else -> g.curves.blue
                        }
                        StudioChip(option.label, current == option.points) {
                            val newCurves = when (channel) {
                                "master" -> g.curves.copy(master = option.points)
                                "red" -> g.curves.copy(red = option.points)
                                "green" -> g.curves.copy(green = option.points)
                                else -> g.curves.copy(blue = option.points)
                            }
                            viewModel.updateColorGradeOnSelected(g.copy(curves = newCurves), "curve_$channel")
                        }
                    }
                }
            }
        }

        else -> StudioCard(
            title = "Color Wheels",
            onReset = { viewModel.resetColorSectionOnSelected("wheels") }
        ) {
            WheelSection("Shadows", g.colorWheels.shadows) { tint ->
                viewModel.updateColorGradeOnSelected(
                    g.copy(colorWheels = g.colorWheels.copy(shadows = tint)),
                    "wheel_shadows"
                )
            }
            WheelSection("Midtones", g.colorWheels.midtones) { tint ->
                viewModel.updateColorGradeOnSelected(
                    g.copy(colorWheels = g.colorWheels.copy(midtones = tint)),
                    "wheel_mid"
                )
            }
            WheelSection("Highlights", g.colorWheels.highlights) { tint ->
                viewModel.updateColorGradeOnSelected(
                    g.copy(colorWheels = g.colorWheels.copy(highlights = tint)),
                    "wheel_high"
                )
            }
        }
    }
}

private enum class CurvePresetOption(val label: String, val points: List<CurvePoint>) {
    LINEAR("Linear", RgbCurvesConfig.defaultCurve()),
    CONTRAST("S-Curve", RgbCurvesConfig.sContrastCurve()),
    MATTE("Matte Fade", RgbCurvesConfig.matteFadeCurve())
}

private val HSL_BANDS_FOR_UI: List<HslBand> = HslBand.entries

@Composable
private fun WheelSection(title: String, tint: ColorWheelTint, onChange: (ColorWheelTint) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        StudioSlider("Red", tint.rOffset, -1f..1f, 0f, signed(tint.rOffset)) { onChange(tint.copy(rOffset = it)) }
        StudioSlider("Green", tint.gOffset, -1f..1f, 0f, signed(tint.gOffset)) { onChange(tint.copy(gOffset = it)) }
        StudioSlider("Blue", tint.bOffset, -1f..1f, 0f, signed(tint.bOffset)) { onChange(tint.copy(bOffset = it)) }
    }
}

// =============================================================== DETAIL / SHARPEN

@Composable
fun DetailSheetContent(clip: TimelineClip, viewModel: EditorViewModel) {
    val g = clip.colorGrade
    StudioChipRow {
        StudioChip("Crisp HD") { viewModel.applyDetailQuickPreset("crisp_hd") }
        StudioChip("Ultra 4K") { viewModel.applyDetailQuickPreset("ultra_4k") }
        StudioChip("Portrait Soft") { viewModel.applyDetailQuickPreset("portrait_soft") }
        StudioChip("Low Light Clean") { viewModel.applyDetailQuickPreset("low_light_clean") }
        StudioChip("Analog Grain") { viewModel.applyDetailQuickPreset("analog_grain") }
    }

    StudioCard(
        title = "Sharpening",
        onReset = { viewModel.updateColorGradeOnSelected(g.copy(sharpness = 0f, sharpenRadius = 0.35f, sharpenThreshold = 0.12f), "reset_sharp") }
    ) {
        StudioSlider("Sharpness", g.sharpness, 0f..1f, 0f, pct(g.sharpness)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(sharpness = v), "sharpness")
        }
        StudioSlider("Radius", g.sharpenRadius, 0f..1f, 0.35f, dec(g.sharpenRadius)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(sharpenRadius = v), "sharpen_radius")
        }
        StudioSlider("Edge threshold", g.sharpenThreshold, 0f..1f, 0.12f, dec(g.sharpenThreshold)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(sharpenThreshold = v), "sharpen_threshold")
        }
        StudioSlider("Clarity", g.clarity, -1f..1f, 0f, signed(g.clarity)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(clarity = v), "clarity")
        }
        StudioSlider("Structure", g.structure, 0f..1f, 0f, pct(g.structure)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(structure = v), "structure")
        }
        StudioSlider("Denoise", g.denoise, 0f..1f, 0f, pct(g.denoise)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(denoise = v), "denoise")
        }
    }

    StudioCard(
        title = "Optics",
        onReset = { viewModel.updateColorGradeOnSelected(g.copy(vignette = 0f, grain = 0f), "reset_optics") }
    ) {
        StudioSlider("Vignette", g.vignette, -1f..1f, 0f, signed(g.vignette)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(vignette = v), "vignette")
        }
        StudioSlider("Feather", g.vignetteFeather, 0f..1f, 0.5f, pct(g.vignetteFeather)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(vignetteFeather = v), "vignette_feather")
        }
        StudioSlider("Film grain", g.grain, 0f..1f, 0f, pct(g.grain)) { v ->
            viewModel.updateColorGradeOnSelected(g.copy(grain = v), "grain")
        }
    }
}

// =============================================================== TRANSITIONS

@Composable
fun TransitionsSheetContent(clip: TimelineClip, viewModel: EditorViewModel) {
    val current = clip.transitionIn
    StudioCard(
        title = "Incoming transition",
        badge = current?.type?.label ?: "None",
        onReset = { viewModel.setTransitionOnSelected(null) }
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StudioChip("None", current == null) { viewModel.setTransitionOnSelected(null) }
            TransitionType.entries.forEach { type ->
                StudioChip(type.label, current?.type == type) {
                    viewModel.setTransitionOnSelected(ClipTransition(type = type))
                }
            }
        }
        if (current != null) {
            StudioSlider(
                label = "Duration",
                value = current.durationMs.toFloat(),
                valueRange = 100f..2_000f,
                defaultValue = current.type.defaultDurationMs.toFloat(),
                valueText = "${(current.durationMs / 100f).roundToInt() / 10f}s"
            ) { v -> viewModel.setTransitionOnSelected(current.copy(durationMs = v.toLong())) }
            StudioSlider(
                label = "Direction",
                value = current.directionAngleDeg,
                valueRange = 0f..360f,
                defaultValue = 0f,
                valueText = "${current.directionAngleDeg.roundToInt()}°"
            ) { v -> viewModel.setTransitionOnSelected(current.copy(directionAngleDeg = v)) }
            StudioSlider(
                label = "Softness",
                value = current.softness,
                valueRange = 0f..1f,
                defaultValue = 0.35f,
                valueText = pct(current.softness)
            ) { v -> viewModel.setTransitionOnSelected(current.copy(softness = v)) }
            StudioSlider(
                label = "Chromatic",
                value = current.chromaticAmount,
                valueRange = 0f..1f,
                defaultValue = 0.4f,
                valueText = pct(current.chromaticAmount)
            ) { v -> viewModel.setTransitionOnSelected(current.copy(chromaticAmount = v)) }
            StudioChip("Apply to all cuts") {
                viewModel.applyTransitionToAllClips(current)
            }
        }
    }
}

// =============================================================== KEYFRAMES

@Composable
fun KeyframesSheetContent(clip: TimelineClip, playheadMs: Long, viewModel: EditorViewModel) {
    val localMs = (playheadMs - clip.startTimeOnTrackMs).coerceIn(0L, clip.timelineDurationMs)
    var property by remember { mutableStateOf(KeyframeProperty.SCALE) }
    val state = com.vibecut.pro.engine.keyframes.KeyframeInterpolator.evaluateClipState(clip, localMs)

    StudioCard(title = "Motion at playhead", badge = "${localMs}ms") {
        Text(
            "Add a keyframe here, move the playhead, then change the value — ZERO animates between the two with the easing you pick.",
            fontSize = 10.sp,
            color = TextTertiary
        )
        StudioChipRow {
            KeyframeProperty.entries.forEach { p ->
                StudioChip(p.label, property == p) { property = p }
            }
        }
        val (value, range) = when (property) {
            KeyframeProperty.SCALE -> state.scaleX to (0.1f..5f)
            KeyframeProperty.ROTATION -> state.rotationDegrees to (-360f..360f)
            KeyframeProperty.OPACITY -> state.opacity to (0f..1f)
            KeyframeProperty.VOLUME -> state.volume to (0f..2f)
            KeyframeProperty.BLUR -> state.extraBlur to (0f..1f)
            KeyframeProperty.EFFECT_INTENSITY -> state.effectIntensityMultiplier to (0f..1f)
            KeyframeProperty.COLOR_INTENSITY -> state.colorIntensityMix to (0f..1f)
            KeyframeProperty.POSITION_X -> state.positionX to (-1f..1f)
            KeyframeProperty.POSITION_Y -> state.positionY to (-1f..1f)
            KeyframeProperty.TEXT_PROGRESS -> state.textProgress to (0f..1f)
        }
        var draft by remember(property, clip.id) { mutableStateOf(value) }
        StudioSlider(
            label = property.label,
            value = draft,
            valueRange = range,
            defaultValue = property.defaultValue,
            valueText = String.format(Locale.US, "%.2f", draft)
        ) { draft = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip("Add keyframe") {
                viewModel.upsertKeyframeAtPlayhead(property, draft)
            }
            StudioChip("Clear all") { viewModel.clearKeyframes() }
        }
    }

    StudioCard(title = "Keyframes", badge = "${clip.keyframes.size}") {
        if (clip.keyframes.isEmpty()) {
            Text("No keyframes on this clip yet.", fontSize = 11.sp, color = TextTertiary)
        } else {
            clip.keyframes.sortedBy { it.timeOffsetMs }.forEach { kf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ZeroSurfaceHigh)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${kf.property.label} @ ${kf.timeOffsetMs}ms = ${String.format(Locale.US, "%.2f", kf.value)}",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete keyframe",
                        tint = ZeroCoral,
                        modifier = Modifier.size(16.dp).clickable { viewModel.removeKeyframe(kf.id) }
                    )
                }
            }
        }
    }
}

// =============================================================== AUDIO

@Composable
fun AudioSheetContent(clip: TimelineClip, viewModel: EditorViewModel) {
    val ui by viewModel.uiState.collectAsState()
    val sfxPresets = com.vibecut.pro.engine.audio.BuiltInSfxPreset.entries

    StudioCard(
        title = "Clip Audio",
        onReset = {
            viewModel.updateSelectedAudioDsp(
                volume = 1f, fadeInMs = 0L, fadeOutMs = 0L,
                noiseReduction = 0f, voiceEnhancement = 0f, audioDucking = false
            )
        }
    ) {
        StudioSlider("Volume", clip.volume, 0f..2f, 1f, "${pct(clip.volume)}%") { v ->
            viewModel.updateSelectedAudioDsp(volume = v)
        }
        StudioSlider("Fade in", clip.fadeInMs.toFloat(), 0f..3_000f, 0f, "${(clip.fadeInMs / 1000f).let { "%.1fs".format(it) }}") { v ->
            viewModel.updateSelectedAudioDsp(fadeInMs = v.toLong())
        }
        StudioSlider("Fade out", clip.fadeOutMs.toFloat(), 0f..3_000f, 0f, "${(clip.fadeOutMs / 1000f).let { "%.1fs".format(it) }}") { v ->
            viewModel.updateSelectedAudioDsp(fadeOutMs = v.toLong())
        }
        StudioSlider("Noise reduction", clip.noiseReduction, 0f..1f, 0f, pct(clip.noiseReduction)) { v ->
            viewModel.updateSelectedAudioDsp(noiseReduction = v)
        }
        StudioSlider("Voice clarity", clip.voiceEnhancement, 0f..1f, 0f, pct(clip.voiceEnhancement)) { v ->
            viewModel.updateSelectedAudioDsp(voiceEnhancement = v)
        }
        StudioToggleRow("Duck music under this clip", clip.audioDucking) { on ->
            viewModel.updateSelectedAudioDsp(audioDucking = on)
        }
        StudioToggleRow("Mute this clip", clip.isMuted) { on ->
            viewModel.updateSelectedAudioDsp(isMuted = on)
        }
    }

    StudioCard(title = "Audio tools") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            StudioChip("Extract audio") { viewModel.extractAudioFromSelectedClip() }
            StudioChip("Detect beats") { viewModel.detectBeatsAndAutoSync() }
            StudioChip(
                if (ui.isVoiceRecording) "Stop recording" else "Record voiceover"
            ) { viewModel.toggleVoiceoverRecording() }
        }
        if (ui.isVoiceRecording) {
            val amp = ui.voiceAmplitude
            LinearProgressIndicator(
                progress = { amp.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = ZeroCoral,
                trackColor = ZeroStroke
            )
        }
    }

    StudioCard(title = "Sound effects") {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            sfxPresets.forEach { preset ->
                StudioChip(preset.title) { viewModel.addSynthesizedSfx(preset) }
            }
        }
        Text(
            "SFX are generated locally as real PCM audio and mixed by the same DSP engine used on export.",
            fontSize = 10.sp,
            color = TextTertiary
        )
    }
}

// =============================================================== TEXT

@Composable
fun TextSheetContent(clip: TimelineClip, viewModel: EditorViewModel) {
    val spec = clip.textSpec ?: TextStyleSpec()
    val fontOptions = listOf("Sans Bold", "Sans", "Serif Bold", "Serif", "Mono", "Mono Bold")
    val colorOptions = listOf(
        0xFFFFFFFFuL.toLong(), 0xFF000000uL.toLong(), 0xFFFFC46BuL.toLong(), 0xFF6EF2B5uL.toLong(),
        0xFFFF7A7AuL.toLong(), 0xFF64B5F6uL.toLong(), 0xFF9B8CFFuL.toLong(), 0xFFFFE066uL.toLong()
    )

    StudioCard(title = "Text content") {
        OutlinedTextField(
            value = spec.text,
            onValueChange = { viewModel.updateSelectedTextSpec(spec.copy(text = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text") },
            maxLines = 4
        )
    }

    StudioCard(title = "Style") {
        StudioSlider("Font size", spec.fontSizeSp, 10f..90f, 28f, "${spec.fontSizeSp.roundToInt()}sp") { v ->
            viewModel.updateSelectedTextSpec(spec.copy(fontSizeSp = v))
        }
        StudioChipRow {
            fontOptions.forEach { font ->
                StudioChip(font, spec.fontFamilyName == font) {
                    viewModel.updateSelectedTextSpec(spec.copy(fontFamilyName = font))
                }
            }
        }
        Text("Color", fontSize = 11.sp, color = TextSecondary)
        StudioChipRow {
            colorOptions.forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(argb))
                        .border(
                            width = if (spec.textColorArgb == argb) 2.dp else 1.dp,
                            color = if (spec.textColorArgb == argb) ZeroAccent else ZeroStroke,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { viewModel.updateSelectedTextSpec(spec.copy(textColorArgb = argb)) }
                )
            }
        }
        StudioSlider("Outline", spec.outlineWidth, 0f..10f, 0f, "${spec.outlineWidth.roundToInt()}") { v ->
            viewModel.updateSelectedTextSpec(spec.copy(outlineWidth = v))
        }
        StudioSlider("Shadow", spec.shadowRadius, 0f..20f, 6f, "${spec.shadowRadius.roundToInt()}") { v ->
            viewModel.updateSelectedTextSpec(spec.copy(shadowRadius = v))
        }
        StudioSlider("Letter spacing", spec.letterSpacingEm, -0.05f..0.3f, 0.02f, dec(spec.letterSpacingEm)) { v ->
            viewModel.updateSelectedTextSpec(spec.copy(letterSpacingEm = v))
        }
        StudioChipRow {
            StudioChip(
                "Background on",
                (spec.backgroundColorArgb ushr 24) > 0
            ) {
                viewModel.updateSelectedTextSpec(
                    spec.copy(
                        backgroundColorArgb = if ((spec.backgroundColorArgb ushr 24) > 0) 0x00000000L
                        else 0x99000000L
                    )
                )
            }
            StudioChip("Gradient", spec.useGradient) {
                viewModel.updateSelectedTextSpec(spec.copy(useGradient = !spec.useGradient))
            }
        }
    }

    StudioCard(title = "Animation") {
        StudioChipRow {
            TextAnimationType.entries.forEach { anim ->
                StudioChip(anim.label, spec.animationIn == anim) {
                    viewModel.updateSelectedTextSpec(spec.copy(animationIn = anim))
                }
            }
        }
        StudioSlider(
            label = "Duration",
            value = spec.animationDurationMs.toFloat(),
            valueRange = 100f..2_000f,
            defaultValue = 450f,
            valueText = "${(spec.animationDurationMs / 1000f).let { "%.1fs".format(it) }}"
        ) { v -> viewModel.updateSelectedTextSpec(spec.copy(animationDurationMs = v.toLong())) }
    }

    StudioCard(title = "Position") {
        val tf = clip.transform
        StudioSlider("X", tf.positionX, -1f..1f, 0f, signed(tf.positionX)) { v ->
            viewModel.updateSelectedTransform(tf.copy(positionX = v))
        }
        StudioSlider("Y", tf.positionY, -1f..1f, 0f, signed(tf.positionY)) { v ->
            viewModel.updateSelectedTransform(tf.copy(positionY = v))
        }
        StudioSlider("Scale", tf.scaleX, 0.2f..3f, 1f, "${pct(tf.scaleX)}%") { v ->
            viewModel.updateSelectedTransform(tf.copy(scaleX = v, scaleY = v))
        }
        StudioSlider("Rotation", tf.rotationDegrees, -180f..180f, 0f, "${tf.rotationDegrees.roundToInt()}°") { v ->
            viewModel.updateSelectedTransform(tf.copy(rotationDegrees = v))
        }
    }
}

// =============================================================== ENHANCE (4K)

@Composable
fun EnhanceSheetContent(
    project: Project,
    viewModel: EditorViewModel
) {
    val cfg = project.enhancement
    val plan = remember(cfg, project.mainVideoClips) { viewModel.enhancementPlan(cfg.targetHeight) }

    StudioCard(title = "Enhancer") {
        StudioToggleRow("Enable 4K enhancement", cfg.enabled) { on ->
            viewModel.updateEnhancement(cfg.copy(enabled = on))
        }
        StudioChipRow {
            listOf(720, 1080, 1440, 2160).forEach { h ->
                StudioChip(
                    when (h) {
                        720 -> "720p"; 1080 -> "1080p"; 1440 -> "1440p"; else -> "2160p 4K"
                    },
                    cfg.targetHeight == h
                ) { viewModel.updateEnhancement(cfg.copy(enabled = true, targetHeight = h)) }
            }
        }
        Text(
            plan.upscaleLabel,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = ZeroAccent
        )
        Text(
            plan.detailHonestyLabel,
            fontSize = 10.sp,
            color = if (plan.needsUpscale) ZeroAmber else TextSecondary
        )
        Text(plan.costNote, fontSize = 10.sp, color = TextTertiary)
        Text(
            "Engine: ${plan.engine.label} — ${plan.engine.description}",
            fontSize = 10.sp,
            color = TextTertiary
        )
        Text(
            "No AI super-resolution model is bundled. This is real Lanczos-3 GPU resampling plus detail recovery — it cannot invent detail the source never captured.",
            fontSize = 10.sp,
            color = TextTertiary
        )
    }

    StudioCard(title = "Enhancement controls", onReset = { viewModel.updateEnhancement(EnhancementConfig()) }) {
        StudioSlider("Sharpen", cfg.sharpen, 0f..1f, 0.45f, pct(cfg.sharpen)) { v ->
            viewModel.updateEnhancement(cfg.copy(sharpen = v))
        }
        StudioSlider("Denoise", cfg.denoise, 0f..1f, 0.25f, pct(cfg.denoise)) { v ->
            viewModel.updateEnhancement(cfg.copy(denoise = v))
        }
        StudioSlider("Contrast", cfg.contrastBoost, -1f..1f, 0.12f, signed(cfg.contrastBoost)) { v ->
            viewModel.updateEnhancement(cfg.copy(contrastBoost = v))
        }
        StudioSlider("Color", cfg.colorBoost, -1f..1f, 0.1f, signed(cfg.colorBoost)) { v ->
            viewModel.updateEnhancement(cfg.copy(colorBoost = v))
        }
        StudioSlider("Brightness fix", cfg.brightnessFix, -1f..1f, 0f, signed(cfg.brightnessFix)) { v ->
            viewModel.updateEnhancement(cfg.copy(brightnessFix = v))
        }
        StudioSlider("Detail preserve", cfg.detailPreserve, 0f..1f, 0.55f, pct(cfg.detailPreserve)) { v ->
            viewModel.updateEnhancement(cfg.copy(detailPreserve = v))
        }
    }

    StudioCard(title = "Export resolution reminder") {
        Text(
            "The exporter renders at ${plan.outputWidth}x${plan.outputHeight} (${plan.outputHeight}p). Choosing 4K output does not mean 4K detail — the badge in the export sheet reports the real detail ratio.",
            fontSize = 10.sp,
            color = TextSecondary
        )
    }
}

// =============================================================== PROJECT SETTINGS

@Composable
fun ProjectSheetContent(
    project: Project,
    viewModel: EditorViewModel,
    onEditTitle: () -> Unit
) {
    StudioCard(title = "Canvas") {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AspectRatioPreset.entries.forEach { preset ->
                StudioChip(preset.label, project.aspectRatio == preset) {
                    viewModel.setAspectRatio(preset)
                }
            }
        }
        if (project.aspectRatio == AspectRatioPreset.CUSTOM) {
            StudioSlider(
                label = "Custom ratio",
                value = project.customAspectRatio,
                valueRange = 0.4f..2.5f,
                defaultValue = 0.5625f,
                valueText = String.format(Locale.US, "%.2f", project.customAspectRatio)
            ) { viewModel.setCustomAspectRatio(it) }
        }
    }

    StudioCard(title = "Preview quality") {
        StudioChipRow {
            PreviewQuality.entries.forEach { quality ->
                StudioChip(quality.label, project.previewQuality == quality) {
                    viewModel.setPreviewQuality(quality)
                }
            }
        }
        Text(
            "Performance modes decode a smaller proxy so dragging stays smooth on mid-range phones. Export always renders at full quality.",
            fontSize = 10.sp,
            color = TextTertiary
        )
    }

    StudioCard(title = "Timeline behaviour") {
        StudioToggleRow("Magnetic main track", project.magneticTimeline) {
            viewModel.toggleMagneticTimeline()
        }
        StudioToggleRow("Snapping to clip edges", project.snappingEnabled) {
            viewModel.toggleSnapping()
        }
        StudioChip("Rename project") { onEditTitle() }
    }
}
