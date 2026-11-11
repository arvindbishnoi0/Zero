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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.color.ColorGradingEngine
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorViewModel
import java.util.Locale
import kotlin.math.abs

@Composable
fun ClipEditPanel(
    selectedClip: TimelineClip?,
    project: Project,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint("Select or import a clip on the timeline to edit.")
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Primary VN-style Action Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionPill(Icons.Default.ContentCut, "Split", onClick = viewModel::splitSelectedAtPlayhead)
            ActionPill(Icons.Default.CallSplit, "Cut Mid", onClick = viewModel::cutMiddleTwentyPercentOfSelected)
            ActionPill(Icons.Default.AcUnit, "Freeze", onClick = viewModel::insertFreezeFrame)
            ActionPill(Icons.Default.ContentCopy, "Duplicate", onClick = viewModel::duplicateSelectedClip)
            ActionPill(Icons.Default.MergeType, "Merge Track", onClick = viewModel::mergeAdjacentOnCurrentTrack)
            ActionPill(Icons.Default.Rotate90DegreesCw, "Rotate 90°", onClick = { viewModel.rotateSelected90(true) })
            ActionPill(Icons.Default.Flip, "Flip H", onClick = viewModel::flipSelectedHorizontal)
            ActionPill(Icons.Default.SwapVert, "Flip V", onClick = viewModel::flipSelectedVertical)
            ActionPill(
                Icons.Default.Replay,
                if (selectedClip.isReversed) "Reversed ✓" else "Reverse",
                onClick = viewModel::toggleReverseSelected
            )
            ActionPill(Icons.Default.KeyboardArrowLeft, "Move ◀", onClick = { viewModel.moveSelectedClipOrder(-1) })
            ActionPill(Icons.Default.KeyboardArrowRight, "Move ▶", onClick = { viewModel.moveSelectedClipOrder(1) })
            ActionPill(Icons.Default.Delete, "Delete", tint = CoralRed, onClick = viewModel::deleteSelectedClip)
        }

        // Precision Trim RangeSlider
        Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
            Column(Modifier.padding(12.dp)) {
                val maxDur = selectedClip.sourceDurationMs.coerceAtLeast(200L).toFloat()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Trim Clip", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        "${selectedClip.trimStartMs}ms – ${selectedClip.trimEndMs}ms (${selectedClip.timelineDurationMs}ms)",
                        fontSize = 11.sp,
                        color = ElectricCyan
                    )
                }
                RangeSlider(
                    value = selectedClip.trimStartMs.toFloat()..selectedClip.trimEndMs.toFloat(),
                    onValueChange = { range ->
                        viewModel.trimSelectedClip(range.start.toLong(), range.endInclusive.toLong())
                    },
                    valueRange = 0f..maxDur
                )
            }
        }

        // Speed & VN Speed Curves Card
        Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed & Ramps", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        text = if (selectedClip.speedCurvePreset != SpeedCurvePreset.NONE) {
                            "Curve: ${selectedClip.speedCurvePreset.label}"
                        } else String.format(Locale.US, "%.2fx", selectedClip.speed),
                        fontSize = 12.sp,
                        color = KeyframeGold
                    )
                }

                Slider(
                    value = selectedClip.speed,
                    onValueChange = { spd ->
                        viewModel.updateSelectedSpeed(spd, SpeedCurvePreset.NONE)
                    },
                    valueRange = 0.1f..6.0f
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f, 4.0f).forEach { presetSpd ->
                        AssistChip(
                            onClick = { viewModel.updateSelectedSpeed(presetSpd, SpeedCurvePreset.NONE) },
                            label = { Text("${presetSpd}x", fontSize = 11.sp) }
                        )
                    }
                }

                Text("Speed Curves (VN Pro Ramps):", fontSize = 11.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SpeedCurvePreset.entries.filter { it != SpeedCurvePreset.CUSTOM }.forEach { curve ->
                        FilterChip(
                            selected = selectedClip.speedCurvePreset == curve,
                            onClick = { viewModel.updateSelectedSpeed(selectedClip.speed, curve) },
                            label = { Text(curve.label, fontSize = 11.sp) }
                        )
                    }
                }

                if (selectedClip.speedCurvePreset != SpeedCurvePreset.NONE) {
                    val pts = selectedClip.speedCurvePreset.defaultPoints
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0B0E17))
                            .padding(8.dp)
                    ) {
                        val path = Path()
                        for (i in 0..40) {
                            val u = i / 40f
                            val spd = TimelineClip.sampleSpeedCurve(pts, u)
                            val x = u * size.width
                            val y = size.height * (1f - (spd / 5f).coerceIn(0.05f, 0.95f))
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = KeyframeGold, style = Stroke(width = 3f))
                    }
                }
            }
        }

        // Crop & Transform Controls
        Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Crop, Scale & Opacity", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "FREE" to CropRect(0f, 0f, 1f, 1f, "FREE"),
                        "Center 85%" to CropRect(0.075f, 0.075f, 0.925f, 0.925f, "85%"),
                        "Zoom 70%" to CropRect(0.15f, 0.15f, 0.85f, 0.85f, "70%"),
                        "Cinema Scope" to CropRect(0f, 0.14f, 1f, 0.86f, "21:9"),
                        "Square" to CropRect(0f, 0.2f, 1f, 0.8f, "1:1")
                    ).forEach { (label, rect) ->
                        FilterChip(
                            selected = selectedClip.crop.aspectPresetId == rect.aspectPresetId,
                            onClick = { viewModel.setCropOnSelected(rect) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                LabeledSlider(
                    label = "Scale",
                    value = selectedClip.transform.scaleX,
                    range = 0.25f..3.5f,
                    onValueChange = { s ->
                        viewModel.updateSelectedTransform(selectedClip.transform.copy(scaleX = s, scaleY = s))
                    }
                )
                LabeledSlider(
                    label = "Rotation",
                    value = selectedClip.transform.rotationDegrees,
                    range = -180f..180f,
                    onValueChange = { deg ->
                        viewModel.updateSelectedTransform(selectedClip.transform.copy(rotationDegrees = deg))
                    }
                )
                LabeledSlider(
                    label = "Opacity",
                    value = selectedClip.transform.opacity,
                    range = 0f..1f,
                    onValueChange = { op ->
                        viewModel.updateSelectedTransform(selectedClip.transform.copy(opacity = op))
                    }
                )
            }
        }

        // Multi-Track Layer Adder + Timeline Mode Toggles
        Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Multi-Layer Tracks & Timeline", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(onClick = viewModel::toggleMagneticTimeline, label = { Text("Magnetic: ${if (project.magneticTimeline) "ON" else "OFF"}") })
                    AssistChip(onClick = viewModel::toggleSnapping, label = { Text("Snapping: ${if (project.snappingEnabled) "ON" else "OFF"}") })
                    AssistChip(onClick = { viewModel.nudgeClipPositionMs(-200L) }, label = { Text("-200ms") })
                    AssistChip(onClick = { viewModel.nudgeClipPositionMs(200L) }, label = { Text("+200ms") })
                    AssistChip(onClick = { viewModel.addTrack(TrackType.VIDEO_PIP) }, label = { Text("+ PIP Track") })
                    AssistChip(onClick = { viewModel.addTrack(TrackType.AUDIO_MUSIC) }, label = { Text("+ Audio Track") })
                    AssistChip(onClick = { viewModel.addTrack(TrackType.OVERLAY) }, label = { Text("+ Overlay Track") })
                }
            }
        }
    }
}

