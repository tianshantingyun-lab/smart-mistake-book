package com.tingyun.smartmistakebook.feature.capture

import com.tingyun.smartmistakebook.core.domain.CaptureSourcePage
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureParseInput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.MODEL_EGRESS_APPROVAL_TTL_MILLIS
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureModelEgressPolicyTest {
    @Test
    fun externalImageProviderRequiresOneExactCaptureApproval() {
        val provider = provider()
        val manifest = buildCaptureEgressManifest(
            authorizationId = "approval-1",
            draftId = "draft-1",
            provider = provider,
            assetId = "asset-1",
            assetSha256 = "a".repeat(64),
            assetByteSize = 2_048,
            assetWidth = 1080,
            assetHeight = 1440,
            approvedAtEpochMillis = 200,
        )

        assertTrue(provider.requiresCaptureEgressApproval())
        assertTrue(
            manifest.matchesCaptureApproval(
                draftId = "draft-1",
                provider = provider,
                assetId = "asset-1",
                assetSha256 = "a".repeat(64),
            ),
        )
        assertFalse(
            manifest.matchesCaptureApproval(
                draftId = "draft-1",
                provider = provider.copy(providerConfigurationVersion = "config-v2"),
                assetId = "asset-1",
                assetSha256 = "a".repeat(64),
            ),
        )
    }

    @Test
    fun localFixtureAndUnavailableProviderNeverAskToUpload() {
        assertFalse(
            provider().copy(
                executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
            ).requiresCaptureEgressApproval(),
        )
        assertFalse(
            provider().copy(
                executionLocation = ModelExecutionLocation.UNAVAILABLE,
            ).requiresCaptureEgressApproval(),
        )
    }

    @Test
    fun multiPageApprovalIsInvalidatedWhenAnyPageChanges() {
        val pages = listOf(
            page(0, "asset-1", "a"),
            page(1, "asset-2", "b"),
        )
        val manifest = buildCaptureEgressManifest(
            authorizationId = "approval-pages",
            draftId = "draft-1",
            provider = provider(),
            sourcePages = pages,
            approvedAtEpochMillis = 300,
        )

        assertTrue(manifest.matchesCaptureApproval("draft-1", provider(), pages))
        assertFalse(
            manifest.matchesCaptureApproval(
                "draft-1",
                provider(),
                pages.dropLast(1) + page(1, "asset-2", "c"),
            ),
        )
    }

    @Test
    fun freshCurrentCaptureApprovalIsExactAndSingleLaunch() {
        val provider = provider()
        val pages = listOf(
            page(0, "asset-current-1", "a"),
            page(1, "asset-current-2", "b"),
        )
        val manifest = buildCaptureEgressManifest(
            authorizationId = "fresh-current-approval",
            draftId = "draft-current",
            provider = provider,
            sourcePages = pages,
            approvedAtEpochMillis = 1_000,
        )
        val request = ModelTaskRequest(
            requestId = "capture-assess:fresh-current",
            input = CaptureAssessmentInput(
                draftId = "draft-current",
                sourceAssetId = pages.first().sourceAssetId,
                origin = CaptureAssessmentOrigin.TUTOR,
                imageWidth = pages.first().width,
                imageHeight = pages.first().height,
            ),
            occurredAtEpochMillis = 1_000,
            egressManifest = manifest,
        )
        val launchGuard = CaptureExternalExecutionLaunchGuard()

        assertTrue(manifest.matchesCaptureApproval("draft-current", provider, pages))
        assertFalse(
            manifest.matchesCaptureApproval(
                "draft-current",
                provider,
                pages.dropLast(1) + page(1, "asset-current-2", "c"),
            ),
        )
        assertTrue(
            launchGuard.claim(
                request = request,
                snapshot = null,
                provider = provider,
                manifest = manifest,
                activeAuthorizationId = manifest.authorizationId,
                nowEpochMillis = 1_001,
            ),
        )
        assertFalse(
            launchGuard.claim(
                request = request,
                snapshot = null,
                provider = provider,
                manifest = manifest,
                activeAuthorizationId = manifest.authorizationId,
                nowEpochMillis = 1_001,
            ),
        )
    }

    @Test
    fun changedByokConfigurationRecoversTheExactSourceWithANewApproval() {
        val sourcePage = page(0, "asset-1", "a")
        val oldProvider = provider()
        val changedProvider = provider().copy(providerConfigurationVersion = "config-v2")
        val oldManifest = buildCaptureEgressManifest(
            authorizationId = "old-approval",
            draftId = "draft-1",
            provider = oldProvider,
            sourcePages = listOf(sourcePage),
            approvedAtEpochMillis = 70,
        )
        val original = ModelTaskRequest(
            requestId = "capture-assess:failed-byok",
            input = CaptureAssessmentInput(
                draftId = "draft-1",
                sourceAssetId = sourcePage.sourceAssetId,
                origin = CaptureAssessmentOrigin.TUTOR,
                imageWidth = sourcePage.width,
                imageHeight = sourcePage.height,
            ),
            occurredAtEpochMillis = 70,
            egressManifest = oldManifest,
        )
        val failed = failureSnapshot(original, oldProvider, ModelFailureCode.AUTHENTICATION_FAILED)

        assertFalse(failed.requiresFreshCaptureApproval(oldProvider))
        assertTrue(failed.requiresFreshCaptureApproval(changedProvider))

        val timedOut = failed.copy(
            failure = ModelTaskFailure(
                ModelFailureCode.TIMEOUT,
                "连接超时",
                retryable = true,
            ),
        )
        assertFalse(timedOut.requiresFreshCaptureApproval(oldProvider))
        assertTrue(timedOut.requiresFreshCaptureApproval(changedProvider))

        val legacyManifest = oldManifest.copy(promptPolicyVersion = "capture-document-legacy")
        val legacyRequest = original.copy(egressManifest = legacyManifest)
        val legacyPolicy = timedOut.copy(
            request = legacyRequest,
            requestFingerprint = ModelTaskFingerprint.of(legacyRequest),
        )
        assertTrue(legacyPolicy.requiresFreshCaptureApproval(oldProvider))

        val freshManifest = buildCaptureEgressManifest(
            authorizationId = "fresh-approval",
            draftId = "draft-1",
            provider = changedProvider,
            sourcePages = listOf(sourcePage),
            approvedAtEpochMillis = 500,
        )
        val first = rebuildCaptureRequestAfterApproval(failed, changedProvider, freshManifest)
        val restored = rebuildCaptureRequestAfterApproval(failed, changedProvider, freshManifest)
        val nextApproval = rebuildCaptureRequestAfterApproval(
            failed,
            changedProvider,
            freshManifest.copy(
                authorizationId = "another-fresh-approval",
                approvedAtEpochMillis = 501,
            ),
        )

        assertEquals(first, restored)
        assertEquals(original.input, first.input)
        assertEquals(original.occurredAtEpochMillis, first.occurredAtEpochMillis)
        assertNotEquals(original.requestId, first.requestId)
        assertNotEquals(oldManifest.authorizationId, first.egressManifest?.authorizationId)
        assertEquals("config-v2", first.egressManifest?.providerConfigurationVersion)
        assertNotEquals(first.requestId, nextApproval.requestId)
    }

    @Test
    fun egressFailuresRenewCaptureConsentWithoutOpeningSettings() {
        val provider = provider()
        val sourcePage = page(0, "asset-1", "a")
        val manifest = buildCaptureEgressManifest(
            authorizationId = "expired-approval",
            draftId = "draft-1",
            provider = provider,
            sourcePages = listOf(sourcePage),
            approvedAtEpochMillis = 70,
        )
        val request = ModelTaskRequest(
            requestId = "capture-assess:expired-approval",
            input = CaptureAssessmentInput(
                draftId = "draft-1",
                sourceAssetId = sourcePage.sourceAssetId,
                origin = CaptureAssessmentOrigin.TUTOR,
                imageWidth = sourcePage.width,
                imageHeight = sourcePage.height,
            ),
            occurredAtEpochMillis = 70,
            egressManifest = manifest,
        )

        listOf(
            ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED,
            ModelFailureCode.EGRESS_AUTHORIZATION_INVALID,
        ).forEach { code ->
            assertTrue(failureSnapshot(request, provider, code).requiresFreshCaptureApproval(provider))
        }
    }

    @Test
    fun parseRecoveryKeepsItsExactAssessmentDependencyAndSourcePage() {
        val page = page(0, "asset-parse", "b")
        val oldProvider = provider()
        val changedProvider = provider().copy(providerConfigurationVersion = "config-v2")
        val oldManifest = buildCaptureEgressManifest(
            "old-parse-approval",
            "draft-parse",
            oldProvider,
            listOf(page),
            90,
        )
        val input = CaptureParseInput(
            draftId = "draft-parse",
            origin = CaptureAssessmentOrigin.TUTOR,
            basisRevisionNumber = 7,
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = page.sourceAssetId,
                    sha256 = page.sourceAssetSha256,
                    width = page.width,
                    height = page.height,
                    pageIndex = page.pageIndex,
                ),
            ),
            assessmentRequestId = "capture-assess:exact-prerequisite",
        )
        val original = ModelTaskRequest(
            requestId = "capture-parse:failed-byok",
            input = input,
            occurredAtEpochMillis = 90,
            egressManifest = oldManifest,
        )
        val failed = failureSnapshot(
            original,
            oldProvider,
            ModelFailureCode.PROVIDER_CAPABILITY_MISSING,
        )
        val freshManifest = buildCaptureEgressManifest(
            "fresh-parse-approval",
            "draft-parse",
            changedProvider,
            listOf(page),
            600,
        )

        val recovered = rebuildCaptureRequestAfterApproval(
            failed,
            changedProvider,
            freshManifest,
        )

        assertEquals(input, recovered.input)
        assertEquals(90L, recovered.occurredAtEpochMillis)
    }

    @Test
    fun restoredUncertainCaptureNeverRoutesToTheGateway() {
        val provider = provider()
        val page = page(0, "asset-resume", "c")
        val manifest = buildCaptureEgressManifest(
            authorizationId = "persisted-approval",
            draftId = "draft-resume",
            provider = provider,
            sourcePages = listOf(page),
            approvedAtEpochMillis = 1_000,
        )
        val request = ModelTaskRequest(
            requestId = "capture-assess:resume",
            input = CaptureAssessmentInput(
                draftId = "draft-resume",
                sourceAssetId = page.sourceAssetId,
                origin = CaptureAssessmentOrigin.TUTOR,
                imageWidth = page.width,
                imageHeight = page.height,
            ),
            occurredAtEpochMillis = 1_000,
            egressManifest = manifest,
        )
        val restoredStates = listOf(
            uncertainSnapshot(
                request,
                provider,
                ModelTaskStatus.WAITING_FOR_MODEL,
                attemptCount = 0,
            ) to null,
            Pair(
                uncertainSnapshot(request, provider, ModelTaskStatus.RUNNING, attemptCount = 1),
                manifest.authorizationId,
            ),
            Pair(
                uncertainSnapshot(request, provider, ModelTaskStatus.STREAMING, attemptCount = 1),
                manifest.authorizationId,
            ),
            Pair(
                uncertainSnapshot(request, provider, ModelTaskStatus.QUEUED, attemptCount = 1),
                manifest.authorizationId,
            ),
            uncertainSnapshot(
                request,
                provider,
                ModelTaskStatus.RETRYABLE_FAILURE,
                attemptCount = 1,
            ) to manifest.authorizationId,
        )

        restoredStates.forEach { (restored, activeAuthorizationId) ->
            val gateway = RecordingCaptureGateway()
            val guard = CaptureExternalExecutionLaunchGuard()
            if (
                guard.claim(
                    request = request,
                    snapshot = restored,
                    provider = provider,
                    manifest = manifest,
                    activeAuthorizationId = activeAuthorizationId,
                    nowEpochMillis = 1_001,
                )
            ) {
                gateway.execute(request)
            }
            assertEquals(0, gateway.requests.size)
        }
    }

    @Test
    fun stalePendingCaptureStaysBlockedUntilExplicitFreshApproval() {
        val provider = provider()
        val page = page(0, "asset-stale", "e")
        val staleManifest = buildCaptureEgressManifest(
            authorizationId = "stale-approval",
            draftId = "draft-stale",
            provider = provider,
            sourcePages = listOf(page),
            approvedAtEpochMillis = 1_000,
        )
        val staleRequest = ModelTaskRequest(
            requestId = "capture-assess:stale-pending",
            input = CaptureAssessmentInput(
                draftId = "draft-stale",
                sourceAssetId = page.sourceAssetId,
                origin = CaptureAssessmentOrigin.LIBRARY,
                imageWidth = page.width,
                imageHeight = page.height,
            ),
            occurredAtEpochMillis = 1_000,
            egressManifest = staleManifest,
        )
        val now = 1_000 + MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1
        val guard = CaptureExternalExecutionLaunchGuard()

        assertFalse(
            guard.claim(
                request = staleRequest,
                snapshot = null,
                provider = provider,
                manifest = staleManifest,
                activeAuthorizationId = staleManifest.authorizationId,
                nowEpochMillis = now,
            ),
        )

        val freshManifest = buildCaptureEgressManifest(
            authorizationId = "explicit-continue-approval",
            draftId = "draft-stale",
            provider = provider,
            sourcePages = listOf(page),
            approvedAtEpochMillis = now,
        )
        val continuedRequest = staleRequest.copy(
            requestId = "capture-assess:explicit-continue",
            egressManifest = freshManifest,
        )
        assertTrue(
            guard.claim(
                request = continuedRequest,
                snapshot = null,
                provider = provider,
                manifest = freshManifest,
                activeAuthorizationId = freshManifest.authorizationId,
                nowEpochMillis = now + 1,
            ),
        )
    }

    @Test
    fun unchangedProviderSettingsFailureNeedsSettingsNotFreshApproval() {
        val provider = provider()
        val page = page(0, "asset-settings", "f")
        val manifest = buildCaptureEgressManifest(
            authorizationId = "settings-approval",
            draftId = "draft-settings",
            provider = provider,
            sourcePages = listOf(page),
            approvedAtEpochMillis = 1_000,
        )
        val request = ModelTaskRequest(
            requestId = "capture-assess:settings-failure",
            input = CaptureAssessmentInput(
                draftId = "draft-settings",
                sourceAssetId = page.sourceAssetId,
                origin = CaptureAssessmentOrigin.TUTOR,
                imageWidth = page.width,
                imageHeight = page.height,
            ),
            occurredAtEpochMillis = 1_000,
            egressManifest = manifest,
        )
        val failed = failureSnapshot(
            request = request,
            provider = provider,
            code = ModelFailureCode.AUTHENTICATION_FAILED,
        )

        assertEquals(
            CaptureModelRecoveryAction.OPEN_SETTINGS,
            captureModelRecoveryAction(failed, null),
        )
        assertFalse(failed.requiresFreshCaptureApproval(provider))
        assertFalse(captureEgressApprovalMustBeRenewed(failed, null))
    }

    @Test
    fun explicitFreshApprovalRoutesTheExactInputOnlyOnceAndExpiryFailsClosed() {
        val provider = provider()
        val page = page(0, "asset-explicit", "d")
        val oldManifest = buildCaptureEgressManifest(
            authorizationId = "old-approval",
            draftId = "draft-explicit",
            provider = provider,
            sourcePages = listOf(page),
            approvedAtEpochMillis = 1_000,
        )
        val original = ModelTaskRequest(
            requestId = "capture-assess:uncertain",
            input = CaptureAssessmentInput(
                draftId = "draft-explicit",
                sourceAssetId = page.sourceAssetId,
                origin = CaptureAssessmentOrigin.LIBRARY,
                imageWidth = page.width,
                imageHeight = page.height,
            ),
            occurredAtEpochMillis = 1_000,
            egressManifest = oldManifest,
        )
        val restored = uncertainSnapshot(
            original,
            provider,
            ModelTaskStatus.RUNNING,
            attemptCount = 1,
        )
        val freshManifest = buildCaptureEgressManifest(
            authorizationId = "fresh-approval",
            draftId = "draft-explicit",
            provider = provider,
            sourcePages = listOf(page),
            approvedAtEpochMillis = 2_000,
        )
        val approvedRequest = rebuildCaptureRequestAfterApproval(
            failedTask = restored,
            provider = provider,
            freshManifest = freshManifest,
        )
        val gateway = RecordingCaptureGateway()
        val guard = CaptureExternalExecutionLaunchGuard()
        val freshlyApprovedPending = uncertainSnapshot(
            approvedRequest,
            provider,
            ModelTaskStatus.WAITING_FOR_MODEL,
            attemptCount = 0,
        )

        repeat(2) {
            if (
                guard.claim(
                    request = approvedRequest,
                    snapshot = freshlyApprovedPending,
                    provider = provider,
                    manifest = freshManifest,
                    activeAuthorizationId = freshManifest.authorizationId,
                    nowEpochMillis = 2_001,
                )
            ) {
                gateway.execute(approvedRequest)
            }
        }

        assertEquals(listOf(approvedRequest), gateway.requests)
        assertEquals(original.input, gateway.requests.single().input)
        assertFalse(
            CaptureExternalExecutionLaunchGuard().claim(
                request = approvedRequest,
                snapshot = null,
                provider = provider,
                manifest = freshManifest,
                activeAuthorizationId = freshManifest.authorizationId,
                nowEpochMillis = 2_000 + MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1,
            ),
        )
    }

    private fun failureSnapshot(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
        code: ModelFailureCode,
    ) = ModelTaskSnapshot(
        taskId = "task-${request.requestId}",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = ModelTaskStatus.PERMANENT_FAILURE,
        stateVersion = 2,
        stage = ModelTaskStage.PREPARING,
        userMessage = "模型设置需要更新",
        attemptCount = 1,
        provider = provider,
        failure = ModelTaskFailure(code, "失败", retryable = false),
        createdAtEpochMillis = request.occurredAtEpochMillis,
        updatedAtEpochMillis = request.occurredAtEpochMillis + 1,
    )

    private fun uncertainSnapshot(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot,
        status: ModelTaskStatus,
        attemptCount: Int,
    ) = ModelTaskSnapshot(
        taskId = "task-${request.requestId}-${status.name}",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = status,
        stateVersion = 2,
        stage = ModelTaskStage.PREPARING,
        userMessage = "题目正在整理",
        attemptCount = attemptCount,
        provider = provider,
        failure = if (status == ModelTaskStatus.RETRYABLE_FAILURE) {
            ModelTaskFailure(ModelFailureCode.TIMEOUT, "连接超时", retryable = true)
        } else {
            null
        },
        createdAtEpochMillis = request.occurredAtEpochMillis,
        updatedAtEpochMillis = request.occurredAtEpochMillis + 1,
    )

    private class RecordingCaptureGateway {
        val requests = mutableListOf<ModelTaskRequest>()

        fun execute(request: ModelTaskRequest) {
            requests += request
        }
    }

    private fun page(index: Int, assetId: String, hashChar: String) = CaptureSourcePage(
        pageIndex = index,
        imageUri = "file:///source-$index.jpg",
        sourceAssetId = assetId,
        sourceAssetSha256 = hashChar.repeat(64),
        width = 1080,
        height = 1440,
        byteSize = 2_048,
    )

    private fun provider() = ProviderCapabilitySnapshot(
        providerId = "provider-1",
        providerDisplayName = "我的视觉模型",
        modelId = "vision-model-1",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "config-v1",
    )
}
