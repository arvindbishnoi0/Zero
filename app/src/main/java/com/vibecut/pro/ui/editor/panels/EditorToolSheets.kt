package com.vibecut.pro.ui.editor.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorSheet
import com.vibecut.pro.ui.viewmodel.EditorUiState
import com.vibecut.pro.ui.viewmodel.EditorViewModel

/**
 * Host for all editor tool sheets. Exactly one sheet is open at a time, each with its own title,
 * live controls and Done/Reset actions — no long scrolling inspector pages, no hidden tools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorToolSheets(
    state: EditorUiState,
    playheadMs: Long,
    viewModel: EditorViewModel
) {
    val sheet = state.openSheet
    if (sheet == EditorSheet.NONE) return
    val clip = state.selectedClip ?: state.project.findMainClipAt(playheadMs)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = viewModel::closeSheet,
        sheetState = sheetState,
        containerColor = ZeroBackground,
        contentColor = TextPrimary,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ZeroStroke)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 14.dp)
        ) {
            val title = when (sheet) {
                EditorSheet.MEDIA -> "Add media"
                EditorSheet.CLIP -> "Clip tools"
                EditorSheet.TRANSFORM -> "Transform"
                EditorSheet.SPEED -> "Speed"
                EditorSheet.FILTERS -> "Filters"
                EditorSheet.ADJUST -> "Color adjust"
                EditorSheet.DETAIL -> "Detail & finishing"
                EditorSheet.EFFECTS -> "Effects"
                EditorSheet.TRANSITIONS -> "Transitions"
                EditorSheet.TEXT -> "Text & captions"
                EditorSheet.AUDIO -> "Audio & mixing"
                EditorSheet.KEYFRAMES -> "Motion & keyframes"
                EditorSheet.ENHANCE -> "4K enhancer"
                EditorSheet.EXPORT -> "Export"
                EditorSheet.PROJECT -> "Project settings"
                EditorSheet.NONE -> ""
            }
            val subtitle = when {
                sheet == EditorSheet.PROJECT || sheet == EditorSheet.EXPORT -> state.project.title
                clip != null -> "${clip.sourceName} · ${formatDurationShort(clip.timelineDurationMs)}"
                else -> "No clip selected"
            }

            SheetHeader(title = title, subtitle = subtitle, onClose = viewModel::closeSheet)

            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                when (sheet) {
                    EditorSheet.CLIP -> ClipToolSheet(clip, state.project, viewModel)
                    EditorSheet.TRANSFORM -> TransformSheet(clip, viewModel, state.project)
                    EditorSheet.SPEED -> SpeedSheet(clip, playheadMs, viewModel)
                    EditorSheet.FILTERS -> FilterSheet(clip, state.project, viewModel)
                    EditorSheet.ADJUST -> AdjustSheet(clip, viewModel)
                    EditorSheet.DETAIL -> DetailSheet(clip, viewModel)
                    EditorSheet.EFFECTS -> EffectsSheet(clip, state.project, viewModel)
                    EditorSheet.TRANSITIONS -> TransitionSheet(clip, viewModel)
                    EditorSheet.TEXT -> TextSheet(clip, state.project, viewModel)
                    EditorSheet.AUDIO -> AudioSheet(clip, state.project, playheadMs, viewModel)
                    EditorSheet.KEYFRAMES -> KeyframeSheet(clip, playheadMs, viewModel)
                    EditorSheet.ENHANCE -> EnhanceSheet(state.project, viewModel)
                    EditorSheet.EXPORT -> ExportSheet(state.project, viewModel)
                    EditorSheet.PROJECT -> ProjectSheet(state.project, viewModel)
                    else -> Unit
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sheet != EditorSheet.EXPORT && sheet != EditorSheet.PROJECT) {
                    StudioPrimaryButton(
                        text = "Reset",
                        icon = Icons.Default.Refresh,
                        container = ZeroSurfaceHigh,
                        contentColor = TextPrimary,
                        onClick = {
                            @Suppress("UNUSED_EXPRESSION")
                            when (sheet) {
                                EditorSheet.FILTERS, EditorSheet.ADJUST, EditorSheet.DETAIL ->
                                    viewModel.resetAllGradingOnSelected()
                                EditorSheet.EFFECTS -> viewModel.clearEffectsOnSelected()
                                EditorSheet.TRANSFORM -> viewModel.updateSelectedTransform(ClipTransform())
                                EditorSheet.TRANSITIONS -> viewModel.setTransitionOnSelected(null)
                                EditorSheet.SPEED -> viewModel.updateSelectedSpeed(1f)
                                EditorSheet.KEYFRAMES -> viewModel.clearKeyframesOnSelected()
                                else -> Unit
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                StudioPrimaryButton(
                    text = "Done",
                    icon = Icons.Default.Check,
                    onClick = viewModel::closeSheet,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- clip tools

@Composable
private fun ClipToolSheet(
    clip: TimelineClip?,
    project: Project,
    viewModel: EditorViewModel
) {
    if (clip == null) {
        EmptySelectionHint()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Arrange") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioAction(Icons.Default.ContentCut, "Split", onClick = viewModel::splitSelectedAtPlayhead)
                StudioAction(Icons.Default.ContentCopy, "Duplicate", onClick = viewModel::duplicateSelectedClip)
                StudioAction(Icons.Default.Delete, "Delete", tint = ZeroCoral, onClick = viewModel::deleteSelectedClip)
                StudioAction(Icons.Default.Merge, "Merge", onClick = viewModel::mergeAdjacentOnCurrentTrack)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioAction(Icons.Default.ArrowBack, "Left", onClick = { viewModel.nudgeSelectedClipPositionMs(-200L) })
                StudioAction(Icons.Default.ArrowForward, "Right", onClick = { viewModel.nudgeSelectedClipPositionMs(200L) })
                StudioAction(Icons.Default.SwapHoriz, "Reorder", onClick = { viewModel.moveSelectedClipOrder(1) })
                StudioAction(Icons.Default.Timer, "Freeze", onClick = viewModel::insertFreezeFrame)
            }
        }

        StudioCard(title = "Transform", onReset = { viewModel.updateSelectedTransform(ClipTransform()) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioAction(Icons.Default.RotateRight, "Rotate 90°", onClick = { viewModel.rotateSelected90(true) })
                StudioAction(Icons.Default.Flip, "Flip H", onClick = viewModel::flipSelectedHorizontal)
                StudioAction(Icons.Default.SwapVert, "Flip V", onClick = viewModel::flipSelectedVertical)
                StudioAction(
                    Icons.Default.Speed,
                    "Speed",
                    tint = if (clip.clampedSpeed != 1f) ZeroAmber else TextPrimary,
                    onClick = { viewModel.openSheet(EditorSheet.SPEED) }
                )
            }
        }

        StudioCard(title = "Crop") {
            CropPresetRow(clip, viewModel)
        }

        StudioCard(title = "Blend & key") {
            StudioChipRow {
                BlendMode.entries.forEach { mode ->
                    StudioChip(
                        label = mode.label,
                        selected = clip.transform.blendMode == mode,
                        onClick = { viewModel.setBlendModeOnSelected(mode) }
                    )
                }
            }
            StudioToggleRow(
                label = "Chroma key (green screen)",
                checked = clip.chromaKey.isActive,
                onCheckedChange = { on ->
                    viewModel.setChromaKeyOnSelected(
                        if (on) ChromaKeyConfig(enabled = true) else ChromaKeyConfig()
                    )
                }
            )
            if (clip.chromaKey.isActive) {
                StudioSlider(
                    label = "Key strength",
                    value = clip.chromaKey.amount,
                    valueRange = 0.05f..1f,
                    defaultValue = 1f,
                    valueText = "${(clip.chromaKey.amount * 100).toInt()}%",
                    onValueChange = { viewModel.setChromaKeyOnSelected(clip.chromaKey.copy(amount = it)) }
                )
                StudioSlider(
                    label = "Tolerance",
                    value = clip.chromaKey.toleranceDeg,
                    valueRange = 5f..80f,
                    defaultValue = 32f,
                    valueText = "${clip.chromaKey.toleranceDeg.toInt()}°",
                    onValueChange = { viewModel.setChromaKeyOnSelected(clip.chromaKey.copy(toleranceDeg = it)) }
                )
                StudioSlider(
                    label = "Edge softness",
                    value = clip.chromaKey.softness,
                    valueRange = 0f..1f,
                    defaultValue = 0.12f,
                    valueText = "${(clip.chromaKey.softness * 100).toInt()}%",
                    onValueChange = { viewModel.setChromaKeyOnSelected(clip.chromaKey.copy(softness = it)) }
                )
            }
        }

        StudioCard(title = "Duration") {
            StudioSlider(
                label = "Timeline duration",
                value = clip.timelineDurationMs.toFloat(),
                valueRange = 200f..(clip.trimmedSourceDurationMs.coerceAtLeast(
                    clip.timelineDurationMs
                ).coerceAtLeast(2_000L).toFloat()),
                defaultValue = clip.trimmedSourceDurationMs.toFloat(),
                valueText = formatDurationShort(clip.timelineDurationMs),
                onValueChange = { viewModel.setPhotoOrClipDurationMs(it.toLong()) }
            )
        }
    }
}

@Composable
private fun CropPresetRow(clip: TimelineClip, viewModel: EditorViewModel) {
    // CropRect holds normalized *bounds*: identity is left/top = 0, right/bottom = 1.
    val crops = listOf(
        "Original" to CropRect(),
        "1:1" to CropRect(left = 0.219f, top = 0f, right = 0.781f, bottom = 1f),
        "4:5" to CropRect(left = 0.1f, top = 0f, right = 0.9f, bottom = 1f),
        "9:16" to CropRect(left = 0.281f, top = 0f, right = 0.719f, bottom = 1f),
        "16:9" to CropRect(left = 0f, top = 0.219f, right = 1f, bottom = 0.781f)
    )
    StudioChipRow {
        crops.forEach { (label, rect) ->
            StudioChip(
                label = label,
                selected = clip.crop == rect,
                onClick = { viewModel.setCropOnSelected(rect) }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    val zoom = 1f / clip.crop.widthFraction.coerceAtLeast(0.15f)
    StudioSlider(
        label = "Zoom",
        value = zoom.coerceIn(1f, 3f),
        valueRange = 1f..3f,
        defaultValue = 1f,
        valueText = "${"%.2f".format(zoom)}x",
        onValueChange = { newZoom ->
            val side = ((1f - 1f / newZoom.coerceAtLeast(1f)) * 0.5f).coerceIn(0f, 0.42f)
            viewModel.setCropOnSelected(
                clip.crop.copy(left = side, top = side, right = 1f - side, bottom = 1f - side)
            )
        }
    )
}

// ---------------------------------------------------------------------------- transform

@Composable
private fun TransformSheet(clip: TimelineClip?, viewModel: EditorViewModel, project: Project) {
    if (clip == null) { EmptySelectionHint(); return }
    val t = clip.transform
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Scale & position") {
            StudioSlider(
                label = "Scale",
                value = t.scaleX,
                valueRange = 0.15f..3f,
                defaultValue = 1f,
                valueText = "${"%.2f".format(t.scaleX)}x",
                onValueChange = { viewModel.updateSelectedTransform(t.copy(scaleX = it, scaleY = it)) }
            )
            StudioSlider(
                label = "Position X",
                value = t.positionX,
                valueRange = -1f..1f,
                defaultValue = 0f,
                valueText = "${"%.2f".format(t.positionX)}",
                onValueChange = { viewModel.updateSelectedTransform(t.copy(positionX = it)) }
            )
            StudioSlider(
                label = "Position Y",
                value = t.positionY,
                valueRange = -1f..1f,
                defaultValue = 0f,
                valueText = "${"%.2f".format(t.positionY)}",
                onValueChange = { viewModel.updateSelectedTransform(t.copy(positionY = it)) }
            )
            StudioSlider(
                label = "Rotation",
                value = t.rotationDegrees,
                valueRange = -180f..180f,
                defaultValue = 0f,
                valueText = "${t.rotationDegrees.toInt()}°",
                onValueChange = { viewModel.updateSelectedTransform(t.copy(rotationDegrees = it)) }
            )
        }

        StudioCard(title = "Opacity & blend") {
            StudioSlider(
                label = "Opacity",
                value = t.opacity,
                valueRange = 0f..1f,
                defaultValue = 1f,
                valueText = "${(t.opacity * 100).toInt()}%",
                onValueChange = { viewModel.updateSelectedTransform(t.copy(opacity = it)) }
            )
            StudioToggleRow("Flip horizontal", t.flipHorizontal) {
                viewModel.flipSelectedHorizontal()
            }
            StudioToggleRow("Flip vertical", t.flipVertical) {
                viewModel.flipSelectedVertical()
            }
            StudioChipRow {
                BlendMode.entries.forEach { mode ->
                    StudioChip(mode.label, t.blendMode == mode) { viewModel.setBlendModeOnSelected(mode) }
                }
            }
        }

        StudioCard(title = "Shortcuts") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioAction(Icons.Default.RotateRight, "Rotate", onClick = { viewModel.rotateSelected90(true) })
                StudioAction(Icons.Default.SwapVert, "Flip V", onClick = viewModel::flipSelectedVertical)
                StudioAction(Icons.Default.Speed, "Speed", onClick = { viewModel.openSheet(EditorSheet.SPEED) })
                StudioAction(Icons.Default.Timeline, "Keyframes", onClick = { viewModel.openSheet(EditorSheet.KEYFRAMES) })
            }
        }
    }
}

// ---------------------------------------------------------------------------- speed

@Composable
private fun SpeedSheet(clip: TimelineClip?, playheadMs: Long, viewModel: EditorViewModel) {
    if (clip == null) { EmptySelectionHint(); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Playback speed", badge = "${"%.2f".format(clip.clampedSpeed)}x") {
            StudioSlider(
                label = "Constant speed",
                value = clip.clampedSpeed,
                valueRange = 0.1f..4f,
                defaultValue = 1f,
                valueText = "${"%.2f".format(clip.clampedSpeed)}x",
                onValueChange = { viewModel.updateSelectedSpeed(it) }
            )
            StudioChipRow {
                listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f).forEach { s ->
                    StudioChip("${s}x", selected = kotlin.math.abs(clip.clampedSpeed - s) < 0.01f) {
                        viewModel.updateSelectedSpeed(s)
                    }
                }
            }
            StudioToggleRow("Reverse playback", clip.isReversed) {
                viewModel.toggleReverseSelected()
            }
        }

        StudioCard(title = "Speed ramp") {
            StudioChipRow {
                SpeedCurvePreset.entries.forEach { preset ->
                    StudioChip(
                        label = preset.label,
                        selected = clip.speedCurvePreset == preset,
                        onClick = {
                            viewModel.updateSelectedSpeed(clip.clampedSpeed, preset)
                        }
                    )
                }
            }
            Text(
                "Ramps are applied as real per-segment speed in preview and export (audio is re-pitched to match).",
                fontSize = 10.sp,
                color = TextTertiary
            )
        }

        StudioCard(title = "Freeze") {
            StudioPrimaryButton(
                text = "Insert 2s freeze at playhead",
                icon = Icons.Default.Timer,
                onClick = viewModel::insertFreezeFrame
            )
        }
    }
}

// ---------------------------------------------------------------------------- filters

@Composable
private fun FilterSheet(clip: TimelineClip?, project: Project, viewModel: EditorViewModel) {
    if (clip == null) { EmptySelectionHint(); return }
    var category by remember { mutableStateOf("All") }
    val presets = com.vibecut.pro.engine.color.ColorGradingEngine.presetsInCategory(
        category, project.customColorPresets
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioChipRow {
            com.vibecut.pro.engine.color.ColorGradingEngine.filterCategories.forEach { cat ->
                StudioChip(cat, selected = category == cat) { category = cat }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in presets.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    preset.forEach { p ->
                        val selected = clip.colorGrade.lutPresetId == p.id
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) ZeroAccent.copy(alpha = 0.16f) else ZeroSurface)
                                .clickable { viewModel.applyColorPresetOnSelected(p) }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                p.name,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) ZeroAccent else TextPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                            Text(p.category, fontSize = 8.sp, color = TextTertiary)
                        }
                    }
                    repeat(3 - preset.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        StudioChip(
            label = "None (original)",
            selected = clip.colorGrade.lutPresetId == null && clip.colorGrade.customCubeLutUri == null,
            onClick = { viewModel.applyColorPresetOnSelected(null) }
        )
        StudioCard(title = "Filter strength") {
            StudioSlider(
                label = "Mix",
                value = clip.colorGrade.lutIntensity,
                valueRange = 0f..1f,
                defaultValue = 1f,
                valueText = "${(clip.colorGrade.lutIntensity * 100).toInt()}%",
                onValueChange = viewModel::updateFilterIntensityOnSelected
            )
        }
    }
}

// ---------------------------------------------------------------------------- adjust

@Composable
private fun AdjustSheet(clip: TimelineClip?, viewModel: EditorViewModel) {
    if (clip == null) { EmptySelectionHint(); return }
    val g = clip.colorGrade
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Light", onReset = { viewModel.resetColorSectionOnSelected("light") }) {
            GradeSlider("Exposure", g.exposure, -2f..2f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(exposure = it), "exposure") }
            GradeSlider("Brightness", g.brightness, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(brightness = it), "brightness") }
            GradeSlider("Contrast", g.contrast, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(contrast = it), "contrast") }
            GradeSlider("Highlights", g.highlights, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(highlights = it), "highlights") }
            GradeSlider("Shadows", g.shadows, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(shadows = it), "shadows") }
            GradeSlider("Whites", g.whites, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(whites = it), "whites") }
            GradeSlider("Blacks", g.blacks, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(blacks = it), "blacks") }
            GradeSlider("Gamma", g.gamma, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(gamma = it), "gamma") }
        }
        StudioCard(title = "Color", onReset = { viewModel.resetColorSectionOnSelected("color") }) {
            GradeSlider("Saturation", g.saturation, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(saturation = it), "saturation") }
            GradeSlider("Vibrance", g.vibrance, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(vibrance = it), "vibrance") }
            GradeSlider("Temperature", g.temperature, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(temperature = it), "temperature") }
            GradeSlider("Tint", g.tint, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(tint = it), "tint") }
            GradeSlider("Hue shift", g.hueShift, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(hueShift = it), "hue") }
            GradeSlider("Fade", g.fade, 0f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(fade = it), "fade") }
            GradeSlider("Vignette", g.vignette, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(vignette = it), "vignette") }
        }
        StudioCard(title = "Presets") {
            StudioPrimaryButton(
                text = "Apply this look to all clips",
                icon = Icons.Default.Layers,
                onClick = viewModel::applyColorGradeToAllClips
            )
            SavedPresetRow(clip, viewModel)
        }
    }
}

@Composable
private fun SavedPresetRow(clip: TimelineClip, viewModel: EditorViewModel) {
    var presetName by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = presetName,
            onValueChange = { presetName = it },
            placeholder = { Text("Preset name", fontSize = 11.sp, color = TextTertiary) },
            singleLine = true,
            modifier = Modifier.weight(1f).height(46.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ZeroAccent,
                unfocusedBorderColor = ZeroStroke,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ZeroAccent)
                .clickable {
                    viewModel.saveCustomColorPreset(presetName)
                    presetName = ""
                }
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Text("Save look", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZeroBlack)
        }
    }
}

@Composable
private fun GradeSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    viewModel: EditorViewModel,
    onChange: (Float) -> Unit
) {
    StudioSlider(
        label = label,
        value = value,
        valueRange = range,
        defaultValue = default,
        valueText = if (range.endInclusive <= 1f && range.start >= 0f) "${(value * 100).toInt()}"
        else formatSignedPercent(value),
        onValueChange = onChange
    )
}

// ---------------------------------------------------------------------------- detail

@Composable
private fun DetailSheet(clip: TimelineClip?, viewModel: EditorViewModel) {
    if (clip == null) { EmptySelectionHint(); return }
    val g = clip.colorGrade
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Sharpening", badge = "GPU", onReset = { viewModel.resetColorSectionOnSelected("detail") }) {
            GradeSlider("Sharpness", g.sharpness, 0f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(sharpness = it), "sharp") }
            GradeSlider("Radius", g.sharpenRadius, 0.05f..1f, 0.35f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(sharpenRadius = it), "sharpen_radius") }
            GradeSlider("Threshold", g.sharpenThreshold, 0f..0.5f, 0.12f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(sharpenThreshold = it), "sharpen_threshold") }
            GradeSlider("Clarity", g.clarity, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(clarity = it), "clarity") }
            GradeSlider("Structure", g.structure, 0f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(structure = it), "structure") }
        }
        StudioCard(title = "Noise control") {
            GradeSlider("Denoise", g.denoise, 0f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(denoise = it), "denoise") }
            GradeSlider("Film grain", g.grain, 0f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(grain = it), "grain") }
            GradeSlider("Dehaze", g.dehaze, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(dehaze = it), "dehaze") }
        }
        StudioCard(title = "Vignette") {
            GradeSlider("Amount", g.vignette, -1f..1f, 0f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(vignette = it), "vig") }
            GradeSlider("Feather", g.vignetteFeather, 0f..1f, 0.5f, viewModel) { viewModel.updateColorGradeOnSelected(g.copy(vignetteFeather = it), "vig_feather") }
        }
    }
}

// ---------------------------------------------------------------------------- effects

@Composable
private fun EffectsSheet(clip: TimelineClip?, project: Project, viewModel: EditorViewModel) {
    if (clip == null) { EmptySelectionHint(); return }
    var tab by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip("Library", tab == 0) { tab = 0 }
            StudioChip("Applied (${clip.effects.size})", tab == 1) { tab = 1 }
        }

        if (tab == 0) {
            EffectCategory.entries.forEach { category ->
                val types = EffectType.entries.filter { it.category == category }
                if (types.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            category.label.uppercase(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextTertiary
                        )
                        StudioChipRow {
                            types.forEach { type ->
                                StudioChip(
                                    label = type.label,
                                    accent = Color(type.badgeColorHex),
                                    selected = clip.effects.any { it.type == type }
                                ) {
                                    viewModel.addEffectToSelected(type)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (clip.effects.isEmpty()) {
                Text("No effects yet — add some from the library.", fontSize = 11.sp, color = TextTertiary)
            }
            clip.effects.forEachIndexed { index, fx ->
                StudioCard(title = fx.type.label, badge = if (fx.enabled) "ON" else "OFF") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "Move up",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable(enabled = index > 0) { viewModel.reorderEffectOnSelected(fx.id, -1) }
                            )
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = "Move down",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable(enabled = index < clip.effects.lastIndex) {
                                        viewModel.reorderEffectOnSelected(fx.id, 1)
                                    }
                            )
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint = ZeroCoral,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { viewModel.removeEffectFromSelected(fx.id) }
                            )
                        }
                        Switch(
                            checked = fx.enabled,
                            onCheckedChange = { viewModel.toggleEffectEnabled(fx.id, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ZeroBlack,
                                checkedTrackColor = ZeroAccent,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = ZeroSurfaceHigh
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                    StudioSlider(
                        label = fx.type.primaryLabel,
                        value = fx.intensity,
                        valueRange = 0f..1f,
                        defaultValue = fx.type.defaultIntensity,
                        valueText = "${(fx.intensity * 100).toInt()}%",
                        accent = Color(fx.type.badgeColorHex),
                        onValueChange = { viewModel.updateEffectOnSelected(fx.copy(intensity = it)) }
                    )
                    StudioSlider(
                        label = fx.type.secondaryLabel,
                        value = fx.secondaryParam,
                        valueRange = 0f..1f,
                        defaultValue = fx.type.defaultSecondary,
                        valueText = "${(fx.secondaryParam * 100).toInt()}%",
                        accent = Color(fx.type.badgeColorHex),
                        onValueChange = { viewModel.updateEffectOnSelected(fx.copy(secondaryParam = it)) }
                    )
                    StudioSlider(
                        label = fx.type.tertiaryLabel,
                        value = fx.tertiaryParam,
                        valueRange = 0f..1f,
                        defaultValue = fx.type.defaultTertiary,
                        valueText = "${(fx.tertiaryParam * 100).toInt()}%",
                        accent = Color(fx.type.badgeColorHex),
                        onValueChange = { viewModel.updateEffectOnSelected(fx.copy(tertiaryParam = it)) }
                    )
                }
            }
            if (clip.effects.isNotEmpty()) {
                StudioPrimaryButton(
                    text = "Apply stack to all clips",
                    icon = Icons.Default.Layers,
                    onClick = viewModel::applyEffectsToAllClips
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- transitions

@Composable
private fun TransitionSheet(clip: TimelineClip?, viewModel: EditorViewModel) {
    if (clip == null) { EmptySelectionHint(); return }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioChipRow {
            TransitionType.entries.forEach { type ->
                StudioChip(
                    label = type.label,
                    selected = clip.transitionIn?.type == type
                ) {
                    viewModel.setTransitionOnSelected(
                        ClipTransition(type = type, durationMs = type.defaultDurationMs)
                    )
                }
            }
        }
        StudioChip("No transition", selected = clip.transitionIn == null) {
            viewModel.setTransitionOnSelected(null)
        }
        clip.transitionIn?.let { trans ->
            StudioCard(title = "${trans.type.label} settings") {
                StudioSlider(
                    label = "Duration",
                    value = trans.durationMs.toFloat(),
                    valueRange = 100f..1500f,
                    defaultValue = trans.type.defaultDurationMs.toFloat(),
                    valueText = "${trans.durationMs}ms",
                    onValueChange = {
                        viewModel.setTransitionOnSelected(
                            trans.copy(durationMs = (it / 50).toInt() * 50L + 50L)
                        )
                    }
                )
                StudioSlider(
                    label = "Softness",
                    value = trans.softness,
                    valueRange = 0f..1f,
                    defaultValue = 0.5f,
                    valueText = "${(trans.softness * 100).toInt()}%",
                    onValueChange = { viewModel.setTransitionOnSelected(trans.copy(softness = it)) }
                )
                StudioSlider(
                    label = "Direction",
                    value = trans.directionAngleDeg,
                    valueRange = 0f..360f,
                    defaultValue = 0f,
                    valueText = "${trans.directionAngleDeg.toInt()}°",
                    onValueChange = { viewModel.setTransitionOnSelected(trans.copy(directionAngleDeg = it)) }
                )
                if (trans.type == TransitionType.CUSTOM) {
                    StudioSlider(
                        label = "Chromatic fringe",
                        value = trans.chromaticAmount,
                        valueRange = 0f..1f,
                        defaultValue = 0.3f,
                        valueText = "${(trans.chromaticAmount * 100).toInt()}%",
                        onValueChange = { viewModel.setTransitionOnSelected(trans.copy(chromaticAmount = it)) }
                    )
                }
            }
        }
        val transitionIn = clip.transitionIn
        if (transitionIn != null) {
            StudioPrimaryButton(
                text = "Apply this transition to all clips",
                icon = Icons.Default.Layers,
                onClick = { viewModel.applyTransitionToAllClips(transitionIn) }
            )
        }
    }
}

// ---------------------------------------------------------------------------- text

@Composable
private fun TextSheet(clip: TimelineClip?, project: Project, viewModel: EditorViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val spec = clip?.textSpec
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Add text") {
            StudioPrimaryButton(
                text = "New text at playhead",
                icon = Icons.Default.TextFields,
                onClick = { viewModel.addTextClip(TextStyleSpec(text = "Your text")) }
            )
        }
        if (spec != null && clip != null) {
            var text by remember(clip.id) { mutableStateOf(spec.text) }
            StudioCard(title = "Content") {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        viewModel.updateSelectedTextSpec(spec.copy(text = it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZeroAccent,
                        unfocusedBorderColor = ZeroStroke,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
            StudioCard(title = "Style") {
                StudioSlider(
                    label = "Font size",
                    value = spec.fontSizeSp,
                    valueRange = 12f..96f,
                    defaultValue = 36f,
                    valueText = "${spec.fontSizeSp.toInt()}sp",
                    onValueChange = { viewModel.updateSelectedTextSpec(spec.copy(fontSizeSp = it)) }
                )
                StudioSlider(
                    label = "Stroke width",
                    value = spec.outlineWidth,
                    valueRange = 0f..8f,
                    defaultValue = 0f,
                    valueText = "%.1f".format(spec.outlineWidth),
                    onValueChange = { viewModel.updateSelectedTextSpec(spec.copy(outlineWidth = it)) }
                )
                StudioSlider(
                    label = "Shadow",
                    value = spec.shadowRadius,
                    valueRange = 0f..24f,
                    defaultValue = 6f,
                    valueText = "${spec.shadowRadius.toInt()}",
                    onValueChange = { viewModel.updateSelectedTextSpec(spec.copy(shadowRadius = it)) }
                )
                StudioChipRow {
                    TextAnimationType.entries.forEach { anim ->
                        StudioChip(anim.label, spec.animationIn == anim) {
                            viewModel.updateSelectedTextSpec(spec.copy(animationIn = anim))
                        }
                    }
                }
            }
        } else {
            Text(
                "Select a text clip on the timeline to edit it, or add a new one above.",
                fontSize = 11.sp,
                color = TextTertiary
            )
        }

        StudioCard(title = "Captions & subtitles", badge = "${project.subtitles.size}") {
            val srtLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) viewModel.importSrtUri(uri)
            }
            StudioPrimaryButton(
                text = "Import .srt subtitles",
                icon = Icons.Default.Subtitles,
                onClick = { srtLauncher.launch(arrayOf("text/*", "application/x-subrip", "text/plain")) }
            )
            if (project.subtitles.isNotEmpty()) {
                StudioPrimaryButton(
                    text = "Share .srt",
                    icon = Icons.Default.Share,
                    container = ZeroSurfaceHigh,
                    contentColor = TextPrimary,
                    onClick = {
                        viewModel.createExportSrtShareIntent()?.let { intent ->
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(android.content.Intent.createChooser(intent, "Share subtitles"))
                        }
                    }
                )
                project.subtitles.take(6).forEach { cue ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cue.text,
                            fontSize = 10.sp,
                            color = TextPrimary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Delete cue",
                            tint = ZeroCoral,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { viewModel.deleteSubtitleCue(cue.id) }
                        )
                    }
                }
            }
            Text(
                "Captions are burned into the preview and the export, and can also be shared as .srt.",
                fontSize = 10.sp,
                color = TextTertiary
            )
        }
    }
}

// ---------------------------------------------------------------------------- audio

@Composable
private fun AudioSheet(
    clip: TimelineClip?,
    project: Project,
    playheadMs: Long,
    viewModel: EditorViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Music & voice") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioAction(Icons.Default.LibraryMusic, "Add audio", onClick = {
                    viewModel.setShowMediaImporterSheet(true, TrackType.AUDIO_MUSIC)
                })
                StudioAction(Icons.Default.GraphicEq, "Detect beats", onClick = viewModel::detectBeatsAndAutoSync)
                StudioAction(
                    Icons.Default.LibraryAdd,
                    if (clip?.hasAudioTrack == true) "Extract audio" else "Extract",
                    onClick = viewModel::extractAudioFromSelectedClip
                )
            }
        }

        if (clip != null && clip.hasAudioTrack) {
            StudioCard(title = "Clip audio") {
                StudioSlider(
                    label = "Volume",
                    value = clip.volume,
                    valueRange = 0f..2f,
                    defaultValue = 1f,
                    valueText = "${(clip.volume * 100).toInt()}%",
                    onValueChange = { viewModel.updateSelectedAudioDsp(volume = it) }
                )
                StudioSlider(
                    label = "Fade in",
                    value = clip.fadeInMs.toFloat(),
                    valueRange = 0f..3000f,
                    defaultValue = 0f,
                    valueText = "${clip.fadeInMs}ms",
                    onValueChange = { viewModel.updateSelectedAudioDsp(fadeInMs = it.toLong()) }
                )
                StudioSlider(
                    label = "Fade out",
                    value = clip.fadeOutMs.toFloat(),
                    valueRange = 0f..3000f,
                    defaultValue = 0f,
                    valueText = "${clip.fadeOutMs}ms",
                    onValueChange = { viewModel.updateSelectedAudioDsp(fadeOutMs = it.toLong()) }
                )
                StudioToggleRow("Mute clip", clip.isMuted) {
                    viewModel.updateSelectedAudioDsp(isMuted = it)
                }
            }
            StudioCard(title = "Clean-up") {
                StudioSlider(
                    label = "Noise reduction",
                    value = clip.noiseReduction,
                    valueRange = 0f..1f,
                    defaultValue = 0f,
                    valueText = "${(clip.noiseReduction * 100).toInt()}%",
                    onValueChange = { viewModel.updateSelectedAudioDsp(noiseReduction = it) }
                )
                StudioSlider(
                    label = "Voice clarity",
                    value = clip.voiceEnhancement,
                    valueRange = 0f..1f,
                    defaultValue = 0f,
                    valueText = "${(clip.voiceEnhancement * 100).toInt()}%",
                    onValueChange = { viewModel.updateSelectedAudioDsp(voiceEnhancement = it) }
                )
                StudioToggleRow("Duck music under voice", clip.audioDucking) {
                    viewModel.updateSelectedAudioDsp(audioDucking = it)
                }
            }
        }

        StudioCard(title = "Tracks") {
            project.tracks.filter { it.type.isAudioTrack }.forEach { track ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(track.type.label, fontSize = 11.sp, color = TextPrimary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StudioChip("Mute", selected = track.isMuted) { viewModel.toggleTrackMute(track.id) }
                            StudioChip("Solo", selected = false) { }
                        }
                    }
                    StudioSlider(
                        label = "Track gain",
                        value = track.volume,
                        valueRange = 0f..2f,
                        defaultValue = 1f,
                        valueText = "${(track.volume * 100).toInt()}%",
                        onValueChange = { viewModel.setTrackVolume(track.id, it) }
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- keyframes

@Composable
private fun KeyframeSheet(clip: TimelineClip?, playheadMs: Long, viewModel: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Add keyframe") {
            val local = clip?.let { (playheadMs - it.startTimeOnTrackMs).coerceAtLeast(0L) } ?: 0L
            KeyframeProperty.entries.forEach { prop ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(prop.label, fontSize = 11.sp, color = TextPrimary)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ZeroAccent.copy(alpha = 0.14f))
                            .clickable {
                                viewModel.upsertKeyframeAtPlayhead(prop, prop.defaultValue)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("+ Key", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ZeroAccent)
                    }
                }
            }
        }
        if (clip != null && clip.keyframes.isNotEmpty()) {
            StudioCard(title = "On this clip", onReset = viewModel::clearKeyframesOnSelected) {
                clip.keyframes.sortedBy { it.timeOffsetMs }.forEach { kf ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(kf.property.label, fontSize = 11.sp, color = TextPrimary)
                            Text(
                                "${kf.timeOffsetMs / 1000f}s · ${kf.value}",
                                fontSize = 9.sp,
                                color = TextTertiary
                            )
                        }
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Delete keyframe",
                            tint = ZeroCoral,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { viewModel.removeKeyframe(kf.id) }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- enhancement

@Composable
private fun EnhanceSheet(project: Project, viewModel: EditorViewModel) {
    val cfg = project.enhancement
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioToggleRow("Enable enhancer", cfg.enabled) {
            viewModel.updateEnhancement(cfg.copy(enabled = it))
        }
        StudioChipRow {
            listOf(720, 1080, 1440, 2160).forEach { h ->
                StudioChip("${h}p", cfg.targetHeight == h) {
                    viewModel.updateEnhancement(cfg.copy(targetHeight = h, enabled = true))
                }
            }
        }
        if (cfg.enabled) {
            StudioCard(title = "Processing", badge = "GPU") {
                StudioSlider(
                    label = "Upscale sharpening",
                    value = cfg.sharpen,
                    valueRange = 0f..1f,
                    defaultValue = 0.4f,
                    valueText = "${(cfg.sharpen * 100).toInt()}%",
                    onValueChange = { viewModel.updateEnhancement(cfg.copy(sharpen = it)) }
                )
                StudioSlider(
                    label = "Contrast",
                    value = cfg.contrastBoost,
                    valueRange = 0f..1f,
                    defaultValue = 0.15f,
                    valueText = "${(cfg.contrastBoost * 100).toInt()}%",
                    onValueChange = { viewModel.updateEnhancement(cfg.copy(contrastBoost = it)) }
                )
                StudioSlider(
                    label = "Color",
                    value = cfg.colorBoost,
                    valueRange = 0f..1f,
                    defaultValue = 0.15f,
                    valueText = "${(cfg.colorBoost * 100).toInt()}%",
                    onValueChange = { viewModel.updateEnhancement(cfg.copy(colorBoost = it)) }
                )
                StudioSlider(
                    label = "Brightness fix",
                    value = cfg.brightnessFix,
                    valueRange = 0f..1f,
                    defaultValue = 0.1f,
                    valueText = "${(cfg.brightnessFix * 100).toInt()}%",
                    onValueChange = { viewModel.updateEnhancement(cfg.copy(brightnessFix = it)) }
                )
                StudioSlider(
                    label = "Detail preservation",
                    value = cfg.detailPreserve,
                    valueRange = 0f..1f,
                    defaultValue = 0.5f,
                    valueText = "${(cfg.detailPreserve * 100).toInt()}%",
                    onValueChange = { viewModel.updateEnhancement(cfg.copy(detailPreserve = it)) }
                )
            }
            StudioCard(title = "Honest capability") {
                Text(
                    "This is a high-quality conventional (non-AI) pipeline: Lanczos-3 resampling, edge-aware sharpening and denoise. Upscaling cannot invent detail that was never captured.",
                    fontSize = 10.sp,
                    color = TextTertiary
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------- export

@Composable
private fun ExportSheet(project: Project, viewModel: EditorViewModel) {
    val cfg = project.exportConfig
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Resolution") {
            StudioChipRow {
                ExportResolution.entries.forEach { res ->
                    StudioChip(res.badge, cfg.resolution == res) {
                        viewModel.updateExportConfig(cfg.copy(resolution = res))
                    }
                }
            }
        }
        StudioCard(title = "Frame rate") {
            StudioChipRow {
                ExportFrameRate.entries.forEach { fps ->
                    StudioChip(fps.label, cfg.frameRate == fps) {
                        viewModel.updateExportConfig(cfg.copy(frameRate = fps))
                    }
                }
            }
        }
        StudioCard(title = "Quality") {
            StudioChipRow {
                BitrateTier.entries.forEach { tier ->
                    StudioChip(tier.label, cfg.bitrateTier == tier) {
                        viewModel.updateExportConfig(cfg.copy(bitrateTier = tier))
                    }
                }
            }
            StudioSlider(
                label = "Custom bitrate (Mbps)",
                value = (cfg.customBitrateMbps).coerceIn(1f, 100f),
                valueRange = 1f..100f,
                defaultValue = 16f,
                valueText = "${"%.0f".format(cfg.customBitrateMbps)}",
                onValueChange = {
                    viewModel.updateExportConfig(
                        cfg.copy(bitrateTier = BitrateTier.CUSTOM, customBitrateMbps = it)
                    )
                }
            )
        }
        StudioCard(title = "Codec") {
            StudioChipRow {
                StudioChip("H.264", cfg.codec == VideoCodecOption.H264) {
                    viewModel.updateExportConfig(cfg.copy(codec = VideoCodecOption.H264))
                }
                StudioChip(
                    if (viewModel.uiState.value.isHevcSupported) "H.265 (HEVC)" else "H.265 unavailable",
                    cfg.codec == VideoCodecOption.H265
                ) {
                    viewModel.updateExportConfig(cfg.copy(codec = VideoCodecOption.H265))
                }
            }
        }
        StudioCard(title = "Output") {
            StudioToggleRow("Save to gallery", cfg.saveToGallery) {
                viewModel.updateExportConfig(cfg.copy(saveToGallery = it))
            }
            StudioToggleRow("Audio only (M4A master)", cfg.audioOnly) {
                viewModel.updateExportConfig(cfg.copy(audioOnly = it, exportAsPhotoFrame = false))
            }
            StudioToggleRow("Photo frame at playhead (JPG)", cfg.exportAsPhotoFrame) {
                viewModel.updateExportConfig(cfg.copy(exportAsPhotoFrame = it, audioOnly = false))
            }
        }

        // Real numbers derived from the configured raster, not decoration.
        val (outW, outH) = AspectRatioPreset.dimensionsForProject(
            project.aspectRatio,
            cfg.resolution.shortSidePx,
            project.customAspectRatio
        )
        val bitrateMbps = cfg.targetVideoBitrateBps(project.aspectRatio, project.customAspectRatio) / 1_000_000f
        val estimateMb = cfg.estimatedFileSizeMb(
            project.totalDurationMs,
            project.aspectRatio,
            project.customAspectRatio
        )
        StudioCard(title = "Estimated output", badge = "REAL") {
            Text(
                buildString {
                    append(if (cfg.audioOnly) "Audio ${cfg.audioBitrateKbps} kbps AAC"
                    else "${outW}x$outH · ${cfg.frameRate.fps}fps")
                    if (!cfg.audioOnly) append(" · ${"%.1f".format(bitrateMbps)} Mbps ${cfg.codec.label}")
                    append(" · ~${"%.1f".format(estimateMb)} MB")
                    append("\n${formatDurationShort(project.totalDurationMs)} timeline")
                },
                fontSize = 10.sp,
                color = TextSecondary
            )
            if (!viewModel.uiState.value.isHevcSupported && cfg.codec == VideoCodecOption.H265) {
                Text(
                    "This device has no HEVC encoder — the export will fall back to H.264.",
                    fontSize = 9.5.sp,
                    color = ZeroAmber
                )
            }
        }

        StudioPrimaryButton(
            text = when {
                cfg.exportAsPhotoFrame -> "Save photo frame (.jpg)"
                cfg.audioOnly -> "Export audio master (.m4a)"
                else -> "Export ${cfg.resolution.badge} ${cfg.frameRate.fps}fps"
            },
            icon = Icons.Default.Movie,
            onClick = {
                viewModel.closeSheet()
                viewModel.startExport()
            }
        )
    }
}

// ---------------------------------------------------------------------------- project

@Composable
private fun ProjectSheet(project: Project, viewModel: EditorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StudioCard(title = "Aspect ratio") {
            StudioChipRow {
                AspectRatioPreset.entries.forEach { preset ->
                    StudioChip(preset.label, project.aspectRatio == preset) {
                        viewModel.setAspectRatio(preset)
                    }
                }
            }
            if (project.aspectRatio == AspectRatioPreset.CUSTOM) {
                StudioSlider(
                    label = "Custom ratio (W:H)",
                    value = project.customAspectRatio,
                    valueRange = 0.5f..2.5f,
                    defaultValue = 1f,
                    valueText = "${"%.2f".format(project.customAspectRatio)}",
                    onValueChange = { viewModel.setAspectRatio(AspectRatioPreset.CUSTOM, it) }
                )
            }
        }
        StudioCard(title = "Preview quality") {
            StudioChipRow {
                PreviewQuality.entries.forEach { q ->
                    StudioChip(q.label, project.previewQuality == q) { viewModel.setPreviewQuality(q) }
                }
            }
        }
        StudioCard(title = "Timeline behaviour") {
            StudioToggleRow("Magnetic main track", project.magneticTimeline) {
                viewModel.toggleMagneticTimeline()
            }
            StudioToggleRow("Snap to edges & beats", project.snappingEnabled) {
                viewModel.toggleSnapping()
            }
        }
        StudioCard(title = "Enhancer") {
            StudioChip(if (project.enhancement.enabled) "Enabled" else "Disabled", project.enhancement.enabled) {
                viewModel.openSheet(EditorSheet.ENHANCE)
            }
        }
    }
}

@Composable
private fun EmptySelectionHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.TouchApp, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            "Select a clip on the timeline first",
            fontSize = 11.sp,
            color = TextTertiary
        )
    }
}
