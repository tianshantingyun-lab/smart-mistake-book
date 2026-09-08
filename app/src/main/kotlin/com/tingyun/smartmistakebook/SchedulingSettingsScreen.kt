package com.tingyun.smartmistakebook

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ExamCalendarEntry
import com.tingyun.smartmistakebook.core.domain.SchedulingOptions
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 复习排程设置（spec mastery-scheduling §2.17/§8-B4）：期望保持率、FSRS 开关、
 * 考试日历。审计 2026-09-09 发现这三个写入路径此前全仓无调用方——`setOptions` /
 * `declareExam` / `removeExam` 只有接口与实现，学生改不了任何排程参数。
 *
 * 排程参数在仓库构造时读取，改动在下次启动生效（spec §2.20：单次会话内投影模型稳定），
 * 因此本页只负责落库，不假装立即生效。
 */
@Composable
internal fun SchedulingSettingsScreen(
    options: SchedulingOptions,
    exams: List<ExamCalendarEntry>,
    onSetOptions: (SchedulingOptions) -> Unit,
    onAddExam: (ExamCalendarEntry) -> Unit,
    onRemoveExam: (String) -> Unit,
    onBack: () -> Unit,
) {
    RootPageColumn(modifier = Modifier.testTag("root_scheduling_settings")) {
        SecondaryHeader(title = "复习排程", onBack = onBack)
        Text(
            text = "改动在下次启动应用时生效。",
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(12.dp))

        SectionHeader(title = "期望保持率")
        Text(
            text = "目标回忆概率 ${(options.desiredRetention * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("scheduling_retention_label"),
        )
        Slider(
            value = options.desiredRetention.toFloat(),
            onValueChange = { value ->
                onSetOptions(options.copy(desiredRetention = value.toDouble()))
            },
            valueRange = 0.7f..0.97f,
            steps = 26,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scheduling_retention_slider"),
        )

        PaperDivider(modifier = Modifier.padding(vertical = 12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("使用 FSRS 排程", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "关闭后回退到旧的指数模型（仅用于排查问题）。",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = options.useFsrsScheduling,
                onCheckedChange = { enabled ->
                    onSetOptions(options.copy(useFsrsScheduling = enabled))
                },
                modifier = Modifier.testTag("scheduling_fsrs_switch"),
            )
        }

        PaperDivider(modifier = Modifier.padding(vertical = 12.dp))

        SectionHeader(title = "考试日历")
        Text(
            text = "申报考试日后，考前两周会自动加大相关科目的复习权重。",
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        ExamCalendarEditor(
            exams = exams,
            onAddExam = onAddExam,
            onRemoveExam = onRemoveExam,
        )
    }
}

@Composable
private fun ExamCalendarEditor(
    exams: List<ExamCalendarEntry>,
    onAddExam: (ExamCalendarEntry) -> Unit,
    onRemoveExam: (String) -> Unit,
) {
    var subject by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var daysFromNow by rememberSaveable { mutableStateOf("30") }

    exams.forEach { exam ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(exam.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${exam.subject} · ${LocalDate.ofEpochDay(exam.examEpochDay)}",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = { onRemoveExam(exam.entryId) },
                modifier = Modifier.testTag("exam_remove_${exam.entryId}"),
            ) {
                Text("删除")
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = subject,
        onValueChange = { subject = it },
        label = { Text("科目（如 数学）") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("exam_subject_field"),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("考试名称（如 期中考试）") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("exam_title_field"),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = daysFromNow,
        onValueChange = { daysFromNow = it.filter(Char::isDigit).take(4) },
        label = { Text("还有几天（如 30）") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("exam_days_field"),
    )
    Spacer(Modifier.height(8.dp))
    val parsedDays = daysFromNow.toLongOrNull()
    val canAdd = subject.isNotBlank() && title.isNotBlank() && parsedDays != null && parsedDays >= 0
    OutlinedButton(
        onClick = {
            val days = parsedDays ?: return@OutlinedButton
            onAddExam(
                ExamCalendarEntry(
                    entryId = "exam-${subject.trim()}-${System.currentTimeMillis()}",
                    subject = subject.trim(),
                    examEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay() + days,
                    title = title.trim(),
                ),
            )
            subject = ""
            title = ""
            daysFromNow = "30"
        },
        enabled = canAdd,
        modifier = Modifier.testTag("exam_add_button"),
    ) {
        Text("添加考试")
    }
}
