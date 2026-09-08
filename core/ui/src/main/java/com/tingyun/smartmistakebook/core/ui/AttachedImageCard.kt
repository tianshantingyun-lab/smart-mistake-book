package com.tingyun.smartmistakebook.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind

/** 附加图的默认折叠标题（纯函数，便于单测）：按 kind 给教育语境图题。 */
internal fun attachedImageCollapsedTitle(image: AttachedImage): String = when (image.kind) {
    AttachedImageKind.REDRAW_PROBLEM -> "重绘图 · ${image.description.take(18)}"
    AttachedImageKind.GENERATE_PROCESS -> "过程图 · ${image.description.take(18)}"
}

/**
 * 讲题回复附加图锚点：默认折叠为一行缩略摘要（图标 + 图题），点击展开为 [BoundedLocalImage]。
 * 图以 [imageUri]（本地 file/content）渲染，绝不联网；[imageLocalUri] 为空时渲染一个"图未就绪"占位。
 * 与 [ThinkingCollapsibleCard] 同源——教育语境下先给文字讲解，图作为可选展开，不抢正文。
 */
@Composable
fun AttachedImageCard(
    image: AttachedImage,
    imageLocalUri: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = attachedImageCollapsedTitle(image)
    val accessibilityText = image.accessibilityText.ifBlank { image.description }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = accessibilityText,
                tint = InkSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (expanded) "已展开" else title,
                color = InkSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起图" else "展开图",
                tint = InkSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            if (imageLocalUri != null) {
                BoundedLocalImage(
                    imageUri = imageLocalUri,
                    contentDescription = accessibilityText,
                    expanded = expanded,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "图尚未生成，稍后查看。",
                    color = InkMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Resolves each [AttachedImage] intent to a local URI via [resolve]. A figure
 * that cannot be produced maps to null — the section renders its "图未生成"
 * placeholder rather than dropping it. Pure suspend helper, unit-testable.
 */
internal suspend fun resolveAttachedImages(
    images: List<AttachedImage>,
    resolve: suspend (AttachedImage) -> String?,
): Map<String, String?> = buildMap {
    for (image in images) {
        put(image.imageId, resolve(image))
    }
}

/**
 * Renders a reply's [images] as a stack of foldable figure cards, resolving each
 * intent to its local URI asynchronously. The text reply renders immediately and
 * the figures fill in (or show a "生成中/未生成" placeholder) without blocking.
 *
 * This is the lightweight standalone pipeline for model-authored bitmap figures —
 * it does not enter the structured-scene [TutorVisualSceneRenderer] path.
 */
@Composable
fun AttachedImagesSection(
    images: List<AttachedImage>,
    resolve: suspend (AttachedImage) -> String?,
    modifier: Modifier = Modifier,
) {
    var uris by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    LaunchedEffect(images) {
        uris = resolveAttachedImages(images, resolve)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEach { image ->
            AttachedImageCard(
                image = image,
                imageLocalUri = uris[image.imageId],
            )
        }
    }
}
