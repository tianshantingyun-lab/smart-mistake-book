package com.tingyun.smartmistakebook.feature.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemRelation
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationConfirmation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationDurableStatus
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationReauthorizationOutcome
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationReauthorizationPreparation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskFailure
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MistakeOrganizationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun durableReauthorizationUsesTheExactWorkAndNeverResumesTheLegacyRequest() {
        val pending = ProblemOrganizationReauthorizationPreparation(
            key = KEY,
            workId = "organization-work-exact",
            expectedStateVersion = 7,
            requiresStudentConfirmation = true,
        )
        val organization = FakeOrganizationRepository(reauthorization = pending)
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = FakeModelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_reauthorization")
        composeRule.onNodeWithText(
            "本次题图、整理后的题面和相关知识资料会交给测试模型，用于保存后继续整理；" +
                "不会发送其他题目或学习记录。",
        ).assertExists()
        composeRule.onNodeWithText("同科目题面", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("mistake_organization_reauthorize").performClick()
        waitForTag("mistake_organization_running")

        assertEquals(pending, organization.reauthorizedPreparation)
        assertEquals(0, organization.prepareCallCount)
    }

    @Test
    fun switchingProblemCancelsInFlightDurableReauthorization() {
        val nextKey = MistakeRevisionKey("entry-2", "problem-2", "revision-2")
        val firstPending = ProblemOrganizationReauthorizationPreparation(
            key = KEY,
            workId = "organization-work-first",
            expectedStateVersion = 7,
            requiresStudentConfirmation = true,
        )
        val nextPending = ProblemOrganizationReauthorizationPreparation(
            key = nextKey,
            workId = "organization-work-next",
            expectedStateVersion = 3,
            requiresStudentConfirmation = true,
        )
        val reauthorizationStarted = CompletableDeferred<Unit>()
        val reauthorizationCancelled = CompletableDeferred<Unit>()
        val organization = FakeOrganizationRepository(
            reauthorizationByKey = mapOf(KEY to firstPending, nextKey to nextPending),
            onReauthorize = { preparation ->
                check(preparation.key == KEY)
                reauthorizationStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    reauthorizationCancelled.complete(Unit)
                }
            },
        )
        val detailState = mutableStateOf(readyState())
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = detailState.value,
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = FakeModelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_reauthorization")
        composeRule.onNodeWithTag("mistake_organization_reauthorize").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) { reauthorizationStarted.isCompleted }
        composeRule.runOnIdle { detailState.value = readyState(nextKey) }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            nextKey in organization.prepareReauthorizationKeys
        }
        composeRule.waitUntil(timeoutMillis = 20_000) { reauthorizationCancelled.isCompleted }

        waitForTag("mistake_organization_reauthorization")
        composeRule.onNodeWithTag("mistake_organization_message").assertDoesNotExist()
    }

    @Test
    fun providerChangeBeforeApprovalRequiresTheStudentToConfirmAgain() {
        val pending = ProblemOrganizationReauthorizationPreparation(
            key = KEY,
            workId = "organization-work-provider-change",
            expectedStateVersion = 9,
            requiresStudentConfirmation = true,
        )
        val organization = FakeOrganizationRepository(reauthorization = pending)
        val modelTasks = ChangingCapabilityModelTasks()
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = modelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_reauthorization")
        composeRule.onNodeWithTag("mistake_organization_reauthorize").performClick()
        waitForTag("mistake_organization_message")

        composeRule.onNodeWithText("模型配置已变化，请重新确认").assertExists()
        assertNull(organization.reauthorizedPreparation)
    }

    @Test
    fun durableLookupFailureNeverFallsBackToLegacyOrganization() {
        val organization = FakeOrganizationRepository(reauthorizationFailure = true)
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = StrictDurableModelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_reauthorization_retry")

        composeRule.onNodeWithText("暂时无法读取整理进度").assertExists()
        assertEquals(0, organization.prepareCallCount)
    }

    @Test
    fun durableActiveOccurrenceNeverFallsBackToLegacyOrganization() {
        val active = ProblemOrganizationReauthorizationPreparation(
            key = KEY,
            workId = "organization-work-active",
            expectedStateVersion = 11,
            requiresStudentConfirmation = false,
            durableStatus = ProblemOrganizationDurableStatus.ACTIVE,
        )
        val organization = FakeOrganizationRepository(reauthorization = active)
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = StrictDurableModelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_running")

        assertEquals(0, organization.prepareCallCount)
    }

    @Test
    fun durableTerminalOccurrenceNeverFallsBackToLegacyOrganization() {
        val terminal = ProblemOrganizationReauthorizationPreparation(
            key = KEY,
            workId = "organization-work-terminal",
            expectedStateVersion = 12,
            requiresStudentConfirmation = false,
            durableStatus = ProblemOrganizationDurableStatus.TERMINAL,
        )
        val organization = FakeOrganizationRepository(reauthorization = terminal)
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = StrictDurableModelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            organization.prepareReauthorizationCallCount >= 1
        }

        assertEquals(0, organization.prepareCallCount)
        composeRule.onNodeWithTag("mistake_organization_consent").assertDoesNotExist()
    }

    @Test
    fun pendingAndRunningV3SnapshotsNeverEnterTheLegacyOrganizationPath() {
        val organization = FakeOrganizationRepository()
        val modelTasks = DurableV3ModelTasks(v3Snapshot(ModelTaskStatus.WAITING_FOR_MODEL))
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = modelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_running")
        composeRule.runOnIdle { modelTasks.update(v3Snapshot(ModelTaskStatus.RUNNING)) }
        waitForTag("mistake_organization_running")

        assertEquals(0, organization.prepareCallCount)
        assertEquals(0, modelTasks.executeCallCount)
    }

    @Test
    fun studentSeesExactDisclosureAndCanLeaveWithoutSending() {
        val organization = FakeOrganizationRepository()
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = FakeModelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_consent")
        composeRule.captureLibraryQaScreenshot("mistake-organization-consent-current.png")

        composeRule.onNodeWithText("确认本次发送范围").assertExists()
        composeRule.onNodeWithText(
            "会把当前题面、1 道同科目题面发给测试模型，只用于整理板块、细化知识点和题目关系；不发送原图或学习记录。",
        ).assertExists()
        composeRule.onNodeWithText("API 密钥", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("暂不整理").performClick()
        composeRule.onNodeWithTag("mistake_organization_consent").assertDoesNotExist()
    }

    @Test
    fun successfulResultIsAppliedWithoutCheckboxesAndEditorOpensOnlyOnRequest() {
        val organization = FakeOrganizationRepository()
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = successfulModelTasks(),
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_consent")
        composeRule.onNodeWithTag("mistake_organization_approve").performClick()
        waitForTag("mistake_organization_applied")

        assertEquals("organization-test-0", organization.appliedRequestId)
        assertEquals(1, organization.prepareCallCount)
        assertNull(organization.lastSelection)
        composeRule.onNodeWithText("按选择保存").assertDoesNotExist()
        composeRule.onNodeWithTag("mistake_classification_0").assertDoesNotExist()
        composeRule.onNodeWithText("已整理").assertExists()
        composeRule.onNodeWithText("板块：函数").assertExists()
        composeRule.onNodeWithText("知识点：二次函数最值").assertExists()

        composeRule.onNodeWithTag("mistake_organization_correct_toggle")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("mistake_classification_0").assertExists()
        composeRule.onNodeWithTag("mistake_add_classification_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("mistake_custom_label").performScrollTo()
            .performTextInput("导数零点与单调区间")
        composeRule.onNodeWithTag("mistake_custom_add").performScrollTo().performClick()
        composeRule.onNodeWithText("你补充的", substring = true).assertExists()
        composeRule.onNodeWithTag("mistake_organization_confirm").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            organization.lastSelection != null
        }

        val correction = requireNotNull(organization.lastSelection).userClassifications.single()
        assertEquals(ClassificationDimension.KNOWLEDGE, correction.dimension)
        assertEquals("导数零点与单调区间", correction.displayName)
    }

    @Test
    fun rotationKeepsExternalPendingTaskPausedUntilOneNewConfirmation() {
        val organization = FakeOrganizationRepository()
        val persistedRequest = organizationRequest(
            requestId = "organization-rotation-pending",
            occurredAtEpochMillis = 10_000L,
            approvedAtEpochMillis = 10_500L,
        )
        val modelTasks = DurableOrganizationModelTasks(
            initialTask = pendingSnapshot(persistedRequest),
        )
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = modelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_paused")
        assertTrue(modelTasks.executedRequests.isEmpty())

        restorationTester.emulateSavedInstanceStateRestore()
        waitForTag("mistake_organization_paused")
        assertTrue(modelTasks.executedRequests.isEmpty())
        composeRule.onNodeWithTag("mistake_organization_continue").performClick()
        waitForTag("mistake_organization_applied")

        assertEquals(0, organization.prepareCallCount)
        assertRenewedExternalRequest(persistedRequest, modelTasks.executedRequests.single())
    }

    @Test
    fun reentryHydratesExistingSucceededTaskWithoutPreparingOrExecutingAgain() {
        val organization = FakeOrganizationRepository()
        val persistedRequest = organizationRequest(
            requestId = "organization-existing-success",
            occurredAtEpochMillis = 12_345L,
            approvedAtEpochMillis = 12_999L,
        )
        val modelTasks = DurableOrganizationModelTasks(
            initialTask = successfulSnapshot(persistedRequest),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = modelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_applied")

        assertEquals(0, organization.prepareCallCount)
        assertTrue(modelTasks.executedRequests.isEmpty())
        assertEquals(persistedRequest.requestId, organization.appliedRequestId)
        composeRule.onNodeWithTag("mistake_organization_consent").assertDoesNotExist()
    }

    @Test
    fun reentryRequiresOneNewConfirmationBeforeResumingExternalPendingTask() {
        val organization = FakeOrganizationRepository()
        val persistedRequest = organizationRequest(
            requestId = "organization-existing-pending",
            occurredAtEpochMillis = 23_456L,
            approvedAtEpochMillis = 23_999L,
        )
        val modelTasks = DurableOrganizationModelTasks(
            initialTask = pendingSnapshot(persistedRequest),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = modelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_paused")

        assertEquals(0, organization.prepareCallCount)
        assertTrue(modelTasks.executedRequests.isEmpty())
        composeRule.onNodeWithText("整理已暂停").assertExists()
        composeRule.onNodeWithTag("mistake_organization_continue").performClick()
        waitForTag("mistake_organization_applied")

        val resumedRequest = modelTasks.executedRequests.single()
        assertRenewedExternalRequest(persistedRequest, resumedRequest)
        assertEquals(resumedRequest.requestId, organization.appliedRequestId)
        composeRule.onNodeWithTag("mistake_organization_paused").assertDoesNotExist()
    }

    @Test
    fun reentryRequiresOneNewConfirmationBeforeRetryingExternalFailure() {
        val organization = FakeOrganizationRepository()
        val persistedRequest = organizationRequest(
            requestId = "organization-existing-retryable",
            occurredAtEpochMillis = 24_456L,
            approvedAtEpochMillis = 24_999L,
        )
        val modelTasks = DurableOrganizationModelTasks(
            initialTask = retryableSnapshot(persistedRequest),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = modelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_paused")
        assertTrue(modelTasks.executedRequests.isEmpty())
        composeRule.onNodeWithText("整理已暂停").assertExists()
        composeRule.onNodeWithTag("mistake_organization_continue").performClick()
        waitForTag("mistake_organization_applied")

        assertRenewedExternalRequest(persistedRequest, modelTasks.executedRequests.single())
    }

    @Test
    fun reentryAutomaticallyResumesLocalPendingTaskWithItsExactRequest() {
        val organization = FakeOrganizationRepository()
        val persistedRequest = organizationRequest(
            requestId = "organization-local-pending",
            occurredAtEpochMillis = 34_567L,
            approvedAtEpochMillis = 34_567L,
        ).copy(egressManifest = null)
        val localProvider = PROVIDER.copy(executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS)
        val modelTasks = DurableOrganizationModelTasks(
            initialTask = pendingSnapshot(persistedRequest),
            provider = localProvider,
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = modelTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_applied")

        assertEquals(0, organization.prepareCallCount)
        assertEquals(listOf(persistedRequest), modelTasks.executedRequests)
        composeRule.onNodeWithTag("mistake_organization_paused").assertDoesNotExist()
    }

    @Test
    fun incompleteResultKeepsTheScreenToOneRetryAction() {
        val organization = FakeOrganizationRepository(automaticApplied = false)
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = successfulModelTasks(),
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_consent")
        composeRule.onNodeWithTag("mistake_organization_approve").performClick()
        waitForTag("mistake_organization_incomplete")

        composeRule.onNodeWithText("暂未整理完整").assertExists()
        composeRule.onNodeWithTag("mistake_organization_retry").assertExists()
        composeRule.onNodeWithTag("mistake_organization_correct_toggle").assertDoesNotExist()
        composeRule.onNodeWithTag("mistake_classification_0").assertDoesNotExist()
    }

    @Test
    fun capabilityFailureOffersWorkingRetryAndSettingsActions() {
        val organization = FakeOrganizationRepository()
        var capabilityCalls = 0
        var settingsOpened = false
        val recoveringTasks = object : ModelTaskRepository by FakeModelTasks {
            override suspend fun capabilities(): ProviderCapabilitySnapshot {
                capabilityCalls += 1
                if (capabilityCalls == 1) error("temporary capability failure")
                return PROVIDER
            }
        }
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = recoveringTasks,
                    profile = StudyProfileOverview(),
                    onOpenModelSettings = { settingsOpened = true },
                )
            }
        }

        waitForTag("mistake_organization_capability_failure")
        composeRule.onNodeWithText("检查模型设置").performClick()
        assertTrue(settingsOpened)
        composeRule.onNodeWithText("重试").performClick()
        waitForTag("mistake_organization_consent")
        assertEquals(2, capabilityCalls)
    }

    @Test
    fun localApplyRetryReusesSuccessfulRequestWithoutExecutingModelAgain() {
        val organization = FakeOrganizationRepository(applyFailuresBeforeSuccess = 1)
        val delegate = successfulModelTasks()
        var executeCalls = 0
        val countingTasks = object : ModelTaskRepository by delegate {
            override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> {
                executeCalls += 1
                return delegate.execute(request)
            }
        }
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = countingTasks,
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_consent")
        composeRule.onNodeWithTag("mistake_organization_approve").performClick()
        waitForTag("mistake_organization_apply_failure")
        composeRule.onNodeWithTag("mistake_organization_apply_retry").performClick()
        waitForTag("mistake_organization_applied")

        assertEquals(1, executeCalls)
        assertEquals(2, organization.applyCallCount)
        assertEquals("organization-test-0", organization.appliedRequestId)
    }

    @Test
    fun plannedRemovalOfExistingRelationCanBeUndoneBeforeSave() {
        val organization = FakeOrganizationRepository(
            relationsAfterApply = listOf(
                ConfirmedProblemRelation(
                    targetProblemId = "problem-2",
                    targetProblemRevisionId = "revision-2",
                    kind = ProblemRelationKind.VARIANT_OF,
                    confidence = 0.95,
                ),
            ),
        )
        composeRule.setContent {
            SmartMistakeBookTheme {
                MistakeDetailContent(
                    state = readyState(),
                    onBack = {},
                    onExport = {},
                    organizationRepository = organization,
                    modelTasks = successfulModelTasks(),
                    profile = StudyProfileOverview(),
                )
            }
        }

        waitForTag("mistake_organization_consent")
        composeRule.onNodeWithTag("mistake_organization_approve").performClick()
        waitForTag("mistake_organization_applied")
        composeRule.onNodeWithTag("mistake_organization_correct_toggle")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("mistake_existing_relation_remove_0")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("恢复").assertExists()
        composeRule.onNodeWithTag("mistake_existing_relation_remove_0").performClick()
        composeRule.onNodeWithText("移除").assertExists()
        composeRule.onNodeWithTag("mistake_organization_confirm").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) { organization.lastSelection != null }

        assertTrue(requireNotNull(organization.lastSelection).relationRemovals.isEmpty())
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertRenewedExternalRequest(
        persisted: ModelTaskRequest,
        resumed: ModelTaskRequest,
    ) {
        assertNotEquals(persisted.requestId, resumed.requestId)
        assertEquals(persisted.input, resumed.input)
        assertEquals(persisted.occurredAtEpochMillis, resumed.occurredAtEpochMillis)
        val persistedManifest = requireNotNull(persisted.egressManifest)
        val resumedManifest = requireNotNull(resumed.egressManifest)
        assertNotEquals(persistedManifest.authorizationId, resumedManifest.authorizationId)
        assertNotEquals(
            persistedManifest.approvedAtEpochMillis,
            resumedManifest.approvedAtEpochMillis,
        )
        assertEquals(PROVIDER.providerId, resumedManifest.providerId)
        assertEquals(PROVIDER.modelId, resumedManifest.modelId)
        assertEquals(
            PROVIDER.providerConfigurationVersion,
            resumedManifest.providerConfigurationVersion,
        )
        assertEquals(
            ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
            resumedManifest.promptPolicyVersion,
        )
    }

    private fun readyState(key: MistakeRevisionKey = KEY) = MistakeDetailState.Ready(
        detail = MistakeDetail(
            identity = MistakeDetailIdentity(
                errorBookEntryId = key.entryId,
                problemId = key.problemId,
                problemRevisionId = key.problemRevisionId,
                revisionNumber = 1,
                title = "函数最值",
                subject = "MATH",
            ),
            fallbackMarkdown = "求函数最值。",
            source = MistakeSourceSet.Missing,
        ),
        questionDocument = CapturedQuestionDocument(
            document = document("question", "求函数最值。"),
            blockEvidence = emptyList(),
        ),
    )

    private class FakeOrganizationRepository(
        private val automaticApplied: Boolean = true,
        private var applyFailuresBeforeSuccess: Int = 0,
        private val relationsAfterApply: List<ConfirmedProblemRelation> = emptyList(),
        private val reauthorization: ProblemOrganizationReauthorizationPreparation? = null,
        private val reauthorizationFailure: Boolean = false,
        private val reauthorizationByKey:
            Map<MistakeRevisionKey, ProblemOrganizationReauthorizationPreparation> = emptyMap(),
        private val onReauthorize:
            (suspend (ProblemOrganizationReauthorizationPreparation) ->
            ProblemOrganizationReauthorizationOutcome)? = null,
    ) : MistakeOrganizationRepository {
        var lastSelection: ProblemOrganizationSelection? = null
        var appliedRequestId: String? = null
        var reauthorizedPreparation: ProblemOrganizationReauthorizationPreparation? = null
        var applyCallCount: Int = 0
        var prepareCallCount: Int = 0
        var prepareReauthorizationCallCount: Int = 0
        val prepareReauthorizationKeys = mutableListOf<MistakeRevisionKey>()
        private val confirmed = MutableStateFlow(ConfirmedMistakeOrganization())

        override suspend fun prepareReauthorization(
            key: MistakeRevisionKey,
            provider: ProviderCapabilitySnapshot,
            occurredAtEpochMillis: Long,
        ): ProblemOrganizationReauthorizationPreparation? {
            prepareReauthorizationCallCount += 1
            prepareReauthorizationKeys += key
            if (reauthorizationFailure) error("durable lookup failed")
            return reauthorizationByKey[key] ?: reauthorization
        }

        override suspend fun reauthorize(
            preparation: ProblemOrganizationReauthorizationPreparation,
            provider: ProviderCapabilitySnapshot,
            approvedAtEpochMillis: Long,
        ): ProblemOrganizationReauthorizationOutcome {
            reauthorizedPreparation = preparation
            return onReauthorize?.invoke(preparation)
                ?: ProblemOrganizationReauthorizationOutcome.REAUTHORIZED
        }

        override suspend fun prepare(
            key: MistakeRevisionKey,
            profile: StudyProfileOverview,
            provider: ProviderCapabilitySnapshot,
            attempt: Int,
            occurredAtEpochMillis: Long,
            approvedAtEpochMillis: Long,
        ): MistakeOrganizationPreparation {
            prepareCallCount += 1
            val input = ProblemOrganizationInput(
                problemId = key.problemId,
                problemRevisionId = key.problemRevisionId,
                practiceUnitId = "practice-1",
                subject = SubjectKind.MATH,
                questionDocument = document("question", "求函数最值。"),
                relevantLearningEvidence = emptyList(),
                relationCandidates = listOf(
                    RelatedProblemCandidate(
                        problemId = "problem-2",
                        problemRevisionId = "revision-2",
                        subject = SubjectKind.MATH,
                        title = "二次函数变式",
                        questionDocument = document("related", "讨论参数范围。"),
                    ),
                ),
            )
            val requestId = "organization-test-$attempt"
            return MistakeOrganizationPreparation(
                request = ModelTaskRequest(
                    requestId = requestId,
                    input = input,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    egressManifest = ModelEgressManifest(
                        authorizationId = "authorization:$requestId",
                        subjectId = input.subjectId,
                        purpose = ModelEgressPurpose.CLASSIFICATION,
                        authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                        providerId = provider.providerId,
                        modelId = provider.modelId,
                        providerConfigurationVersion = provider.providerConfigurationVersion,
                        promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
                        approvedAtEpochMillis = approvedAtEpochMillis,
                        assets = emptyList(),
                        disclosedData = ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
                        prohibitedData = ModelEgressManifest.PROBLEM_ORGANIZATION_PROHIBITED_DATA,
                    ),
                ),
                relatedCandidateTitles = listOf("二次函数变式"),
            )
        }

        override fun observeConfirmed(key: MistakeRevisionKey): Flow<ConfirmedMistakeOrganization> =
            confirmed

        override suspend fun applySuccessfulOrganization(
            requestId: String,
        ): ProblemOrganizationConfirmation {
            appliedRequestId = requestId
            applyCallCount += 1
            if (applyFailuresBeforeSuccess > 0) {
                applyFailuresBeforeSuccess -= 1
                error("temporary local apply failure")
            }
            if (!automaticApplied) {
                return ProblemOrganizationConfirmation(false, 0, 0, applied = false)
            }
            confirmed.value = ConfirmedMistakeOrganization(
                classifications = listOf(
                    com.tingyun.smartmistakebook.core.domain.ConfirmedProblemClassification(
                        ClassificationDimension.CHAPTER,
                        "chapter-functions",
                        "函数",
                    ),
                    com.tingyun.smartmistakebook.core.domain.ConfirmedProblemClassification(
                        ClassificationDimension.KNOWLEDGE,
                        "knowledge-quadratic-maximum",
                        "二次函数最值",
                    ),
                ),
                relations = relationsAfterApply,
            )
            return ProblemOrganizationConfirmation(true, 2, 0)
        }

        override suspend fun confirm(
            requestId: String,
            selection: ProblemOrganizationSelection,
            acceptedAtEpochMillis: Long,
        ): ProblemOrganizationConfirmation {
            lastSelection = selection
            return ProblemOrganizationConfirmation(true, 1 + selection.userClassifications.size, 0)
        }
    }

    private inner class DurableOrganizationModelTasks(
        initialTask: ModelTaskSnapshot? = null,
        private val provider: ProviderCapabilitySnapshot = PROVIDER,
    ) : ModelTaskRepository {
        private val persistedTasks = MutableStateFlow(listOfNotNull(initialTask))
        val executedRequests = mutableListOf<ModelTaskRequest>()

        override suspend fun capabilities() = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> =
            flowOf(persistedTasks.value.singleOrNull { it.request.requestId == requestId })

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = persistedTasks

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            executedRequests += request
            val succeeded = successfulSnapshot(request, provider)
            persistedTasks.value = persistedTasks.value
                .filterNot { it.request.requestId == request.requestId } + succeeded
            emit(succeeded)
        }
    }

    private inner class DurableV3ModelTasks(initialTask: ModelTaskSnapshot) : ModelTaskRepository {
        private val persistedTasks = MutableStateFlow(listOf(initialTask))
        var executeCallCount: Int = 0
            private set

        override suspend fun capabilities() = PROVIDER

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> =
            flowOf(persistedTasks.value.singleOrNull { it.request.requestId == requestId })

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = persistedTasks

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> {
            executeCallCount += 1
            error("A durable v3 task must not enter the legacy execution path")
        }

        fun update(snapshot: ModelTaskSnapshot) {
            persistedTasks.value = listOf(snapshot)
        }
    }

    private class ChangingCapabilityModelTasks : ModelTaskRepository {
        private var capabilityReads = 0

        override suspend fun capabilities(): ProviderCapabilitySnapshot {
            capabilityReads += 1
            return if (capabilityReads == 1) {
                PROVIDER
            } else {
                PROVIDER.copy(
                    providerDisplayName = "更新后的测试模型",
                    modelId = "model-v2",
                    providerConfigurationVersion = "config-v2",
                )
            }
        }

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> =
            error("Durable reauthorization must not inspect legacy organization tasks")

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            error("Durable reauthorization must not execute a legacy request")
    }

    private fun organizationRequest(
        requestId: String,
        occurredAtEpochMillis: Long,
        approvedAtEpochMillis: Long,
    ): ModelTaskRequest {
        val input = ProblemOrganizationInput(
            problemId = KEY.problemId,
            problemRevisionId = KEY.problemRevisionId,
            practiceUnitId = "practice-1",
            subject = SubjectKind.MATH,
            questionDocument = document("question", "求函数最值。"),
            relevantLearningEvidence = emptyList(),
            relationCandidates = listOf(
                RelatedProblemCandidate(
                    problemId = "problem-2",
                    problemRevisionId = "revision-2",
                    subject = SubjectKind.MATH,
                    title = "二次函数变式",
                    questionDocument = document("related", "讨论参数范围。"),
                ),
            ),
        )
        return ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = occurredAtEpochMillis,
            egressManifest = ModelEgressManifest(
                authorizationId = "authorization:$requestId",
                subjectId = input.subjectId,
                purpose = ModelEgressPurpose.CLASSIFICATION,
                authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                providerId = PROVIDER.providerId,
                modelId = PROVIDER.modelId,
                providerConfigurationVersion = PROVIDER.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
                approvedAtEpochMillis = approvedAtEpochMillis,
                assets = emptyList(),
                disclosedData = ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
                prohibitedData = ModelEgressManifest.PROBLEM_ORGANIZATION_PROHIBITED_DATA,
            ),
        )
    }

    private fun pendingSnapshot(request: ModelTaskRequest) = ModelTaskSnapshot(
        taskId = "task-${request.requestId}",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = ModelTaskStatus.WAITING_FOR_MODEL,
        stateVersion = 0,
        stage = ModelTaskStage.WAITING,
        userMessage = "等待模型",
        attemptCount = 0,
        createdAtEpochMillis = request.occurredAtEpochMillis,
        updatedAtEpochMillis = request.occurredAtEpochMillis,
    )

    private fun v3Snapshot(status: ModelTaskStatus): ModelTaskSnapshot {
        val request = ModelTaskRequest(
            schemaVersion = ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION,
            requestId = "organization-v3-existing",
            input = ProblemOrganizationV3Input(
                problemId = KEY.problemId,
                problemRevisionId = KEY.problemRevisionId,
                practiceUnitId = "practice-1",
                subject = SubjectKind.MATH,
                capturedDocument = CapturedQuestionDocument(
                    document = document("question-v3", "求函数最值。"),
                    blockEvidence = listOf(
                        QuestionBlockEvidence(
                            blockId = "question-v3-block",
                            sourceAssetId = "asset-v3",
                            writingLayer = WritingLayer.PRINTED,
                            provenance = QuestionBlockProvenance.IMPORTED_STRUCTURE,
                            reviewStatus = QuestionBlockReviewStatus.LOCAL_POLICY_ACCEPTED,
                            producerVersion = "test-v1",
                        ),
                    ),
                ),
                sourceAssets = listOf(
                    CaptureSourceAssetRef(
                        assetId = "asset-v3",
                        sha256 = "a".repeat(64),
                        width = 1_200,
                        height = 1_600,
                        pageIndex = 0,
                    ),
                ),
                relationCandidates = emptyList(),
            ),
            occurredAtEpochMillis = 50_000L,
        )
        return ModelTaskSnapshot(
            taskId = "task-${request.requestId}",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = status,
            stateVersion = if (status == ModelTaskStatus.RUNNING) 1 else 0,
            stage = if (status == ModelTaskStatus.RUNNING) {
                ModelTaskStage.READING_IMAGE
            } else {
                ModelTaskStage.WAITING
            },
            userMessage = "正在整理",
            attemptCount = if (status == ModelTaskStatus.RUNNING) 1 else 0,
            createdAtEpochMillis = request.occurredAtEpochMillis,
            updatedAtEpochMillis = request.occurredAtEpochMillis +
                if (status == ModelTaskStatus.RUNNING) 1 else 0,
        )
    }

    private fun retryableSnapshot(request: ModelTaskRequest) = pendingSnapshot(request).copy(
        status = ModelTaskStatus.RETRYABLE_FAILURE,
        stateVersion = 1,
        userMessage = "整理暂时没有完成",
        attemptCount = 1,
        failure = ModelTaskFailure(
            code = ModelFailureCode.TIMEOUT,
            message = "连接暂时中断",
            retryable = true,
        ),
        updatedAtEpochMillis = request.occurredAtEpochMillis + 1,
    )

    private fun successfulSnapshot(
        request: ModelTaskRequest,
        provider: ProviderCapabilitySnapshot = PROVIDER,
    ): ModelTaskSnapshot {
        val input = request.input as ProblemOrganizationInput
        val output = ProblemOrganizationOutput(
            problemId = input.problemId,
            problemRevisionId = input.problemRevisionId,
            practiceUnitId = input.practiceUnitId,
            plan = ProblemOrganizationPlan(
                summaryMarkdown = "模型给出初步整理，请确认。",
                reviewPriorityMarkdown = "建议近期复习。",
                targetedEvidenceLabels = emptyList(),
                classifications = listOf(
                    ProblemClassificationSuggestion(
                        dimension = ClassificationDimension.CHAPTER,
                        displayName = "函数",
                        rationaleMarkdown = "模型识别的所属板块。",
                        confidence = 0.91,
                    ),
                    ProblemClassificationSuggestion(
                        dimension = ClassificationDimension.KNOWLEDGE,
                        displayName = "二次函数最值",
                        rationaleMarkdown = "模型识别的主要知识点。",
                        confidence = 0.82,
                    ),
                ),
                relations = emptyList(),
                schemaVersion = ProblemOrganizationPlan.SCHEMA_VERSION,
                atomicKnowledge = listOf(
                    AtomicKnowledgeSuggestion(
                        referenceId = "atom-1",
                        canonicalName = "比较顶点与区间端点的函数值",
                        aliases = emptyList(),
                        kind = KnowledgeNodeKind.PROCEDURE,
                        parentKnowledgeDisplayName = "二次函数最值",
                        matchedKnowledgeNodeId = null,
                        prerequisiteReferenceIds = emptyList(),
                        observableOutcomeMarkdown = "能找出候选位置并比较对应函数值。",
                        boundaryMarkdown = "只包含本题确定二次函数最值所需的比较步骤。",
                        confidence = 0.9,
                    ),
                ),
                stepAttributions = listOf(
                    ProblemStepKnowledgeAttribution(
                        stepOrdinal = 1,
                        stepSummaryMarkdown = "找出顶点与区间端点并比较函数值。",
                        atomicReferenceIds = listOf("atom-1"),
                    ),
                ),
            ),
            modelVersion = "model-v1",
        )
        return ModelTaskSnapshot(
            taskId = "task-${request.requestId}",
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "整理完成",
            attemptCount = 1,
            provider = provider,
            output = output,
            failure = null,
            createdAtEpochMillis = request.occurredAtEpochMillis,
            updatedAtEpochMillis = maxOf(
                request.occurredAtEpochMillis,
                request.egressManifest?.approvedAtEpochMillis ?: request.occurredAtEpochMillis,
            ),
        )
    }

    private fun successfulModelTasks(): ModelTaskRepository = object : ModelTaskRepository {
        override suspend fun capabilities() = PROVIDER

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            flowOf(successfulSnapshot(request))
    }

    private object FakeModelTasks : ModelTaskRepository {
        override suspend fun capabilities() = PROVIDER

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> = flowOf(emptyList())

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            error("The consent test must not execute a model request")
    }

    private object StrictDurableModelTasks : ModelTaskRepository {
        override suspend fun capabilities() = PROVIDER

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun observeBySubject(
            subjectId: String,
            kind: ModelTaskKind,
        ): Flow<List<ModelTaskSnapshot>> =
            error("A failed durable lookup must not inspect legacy organization tasks")

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> =
            error("A failed durable lookup must not execute a legacy organization request")
    }

    private companion object {
        val KEY = MistakeRevisionKey("entry-1", "problem-1", "revision-1")
        val PROVIDER = ProviderCapabilitySnapshot(
            providerId = "provider",
            providerDisplayName = "测试模型",
            modelId = "model-v1",
            supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            providerConfigurationVersion = "config-v1",
        )

        fun document(id: String, text: String) = QuestionDocument(
            id = id,
            blocks = listOf(ContentBlock.Paragraph("$id-block", text)),
        )
    }
}
