package com.vibecut.pro.ui.editor.panels

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// 1. CLIP EDIT PANEL (Trim, Duration, Speed & Ramps, Crop, Transform)
// ============================================================================
@Composable
fun ClipEditPanel(
    selectedClip: TimelineClip?,
    project: Project,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint(
            message = "Import a video or photo clip to start editing.",
            actionLabel = "Open Media Hub",
            onAction = { viewModel.setShowMediaImporterSheet(true) }
        )
        return
    }

    var subSection by remember { mutableIntStateOf(0) } // 0 = Cut & Speed, 1 = Crop & Transform

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Quick Action Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionPill(Icons.Default.AddPhotoAlternate, "Add Media", tint = ElectricCyan) {
                viewModel.setShowMediaImporterSheet(true)
            }
            ActionPill(Icons.Default.ContentCut, "Split", onClick = viewModel::splitSelectedAtPlayhead)
            ActionPill(Icons.Default.ContentCopy, "Duplicate", onClick = viewModel::duplicateSelectedClip)
            ActionPill(Icons.Default.Delete, "Delete", tint = CoralRed, onClick = viewModel::deleteSelectedClip)
            ActionPill(Icons.Default.AcUnit, "Freeze 2s", onClick = viewModel::insertFreezeFrame)
            ActionPill(Icons.Default.Rotate90DegreesCw, "Rotate 90°") { viewModel.rotateSelected90(true) }
            ActionPill(Icons.Default.Flip, "Flip H", onClick = viewModel::flipSelectedHorizontal)
            ActionPill(Icons.Default.SwapVert, "Flip V", onClick = viewModel::flipSelectedVertical)
            ActionPill(
                Icons.Default.Replay,
                if (selectedClip.isReversed) "Reversed ✓" else "Reverse",
                tint = if (selectedClip.isReversed) KeyframeGold else TextPrimary,
                onClick = viewModel::toggleReverseSelected
            )
            ActionPill(Icons.Default.CallSplit, "Cut Mid", onClick = viewModel::cutMiddleTwentyPercentOfSelected)
            ActionPill(Icons.Default.KeyboardArrowLeft, "Move ◀") { viewModel.moveSelectedClipOrder(-1) }
            ActionPill(Icons.Default.KeyboardArrowRight, "Move ▶") { viewModel.moveSelectedClipOrder(1) }
            ActionPill(Icons.Default.MergeType, "Merge", onClick = viewModel::mergeAdjacentOnCurrentTrack)
        }

        // Clean 2-Tab Sub-Switcher
        StudioSegmentedRow(
            options = listOf("Trim & Speed", "Crop & Transform"),
            selectedIndex = subSection,
            onSelect = { subSection = it }
        )

        if (subSection == 0) {
            // Safe Precision Trim & Duration Card
            StudioSectionCard(
                title = "Trim & Clip Duration",
                badge = String.format(Locale.US, "%.2fs", selectedClip.timelineDurationMs / 1000f)
            ) {
                val maxDurMs = selectedClip.sourceDurationMs.coerceAtLeast(300L)
                val safeStart = selectedClip.trimStartMs.coerceIn(0L, maxDurMs - 100L).toFloat()
                val safeEnd = selectedClip.effectiveTrimEndMs.coerceIn(selectedClip.trimStartMs + 100L, maxDurMs).toFloat()

                RangeSlider(
                    value = safeStart..safeEnd,
                    onValueChange = { range ->
                        viewModel.trimSelectedClip(range.start.toLong(), range.endInclusive.toLong())
                    },
                    valueRange = 0f..maxDurMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan,
                        inactiveTrackColor = SurfaceStroke
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("In: ${String.format(Locale.US, "%.2fs", safeStart / 1000f)}", fontSize = 10.sp, color = TextSecondary)
                    Text("Out: ${String.format(Locale.US, "%.2fs", safeEnd / 1000f)}", fontSize = 10.sp, color = TextSecondary)
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Quick Duration:", fontSize = 11.sp, color = TextSecondary)
                    listOf(1_500L to "1.5s", 3_000L to "3.0s", 4_500L to "4.5s", 7_000L to "7.0s", 10_000L to "10s").forEach { (ms, label) ->
                        MiniPresetChip(
                            label = label,
                            selected = abs(selectedClip.timelineDurationMs - ms) < 200L,
                            onClick = { viewModel.setPhotoOrClipDurationMs(ms) }
                        )
                    }
                }
            }

            // Speed & Pro Speed Curves Card
            StudioSectionCard(
                title = "Speed & Velocity Ramps",
                badge = if (selectedClip.speedCurvePreset != SpeedCurvePreset.NONE) {
                    selectedClip.speedCurvePreset.label
                } else String.format(Locale.US, "%.2fx", selectedClip.speed),
                onReset = if (abs(selectedClip.speed - 1f) > 0.01f || selectedClip.speedCurvePreset != SpeedCurvePreset.NONE) {
                    { viewModel.updateSelectedSpeed(1.0f, SpeedCurvePreset.NONE) }
                } else null
            ) {
                ProParameterSlider(
                    label = "Constant Speed",
                    value = selectedClip.speed,
                    defaultValue = 1.0f,
                    range = 0.1f..6.0f,
                    valueFormatter = { String.format(Locale.US, "%.2fx", it) },
                    onValueChange = { viewModel.updateSelectedSpeed(it, SpeedCurvePreset.NONE) }
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 4.0f).forEach { spd ->
                        MiniPresetChip(
                            label = "${spd}x",
                            selected = selectedClip.speedCurvePreset == SpeedCurvePreset.NONE && abs(selectedClip.speed - spd) < 0.03f,
                            onClick = { viewModel.updateSelectedSpeed(spd, SpeedCurvePreset.NONE) }
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Pro Velocity Curve Ramps", fontSize = 11.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SpeedCurvePreset.entries.forEach { curve ->
                        MiniPresetChip(
                            label = curve.label,
                            selected = selectedClip.speedCurvePreset == curve,
                            accent = KeyframeGold,
                            onClick = { viewModel.updateSelectedSpeed(1.0f, curve, curve.defaultPoints) }
                        )
                    }
                }
            }
        } else {
            // Crop & Framing Card
            val tf = selectedClip.transform
            StudioSectionCard(
                title = "Crop & Framing",
                badge = if (selectedClip.crop.isIdentity) "Full" else "Cropped",
                onReset = if (!selectedClip.crop.isIdentity) {
                    { viewModel.setCropOnSelected(CropRect()) }
                } else null
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MiniPresetChip("Full Frame", selectedClip.crop.isIdentity) {
                        viewModel.setCropOnSelected(CropRect())
                    }
                    MiniPresetChip("Center 90%", abs(selectedClip.crop.left - 0.05f) < 0.01f) {
                        viewModel.setCropOnSelected(CropRect(0.05f, 0.05f, 0.95f, 0.95f))
                    }
                    MiniPresetChip("Cinema Tight", abs(selectedClip.crop.top - 0.12f) < 0.01f) {
                        viewModel.setCropOnSelected(CropRect(0.0f, 0.12f, 1.0f, 0.88f))
                    }
                    MiniPresetChip("Square Focus", abs(selectedClip.crop.left - 0.15f) < 0.01f) {
                        viewModel.setCropOnSelected(CropRect(0.15f, 0.15f, 0.85f, 0.85f))
                    }
                }
                ProParameterSlider(
                    label = "Crop Left",
                    value = selectedClip.crop.left,
                    defaultValue = 0f,
                    range = 0f..0.42f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { viewModel.setCropOnSelected(selectedClip.crop.copy(left = it)) }
                )
                ProParameterSlider(
                    label = "Crop Right",
                    value = selectedClip.crop.right,
                    defaultValue = 1f,
                    range = 0.58f..1f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { viewModel.setCropOnSelected(selectedClip.crop.copy(right = it)) }
                )
                ProParameterSlider(
                    label = "Crop Top",
                    value = selectedClip.crop.top,
                    defaultValue = 0f,
                    range = 0f..0.42f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { viewModel.setCropOnSelected(selectedClip.crop.copy(top = it)) }
                )
                ProParameterSlider(
                    label = "Crop Bottom",
                    value = selectedClip.crop.bottom,
                    defaultValue = 1f,
                    range = 0.58f..1f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { viewModel.setCropOnSelected(selectedClip.crop.copy(bottom = it)) }
                )
            }

            // Transform Card
            StudioSectionCard(
                title = "Scale, Position, Rotation & Opacity",
                onReset = if (!tf.isIdentity) {
                    { viewModel.updateSelectedTransform(ClipTransform()) }
                } else null
            ) {
                val signX = if (tf.scaleX < 0f) -1f else 1f
                val signY = if (tf.scaleY < 0f) -1f else 1f
                ProParameterSlider(
                    label = "Zoom / Scale",
                    value = abs(tf.scaleX),
                    defaultValue = 1.0f,
                    range = 0.25f..4.0f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { s ->
                        viewModel.updateSelectedTransform(tf.copy(scaleX = s * signX, scaleY = s * signY))
                    }
                )
                ProParameterSlider(
                    label = "Rotation",
                    value = tf.rotationDegrees,
                    defaultValue = 0f,
                    range = -180f..180f,
                    valueFormatter = { "${it.roundToInt()}°" },
                    onValueChange = { deg -> viewModel.updateSelectedTransform(tf.copy(rotationDegrees = deg)) }
                )
                ProParameterSlider(
                    label = "Position X",
                    value = tf.positionX,
                    defaultValue = 0f,
                    range = -1f..1f,
                    valueFormatter = { formatSigned100(it) },
                    onValueChange = { px -> viewModel.updateSelectedTransform(tf.copy(positionX = px)) }
                )
                ProParameterSlider(
                    label = "Position Y",
                    value = tf.positionY,
                    defaultValue = 0f,
                    range = -1f..1f,
                    valueFormatter = { formatSigned100(it) },
                    onValueChange = { py -> viewModel.updateSelectedTransform(tf.copy(positionY = py)) }
                )
                ProParameterSlider(
                    label = "Opacity",
                    value = tf.opacity,
                    defaultValue = 1f,
                    range = 0f..1f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { op -> viewModel.updateSelectedTransform(tf.copy(opacity = op)) }
                )
            }
        }
    }
}

