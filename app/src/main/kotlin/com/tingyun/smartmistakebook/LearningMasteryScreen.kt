package com.tingyun.smartmistakebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayError
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeItem
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageCursor
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryProjectionRevision
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubjectOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimeline
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineActivity
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineEntry
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRange
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineSignal
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeMuted
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.SmartDimens
import com.tingyun.smartmistakebook.core.ui.Track
import com.tingyun.smartmistakebook.core.ui.studentLabel
import kotlinx.coroutines.flow.collect

/**
 * Read-only route for "我的 → 学习掌握".
 *
 * The repository is the only data input. Storage records, confidence values, evidence weights,
 * knowledge identifiers, and internal projection concepts never enter the visible UI.
 */
@Composable
internal fun LearningMasteryRoute(
    repository: LearningMasteryDisplayRepository,
    onBack: () -> Unit,
) {
    var overviewRetryKey by remember { mutableIntStateOf(0) }
    val overviewState by produceState<LearningMasteryLoadState<LearningMasteryOverview>>(
        initialValue = LearningMasteryLoadState.Loading,
        repository,
        overviewRetryKey,
    ) {
        repository.observeSubjectOverview().collect { value = it }
    }
    val overview =
        (overviewState as? LearningMasteryLoadState.Content<LearningMasteryOverview>)?.value
    var selectedSubjectName by rememberSaveable { mutableStateOf<String?>(null) }
    var timelineRange by rememberSaveable {
        mutableStateOf(LearningMasteryTimelineRange.LAST_7_DAYS)
    }
    val selectedSubject =
        selectedSubjectName?.let { name ->
            LearningMasterySubject.entries.firstOrNull { it.name == name }
        }

    LaunchedEffect(overview?.revision?.opaqueValue) {
        val subjects = overview?.subjects.orEmpty()
        if (subjects.none { it.subject == selectedSubject }) {
            selectedSubjectName = subjects.preferredSubject()?.subject?.name
        }
    }

    LearningMasteryScreen(
        overviewState = overviewState,
        selectedSubject = selectedSubject,
        onSubjectSelected = { selectedSubjectName = it.name },
        onBack = onBack,
        onRetryOverview = { overviewRetryKey += 1 },
        timelineRange = timelineRange,
        onTimelineRangeChanged = { timelineRange = it },
        timelineContent = { revision, subject, range, onRangeSelected ->
            LearningMasteryTimelineSections(
                repository = repository,
                revision = revision,
                subject = subject,
                range = range,
                onRangeSelected = onRangeSelected,
            )
        },
        knowledgeContent = { revision, subject ->
            LearningMasteryKnowledgeSections(
                repository = repository,
                revision = revision,
                subject = subject,
                onProjectionChanged = { overviewRetryKey += 1 },
            )
        },
    )
}

@Composable
private fun LearningMasteryScreen(
    overviewState: LearningMasteryLoadState<LearningMasteryOverview>,
    selectedSubject: LearningMasterySubject?,
    onSubjectSelected: (LearningMasterySubject) -> Unit,
    onBack: () -> Unit,
    onRetryOverview: () -> Unit,
    timelineRange: LearningMasteryTimelineRange,
    onTimelineRangeChanged: (LearningMasteryTimelineRange) -> Unit,
    timelineContent:
        @Composable (
            revision: LearningMasteryProjectionRevision,
            subject: LearningMasterySubject,
            range: LearningMasteryTimelineRange,
            onRangeSelected: (LearningMasteryTimelineRange) -> Unit,
        ) -> Unit,
    knowledgeContent:
        @Composable (
            revision: LearningMasteryProjectionRevision,
            subject: LearningMasterySubject,
        ) -> Unit,
) {
    RootPageColumn(modifier = Modifier.testTag("learning_mastery_screen")) {
        SecondaryHeader(title = "学习掌握", onBack = onBack)
        when (overviewState) {
            LearningMasteryLoadState.Loading -> LearningMasteryLoading()
            is LearningMasteryLoadState.Empty -> LearningMasteryEmpty()
            is LearningMasteryLoadState.Error ->
                LearningMasteryError(onRetry = onRetryOverview)
            is LearningMasteryLoadState.Content -> {
                val overview = overviewState.value
                OverallMasterySummary(subjects = overview.subjects)
                SectionHeader("分科总览")
                SubjectOverviewRow(
                    subjects = overview.subjects,
                    selectedSubject = selectedSubject,
                    onSubjectSelected = onSubjectSelected,
                )
                selectedSubject?.let { subject ->
                    timelineContent(
                        overview.revision,
                        subject,
                        timelineRange,
                        onTimelineRangeChanged,
                    )
                    knowledgeContent(overview.revision, subject)
                }
            }
        }
    }
}

