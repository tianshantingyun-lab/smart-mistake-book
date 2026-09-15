package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.TutorConversation
import com.tingyun.smartmistakebook.core.domain.TutorConversationAnchorKind
import com.tingyun.smartmistakebook.core.domain.TutorConversationRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationStatus
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.PaperDivider

@Composable
fun TutorHistoryRoute(
    conversations: TutorConversationRepository,
    onOpenTextConversation: (String) -> Unit,
    onOpenCapturedSession: (String) -> Unit,
    onArchive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recent by conversations.observeRecent(100)
        .collectAsState(initial = emptyList())
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Paper)
            .testTag("tutor_history_screen"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("tutor_history_back"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回讲题",
                    tint = Ink,
                )
            }
            Text(
                text = "讲题历史",
                modifier = Modifier.weight(1f),
                color = Ink,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        PaperDivider()
        if (recent.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "还没有讲题记录",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "从拍题或选择一道错题开始",
                    color = InkMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = recent,
                    key = TutorConversation::conversationId,
                ) { conversation ->
                    TutorHistoryRow(
                        conversation = conversation,
                        onClick = {
                            when (conversation.anchorKind) {
                                TutorConversationAnchorKind.TEXT_ONLY ->
                                    onOpenTextConversation(conversation.conversationId)
                                TutorConversationAnchorKind.EPHEMERAL_DRAFT,
                                TutorConversationAnchorKind.PROBLEM_REVISION,
                                -> onOpenCapturedSession(
                                    conversation.anchorId ?: conversation.conversationId,
                                )
                            }
                        },
                        onArchive = {
                            if (conversation.status != TutorConversationStatus.ARCHIVED) {
                                onArchive(conversation.conversationId)
                            }
                        },
                        onDelete = { deleteTarget = conversation.conversationId },
                    )
                }
            }
        }
    }
    deleteTarget?.let { conversationId ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条讲题记录？") },
            text = { Text("只会删除这条对话，题目、修订和错题本内容会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDelete(conversationId)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TutorHistoryRow(
    conversation: TutorConversation,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
            .testTag("tutor_history_row_${conversation.conversationId}"),
        color = Paper,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp),
            ) {
                Text(
                    text = conversation.title ?: conversationTitle(conversation),
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    text = historySubtitle(conversation),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (conversation.status != TutorConversationStatus.ARCHIVED) {
                IconButton(
                    onClick = onArchive,
                    modifier = Modifier.testTag("tutor_history_archive_${conversation.conversationId}"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Archive,
                        contentDescription = "归档这个讲题记录",
                        tint = InkSecondary,
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("tutor_history_delete_${conversation.conversationId}"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "删除这条讲题记录",
                    tint = InkSecondary,
                )
            }
        }
    }
}

private fun conversationTitle(conversation: TutorConversation): String = when (
    conversation.anchorKind
) {
    TutorConversationAnchorKind.TEXT_ONLY -> "文字讲题"
    TutorConversationAnchorKind.EPHEMERAL_DRAFT -> "拍题讲题"
    TutorConversationAnchorKind.PROBLEM_REVISION -> "错题讲题"
}

private fun historySubtitle(conversation: TutorConversation): String {
    val status = when (conversation.status) {
        TutorConversationStatus.ACTIVE -> "进行中"
        TutorConversationStatus.PAUSED -> "等你继续"
        TutorConversationStatus.COMPLETED -> "已结束"
        TutorConversationStatus.ARCHIVED -> "已归档"
    }
    // 用真实消息行数，而不是 last_turn_ordinal 序号（讲题会话的序号有空洞会多报）。
    return "$status · 共 ${conversation.messageCount} 条消息"
}