// ============================================================================
// 2. FILTER STUDIO PANEL (32 Basic & Pro Filters + Strength + .CUBE 3D LUT)
// ============================================================================
@Composable
fun FilterStudioPanel(
    selectedClip: TimelineClip?,
    project: Project,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint(
            message = "Import a video or photo clip to apply Filters & 3D LUTs.",
            actionLabel = "Open Media Hub",
            onAction = { viewModel.setShowMediaImporterSheet(true) }
        )
        return
    }

    val grade = selectedClip.colorGrade
    val allPresets = remember(project.customColorPresets) {
        project.customColorPresets + ColorGradingEngine.builtInPresets
    }
    var selectedCategory by remember { mutableStateOf("All") }
    var savePresetDialog by remember { mutableStateOf(false) }

    val cubePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importCubeLutUriOnSelected(uri)
    }

    val categories = remember(project.customColorPresets) {
        buildList {
            add("All")
            if (project.customColorPresets.isNotEmpty()) add("Custom")
            addAll(ColorGradingEngine.filterCategories.drop(1))
        }
    }

    val visiblePresets = remember(allPresets, selectedCategory) {
        if (selectedCategory == "All") allPresets
        else allPresets.filter { it.category.equals(selectedCategory, ignoreCase = true) }
    }

    val activePreset = remember(grade.lutPresetId, allPresets) {
        allPresets.firstOrNull { it.id == grade.lutPresetId }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActiveClipTargetBar(
            clip = selectedClip,
            onApplyToAll = viewModel::applyColorGradeToAllClips,
            onReset = if (grade.lutPresetId != null || !grade.customCubeLutUri.isNullOrBlank()) {
                { viewModel.resetColorSectionOnSelected("filter") }
            } else null
        )

        // Category Filter Pills + .CUBE Import + Save Look
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { cat ->
                MiniPresetChip(
                    label = cat,
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat }
                )
            }
            MiniPresetChip(
                label = "+ .CUBE LUT",
                selected = !grade.customCubeLutUri.isNullOrBlank(),
                accent = KeyframeGold,
                onClick = { cubePicker.launch("*/*") }
            )
            MiniPresetChip(
                label = "Save Look",
                selected = false,
                accent = NeonViolet,
                onClick = { savePresetDialog = true }
            )
        }

        // Live Filter Strength Slider (visible whenever a Filter or .cube LUT is selected)
        if (activePreset != null || !grade.customCubeLutUri.isNullOrBlank()) {
            StudioSectionCard(
                title = activePreset?.let { "Filter: ${it.name}" } ?: "Custom 3D .CUBE LUT",
                badge = "${(grade.lutIntensity * 100).roundToInt()}%"
            ) {
                ProParameterSlider(
                    label = "Filter Strength",
                    value = grade.lutIntensity,
                    defaultValue = 1.0f,
                    range = 0f..1f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = viewModel::updateFilterIntensityOnSelected
                )
            }
        }

        // Visual Filter Swatch Cards Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            // "Original (None)" Card
            item(key = "filter_none") {
                val isNone = grade.lutPresetId == null && grade.customCubeLutUri.isNullOrBlank()
                FilterSwatchCard(
                    name = "Original",
                    category = "None",
                    primaryHex = 0xFF2B3346,
                    secondaryHex = 0xFF151924,
                    isSelected = isNone,
                    onClick = { viewModel.applyColorPresetOnSelected(null) }
                )
            }

            items(visiblePresets, key = { it.id }) { preset ->
                val isSelected = grade.lutPresetId == preset.id
                FilterSwatchCard(
                    name = preset.name,
                    category = preset.category,
                    primaryHex = preset.accentHex,
                    secondaryHex = preset.secondaryHex,
                    isSelected = isSelected,
                    onClick = { viewModel.applyColorPresetOnSelected(preset) }
                )
            }
        }
    }

    if (savePresetDialog) {
        var presetName by remember { mutableStateOf("My Studio Grade") }
        AlertDialog(
            onDismissRequest = { savePresetDialog = false },
            containerColor = PanelDark,
            title = { Text("Save Custom Filter Look") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Filter Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveCustomColorPreset(presetName)
                    savePresetDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { savePresetDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FilterSwatchCard(
    name: String,
    category: String,
    primaryHex: Long,
    secondaryHex: Long,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) ElectricCyan.copy(alpha = 0.14f) else PanelDark)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ElectricCyan else SurfaceStroke,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(primaryHex), Color(secondaryHex))
                    )
                ),
            contentAlignment = Alignment.BottomEnd
        ) {
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(16.dp)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = name,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (isSelected) ElectricCyan else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = category,
            fontSize = 9.sp,
            color = TextSecondary,
            maxLines = 1
        )
    }
}

