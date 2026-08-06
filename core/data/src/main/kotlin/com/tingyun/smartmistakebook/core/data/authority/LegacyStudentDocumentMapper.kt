package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationRecord
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.CommitStudentProblemCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.SetStudentProblemCollectionCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryState
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeImportSemanticSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationRecord
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemImageReference
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentPracticeUnitKind
import java.net.URI
import kotlinx.coroutines.CancellationException

/**
 * The single legacy-document conversion used by both bulk preparation and live mirror writes.
 */
internal class LegacyStudentDocumentMapper(
    private val learnerId: String,
    private val assetUriResolver: LegacyStudentAssetUriResolver,
) {
    init {
        require(learnerId.isNotBlank())
    }

    fun map(source: LegacyStudentDocumentMigrationRecord): StudentMistakeMigrationRecord =
        try {
            source.requireLosslessTargetShape()
            val subject = SubjectKind.valueOf(source.subject)
            require(subject != SubjectKind.GENERAL)
            val problem =
                StudentProblemRef(
                    learnerId = learnerId,
                    subject = subject,
                    problemId = source.problemId,
                    practiceUnitId = source.practiceUnitId,
                )
            val captured =
                source.questionDocumentSnapshot?.let { snapshot ->
                    CapturedQuestionDocumentCodec.decode(snapshot).also { document ->
                        require(
                            CapturedQuestionDocumentValidator
                                .validateForCommit(document)
                                .isEmpty(),
                        )
                        require(
                            CapturedQuestionDocumentFingerprint.of(document) ==
                                source.contentFingerprint,
                        )
                    }
                }
            val revision =
                StudentProblemRevisionRef(
                    problem = problem,
                    revisionId = source.revisionId,
                    revisionNumber = source.revisionNumber,
                    documentCanonicalFingerprint = source.contentFingerprint,
                )
            val practiceUnitKind = StudentPracticeUnitKind.valueOf(source.practiceUnitKind)
            val orderedQuestionAssets = source.orderedQuestionAssets()
            val images =
                orderedQuestionAssets
                    .mapIndexed { index, sourceAsset ->
                        mapStudentImage(
                            revisionId = source.revisionId,
                            sourceAsset = sourceAsset.sourceAsset,
                            ordinal = index,
                        )
                    }
            val commit =
                CommitStudentProblemCommand(
                    revision = revision,
                    title = source.title,
                    stemMarkdown = source.problemMarkdown,
                    practiceUnitKind = practiceUnitKind,
                    practiceUnitTitle = source.practiceUnitTitle,
                    itemFamilyId = source.problemId,
                    estimatedDurationSeconds = source.estimatedSeconds,
                    sourceBundleId = null,
                    partIds =
                        when (practiceUnitKind) {
                            StudentPracticeUnitKind.WHOLE_PROBLEM -> emptyList()
                            StudentPracticeUnitKind.PROBLEM_PART,
                            StudentPracticeUnitKind.SHARED_STIMULUS_GROUP,
                            -> listOf(source.practiceUnitKey)
                        },
                    originalImages = images,
                    committedAtEpochMillis = source.committedAtEpochMillis,
                    errorBookEntryId = source.entryId,
                    capturedQuestionDocument = captured,
                )
                StudentMistakeMigrationRecord(
                    problem = commit,
                collection =
                    SetStudentProblemCollectionCommand(
                        problem = problem,
                        mistakeState = StudentMistakeEntryState.valueOf(source.status),
                        favorite = false,
                        changedAtEpochMillis =
                            maxOf(
                                source.updatedAtEpochMillis,
                                source.committedAtEpochMillis,
                            ),
                    ),
                    importSemanticSnapshot =
                        StudentMistakeImportSemanticSnapshot(
                            problemCanonicalFingerprint =
                                source.problemCanonicalFingerprint,
                            revisionSourceType = source.revisionSourceType,
                            revisionSourceReference = source.revisionSourceReference,
                            answerSpecId = source.answerSpecId,
                            answerSpecSnapshot = source.answerSpecSnapshot,
                            answerVerificationStatus =
                                source.answerVerificationStatus,
                            errorBookSourceKey = source.sourceKey,
                            practiceUnitKey = source.practiceUnitKey,
                            practiceUnitPromptMarkdown =
                                source.practiceUnitPromptMarkdown,
                        ),
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: LegacyStudentRecordRejected) {
            throw rejected
        } catch (_: Exception) {
            throw LegacyStudentRecordRejected(
                code = LegacyAuthorityPreparationBlockerCode.STUDENT_DOCUMENT_INVALID,
                sourceReferenceId = source.revisionId,
            )
        }

    private fun mapStudentImage(
        revisionId: String,
        sourceAsset: CanonicalSourceAssetRecord,
        ordinal: Int,
    ): StudentProblemImageReference =
        try {
            val localUri = assetUriResolver.resolve(sourceAsset)
            require(URI(localUri).scheme == "file")
            StudentProblemImageReference(
                imageReferenceId =
                    CanonicalSha256("legacy-student-image-reference-v1")
                        .field("revisionId", revisionId)
                        .field("sourceAssetId", sourceAsset.sourceAssetId)
                        .finish(),
                localContentUri = localUri,
                contentCanonicalFingerprint = sourceAsset.contentSha256,
                mediaType = sourceAsset.mimeType,
                ordinal = ordinal,
                widthPixels = sourceAsset.width,
                heightPixels = sourceAsset.height,
                byteSize = sourceAsset.byteSize,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw LegacyStudentRecordRejected(
                code =
                    LegacyAuthorityPreparationBlockerCode
                        .STUDENT_SOURCE_ASSET_UNAVAILABLE,
                sourceReferenceId = revisionId,
            )
        }
}

private fun LegacyStudentDocumentMigrationRecord.requireLosslessTargetShape() {
    require(problemArchivedAtEpochMillis == null) {
        "An archived legacy problem requires an explicit target lifecycle snapshot"
    }
    require(problemCreatedAtEpochMillis == committedAtEpochMillis) {
        "Legacy problem creation time cannot be folded into a different commit time"
    }
    require(revisionCreatedAtEpochMillis == committedAtEpochMillis) {
        "Legacy revision creation time cannot be folded into a different commit time"
    }
    require(practiceUnitCreatedAtEpochMillis == committedAtEpochMillis) {
        "Legacy practice-unit creation time cannot be folded into a different commit time"
    }
    require(acceptedAtEpochMillis == committedAtEpochMillis) {
        "Legacy collection acceptance time cannot be folded into a different commit time"
    }
    require(practiceUnitRevisionId == revisionId) {
        "Legacy practice unit does not target the migrated revision"
    }
    require(entryCurrentRevisionId == revisionId) {
        "Legacy collection state does not target the migrated revision"
    }
}

private fun LegacyStudentDocumentMigrationRecord.orderedQuestionAssets() =
    sourceAssets
        .also { assets ->
            require(
                assets.all { linked ->
                    linked.role == LEGACY_QUESTION_SOURCE_ROLE
                },
            ) {
                "Only original question pages may enter the student-mistake image list"
            }
            require(
                assets.map { linked -> linked.sourceAsset.sourceAssetId }.distinct().size ==
                    assets.size,
            ) {
                "Legacy question pages must be unique"
            }
            if (assets.size > 1) {
                require(assets.all { linked -> linked.pageIndex != null }) {
                    "A multi-page legacy problem requires explicit page order"
                }
            }
        }
        .sortedBy { linked -> linked.pageIndex ?: 0 }
        .also { ordered ->
            if (ordered.isNotEmpty()) {
                require(
                    ordered.mapIndexed { index, linked -> linked.pageIndex ?: index } ==
                        ordered.indices.toList(),
                ) {
                    "Legacy question-page order must be zero-based and contiguous"
                }
            }
        }

internal class LegacyStudentRecordRejected(
    val code: LegacyAuthorityPreparationBlockerCode,
    val sourceReferenceId: String,
) : IllegalArgumentException()

private const val LEGACY_QUESTION_SOURCE_ROLE = "QUESTION_SOURCE"
