package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * Optional source semantics that have no equivalent in the normal student-mistake write model.
 *
 * This type is accepted only by the migration port. In particular, [errorBookSourceKey] is not a
 * source-bundle id and is never copied into that field.
 */
data class StudentMistakeImportSemanticSnapshot(
    val problemCanonicalFingerprint: String,
    val revisionSourceType: String,
    val revisionSourceReference: String?,
    val answerSpecId: String?,
    val answerSpecSnapshot: String?,
    val answerVerificationStatus: String,
    val errorBookSourceKey: String?,
    val practiceUnitKey: String,
    val practiceUnitPromptMarkdown: String,
) {
    init {
        requireSha256(problemCanonicalFingerprint, "Imported problem fingerprint")
        revisionSourceType.requireStoreText("Imported revision source type", MAX_ID_CHARS)
        revisionSourceReference?.requireStoreText(
            "Imported revision source reference",
            MAX_URI_CHARS,
        )
        answerSpecId?.requireStoreText("Imported answer-spec id", MAX_ID_CHARS)
        require(answerSpecSnapshot == null || answerSpecSnapshot.isNotBlank()) {
            "Imported answer-spec snapshot must not be blank"
        }
        answerSpecSnapshot?.requireStoreText(
            "Imported answer-spec snapshot",
            MAX_DOCUMENT_CHARS,
        )
        answerVerificationStatus.requireStoreText(
            "Imported answer verification status",
            MAX_ID_CHARS,
        )
        errorBookSourceKey?.requireStoreText(
            "Imported error-book source key",
            MAX_URI_CHARS,
        )
        practiceUnitKey.requireStoreText("Imported practice-unit key", MAX_ID_CHARS)
        practiceUnitPromptMarkdown.requireStoreText(
            "Imported practice-unit prompt",
            MAX_DOCUMENT_CHARS,
        )
    }
}

/**
 * Append-only snapshot of the exact target practice unit plus optional legacy-only semantics.
 *
 * The live practice-unit row is intentionally not part of terminal migration evidence because
 * committing a later revision may update it. This row remains bound to the imported revision.
 */
@Entity(
    tableName = STUDENT_PROBLEM_IMPORT_SEMANTIC_SNAPSHOT_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["problem_id"]),
        Index(value = ["practice_unit_id"]),
        Index(value = ["legacy_problem_canonical_fingerprint"]),
        Index(value = ["legacy_error_book_source_key"]),
    ],
    primaryKeys = ["revision_id"],
)
internal data class StudentProblemImportSemanticSnapshotEntity(
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "target_unit_kind")
    val targetUnitKind: String,
    @ColumnInfo(name = "target_title")
    val targetTitle: String,
    @ColumnInfo(name = "target_item_family_id")
    val targetItemFamilyId: String,
    @ColumnInfo(name = "target_estimated_duration_seconds")
    val targetEstimatedDurationSeconds: Int,
    @ColumnInfo(name = "target_source_bundle_id")
    val targetSourceBundleId: String?,
    @ColumnInfo(name = "target_part_ids_wire")
    val targetPartIdsWire: String,
    @ColumnInfo(name = "target_error_book_entry_id")
    val targetErrorBookEntryId: String?,
    @ColumnInfo(name = "legacy_semantics_present")
    val legacySemanticsPresent: Boolean,
    @ColumnInfo(name = "legacy_problem_canonical_fingerprint")
    val legacyProblemCanonicalFingerprint: String?,
    @ColumnInfo(name = "legacy_revision_source_type")
    val legacyRevisionSourceType: String?,
    @ColumnInfo(name = "legacy_revision_source_reference")
    val legacyRevisionSourceReference: String?,
    @ColumnInfo(name = "legacy_answer_spec_id")
    val legacyAnswerSpecId: String?,
    @ColumnInfo(name = "legacy_answer_spec_snapshot")
    val legacyAnswerSpecSnapshot: String?,
    @ColumnInfo(name = "legacy_answer_verification_status")
    val legacyAnswerVerificationStatus: String?,
    @ColumnInfo(name = "legacy_error_book_source_key")
    val legacyErrorBookSourceKey: String?,
    @ColumnInfo(name = "legacy_practice_unit_key")
    val legacyPracticeUnitKey: String?,
    @ColumnInfo(name = "legacy_practice_unit_prompt_markdown")
    val legacyPracticeUnitPromptMarkdown: String?,
    @ColumnInfo(name = "snapshot_canonical_fingerprint")
    val snapshotCanonicalFingerprint: String,
)

