package com.tingyun.smartmistakebook.core.visual.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.visual.runtime.CompiledTutorVisualDocument
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualDocumentCompiler
import kotlinx.coroutines.isActive

@Composable
fun TutorVisualDocumentContent(
    scene: TutorVisualDocumentScene,
    modifier: Modifier = Modifier,
    onOpenOriginal: (() -> Unit)? = null,
    onReportIncorrect: (() -> Unit)? = null,
) {
    val compiledResult = remember(scene) { runCatching { TutorVisualDocumentCompiler.compile(scene) } }
    val compiled = compiledResult.getOrNull()
    if (compiled == null) {
        TutorVisualFallback(
            markdown = scene.fallbackMarkdown,
            accessibilitySummary = scene.accessibilitySummary,
            modifier = modifier,
        )
        return
    }
    TutorVisualDocumentPlayer(
        compiled = compiled,
        modifier = modifier,
        onOpenOriginal = onOpenOriginal,
        onReportIncorrect = onReportIncorrect,
    )
}

@Composable
private fun TutorVisualDocumentPlayer(
    compiled: CompiledTutorVisualDocument,
    modifier: Modifier,
    onOpenOriginal: (() -> Unit)?,
    onReportIncorrect: (() -> Unit)?,
) {
    val scene = compiled.scene
    val context = LocalContext.current
    val profile = remember(context) { TutorVisualRenderProfileResolver.resolve(context) }
    var stepIndex by rememberSaveable(scene.sceneId) { mutableIntStateOf(0) }
    var selectedPanelIndex by rememberSaveable(scene.sceneId) { mutableIntStateOf(0) }
    var timeSeconds by rememberSaveable(scene.sceneId) { mutableDoubleStateOf(0.0) }
    var playing by rememberSaveable(scene.sceneId) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(scene.sceneId) { mutableStateOf(false) }
    val animationsEnabled = remember {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O ||
            ValueAnimator.areAnimatorsEnabled()
    }
    val selectedPanel = scene.panels[selectedPanelIndex.coerceIn(scene.panels.indices)]
    val frame = remember(compiled, stepIndex, timeSeconds) {
        compiled.evaluate(timeSeconds, stepIndex)
    }

    LaunchedEffect(playing, scene.durationSeconds, animationsEnabled) {
        if (!playing || scene.durationSeconds <= 0.0 || !animationsEnabled) return@LaunchedEffect
        var previousFrameNanos = 0L
        while (isActive && playing) {
            withFrameNanos { frameNanos ->
                if (previousFrameNanos != 0L) {
                    val elapsed = (frameNanos - previousFrameNanos) / 1_000_000_000.0
                    val next = timeSeconds + elapsed
                    if (next >= scene.durationSeconds) {
                        timeSeconds = scene.durationSeconds
                        playing = false
                    } else {
                        timeSeconds = next
                    }
                }
                previousFrameNanos = frameNanos
            }
        }
    }

    val content: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxWidth()
                .semantics { contentDescription = scene.accessibilitySummary },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (scene.panels.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    scene.panels.forEachIndexed { index, panel ->
                        AssistChip(
                            onClick = { selectedPanelIndex = index },
                            label = {
                                Text(panel.title ?: defaultPanelLabel(panel.kind))
                            },
                            leadingIcon = if (index == selectedPanelIndex) {
                                { Box(Modifier.widthIn(min = 4.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            TutorVisualPanelContent(
                compiled = compiled,
                panel = selectedPanel,
                frame = frame,
                profile = profile,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (fullscreen) 1.35f else 1.45f)
                    .testTag("tutor-visual-v2-panel-${selectedPanel.panelId}"),
            )
            scene.steps.getOrNull(stepIndex)?.let { step ->
                Text(
                    text = step.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val visibleVariables = step.displayVariableIds.mapNotNull(compiled.variables::get)
                if (visibleVariables.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        visibleVariables.forEach { variable ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    text = buildString {
                                        append(variable.label)
                                        append("  ")
                                        append(variable.value.toStudentNumber())
                                        variable.unit?.let { append(" ").append(it) }
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }
            if (scene.durationSeconds > 0.0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (!animationsEnabled) {
                                timeSeconds = if (timeSeconds < scene.durationSeconds) {
                                    scene.durationSeconds
                                } else {
                                    0.0
                                }
                            } else {
                                playing = !playing
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                        )
                    }
                    Slider(
                        value = timeSeconds.toFloat(),
                        onValueChange = {
                            playing = false
                            timeSeconds = it.toDouble()
                        },
                        valueRange = 0f..scene.durationSeconds.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    if (!fullscreen) {
                        IconButton(onClick = { fullscreen = true }) {
                            Icon(Icons.Rounded.Fullscreen, contentDescription = "专注查看")
                        }
                    }
                }
            } else if (!fullscreen) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { fullscreen = true }) {
                        Icon(Icons.Rounded.Fullscreen, contentDescription = "专注查看")
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                scene.steps.forEachIndexed { index, step ->
                    AssistChip(
                        onClick = {
                            playing = false
                            stepIndex = index
                            timeSeconds = step.animationStartSeconds
                        },
                        label = { Text("${index + 1}  ${step.label}") },
                    )
                }
            }
            if (fullscreen && (onOpenOriginal != null || onReportIncorrect != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    onOpenOriginal?.let { open ->
                        IconButton(onClick = open) {
                            Icon(Icons.Rounded.Image, contentDescription = "查看原图")
                        }
                    }
                    onReportIncorrect?.let { report ->
                        IconButton(onClick = report) {
                            Icon(Icons.Rounded.BrokenImage, contentDescription = "图不对")
                        }
                    }
                }
            }
        }
    }

    content(modifier)
    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { fullscreen = false }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                        Text(
                            text = scene.title,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    content(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TutorVisualPanelContent(
    compiled: CompiledTutorVisualDocument,
    panel: TutorVisualPanel,
    frame: com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualFrame,
    profile: TutorVisualRenderProfile,
    modifier: Modifier,
) {
    when (panel.kind) {
        TutorVisualPanelKind.DIAGRAM_2D -> TutorVisual2DPanel(
            compiled = compiled,
            panel = panel,
            frame = frame,
            modifier = modifier,
        )
        TutorVisualPanelKind.SCENE_3D -> TutorVisual3DPanel(
            compiled = compiled,
            panel = panel,
            frame = frame,
            profile = profile,
            modifier = modifier,
        )
        TutorVisualPanelKind.SCIENTIFIC_CHART -> TutorVisualChartPanel(
            compiled = compiled,
            panel = panel,
            frame = frame,
            modifier = modifier,
        )
    }
}

@Composable
private fun TutorVisualFallback(
    markdown: String,
    accessibilitySummary: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = accessibilitySummary },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = markdown,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun defaultPanelLabel(kind: TutorVisualPanelKind): String = when (kind) {
    TutorVisualPanelKind.DIAGRAM_2D -> "装置"
    TutorVisualPanelKind.SCENE_3D -> "空间"
    TutorVisualPanelKind.SCIENTIFIC_CHART -> "图表"
}

private fun Double.toStudentNumber(): String =
    if (this % 1.0 == 0.0) toLong().toString() else "%.4f".format(this).trimEnd('0').trimEnd('.')
