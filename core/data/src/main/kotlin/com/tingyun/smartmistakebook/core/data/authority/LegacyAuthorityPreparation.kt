package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.data.session.capture.LegacyRoomCaptureAssetBridge
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSnapshot
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationRecord
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.student.mistake.database.ApplyStudentMistakeMigrationPageCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.MAX_MIGRATION_PAGE_SIZE
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationCheckpoint
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationKey
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationRecord
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRelayCapability
import kotlinx.coroutines.CancellationException

enum class LegacyAuthorityPreparationBlockerCode {
    LEGACY_STUDENT_WRITES_ACTIVE,
    LEGACY_MASTERY_WRITES_ACTIVE,
    STUDENT_PROBLEM_HAS_MULTIPLE_ENTRIES,
    STUDENT_CURRENT_REVISION_CANNOT_BE_PRESERVED,
    STUDENT_DOCUMENT_INVALID,
    STUDENT_SOURCE_ASSET_UNAVAILABLE,
    STUDENT_MIRROR_CHECKPOINT_INVALID,
    STUDENT_DESTINATION_COUNT_MISMATCH,
    STUDENT_RELAY_DID_NOT_QUIESCE,
    MASTERY_SOURCE_PROOF_MISSING,
    MASTERY_BEHAVIOR_METADATA_INSUFFICIENT,
    OPEN_RESPONSE_HAS_NO_EQUIVALENT,
    SOURCE_CHANGED_DURING_PREPARATION,
    VERIFIED_PREFIX_UNAVAILABLE,
    PREPARATION_FAILED,
}

data class LegacyAuthorityPreparationBlocker(
    val code: LegacyAuthorityPreparationBlockerCode,
    val blockedStageName: String,
    val sourceReferenceId: String? = null,
)

/**
 * Startup-readable state for a safe transitional run.
 *
 * Neither route may move to an isolated authority until a separate write-route switch and final
 * verification exist. The knowledge catalog is already a physically isolated, read-only runtime.
 */
data class LegacyAuthorityPreparationResult(
    val durableCutoverStageNames: List<String>,
    val blockedCutoverStageName: String,
    val mirroredStudentRevisionCount: Long,
    val studentMirrorCheckpointFingerprint: String?,
    val pendingMasteryFactCount: Long,
    val blockers: List<LegacyAuthorityPreparationBlocker>,
    val keepLegacyStudentAuthority: Boolean,
    val keepLegacyMasteryAuthority: Boolean,
) {
    init {
        require(durableCutoverStageNames.none(String::isBlank))
        require(blockedCutoverStageName.isNotBlank())
        require(mirroredStudentRevisionCount >= 0L)
        require(pendingMasteryFactCount >= 0L)
        require(
            studentMirrorCheckpointFingerprint == null ||
                studentMirrorCheckpointFingerprint.matches(SHA_256),
        )
        require(keepLegacyStudentAuthority)
        require(keepLegacyMasteryAuthority)
        require(
            blockers.any {
                it.code == LegacyAuthorityPreparationBlockerCode.LEGACY_STUDENT_WRITES_ACTIVE
            },
        )
        require(
            blockers.any {
                it.code == LegacyAuthorityPreparationBlockerCode.LEGACY_MASTERY_WRITES_ACTIVE
            },
        )
    }

    companion object {
        fun failed(
            code: LegacyAuthorityPreparationBlockerCode =
                LegacyAuthorityPreparationBlockerCode.PREPARATION_FAILED,
        ): LegacyAuthorityPreparationResult =
            LegacyAuthorityPreparationResult(
                durableCutoverStageNames = emptyList(),
                blockedCutoverStageName =
                    ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY.name,
                mirroredStudentRevisionCount = 0L,
                studentMirrorCheckpointFingerprint = null,
                pendingMasteryFactCount = 0L,
                blockers =
                    baseWriteRouteBlockers() +
                        LegacyAuthorityPreparationBlocker(
                            code = code,
                            blockedStageName =
                                ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY.name,
                        ),
                keepLegacyStudentAuthority = true,
                keepLegacyMasteryAuthority = true,
            )
    }
}

internal fun interface LegacyStudentAssetUriResolver {
    fun resolve(sourceAsset: CanonicalSourceAssetRecord): String
}

