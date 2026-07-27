package com.tingyun.smartmistakebook.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.StructuredContentValidator
import com.tingyun.smartmistakebook.core.model.TutorSceneEmphasis
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import com.tingyun.smartmistakebook.core.ui.StreamingSafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.StructuredContentRenderer
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer

/**
 * Launcher-free benchmark fixture for one deterministic, local rendering pipeline.
 *
 * The fixture does not access the network, Room, or model-task storage. It owns only fixed
 * structured input and delegates all rendering to the production renderers.
 */
class DynamicGuiBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(StructuredContentValidator.validate(BENCHMARK_DOCUMENT).isEmpty())
        enableEdgeToEdge()
        setContent {
            SmartMistakeBookTheme {
                DynamicGuiBenchmarkFixture()
            }
        }
    }
}

@Composable
private fun DynamicGuiBenchmarkFixture() {
    var runId by remember { mutableIntStateOf(0) }
    var stage by remember { mutableStateOf(RenderStage.IDLE) }
    var streamedMarkdown by remember { mutableStateOf("") }

    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        stage = RenderStage.STREAMING
        streamedMarkdown = ""
        STREAM_CHUNKS.forEach { chunk ->
            withFrameNanos { }
            streamedMarkdown += chunk
        }
        stage = RenderStage.STABLE
        withFrameNanos { }
        stage = RenderStage.GUI
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = { runId += 1 },
            modifier = Modifier.testTag("benchmark_stream_start"),
            enabled = stage == RenderStage.IDLE || stage == RenderStage.GUI,
        ) {
            Text("运行本地动态讲解")
        }

        if (stage == RenderStage.STREAMING) {
            StreamingSafeMarkdownText(
                stableMarkdown = "",
                provisionalMarkdown = streamedMarkdown,
                contentIdentity = runId,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("benchmark_markdown_streaming"),
            )
        }

        if (stage >= RenderStage.STABLE) {
            StructuredContentRenderer(
                document = BENCHMARK_DOCUMENT,
                modifier = Modifier.testTag("benchmark_markdown_stable"),
            )
        }

        if (stage == RenderStage.GUI) {
            TutorVisualSceneRenderer(
                scene = BENCHMARK_SCENE,
                modifier = Modifier.testTag("benchmark_dynamic_gui_ready"),
            )
        }
    }
}

private enum class RenderStage {
    IDLE,
    STREAMING,
    STABLE,
    GUI,
}

private val STREAM_CHUNKS = listOf(
    "先把 **已知条件** 写成比例：",
    " \$v = s / t\$。",
    "\n再核对单位，",
    "最后把数值代入并检查结果。",
)

private val BENCHMARK_DOCUMENT = QuestionDocument(
    id = "benchmark-stream-document",
    title = "稳定后的讲解",
    blocks = listOf(
        ContentBlock.Paragraph(
            id = "benchmark-explanation",
            markdown = STREAM_CHUNKS.joinToString(separator = ""),
        ),
    ),
)

private val BENCHMARK_SCENE = TutorStepFlowScene(
    sceneId = "benchmark-dynamic-gui",
    title = "速度关系检查",
    steps = listOf(
        TutorSceneStep(
            stepId = "identify",
            label = "识别",
            bodyMarkdown = "标出路程 \$s\$ 与时间 \$t\$。",
        ),
        TutorSceneStep(
            stepId = "calculate",
            label = "计算",
            bodyMarkdown = "使用 **速度公式** 计算。",
            formula = "v = \\frac{s}{t}",
            emphasis = TutorSceneEmphasis.KEY,
        ),
        TutorSceneStep(
            stepId = "verify",
            label = "核对",
            bodyMarkdown = "检查单位和数量级是否合理。",
            emphasis = TutorSceneEmphasis.CHECK,
        ),
    ),
)
