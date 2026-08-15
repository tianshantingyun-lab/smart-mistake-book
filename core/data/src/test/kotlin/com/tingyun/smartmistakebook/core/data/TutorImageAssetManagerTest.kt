package com.tingyun.smartmistakebook.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * 测试 TutorImageAssetManager 的安全防护
 * 重点验证 P1-1 修复：边读边计数的字节大小检查
 */
@RunWith(AndroidJUnit4::class)
class TutorImageAssetManagerTest {

    private lateinit var context: Context
    private lateinit var manager: TutorImageAssetManager
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = TutorImageAssetManager(context)
        testDir = File(context.filesDir, "tutor_images_test")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    /**
     * P1-1 修复验证：确保大文件被拒绝
     * 之前的 available() 实现会让大文件绕过检查
     */
    @Test
    fun saveImage_exceedsSizeLimit_throwsException() = runTest {
        // 创建一个 11MB 的模拟输入流（超过 10MB 限制）
        val oversizedBytes = ByteArray(11 * 1024 * 1024) { 0xFF.toByte() }
        val mockUri = createMockImageUri(oversizedBytes)

        val exception = assertThrows(IllegalStateException::class.java) {
            runTest { manager.saveImage(mockUri) }
        }

        assertTrue(
            "Expected size limit error",
            exception.message?.contains("exceeds") == true
        )
    }

    /**
     * 验证正常大小的图片可以保存
     */
    @Test
    fun saveImage_withinSizeLimit_succeeds() = runTest {
        // 创建一个 1MB 的有效 JPEG（简化版，实际需要有效的 JPEG 头）
        val validJpegBytes = createMinimalJpegBytes(1024 * 1024)
        val mockUri = createMockImageUri(validJpegBytes)

        // 应该成功保存
        val assetId = manager.saveImage(mockUri)

        assertTrue("Asset ID should not be blank", assetId.isNotBlank())
        assertTrue("Asset ID should start with tutor_img_", assetId.startsWith("tutor_img_"))
    }

    /**
     * 验证边界值：恰好 10MB 的图片应该被接受
     */
    @Test
    fun saveImage_exactSizeLimit_succeeds() = runTest {
        val exactLimitBytes = createMinimalJpegBytes(10 * 1024 * 1024)
        val mockUri = createMockImageUri(exactLimitBytes)

        val assetId = manager.saveImage(mockUri)

        assertTrue("Asset ID should not be blank", assetId.isNotBlank())
    }

    /**
     * 验证边界值：10MB + 1 字节应该被拒绝
     */
    @Test
    fun saveImage_oneBytePastLimit_throwsException() = runTest {
        val slightlyOversized = createMinimalJpegBytes(10 * 1024 * 1024 + 1)
        val mockUri = createMockImageUri(slightlyOversized)

        val exception = assertThrows(IllegalStateException::class.java) {
            runTest { manager.saveImage(mockUri) }
        }

        assertTrue(
            "Expected size limit error",
            exception.message?.contains("exceeds") == true
        )
    }

    // === 辅助方法 ===

    /**
     * 创建一个最小有效的 JPEG 字节流（带正确的 JPEG 头）
     */
    private fun createMinimalJpegBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        // JPEG 文件头：FF D8 FF E0 00 10 4A 46 49 46 00 01
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xE0.toByte()
        bytes[4] = 0x00.toByte()
        bytes[5] = 0x10.toByte()
        bytes[6] = 0x4A.toByte() // 'J'
        bytes[7] = 0x46.toByte() // 'F'
        bytes[8] = 0x49.toByte() // 'I'
        bytes[9] = 0x46.toByte() // 'F'
        bytes[10] = 0x00.toByte()
        bytes[11] = 0x01.toByte()
        // JPEG 文件尾：FF D9
        bytes[size - 2] = 0xFF.toByte()
        bytes[size - 1] = 0xD9.toByte()
        return bytes
    }

    /**
     * 创建一个模拟的 content:// URI，返回给定的字节流
     */
    private fun createMockImageUri(bytes: ByteArray): Uri {
        // 在测试环境中，我们需要创建一个临时文件并返回其 URI
        val tempFile = File(testDir, "temp_${System.currentTimeMillis()}.jpg")
        tempFile.writeBytes(bytes)
        return Uri.fromFile(tempFile)
    }
}
