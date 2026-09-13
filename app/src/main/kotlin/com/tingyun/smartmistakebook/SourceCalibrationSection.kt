package com.tingyun.smartmistakebook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.SourceCalibration
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import kotlinx.coroutines.CancellationException

/**
 * 来源校准（spec §2.5）：每种"主观/判定"来源的正向报告，对着它之后第一次真实作答的
 * 回忆率，与真实作答基线比。存在的意义是让**人**能看到某个来源是不是在过度声明，
 * 再决定要不要调它的权重或放开它进 FSRS 参数拟合——目前唯一等待裁决的就是
 * `MODEL_JUDGED`（讲题判定）：校准达标前它被显式排除在拟合之外。
 *
 * 独立成文件而非塞进 `SecondaryScreens.kt`：那个文件已接近 1000 行硬限，规模门要求
 * 拆出内聚块（同一理由见 `SmartMistakeBookDestinations.kt` 的由来）。
 */
@Composable
internal fun SourceCalibrationSection(
    provider: suspend () -> List<SourceCalibration>,
    modifier: Modifier = Modifier,
) {
    var calibrations by remember { mutableStateOf<List<SourceCalibration>?>(null) }
    var unavailable by remember { mutableStateOf(false) }

    LaunchedEffect(provider) {
        calibrations = try {
            provider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable = true
            null
        }
    }

    Column(modifier = modifier) {
        PaperDivider(Modifier.padding(vertical = 16.dp))
        SectionHeader("来源校准")
        val rows = calibrations
        when {
            unavailable -> Text(
                text = "来源校准数据暂不可用，请稍后再试。",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("source_calibration_error"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            rows == null -> Text(
                text = "正在读取来源校准…",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("source_calibration_loading"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            rows.isEmpty() -> Text(
                text = "还没有可校准的来源。讲题判定复习过几轮之后，这里会显示" +
                    "它判对之后下一次真实作答的回忆率，与真实作答基线的差距。",
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("source_calibration_empty"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            else -> rows.forEach { row ->
                SourceCalibrationRow(row)
                Spacer(Modifier.height(10.dp))
            }
        }
        if (rows?.any { it.sourceKind == MODEL_JUDGED_SOURCE_KIND } == true) {
            Text(
                text = "讲题判定的来源：校准样本足够（≥${SourceCalibration.MIN_PAIRED_OUTCOMES} 对）" +
                    "且不显著低于真实作答基线之前，它不参与 FSRS 参数拟合——避免用一种新评分" +
                    "把历史间隔标尺整体拉偏。",
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("source_calibration_model_judged_note"),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourceCalibrationRow(row: SourceCalibration) {
    Text(
        text = sourceKindLabel(row.sourceKind),
        modifier = Modifier.testTag("source_calibration_kind_${row.sourceKind}"),
        color = InkSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = buildString {
            append("正向报告 ${row.positiveReportCount} 次")
            append(" · 配到真实作答 ${row.nextAttemptCount} 次")
            if (row.nextAttemptCount > 0) {
                append(" · 之后回忆率 ${percent(row.realizedRecallRate)}")
                append(" · 真实作答基线 ${percent(row.attemptBaselineRecallRate)}")
            }
        },
        modifier = Modifier
            .padding(top = 2.dp)
            .testTag("source_calibration_stats_${row.sourceKind}"),
        color = InkSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
    val verdict = when {
        !row.hasSufficientPairs ->
            "样本还不够（需 ≥${SourceCalibration.MIN_PAIRED_OUTCOMES} 对），暂不建议改动这个来源。"

        row.suggestsDowngrade ->
            "低于基线超过 ${percent(SourceCalibration.DOWNGRADE_MARGIN)}：这个来源在过度声明，" +
                "建议下调它的权重（人工决定，不自动改写）。"

        else ->
            "与基线相当：可以维持现有权重。"
    }
    Text(
        text = verdict,
        modifier = Modifier
            .padding(top = 2.dp)
            .testTag("source_calibration_verdict_${row.sourceKind}"),
        color = InkSecondary,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun sourceKindLabel(sourceKind: String): String = when (sourceKind) {
    MODEL_JUDGED_SOURCE_KIND -> "讲题判定（模型语义判断 + 本地核对）"
    "SELF_REPORT" -> "自评（2026-09-13 已拆除，仅历史数据）"
    "VISUAL" -> "看图互动"
    else -> sourceKind
}

private fun percent(value: Double): String =
    if (value.isNaN()) "—" else "${(value * 100).toInt()}%"

/** 与 `ReviewLogSink.SOURCE_KIND_MODEL_JUDGED` / `ReviewSample.MODEL_JUDGED_KIND` 同值。 */
private const val MODEL_JUDGED_SOURCE_KIND = "MODEL_JUDGED"
