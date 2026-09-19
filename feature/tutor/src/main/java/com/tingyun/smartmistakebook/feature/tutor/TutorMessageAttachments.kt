package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.LobbyMessageImageIntake
import com.tingyun.smartmistakebook.core.domain.MAX_TUTOR_MESSAGE_IMAGES
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.Paper
import com.tingyun.smartmistakebook.core.ui.SmartDimens

/**
 * 学生待发送的附件：只持有本地 uri，真正的登记（进规范资产库、拿哈希与尺寸）发生在发送时。
 *
 * 抽在这里而不是留在某一个入口里，是因为大厅与讲题会话都要用同一套选图/预览/气泡渲染——
 * 两份实现迟早会分叉，而分叉的表现会是"同一个操作在两个页面行为不同"。
 */
internal data class PendingMessageImage(
    val localUri: String,
)

/**
 * 输入框上方的附件预览行（微信式）：缩略图 + 删除角标。
 *
 * [testTagPrefix] 让两个入口各自保留可定位的标签，UI 测试不会互相串到另一个页面。
 */
@Composable
internal fun PendingMessageImagesRow(
    images: List<PendingMessageImage>,
    onRemove: (Int) -> Unit,
    testTagPrefix: String,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SmartDimens.ContentHorizontalPadding, vertical = 4.dp)
            .testTag("${testTagPrefix}_pending_images"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(images.size) { index ->
            Box {
                BoundedLocalImage(
                    imageUri = images[index].localUri,
                    contentDescription = "待发送图片 ${index + 1}",
                    expanded = false,
                    collapsedMaxHeight = 72.dp,
                    modifier = Modifier
                        .size(width = 72.dp, height = 72.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Surface(
                    color = Paper,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clickable { onRemove(index) }
                        .testTag("${testTagPrefix}_pending_image_remove_$index"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "移除这张图片",
                        tint = InkSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(2.dp),
                    )
                }
            }
        }
    }
}

/** 气泡内的消息图片行：从规范资产解析本地 uri 后内嵌渲染（微信式）。 */
@Composable
internal fun MessageImagesRow(
    assetIds: List<String>,
    imageIntake: LobbyMessageImageIntake,
    testTagPrefix: String,
) {
    val uris by produceState<Map<String, String?>>(emptyMap(), assetIds, imageIntake) {
        val resolved = buildMap {
            for (assetId in assetIds) {
                put(assetId, imageIntake.resolveImageUri(assetId))
            }
        }
        value = resolved
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        assetIds.forEachIndexed { index, assetId ->
            val uri = uris[assetId]
            if (uri != null) {
                BoundedLocalImage(
                    imageUri = uri,
                    contentDescription = "消息图片 ${index + 1}",
                    expanded = false,
                    collapsedMaxHeight = 140.dp,
                    modifier = Modifier
                        .size(width = 120.dp, height = 120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .testTag("${testTagPrefix}_message_image_$index"),
                )
            } else {
                Text(
                    text = "图片暂时打不开",
                    color = InkSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** 加号菜单：拍一张新照片、从相册选择，或从错题库挑一道题加进来。 */
@Composable
internal fun MessageAttachmentDialog(
    onDismiss: () -> Unit,
    onLaunchCamera: () -> Unit,
    onLaunchGallery: () -> Unit,
    testTagPrefix: String,
    /** 页面没有错题库入口时留空，那一项就不出现。 */
    onPickFromLibrary: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // 三个动作同权，所以都排在内容区；确认键留给"取消"。
                AttachmentMenuRow(
                    text = "拍照",
                    icon = Icons.Outlined.CameraAlt,
                    onClick = onLaunchCamera,
                    testTag = "${testTagPrefix}_attach_camera",
                )
                AttachmentMenuRow(
                    text = "从相册选择",
                    icon = Icons.Outlined.PhotoLibrary,
                    onClick = onLaunchGallery,
                    testTag = "${testTagPrefix}_attach_gallery",
                )
                onPickFromLibrary?.let { pick ->
                    AttachmentMenuRow(
                        text = "从错题库选择",
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        onClick = pick,
                        testTag = "${testTagPrefix}_attach_library",
                    )
                }
                Text(
                    text = "图片最多 ${MAX_TUTOR_MESSAGE_IMAGES} 张；从错题库选中的题会作为这一轮要讲的那道题。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("${testTagPrefix}_attach_cancel"),
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AttachmentMenuRow(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(text = text, modifier = Modifier.padding(start = 10.dp))
    }
}
