package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * The immutable business rows created for one imported revision.
 *
 * Mutable aggregate projections such as collection state, the current-revision pointer, search
 * indexing state, and relay delivery state are deliberately excluded: valid post-cutover student
 * activity may change them. Stable problem ownership, revision content, source images, and revision
 * authority are immutable.
 */
internal data class StudentMistakeImmutableRevisionSnapshot(
    val problem: StudentProblemDocumentEntity,
    val revision: StudentProblemRevisionEntity,
    val importSnapshot: StudentProblemImportSemanticSnapshotEntity,
    val images: List<StudentProblemImageReferenceEntity>,
    val solutionAnalysis: StudentProblemSolutionAnalysisEntity?,
    val solutionSteps: List<StudentProblemSolutionStepEntity>,
    val errorAttributions: List<StudentProblemErrorAttributionEntity>,
    val errorEvidence: List<StudentProblemErrorEvidenceEntity>,
)

internal fun CommitStudentProblemBundle.toImmutableRevisionSnapshot(
    importSnapshot: StudentProblemImportSemanticSnapshotEntity,
):
    StudentMistakeImmutableRevisionSnapshot =
    StudentMistakeImmutableRevisionSnapshot(
        problem = problem,
        revision = revision,
        importSnapshot = importSnapshot,
        images = images,
        solutionAnalysis = solutionAnalysis,
        solutionSteps = solutionSteps,
        errorAttributions = errorAttributions,
        errorEvidence = errorEvidence,
    )

internal fun StudentMistakeImmutableRevisionSnapshot.canonicalFingerprint(): String =
    canonicalFingerprint(IMMUTABLE_MIGRATION_DESTINATION_RECORD_V3_DOMAIN)

/**
 * The v2 policy can still be replayed exactly only when it contained no error attribution.
 * Attribution confidence was part of the old canonical bytes and was intentionally not retained.
 */
internal fun StudentMistakeImmutableRevisionSnapshot.canonicalFingerprintV2WithoutAttributions():
    String {
    check(errorAttributions.isEmpty()) {
        "A v2 destination with error attribution requires independent reattestation"
    }
    check(errorEvidence.isEmpty()) {
        "A v2 destination without attribution cannot contain attribution evidence"
    }
    return canonicalFingerprint(IMMUTABLE_MIGRATION_DESTINATION_RECORD_V2_DOMAIN)
}

