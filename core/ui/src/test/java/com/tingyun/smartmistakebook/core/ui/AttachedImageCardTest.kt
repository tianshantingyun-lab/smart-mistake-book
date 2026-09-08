package com.tingyun.smartmistakebook.core.ui

import com.tingyun.smartmistakebook.core.model.AttachedImage
import com.tingyun.smartmistakebook.core.model.AttachedImageKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachedImageCardTest {
    @Test
    fun collapsedTitleLabelsARedrawByKind() {
        val title = attachedImageCollapsedTitle(
            AttachedImage(
                imageId = "redraw-1",
                kind = AttachedImageKind.REDRAW_PROBLEM,
                description = "重绘题面去除手写笔迹保留印刷内容",
                accessibilityText = "干净的题目图",
            ),
        )
        assertTrue(title.startsWith("重绘图"))
        assertTrue(title.contains("重绘题面"))
    }

    @Test
    fun collapsedTitleLabelsAProcessFigureByKind() {
        val title = attachedImageCollapsedTitle(
            AttachedImage(
                imageId = "process-1",
                kind = AttachedImageKind.GENERATE_PROCESS,
                description = "画出导数为正与为负的区间标注图",
            ),
        )
        assertTrue(title.startsWith("过程图"))
        assertTrue(title.contains("标注图"))
    }

    @Test
    fun resolveAttachedImagesResolvesEachIntentToAKeyedUri() = runBlocking {
        val images = listOf(
            AttachedImage("a", AttachedImageKind.GENERATE_PROCESS, "图 A"),
            AttachedImage("b", AttachedImageKind.REDRAW_PROBLEM, "图 B"),
            AttachedImage("c", AttachedImageKind.GENERATE_PROCESS, "图 C"),
        )
        val uris = resolveAttachedImages(images) { image -> if (image.imageId == "a") "file://a" else "file://${image.imageId}" }

        assertEquals("file://a", uris["a"])
        assertEquals("file://b", uris["b"])
        assertEquals("file://c", uris["c"])
    }

    @Test
    fun resolveAttachedImagesKeepsAFailedFigureAsNullPlaceholder() = runBlocking {
        val images = listOf(
            AttachedImage("ok", AttachedImageKind.GENERATE_PROCESS, "好图"),
            AttachedImage("bad", AttachedImageKind.GENERATE_PROCESS, "坏图"),
        )
        val uris = resolveAttachedImages(images) { image -> image.imageId.takeIf { it == "ok" }?.let { "file://$it" } }

        assertEquals("file://ok", uris["ok"])
        assertTrue(uris["bad"] == null)
    }
}
