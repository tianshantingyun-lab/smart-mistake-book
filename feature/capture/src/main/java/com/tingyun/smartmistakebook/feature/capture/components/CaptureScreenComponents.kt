package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton

@Composable
internal fun WorkspaceSaveErrorCard(
    message: String,
    saving: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ErrorWarm, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("capture_workspace_save_error"),
    ) {
        Text(message, color = ErrorWarm, style = MaterialTheme.typography.bodySmall)
        OutlineActionChip(
            text = if (saving) "正在重试…" else "重新保存",
            onClick = onRetry,
            enabled = !saving,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("capture_workspace_save_retry"),
        )
    }
}

@Composable
internal fun ReplacementStatusCard(
    isReplacing: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onKeepCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (error == null) Outline else ErrorWarm, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag("capture_replacement_status"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isReplacing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = JadeActive,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = ErrorWarm,
                )
            }
            Text(
                text = if (isReplacing) "正在安全替换题图" else error.orEmpty(),
                modifier = Modifier.padding(start = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!isReplacing && error != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineActionChip(
                    text = "重试替换",
                    onClick = onRetry,
                    modifier = Modifier.testTag("capture_replacement_retry"),
                )
                OutlineActionChip(
                    text = "保留原题",
                    onClick = onKeepCurrent,
                    modifier = Modifier.testTag("capture_replacement_keep"),
                )
            }
        }
    }
}

