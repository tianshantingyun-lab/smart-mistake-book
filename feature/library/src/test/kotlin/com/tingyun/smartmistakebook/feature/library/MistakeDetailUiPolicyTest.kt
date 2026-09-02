package com.tingyun.smartmistakebook.feature.library

import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.ui.LocalImageLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MistakeDetailUiPolicyTest {
    @Test
    fun sourceToggleHasNoAssetsWhenSourceIsMissing() {
        assertTrue(MistakeSourceSet.Missing.displayAssets().isEmpty())
    }

    @Test
    fun unavailableSourceRemainsVisibleWithTruthfulStatus() {
        val asset = sourceAsset(MistakeSourceLocation.Unavailable)

        assertEquals(
            listOf(asset),
            MistakeSourceSet.Present(listOf(asset)).displayAssets(),
        )
        assertEquals(
            "原图记录存在，但本机文件当前不可用。",
            sourceStatusText(
                hasLocalLocation = false,
                loadState = LocalImageLoadState.UNAVAILABLE,
                expanded = false,
            ),
        )
    }

    @Test
    fun failedLocalDecodeDoesNotClaimThatTheImageWasShown() {
        assertEquals(
            "原图文件暂时打不开。",
            sourceStatusText(
                hasLocalLocation = true,
                loadState = LocalImageLoadState.UNAVAILABLE,
                expanded = false,
            ),
        )
    }

    @Test
    fun cleanRedrawAssetComesFirstWhenPresent() {
        val clean = sourceAsset(
            MistakeSourceLocation.Available("file:///clean.png"),
            role = CLEAN_IMAGE_SOURCE_ROLE,
        )
        val original = sourceAsset(MistakeSourceLocation.Available("file:///original.png"))

        val displayed = MistakeSourceSet.Present(listOf(original, clean)).displayAssets()

        assertEquals(2, displayed.size)
        assertEquals(CLEAN_IMAGE_SOURCE_ROLE, displayed.first().role)
        assertEquals("QUESTION_SOURCE", displayed.last().role)
    }

    @Test
    fun noCleanRedrawKeepsOriginalOrder() {
        val a = sourceAsset(MistakeSourceLocation.Available("file:///a.png"))
        val b = sourceAsset(MistakeSourceLocation.Available("file:///b.png"))

        assertEquals(listOf(a, b), MistakeSourceSet.Present(listOf(a, b)).displayAssets())
    }

    private fun sourceAsset(
        location: MistakeSourceLocation,
        role: String = "QUESTION_SOURCE",
    ) = MistakeSourceAsset(
        role = role,
        sourceAssetId = "asset-$role",
        contentSha256 = "a".repeat(64),
        mimeType = "image/jpeg",
        byteSize = 1_024,
        width = 800,
        height = 600,
        sourceType = "CAMERA",
        createdAtEpochMillis = 1L,
        location = location,
    )
}