internal fun createStudentProblemImportSemanticSnapshot(
    bundle: CommitStudentProblemBundle,
    semantics: StudentMistakeImportSemanticSnapshot?,
): StudentProblemImportSemanticSnapshotEntity {
    val practiceUnit = bundle.practiceUnit
    val entity =
        StudentProblemImportSemanticSnapshotEntity(
            revisionId = bundle.revision.revisionId,
            problemId = bundle.problem.problemId,
            practiceUnitId = practiceUnit.practiceUnitId,
            targetUnitKind = practiceUnit.unitKind,
            targetTitle = practiceUnit.title,
            targetItemFamilyId = practiceUnit.itemFamilyId,
            targetEstimatedDurationSeconds = practiceUnit.estimatedDurationSeconds,
            targetSourceBundleId = practiceUnit.sourceBundleId,
            targetPartIdsWire = practiceUnit.partIdsWire,
            targetErrorBookEntryId = bundle.problem.errorBookEntryId,
            legacySemanticsPresent = semantics != null,
            legacyProblemCanonicalFingerprint = semantics?.problemCanonicalFingerprint,
            legacyRevisionSourceType = semantics?.revisionSourceType,
            legacyRevisionSourceReference = semantics?.revisionSourceReference,
            legacyAnswerSpecId = semantics?.answerSpecId,
            legacyAnswerSpecSnapshot = semantics?.answerSpecSnapshot,
            legacyAnswerVerificationStatus = semantics?.answerVerificationStatus,
            legacyErrorBookSourceKey = semantics?.errorBookSourceKey,
            legacyPracticeUnitKey = semantics?.practiceUnitKey,
            legacyPracticeUnitPromptMarkdown = semantics?.practiceUnitPromptMarkdown,
            snapshotCanonicalFingerprint = "",
        )
    return entity.copy(snapshotCanonicalFingerprint = entity.computeCanonicalFingerprint())
}

