package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.ui.InkMuted
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.BoundedLocalImage

/** 框选区域的最小尺寸阈值，与 SplitCaptureDraftRequest 的校验一致。 */
internal const val REGION_MIN_WIDTH = 0.08
internal const val REGION_MIN_HEIGHT = 0.04
internal const val REGION_MIN_AREA = 0.006
internal const val REGION_MAX_OVERLAP_OF_SMALLER = 0.8

/** 拖拽矩形 → 归一化区域（clamp 0..1、保证 left<right/top<bottom）。纯函数便于单测。 */
internal fun normalizedRegionFromRect(
    start: Offset,
    end: Offset,
    canvasWidth: Float,
    canvasHeight: Float,
): NormalizedSourceRegion? {
    if (canvasWidth <= 0f || canvasHeight <= 0f) return null
    val left = (minOf(start.x, end.x) / canvasWidth).toDouble().coerceIn(0.0, 1.0)
    val right = (maxOf(start.x, end.x) / canvasWidth).toDouble().coerceIn(0.0, 1.0)
    val top = (minOf(start.y, end.y) / canvasHeight).toDouble().coerceIn(0.0, 1.0)
    val bottom = (maxOf(start.y, end.y) / canvasHeight).toDouble().coerceIn(0.0, 1.0)
    val width = right - left
    val height = bottom - top
    if (width < REGION_MIN_WIDTH || height < REGION_MIN_HEIGHT || width * height < REGION_MIN_AREA) {
        return null
    }
    return NormalizedSourceRegion(left, top, right, bottom)
}

/** 与 SplitCaptureDraftRequest 相同的两两重叠约束：小框被大框覆盖超过 80% 判为无效。 */
internal fun regionsOverlapBeyondLimit(regions: List<NormalizedSourceRegion>): Boolean {
    for (i in regions.indices) {
        for (j in i + 1 until regions.size) {
            val a = regions[i]
            val b = regions[j]
            val overlapWidth = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val overlapHeight = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (overlapWidth <= 0.0 || overlapHeight <= 0.0) continue
            val overlapArea = overlapWidth * overlapHeight
            val areaA = (a.right - a.left) * (a.bottom - a.top)
            val areaB = (b.right - b.left) * (b.bottom - b.top)
            val smaller = minOf(areaA, areaB)
            if (smaller > 0 && overlapArea / smaller > REGION_MAX_OVERLAP_OF_SMALLER) return true
        }
    }
    return false
}

/**
 * 手动框选整页多题：在原图上拖拽画框（归一化坐标），按阅读顺序提交给
 * 拆分流程。2 到 12 个框；画得太小忽略；两两重叠过大时禁止提交并提示。
 */
