package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 的题面字节选取与出网前核对（`REDRAW_PROBLEM` 用它们重绘题面）。
 *
 * 这两条是"模型要的配图"链上唯一由本机决定的东西：选错图，或者把一份与规范记录不一致的
 * 字节当题面发出去，都是真实失败 —— 后者正是拍照会话那条链专门修过的
 * （`RoomCaptureWorkflowRepository.readTutorSessionSheetBytes` 的注释）。这里把同一纪律
 * 钉在错题讲题页上。
 */
class SavedMistakeSheetBytesTest {
    @Test
    fun cleanRedrawWinsOverTheOriginalPhoto() {
        val clean = asset(role = CLEAN_PROBLEM_SHEET_ROLE, available = true)
        val original = asset(role = "ORIGINAL", available = true)

        val picked = sheetSourceAsset(MistakeSourceSet.Present(listOf(original, clean)))

        assertEquals(clean.sourceAssetId, picked?.sourceAssetId)
    }

    @Test
    fun originalPhotoIsUsedWhenTheCleanRedrawIsMissingHere() {
        val cleanWithoutFile = asset(role = CLEAN_PROBLEM_SHEET_ROLE, available = false)
        val original = asset(role = "ORIGINAL", available = true)

        val picked = sheetSourceAsset(MistakeSourceSet.Present(listOf(cleanWithoutFile, original)))

        assertEquals(original.sourceAssetId, picked?.sourceAssetId)
    }

    @Test
    fun noReadableSourceAssetYieldsNothingToRedraw() {
        assertNull(sheetSourceAsset(MistakeSourceSet.Missing))
        assertNull(
            sheetSourceAsset(
                MistakeSourceSet.Present(listOf(asset(role = CLEAN_PROBLEM_SHEET_ROLE, available = false))),
            ),
        )
    }

    @Test
    fun bytesMustMatchTheRecordedHashAndSize() {
        val bytes = "题面字节".toByteArray(Charsets.UTF_8)
        val recorded = asset(role = CLEAN_PROBLEM_SHEET_ROLE, available = true, bytes = bytes)

        assertTrue(recorded.matchesRecordedBytes(bytes))
        // 内容被替换（哈希不符）或长度不符：两种都不该出网。
        assertFalse(recorded.matchesRecordedBytes("题面字节!".toByteArray(Charsets.UTF_8)))
        assertFalse(recorded.matchesRecordedBytes(bytes.copyOf(bytes.size - 1)))
    }

    private fun asset(
        role: String,
        available: Boolean,
        bytes: ByteArray = "canonical".toByteArray(Charsets.UTF_8),
    ) = MistakeSourceAsset(
        role = role,
        sourceAssetId = "asset-$role",
        contentSha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) },
        mimeType = "image/png",
        byteSize = bytes.size.toLong(),
        width = 64,
        height = 64,
        sourceType = "CAPTURE",
        createdAtEpochMillis = 1,
        location = if (available) {
            MistakeSourceLocation.Available("file:///data/user/0/app/files/assets/$role.png")
        } else {
            MistakeSourceLocation.Unavailable
        },
    )
}
