package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.IncrementalLearningEvent
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningLedgerEvent
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import com.tingyun.smartmistakebook.core.model.TutorMoveType

/**
 * 整卷切分（split-import）跨 [StudyDatabasePort] 的命令与记录。
 *
 * 从 `StudyDatabaseRecords.kt` 拆出（审计 R-01）：同包，所以引用它们的地方一个 import 都不用改。
 */
data class CreateSplitImportJobCommand(
    val jobId: String,
    val sourceKind: String,
    val sourceFingerprint: String,
    val sourceUri: String,
    val pageCount: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        require(jobId.isNotBlank()) { "Split job id must not be blank" }
        require(sourceKind.isNotBlank()) { "Split job source kind must not be blank" }
        require(sourceFingerprint.isNotEmpty()) { "Split job source fingerprint must not be blank" }
        require(sourceUri.isNotBlank()) { "Split job source image uri must not be blank" }
        require(pageCount >= 1) { "Split job page count must be positive" }
        require(createdAtEpochMillis >= 0) { "Split job creation time must not be negative" }
    }
}
data class SplitImportQuestionSeed(
    private val left: Double,
    private val top: Double,
    private val right: Double,
    private val bottom: Double,
    val pageIndex: Int,
    val prioritised: Boolean = false,
) {
    init {
        require(pageIndex >= 0) { "Split question page index must not be negative" }
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Split region must be finite"
        }
        require(left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0) {
            "Split region must stay inside the normalized page"
        }
        require(left < right && top < bottom) { "Split region must have positive extent" }
    }

    fun regionLeft(): Double = left

    fun regionTop(): Double = top

    fun regionRight(): Double = right

    fun regionBottom(): Double = bottom
}
data class SplitImportQuestionRecord(
    val jobId: String,
    val questionOrdinal: Int,
    val pageIndex: Int,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val selected: Boolean,
    val confirmState: String,
    val splitDraftId: String?,
)
data class SplitImportJobRecord(
    val jobId: String,
    val sourceKind: String,
    val sourceFingerprint: String,
    val sourceUri: String,
    val pageCount: Int,
    val questionCount: Int,
    val status: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val questions: List<SplitImportQuestionRecord> = emptyList(),
) {
    init {
        require(jobId.isNotBlank()) { "Split job id must not be blank" }
        require(sourceKind.isNotBlank()) { "Split job source kind must not be blank" }
        require(sourceUri.isNotBlank()) { "Split job source image uri must not be blank" }
        require(pageCount >= 1) { "Split job page count must be positive" }
        require(questionCount >= 0) { "Split job question count must not be negative" }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Split job times are invalid"
        }
    }

    val readyForReview: Boolean get() = status == StudyDbValue.SplitImportStatus.READY ||
        status == StudyDbValue.SplitImportStatus.PREPARING
}