@Composable
internal fun RegionBoxSelectorDialog(
    imageUri: String,
    pageWidth: Int,
    pageHeight: Int,
    onConfirm: (List<NormalizedSourceRegion>) -> Unit,
    onDismiss: () -> Unit,
) {
    var regions by remember { mutableStateOf<List<NormalizedSourceRegion>>(emptyList()) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var boxSize by remember { mutableStateOf<androidx.compose.ui.unit.IntSize?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    fun addRegion(region: NormalizedSourceRegion?) {
        if (region == null) {
            hint = "框太小了：请框住一道完整的题"
            return
        }
        if (regions.size >= 12) {
            hint = "一页最多框 12 道题"
            return
        }
        regions = regions + region
        hint = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6121412))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "在图上拖拽，框出每道题的范围（2 到 12 道）",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("region_box_selector_hint"),
            )
            // 普通 Box：这里只用到外部传入的 pageWidth/pageHeight 定长宽比，
            // BoxWithConstraints 的约束 scope 一句没用（CI lint 的硬门就是这条）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val hostModifier = if (pageWidth > 0 && pageHeight > 0) {
                    Modifier.aspectRatio(pageWidth.toFloat() / pageHeight.toFloat())
                } else {
                    Modifier.fillMaxSize()
                }
                Box(
                    modifier = hostModifier
                        .align(Alignment.Center)
                        .onSizeChanged { boxSize = it }
                        .testTag("region_box_canvas"),
                ) {
                    // 宿主已按原图宽高比固定尺寸，解除 BoundedLocalImage 内部
                    // heightIn(max) 限制后 Fit 不留边：框选坐标与图片坐标一一对应。
                    BoundedLocalImage(
                        imageUri = imageUri,
                        contentDescription = "待框选的题目原图",
                        expanded = true,
                        expandedMaxHeight = Dp.Infinity,
                        modifier = Modifier.matchParentSize(),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(regions) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        dragStart = offset
                                        dragEnd = offset
                                        hint = null
                                    },
                                    onDragEnd = {
                                        val start = dragStart
                                        val end = dragEnd
                                        if (start != null && end != null) {
                                            addRegion(
                                                normalizedRegionFromRect(
                                                    start = start,
                                                    end = end,
                                                    canvasWidth = size.width.toFloat(),
                                                    canvasHeight = size.height.toFloat(),
                                                ),
                                            )
                                        }
                                        dragStart = null
                                        dragEnd = null
                                    },
                                ) { change, _ ->
                                    dragEnd = change.position
                                }
                            },
                    ) {
                        Canvas(Modifier.matchParentSize()) {
                            regions.forEach { region ->
                                drawRect(
                                    color = JadeActive.copy(alpha = 0.18f),
                                    topLeft = Offset(
                                        (region.left * size.width).toFloat(),
                                        (region.top * size.height).toFloat(),
                                    ),
                                    size = Size(
                                        ((region.right - region.left) * size.width).toFloat(),
                                        ((region.bottom - region.top) * size.height).toFloat(),
                                    ),
                                )
                                drawRect(
                                    color = JadeActive,
                                    topLeft = Offset(
                                        (region.left * size.width).toFloat(),
                                        (region.top * size.height).toFloat(),
                                    ),
                                    size = Size(
                                        ((region.right - region.left) * size.width).toFloat(),
                                        ((region.bottom - region.top) * size.height).toFloat(),
                                    ),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                                )
                            }
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) {
                                drawRect(
                                    color = Color.White.copy(alpha = 0.55f),
                                    topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                                    size = Size(
                                        kotlin.math.abs(end.x - start.x),
                                        kotlin.math.abs(end.y - start.y),
                                    ),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                                )
                            }
                        }
                        val boxSizeValue = boxSize
                        regions.forEachIndexed { index, region ->
                            if (boxSizeValue == null) return@forEachIndexed
                            val ordinalLeft = (region.left * boxSizeValue.width).toFloat()
                            val ordinalTop = (region.top * boxSizeValue.height).toFloat()
                            Text(
                                text = "${index + 1}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(
                                        x = with(density) { ordinalLeft.toDp() },
                                        y = with(density) { ordinalTop.toDp() },
                                    )
                                    .background(JadeActive, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
            hint?.let {
                Text(
                    text = it,
                    color = Color(0xFFFFC9A3),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("region_box_hint"),
                )
            }
            val canConfirm = regions.size in 2..12 && !regionsOverlapBeyondLimit(regions)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlineActionChip(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.testTag("region_box_cancel"),
                )
                OutlineActionChip(
                    text = "撤销上一框",
                    onClick = {
                        regions = regions.dropLast(1)
                        hint = null
                    },
                    enabled = regions.isNotEmpty(),
                    modifier = Modifier.testTag("region_box_undo"),
                )
                Box(Modifier.weight(1f))
                PrimaryActionButton(
                    text = "完成（已框 ${regions.size}）",
                    onClick = { onConfirm(regions) },
                    enabled = canConfirm,
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("region_box_confirm"),
                )
            }
            if (regions.size >= 2 && regionsOverlapBeyondLimit(regions)) {
                Text(
                    text = "有两个框重叠得太多，请调整后再完成。",
                    color = Color(0xFFFFC9A3),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "框会按题目在页面上的阅读顺序编号",
                color = InkMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
