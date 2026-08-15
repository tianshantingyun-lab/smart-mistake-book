package com.tingyun.smartmistakebook.feature.tutor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tingyun.smartmistakebook.core.data.TutorImageAssetManager
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.MessageBubble
import com.tingyun.smartmistakebook.core.ui.Paper
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTutorRoute(
    modelTasks: ModelTaskRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messageOrdinal by remember { mutableIntStateOf(1) }
    var inputText by remember { mutableStateOf("") }
    var attachedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val messages = remember { mutableStateListOf<MessageItem>() }
    val priorTasks = remember { mutableStateListOf<ModelTaskSnapshot>() }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    // 相机启动器
    val takePicture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success && pendingCameraUri != null) {
            attachedImages = attachedImages + pendingCameraUri!!
        }
        pendingCameraUri = null
    }

    // 相册选择器
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 3),
    ) { uris ->
        if (uris.isNotEmpty()) {
            attachedImages = attachedImages + uris
        }
    }

    // 拍照函数
    fun launchCamera() {
        val imageFile = File(context.cacheDir, "tutor_camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile,
        )
        pendingCameraUri = uri
        takePicture.launch(uri)
    }

    // 监听任务流 - 使用 observeBySubject
    val tasks by modelTasks.observeBySubject(
        subjectId = SIMPLE_TUTOR_CONVERSATION_ID,
        kind = com.tingyun.smartmistakebook.core.model.ModelTaskKind.TUTOR_LOBBY,
    ).collectAsState(initial = emptyList())

    // 处理任务更新
    LaunchedEffect(tasks) {
        tasks.forEach { task ->
            val output = task.output as? TutorLobbyOutput
            val requestOrdinal = (task.request.input as? TutorLobbyInput)?.messageOrdinal
            if (output != null && task.status == ModelTaskStatus.SUCCEEDED) {
                // 更新或添加 AI 回复（只替换 isLoading=true 的占位消息）
                val existingIndex = messages.findAssistantByOrdinal(
                    output.messageOrdinal,
                    isLoading = true
                )
                if (existingIndex >= 0) {
                    messages[existingIndex] = MessageItem.Assistant(
                        messageOrdinal = output.messageOrdinal,
                        content = output.messageMarkdown,
                        isLoading = false,
                    )
                } else {
                    messages.add(
                        MessageItem.Assistant(
                            messageOrdinal = output.messageOrdinal,
                            content = output.messageMarkdown,
                            isLoading = false,
                        ),
                    )
                }

                // 更新任务历史
                if (!priorTasks.any { it.request.requestId == task.request.requestId }) {
                    priorTasks.add(task)
                }
            } else if (
                requestOrdinal != null &&
                (
                    task.status == ModelTaskStatus.PERMANENT_FAILURE ||
                        task.status == ModelTaskStatus.RETRYABLE_FAILURE ||
                        task.status == ModelTaskStatus.CANCELLED
                    )
            ) {
                // 任务失败或被取消：把占位的转圈替换为用户友好的错误提示
                val existingIndex = messages.findAssistantByOrdinal(requestOrdinal, isLoading = true)
                if (existingIndex >= 0) {
                    val userFriendlyMessage = task.failure?.code?.toUserFriendlyMessage()
                        ?: task.userMessage
                        ?: "AI 暂时无法回复"
                    messages[existingIndex] = MessageItem.Assistant(
                        messageOrdinal = requestOrdinal,
                        content = "抱歉，$userFriendlyMessage",
                        isLoading = false,
                    )
                }
            }
        }
    }

    // 自动滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "讲题",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Paper,
                ),
            )
        },
        modifier = modifier.fillMaxSize(),
        containerColor = Paper,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // 对话列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(
                    items = messages,
                    key = { item ->
                        when (item) {
                            is MessageItem.User -> "user-${item.messageOrdinal}"
                            is MessageItem.Assistant -> "assistant-${item.messageOrdinal}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is MessageItem.User -> UserMessageBubble(
                            text = item.text,
                            images = item.images,
                        )

                        is MessageItem.Assistant -> AssistantMessageBubble(
                            content = item.content,
                            isLoading = item.isLoading,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 输入框
            TutorComposer(
                value = inputText,
                onValueChange = { value: String -> inputText = value },
                onCapture = ::launchCamera,  // 直接调用相机
                onSend = {
                    if (inputText.isNotBlank() || attachedImages.isNotEmpty()) {
                        val currentText = inputText
                        val currentImages = attachedImages.toList()

                        // 添加用户消息
                        messages.add(
                            MessageItem.User(
                                messageOrdinal = messageOrdinal,
                                text = currentText,
                                images = currentImages,
                            ),
                        )

                        // 添加加载中的 AI 回复占位
                        messages.add(
                            MessageItem.Assistant(
                                messageOrdinal = messageOrdinal,
                                content = "",
                                isLoading = true,
                            ),
                        )

                        // 清空输入
                        inputText = ""
                        attachedImages = emptyList()

                        // 发送请求
                        scope.launch {
                            try {
                                // 上传图片并获取资产引用
                                val imageAssetManager = TutorImageAssetManager(context)
                                val imageAssetRefs = currentImages.map { uri ->
                                    imageAssetManager.saveImage(uri)
                                }

                                // 如果没有文字，使用占位提示（空格会被 requireSafeModelText 的 isNotBlank 拒绝）
                                val messageText = currentText.ifBlank { "请帮我看看这道题" }

                                val request = buildSimpleTutorRequest(
                                    messageOrdinal = messageOrdinal,
                                    studentMessage = messageText,
                                    imageAssetRefs = imageAssetRefs,
                                    priorTasks = priorTasks.toList(),
                                    occurredAtEpochMillis = System.currentTimeMillis(),
                                    context = context,
                                )

                                // 使用 execute 而不是 enqueue
                                modelTasks.execute(request).collect { snapshot ->
                                    // 任务状态会通过 observeBySubject 自动更新
                                }
                                messageOrdinal++
                            } catch (e: Exception) {
                                // 移除加载占位，显示错误
                                messages.removeLastOrNull()
                                messages.add(
                                    MessageItem.Assistant(
                                        messageOrdinal = messageOrdinal - 1,
                                        content = "抱歉，发送失败：${e.message}",
                                        isLoading = false,
                                    ),
                                )
                            }
                        }
                    }
                },
                placeholder = "可以发送图片或文字提问，需要加入错题本时直接说",
                enabled = true,
                attachedImages = attachedImages,
                onImagesAttached = { uris: List<Uri> -> attachedImages = attachedImages + uris },
                onImageRemoved = { uri: Uri -> attachedImages = attachedImages.filter { it != uri } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }
    }
}

/**
 * 辅助函数：在消息列表中查找指定 ordinal 的 Assistant 消息
 */
private fun MutableList<MessageItem>.findAssistantByOrdinal(
    ordinal: Int,
    isLoading: Boolean? = null
): Int = indexOfFirst {
    it is MessageItem.Assistant &&
        it.messageOrdinal == ordinal &&
        (isLoading == null || it.isLoading == isLoading)
}

/**
 * 将内部错误码转换为用户友好的提示
 */
private fun ModelFailureCode.toUserFriendlyMessage(): String = when (this) {
    ModelFailureCode.MODEL_NOT_CONFIGURED -> "请先在\"我的\"中配置 AI 模型"
    ModelFailureCode.AUTHENTICATION_FAILED -> "API 认证失败，请检查密钥"
    ModelFailureCode.NETWORK_UNAVAILABLE -> "网络连接失败，请稍后重试"
    ModelFailureCode.TIMEOUT -> "请求超时，请重试"
    ModelFailureCode.RATE_LIMITED -> "请求过于频繁，请稍后重试"
    ModelFailureCode.PROVIDER_REJECTED_INPUT -> "输入内容不符合要求"
    ModelFailureCode.INVALID_RESPONSE -> "AI 返回了无效的数据"
    ModelFailureCode.EGRESS_AUTHORIZATION_INVALID -> "权限验证失败"
    ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED -> "需要授权才能继续"
    ModelFailureCode.PROVIDER_CAPABILITY_MISSING -> "当前模型不支持此功能"
    ModelFailureCode.UNKNOWN -> "AI 暂时无法回复"
}

private sealed class MessageItem {
    data class User(
        val messageOrdinal: Int,
        val text: String,
        val images: List<Uri>,
    ) : MessageItem()

    data class Assistant(
        val messageOrdinal: Int,
        val content: String,
        val isLoading: Boolean,
    ) : MessageItem()
}

@Composable
private fun UserMessageBubble(
    text: String,
    images: List<Uri>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .background(JadeSoft, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 显示图片
            images.forEach { uri ->
                BoundedLocalImage(
                    imageUri = uri.toString(),
                    contentDescription = "发送的图片",
                    expanded = false,
                    collapsedMaxHeight = 200.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 显示文字
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    content: String,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(8.dp)
                    .size(24.dp),
                color = JadeActive,
            )
        } else {
            // 使用 MessageBubble 渲染 Markdown
            MessageBubble(
                text = content,
                isUser = false,
            )
        }
    }
}
