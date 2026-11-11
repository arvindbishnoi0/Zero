package com.vibecut.pro.ui.editor.panels

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecut.pro.engine.ai.AiToolType
import com.vibecut.pro.engine.audio.BuiltInSfxPreset
import com.vibecut.pro.engine.keyframes.KeyframeInterpolator
import com.vibecut.pro.engine.model.*
import com.vibecut.pro.engine.subtitles.SubtitleCodec
import com.vibecut.pro.ui.theme.*
import com.vibecut.pro.ui.viewmodel.EditorUiState
import com.vibecut.pro.ui.viewmodel.EditorViewModel

@Composable
fun TransitionsPanel(
    selectedClip: TimelineClip?,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint("Select a clip to configure entry/exit transitions.")
        return
    }

    val existingTrans = selectedClip.transitionIn
    val current = existingTrans ?: ClipTransition(TransitionType.DISSOLVE, 500L)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (existingTrans != null) "Active: ${existingTrans.type.label}" else "No Transition Selected",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (existingTrans != null) {
                    TextButton(onClick = { viewModel.setTransitionOnSelected(null) }) {
                        Text("None", color = CoralRed, fontSize = 11.sp)
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.applyTransitionToAllClips(current) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("Apply to All", fontSize = 11.sp)
                }
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(TransitionType.entries) { tType ->
                val active = selectedClip.transitionIn?.type == tType
                Column(
                    modifier = Modifier
                        .width(102.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) NeonViolet.copy(alpha = 0.28f) else PanelDark)
                        .border(1.dp, if (active) NeonViolet else SurfaceStroke, RoundedCornerShape(14.dp))
                        .clickable {
                            viewModel.setTransitionOnSelected(
                                current.copy(type = tType)
                            )
                        }
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = if (active) ElectricCyan else TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(tType.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(tType.category, fontSize = 9.sp, color = TextSecondary)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
            Column(Modifier.padding(12.dp)) {
                LabeledSlider(
                    label = "Duration (${current.durationMs} ms)",
                    value = current.durationMs / 1000f,
                    range = 0.1f..3.0f,
                    onValueChange = { sec ->
                        viewModel.setTransitionOnSelected(current.copy(durationMs = (sec * 1000f).toLong()))
                    }
                )
                LabeledSlider(
                    label = "Edge Softness",
                    value = current.softness,
                    range = 0.02f..1f,
                    onValueChange = { s ->
                        viewModel.setTransitionOnSelected(current.copy(softness = s))
                    }
                )
                LabeledSlider(
                    label = "Chromatic / FX Intensity",
                    value = current.chromaticAmount,
                    range = 0f..1f,
                    onValueChange = { c ->
                        viewModel.setTransitionOnSelected(current.copy(chromaticAmount = c))
                    }
                )
            }
        }
    }
}