@Composable
private fun OverallMasterySummary(
    subjects: List<LearningMasterySubjectOverview>,
) {
    val summary = learningMasterySummaryText(subjects)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = SmartDimens.Space8)
                .background(
                    color = JadeSoft.copy(alpha = 0.42f),
                    shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
                )
                .padding(horizontal = SmartDimens.Space16, vertical = SmartDimens.Space12)
                .testTag("learning_mastery_overall"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = summary
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "总体",
                color = Ink,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(SmartDimens.Space16))
            Text(
                text = summary,
                modifier = Modifier.weight(1f),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(SmartDimens.Space12))
        MasteryDistributionBar(
            distribution = learningMasteryStatusDistribution(subjects),
        )
    }
}

@Composable
private fun MasteryDistributionBar(
    distribution: LearningMasteryStatusDistribution,
) {
    val summary = learningMasteryDistributionSummary(distribution)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = summary
                }
                .testTag("learning_mastery_distribution"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            distributionSegment(distribution.needsReinforcement, ErrorWarm)
            distributionSegment(distribution.gettingFamiliar, JadeMuted)
            distributionSegment(distribution.fairlySteady, JadeActive)
            distributionSegment(distribution.notYetLearned, Track)
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = SmartDimens.Space8),
            horizontalArrangement = Arrangement.spacedBy(SmartDimens.Space12),
        ) {
            LegendItem(ErrorWarm, "需要再巩固 ${distribution.needsReinforcement}")
            LegendItem(JadeMuted, "正在熟悉 ${distribution.gettingFamiliar}")
            LegendItem(JadeActive, "比较稳 ${distribution.fairlySteady}")
            LegendItem(Track, "还没学到 ${distribution.notYetLearned}")
        }
    }
}

@Composable
private fun RowScope.distributionSegment(
    count: Int,
    color: Color,
) {
    if (count <= 0) return
    Box(
        modifier =
            Modifier
                .weight(count.toFloat())
                .fillMaxHeight()
                .background(color, RoundedCornerShape(3.dp)),
    )
}

internal data class LearningMasteryStatusDistribution(
    val needsReinforcement: Int,
    val gettingFamiliar: Int,
    val fairlySteady: Int,
    val notYetLearned: Int,
) {
    val total: Int
        get() = needsReinforcement + gettingFamiliar + fairlySteady + notYetLearned
}

internal fun learningMasteryStatusDistribution(
    subjects: List<LearningMasterySubjectOverview>,
): LearningMasteryStatusDistribution =
    LearningMasteryStatusDistribution(
        needsReinforcement =
            subjects.count { it.status == LearningMasteryStatus.NEEDS_REINFORCEMENT },
        gettingFamiliar =
            subjects.count { it.status == LearningMasteryStatus.GETTING_FAMILIAR },
        fairlySteady =
            subjects.count { it.status == LearningMasteryStatus.FAIRLY_STEADY },
        notYetLearned =
            subjects.count { it.status == LearningMasteryStatus.NOT_YET_LEARNED },
    )

private fun learningMasteryDistributionSummary(
    distribution: LearningMasteryStatusDistribution,
): String =
    buildList {
        if (distribution.needsReinforcement > 0) {
            add("${distribution.needsReinforcement} 科需要再巩固")
        }
        if (distribution.gettingFamiliar > 0) {
            add("${distribution.gettingFamiliar} 科正在熟悉")
        }
        if (distribution.fairlySteady > 0) {
            add("${distribution.fairlySteady} 科比较稳")
        }
        if (distribution.notYetLearned > 0) {
            add("${distribution.notYetLearned} 科还没学到")
        }
    }.ifEmpty { listOf("学习记录还不多") }.joinToString("，")