// ============================================================================
// 3. SHARPEN, DETAIL & OPTICS STUDIO PANEL
// ============================================================================
@Composable
fun DetailAndSharpenPanel(
    selectedClip: TimelineClip?,
    project: Project,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint(
            message = "Import a video or photo clip to adjust Sharpness, Clarity & Detail.",
            actionLabel = "Open Media Hub",
            onAction = { viewModel.setShowMediaImporterSheet(true) }
        )
        return
    }

    val g = selectedClip.colorGrade

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActiveClipTargetBar(
            clip = selectedClip,
            onApplyToAll = viewModel::applyColorGradeToAllClips,
            onReset = if (g.hasSpatialDetailChanges || abs(g.dehaze) > 0.005f) {
                { viewModel.resetColorSectionOnSelected("detail") }
            } else null
        )

        // 1-Tap Pro Sharpen & Detail Quick Presets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniPresetChip("Crisp HD", false, ElectricCyan) { viewModel.applyDetailQuickPreset("crisp_hd") }
            MiniPresetChip("4K Micro-Detail", false, ElectricCyan) { viewModel.applyDetailQuickPreset("ultra_4k") }
            MiniPresetChip("Portrait Soft", false, NeonViolet) { viewModel.applyDetailQuickPreset("portrait_soft") }
            MiniPresetChip("Low-Light Clean", false, EmeraldBeat) { viewModel.applyDetailQuickPreset("low_light_clean") }
            MiniPresetChip("Analog 35mm", false, KeyframeGold) { viewModel.applyDetailQuickPreset("analog_grain") }
        }

        // Card 1: Pro Unsharp Mask (Amount, Radius, Edge Guard)
        StudioSectionCard(
            title = "Pro Sharpener (Unsharp Mask)",
            badge = if (g.sharpness > 0.005f) "+${(g.sharpness * 100).roundToInt()}" else "Off"
        ) {
            ProParameterSlider(
                label = "Sharpen Amount",
                value = g.sharpness,
                defaultValue = 0f,
                range = 0f..1f,
                valueFormatter = { "${(it * 100).roundToInt()}" },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(sharpness = it), "sharpness") }
            )
            ProParameterSlider(
                label = "Sharpen Radius",
                value = g.sharpenRadius,
                defaultValue = 0.35f,
                range = 0f..1f,
                valueFormatter = { String.format(Locale.US, "%.1f px", 0.5f + it * 2.5f) },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(sharpenRadius = it), "sharp_radius") }
            )
            ProParameterSlider(
                label = "Edge Guard (Skin/Noise Protection)",
                value = g.sharpenThreshold,
                defaultValue = 0.18f,
                range = 0f..1f,
                valueFormatter = { "${(it * 100).roundToInt()}" },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(sharpenThreshold = it), "sharp_thresh") }
            )
        }

        // Card 2: Micro-Detail, Clarity, Dehaze & Denoise
        StudioSectionCard(title = "Clarity, Structure & Noise Reduction") {
            ProParameterSlider(
                label = "Clarity (Midtone Punch)",
                value = g.clarity,
                defaultValue = 0f,
                range = -1f..1f,
                valueFormatter = { formatSigned100(it) },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(clarity = it), "clarity") }
            )
            ProParameterSlider(
                label = "Structure (Fine Texture)",
                value = g.structure,
                defaultValue = 0f,
                range = 0f..1f,
                valueFormatter = { "${(it * 100).roundToInt()}" },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(structure = it), "structure") }
            )
            ProParameterSlider(
                label = "Dehaze (Atmospheric Contrast)",
                value = g.dehaze,
                defaultValue = 0f,
                range = -0.5f..1f,
                valueFormatter = { formatSigned100(it) },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(dehaze = it), "dehaze") }
            )
            ProParameterSlider(
                label = "Noise Reduction (Denoise)",
                value = g.denoise,
                defaultValue = 0f,
                range = 0f..1f,
                valueFormatter = { "${(it * 100).roundToInt()}" },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(denoise = it), "denoise") }
            )
        }

        // Card 3: Optical Vignette & Film Grain
        StudioSectionCard(title = "Optical Vignette & Film Grain") {
            ProParameterSlider(
                label = "Vignette (Dark + / Bright -)",
                value = g.vignette,
                defaultValue = 0f,
                range = -1f..1f,
                valueFormatter = { formatSigned100(it) },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(vignette = it), "vignette") }
            )
            ProParameterSlider(
                label = "Vignette Feather",
                value = g.vignetteFeather,
                defaultValue = 0.55f,
                range = 0f..1f,
                valueFormatter = { "${(it * 100).roundToInt()}" },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(vignetteFeather = it), "vig_feather") }
            )
            ProParameterSlider(
                label = "35mm Film Grain",
                value = g.grain,
                defaultValue = 0f,
                range = 0f..1f,
                valueFormatter = { "${(it * 100).roundToInt()}" },
                onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(grain = it), "grain") }
            )
        }
    }
}

