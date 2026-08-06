package com.tingyun.smartmistakebook.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubjectOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.studentLabel
import com.tingyun.smartmistakebook.core.ui.SmartColors
import kotlinx.coroutines.flow.collect

/**
 * Production profile gate.
 *
 * The content callback receives only the published narrow capability. No legacy profile snapshot
 * is accepted, so a blocked route cannot read or render old mastery data.
 */
@Composable
fun ProductionProfileRoute(
    capabilityProvider: ProfileProductionCapabilityProvider,
    content: @Composable (ProfileProductionCapability) -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }

    when (decision) {
        is ProfileProductionCapabilityDecision.Available ->
            content(decision.capability)

        is ProfileProductionCapabilityDecision.Blocked ->
            ProfileProductionUnavailable(
                onRetry = { retryRevision += 1 },
                modifier = modifier,
            )
    }
}

/** The production "我的" page reads only the learner-facing mastery projection. */
@Composable
fun ProductionProfileHomeRoute(
    capabilityProvider: ProfileProductionCapabilityProvider,
    onOpenCapability: () -> Unit,
    onOpenLearningMastery: () -> Unit,
    onOpenDataPrivacy: () -> Unit,
    onOpenReminder: () -> Unit,
    onOpenStorage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProductionProfileRoute(
        capabilityProvider = capabilityProvider,
        content = { capability ->
            LearningMasteryProfileHome(
                repository = capability.learningMasteryDisplay,
                onOpenCapability = onOpenCapability,
                onOpenLearningMastery = onOpenLearningMastery,
                onOpenDataPrivacy = onOpenDataPrivacy,
                onOpenReminder = onOpenReminder,
                onOpenStorage = onOpenStorage,
                modifier = modifier,
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun LearningMasteryProfileHome(
    repository: LearningMasteryDisplayRepository,
    onOpenCapability: () -> Unit,
    onOpenLearningMastery: () -> Unit,
    onOpenDataPrivacy: () -> Unit,
    onOpenReminder: () -> Unit,
    onOpenStorage: () -> Unit,
    modifier: Modifier,
) {
    var retryRevision by remember(repository) { mutableIntStateOf(0) }
    val masteryState by
        produceState<LearningMasteryLoadState<LearningMasteryOverview>>(
            initialValue = LearningMasteryLoadState.Loading,
            repository,
            retryRevision,
        ) {
            repository.observeSubjectOverview().collect { value = it }
        }

    RootPageColumn(modifier = modifier.testTag("profile_screen")) {
        SectionHeader("学习掌握")
        when (val state = masteryState) {
            LearningMasteryLoadState.Loading ->
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 16.dp).size(22.dp),
                    color = JadeActive,
                    strokeWidth = 2.dp,
                )
            is LearningMasteryLoadState.Content -> {
                state.value.subjects
                    .sortedWith(compareBy<LearningMasterySubjectOverview>(::masteryPriority))
                    .take(MAX_PROFILE_SUBJECTS)
                    .forEachIndexed { index, subject ->
                        if (index > 0) PaperDivider(Modifier.padding(vertical = 10.dp))
                        MasterySubjectRow(subject)
                    }
                OutlineActionChip(
                    text = "查看学习掌握",
                    onClick = onOpenLearningMastery,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag("profile_open_learning_mastery"),
                )
            }
            is LearningMasteryLoadState.Empty ->
                Text(
                    text = "还没有学习记录",
                    modifier = Modifier.padding(top = 12.dp),
                    color = InkSecondary,
                )
            is LearningMasteryLoadState.Error ->
                OutlineActionChip(
                    text = "重试",
                    onClick = { retryRevision += 1 },
                    modifier = Modifier.padding(top = 12.dp),
                )
        }
        PaperDivider(Modifier.padding(vertical = 16.dp))
        SettingsSection(
            onOpenCapability = onOpenCapability,
            onOpenDataPrivacy = onOpenDataPrivacy,
            onOpenReminder = onOpenReminder,
            onOpenStorage = onOpenStorage,
        )
    }
}

@Composable
private fun MasterySubjectRow(subject: LearningMasterySubjectOverview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = subject.subject.studentLabel(),
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            if (subject.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING) {
                Text(
                    text = "最近有波动",
                    modifier = Modifier.padding(top = 2.dp),
                    color = InkSecondary,
                )
            }
        }
        Text(text = subject.status.studentLabel(), color = InkSecondary)
    }
}

private fun masteryPriority(subject: LearningMasterySubjectOverview): Int =
    when {
        subject.status == LearningMasteryStatus.NEEDS_REINFORCEMENT -> 0
        subject.trend == LearningMasteryTrend.RECENTLY_FLUCTUATING -> 1
        subject.status == LearningMasteryStatus.GETTING_FAMILIAR -> 2
        else -> 3
    }

@Composable
private fun ProfileProductionUnavailable(
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    RootPageColumn(
        modifier = modifier.testTag("profile_production_unavailable"),
    ) {
        Text(
            text = "暂时无法使用",
            color = Ink,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryActionButton(
            text = "重试",
            onClick = onRetry,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("profile_production_retry"),
        )
    }
}

private const val MAX_PROFILE_SUBJECTS = 4
