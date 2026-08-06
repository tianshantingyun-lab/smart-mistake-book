package com.tingyun.smartmistakebook

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import com.tingyun.smartmistakebook.core.domain.ReviewPacingLevel
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import com.tingyun.smartmistakebook.core.ui.studentLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ReminderScreen(
    repository: ReviewReminderRepository,
    onRefreshSchedule: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val preferences by repository.preferences.collectAsStateWithLifecycle(
        initialValue = ReviewReminderPreferences(),
    )
    val todayEpochDay = remember { LocalDate.now(ZoneId.systemDefault()).toEpochDay() }
    var permissionGranted by remember { mutableStateOf(context.canPostReviewNotifications()) }
    var statusMessage by rememberSaveable { mutableStateOf<String?>(null) }

    val openNotificationSettings = {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, REVIEW_REMINDER_CHANNEL_ID)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        }
        context.startActivity(intent)
    }

    fun saveEnabled(enabled: Boolean) {
        statusMessage = null
        scope.launch {
            try {
                repository.setEnabled(enabled)
                statusMessage = if (enabled) "已开启每日提醒" else "已关闭每日提醒"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusMessage = "保存失败，请稍后再试；原有设置没有被替换。"
            }
        }
    }

    fun saveTime(minutesAfterMidnight: Int) {
        statusMessage = null
        scope.launch {
            try {
                repository.setReminderTime(minutesAfterMidnight)
                statusMessage = "提醒时间已改为 ${formatReminderTime(minutesAfterMidnight)}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusMessage = "时间保存失败，请稍后再试。"
            }
        }
    }

    fun savePacing(pacingLevel: ReviewPacingLevel) {
        statusMessage = null
        scope.launch {
            try {
                repository.setPacingLevel(pacingLevel)
                statusMessage = "每日复习强度已改为 ${pacingLevel.studentLabel()}"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusMessage = "复习强度保存失败，请稍后再试。"
            }
        }
    }

    fun saveExamTarget(
        subject: SubjectKind?,
        days: Int?,
    ) {
        statusMessage = null
        scope.launch {
            try {
                val examEpochDay =
                    if (subject == null || days == null) {
                        null
                    } else {
                        todayEpochDay + days
                    }
                repository.setExamTarget(subject, examEpochDay)
                statusMessage =
                    if (subject == null) {
                        "已清除考试目标"
                    } else {
                        "${subject.studentLabel()}安排在 ${days} 天后"
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusMessage = "考试目标保存失败，请稍后再试。"
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted && context.canPostReviewNotifications()
        if (permissionGranted) {
            saveEnabled(true)
        } else if (granted) {
            statusMessage = "复习提醒频道已关闭，请在系统设置中允许后再开启。"
            openNotificationSettings()
        } else {
            statusMessage = "没有通知权限，提醒未开启；复习功能仍可正常使用。"
        }
    }

    DisposableEffect(lifecycleOwner, preferences.enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val latestPermission = context.canPostReviewNotifications()
                if (latestPermission && !permissionGranted && preferences.enabled) {
                    onRefreshSchedule()
                }
                permissionGranted = latestPermission
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RootPageColumn(modifier = Modifier.testTag("review_reminder_screen")) {
        SecondaryHeader(title = "复习提醒", onBack = onBack)
        SectionHeader("复习节奏")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("每天提醒我复习", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    if (preferences.enabled) {
                        "当前计划：${formatReminderTime(preferences.minutesAfterMidnight)}"
                    } else {
                        "关闭后仍可随时从“复习”栏开始"
                    },
                    color = InkSecondary,
                )
            }
            Switch(
                checked = preferences.enabled,
                onCheckedChange = { enabled ->
                    statusMessage = null
                    when {
                        !enabled -> saveEnabled(false)
                        permissionGranted -> saveEnabled(true)
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !context.hasPostNotificationsPermission() ->
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else -> {
                            statusMessage = "系统通知已关闭，请在系统设置中允许后再开启。"
                            openNotificationSettings()
                        }
                    }
                },
                modifier = Modifier.testTag("reminder_enabled_switch"),
            )
        }
        Text(
            "每日复习强度",
            modifier = Modifier.padding(top = 18.dp),
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
        )
        Text(
            "按当天可用时间选择；保存后次日计划按新强度安排。",
            modifier = Modifier.padding(top = 4.dp),
            color = InkSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            ReviewPacingChoice(
                label = "轻量",
                level = ReviewPacingLevel.LIGHT,
                selected = preferences.pacingLevel == ReviewPacingLevel.LIGHT,
                onSelect = ::savePacing,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ReviewPacingChoice(
                label = "标准",
                level = ReviewPacingLevel.STANDARD,
                selected = preferences.pacingLevel == ReviewPacingLevel.STANDARD,
                onSelect = ::savePacing,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ReviewPacingChoice(
                label = "加强",
                level = ReviewPacingLevel.STRONG,
                selected = preferences.pacingLevel == ReviewPacingLevel.STRONG,
                onSelect = ::savePacing,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "当前：${preferences.pacingLevel.studentLabel()} · ${preferences.pacingLevel.studentDescription()}",
            modifier = Modifier
                .padding(top = 10.dp)
                .testTag("reminder_pacing_summary"),
            style = MaterialTheme.typography.bodyMedium,
            color = Ink,
        )
        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("考试目标")
        Text(
            "考试前优先排对应科目；不设置就不会额外加权。",
            modifier = Modifier.padding(top = 6.dp),
            color = InkSecondary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubjectKind.entries
                .filterNot { it == SubjectKind.GENERAL }
                .forEach { subject ->
                    FilterChip(
                        selected = preferences.examSubject == subject,
                        onClick = { saveExamTarget(subject, 14) },
                        label = { Text(subject.studentLabel()) },
                        modifier = Modifier.testTag("reminder_exam_${subject.name}"),
                    )
                }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExamDayChoice(
                label = "7天后",
                days = 7,
                todayEpochDay = todayEpochDay,
                preferences = preferences,
                onSelect = { days -> preferences.examSubject?.let { saveExamTarget(it, days) } },
                modifier = Modifier.weight(1f),
            )
            ExamDayChoice(
                label = "14天后",
                days = 14,
                todayEpochDay = todayEpochDay,
                preferences = preferences,
                onSelect = { days -> preferences.examSubject?.let { saveExamTarget(it, days) } },
                modifier = Modifier.weight(1f),
            )
            ExamDayChoice(
                label = "30天后",
                days = 30,
                todayEpochDay = todayEpochDay,
                preferences = preferences,
                onSelect = { days -> preferences.examSubject?.let { saveExamTarget(it, days) } },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = preferences.examSubject == null,
                onClick = { saveExamTarget(null, null) },
                label = { Text("清除") },
                modifier = Modifier.weight(1f).testTag("reminder_exam_clear"),
            )
        }

        PaperDivider(Modifier.padding(vertical = 18.dp))
        SectionHeader("提醒时间")
        Text(
            formatReminderTime(preferences.minutesAfterMidnight),
            modifier = Modifier
                .padding(top = 12.dp)
                .testTag("reminder_time_value"),
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
        )
        Text("选一个符合日常节奏的时间，也可以自定义。", color = InkSecondary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            ReminderTimePreset(
                label = "放学后",
                minutes = 18 * 60 + 30,
                selectedMinutes = preferences.minutesAfterMidnight,
                onSelect = ::saveTime,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ReminderTimePreset(
                label = "晚自习",
                minutes = 20 * 60 + 30,
                selectedMinutes = preferences.minutesAfterMidnight,
                onSelect = ::saveTime,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ReminderTimePreset(
                label = "睡前",
                minutes = 22 * 60,
                selectedMinutes = preferences.minutesAfterMidnight,
                onSelect = ::saveTime,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(
            onClick = {
                val hour = preferences.minutesAfterMidnight / 60
                val minute = preferences.minutesAfterMidnight % 60
                TimePickerDialog(
                    context,
                    { _, selectedHour, selectedMinute ->
                        saveTime(selectedHour * 60 + selectedMinute)
                    },
                    hour,
                    minute,
                    true,
                ).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(48.dp)
                .testTag("reminder_custom_time"),
        ) {
            Text("自定义时间")
        }

        if (preferences.enabled && !permissionGranted) {
            Text(
                "提醒偏好已保留，但系统通知权限已关闭，所以当前不会弹出提醒。",
                modifier = Modifier.padding(top = 16.dp),
                color = InkSecondary,
            )
            OutlinedButton(
                onClick = openNotificationSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(48.dp)
                    .testTag("reminder_open_system_settings"),
            ) {
                Text("打开系统通知设置")
            }
        }

        statusMessage?.let { message ->
            Text(
                message,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .testTag("reminder_status_message"),
                color = InkSecondary,
            )
        }

        PaperDivider(Modifier.padding(vertical = 18.dp))
        Text(
            "提醒只负责叫你回来：今日题单仍由本机学习记录和遗忘曲线决定。锁屏不会显示具体错题，也不会因为提醒而后台调用大模型。",
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReminderTimePreset(
    label: String,
    minutes: Int,
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selectedMinutes == minutes,
        onClick = { onSelect(minutes) },
        label = { Text(label) },
        modifier = modifier.testTag("reminder_preset_$minutes"),
    )
}

@Composable
private fun ReviewPacingChoice(
    label: String,
    level: ReviewPacingLevel,
    selected: Boolean,
    onSelect: (ReviewPacingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = { onSelect(level) },
        label = { Text(label) },
        modifier = modifier.testTag("reminder_pacing_${level.name}"),
    )
}

@Composable
private fun ExamDayChoice(
    label: String,
    days: Int,
    todayEpochDay: Long,
    preferences: ReviewReminderPreferences,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected =
        preferences.examSubject != null &&
            preferences.examEpochDay == todayEpochDay + days
    FilterChip(
        selected = selected,
        enabled = preferences.examSubject != null,
        onClick = { onSelect(days) },
        label = { Text(label) },
        modifier = modifier.testTag("reminder_exam_days_$days"),
    )
}

private fun ReviewPacingLevel.studentLabel(): String =
    when (this) {
        ReviewPacingLevel.LIGHT -> "轻量"
        ReviewPacingLevel.STANDARD -> "标准"
        ReviewPacingLevel.STRONG -> "加强"
    }

private fun ReviewPacingLevel.studentDescription(): String =
    when (this) {
        ReviewPacingLevel.LIGHT -> "约10分钟，最多4题"
        ReviewPacingLevel.STANDARD -> "约15分钟，最多5题"
        ReviewPacingLevel.STRONG -> "约25分钟，最多8题"
    }

private fun formatReminderTime(minutesAfterMidnight: Int): String =
    "%02d:%02d".format(minutesAfterMidnight / 60, minutesAfterMidnight % 60)
