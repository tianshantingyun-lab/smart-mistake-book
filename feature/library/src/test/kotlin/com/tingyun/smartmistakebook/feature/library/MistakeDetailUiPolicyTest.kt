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

    private fun sourceAsset(location: MistakeSourceLocation) = MistakeSourceAsset(
        role = "QUESTION_SOURCE",
        sourceAssetId = "asset-1",
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