internal fun interface LegacyStudentRelayDrainer {
    suspend fun drain(
        nowEpochMillis: Long,
        batchSize: Int,
    ): LocalLearningAuthorityRelayDrainResult
}

internal class LegacyAuthorityPreparationCoordinator(
    private val learnerId: String,
    private val verifiedPrefix: VerifiedAuthorityCutoverPrefixCoordinator,
    private val legacySource: LegacyAuthorityMigrationSourcePort,
    private val studentMigration: StudentMistakeMigrationPort,
    private val studentRelayDrainer: LegacyStudentRelayDrainer,
    private val assetUriResolver: LegacyStudentAssetUriResolver,
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0L) },
) {
    private val studentDocumentMapper =
        LegacyStudentDocumentMapper(
            learnerId = learnerId,
            assetUriResolver = assetUriResolver,
        )

    init {
        require(learnerId.isNotBlank())
    }

    suspend fun prepare(): LegacyAuthorityPreparationResult {
        val prefix =
            try {
                verifiedPrefix.migrateVerifiedPrefix()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return LegacyAuthorityPreparationResult.failed(
                    LegacyAuthorityPreparationBlockerCode.VERIFIED_PREFIX_UNAVAILABLE,
                )
            }

        val initial =
            try {
                legacySource.readLegacyAuthorityMigrationSnapshot(learnerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return result(
                    prefix = prefix,
                    studentMirror = StudentMirrorResult.empty(),
                    masteryScan = MasteryScanResult.empty(),
                    additionalBlockers =
                        listOf(
                            LegacyAuthorityPreparationBlocker(
                                code =
                                    LegacyAuthorityPreparationBlockerCode.PREPARATION_FAILED,
                                blockedStageName = prefix.blockedAt.name,
                            ),
                        ),
                )
            }

        val studentMirror =
            mirrorStudentDocuments(initial)
        if (studentMirror.mirroredCount > 0L) {
            drainStudentRelay()?.let { relayBlocker ->
                return result(
                    prefix = prefix,
                    studentMirror = studentMirror,
                    masteryScan = scanMasteryFacts(),
                    additionalBlockers = listOf(relayBlocker),
                )
            }
        }
        val masteryScan = scanMasteryFacts()
        val finalSnapshot =
            try {
                legacySource.readLegacyAuthorityMigrationSnapshot(learnerId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        val changedBlockers =
            buildList {
                if (finalSnapshot == null) {
                    add(
                        LegacyAuthorityPreparationBlocker(
                            code = LegacyAuthorityPreparationBlockerCode.PREPARATION_FAILED,
                            blockedStageName = prefix.blockedAt.name,
                        ),
                    )
                } else {
                    if (
                        initial.studentDocumentsCanonicalFingerprint !=
                            finalSnapshot.studentDocumentsCanonicalFingerprint
                    ) {
                        add(
                            LegacyAuthorityPreparationBlocker(
                                code =
                                    LegacyAuthorityPreparationBlockerCode
                                        .SOURCE_CHANGED_DURING_PREPARATION,
                                blockedStageName =
                                    ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED.name,
                            ),
                        )
                    }
                    if (
                        initial.masteryFactsCanonicalFingerprint !=
                            finalSnapshot.masteryFactsCanonicalFingerprint
                    ) {
                        add(
                            LegacyAuthorityPreparationBlocker(
                                code =
                                    LegacyAuthorityPreparationBlockerCode
                                        .SOURCE_CHANGED_DURING_PREPARATION,
                                blockedStageName =
                                    ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED.name,
                            ),
                        )
                    }
                }
            }
        return result(
            prefix = prefix,
            studentMirror = studentMirror,
            masteryScan = masteryScan,
            additionalBlockers = changedBlockers,
        )
    }

    private suspend fun mirrorStudentDocuments(
        snapshot: LegacyAuthorityMigrationSnapshot,
    ): StudentMirrorResult {
        if (snapshot.studentProblemWithMultipleEntriesCount > 0L) {
            return StudentMirrorResult(
                mirroredCount = 0L,
                checkpointFingerprint = null,
                blocker =
                    LegacyAuthorityPreparationBlocker(
                        code =
                            LegacyAuthorityPreparationBlockerCode
                                .STUDENT_PROBLEM_HAS_MULTIPLE_ENTRIES,
                        blockedStageName =
                            ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED.name,
                    ),
            )
        }
        if (snapshot.studentProblemCurrentRevisionNotLatestCount > 0L) {
            return StudentMirrorResult.blocked(
                LegacyAuthorityPreparationBlockerCode
                    .STUDENT_CURRENT_REVISION_CANNOT_BE_PRESERVED,
            )
        }
        val migrationId =
            "$STUDENT_MIRROR_ID_PREFIX-" +
                snapshot.studentDocumentsCanonicalFingerprint.take(MIGRATION_ID_FINGERPRINT_CHARS)
        var checkpoint =
            try {
                studentMigration.readCheckpoint(migrationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return StudentMirrorResult.blocked(
                    LegacyAuthorityPreparationBlockerCode.STUDENT_MIRROR_CHECKPOINT_INVALID,
                )
            }
        if (
            checkpoint != null &&
                (
                    checkpoint.sourceDatabaseCanonicalFingerprint !=
                        snapshot.studentDocumentsCanonicalFingerprint ||
                        checkpoint.completed
                )
        ) {
            return StudentMirrorResult(
                mirroredCount = checkpoint.importedRecordCount,
                checkpointFingerprint = checkpoint.checkpointCanonicalFingerprint,
                blocker =
                    LegacyAuthorityPreparationBlocker(
                        code =
                            LegacyAuthorityPreparationBlockerCode
                                .STUDENT_MIRROR_CHECKPOINT_INVALID,
                        blockedStageName =
                            ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED.name,
                    ),
            )
        }

        var sourceCursor = checkpoint?.lastKey?.toLegacyCursor()
        while (true) {
            val page =
                try {
                    legacySource.readLegacyStudentDocumentMigrationPage(
                        afterExclusive = sourceCursor,
                        limit = MAX_MIGRATION_PAGE_SIZE,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return StudentMirrorResult.from(
                        checkpoint = checkpoint,
                        blockerCode =
                            LegacyAuthorityPreparationBlockerCode.STUDENT_DOCUMENT_INVALID,
                    )
                }
            if (page.records.isEmpty()) break
            val firstCursor = page.records.first().cursor
            if (sourceCursor != null && firstCursor <= sourceCursor) {
                return StudentMirrorResult.from(
                    checkpoint = checkpoint,
                    blockerCode =
                        LegacyAuthorityPreparationBlockerCode
                            .STUDENT_MIRROR_CHECKPOINT_INVALID,
                )
            }
            val mapped =
                try {
                    page.records.map(studentDocumentMapper::map)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (rejected: LegacyStudentRecordRejected) {
                    return StudentMirrorResult(
                        mirroredCount = checkpoint?.importedRecordCount ?: 0L,
                        checkpointFingerprint =
                            checkpoint?.checkpointCanonicalFingerprint,
                        blocker =
                            LegacyAuthorityPreparationBlocker(
                                code = rejected.code,
                                blockedStageName =
                                    ThreeAuthorityCutoverStage
                                        .STUDENT_DOCUMENTS_IMPORTED.name,
                                sourceReferenceId = rejected.sourceReferenceId,
                            ),
                    )
                }
            val command =
                ApplyStudentMistakeMigrationPageCommand(
                    migrationId = migrationId,
                    sourceDatabaseCanonicalFingerprint =
                        snapshot.studentDocumentsCanonicalFingerprint,
                    sourcePageCanonicalFingerprint =
                        pageFingerprint(
                            sourceFingerprint =
                                snapshot.studentDocumentsCanonicalFingerprint,
                            afterExclusive = sourceCursor,
                            records = page.records,
                            mapped = mapped,
                        ),
                    expectedCheckpointCanonicalFingerprint =
                        checkpoint?.checkpointCanonicalFingerprint,
                    afterExclusive = checkpoint?.lastKey,
                    records = mapped,
                    // This is a mirror of an active source, not a completed cutover.
                    isLastPage = false,
                    appliedAtEpochMillis = clock().coerceAtLeast(0L),
                )
            val receipt =
                try {
                    studentMigration.applyPage(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return StudentMirrorResult.from(
                        checkpoint = checkpoint,
                        blockerCode =
                            LegacyAuthorityPreparationBlockerCode
                                .STUDENT_MIRROR_CHECKPOINT_INVALID,
                    )
                }
            val persisted =
                try {
                    studentMigration.readCheckpoint(migrationId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            if (persisted == null || persisted != receipt.checkpoint) {
                return StudentMirrorResult.from(
                    checkpoint = checkpoint,
                    blockerCode =
                        LegacyAuthorityPreparationBlockerCode
                            .STUDENT_MIRROR_CHECKPOINT_INVALID,
                )
            }
            checkpoint = persisted
            sourceCursor = checkNotNull(persisted.lastKey).toLegacyCursor()
            if (!page.hasMore) break
        }
        val mirroredCount = checkpoint?.importedRecordCount ?: 0L
        return StudentMirrorResult(
            mirroredCount = mirroredCount,
            checkpointFingerprint = checkpoint?.checkpointCanonicalFingerprint,
            blocker =
                if (mirroredCount == snapshot.studentDocumentRevisionCount) {
                    null
                } else {
                    LegacyAuthorityPreparationBlocker(
                        code =
                            LegacyAuthorityPreparationBlockerCode
                                .STUDENT_DESTINATION_COUNT_MISMATCH,
                        blockedStageName =
                            ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED.name,
                    )
                },
        )
    }

    private suspend fun drainStudentRelay(): LegacyAuthorityPreparationBlocker? {
        repeat(MAX_RELAY_DRAIN_ROUNDS) {
            val result =
                try {
                    studentRelayDrainer.drain(
                        nowEpochMillis = clock().coerceAtLeast(0L),
                        batchSize = LearnerMasteryRelayCapability.MAX_RELAY_BATCH_SIZE,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return LegacyAuthorityPreparationBlocker(
                        code =
                            LegacyAuthorityPreparationBlockerCode
                                .STUDENT_RELAY_DID_NOT_QUIESCE,
                        blockedStageName =
                            ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED.name,
                    )
                }
            val processed =
                result.inboundApplied +
                    result.inboundDuplicates +
                    result.inboundReferenceReceipts +
                    result.outboundApplied +
                    result.outboundDuplicates
            if (processed == 0) return null
        }
        return LegacyAuthorityPreparationBlocker(
            code =
                LegacyAuthorityPreparationBlockerCode.STUDENT_RELAY_DID_NOT_QUIESCE,
            blockedStageName =
                ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED.name,
        )
    }

    private suspend fun scanMasteryFacts(): MasteryScanResult {
        var cursor: LegacyMasteryFactMigrationCursor? = null
        var pending = 0L
        val samples = mutableListOf<LegacyAuthorityPreparationBlocker>()
        while (true) {
            val page =
                try {
                    legacySource.readLegacyMasteryFactMigrationPage(
                        learnerId = learnerId,
                        afterExclusive = cursor,
                        limit = MASTERY_SCAN_PAGE_SIZE,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    return MasteryScanResult(
                        pendingCount = pending,
                        blockerSamples =
                            samples +
                                LegacyAuthorityPreparationBlocker(
                                    code =
                                        LegacyAuthorityPreparationBlockerCode
                                            .PREPARATION_FAILED,
                                    blockedStageName =
                                        ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED.name,
                                ),
                    )
                }
            if (page.records.isEmpty()) break
            if (cursor != null && page.records.first().cursor <= cursor) {
                return MasteryScanResult(
                    pendingCount = pending,
                    blockerSamples =
                        samples +
                            LegacyAuthorityPreparationBlocker(
                                code =
                                    LegacyAuthorityPreparationBlockerCode
                                        .PREPARATION_FAILED,
                                blockedStageName =
                                    ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED.name,
                            ),
                )
            }
            page.records.forEach { record ->
                pending += 1
                if (samples.size < MAX_MASTERY_BLOCKER_SAMPLES) {
                    val code =
                        when {
                            record.sourceFact.factKind ==
                                LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED ->
                                LegacyAuthorityPreparationBlockerCode
                                    .OPEN_RESPONSE_HAS_NO_EQUIVALENT

                            record.sourceProofCanonicalFingerprint == null ->
                                LegacyAuthorityPreparationBlockerCode
                                    .MASTERY_SOURCE_PROOF_MISSING

                            else ->
                                LegacyAuthorityPreparationBlockerCode
                                    .MASTERY_BEHAVIOR_METADATA_INSUFFICIENT
                        }
                    samples +=
                        LegacyAuthorityPreparationBlocker(
                            code = code,
                            blockedStageName =
                                ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED.name,
                            sourceReferenceId = record.sourceFact.sourceFactId,
                        )
                }
            }
            cursor = page.records.last().cursor
            if (!page.hasMore) break
        }
        return MasteryScanResult(
            pendingCount = pending,
            blockerSamples = samples,
        )
    }

    private fun result(
        prefix: AuthorityCutoverPrefixResult,
        studentMirror: StudentMirrorResult,
        masteryScan: MasteryScanResult,
        additionalBlockers: List<LegacyAuthorityPreparationBlocker>,
    ): LegacyAuthorityPreparationResult =
        LegacyAuthorityPreparationResult(
            durableCutoverStageNames = prefix.durableStages.map { it.name },
            blockedCutoverStageName = prefix.blockedAt.name,
            mirroredStudentRevisionCount = studentMirror.mirroredCount,
            studentMirrorCheckpointFingerprint = studentMirror.checkpointFingerprint,
            pendingMasteryFactCount = masteryScan.pendingCount,
            blockers =
                baseWriteRouteBlockers() +
                    listOfNotNull(studentMirror.blocker) +
                    masteryScan.blockerSamples +
                    additionalBlockers,
            keepLegacyStudentAuthority = true,
            keepLegacyMasteryAuthority = true,
        )
}

private data class StudentMirrorResult(
    val mirroredCount: Long,
    val checkpointFingerprint: String?,
    val blocker: LegacyAuthorityPreparationBlocker?,
) {
    companion object {
        fun empty(): StudentMirrorResult =
            StudentMirrorResult(
                mirroredCount = 0L,
                checkpointFingerprint = null,
                blocker = null,
            )

        fun blocked(code: LegacyAuthorityPreparationBlockerCode): StudentMirrorResult =
            from(checkpoint = null, blockerCode = code)

        fun from(
            checkpoint: StudentMistakeMigrationCheckpoint?,
            blockerCode: LegacyAuthorityPreparationBlockerCode,
        ): StudentMirrorResult =
            StudentMirrorResult(
                mirroredCount = checkpoint?.importedRecordCount ?: 0L,
                checkpointFingerprint = checkpoint?.checkpointCanonicalFingerprint,
                blocker =
                    LegacyAuthorityPreparationBlocker(
                        code = blockerCode,
                        blockedStageName =
                            ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED.name,
                    ),
            )
    }
}

private data class MasteryScanResult(
    val pendingCount: Long,
    val blockerSamples: List<LegacyAuthorityPreparationBlocker>,
) {
    companion object {
        fun empty(): MasteryScanResult =
            MasteryScanResult(
                pendingCount = 0L,
                blockerSamples = emptyList(),
            )
    }
}

private fun StudentMistakeMigrationKey.toLegacyCursor(): LegacyStudentDocumentMigrationCursor =
    LegacyStudentDocumentMigrationCursor(
        committedAtEpochMillis = committedAtEpochMillis,
        problemId = problemId,
        revisionNumber = revisionNumber,
        revisionId = revisionId,
    )

private fun pageFingerprint(
    sourceFingerprint: String,
    afterExclusive: LegacyStudentDocumentMigrationCursor?,
    records: List<LegacyStudentDocumentMigrationRecord>,
    mapped: List<StudentMistakeMigrationRecord>,
): String {
    val digest =
        CanonicalSha256(STUDENT_MIRROR_PAGE_FINGERPRINT_DOMAIN)
            .field("mapperVersion", STUDENT_MIRROR_MAPPER_VERSION)
            .field("sourceFingerprint", sourceFingerprint)
            .nullableField(
                "afterCommittedAtEpochMillis",
                afterExclusive?.committedAtEpochMillis?.toString(),
            )
            .nullableField("afterProblemId", afterExclusive?.problemId)
            .nullableField(
                "afterRevisionNumber",
                afterExclusive?.revisionNumber?.toString(),
            )
            .nullableField("afterRevisionId", afterExclusive?.revisionId)
            .field("recordCount", records.size)
    records.zip(mapped).forEachIndexed { index, (source, destination) ->
        digest
            .field("record[$index].entryId", source.entryId)
            .field("record[$index].problemId", source.problemId)
            .field(
                "record[$index].problemCanonicalFingerprint",
                source.problemCanonicalFingerprint,
            )
            .field("record[$index].revisionId", source.revisionId)
            .field("record[$index].revisionNumber", source.revisionNumber)
            .field("record[$index].subject", source.subject)
            .field("record[$index].title", source.title)
            .field("record[$index].problemMarkdown", source.problemMarkdown)
            .nullableField(
                "record[$index].questionDocumentSnapshot",
                source.questionDocumentSnapshot,
            )
            .field("record[$index].contentFingerprint", source.contentFingerprint)
            .field("record[$index].practiceUnitId", source.practiceUnitId)
            .field("record[$index].practiceUnitKey", source.practiceUnitKey)
            .field("record[$index].practiceUnitKind", source.practiceUnitKind)
            .field("record[$index].practiceUnitTitle", source.practiceUnitTitle)
            .field("record[$index].estimatedSeconds", source.estimatedSeconds)
            .field("record[$index].status", source.status)
            .field(
                "record[$index].acceptedAtEpochMillis",
                source.acceptedAtEpochMillis,
            )
            .field("record[$index].updatedAtEpochMillis", source.updatedAtEpochMillis)
            .field(
                "record[$index].revisionCreatedAtEpochMillis",
                source.revisionCreatedAtEpochMillis,
            )
            .field("record[$index].assetCount", source.sourceAssets.size)
        source.sourceAssets.forEachIndexed { assetIndex, linked ->
            val asset = linked.sourceAsset
            digest
                .field("record[$index].asset[$assetIndex].role", linked.role)
                .field("record[$index].asset[$assetIndex].id", asset.sourceAssetId)
                .field("record[$index].asset[$assetIndex].sha256", asset.contentSha256)
                .field("record[$index].asset[$assetIndex].path", asset.relativePath)
                .field("record[$index].asset[$assetIndex].mimeType", asset.mimeType)
                .field("record[$index].asset[$assetIndex].byteSize", asset.byteSize)
                .field("record[$index].asset[$assetIndex].width", asset.width)
                .field("record[$index].asset[$assetIndex].height", asset.height)
                .field("record[$index].asset[$assetIndex].sourceType", asset.sourceType)
                .field(
                    "record[$index].asset[$assetIndex].createdAtEpochMillis",
                    asset.createdAtEpochMillis,
                )
        }
        destination.problem.originalImages.forEachIndexed { imageIndex, image ->
            digest.field(
                "record[$index].destinationImage[$imageIndex].uri",
                image.localContentUri,
            )
        }
    }
    return digest.finish()
}

private fun baseWriteRouteBlockers(): List<LegacyAuthorityPreparationBlocker> =
    listOf(
        LegacyAuthorityPreparationBlocker(
            code = LegacyAuthorityPreparationBlockerCode.LEGACY_STUDENT_WRITES_ACTIVE,
            blockedStageName =
                ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED.name,
        ),
        LegacyAuthorityPreparationBlocker(
            code = LegacyAuthorityPreparationBlockerCode.LEGACY_MASTERY_WRITES_ACTIVE,
            blockedStageName =
                ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED.name,
        ),
    )

private const val STUDENT_MIRROR_MAPPER_VERSION = "legacy-student-mirror-v1"
private const val STUDENT_MIRROR_ID_PREFIX = "legacy-student-mirror-v1"
private const val STUDENT_MIRROR_PAGE_FINGERPRINT_DOMAIN =
    "legacy-student-mirror-page-v1"
private const val MIGRATION_ID_FINGERPRINT_CHARS = 48
private const val MASTERY_SCAN_PAGE_SIZE = 128
private const val MAX_MASTERY_BLOCKER_SAMPLES = 32
private const val MAX_RELAY_DRAIN_ROUNDS = 128
private val SHA_256 = Regex("[0-9a-f]{64}")

internal fun productionLegacyStudentAssetUriResolver(
    context: Context,
): LegacyStudentAssetUriResolver {
    val vault =
        LegacyRoomCaptureAssetBridge(
            AndroidCanonicalAssetVault(context.applicationContext),
        )
    return LegacyStudentAssetUriResolver { sourceAsset ->
        vault.resolve(sourceAsset).toURI().toASCIIString()
    }
}