// ============================================================================
// 4. PRO ADJUSTMENTS & COLOR GRADING PANEL (Light, Color, HSL, Curves & Wheels)
// ============================================================================
@Composable
fun ColorGradingPanel(
    selectedClip: TimelineClip?,
    project: Project,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint(
            message = "Import a video or photo clip to adjust Light, Color, HSL & Curves.",
            actionLabel = "Open Media Hub",
            onAction = { viewModel.setShowMediaImporterSheet(true) }
        )
        return
    }

    val g = selectedClip.colorGrade
    var subTab by remember { mutableIntStateOf(0) } // 0=Light, 1=Color, 2=HSL, 3=Curves & Wheels

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActiveClipTargetBar(
            clip = selectedClip,
            onApplyToAll = viewModel::applyColorGradeToAllClips,
            onReset = if (!g.isIdentity) {
                {
                    val sec = when (subTab) {
                        0 -> "light"
                        1 -> "color"
                        2 -> "hsl"
                        else -> "curves"
                    }
                    viewModel.resetColorSectionOnSelected(sec)
                }
            } else null
        )

        StudioSegmentedRow(
            options = listOf("Light", "Color", "HSL", "Curves & Wheels"),
            selectedIndex = subTab,
            onSelect = { subTab = it }
        )

        when (subTab) {
            0 -> {
                StudioSectionCard(title = "Tone & Exposure") {
                    ProParameterSlider(
                        label = "Exposure",
                        value = g.exposure,
                        defaultValue = 0f,
                        range = -2f..2f,
                        valueFormatter = { String.format(Locale.US, "%+.2f EV", it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(exposure = it), "exposure") }
                    )
                    ProParameterSlider(
                        label = "Brightness",
                        value = g.brightness,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(brightness = it), "brightness") }
                    )
                    ProParameterSlider(
                        label = "Contrast",
                        value = g.contrast,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(contrast = it), "contrast") }
                    )
                    ProParameterSlider(
                        label = "Highlights",
                        value = g.highlights,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(highlights = it), "highlights") }
                    )
                    ProParameterSlider(
                        label = "Shadows",
                        value = g.shadows,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(shadows = it), "shadows") }
                    )
                    ProParameterSlider(
                        label = "Whites",
                        value = g.whites,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(whites = it), "whites") }
                    )
                    ProParameterSlider(
                        label = "Blacks",
                        value = g.blacks,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(blacks = it), "blacks") }
                    )
                    ProParameterSlider(
                        label = "Gamma",
                        value = g.gamma,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(gamma = it), "gamma") }
                    )
                    ProParameterSlider(
                        label = "Matte Black Fade",
                        value = g.fade,
                        defaultValue = 0f,
                        range = 0f..1f,
                        valueFormatter = { "${(it * 100).roundToInt()}" },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(fade = it), "fade") }
                    )
                }
            }

            1 -> {
                StudioSectionCard(title = "Color Balance & Saturation") {
                    ProParameterSlider(
                        label = "Saturation",
                        value = g.saturation,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(saturation = it), "saturation") }
                    )
                    ProParameterSlider(
                        label = "Vibrance (Skin-Safe Boost)",
                        value = g.vibrance,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(vibrance = it), "vibrance") }
                    )
                    ProParameterSlider(
                        label = "Warmth (Temperature)",
                        value = g.temperature,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(temperature = it), "temp") }
                    )
                    ProParameterSlider(
                        label = "Tint (Green ↔ Magenta)",
                        value = g.tint,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(tint = it), "tint") }
                    )
                    ProParameterSlider(
                        label = "Global Hue Shift",
                        value = g.hueShift,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { "${(it * 180f).roundToInt()}°" },
                        onValueChange = { viewModel.updateColorGradeOnSelected(g.copy(hueShift = it), "hue_shift") }
                    )
                }
            }

            2 -> {
                var selectedBandIdx by remember { mutableIntStateOf(0) }
                val bandNames = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")
                val bandColors = listOf(
                    Color(0xFFFF1744), Color(0xFFFF9100), Color(0xFFFFEA00), Color(0xFF00E676),
                    Color(0xFF00E5FF), Color(0xFF2979FF), Color(0xFF7C4DFF), Color(0xFFF50057)
                )
                val currentBands = if (g.hslBands.size == 8) g.hslBands else ColorGradeConfig.defaultHslBands()
                val activeBand = currentBands[selectedBandIdx]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                bandColors.forEachIndexed { idx, col ->
                    val band = currentBands[idx]
                    val modified = !band.isNeutral
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { selectedBandIdx = idx }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    width = if (selectedBandIdx == idx) 3.dp else 1.dp,
                                    color = if (selectedBandIdx == idx) Color.White else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (modified) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(bandNames[idx], fontSize = 9.sp, color = if (selectedBandIdx == idx) TextPrimary else TextSecondary)
                    }
                }
            }

            fun updateBand(updated: HslBandAdjustment) {
                val nextList = currentBands.toMutableList().apply { set(selectedBandIdx, updated) }
                viewModel.updateColorGradeOnSelected(g.copy(hslBands = nextList), "hsl_$selectedBandIdx")
            }

            StudioSectionCard(
                title = "${bandNames[selectedBandIdx]} Channel Mixer",
                onReset = {
                    updateBand(activeBand.copy(hueShift = 0f, saturationShift = 0f, luminanceShift = 0f))
                }
            ) {
                ProParameterSlider(
                    label = "Hue Shift",
                    value = activeBand.hueShift,
                    defaultValue = 0f,
                    range = -1f..1f,
                    valueFormatter = { "${(it * 30f).roundToInt()}°" },
                    onValueChange = { updateBand(activeBand.copy(hueShift = it)) }
                )
                ProParameterSlider(
                    label = "Saturation",
                    value = activeBand.saturationShift,
                    defaultValue = 0f,
                    range = -1f..1f,
                    valueFormatter = { formatSigned100(it) },
                    onValueChange = { updateBand(activeBand.copy(saturationShift = it)) }
                )
                ProParameterSlider(
                    label = "Luminance",
                    value = activeBand.luminanceShift,
                    defaultValue = 0f,
                    range = -1f..1f,
                    valueFormatter = { formatSigned100(it) },
                    onValueChange = { updateBand(activeBand.copy(luminanceShift = it)) }
                )
            }
        }

            3 -> {
                var channelIdx by remember { mutableIntStateOf(0) } // 0=Master, 1=R, 2=G, 3=B
                val channelLabels = listOf("Master", "Red", "Green", "Blue")
                val channelColors = listOf(Color.White, Color(0xFFFF5252), Color(0xFF69F0AE), Color(0xFF448AFF))

                StudioSectionCard(
                    title = "RGB Tone Curves",
                    onReset = if (!g.curves.isIdentity) {
                        { viewModel.resetColorSectionOnSelected("curves") }
                    } else null
                ) {
                    // 1-tap Curve Presets
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MiniPresetChip("Linear", g.curves.isIdentity) {
                            viewModel.updateColorGradeOnSelected(g.copy(curves = RgbCurvesConfig()), "curve_preset")
                        }
                        MiniPresetChip("Film S-Curve", false, KeyframeGold) {
                            viewModel.updateColorGradeOnSelected(g.copy(curves = RgbCurvesConfig(master = RgbCurvesConfig.sCurvePoints)), "curve_preset")
                        }
                        MiniPresetChip("Matte Lift", false, NeonViolet) {
                            viewModel.updateColorGradeOnSelected(g.copy(curves = RgbCurvesConfig(master = RgbCurvesConfig.matteLiftPoints)), "curve_preset")
                        }
                        MiniPresetChip("High Contrast", false, ElectricCyan) {
                            viewModel.updateColorGradeOnSelected(g.copy(curves = RgbCurvesConfig(master = RgbCurvesConfig.highContrastPoints)), "curve_preset")
                        }
                        MiniPresetChip("Soft Roll-Off", false, EmeraldBeat) {
                            viewModel.updateColorGradeOnSelected(g.copy(curves = RgbCurvesConfig(master = RgbCurvesConfig.softHighlightRollPoints)), "curve_preset")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        channelLabels.forEachIndexed { idx, name ->
                            MiniPresetChip(
                                label = name,
                                selected = channelIdx == idx,
                                accent = channelColors[idx],
                                onClick = { channelIdx = idx }
                            )
                        }
                    }

                    val activePoints = when (channelIdx) {
                        0 -> g.curves.master
                        1 -> g.curves.red
                        2 -> g.curves.green
                        else -> g.curves.blue
                    }

                    InteractiveCurveBox(
                        points = activePoints,
                        strokeColor = channelColors[channelIdx],
                        onUpdatePoints = { updatedPts ->
                            val nextCurves = when (channelIdx) {
                                0 -> g.curves.copy(master = updatedPts)
                                1 -> g.curves.copy(red = updatedPts)
                                2 -> g.curves.copy(green = updatedPts)
                                else -> g.curves.copy(blue = updatedPts)
                            }
                            viewModel.updateColorGradeOnSelected(g.copy(curves = nextCurves), "curve_$channelIdx")
                        }
                    )
                }

                // 3-Way Color Wheels Card
                var wheelIdx by remember { mutableIntStateOf(0) }
                StudioSectionCard(
                    title = "3-Way Color Wheels (Lift / Gamma / Gain)",
                    onReset = if (!g.colorWheels.isIdentity) {
                        { viewModel.resetColorSectionOnSelected("wheels") }
                    } else null
                ) {
                    StudioSegmentedRow(
                        options = listOf("Shadows", "Midtones", "Highlights"),
                        selectedIndex = wheelIdx,
                        onSelect = { wheelIdx = it }
                    )
                    val activeTint = when (wheelIdx) {
                        0 -> g.colorWheels.shadows
                        1 -> g.colorWheels.midtones
                        else -> g.colorWheels.highlights
                    }
                    fun updateTint(next: ColorWheelTint) {
                        val nextWheels = when (wheelIdx) {
                            0 -> g.colorWheels.copy(shadows = next)
                            1 -> g.colorWheels.copy(midtones = next)
                            else -> g.colorWheels.copy(highlights = next)
                        }
                        viewModel.updateColorGradeOnSelected(g.copy(colorWheels = nextWheels), "wheel_$wheelIdx")
                    }
                    ProParameterSlider(
                        label = "Red Balance",
                        value = activeTint.rOffset,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { updateTint(activeTint.copy(rOffset = it)) }
                    )
                    ProParameterSlider(
                        label = "Green Balance",
                        value = activeTint.gOffset,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { updateTint(activeTint.copy(gOffset = it)) }
                    )
                    ProParameterSlider(
                        label = "Blue Balance",
                        value = activeTint.bOffset,
                        defaultValue = 0f,
                        range = -1f..1f,
                        valueFormatter = { formatSigned100(it) },
                        onValueChange = { updateTint(activeTint.copy(bOffset = it)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveCurveBox(
    points: List<CurvePoint>,
    strokeColor: Color,
    onUpdatePoints: (List<CurvePoint>) -> Unit
) {
    val safePoints = if (points.size >= 3) points else RgbCurvesConfig.linearPoints
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0A0D14))
            .border(1.dp, SurfaceStroke, RoundedCornerShape(12.dp))
            .pointerInput(safePoints) {
                detectDragGestures { change, _ ->
                    change.consume()
                    if (size.width <= 0 || size.height <= 0) return@detectDragGestures
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    val nearestIdx = safePoints.indices.minByOrNull { i -> abs(safePoints[i].x - nx) } ?: 1
                    val lockedX = when (nearestIdx) {
                        0 -> 0f
                        safePoints.lastIndex -> 1f
                        else -> nx.coerceIn(0.08f, 0.92f)
                    }
                    val next = safePoints.toMutableList().apply {
                        set(nearestIdx, CurvePoint(lockedX, ny))
                    }.sortedBy { it.x }
                    onUpdatePoints(next)
                }
            }
    ) {
        for (i in 1..3) {
            val gx = size.width * (i / 4f)
            val gy = size.height * (i / 4f)
            drawLine(SurfaceStroke.copy(alpha = 0.5f), Offset(gx, 0f), Offset(gx, size.height), 1f)
            drawLine(SurfaceStroke.copy(alpha = 0.5f), Offset(0f, gy), Offset(size.width, gy), 1f)
        }
        drawLine(SurfaceStroke, Offset(0f, size.height), Offset(size.width, 0f), 1.5f)

        val path = Path()
        val steps = 48
        for (s in 0..steps) {
            val t = s / steps.toFloat()
            val yVal = ColorGradingEngine.sampleCurve(safePoints, t)
            val px = t * size.width
            val py = (1f - yVal) * size.height
            if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, strokeColor, style = Stroke(width = 3.2f))

        safePoints.forEach { pt ->
            val cx = pt.x * size.width
            val cy = (1f - pt.y) * size.height
            drawCircle(Color.Black, radius = 7.5f, center = Offset(cx, cy))
            drawCircle(strokeColor, radius = 5.5f, center = Offset(cx, cy))
        }
    }
}

// ============================================================================
// 5. GPU EFFECTS STUDIO PANEL (All 32 OpenGL ES 2.0 Effects + 3-Axis Customize)
// ============================================================================
@Composable
fun EffectsStudioPanel(
    selectedClip: TimelineClip?,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint(
            message = "Import a video or photo clip to add GPU Shader Effects.",
            actionLabel = "Open Media Hub",
            onAction = { viewModel.setShowMediaImporterSheet(true) }
        )
        return
    }

    var selectedCategory by remember { mutableStateOf<EffectCategory?>(null) }
    var editingEffectId by remember(selectedClip.id, selectedClip.effects.size) {
        mutableStateOf(selectedClip.effects.lastOrNull()?.id)
    }

    val activeEditingEffect = selectedClip.effects.firstOrNull { it.id == editingEffectId }
        ?: selectedClip.effects.lastOrNull()

    val filteredEffects = remember(selectedCategory) {
        if (selectedCategory == null) EffectType.entries
        else EffectType.entries.filter { it.category == selectedCategory }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ActiveClipTargetBar(
            clip = selectedClip,
            onApplyToAll = if (selectedClip.effects.isNotEmpty()) viewModel::applyEffectsToAllClips else null,
            onReset = if (selectedClip.effects.isNotEmpty()) viewModel::clearEffectsOnSelected else null
        )

        // Stacked Effects Customizer Card (when at least 1 effect is applied)
        if (selectedClip.effects.isNotEmpty() && activeEditingEffect != null) {
            StudioSectionCard(
                title = "Customize: ${activeEditingEffect.type.label}",
                badge = "${selectedClip.effects.size} Stacked"
            ) {
                // Active stack pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedClip.effects.forEachIndexed { idx, fx ->
                        val isCurrent = fx.id == activeEditingEffect.id
                        Surface(
                            color = if (isCurrent) NeonViolet.copy(alpha = 0.25f) else SurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (isCurrent) NeonViolet else SurfaceStroke),
                            modifier = Modifier.clickable { editingEffectId = fx.id }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${idx + 1}. ${fx.type.label}",
                                    fontSize = 11.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (fx.enabled) TextPrimary else TextSecondary
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = CoralRed,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { viewModel.removeEffectFromSelected(fx.id) }
                                )
                            }
                        }
                    }
                }

                // Enable toggle + Reorder buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = activeEditingEffect.enabled,
                            onCheckedChange = { en ->
                                viewModel.updateEffectOnSelected(activeEditingEffect.copy(enabled = en))
                            }
                        )
                        Text("Effect Enabled", fontSize = 11.sp, color = TextSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MiniPresetChip("◀ Earlier", false) {
                            viewModel.reorderEffectOnSelected(activeEditingEffect.id, -1)
                        }
                        MiniPresetChip("Later ▶", false) {
                            viewModel.reorderEffectOnSelected(activeEditingEffect.id, +1)
                        }
                    }
                }

                // 3 Dedicated Named Customization Sliders per Effect
                ProParameterSlider(
                    label = activeEditingEffect.type.primaryLabel,
                    value = activeEditingEffect.intensity,
                    defaultValue = 0.65f,
                    range = 0f..1f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { v ->
                        viewModel.updateEffectOnSelected(activeEditingEffect.copy(intensity = v))
                    }
                )
                ProParameterSlider(
                    label = activeEditingEffect.type.secondaryLabel,
                    value = activeEditingEffect.secondaryParam,
                    defaultValue = 0.50f,
                    range = 0f..1f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { v ->
                        viewModel.updateEffectOnSelected(activeEditingEffect.copy(secondaryParam = v))
                    }
                )
                ProParameterSlider(
                    label = activeEditingEffect.type.tertiaryLabel,
                    value = activeEditingEffect.tertiaryParam,
                    defaultValue = 0.50f,
                    range = 0f..1f,
                    valueFormatter = { "${(it * 100).roundToInt()}%" },
                    onValueChange = { v ->
                        viewModel.updateEffectOnSelected(activeEditingEffect.copy(tertiaryParam = v))
                    }
                )
            }
        }

        // Category Filter Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniPresetChip(
                label = "All (${EffectType.entries.size})",
                selected = selectedCategory == null,
                accent = NeonViolet,
                onClick = { selectedCategory = null }
            )
            EffectCategory.entries.forEach { cat ->
                MiniPresetChip(
                    label = cat.label,
                    selected = selectedCategory == cat,
                    accent = NeonViolet,
                    onClick = { selectedCategory = cat }
                )
            }
        }

        // Clean 3-Column Responsive Effect Catalog
        val chunked = remember(filteredEffects) { filteredEffects.chunked(3) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chunked.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { fxType ->
                        val existing = selectedClip.effects.firstOrNull { it.type == fxType }
                        val isApplied = existing != null
                        val catAccent = Color(fxType.badgeColorHex)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isApplied) catAccent.copy(alpha = 0.18f) else PanelDark)
                                .border(
                                    width = if (isApplied) 1.5.dp else 1.dp,
                                    color = if (isApplied) catAccent else SurfaceStroke,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    if (existing != null) {
                                        editingEffectId = existing.id
                                    } else {
                                        viewModel.addEffectToSelected(fxType)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(catAccent)
                                )
                                if (isApplied) {
                                    Text("ACTIVE", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = catAccent)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = fxType.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = fxType.category.label,
                                fontSize = 9.sp,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    repeat(3 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ============================================================================
// SHARED ERGONOMIC STUDIO COMPONENTS (Used across Part 1 & Part 2)
// ============================================================================

@Composable
fun ActiveClipTargetBar(
    clip: TimelineClip,
    onApplyToAll: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PanelDark)
            .border(1.dp, SurfaceStroke, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Default.Movie, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
            Text(
                text = clip.sourceName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (clip.sourceWidth > 0 && clip.sourceHeight > 0) {
                Text(
                    text = "• ${clip.sourceWidth}×${clip.sourceHeight}",
                    fontSize = 10.sp,
                    color = TextSecondary
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onReset != null) {
                Text(
                    text = "Reset",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CoralRed,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onReset)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (onApplyToAll != null) {
                Surface(
                    color = ElectricCyan.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                    modifier = Modifier.clickable(onClick = onApplyToAll)
                ) {
                    Text(
                        text = "Apply All",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StudioSegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PanelDark)
            .border(1.dp, SurfaceStroke, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) ElectricCyan.copy(alpha = 0.20f) else Color.Transparent)
                    .clickable { onSelect(idx) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (selected) ElectricCyan else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun StudioSectionCard(
    title: String,
    badge: String? = null,
    onReset: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelDark),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceStroke)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (badge != null) {
                        Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ElectricCyan)
                    }
                    if (onReset != null) {
                        Text(
                            text = "Reset",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CoralRed,
                            modifier = Modifier.clickable(onClick = onReset)
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun MiniPresetChip(
    label: String,
    selected: Boolean,
    accent: Color = ElectricCyan,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) accent.copy(alpha = 0.22f) else SurfaceElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) accent else SurfaceStroke),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else TextPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun ProParameterSlider(
    label: String,
    value: Float,
    defaultValue: Float = 0f,
    range: ClosedFloatingPointRange<Float>,
    valueFormatter: (Float) -> String = { String.format(Locale.US, "%.2f", it) },
    onValueChange: (Float) -> Unit
) {
    val clamped = value.coerceIn(range.start, range.endInclusive)
    val isModified = abs(clamped - defaultValue) > ((range.endInclusive - range.start) * 0.008f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isModified) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isModified) TextPrimary else TextSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isModified) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset $label",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onValueChange(defaultValue) }
                    )
                }
                Surface(
                    color = if (isModified) ElectricCyan.copy(alpha = 0.16f) else SurfaceElevated,
                    shape = RoundedCornerShape(5.dp)
                ) {
                    Text(
                        text = valueFormatter(clamped),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isModified) ElectricCyan else TextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Slider(
            value = clamped,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = if (isModified) ElectricCyan else TextPrimary,
                activeTrackColor = if (isModified) ElectricCyan else TextSecondary.copy(alpha = 0.6f),
                inactiveTrackColor = SurfaceStroke
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}

@Composable
fun ActionPill(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PanelDark)
            .border(1.dp, SurfaceStroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val defaultVal = if (0f in range) 0f else range.start
    ProParameterSlider(
        label = label,
        value = value,
        defaultValue = defaultVal,
        range = range,
        onValueChange = onValueChange
    )
}

@Composable
fun EmptySelectionHint(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = TextSecondary, fontSize = 12.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(actionLabel, color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun formatSigned100(v: Float): String {
    val n = (v * 100f).roundToInt()
    return if (n > 0) "+$n" else "$n"
}