@Composable
fun EffectsStudioPanel(
    selectedClip: TimelineClip?,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint("Select a video or image clip to apply GPU Shader Effects.")
        return
    }

    var selectedCat by remember { mutableStateOf(EffectCategory.SHARPEN_AND_BLUR) }
    val effectsInCat = remember(selectedCat) {
        EffectType.entries.filter { it.category == selectedCat }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Category Selector
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EffectCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCat == cat,
                    onClick = { selectedCat = cat },
                    label = { Text(cat.label, fontSize = 11.sp) }
                )
            }
        }

        // Effect Picker Cards (Tap to stack onto clip!)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(effectsInCat) { fxType ->
                val accent = Color(fxType.badgeColorHex)
                val isApplied = selectedClip.effects.any { it.type == fxType }
                Column(
                    modifier = Modifier
                        .width(104.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isApplied) accent.copy(alpha = 0.22f) else PanelDark)
                        .border(1.dp, if (isApplied) accent else SurfaceStroke, RoundedCornerShape(14.dp))
                        .clickable { viewModel.addEffectToSelected(fxType) }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = accent)
                    Spacer(Modifier.height(6.dp))
                    Text(fxType.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("+ Stack FX", fontSize = 9.sp, color = TextSecondary)
                }
            }
        }

        // Active Multi-Effect Stack Inspector
        if (selectedClip.effects.isEmpty()) {
            Text(
                "No GPU effects stacked yet. Tap 'Pro Sharpen', 'Neon Edge Glow', 'VHS Tape' or any effect above to stack multiple effects!",
                fontSize = 11.sp,
                color = TextSecondary
            )
        } else {
            Text("Stacked Clip Effects (${selectedClip.effects.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ElectricCyan)
            selectedClip.effects.forEach { applied ->
                Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                    Column(Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = applied.enabled,
                                    onCheckedChange = { en ->
                                        viewModel.updateEffectOnSelected(applied.copy(enabled = en))
                                    }
                                )
                                Text(applied.type.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            IconButton(
                                onClick = { viewModel.removeEffectFromSelected(applied.id) },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = CoralRed, modifier = Modifier.size(16.dp))
                            }
                        }

                        LabeledSlider(
                            label = "Intensity",
                            value = applied.intensity,
                            range = 0f..1f,
                            onValueChange = { v -> viewModel.updateEffectOnSelected(applied.copy(intensity = v)) }
                        )
                        LabeledSlider(
                            label = applied.type.secondaryLabel,
                            value = applied.secondaryParam,
                            range = 0f..1f,
                            onValueChange = { v -> viewModel.updateEffectOnSelected(applied.copy(secondaryParam = v)) }
                        )
                    }
                }
            }
        }
    }
}

