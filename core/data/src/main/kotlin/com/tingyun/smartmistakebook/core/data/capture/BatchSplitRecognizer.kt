package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.database.CreateSplitImportJobCommand
import com.tingyun.smartmistakebook.core.database.SplitImportQuestionSeed
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.last

internal suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectLast(): T = last()

/**
 * Runs a CaptureAssessment against one staged batch page and, when the model
 * answers SPLIT with page-local question regions, records the cut into the
 * split-import ledger so the student can confirm which pieces to keep.
 *
 * The page stays IMPORTING while the model works; the assessment persists in
 * the model-task ledger, so a killed process resumes by re-running the same
 * request fingerprint instead of starting over.
 */
internal suspend fun recognizeAndSplitBatchPage(
    jobId: String,
    pageIndex: Int,
    sourceUri: String,
    capturedWidth: Int,
    capturedHeight: Int,
    database: StudyDatabasePort,
    modelTasks: ModelTaskRepository,
    splitImports: com.tingyun.smartmistakebook.core.data.splitimport.RoomSplitImportRepository,
    occurrenceTime: Long,
): BatchSplitOutcome {
    val provider = modelTasks.capabilities()
    require(
        provider.executionLocation != ModelExecutionLocation.UNAVAILABLE,
    ) { "Model provider is unavailable for batch split recognition" }

    val subjectId = stableBatchPageSubject(jobId, pageIndex)
    val request = ModelTaskRequest(
        requestId = "batch-split:$jobId:$pageIndex",
        input = com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput(
            draftId = subjectId,
            sourceAssetId = "batch-image:$jobId:$pageIndex",
            origin = com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin.LIBRARY,
            imageWidth = capturedWidth,
            imageHeight = capturedHeight,
        ),
        occurredAtEpochMillis = occurrenceTime,
        egressManifest = batchSplitEgressManifest(
            provider = provider,
            jobId = jobId,
            pageIndex = pageIndex,
            sourceUri = sourceUri,
        ),
    )
    val snapshot = modelTasks.execute(request).collectLast()
    if (snapshot.status != ModelTaskStatus.SUCCEEDED) {
        return BatchSplitOutcome.Failed
    }
    val assessment = (snapshot.output as? CaptureAssessmentOutput)?.assessment ?: return BatchSplitOutcome.Failed
    if (!assessment.questionRegions.isUsableSplit()) {
        return BatchSplitOutcome.NotASplit
    }
    val job = splitImports.createSplitJob(
        CreateSplitImportJobCommand(
            jobId = "split:$jobId:$pageIndex",
            sourceKind = StudyDbValue.SplitImportSourceKind.BATCH,
            sourceFingerprint = batchSplitFingerprint(jobId, pageIndex, sourceUri),
            sourceUri = sourceUri,
            pageCount = 1,
            createdAtEpochMillis = occurrenceTime,
        ),
        questions = assessment.questionRegions.map { region ->
            SplitImportQuestionSeed(
                left = region.left,
                top = region.top,
                right = region.right,
                bottom = region.bottom,
                pageIndex = 0,
                prioritised = true,
            )
        },
    )
    splitImports.markReady(job.jobId, job.questions.size, occurrenceTime)
    return BatchSplitOutcome.SplitReady(job.jobId)
}

private fun List<com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion>.isUsableSplit(): Boolean =
    size in 2..12 && all { it.left < it.right && it.top < it.bottom }

private fun stableBatchPageSubject(jobId: String, pageIndex: Int): String =
    "batch-split-subject:$jobId:$pageIndex"

private fun batchSplitFingerprint(jobId: String, pageIndex: Int, sourceUri: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("batch-split\u001F$jobId\u001F$pageIndex\u001F$sourceUri".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun batchSplitEgressManifest(
    provider: ProviderCapabilitySnapshot,
    jobId: String,
    pageIndex: Int,
    sourceUri: String,
): ModelEgressManifest? {
    if (provider.executionLocation != ModelExecutionLocation.EXTERNAL_PROVIDER) return null
    return ModelEgressManifest(
        authorizationId = "batch-split-egress:$jobId:$pageIndex",
        subjectId = stableBatchPageSubject(jobId, pageIndex),
        purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
        authorizedTaskKinds = setOf(ModelTaskKind.CAPTURE_ASSESS),
        providerId = provider.providerId,
        modelId = provider.modelId,
        providerConfigurationVersion = provider.providerConfigurationVersion,
        promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
        approvedAtEpochMillis = -1L,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = "batch-image:$jobId:$pageIndex",
                sha256 = batchSplitFingerprint(jobId, pageIndex, sourceUri),
                byteSize = 0L,
                width = 0,
                height = 0,
            ),
        ),
        disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
        prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
    )
}

internal sealed interface BatchSplitOutcome {
    data object Failed : BatchSplitOutcome
    data object NotASplit : BatchSplitOutcome
    data class SplitReady(val jobId: String) : BatchSplitOutcome
}