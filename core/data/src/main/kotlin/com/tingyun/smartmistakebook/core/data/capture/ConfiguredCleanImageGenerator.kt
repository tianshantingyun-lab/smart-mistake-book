package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.data.model.GuardedEditsChannelFactory
import com.tingyun.smartmistakebook.core.data.model.ImageChannelFactory
import com.tingyun.smartmistakebook.core.data.model.ImageRedrawRequest
import com.tingyun.smartmistakebook.core.data.model.ImageRedrawResult
import com.tingyun.smartmistakebook.core.data.model.resolveImageCredential
import com.tingyun.smartmistakebook.core.domain.CleanImageGenerator
import com.tingyun.smartmistakebook.core.domain.CleanImageResult
import com.tingyun.smartmistakebook.core.domain.ModelConfigurationStore
import java.util.Arrays
import kotlinx.coroutines.CancellationException

/**
 * Production [CleanImageGenerator] that reuses the model configuration the user
 * already entered for chat (the same baseUrl + API key leased atomically from
 * [ModelConfigurationStore]). A mistake-book commit triggers one gpt-image-2
 * edit against `<configured-base>/images/edits`; the returned clean figure is
 * attached as a CLEAN_IMAGE-role asset by the repository.
 *
 * The edits POST travels over the same SSRF-guarded client as the chat gateway
 * ([GuardedEditsChannelFactory]), so a redraw can never be redirected to a private
 * address.
 *
 * Gating (a redraw is only attempted when adding to the mistake book), all three
 * decided by the shared [resolveImageCredential] gate so this path and the
 * attached-figure path can never disagree:
 *  - the user has not granted the global model-agent consent → decline (null)
 *  - no configured credential            → decline (null)
 *  - capability test never ran or did not verify image input → decline (null)
 *
 * A failed edit (network, auth, provider, decode) also declines — the commit is
 * already durable, so the mistake keeps the original photo. Cancellation
 * propagates so an app shutdown does not silently swallow it.
 */
internal class ConfiguredCleanImageGenerator(
    private val configurationStore: ModelConfigurationStore,
    private val channelFactory: ImageChannelFactory = GuardedEditsChannelFactory,
    private val networkRequestsAllowed: Boolean = true,
) : CleanImageGenerator {

    override suspend fun generateClean(
        originalBytes: ByteArray,
        mimeType: String,
    ): CleanImageResult? {
        if (!networkRequestsAllowed) return null
        val credential = resolveImageCredential(configurationStore, networkRequestsAllowed)
            ?: return null
        return credential.apiKey.use { apiKey ->
            val keyChars = apiKey.copyChars()
            try {
                val configuration = credential.configuration

                val channel = try {
                    channelFactory.create(
                        baseUrl = configuration.baseUrl,
                        authorization = "Bearer ${String(keyChars)}",
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@use null
                }
                val redrawn: ImageRedrawResult = try {
                    channel.redrawClean(
                        ImageRedrawRequest(
                            photoBytes = originalBytes,
                            photoMimeType = mimeType,
                            instruction = CLEAN_REDRAW_INSTRUCTION,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@use null
                }
                CleanImageResult(
                    bytes = redrawn.imageBytes,
                    mimeType = redrawn.mimeType,
                )
            } finally {
                Arrays.fill(keyChars, '\u0000')
            }
        }
    }

    private companion object {
        const val CLEAN_REDRAW_INSTRUCTION =
            "这是一道被学生拍摄的题目照片。请保留印刷题面的所有文字与图形内容不变，" +
                "仅去除照片中的手写笔迹、涂改、无关阴影与折痕，重绘成一张干净的题面图。" +
                "不要改写、增删或重新排版任何印刷内容。"
    }
}

/**
 * Public app-layer entry point. Returns a production clean-redraw generator over
 * the user's configured model credential, or one that always declines when this
 * build cannot egress or no credential is configured. 「配置模型即同意」之下
 * 没有单独的同意形参：凭据不存在 ⇒ 本对象造得出来也发不出去（审计 S-2 的对齐，
 * 见 `ImageCredentialGate` 的类注释）。
 */
object ConfiguredCleanImageGeneratorFactory {
    fun create(
        configurationStore: ModelConfigurationStore,
        networkRequestsAllowed: Boolean,
    ): CleanImageGenerator = ConfiguredCleanImageGenerator(
        configurationStore = configurationStore,
        channelFactory = GuardedEditsChannelFactory,
        networkRequestsAllowed = networkRequestsAllowed,
    )
}
