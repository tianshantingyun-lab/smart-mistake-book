package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_APPROVAL_TTL_MILLIS
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureInformedEgressIntentTest {
    @Test
    fun initialDisclosureNamesTheVisibleRecipientAndExactScope() {
        val disclosure = captureInitialEgressDisclosure(provider())

        assertEquals(
            "仅把本次选中的题图交给我的视觉模型整理；不会发送其他题目或学习记录。",
            disclosure,
        )
        assertFalse(disclosure.contains("vision-model-1"))
        assertFalse(disclosure.contains("OCR", ignoreCase = true))
        assertFalse(disclosure.contains("API", ignoreCase = true))
        assertEquals(
            "仅把本次选中的题图交给当前配置的大模型整理；不会发送其他题目或学习记录。",
            captureInitialEgressDisclosure(provider = null),
        )
        assertEquals(
            "本次题图、整理后的题目和与本题相关的学习记录会交给我的视觉模型，用于开始讲解；不会发送其他题目。",
            captureInitialEgressDisclosure(
                provider = provider(),
                entryOrigin = CaptureEntryOrigin.TUTOR,
            ),
        )
    }

    @Test
    fun returnedImageIsBoundToTheExactClickProviderDraftAndAssets() {
        val provider = provider()
        val page = page()
        val session = CaptureInformedEgressIntentSession()
        session.begin(
            provider = provider,
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
            intentId = "click-1",
            nowEpochMillis = 1_000,
            authorizesInitialTutorPlan = true,
        )

        val sourceIntent = session.bindReturnedSource(
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
            sourceUri = "content://capture/selected",
        )
        assertNotNull(sourceIntent)
        assertNull(
            session.sourceFor(
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                sourceUri = "content://capture/another",
            ),
        )

        val draftIntent = requireNotNull(sourceIntent).bindDraft("draft-1", listOf(page))
        assertTrue(draftIntent.authorizesInitialTutorPlan)
        assertTrue(
            draftIntent.matches(
                provider = provider,
                draftId = "draft-1",
                sourcePages = listOf(page),
                nowEpochMillis = 1_001,
            ),
        )
        assertFalse(
            draftIntent.matches(
                provider = provider.copy(providerConfigurationVersion = "config-v2"),
                draftId = "draft-1",
                sourcePages = listOf(page),
                nowEpochMillis = 1_001,
            ),
        )
        assertFalse(
            draftIntent.matches(
                provider = provider,
                draftId = "draft-1",
                sourcePages = listOf(page.copy(sourceAssetSha256 = "b".repeat(64))),
                nowEpochMillis = 1_001,
            ),
        )
        assertFalse(
            draftIntent.matches(
                provider = provider,
                draftId = "draft-1",
                sourcePages = listOf(page),
                nowEpochMillis = 1_000 + MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1,
            ),
        )
    }

    @Test
    fun rebuiltCompositionHasNoInformedIntentAndCannotSilentlyReuseTheClick() {
        val originalComposition = CaptureInformedEgressIntentSession()
        originalComposition.begin(
            provider = provider(),
            purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
            intentId = "click-before-rebuild",
            nowEpochMillis = 1_000,
        )
        assertNotNull(
            originalComposition.bindReturnedSource(
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                sourceUri = "content://capture/exact",
            ),
        )

        val rebuiltComposition = CaptureInformedEgressIntentSession()
        assertNull(
            rebuiltComposition.sourceFor(
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                sourceUri = "content://capture/exact",
            ),
        )
        assertNull(
            rebuiltComposition.bindReturnedSource(
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                sourceUri = "content://capture/exact",
            ),
        )
    }

    @Test
    fun localOrUnknownProviderDoesNotCreateAnExternalSendIntent() {
        listOf(
            null,
            provider().copy(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS),
        ).forEach { provider ->
            val session = CaptureInformedEgressIntentSession()
            session.begin(
                provider = provider,
                purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                intentId = "click-local",
                nowEpochMillis = 1_000,
            )
            assertNull(
                session.bindReturnedSource(
                    purpose = CaptureAcquisitionPurpose.NEW_CAPTURE,
                    sourceUri = "content://capture/local",
                ),
            )
        }
    }

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider-1",
        providerDisplayName = "我的视觉模型",
        modelId = "vision-model-1",
        supportedTasks = setOf(
            ModelTaskKind.CAPTURE_ASSESS,
            ModelTaskKind.CAPTURE_PARSE,
            ModelTaskKind.TUTOR_PLAN,
        ),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "config-v1",
    )

    private fun page() = CaptureSourcePage(
        pageIndex = 0,
        imageUri = "content://capture/exact",
        sourceAssetId = "asset-1",
        sourceAssetSha256 = "a".repeat(64),
        width = 1080,
        height = 1440,
        byteSize = 2048,
    )
}