@Composable
internal fun CaptureTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .testTag("capture_back_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Ink,
            )
        }
        Text(
            text = title,
            modifier = Modifier.padding(start = 4.dp),
            color = Ink,
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
internal fun CaptureSourcePageBar(
    pages: List<CaptureSourcePage>,
    selectedPageIndex: Int,
    enabled: Boolean,
    onSelectPage: (Int) -> Unit,
    onAddByCamera: () -> Unit,
    onAddFromPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "本题原图 · ${pages.size} 页",
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.forEach { page ->
                FilterChip(
                    selected = page.pageIndex == selectedPageIndex,
                    onClick = { onSelectPage(page.pageIndex) },
                    label = { Text("第 ${page.pageIndex + 1} 页") },
                    modifier = Modifier.testTag("capture_source_page_${page.pageIndex}"),
                )
            }
            IconButton(
                onClick = onAddByCamera,
                enabled = enabled,
                modifier = Modifier.testTag("capture_add_page_camera"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = "拍照补充本题下一页",
                    tint = JadeActive,
                )
            }
            IconButton(
                onClick = onAddFromPhotos,
                enabled = enabled,
                modifier = Modifier.testTag("capture_add_page_photos"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = "从照片补充本题下一页",
                    tint = JadeActive,
                )
            }
        }
        Text(
            text = "跨页题按顺序补拍；每一页原图都会保留。",
            modifier = Modifier.padding(top = 4.dp),
            color = InkMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun CaptureActions(
    provider: ProviderCapabilitySnapshot?,
    entryOrigin: CaptureEntryOrigin = CaptureEntryOrigin.LIBRARY,
    onTakePicture: () -> Unit,
    onPickPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        PrimaryActionButton(
            text = "拍照并整理",
            onClick = onTakePicture,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("capture_take_picture_button"),
            icon = Icons.Outlined.PhotoCamera,
            contentDescription = "使用系统相机拍摄题目并整理",
        )
        OutlineActionChip(
            text = "选图并整理",
            onClick = onPickPhoto,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .testTag("capture_pick_photo_button"),
            icon = Icons.Outlined.PhotoLibrary,
            contentDescription = "使用系统照片选择器选择题目图片并整理",
        )
    }
}

@Composable
internal fun CaptureModelSetupBlock(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Outline, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag("capture_model_setup_block"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "配置好模型后，拍照题图才会交给模型整理",
            color = Ink,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlineActionChip(
            text = "去设置",
            onClick = onOpenSettings,
            modifier = Modifier.testTag("capture_model_setup_settings"),
        )
    }
}

@Composable
internal fun CaptureGuidance(modifier: Modifier = Modifier) {
    Text(
        text = "尽量把题干、选项和配图拍完整。",
        modifier = modifier.fillMaxWidth(),
        color = InkSecondary,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
internal fun AwaitingCorrectionCard(
    onRetake: () -> Unit,
    onPickAnother: () -> Unit,
    onStartCorrection: () -> Unit,
    isImporting: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ErrorWarm, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(16.dp)
            .testTag("capture_pending_correction"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = ErrorWarm,
            )
            Text(
                text = if (isImporting) "正在保存题图" else "题图待重试",
                modifier = Modifier.padding(start = 10.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = if (isImporting) {
                "图片已收到，正在自动保存到本机；保存完成后会继续整理题面。"
            } else {
                "这张图还没有安全保存，已暂停后续处理。请重试，或重新拍摄。"
            },
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlineActionChip(
                text = "重新拍摄",
                onClick = onRetake,
                modifier = Modifier
                    .weight(1f)
                    .testTag("capture_retake_button"),
                icon = Icons.Outlined.PhotoCamera,
                enabled = !isImporting,
            )
            OutlineActionChip(
                text = "另选照片",
                onClick = onPickAnother,
                modifier = Modifier
                    .weight(1f)
                    .testTag("capture_pick_another_button"),
                icon = Icons.Outlined.PhotoLibrary,
                enabled = !isImporting,
            )
        }
        PrimaryActionButton(
            text = if (isImporting) "正在安全保存…" else "重试保存并继续",
            onClick = onStartCorrection,
            enabled = !isImporting,
            icon = Icons.Outlined.Save,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .testTag("capture_start_correction_button"),
        )
    }
}

@Composable
internal fun CaptureCommittedCard(
    onView: () -> Unit,
    onCaptureAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(JadeSoft.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(16.dp)
            .testTag("capture_committed"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = JadeActive,
            )
            Text(
                text = "已经存入错题本",
                modifier = Modifier.padding(start = 8.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = "原图和整理后的题面都已保存。现在可以查看这道题，或继续录入下一道。",
            modifier = Modifier.padding(top = 10.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        PrimaryActionButton(
            text = "查看这道题",
            onClick = onView,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .testTag("capture_view_saved_item"),
        )
        OutlineActionChip(
            text = "再录一道",
            onClick = onCaptureAnother,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("capture_another_item"),
        )
    }
}

@Composable
internal fun CaptureError(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, ErrorWarm, RoundedCornerShape(8.dp))
            .padding(12.dp)
            .testTag("capture_error"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = ErrorWarm,
        )
        Text(
            text = message,
            modifier = Modifier.padding(start = 8.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun CaptureResumeStateCard(
    state: CaptureResumeLoadState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = state == CaptureResumeLoadState.LOADING ||
        state == CaptureResumeLoadState.REDIRECTING
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isBusy) Outline else ErrorWarm,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(16.dp)
            .testTag("capture_resume_${state.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = JadeActive,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ErrorWarm,
                )
            }
            Text(
                text = when (state) {
                    CaptureResumeLoadState.LOADING -> "正在恢复题目…"
                    CaptureResumeLoadState.REDIRECTING -> "正在打开讲题会话…"
                    CaptureResumeLoadState.MISSING -> "这道题已处理或不存在"
                    CaptureResumeLoadState.SOURCE_UNAVAILABLE -> "原图暂时无法打开"
                    CaptureResumeLoadState.NOT_REQUESTED,
                    CaptureResumeLoadState.READY,
                    -> "正在恢复题目…"
                },
                modifier = Modifier.padding(start = 10.dp),
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = when (state) {
                CaptureResumeLoadState.LOADING ->
                    "正在读取本机保存的原图、题面和上次处理状态。"
                CaptureResumeLoadState.REDIRECTING ->
                    "这道题已经准备好讲解，将回到原来的临时会话。"
                CaptureResumeLoadState.MISSING ->
                    "它可能已经存入错题本，返回后列表会自动更新。"
                CaptureResumeLoadState.SOURCE_UNAVAILABLE ->
                    "原图暂时无法打开，请返回后重新拍摄。"
                CaptureResumeLoadState.NOT_REQUESTED,
                CaptureResumeLoadState.READY,
                -> "正在读取本机保存的题目。"
            },
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!isBusy) {
            OutlineActionChip(
                text = "返回待处理题目",
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capture_resume_back_to_inbox"),
            )
        }
    }
}