@Composable
fun KeyframesPanel(
    selectedClip: TimelineClip?,
    playheadMs: Long,
    viewModel: EditorViewModel
) {
    if (selectedClip == null) {
        EmptySelectionHint("Select any Video, PIP, Text or Audio clip to animate with Keyframes.")
        return
    }

    var selectedProp by remember { mutableStateOf(KeyframeProperty.SCALE) }
    var selectedEasing by remember { mutableStateOf(EasingCurve.EASE_IN_OUT) }
    val localOffsetMs = (playheadMs - selectedClip.startTimeOnTrackMs).coerceIn(0L, selectedClip.timelineDurationMs)
    val currentVal = KeyframeInterpolator.evaluateProperty(
        selectedClip.keyframes,
        selectedProp,
        localOffsetMs,
        selectedProp.defaultValue
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Property Selector
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyframeProperty.entries.forEach { prop ->
                val count = selectedClip.keyframes.count { it.property == prop }
                FilterChip(
                    selected = selectedProp == prop,
                    onClick = { selectedProp = prop },
                    label = { Text(if (count > 0) "${prop.label} ($count)" else prop.label, fontSize = 11.sp) }
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selectedProp.label} @ ${localOffsetMs}ms", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Button(
                        onClick = { viewModel.upsertKeyframeAtPlayhead(selectedProp, currentVal, selectedEasing) },
                        colors = ButtonDefaults.buttonColors(containerColor = KeyframeGold, contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("+◆ Keyframe", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                LabeledSlider(
                    label = selectedProp.label,
                    value = currentVal,
                    range = selectedProp.minValue..selectedProp.maxValue,
                    onValueChange = { v ->
                        viewModel.upsertKeyframeAtPlayhead(selectedProp, v, selectedEasing)
                    }
                )

                Text("Interpolation Easing Curve:", fontSize = 11.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    EasingCurve.entries.forEach { curve ->
                        FilterChip(
                            selected = selectedEasing == curve,
                            onClick = { selectedEasing = curve },
                            label = { Text(curve.label, fontSize = 11.sp) }
                        )
                    }
                }

                // Live Cubic Bezier Curve Preview
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0A0D14))
                        .padding(8.dp)
                ) {
                    val p = Path()
                    for (i in 0..40) {
                        val u = i / 40f
                        val y = KeyframeInterpolator.evaluateEasing(u, selectedEasing)
                        val px = u * size.width
                        val py = size.height * (1f - y.coerceIn(-0.2f, 1.25f) * 0.8f - 0.1f)
                        if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                    }
                    drawPath(p, color = KeyframeGold, style = Stroke(width = 3f))
                }
            }
        }

        if (selectedClip.keyframes.isNotEmpty()) {
            Text("Active Keyframes on Clip (${selectedClip.keyframes.size})", fontSize = 11.sp, color = TextSecondary)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selectedClip.keyframes, key = { it.id }) { kf ->
                    InputChip(
                        selected = true,
                        onClick = { viewModel.seekTo(selectedClip.startTimeOnTrackMs + kf.timeOffsetMs) },
                        label = { Text("◆ ${kf.property.label} @ ${kf.timeOffsetMs}ms", fontSize = 10.sp) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove keyframe",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { viewModel.removeKeyframe(kf.id) }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AudioStudioPanel(
    uiState: EditorUiState,
    viewModel: EditorViewModel
) {
    val selectedClip = uiState.selectedClip
    val musicPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importMediaUris(listOf(uri), TrackType.AUDIO_MUSIC)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.toggleVoiceoverRecording()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Studio Audio Action Row
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionPill(Icons.Default.LibraryMusic, "Import Music", ElectricCyan) {
                musicPicker.launch("audio/*")
            }
            ActionPill(Icons.Default.GraphicEq, "Extract Audio", KeyframeGold) {
                viewModel.extractAudioFromSelectedClip()
            }
            ActionPill(
                icon = if (uiState.isVoiceRecording) Icons.Default.StopCircle else Icons.Default.Mic,
                label = if (uiState.isVoiceRecording) "Stop Rec ●" else "Voiceover",
                tint = if (uiState.isVoiceRecording) CoralRed else TextPrimary
            ) {
                if (uiState.isVoiceRecording) {
                    viewModel.toggleVoiceoverRecording()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            ActionPill(Icons.Default.Bolt, "Beat Sync", EmeraldBeat) {
                viewModel.detectBeatsAndAutoSync()
            }
        }

        if (uiState.isVoiceRecording) {
            LinearProgressIndicator(
                progress = { uiState.voiceAmplitude.coerceIn(0.05f, 1f) },
                color = CoralRed,
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape)
            )
        }

        // Synthesized Sound Effects Studio Library
        Text("Built-In Sound Effects Studio (Offline 44.1kHz WAV):", fontSize = 11.sp, color = TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BuiltInSfxPreset.entries) { sfx ->
                AssistChip(
                    onClick = { viewModel.addSynthesizedSfx(sfx) },
                    leadingIcon = { Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    label = { Text("${sfx.title} (${sfx.durationMs}ms)", fontSize = 11.sp) }
                )
            }
        }

        // Per-Clip Audio Mixing & DSP Controls
        if (selectedClip != null) {
            Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Clip Audio Mix & Real-Time PCM DSP (${selectedClip.sourceName})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    LabeledSlider("Volume (0–200%)", selectedClip.volume, 0f..2f) {
                        viewModel.updateSelectedAudioDsp(volume = it)
                    }
                    LabeledSlider("Fade In (sec)", selectedClip.fadeInMs / 1000f, 0f..3f) {
                        viewModel.updateSelectedAudioDsp(fadeInMs = (it * 1000f).toLong())
                    }
                    LabeledSlider("Fade Out (sec)", selectedClip.fadeOutMs / 1000f, 0f..3f) {
                        viewModel.updateSelectedAudioDsp(fadeOutMs = (it * 1000f).toLong())
                    }
                    LabeledSlider("Noise Reduction (90Hz HP + Gate)", selectedClip.noiseReduction, 0f..1f) {
                        viewModel.updateSelectedAudioDsp(noiseReduction = it)
                    }
                    LabeledSlider("Voice Enhancement (2.8kHz Clarity)", selectedClip.voiceEnhancement, 0f..1f) {
                        viewModel.updateSelectedAudioDsp(voiceEnhancement = it)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto Ducking under Voiceover / Captions", fontSize = 12.sp)
                        Switch(
                            checked = selectedClip.audioDucking,
                            onCheckedChange = { viewModel.updateSelectedAudioDsp(audioDucking = it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TextAndCaptionsPanel(
    uiState: EditorUiState,
    viewModel: EditorViewModel
) {
    val context = LocalContext.current
    val selectedClip = uiState.selectedClip
    val activeSpec = selectedClip?.textSpec
    var pendingSpeechLang by remember { mutableStateOf("en-US") }

    val speechMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.dictateCaptionWithSpeechRecognizer(pendingSpeechLang)
    }

    val srtImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.importSrtUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Quick Add Text + Auto Captions (Hindi + English) + Stickers
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.addTextClip() },
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color.Black)
            ) {
                Icon(Icons.Default.Title, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("+ Text Layer", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            OutlinedButton(onClick = {
                pendingSpeechLang = "hi-IN"
                speechMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Text("🎙 Hindi Dictate (हिन्दी)", fontSize = 11.sp)
            }
            OutlinedButton(onClick = {
                pendingSpeechLang = "en-US"
                speechMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }) {
                Text("🎙 English Dictate", fontSize = 11.sp)
            }
            OutlinedButton(onClick = { viewModel.generateVadTimedCaptionSlots("en-US") }) {
                Text("Auto-Scan Speech", fontSize = 11.sp)
            }
            OutlinedButton(onClick = { srtImportLauncher.launch("*/*") }) {
                Text("Import .SRT", fontSize = 11.sp)
            }
            if (uiState.project.subtitles.isNotEmpty()) {
                OutlinedButton(onClick = {
                    viewModel.createExportSrtShareIntent()?.let { context.startActivity(Intent.createChooser(it, "Export SRT")) }
                }) {
                    Text("Export .SRT", fontSize = 11.sp)
                }
            }
        }

        // Text Templates Strip
        Text("Pro Text Templates:", fontSize = 11.sp, color = TextSecondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SubtitleCodec.textTemplates, key = { it.id }) { tpl ->
                AssistChip(
                    onClick = { viewModel.addTextClip(tpl.spec) },
                    label = { Text(tpl.title, fontSize = 11.sp) }
                )
            }
        }

        // Stickers Strip
        Text("Quick Stickers Overlay:", fontSize = 11.sp, color = TextSecondary)
        val stickers = remember {
            listOf(
                StickerSpec("🔥", "Fire"), StickerSpec("✨", "Sparkle"),
                StickerSpec("🎬", "Clapper"), StickerSpec("⚡", "Bolt"),
                StickerSpec("💯", "100"), StickerSpec("❤️", "Heart"),
                StickerSpec("🚀", "Rocket"), StickerSpec("👑", "Crown"),
                StickerSpec("🎵", "Music"), StickerSpec("🎯", "Target")
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(stickers) { st ->
                Surface(
                    color = PanelDark,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.clickable { viewModel.addSticker(st) }
                ) {
                    Text(st.content, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }

        // Selected Text Layer Rich Inspector
        if (activeSpec != null) {
            Card(colors = CardDefaults.cardColors(containerColor = PanelDark)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Edit Selected Text Layer", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ElectricCyan)
                    OutlinedTextField(
                        value = activeSpec.text,
                        onValueChange = { viewModel.updateSelectedTextSpec(activeSpec.copy(text = it)) },
                        label = { Text("Caption / Title Text (Supports English & हिन्दी)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Fonts
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Sans-Serif Bold", "Sans-Serif Black", "Serif", "Monospace").forEach { font ->
                            FilterChip(
                                selected = activeSpec.fontFamilyName == font,
                                onClick = { viewModel.updateSelectedTextSpec(activeSpec.copy(fontFamilyName = font)) },
                                label = { Text(font, fontSize = 11.sp) }
                            )
                        }
                    }

                    // Color Palette + Gradient + 3D + Pill BG
                    val palette = listOf(0xFFFFFFFF, 0xFF00E5FF, 0xFFFFEA00, 0xFFFF1744, 0xFF00E676, 0xFFE040FB, 0xFFFF9100)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        palette.forEach { colHex ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(colHex))
                                    .border(
                                        width = if (activeSpec.textColorArgb == colHex) 2.dp else 0.dp,
                                        color = Color.White,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.updateSelectedTextSpec(activeSpec.copy(textColorArgb = colHex)) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeSpec.useGradient,
                            onClick = { viewModel.updateSelectedTextSpec(activeSpec.copy(useGradient = !activeSpec.useGradient)) },
                            label = { Text("Gradient") }
                        )
                        FilterChip(
                            selected = activeSpec.is3dExtruded,
                            onClick = { viewModel.updateSelectedTextSpec(activeSpec.copy(is3dExtruded = !activeSpec.is3dExtruded)) },
                            label = { Text("3D Extrude") }
                        )
                        FilterChip(
                            selected = activeSpec.backgroundColorArgb != 0L,
                            onClick = {
                                val nextBg = if (activeSpec.backgroundColorArgb == 0L) 0xAA000000 else 0x00000000
                                viewModel.updateSelectedTextSpec(activeSpec.copy(backgroundColorArgb = nextBg))
                            },
                            label = { Text("Pill BG") }
                        )
                    }

                    // Text Animations
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TextAnimationType.entries.forEach { anim ->
                            FilterChip(
                                selected = activeSpec.animationIn == anim,
                                onClick = { viewModel.updateSelectedTextSpec(activeSpec.copy(animationIn = anim)) },
                                label = { Text(anim.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    LabeledSlider("Font Size", activeSpec.fontSizeSp, 14f..64f) {
                        viewModel.updateSelectedTextSpec(activeSpec.copy(fontSizeSp = it))
                    }
                    LabeledSlider("Outline Width", activeSpec.outlineWidth, 0f..10f) {
                        viewModel.updateSelectedTextSpec(activeSpec.copy(outlineWidth = it))
                    }
                    LabeledSlider("Shadow Blur", activeSpec.shadowRadius, 0f..20f) {
                        viewModel.updateSelectedTextSpec(activeSpec.copy(shadowRadius = it))
                    }
                }
            }
        }
    }
}

@Composable
fun AiToolsStudioPanel(
    uiState: EditorUiState,
    viewModel: EditorViewModel
) {
    var showScriptModal by remember { mutableStateOf(false) }
    var scriptDraft by remember {
        mutableStateOf("Scene 1: Bold opening reveal\nScene 2: High-speed action montage\nScene 3: Cinematic closing callout")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (uiState.isProcessingAi) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(NeonViolet.copy(alpha = 0.2f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = ElectricCyan, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(uiState.aiStatusText ?: "Running on-device AI...", fontSize = 12.sp)
            }
        }

        AiToolType.entries.forEach { tool ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(PanelDark)
                    .border(1.dp, SurfaceStroke, RoundedCornerShape(12.dp))
                    .clickable(enabled = !uiState.isProcessingAi) {
                        if (tool == AiToolType.SCRIPT_TO_VIDEO) {
                            showScriptModal = true
                        } else {
                            viewModel.executeAiTool(tool)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tool.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(tool.subtitle, fontSize = 11.sp, color = TextSecondary)
                }
                Surface(
                    color = ElectricCyan.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = tool.badge,
                        color = ElectricCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    if (showScriptModal) {
        AlertDialog(
            onDismissRequest = { showScriptModal = false },
            containerColor = PanelDark,
            title = { Text("AI Script to Video") },
            text = {
                OutlinedTextField(
                    value = scriptDraft,
                    onValueChange = { scriptDraft = it },
                    label = { Text("One scene / caption per line") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showScriptModal = false
                    viewModel.executeAiTool(AiToolType.SCRIPT_TO_VIDEO, scriptDraft)
                }) { Text("Generate Multi-Scene Edit") }
            },
            dismissButton = {
                TextButton(onClick = { showScriptModal = false }) { Text("Cancel") }
            }
        )
    }

    uiState.latestSummaryReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::clearSummaryReport,
            containerColor = PanelDark,
            title = { Text("AI Video Analysis Summary") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pacing: ${report.dominantPaceLabel}", color = ElectricCyan, fontWeight = FontWeight.Bold)
                    report.summaryBullets.forEach { bullet ->
                        Text("• $bullet", fontSize = 12.sp, color = TextPrimary)
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::clearSummaryReport) { Text("Done") }
            }
        )
    }
}