private enum class ColorSubPanel(val label: String) {
    ADJUST("Adjust"),
    PRESETS_LUT("LUT & Presets"),
    HSL("HSL"),
    CURVES("RGB Curves"),
    WHEELS("Color Wheels")
}

@Composable
fun ColorGradingPanel(
    selectedClip: TimelineClip?,
    project: Project,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint("Select a clip to grade colors, curves, HSL & 3D LUTs.")
        return
    }

    val grade = selectedClip.colorGrade
    var subPanel by remember { mutableStateOf(ColorSubPanel.ADJUST) }
    var savePresetDialog by remember { mutableStateOf(false) }

    val cubeLutLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importCubeLutUriOnSelected(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ColorSubPanel.entries.forEach { tab ->
                FilterChip(
                    selected = subPanel == tab,
                    onClick = { subPanel = tab },
                    label = { Text(tab.label, fontSize = 11.sp) }
                )
            }
            AssistChip(
                onClick = { viewModel.updateColorGradeOnSelected(ColorGradeConfig()) },
                label = { Text("Reset", fontSize = 11.sp, color = CoralRed) }
            )
        }

        when (subPanel) {
            ColorSubPanel.ADJUST -> {
                Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabeledSlider("Sharpness", grade.sharpness, 0f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(sharpness = it))
                        }
                        LabeledSlider("Exposure (EV)", grade.exposure, -2f..2f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(exposure = it))
                        }
                        LabeledSlider("Brightness", grade.brightness, -1f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(brightness = it))
                        }
                        LabeledSlider("Contrast", grade.contrast, -1f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(contrast = it))
                        }
                        LabeledSlider("Saturation", grade.saturation, -1f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(saturation = it))
                        }
                        LabeledSlider("Temperature", grade.temperature, -1f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(temperature = it))
                        }
                        LabeledSlider("Tint", grade.tint, -1f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(tint = it))
                        }
                        LabeledSlider("Highlights", grade.highlights, -1f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(highlights = it))
                        }
                        LabeledSlider("Shadows", grade.shadows, -1f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(shadows = it))
                        }
                        LabeledSlider("Matte Fade", grade.fade, 0f..1f) {
                            viewModel.updateColorGradeOnSelected(grade.copy(fade = it))
                        }
                    }
                }
            }

            ColorSubPanel.PRESETS_LUT -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { cubeLutLauncher.launch("*/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import .cube 3D LUT", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { savePresetDialog = true }) {
                        Text("Save Preset", fontSize = 12.sp)
                    }
                }

                LabeledSlider("Look / LUT Intensity", grade.lutIntensity, 0f..1f) {
                    viewModel.updateColorGradeOnSelected(grade.copy(lutIntensity = it))
                }

                val allPresets = project.customColorPresets + ColorGradingEngine.builtInPresets
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(allPresets, key = { it.id }) { preset ->
                        val active = grade.lutPresetId == preset.id
                        val accent = Color(preset.accentHex)
                        Column(
                            modifier = Modifier
                                .width(110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) accent.copy(alpha = 0.24f) else PanelDark)
                                .border(1.dp, if (active) accent else SurfaceStroke, RoundedCornerShape(12.dp))
                                .clickable { viewModel.applyColorPresetOnSelected(preset) }
                                .padding(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(accent)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(preset.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(preset.category, fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                }
            }

            ColorSubPanel.HSL -> {
                var selectedBand by remember { mutableStateOf(HslBand.RED) }
                val activeAdj = grade.hslBands.firstOrNull { it.band == selectedBand } ?: HslBandAdjustment(selectedBand)

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HslBand.entries.forEach { band ->
                        val c = Color(band.colorHex)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (selectedBand == band) 3.dp else 1.dp,
                                    color = if (selectedBand == band) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedBand = band }
                        )
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${selectedBand.label} Channel HSL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        fun updateBand(updated: HslBandAdjustment) {
                            val nextList = grade.hslBands.map { if (it.band == selectedBand) updated else it }
                            viewModel.updateColorGradeOnSelected(grade.copy(hslBands = nextList))
                        }
                        LabeledSlider("Hue Shift", activeAdj.hueShift, -1f..1f) {
                            updateBand(activeAdj.copy(hueShift = it))
                        }
                        LabeledSlider("Saturation", activeAdj.saturationShift, -1f..1f) {
                            updateBand(activeAdj.copy(saturationShift = it))
                        }
                        LabeledSlider("Luminance", activeAdj.luminanceShift, -1f..1f) {
                            updateBand(activeAdj.copy(luminanceShift = it))
                        }
                    }
                }
            }

            ColorSubPanel.CURVES -> {
                var channelIndex by remember { mutableIntStateOf(0) } // 0=Master, 1=R, 2=G, 3=B
                val labels = listOf("Master" to Color.White, "Red" to CoralRed, "Green" to EmeraldBeat, "Blue" to ElectricCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { idx, (name, col) ->
                        FilterChip(
                            selected = channelIndex == idx,
                            onClick = { channelIndex = idx },
                            label = { Text(name, color = col, fontSize = 11.sp) }
                        )
                    }
                }

                val activePoints = when (channelIndex) {
                    1 -> grade.curves.red
                    2 -> grade.curves.green
                    3 -> grade.curves.blue
                    else -> grade.curves.master
                }
                val strokeColor = labels[channelIndex].second

                // Interactive Touch Spline Curve Canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A0D14))
                        .border(1.dp, SurfaceStroke, RoundedCornerShape(12.dp))
                        .pointerInput(channelIndex, grade.curves) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val normX = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val normY = (1f - change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val nearestIdx = activePoints.indices.minByOrNull { abs(activePoints[it].x - normX) } ?: return@detectDragGestures
                                val fixedX = activePoints[nearestIdx].x
                                val nextPts = activePoints.mapIndexed { i, pt ->
                                    if (i == nearestIdx) CurvePoint(fixedX, normY) else pt
                                }
                                val nextCurves = when (channelIndex) {
                                    1 -> grade.curves.copy(red = nextPts)
                                    2 -> grade.curves.copy(green = nextPts)
                                    3 -> grade.curves.copy(blue = nextPts)
                                    else -> grade.curves.copy(master = nextPts)
                                }
                                viewModel.updateColorGradeOnSelected(grade.copy(curves = nextCurves))
                            }
                        }
                ) {
                    // Grid
                    for (g in 1..3) {
                        val gx = size.width * (g / 4f)
                        val gy = size.height * (g / 4f)
                        drawLine(SurfaceStroke, Offset(gx, 0f), Offset(gx, size.height), 1f)
                        drawLine(SurfaceStroke, Offset(0f, gy), Offset(size.width, gy), 1f)
                    }

                    // Evaluated Smooth Spline
                    val path = Path()
                    for (s in 0..60) {
                        val u = s / 60f
                        val yVal = ColorGradingEngine.evaluateToneCurve(activePoints, u)
                        val px = u * size.width
                        val py = (1f - yVal) * size.height
                        if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(path, color = strokeColor, style = Stroke(width = 3.5f))

                    // Control Knobs
                    activePoints.forEach { pt ->
                        drawCircle(
                            color = strokeColor,
                            radius = 7f,
                            center = Offset(pt.x * size.width, (1f - pt.y) * size.height)
                        )
                    }
                }
            }

            ColorSubPanel.WHEELS -> {
                var wheelIdx by remember { mutableIntStateOf(0) } // 0=Shadows, 1=Midtones, 2=Highlights
                val wheelNames = listOf("Shadows (Lift)", "Midtones (Gamma)", "Highlights (Gain)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    wheelNames.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = wheelIdx == idx,
                            onClick = { wheelIdx = idx },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
                val activeTint = when (wheelIdx) {
                    0 -> grade.colorWheels.shadows
                    1 -> grade.colorWheels.midtones
                    else -> grade.colorWheels.highlights
                }
                fun updateTint(next: ColorWheelTint) {
                    val nextWheels = when (wheelIdx) {
                        0 -> grade.colorWheels.copy(shadows = next)
                        1 -> grade.colorWheels.copy(midtones = next)
                        else -> grade.colorWheels.copy(highlights = next)
                    }
                    viewModel.updateColorGradeOnSelected(grade.copy(colorWheels = nextWheels))
                }
                Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                    Column(Modifier.padding(12.dp)) {
                        LabeledSlider("Red Push", activeTint.rOffset, -1f..1f) { updateTint(activeTint.copy(rOffset = it)) }
                        LabeledSlider("Green Push", activeTint.gOffset, -1f..1f) { updateTint(activeTint.copy(gOffset = it)) }
                        LabeledSlider("Blue Push", activeTint.bOffset, -1f..1f) { updateTint(activeTint.copy(bOffset = it)) }
                    }
                }
            }
        }
    }

    if (savePresetDialog) {
        var presetName by remember { mutableStateOf("My Cinema Grade") }
        AlertDialog(
            onDismissRequest = { savePresetDialog = false },
            containerColor = PanelDark,
            title = { Text("Save Custom Color Preset") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Preset Name") },
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
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = TextSecondary)
            Text(String.format(Locale.US, "%.2f", value), fontSize = 11.sp, color = ElectricCyan)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.height(28.dp)
        )
    }
}

@Composable
fun EmptySelectionHint(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = TextSecondary, fontSize = 12.sp)
    }
}
