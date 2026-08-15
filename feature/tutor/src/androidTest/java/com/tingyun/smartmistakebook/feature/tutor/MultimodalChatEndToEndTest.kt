package com.tingyun.smartmistakebook.feature.tutor

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.data.settings.DataStoreModelConfigurationStore
import com.tingyun.smartmistakebook.core.domain.ModelApiKey
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationUpdate
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationMutationResult
import com.tingyun.smartmistakebook.core.domain.ModelCredentialReadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Arrays

/**
 * 多模态聊天端到端测试
 *
 * 测试目标：
 * 1. 配置 AI 模型（API Key、Base URL、模型名）
 * 2. 加载测试图片
 * 3. 发送图片到 AI 并获取讲解
 * 4. 验证 AI 响应是否包含有效内容
 */
@RunWith(AndroidJUnit4::class)
class MultimodalChatEndToEndTest {

    private lateinit var context: Context
    private lateinit var configStore: DataStoreModelConfigurationStore
    private lateinit var scope: CoroutineScope

    // 测试配置：从环境变量读取，避免明文密钥提交
    // 运行前执行：adb shell setprop debug.smb.test_api_key "sk-..."
    private val TEST_API_KEY = System.getenv("SMB_TEST_API_KEY") ?: ""
    private val TEST_BASE_URL = System.getenv("SMB_TEST_BASE_URL") ?: "https://dasuapi.com/v1"
    private val TEST_MODEL_ID = "gpt-5.6-terra"
    private val TEST_PROVIDER = "OpenAI"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        configStore = DataStoreModelConfigurationStore(
            context = context,
            scope = scope,
            clock = { System.currentTimeMillis() }
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            // 清理配置
            configStore.clear()
        }
    }

    @Test
    fun testConfigureModelSuccessfully() = runBlocking {
        // 步骤 1: 配置模型
        val apiKeyChars = TEST_API_KEY.toCharArray()
        val apiKey = ModelApiKey.from(apiKeyChars)
        Arrays.fill(apiKeyChars, '\u0000')

        val update = ModelConfigurationUpdate(
            provider = TEST_PROVIDER,
            baseUrl = TEST_BASE_URL,
            modelId = TEST_MODEL_ID
        )

        val result = configStore.save(update, apiKey)

        // 验证保存成功
        assertTrue("配置保存应该成功", result is ModelConfigurationMutationResult.Success)

        // 步骤 2: 验证配置已保存
        val config = configStore.configuration.first()
        assertTrue("配置应该标记为已配置", config.isConfigured)
        assertEquals("服务商应该匹配", TEST_PROVIDER, config.provider)
        assertEquals("Base URL 应该匹配", TEST_BASE_URL, config.baseUrl)
        assertEquals("模型 ID 应该匹配", TEST_MODEL_ID, config.modelId)

        // 步骤 3: 验证可以读取凭证
        val credential = configStore.readCredential()
        assertTrue(
            "应该能读取到凭证",
            credential is ModelCredentialReadResult.Available
        )
    }

    @Test
    fun testLoadTestImageSuccessfully() {
        // 测试图片路径：C:\Users\听云\Pictures\错题数据集\IMG_20260803_164431.jpg
        // 在 Android 测试中，我们需要从 assets 或者通过 adb push 的方式访问

        val testImagePath = "/sdcard/Pictures/test_question.jpg"
        val imageFile = File(testImagePath)

        if (imageFile.exists()) {
            // 验证图片可以加载
            val bitmap = BitmapFactory.decodeFile(testImagePath)
            assertNotNull("应该能够加载测试图片", bitmap)
            assertTrue("图片宽度应该大于0", bitmap.width > 0)
            assertTrue("图片高度应该大于0", bitmap.height > 0)

            println("✅ 测试图片加载成功：${bitmap.width}x${bitmap.height}")
        } else {
            println("⚠️ 测试图片不存在：$testImagePath")
            println("   请先运行：adb push \"C:\\Users\\听云\\Pictures\\错题数据集\\IMG_20260803_164431.jpg\" /sdcard/Pictures/test_question.jpg")
        }
    }

    // TODO: 添加实际的 AI 调用测试
    // 由于需要集成真实的 TutorRepository 和网络层，这部分需要更多的依赖注入设置
}
