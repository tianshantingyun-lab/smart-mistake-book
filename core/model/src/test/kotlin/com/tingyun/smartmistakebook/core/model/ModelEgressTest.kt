package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEgressTest {
    @Test
    fun preparedRequestBudgetKeepsSmallImagePayloadsUnchanged() {
        val nonImageJsonBytes = 1_024L

        val estimatedBytes = ModelRequestPayloadBudget.requirePreparedRequestFits(
            nonImageJsonUtf8Bytes = nonImageJsonBytes,
            assetByteSizes = listOf(4L, 5L),
        )

        assertEquals(nonImageJsonBytes + 8L + 8L, estimatedBytes)
    }

    @Test
    fun preparedRequestBudgetRejectsAnOversizedMultiPageTotal() {
        val failure = runCatching {
            ModelRequestPayloadBudget.requirePreparedRequestFits(
                nonImageJsonUtf8Bytes = 1_024L,
                assetByteSizes = listOf(14L * 1_024L * 1_024L, 14L * 1_024L * 1_024L),
            )
        }.exceptionOrNull()

        assertTrue(failure is ModelRequestBudgetExceededException)
        assertEquals("Model request exceeds the upload budget", failure?.message)
    }

    @Test
    fun preparedRequestBudgetFailsClosedWhenBase64ArithmeticWouldOverflow() {
        val failure = runCatching {
            ModelRequestPayloadBudget.requirePreparedRequestFits(
                nonImageJsonUtf8Bytes = 0L,
                assetByteSizes = listOf(Long.MAX_VALUE),
            )
        }.exceptionOrNull()

        assertTrue(failure is ModelRequestBudgetExceededException)
    }

    @Test
    fun exactCaptureApprovalAuthorizesOnlyTheBoundExternalProviderAndAsset() {
        val request = request(manifest())

        val execution = ModelEgressPolicy.authorize(request, externalProvider(), 101)

        assertTrue(execution.permit is ModelExecutionPermit.External)
        assertEquals(request, execution.request)
    }

    @Test
    fun externalProviderCannotRunWithoutStudentApproval() {
        val failure = runCatching {
            ModelEgressPolicy.authorize(request(manifest = null), externalProvider(), 101)
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED, failure.failureCode)
    }

    @Test
    fun providerConfigurationChangeInvalidatesAnExistingApproval() {
        val failure = runCatching {
            ModelEgressPolicy.authorize(
                request(manifest()),
                externalProvider().copy(providerConfigurationVersion = "provider-config-v2"),
                101,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, failure.failureCode)
    }

    @Test
    fun captureApprovalRejectsAnyUndisclosedExtraDataClass() {
        val extra = ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE

        val failure = runCatching {
            manifest().copy(
                disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE + extra,
                prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA - extra,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun legacySchemaOneFingerprintStillMatchesItsOriginalJsonShape() {
        val legacyJson =
            """{"schemaVersion":1,"requestId":"capture-assess:legacy","input":{"type":"capture_assessment","draftId":"draft-1","sourceAssetId":"asset-1","origin":"LIBRARY","imageWidth":1080,"imageHeight":1440},"occurredAtEpochMillis":100}"""
        val request = ModelTaskCodec.decodeRequest(legacyJson)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(legacyJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(1, request.schemaVersion)
        assertEquals(expected, ModelTaskFingerprint.of(request))
    }

    @Test
    fun schemaOneTutorPlanFingerprintOmitsNewStudentContextField() {
        val request = legacyTutorPlanRequest().copy(
            schemaVersion = 1,
            egressManifest = null,
        )
        val legacyJson = ModelTaskCodec.encodeRequest(request)
            .replace(",\"priorCycleStudentMessages\":[]", "")
            .replace(",\"egressManifest\":null", "")
            .replace(",\"agentConsentGranted\":false", "")
        val decoded = ModelTaskCodec.decodeRequest(legacyJson)
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(legacyJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, ModelTaskFingerprint.of(decoded))
    }

    @Test
    fun persistedSchemaOneTutorPlanManifestStillDecodesButCannotAuthorizeACurrentPrompt() {
        val request = legacyTutorPlanRequest()
        val decoded = ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(request))

        assertEquals(1, requireNotNull(decoded.egressManifest).schemaVersion)
        assertTrue(
            runCatching {
            ModelEgressPolicy.authorize(
                decoded,
                tutorProvider(ModelTaskKind.TUTOR_PLAN),
                101,
            )
            }.exceptionOrNull() is ModelEgressAuthorizationException,
        )
        assertTrue(
            runCatching {
                legacyTutorPlanManifest().copy(
                    authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
                    disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
                    prohibitedData = ModelEgressManifest.SCHEMA_V1_DATA_CLASSES -
                        ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
                )
            }.isFailure,
        )
    }

    @Test
    fun persistedSchemaTwoTutorPlanManifestKeepsItsOriginalDisclosure() {
        val legacyManifest = legacyTutorPlanManifest().copy(
            schemaVersion = 2,
            prohibitedData = ModelEgressManifest.dataClassUniverseForSchema(2) -
                ModelEgressManifest.LEGACY_TUTOR_PLAN_DISCLOSURE,
        )
        val request = legacyTutorPlanRequest().copy(
            schemaVersion = 2,
            egressManifest = legacyManifest,
        )
        val encoded = ModelTaskCodec.encodeRequest(request)
        val legacyJson = encoded.replace(",\"priorCycleStudentMessages\":[]", "")
            .replace(",\"agentConsentGranted\":false", "")
        val expectedFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(legacyJson.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val decoded = ModelTaskCodec.decodeRequest(legacyJson)

        assertEquals(2, decoded.schemaVersion)
        assertEquals(2, requireNotNull(decoded.egressManifest).schemaVersion)
        assertEquals(expectedFingerprint, ModelTaskFingerprint.of(decoded))
        assertTrue(
            runCatching {
            ModelEgressPolicy.authorize(
                decoded,
                tutorProvider(ModelTaskKind.TUTOR_PLAN),
                101,
            )
            }.exceptionOrNull() is ModelEgressAuthorizationException,
        )
    }

    @Test
    fun currentTutorPlanDisclosureIncludesBoundedConversationContext() {
        assertEquals(
            ModelEgressManifest.LEGACY_TUTOR_PLAN_DISCLOSURE + setOf(
                ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
                ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE,
            ),
            ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
        )
    }

    @Test
    fun tutorResponseApprovalUsesTheExactNewDisclosure() {
        val manifest = tutorRespondManifest()
        val request = tutorRespondRequest(manifest)

        assertEquals(
            setOf(
                ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
                ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE,
                ModelEgressDataClass.QUESTION_LEARNING_EVIDENCE,
                ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
                ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE,
            ),
            manifest.disclosedData,
        )
        assertTrue(
            ModelEgressPolicy.authorize(
                request,
                tutorProvider(ModelTaskKind.TUTOR_RESPOND),
                101,
            ).permit is ModelExecutionPermit.External,
        )
        assertTrue(
            runCatching {
                manifest.copy(
                    disclosedData = manifest.disclosedData -
                        ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                    prohibitedData = manifest.prohibitedData +
                        ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
                )
            }.isFailure,
        )
    }

    @Test
    fun staleOrFutureApprovalIsRejectedBeforeExternalExecution() {
        val expired = runCatching {
            ModelEgressPolicy.authorize(
                request(manifest()),
                externalProvider(),
                101 + MODEL_EGRESS_APPROVAL_TTL_MILLIS + 1,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException
        val future = runCatching {
            ModelEgressPolicy.authorize(
                request(manifest().copy(approvedAtEpochMillis = 10_000_000)),
                externalProvider(),
                101,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, expired.failureCode)
        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, future.failureCode)
    }

    @Test
    fun tutorConversationGrantMayPrecedeANewMessageWhileItIsStillFresh() {
        val manifest = tutorRespondManifest().copy(approvedAtEpochMillis = 90)

        val execution = ModelEgressPolicy.authorize(
            tutorRespondRequest(manifest),
            tutorProvider(ModelTaskKind.TUTOR_RESPOND),
            101,
        )

        assertTrue(execution.permit is ModelExecutionPermit.External)
    }

    @Test
    fun tutorConversationGrantMayPrecedeANewPlanWhileItIsStillFresh() {
        val request = legacyTutorPlanRequest().copy(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            egressManifest = currentTutorPlanManifest().copy(approvedAtEpochMillis = 90),
        )

        val execution = ModelEgressPolicy.authorize(
            request,
            tutorProvider(ModelTaskKind.TUTOR_PLAN),
            101,
        )

        assertTrue(execution.permit is ModelExecutionPermit.External)
    }

    @Test
    fun lobbyApprovalFromTheSameSendDecisionAuthorizesTheMessage() {
        val execution = ModelEgressPolicy.authorize(
            tutorLobbyRequest(tutorLobbyManifest(approvedAtEpochMillis = 100))
                .copy(occurredAtEpochMillis = 100),
            tutorProvider(ModelTaskKind.TUTOR_LOBBY),
            100,
        )

        assertTrue(execution.permit is ModelExecutionPermit.External)
    }

    @Test
    fun lobbyApprovalOneMillisecondOlderThanTheMessageIsRejected() {
        // 大厅发送一度各自读一次时钟：授权时刻与请求时刻只要错开一毫秒，请求就在任何网络
        // 动作之前被本地拒掉（stage=PREPARING、attempt=0），学生看到"刚发出去一秒就说
        // 没准备好"。这条要求本身是对的——过期的授权不得给新请求背书——所以修法只能是
        // 让两个时刻来自同一次用户动作，而不是放宽这条校验。
        val rejected = runCatching {
            ModelEgressPolicy.authorize(
                tutorLobbyRequest(tutorLobbyManifest(approvedAtEpochMillis = 99))
                    .copy(occurredAtEpochMillis = 100),
                tutorProvider(ModelTaskKind.TUTOR_LOBBY),
                100,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, rejected.failureCode)
    }

    @Test
    fun aLobbyMessageMayCarryEarlierImagesOnlyWithinItsGrantedScope() {
        // 追问带上文图片：授权范围必须逐张覆盖它们。少授权一张就拒绝——图片不因为来自
        // 历史消息而少一分披露；这些字节这次同样要出网。
        val carried = CaptureSourceAssetRef(
            assetId = "asset-old",
            sha256 = "b".repeat(64),
            width = 1_080,
            height = 1_440,
            pageIndex = 0,
        )
        val granted = tutorLobbyManifest(
            approvedAtEpochMillis = 100,
        ).copy(
            assets = listOf(
                ModelEgressAssetGrant(
                    assetId = "asset-old",
                    sha256 = "b".repeat(64),
                    byteSize = 2_048,
                    width = 1_080,
                    height = 1_440,
                ),
            ),
            disclosedData = ModelEgressManifest.TUTOR_LOBBY_IMAGE_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_LOBBY_IMAGE_PROHIBITED_DATA,
        )

        val authorized = ModelEgressPolicy.authorize(
            tutorLobbyRequest(granted).copy(
                input = TutorLobbyInput(
                    conversationId = "tutor-conv-1",
                    messageOrdinal = 2,
                    studentMessage = "第三题",
                    contextImageAssetRefs = listOf(carried),
                ),
                occurredAtEpochMillis = 100,
            ),
            tutorProvider(ModelTaskKind.TUTOR_LOBBY),
            100,
        )
        val withoutGrant = runCatching {
            // 只授权纯文本的清单：这次偏偏要带一张上文图片出网，必须被拒。
            ModelEgressPolicy.authorize(
                tutorLobbyRequest(tutorLobbyManifest(approvedAtEpochMillis = 100)).copy(
                    input = TutorLobbyInput(
                        conversationId = "tutor-conv-1",
                        messageOrdinal = 2,
                        studentMessage = "第三题",
                        contextImageAssetRefs = listOf(carried),
                    ),
                    occurredAtEpochMillis = 100,
                ),
                tutorProvider(ModelTaskKind.TUTOR_LOBBY),
                100,
            )
        }.exceptionOrNull() as ModelEgressAuthorizationException

        assertTrue(authorized.permit is ModelExecutionPermit.External)
        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_INVALID, withoutGrant.failureCode)
    }

    private fun request(manifest: ModelEgressManifest?) = ModelTaskRequest(
        requestId = "capture-assess:request-1",
        input = CaptureAssessmentInput(
            draftId = "draft-1",
            sourceAssetId = "asset-1",
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = 1080,
            imageHeight = 1440,
        ),
        occurredAtEpochMillis = 100,
        egressManifest = manifest,
    )

    private fun manifest() = ModelEgressManifest(
        authorizationId = "approval-1",
        subjectId = "draft-1",
        purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
        authorizedTaskKinds = setOf(
            ModelTaskKind.CAPTURE_ASSESS,
            ModelTaskKind.CAPTURE_PARSE,
        ),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
        approvedAtEpochMillis = 101,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = "asset-1",
                sha256 = "a".repeat(64),
                byteSize = 2_048,
                width = 1080,
                height = 1440,
            ),
        ),
        disclosedData = setOf(
            ModelEgressDataClass.SANITIZED_IMAGE_BYTES,
            ModelEgressDataClass.IMAGE_DIMENSIONS,
        ),
        prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
    )

    private fun legacyTutorPlanRequest() = ModelTaskRequest(
        requestId = "tutor-plan:legacy-request",
        input = TutorPlanInput(
            sessionId = "tutor-session-1",
            draftRevisionNumber = 2,
            subject = "MATH",
            questionDocument = confirmedQuestion(),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
        ),
        occurredAtEpochMillis = 100,
        egressManifest = legacyTutorPlanManifest(),
    )

    private fun legacyTutorPlanManifest() = ModelEgressManifest(
        schemaVersion = 1,
        authorizationId = "tutor-plan-legacy-approval",
        subjectId = "tutor-session-1",
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = "tutor-plan-v1",
        approvedAtEpochMillis = 101,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.LEGACY_TUTOR_PLAN_DISCLOSURE,
        prohibitedData = ModelEgressManifest.SCHEMA_V1_DATA_CLASSES -
            ModelEgressManifest.LEGACY_TUTOR_PLAN_DISCLOSURE,
    )

    /**
     * 学生可以在讲题会话里附上自己的图片，而 Respond 走的是"已配置模型 = 全局同意"这条通道，
     * 没有逐次披露清单兜底。所以图片能否出网必须由图片能力门自己把关：文本模型既拿不到
     * 全局同意的放行，也没有 manifest 可依，只能失败关闭。
     */
    @Test
    fun anImageBearingRespondRoundNeedsAnImageCapableProvider() {
        val request = ModelTaskRequest(
            requestId = "tutor-respond:message-images",
            input = TutorRespondInput(
                sessionId = "tutor-session-1",
                draftRevisionNumber = 2,
                subject = "MATH",
                questionDocument = confirmedQuestion(),
                relevantLearningEvidence = emptyList(),
                projectionIsCurrent = true,
                responseOrdinal = 1,
                studentMessage = "看看我写的这一步对不对。",
                studentImageAssetRefs = listOf("asset-1"),
            ),
            occurredAtEpochMillis = 100,
            agentConsentGranted = true,
        )

        val denied = runCatching {
            ModelEgressPolicy.authorize(
                request = request,
                provider = tutorProvider(ModelTaskKind.TUTOR_RESPOND),
                nowEpochMillis = 100,
            )
        }.exceptionOrNull()
        assertTrue(
            "A text-only provider must not receive a student image: $denied",
            denied is ModelEgressAuthorizationException,
        )

        val authorized = ModelEgressPolicy.authorize(
            request = request,
            provider = tutorProvider(ModelTaskKind.TUTOR_RESPOND).copy(supportsImageInput = true),
            nowEpochMillis = 100,
        )
        assertEquals(ModelExecutionPermit.ProviderConsented, authorized.permit)
    }

    /** 纯文本的 Respond 不受图片能力限制：它本来就不带图片字节。 */
    @Test
    fun aTextOnlyRespondRoundStillRunsOnATextOnlyProvider() {
        val authorized = ModelEgressPolicy.authorize(
            request = tutorRespondRequest(tutorRespondManifest()).copy(
                egressManifest = null,
                agentConsentGranted = true,
            ),
            provider = tutorProvider(ModelTaskKind.TUTOR_RESPOND),
            nowEpochMillis = 100,
        )

        assertEquals(ModelExecutionPermit.ProviderConsented, authorized.permit)
    }

    private fun currentTutorPlanManifest() = ModelEgressManifest(
        authorizationId = "tutor-plan-current-approval",
        subjectId = "tutor-session-1",
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_PLAN,
        approvedAtEpochMillis = 101,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
        prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA,
    )

    // ---- 披露三态：无题 / 有题 / 有题带图（外加候选菜单这一维）----
    // 每一态都断言**两件事**：disclosedData == expected，且 prohibited == 全集 − 已披露。
    // 披露集合是精确相等校验的，任何一边单独改动都会在这里对不上。

    @Test
    fun `a no-question round discloses no question document`() {
        val expected = TutorRoundDisclosure.expected(
            carriesQuestion = false,
            includesImage = false,
            includesQuestionCandidates = false,
        )

        assertEquals(ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE, expected)
        assertFalse(ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT in expected)
        assertEquals(
            ModelEgressDataClass.entries.toSet() - expected,
            ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
        )
    }

    @Test
    fun `a question round discloses the confirmed question document`() {
        val expected = TutorRoundDisclosure.expected(
            carriesQuestion = true,
            includesImage = false,
            includesQuestionCandidates = false,
        )

        assertEquals(ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE, expected)
        assertTrue(ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT in expected)
        assertEquals(
            ModelEgressDataClass.entries.toSet() - expected,
            ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
        )
    }

    @Test
    fun `a question round with an image additionally discloses image classes`() {
        val expected = TutorRoundDisclosure.expected(
            carriesQuestion = true,
            includesImage = true,
            includesQuestionCandidates = false,
        )

        assertTrue(ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT in expected)
        assertTrue(ModelEgressDataClass.SANITIZED_IMAGE_BYTES in expected)
        assertTrue(ModelEgressDataClass.IMAGE_DIMENSIONS in expected)
        assertFalse(ModelEgressDataClass.SELECTED_IMAGE_REGION in expected)
    }

    @Test
    fun `a no-question round with an image keeps the existing lobby image disclosure`() {
        // 这一态不是本轮新加的，而是大厅既有的附图通道：写成同一口径后取值必须逐字不变。
        val expected = TutorRoundDisclosure.expected(
            carriesQuestion = false,
            includesImage = true,
            includesQuestionCandidates = false,
        )

        assertEquals(ModelEgressManifest.TUTOR_LOBBY_IMAGE_DISCLOSURE, expected)
        assertFalse(ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT in expected)
        assertEquals(
            ModelEgressDataClass.entries.toSet() - expected,
            ModelEgressManifest.TUTOR_LOBBY_IMAGE_PROHIBITED_DATA,
        )
    }

    @Test
    fun `a carried candidate menu is disclosed as its own data class`() {
        val withMenu = TutorRoundDisclosure.expected(
            carriesQuestion = true,
            includesImage = false,
            includesQuestionCandidates = true,
        )

        assertEquals(
            ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE +
                ModelEgressDataClass.RELATED_QUESTION_CANDIDATES,
            withMenu,
        )
        assertFalse(ModelEgressDataClass.RELATED_QUESTION_CANDIDATES in ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE)
    }

    @Test
    fun `a manifest that understates the candidate menu is rejected`() {
        // 请求里带着菜单，清单却说"没有候选菜单"——少报就是真实的越界披露，必须被拒。
        assertThrows(IllegalArgumentException::class.java) {
            ModelEgressPolicy.authorize(
                request = tutorRespondRequestWithMenu(
                    tutorRespondManifest().copy(includesQuestionCandidates = false),
                ),
                provider = tutorProvider(ModelTaskKind.TUTOR_RESPOND),
                nowEpochMillis = 101,
            )
        }
    }

    @Test
    fun `a manifest that covers the candidate menu authorizes the round`() {
        val covered = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE +
            ModelEgressDataClass.RELATED_QUESTION_CANDIDATES
        val manifest = tutorRespondManifest().copy(
            disclosedData = covered,
            prohibitedData = ModelEgressDataClass.entries.toSet() - covered,
            includesQuestionCandidates = true,
        )

        val execution = ModelEgressPolicy.authorize(
            request = tutorRespondRequestWithMenu(manifest),
            provider = tutorProvider(ModelTaskKind.TUTOR_RESPOND),
            // 授权新鲜度是短时窗口，测试时钟必须与 approvedAtEpochMillis 对齐。
            nowEpochMillis = 101,
        )

        assertTrue(execution.permit is ModelExecutionPermit.External)
    }

    @Test
    fun `a manifest that overstates the candidate menu is rejected too`() {
        // 反向：清单说覆盖了菜单，请求里却没有菜单 —— 多报同样与请求不一致。
        val covered = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE +
            ModelEgressDataClass.RELATED_QUESTION_CANDIDATES
        val manifest = tutorRespondManifest().copy(
            disclosedData = covered,
            prohibitedData = ModelEgressDataClass.entries.toSet() - covered,
            includesQuestionCandidates = true,
        )

        assertThrows(IllegalArgumentException::class.java) {
            ModelEgressPolicy.authorize(
                request = tutorRespondRequest(manifest),
                provider = tutorProvider(ModelTaskKind.TUTOR_RESPOND),
                nowEpochMillis = 101,
            )
        }
    }

    @Test
    fun `a question round manifest still refuses image assets`() {
        // 这条既有断言（在 requireAuthorizes 里）正是"有题带图"这一态在**清单路径上**不可达的
        // 原因：Respond 的附图只走全局同意通道。把它钉住，免得有人以为那一态已经接线、
        // 或者反过来悄悄放宽它。
        val manifest = tutorRespondManifest().copy(
            assets = listOf(
                ModelEgressAssetGrant(
                    assetId = "asset-1",
                    sha256 = "a".repeat(64),
                    byteSize = 1_024,
                    width = 100,
                    height = 100,
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ModelEgressPolicy.authorize(
                request = tutorRespondRequest(manifest),
                provider = tutorProvider(ModelTaskKind.TUTOR_RESPOND),
                nowEpochMillis = 101,
            )
        }
    }

    @Test
    fun `a legacy manifest cannot claim to cover a candidate menu`() {
        assertThrows(IllegalArgumentException::class.java) {
            tutorRespondManifest().copy(
                schemaVersion = 6,
                includesQuestionCandidates = true,
            )
        }
    }

    private fun tutorRespondRequestWithMenu(manifest: ModelEgressManifest) = ModelTaskRequest(
        requestId = "tutor-respond:request-menu",
        input = TutorRespondInput(
            sessionId = "tutor-session-1",
            draftRevisionNumber = 2,
            subject = "MATH",
            questionDocument = confirmedQuestion(),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "再把光的折射那道题讲一遍",
            boundQuestionCandidates = listOf(
                RelatedProblemCandidate(
                    problemId = "problem-other",
                    problemRevisionId = "revision-other",
                    subject = SubjectKind.PHYSICS,
                    title = "光的折射实验",
                    questionDocument = QuestionDocument(
                        id = "question-other",
                        blocks = listOf(
                            ContentBlock.Paragraph("stem-other", "入射角与折射角的关系"),
                        ),
                    ),
                ),
            ),
        ),
        occurredAtEpochMillis = 100,
        egressManifest = manifest,
    )

    private fun tutorRespondRequest(manifest: ModelEgressManifest) = ModelTaskRequest(
        requestId = "tutor-respond:request-1",
        input = TutorRespondInput(
            sessionId = "tutor-session-1",
            draftRevisionNumber = 2,
            subject = "MATH",
            questionDocument = confirmedQuestion(),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = 1,
            studentMessage = "请解释当前题这一步。",
        ),
        occurredAtEpochMillis = 100,
        egressManifest = manifest,
    )

    private fun tutorRespondManifest() = ModelEgressManifest(
        authorizationId = "tutor-respond-approval",
        subjectId = "tutor-session-1",
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_RESPOND,
        approvedAtEpochMillis = 101,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
        prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
    )

    private fun tutorLobbyRequest(manifest: ModelEgressManifest) = ModelTaskRequest(
        requestId = "tutor-lobby:request-1",
        input = TutorLobbyInput(
            conversationId = "tutor-conv-1",
            messageOrdinal = 1,
            studentMessage = "解一下这个题吧",
        ),
        occurredAtEpochMillis = 100,
        egressManifest = manifest,
    )

    private fun tutorLobbyManifest(approvedAtEpochMillis: Long = 101) = ModelEgressManifest(
        authorizationId = "tutor-lobby-approval",
        subjectId = "tutor-conv-1",
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_LOBBY),
        providerId = "provider-1",
        modelId = "vision-model-1",
        providerConfigurationVersion = "provider-config-v1",
        promptPolicyVersion = ModelPromptPolicyVersions.TUTOR_LOBBY,
        approvedAtEpochMillis = approvedAtEpochMillis,
        assets = emptyList(),
        disclosedData = ModelEgressManifest.TUTOR_LOBBY_DISCLOSURE,
        prohibitedData = ModelEgressManifest.TUTOR_LOBBY_PROHIBITED_DATA,
    )

    private fun confirmedQuestion() = QuestionDocument(
        id = "question-1",
        blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
    )

    private fun tutorProvider(kind: ModelTaskKind) = externalProvider().copy(
        supportedTasks = setOf(kind),
        supportsImageInput = false,
    )

    private fun externalProvider() = ProviderCapabilitySnapshot(
        providerId = "provider-1",
        providerDisplayName = "我的视觉模型",
        modelId = "vision-model-1",
        supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS, ModelTaskKind.CAPTURE_PARSE),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = "provider-config-v1",
    )
}