internal fun StudentProblemImportSemanticSnapshotEntity.requireCanonicalShape() {
    revisionId.requireStoreText("Import snapshot revision id", MAX_ID_CHARS)
    problemId.requireStoreText("Import snapshot problem id", MAX_ID_CHARS)
    practiceUnitId.requireStoreText("Import snapshot practice-unit id", MAX_ID_CHARS)
    targetUnitKind.requireStoreText("Import snapshot unit kind", MAX_ID_CHARS)
    targetTitle.requireStoreText("Import snapshot title", MAX_LABEL_CHARS)
    targetItemFamilyId.requireStoreText("Import snapshot item-family id", MAX_ID_CHARS)
    check(targetEstimatedDurationSeconds in 1..MAX_ESTIMATED_DURATION_SECONDS) {
        "Import snapshot duration is outside the supported range"
    }
    targetSourceBundleId?.requireStoreText("Import snapshot source-bundle id", MAX_ID_CHARS)
    targetErrorBookEntryId?.requireStoreText(
        "Import snapshot error-book entry id",
        MAX_ID_CHARS,
    )
    if (legacySemanticsPresent) {
        requireSha256(
            checkNotNull(legacyProblemCanonicalFingerprint),
            "Imported problem fingerprint",
        )
        checkNotNull(legacyRevisionSourceType)
            .requireStoreText("Imported revision source type", MAX_ID_CHARS)
        legacyRevisionSourceReference?.requireStoreText(
            "Imported revision source reference",
            MAX_URI_CHARS,
        )
        legacyAnswerSpecId?.requireStoreText("Imported answer-spec id", MAX_ID_CHARS)
        check(legacyAnswerSpecSnapshot == null || legacyAnswerSpecSnapshot.isNotBlank()) {
            "Imported answer-spec snapshot must not be blank"
        }
        legacyAnswerSpecSnapshot?.requireStoreText(
            "Imported answer-spec snapshot",
            MAX_DOCUMENT_CHARS,
        )
        checkNotNull(legacyAnswerVerificationStatus).requireStoreText(
            "Imported answer verification status",
            MAX_ID_CHARS,
        )
        legacyErrorBookSourceKey?.requireStoreText(
            "Imported error-book source key",
            MAX_URI_CHARS,
        )
        checkNotNull(legacyPracticeUnitKey)
            .requireStoreText("Imported practice-unit key", MAX_ID_CHARS)
        checkNotNull(legacyPracticeUnitPromptMarkdown).requireStoreText(
            "Imported practice-unit prompt",
            MAX_DOCUMENT_CHARS,
        )
    } else {
        check(
            legacyProblemCanonicalFingerprint == null &&
                legacyRevisionSourceType == null &&
                legacyRevisionSourceReference == null &&
                legacyAnswerSpecId == null &&
                legacyAnswerSpecSnapshot == null &&
                legacyAnswerVerificationStatus == null &&
                legacyErrorBookSourceKey == null &&
                legacyPracticeUnitKey == null &&
                legacyPracticeUnitPromptMarkdown == null,
        ) {
            "Import snapshot marks legacy semantics absent but retains legacy values"
        }
    }
    requireSha256(snapshotCanonicalFingerprint, "Import snapshot fingerprint")
    check(snapshotCanonicalFingerprint == computeCanonicalFingerprint()) {
        "Import snapshot fingerprint is invalid"
    }
}

internal fun StudentProblemImportSemanticSnapshotEntity.computeCanonicalFingerprint(): String =
    CanonicalSha256(IMPORT_SEMANTIC_SNAPSHOT_FINGERPRINT_DOMAIN)
        .field("revisionId", revisionId)
        .field("problemId", problemId)
        .field("practiceUnitId", practiceUnitId)
        .field("targetUnitKind", targetUnitKind)
        .field("targetTitle", targetTitle)
        .field("targetItemFamilyId", targetItemFamilyId)
        .field("targetEstimatedDurationSeconds", targetEstimatedDurationSeconds)
        .nullableField("targetSourceBundleId", targetSourceBundleId)
        .field("targetPartIdsWire", targetPartIdsWire)
        .nullableField("targetErrorBookEntryId", targetErrorBookEntryId)
        .field("legacySemanticsPresent", legacySemanticsPresent)
        .nullableField(
            "legacyProblemCanonicalFingerprint",
            legacyProblemCanonicalFingerprint,
        )
        .nullableField("legacyRevisionSourceType", legacyRevisionSourceType)
        .nullableField("legacyRevisionSourceReference", legacyRevisionSourceReference)
        .nullableField("legacyAnswerSpecId", legacyAnswerSpecId)
        .nullableField("legacyAnswerSpecSnapshot", legacyAnswerSpecSnapshot)
        .nullableField(
            "legacyAnswerVerificationStatus",
            legacyAnswerVerificationStatus,
        )
        .nullableField("legacyErrorBookSourceKey", legacyErrorBookSourceKey)
        .nullableField("legacyPracticeUnitKey", legacyPracticeUnitKey)
        .nullableField(
            "legacyPracticeUnitPromptMarkdown",
            legacyPracticeUnitPromptMarkdown,
        )
        .finish()

internal const val STUDENT_PROBLEM_IMPORT_SEMANTIC_SNAPSHOT_TABLE =
    "student_problem_import_semantic_snapshot"
private const val IMPORT_SEMANTIC_SNAPSHOT_FINGERPRINT_DOMAIN =
    "student-problem-import-semantic-snapshot-v1"