private fun learningMasterySummaryText(
    subjects: List<LearningMasterySubjectOverview>,
): String {
    val remaining = subjects.toMutableList()
    val needsCount = remaining.count { it.status == LearningMasteryStatus.NEEDS_REINFORCEMENT }
    remaining.removeAll { it.status == LearningMasteryStatus.NEEDS_REINFORCEMENT }
    val fluctuatingCount =
        remaining.count { it.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING }
    remaining.removeAll { it.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING }
    val familiarCount = remaining.count { it.status == LearningMasteryStatus.GETTING_FAMILIAR }
    remaining.removeAll { it.status == LearningMasteryStatus.GETTING_FAMILIAR }
    val steadyCount = remaining.count { it.status == LearningMasteryStatus.FAIRLY_STEADY }
    return buildList {
        if (needsCount > 0) add("有 $needsCount 科需要再巩固")
        if (fluctuatingCount > 0) add("有 $fluctuatingCount 科最近有波动")
        if (familiarCount > 0) add("有 $familiarCount 科正在熟悉")
        if (steadyCount > 0) add("有 $steadyCount 科比较稳")
    }.ifEmpty { listOf("学习记录还不多") }.joinToString("，")
}

@Composable
private fun SubjectOverviewRow(
    subjects: List<LearningMasterySubjectOverview>,
    selectedSubject: LearningMasterySubject?,
    onSubjectSelected: (LearningMasterySubject) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = SmartDimens.Space8)
                .testTag("learning_mastery_subjects"),
        horizontalArrangement = Arrangement.spacedBy(SmartDimens.Space12),
    ) {
        subjects.forEach { summary ->
            SubjectOverviewCard(
                summary = summary,
                selected = summary.subject == selectedSubject,
                onClick = { onSubjectSelected(summary.subject) },
            )
        }
    }
}

@Composable
private fun SubjectOverviewCard(
    summary: LearningMasterySubjectOverview,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SmartDimens.SurfaceRadius)
    val status = summary.status.studentLabel()
    val recentChange =
        summary.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING
    Column(
        modifier =
            Modifier
                .width(142.dp)
                .defaultMinSize(minHeight = 92.dp)
                .background(
                    color = if (selected) JadeSoft else Color.Transparent,
                    shape = shape,
                ).border(
                    width = 1.dp,
                    color = if (selected) JadeActive else JadeSoft,
                    shape = shape,
                ).selectable(
                    selected = selected,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics(mergeDescendants = true) {
                    this.selected = selected
                    contentDescription =
                        buildString {
                            append(summary.subject.studentLabel())
                            append('，')
                            append(status)
                            if (recentChange) append("，最近有波动")
                        }
                }.padding(horizontal = SmartDimens.Space16, vertical = SmartDimens.Space12)
                .testTag("learning_mastery_subject:${summary.subject.name}"),
    ) {
        Text(
            text = summary.subject.studentLabel(),
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = status,
            modifier = Modifier.padding(top = 5.dp),
            color =
                when (summary.status) {
                    LearningMasteryStatus.FAIRLY_STEADY -> JadeActive
                    LearningMasteryStatus.NEEDS_REINFORCEMENT -> Ink
                    else -> InkSecondary
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (recentChange) {
            Text(
                text = "最近有波动",
                modifier = Modifier.padding(top = 3.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LearningMasteryTimelineSections(
    repository: LearningMasteryDisplayRepository,
    revision: LearningMasteryProjectionRevision,
    subject: LearningMasterySubject,
    range: LearningMasteryTimelineRange,
    onRangeSelected: (LearningMasteryTimelineRange) -> Unit,
) {
    var retryKey by remember(revision.opaqueValue, subject) { mutableIntStateOf(0) }
    val timelineState by produceState<LearningMasteryLoadState<LearningMasteryTimeline>>(
        initialValue = LearningMasteryLoadState.Loading,
        repository,
        revision.opaqueValue,
        subject,
        range,
        retryKey,
    ) {
        repository.observeSubjectTimeline(
            LearningMasteryTimelineRequest(
                subject = subject,
                revision = revision,
                range = range,
            ),
        ).collect { value = it }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = SmartDimens.Space24)
                .testTag("learning_mastery_timeline:${subject.name}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(
                title = "近期趋势",
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(SmartDimens.Space8))
            OutlineActionChip(
                text = "7天",
                selected = range == LearningMasteryTimelineRange.LAST_7_DAYS,
                onClick = { onRangeSelected(LearningMasteryTimelineRange.LAST_7_DAYS) },
                modifier = Modifier.testTag("learning_mastery_timeline_range_7"),
            )
            Spacer(Modifier.width(SmartDimens.Space8))
            OutlineActionChip(
                text = "30天",
                selected = range == LearningMasteryTimelineRange.LAST_30_DAYS,
                onClick = { onRangeSelected(LearningMasteryTimelineRange.LAST_30_DAYS) },
                modifier = Modifier.testTag("learning_mastery_timeline_range_30"),
            )
        }
        when (val state = timelineState) {
            LearningMasteryLoadState.Loading -> LearningMasteryLoading(compact = true)
            is LearningMasteryLoadState.Error ->
                LearningMasteryError(compact = true, onRetry = { retryKey += 1 })
            is LearningMasteryLoadState.Empty ->
                LearningMasteryMessage(
                    text = "这科还没有学习记录",
                    tag = "learning_mastery_timeline_empty",
                )
            is LearningMasteryLoadState.Content ->
                LearningMasteryTimelineChart(
                    timeline = state.value,
                    modifier = Modifier.padding(top = SmartDimens.Space8),
                )
        }
    }
}

@Composable
private fun LearningMasteryTimelineChart(
    timeline: LearningMasteryTimeline,
    modifier: Modifier = Modifier,
) {
    val activeEntries = timeline.entries.filter { it.attempts > 0 }
    val maxAttempts = activeEntries.maxOfOrNull { it.attempts } ?: 0
    val summary = learningMasteryTimelineSummary(timeline)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = summary
                }
                .testTag("learning_mastery_timeline_chart"),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        JadeSoft.copy(alpha = 0.42f),
                        RoundedCornerShape(SmartDimens.SurfaceRadius),
                    )
                    .padding(horizontal = SmartDimens.Space12, vertical = SmartDimens.Space12),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            timeline.entries.forEach { entry ->
                val barHeight =
                    if (entry.attempts == 0 || maxAttempts == 0) {
                        4.dp
                    } else {
                        (entry.attempts.toFloat() / maxAttempts * 68f).dp + 4.dp
                    }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(barHeight)
                            .background(
                                color = entry.signal.chartColor(),
                                shape =
                                    RoundedCornerShape(
                                        topStart = 3.dp,
                                        topEnd = 3.dp,
                                    ),
                            )
                            .semantics { contentDescription = entry.semanticDescription() },
                )
            }
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = SmartDimens.Space8),
            horizontalArrangement = Arrangement.spacedBy(SmartDimens.Space12),
        ) {
            TimelineLegendItem(LearningMasteryTimelineSignal.PROGRESS, "有进步")
            TimelineLegendItem(LearningMasteryTimelineSignal.MIXED, "有波动")
            TimelineLegendItem(LearningMasteryTimelineSignal.NEEDS_ATTENTION, "需要留意")
        }
    }
}