private fun StudentMistakeImmutableRevisionSnapshot.canonicalFingerprint(domain: String): String {
    val revisionId = revision.revisionId
    check(problem.problemId == revision.problemId) {
        "Migration destination revision belongs to another problem"
    }
    importSnapshot.requireCanonicalShape()
    check(
        importSnapshot.revisionId == revision.revisionId &&
            importSnapshot.problemId == problem.problemId &&
            importSnapshot.practiceUnitId == problem.primaryPracticeUnitId,
    ) {
        "Migration import snapshot belongs to another revision"
    }
    check(images.all { it.revisionId == revisionId }) {
        "Migration destination images belong to another revision"
    }
    check(solutionAnalysis == null || solutionAnalysis.basisRevisionId == revisionId) {
        "Migration destination solution belongs to another revision"
    }
    check(solutionSteps.all { it.basisRevisionId == revisionId }) {
        "Migration destination solution steps belong to another revision"
    }
    check(errorAttributions.all { it.basisRevisionId == revisionId }) {
        "Migration destination attributions belong to another revision"
    }
    check(errorEvidence.all { it.basisRevisionId == revisionId }) {
        "Migration destination evidence belongs to another revision"
    }

    val orderedImages =
        images.sortedWith(
            compareBy(
                StudentProblemImageReferenceEntity::ordinal,
                StudentProblemImageReferenceEntity::imageReferenceId,
            ),
        )
    val orderedSteps =
        solutionSteps.sortedWith(
            compareBy(
                StudentProblemSolutionStepEntity::ordinal,
                StudentProblemSolutionStepEntity::stepId,
            ),
        )
    val orderedAttributions =
        errorAttributions.sortedWith(
            compareBy(
                StudentProblemErrorAttributionEntity::recordedAtEpochMillis,
                StudentProblemErrorAttributionEntity::attributionId,
            ),
        )
    val orderedEvidence =
        errorEvidence.sortedWith(
            compareBy(
                StudentProblemErrorEvidenceEntity::attributionId,
                StudentProblemErrorEvidenceEntity::ordinal,
            ),
        )

    val digest =
        CanonicalSha256(domain)
            .field("problem.problemId", problem.problemId)
            .field("problem.learnerId", problem.learnerId)
            .field("problem.subject", problem.subject)
            .field(
                "problem.primaryPracticeUnitId",
                problem.primaryPracticeUnitId,
            )
            .field("revisionId", revision.revisionId)
            .field("problemId", revision.problemId)
            .field("revisionNumber", revision.revisionNumber)
            .nullableField("title", revision.title)
            .field("stemMarkdown", revision.stemMarkdown)
            .nullableField(
                "capturedQuestionDocumentWire",
                revision.capturedQuestionDocumentWire,
            )
            .field(
                "documentCanonicalFingerprint",
                revision.documentCanonicalFingerprint,
            )
            .field("createdAtEpochMillis", revision.createdAtEpochMillis)
            .field("updatedAtEpochMillis", revision.updatedAtEpochMillis)
            .field(
                "importSnapshotCanonicalFingerprint",
                importSnapshot.snapshotCanonicalFingerprint,
            )
            .field("imageCount", orderedImages.size)
    orderedImages.forEachIndexed { index, image ->
        digest
            .field("image[$index].imageReferenceId", image.imageReferenceId)
            .field("image[$index].revisionId", image.revisionId)
            .field("image[$index].localContentUri", image.localContentUri)
            .field(
                "image[$index].contentCanonicalFingerprint",
                image.contentCanonicalFingerprint,
            )
            .field("image[$index].mediaType", image.mediaType)
            .field("image[$index].ordinal", image.ordinal)
            .nullableField("image[$index].widthPixels", image.widthPixels?.toString())
            .nullableField("image[$index].heightPixels", image.heightPixels?.toString())
            .nullableField("image[$index].byteSize", image.byteSize?.toString())
            .field("image[$index].selectedRegionsWire", image.selectedRegionsWire)
            .field("image[$index].createdAtEpochMillis", image.createdAtEpochMillis)
    }

    val analysis = solutionAnalysis
    digest.field("solutionAnalysisPresent", analysis != null)
    if (analysis != null) {
        digest
            .field("solutionAnalysis.solutionAnalysisId", analysis.solutionAnalysisId)
            .field("solutionAnalysis.basisRevisionId", analysis.basisRevisionId)
            .field("solutionAnalysis.summaryMarkdown", analysis.summaryMarkdown)
            .nullableField(
                "solutionAnalysis.finalAnswerMarkdown",
                analysis.finalAnswerMarkdown,
            )
            .field("solutionAnalysis.modelProviderId", analysis.modelProviderId)
            .field("solutionAnalysis.modelId", analysis.modelId)
            .field("solutionAnalysis.analyzerVersion", analysis.analyzerVersion)
            .field(
                "solutionAnalysis.resultCanonicalFingerprint",
                analysis.resultCanonicalFingerprint,
            )
            .field(
                "solutionAnalysis.recordedAtEpochMillis",
                analysis.recordedAtEpochMillis,
            )
    }

    digest.field("solutionStepCount", orderedSteps.size)
    orderedSteps.forEachIndexed { index, step ->
        digest
            .field("solutionStep[$index].solutionAnalysisId", step.solutionAnalysisId)
            .field("solutionStep[$index].basisRevisionId", step.basisRevisionId)
            .field("solutionStep[$index].stepId", step.stepId)
            .field("solutionStep[$index].ordinal", step.ordinal)
            .field("solutionStep[$index].summaryMarkdown", step.summaryMarkdown)
            .field("solutionStep[$index].reasoningMarkdown", step.reasoningMarkdown)
            .nullableField("solutionStep[$index].resultMarkdown", step.resultMarkdown)
            .field(
                "solutionStep[$index].stepCanonicalFingerprint",
                step.stepCanonicalFingerprint,
            )
    }

    digest.field("errorAttributionCount", orderedAttributions.size)
    orderedAttributions.forEachIndexed { index, attribution ->
        digest
            .field("errorAttribution[$index].attributionId", attribution.attributionId)
            .field(
                "errorAttribution[$index].basisRevisionId",
                attribution.basisRevisionId,
            )
            .nullableField(
                "errorAttribution[$index].solutionAnalysisId",
                attribution.solutionAnalysisId,
            )
            .field(
                "errorAttribution[$index].resolutionStatus",
                attribution.resolutionStatus,
            )
            .field(
                "errorAttribution[$index].rationaleMarkdown",
                attribution.rationaleMarkdown,
            )
            .nullableField(
                "errorAttribution[$index].stepOrdinal",
                attribution.stepOrdinal?.toString(),
            )
            .nullableField(
                "errorAttribution[$index].atomicReferenceId",
                attribution.atomicReferenceId,
            )
            .field(
                "errorAttribution[$index].modelProviderId",
                attribution.modelProviderId,
            )
            .field("errorAttribution[$index].modelId", attribution.modelId)
            .field(
                "errorAttribution[$index].analyzerVersion",
                attribution.analyzerVersion,
            )
            .field(
                "errorAttribution[$index].resultCanonicalFingerprint",
                attribution.resultCanonicalFingerprint,
            )
            .field(
                "errorAttribution[$index].recordedAtEpochMillis",
                attribution.recordedAtEpochMillis,
            )
    }

    digest.field("errorEvidenceCount", orderedEvidence.size)
    orderedEvidence.forEachIndexed { index, evidence ->
        digest
            .field("errorEvidence[$index].attributionId", evidence.attributionId)
            .field("errorEvidence[$index].basisRevisionId", evidence.basisRevisionId)
            .field("errorEvidence[$index].ordinal", evidence.ordinal)
            .field("errorEvidence[$index].blockId", evidence.blockId)
            .field("errorEvidence[$index].sourceAssetId", evidence.sourceAssetId)
            .field("errorEvidence[$index].evidenceKind", evidence.evidenceKind)
    }
    return digest.finish()
}

private const val IMMUTABLE_MIGRATION_DESTINATION_RECORD_V2_DOMAIN =
    "student-mistake-migration-destination-record-v2"
private const val IMMUTABLE_MIGRATION_DESTINATION_RECORD_V3_DOMAIN =
    "student-mistake-migration-destination-record-v3"
