package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.database.CreateSplitImportJobCommand
import com.tingyun.smartmistakebook.core.database.SplitImportQuestionSeed
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
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
    draft: CaptureDraftSummary,
    database: StudyDatabasePort,
    modelTasks: ModelTaskRepository,
    splitImports: com.tingyun.smartmistakebook.core.data.splitimport.RoomSplitImportRepository,
    occurrenceTime: Long,
    consentEnabled: () -> Boolean,
): BatchSplitOutcome {
    val provider = modelTasks.capabilities()
    if (provider.executionLocation == ModelExecutionLocation.UNAVAILABLE) {
        // Split recognition is an optional enhancement over the plain page import
        // that already succeeded; an unavailable provider degrades, never throws.
        return BatchSplitOutcome.NotASplit
    }
    if (
        provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
        !consentEnabled()
    ) {
        // Without global agent consent an external round would be denied by the
        // egress policy anyway; skipping it keeps the page import clean instead of
        // writing a guaranteed PERMANENT_FAILURE row per batch page.
        return BatchSplitOutcome.NotASplit
    }

    val request = ModelTaskRequest(
        requestId = "batch-split:$jobId:$pageIndex",
        input = com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput(
            draftId = draft.draftId,
            sourceAssetId = draft.sourceAssetId,
            origin = com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin.LIBRARY,
            imageWidth = draft.width,
            imageHeight = draft.height,
        ),
        occurredAtEpochMillis = occurrenceTime,
        // Batch page organization runs under the global agent consent; the page
        // import that produced this draft is the same user action that consented.
        agentConsentGranted = consentEnabled(),
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

private fun batchSplitFingerprint(jobId: String, pageIndex: Int, sourceUri: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("batch-split\u001F$jobId\u001F$pageIndex\u001F$sourceUri".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

internal sealed interface BatchSplitOutcome {
    data object Failed : BatchSplitOutcome
    data object NotASplit : BatchSplitOutcome
    data class SplitReady(val jobId: String) : BatchSplitOutcome
}