@Composable
private fun TimelineLegendItem(
    signal: LearningMasteryTimelineSignal,
    label: String,
) {
    LegendItem(color = signal.chartColor(), label = label)
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .background(color, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun learningMasteryTimelineSummary(timeline: LearningMasteryTimeline): String {
    val active = timeline.entries.filter { it.attempts > 0 }
    val progress = active.count { it.signal == LearningMasteryTimelineSignal.PROGRESS }
    val attention =
        active.count { it.signal == LearningMasteryTimelineSignal.NEEDS_ATTENTION }
    val intensive = active.count { it.activity == LearningMasteryTimelineActivity.INTENSIVE }
    return buildString {
        append("最近 ${timeline.entries.size} 天，${active.size} 天有学习记录，")
        append("共 ${active.sumOf { it.attempts }} 次练习")
        if (progress > 0) append("，$progress 天有进步")
        if (attention > 0) append("，$attention 天需要留意")
        if (intensive > 0) append("，$intensive 天练习较多")
    }
}

private fun LearningMasteryTimelineEntry.semanticDescription(): String =
    "第 $day 天，${signal.studentLabel()}，$attempts 次练习，涉及 $knowledgePoints 个知识点"

private fun LearningMasteryTimelineSignal.studentLabel(): String =
    when (this) {
        LearningMasteryTimelineSignal.PROGRESS -> "有进步"
        LearningMasteryTimelineSignal.MIXED -> "有波动"
        LearningMasteryTimelineSignal.NEEDS_ATTENTION -> "需要留意"
        LearningMasteryTimelineSignal.NO_ACTIVITY -> "没有记录"
    }

@Composable
private fun LearningMasteryTimelineSignal.chartColor(): Color =
    when (this) {
        LearningMasteryTimelineSignal.PROGRESS -> JadeActive
        LearningMasteryTimelineSignal.MIXED -> JadeMuted
        LearningMasteryTimelineSignal.NEEDS_ATTENTION -> ErrorWarm
        LearningMasteryTimelineSignal.NO_ACTIVITY -> Track
    }

@Composable
private fun SectionProgressSummary(
    items: List<LearningMasteryKnowledgeItem>,
    subject: LearningMasterySubject,
) {
    val sections = learningMasterySectionProgress(items, subject)
    if (sections.isEmpty()) return
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("learning_mastery_section_progress"),
    ) {
        SectionHeader("板块进度")
        sections.forEachIndexed { index, section ->
            if (index > 0) {
                PaperDivider(Modifier.padding(vertical = SmartDimens.Space12))
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = SmartDimens.Space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = section.section,
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "${section.total} 个知识点",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                distributionSegment(section.needsReinforcement, ErrorWarm)
                distributionSegment(section.familiarizing, JadeMuted)
                distributionSegment(section.steady, JadeActive)
            }
            Text(
                text =
                    "需要再巩固 ${section.needsReinforcement} · " +
                        "正在熟悉 ${section.familiarizing} · " +
                        "比较稳 ${section.steady}",
                modifier = Modifier.padding(top = 4.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal data class LearningMasterySectionProgress(
    val section: String,
    val total: Int,
    val needsReinforcement: Int,
    val familiarizing: Int,
    val steady: Int,
)

internal fun learningMasterySectionProgress(
    items: List<LearningMasteryKnowledgeItem>,
    subject: LearningMasterySubject,
): List<LearningMasterySectionProgress> =
    items
        .groupBy { it.sectionLabel(subject) ?: "其他" }
        .map { (section, sectionItems) ->
            LearningMasterySectionProgress(
                section = section,
                total = sectionItems.size,
                needsReinforcement =
                    sectionItems.count {
                        it.status == LearningMasteryStatus.NEEDS_REINFORCEMENT
                    },
                familiarizing =
                    sectionItems.count {
                        it.status == LearningMasteryStatus.GETTING_FAMILIAR
                    },
                steady =
                    sectionItems.count {
                        it.status == LearningMasteryStatus.FAIRLY_STEADY
                    },
            )
        }
        .sortedBy(LearningMasterySectionProgress::section)

internal fun learningMasterySectionNames(
    items: List<LearningMasteryKnowledgeItem>,
    subject: LearningMasterySubject,
): List<String> =
    items
        .map { it.sectionLabel(subject) ?: "其他" }
        .distinct()
        .sorted()

internal fun learningMasterySectionItems(
    items: List<LearningMasteryKnowledgeItem>,
    subject: LearningMasterySubject,
    selectedSection: String?,
): List<LearningMasteryKnowledgeItem> =
    if (selectedSection == null) {
        items
    } else {
        items.filter { (it.sectionLabel(subject) ?: "其他") == selectedSection }
    }

@Composable
private fun LearningMasteryKnowledgeSections(
    repository: LearningMasteryDisplayRepository,
    revision: LearningMasteryProjectionRevision,
    subject: LearningMasterySubject,
    onProjectionChanged: () -> Unit,
) {
    var items by remember(revision.opaqueValue, subject) {
        mutableStateOf(emptyList<LearningMasteryKnowledgeItem>())
    }
    var nextCursor by remember(revision.opaqueValue, subject) {
        mutableStateOf<LearningMasteryPageCursor?>(null)
    }
    var requestedCursor by remember(revision.opaqueValue, subject) {
        mutableStateOf<LearningMasteryPageCursor?>(null)
    }
    var loading by remember(revision.opaqueValue, subject) { mutableStateOf(true) }
    var empty by remember(revision.opaqueValue, subject) { mutableStateOf(false) }
    var error by remember(revision.opaqueValue, subject) {
        mutableStateOf<LearningMasteryDisplayError?>(null)
    }
    var retryKey by remember(revision.opaqueValue, subject) { mutableIntStateOf(0) }
    var selectedSection by remember(revision.opaqueValue, subject) {
        mutableStateOf<String?>(null)
    }

    LaunchedEffect(
        repository,
        revision.opaqueValue,
        subject,
        requestedCursor?.opaqueValue,
        retryKey,
    ) {
        val pageCursor = requestedCursor
        loading = true
        error = null
        repository.observeKnowledgePage(
            LearningMasteryPageRequest(
                subject = subject,
                revision = revision,
                cursor = pageCursor,
                limit = LearningMasteryPageRequest.MAX_LIMIT,
            ),
        ).collect { state ->
            when (state) {
                LearningMasteryLoadState.Loading -> loading = true
                is LearningMasteryLoadState.Content -> {
                    items =
                        if (pageCursor == null) {
                            state.value.items
                        } else {
                            (items + state.value.items)
                                .distinctBy { it.key }
                        }
                    nextCursor = state.value.nextCursor
                    empty = false
                    loading = false
                }
                is LearningMasteryLoadState.Empty -> {
                    if (pageCursor == null) items = emptyList()
                    nextCursor = null
                    empty = items.isEmpty()
                    loading = false
                }
                is LearningMasteryLoadState.Error -> {
                    error = state.reason
                    loading = false
                    if (state.reason == LearningMasteryDisplayError.PROJECTION_CHANGED) {
                        items = emptyList()
                        nextCursor = null
                        empty = false
                        onProjectionChanged()
                    }
                }
            }
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = SmartDimens.Space24)
                .testTag("learning_mastery_knowledge:${subject.name}"),
    ) {
        if (items.isEmpty()) {
            when {
                loading -> LearningMasteryLoading(compact = true)
                error != null ->
                    LearningMasteryError(
                        compact = true,
                        onRetry = { retryKey += 1 },
                    )
                empty -> LearningMasterySubjectEmpty()
            }
            return@Column
        }

        val sectionNames = learningMasterySectionNames(items, subject)
        val visibleItems = learningMasterySectionItems(items, subject, selectedSection)
        val presentation = learningMasteryKnowledgePresentation(visibleItems)
        val weakItems = presentation.weakItems
        val regularItems = presentation.regularItems
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("learning_mastery_section_filter"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlineActionChip(
                text = "全部",
                selected = selectedSection == null,
                onClick = { selectedSection = null },
                modifier =
                    Modifier.testTag(
                        "learning_mastery_section_all",
                    ),
            )
            sectionNames.forEachIndexed { index, section ->
                OutlineActionChip(
                    text = section,
                    selected = selectedSection == section,
                    onClick = { selectedSection = section },
                    modifier =
                        Modifier.testTag(
                            "learning_mastery_section_$index",
                        ),
                )
            }
        }
        SectionProgressSummary(
            items = visibleItems,
            subject = subject,
        )
        if (weakItems.isNotEmpty()) {
            SectionHeader("薄弱知识")
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(
                            JadeSoft.copy(alpha = 0.42f),
                            RoundedCornerShape(SmartDimens.SurfaceRadius),
                        ).padding(horizontal = SmartDimens.Space16, vertical = SmartDimens.Space12)
                        .testTag("learning_mastery_weak"),
            ) {
                weakItems.forEachIndexed { index, item ->
                    if (index > 0) PaperDivider(Modifier.padding(vertical = SmartDimens.Space8))
                    KnowledgeItemRow(item)
                }
            }
        }

        if (regularItems.isNotEmpty()) {
            SectionHeader(
                title = "知识点",
                modifier =
                    Modifier.padding(
                        top = if (weakItems.isEmpty()) 0.dp else SmartDimens.Space24,
                    ),
            )
            KnowledgeItemsBySection(
                items = regularItems,
                subject = subject,
            )
        }

        when {
            loading -> LearningMasteryLoading(compact = true)
            error != null ->
                LearningMasteryError(
                    compact = true,
                    onRetry = { retryKey += 1 },
                )
            nextCursor != null ->
                TextButton(
                    onClick = { requestedCursor = nextCursor },
                    modifier =
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("learning_mastery_more"),
                ) {
                    Text("查看更多")
                }
        }
    }
}

internal data class LearningMasteryKnowledgePresentation(
    val weakItems: List<LearningMasteryKnowledgeItem>,
    val regularItems: List<LearningMasteryKnowledgeItem>,
)

internal fun learningMasteryKnowledgePresentation(
    items: List<LearningMasteryKnowledgeItem>,
): LearningMasteryKnowledgePresentation {
    val uniqueItems = items.distinctBy { it.key }
    val weakItems =
        uniqueItems
            .filter {
                it.status == LearningMasteryStatus.NEEDS_REINFORCEMENT ||
                    it.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING
            }.take(MAX_WEAK_KNOWLEDGE_ITEMS)
    val weakItemKeys = weakItems.mapTo(mutableSetOf()) { it.key }
    return LearningMasteryKnowledgePresentation(
        weakItems = weakItems,
        regularItems = uniqueItems.filterNot { it.key in weakItemKeys },
    )
}

@Composable
private fun KnowledgeItemsBySection(
    items: List<LearningMasteryKnowledgeItem>,
    subject: LearningMasterySubject,
) {
    val sections =
        items
            .groupBy { it.sectionLabel(subject) }
            .toList()
    sections.forEachIndexed { sectionIndex, (section, sectionItems) ->
        if (sectionIndex > 0) {
            PaperDivider(Modifier.padding(vertical = SmartDimens.Space12))
        }
        if (section != null) {
            Text(
                text = section,
                modifier =
                    Modifier
                        .padding(
                            top = if (sectionIndex == 0) SmartDimens.Space8 else 0.dp,
                        )
                        .semantics { heading() },
                color = InkSecondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        sectionItems.forEach { item ->
            KnowledgeItemRow(
                item = item,
                modifier = Modifier.padding(top = SmartDimens.Space12),
            )
        }
    }
}

@Composable
private fun KnowledgeItemRow(
    item: LearningMasteryKnowledgeItem,
    modifier: Modifier = Modifier,
) {
    LearningMasteryTextRow(
        title = item.displayName,
        state = item.status.studentLabel(),
        change =
            "最近有波动".takeIf {
                item.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING
            },
        modifier =
            modifier
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        buildString {
                            append(item.displayName)
                            append('，')
                            append(item.status.studentLabel())
                            if (item.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING) {
                                append("，最近有波动")
                            }
                        }
                }.testTag("learning_mastery_point:${item.key.opaqueValue}"),
    )
}

@Composable
private fun LearningMasteryTextRow(
    title: String,
    state: String,
    change: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text =
                if (change == null) {
                    state
                } else {
                    "$state · $change"
                },
            modifier = Modifier.padding(top = 2.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LearningMasteryLoading(compact: Boolean = false) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = if (compact) SmartDimens.Space12 else SmartDimens.Space24,
                )
                .semantics { contentDescription = "加载中" }
                .testTag("learning_mastery_loading"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(if (compact) 20.dp else 24.dp),
            color = JadeActive,
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun LearningMasteryEmpty() {
    LearningMasteryMessage(
        text = "还没有学习记录",
        tag = "learning_mastery_empty",
    )
}

@Composable
private fun LearningMasterySubjectEmpty() {
    LearningMasteryMessage(
        text = "这科还没有学习记录",
        tag = "learning_mastery_subject_empty",
    )
}

@Composable
private fun LearningMasteryError(
    compact: Boolean = false,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    vertical = if (compact) SmartDimens.Space8 else SmartDimens.Space24,
                )
                .testTag("learning_mastery_error"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "暂时打不开",
            color = InkSecondary,
            style = MaterialTheme.typography.bodyLarge,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Text("重试")
        }
    }
}

@Composable
private fun LearningMasteryMessage(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = SmartDimens.Space16)
                .background(
                    JadeSoft.copy(alpha = 0.42f),
                    RoundedCornerShape(SmartDimens.SurfaceRadius),
                )
                .padding(horizontal = SmartDimens.Space16, vertical = SmartDimens.Space16)
                .testTag(tag),
        color = Ink,
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun List<LearningMasterySubjectOverview>.preferredSubject():
    LearningMasterySubjectOverview? =
    firstOrNull { it.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING }
        ?: firstOrNull { it.status == LearningMasteryStatus.NEEDS_REINFORCEMENT }
        ?: firstOrNull { it.status == LearningMasteryStatus.GETTING_FAMILIAR }
        ?: firstOrNull { it.status != LearningMasteryStatus.NOT_YET_LEARNED }
        ?: firstOrNull()

private fun LearningMasteryKnowledgeItem.sectionLabel(
    subject: LearningMasterySubject,
): String? =
    displayPath.lastOrNull()?.takeUnless { it == subject.studentLabel() }

private const val MAX_WEAK_KNOWLEDGE_ITEMS = 4
