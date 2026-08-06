package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.flow.map


internal class RoomStudyDatabaseLegacyAuthoritySupport(
    private val database: StudyDatabase,
) {
    suspend fun appendLegacyAuthorityCutoverStageReceipt(
        command: AppendLegacyAuthorityCutoverStageCommand,
    ): LegacyAuthorityCutoverJournalWriteResult =
        database.legacyAuthorityCutoverJournalDao().append(command)

    suspend fun readLegacyAuthorityCutoverStageReceipts():
        List<LegacyAuthorityCutoverStageReceipt> =
        database.legacyAuthorityCutoverJournalDao().readOrdered()

    suspend fun prepareCaptureStudentSaveHandoff(
        command: PrepareCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult =
        database.captureStudentSaveHandoffDao().prepare(command)

    suspend fun finalizeCaptureStudentSaveHandoff(
        command: FinalizeCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult =
        database.captureStudentSaveHandoffDao().finalize(command)

    suspend fun readPendingCaptureStudentSaveHandoffs(
        query: ReadPendingCaptureStudentSaveHandoffsQuery,
    ): List<CaptureStudentSaveHandoffRecord> =
        database.captureStudentSaveHandoffDao().readPending(query)

    suspend fun acknowledgeStudentOwnedCaptureSession(
        command: AcknowledgeStudentOwnedCaptureSessionCommand,
    ): StudentOwnedCaptureSessionAckResult = database.withWriteTransaction {
        val source = command.source
        val handoffDao = database.captureStudentSaveHandoffDao()
        val prepared =
            handoffDao.prepare(
                PrepareCaptureStudentSaveHandoffCommand(
                    intentId = source.intentId,
                    intentCanonicalFingerprint = source.sourceCanonicalFingerprint,
                    learnerId = command.learnerId,
                    draftId = source.draftId,
                    draftRevisionNumber = source.draftRevisionNumber,
                    sessionId = source.sessionId,
                    targetProblemRef = command.targetProblem,
                    targetProblemRevisionRef = command.targetRevision,
                    preparedAtEpochMillis = source.occurredAtEpochMillis,
                ),
            ).record
        if (prepared.state == CaptureStudentSaveHandoffState.FINALIZED) {
            check(
                prepared.targetSaveReceiptFingerprint ==
                    command.targetSaveReceiptFingerprint,
            ) {
                "Student-owned capture session acknowledgement changed its target receipt"
            }
            database.problemDraftTransactionDao()
                .acknowledgeStudentOwnedCaptureSession(command)
            return@withWriteTransaction StudentOwnedCaptureSessionAckResult(
                created = false,
                handoff = prepared,
            )
        }

        val librarySource = source as? StudentOwnedLibraryCaptureSessionAckSource
        librarySource?.let {
            val workspace =
                database.problemDraftEditWorkspaceDao()
                    .requireExactForConfirmation(it.workspace)
            database.problemDraftTransactionDao().revise(
                ReviseProblemDraftCommand(
                    draftId = it.draftId,
                    expectedRevisionNumber = it.workspace.basisRevisionNumber,
                    revision = workspace.toConfirmedRevision(it.workspace),
                ),
            )
        }
        val created =
            database.problemDraftTransactionDao()
                .acknowledgeStudentOwnedCaptureSession(command)
        librarySource?.let {
            database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(
                it.workspace.toConsumeCommand(),
            )
        }
        val finalized =
            handoffDao.finalize(
                FinalizeCaptureStudentSaveHandoffCommand(
                    intentId = source.intentId,
                    intentCanonicalFingerprint = source.sourceCanonicalFingerprint,
                    learnerId = command.learnerId,
                    targetSaveReceiptFingerprint = command.targetSaveReceiptFingerprint,
                    finalizedAtEpochMillis = command.acknowledgedAtEpochMillis,
                    expectedStateVersion = prepared.stateVersion,
                ),
            ).record
        StudentOwnedCaptureSessionAckResult(
            created = created,
            handoff = finalized,
        )
    }

    suspend fun readExactLegacyCaptureStudentDocument(
        query: ExactLegacyCaptureStudentDocumentQuery,
    ): LegacyStudentDocumentMigrationRecord? =
        database.withReadTransaction {
            val dao = database.legacyAuthorityMigrationSourceDao()
            val rows =
                when (query) {
                    is ExactLegacyCaptureReceiptReplayQuery ->
                        dao.readExactReceiptCaptureStudentDocument(
                            learnerId = query.learnerId,
                            intentId = query.intentId,
                            intentCanonicalFingerprint =
                                query.intentCanonicalFingerprint,
                            draftId = query.draftId,
                            draftRevisionNumber = query.draftRevisionNumber,
                            tutorSessionId = query.tutorSessionId,
                            problemId = query.problemId,
                            problemRevisionId = query.problemRevisionId,
                            practiceUnitId = query.practiceUnitId,
                            errorBookEntryId = query.errorBookEntryId,
                        )

                    is ExactLegacyPreparedHandoffReplayQuery ->
                        dao.readExactPreparedHandoffCaptureStudentDocument(
                            learnerId = query.learnerId,
                            intentId = query.intentId,
                            intentCanonicalFingerprint =
                                query.intentCanonicalFingerprint,
                            draftId = query.draftId,
                            draftRevisionNumber = query.draftRevisionNumber,
                            tutorSessionId = query.tutorSessionId,
                            subject = query.subject,
                            problemId = query.problemId,
                            problemRevisionId = query.problemRevisionId,
                            problemRevisionNumber = query.problemRevisionNumber,
                            practiceUnitId = query.practiceUnitId,
                            documentCanonicalFingerprint =
                                query.documentCanonicalFingerprint,
                        )
                }
            val row = rows.singleOrNull() ?: return@withReadTransaction null
            if (enumValues<SubjectKind>().none { subject -> subject.name == row.subject }) {
                return@withReadTransaction null
            }
            val sourceAssets =
                dao.readExactStudentDocumentAssets(
                    draftId = query.draftId,
                    revisionId = row.revisionId,
                    limit = MAX_EXACT_LEGACY_CAPTURE_SOURCE_ASSETS + 1,
                )
            val draftSourceAssets =
                dao.readExactDraftSourceAssets(
                    draftId = query.draftId,
                    limit = MAX_EXACT_LEGACY_CAPTURE_SOURCE_ASSETS + 1,
                )
            if (
                sourceAssets.size > MAX_EXACT_LEGACY_CAPTURE_SOURCE_ASSETS ||
                draftSourceAssets.isEmpty() ||
                draftSourceAssets.size > MAX_EXACT_LEGACY_CAPTURE_SOURCE_ASSETS ||
                draftSourceAssets.map { it.pageIndex } != draftSourceAssets.indices.toList()
            ) {
                return@withReadTransaction null
            }
            val durableAssets =
                sourceAssets.map { asset ->
                    asset.toLegacyStudentDocumentAssetRowOrNull(row.revisionId)
                        ?: return@withReadTransaction null
                }
            val questionSourceAssetIds =
                durableAssets.asSequence()
                    .filter { asset -> asset.role == EXACT_CAPTURE_QUESTION_SOURCE_ROLE }
                    .mapTo(hashSetOf()) { asset ->
                        checkNotNull(asset.canonicalSourceAssetId)
                    }
            if (
                !questionSourceAssetIds.containsAll(
                    draftSourceAssets.mapTo(hashSetOf()) { asset -> asset.sourceAssetId },
                )
            ) {
                return@withReadTransaction null
            }
            try {
                row.toLegacyStudentDocumentMigrationRecord(durableAssets)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

    suspend fun readLegacyAuthorityMigrationSnapshot(
        learnerId: String,
    ): LegacyAuthorityMigrationSnapshot {
        require(learnerId.isNotBlank())
        val stats = database.legacyAuthorityMigrationSourceDao().readStats(learnerId)
        return LegacyAuthorityMigrationSnapshot(
            schemaVersion = STUDY_DATABASE_VERSION,
            studentDocumentRevisionCount = stats.studentDocumentRevisionCount,
            studentSourceAssetLinkCount = stats.studentSourceAssetLinkCount,
            studentBrokenSourceAssetLinkCount =
                stats.studentBrokenSourceAssetLinkCount,
            studentSourceAssetByteCount = stats.studentSourceAssetByteCount,
            studentProblemWithMultipleEntriesCount =
                stats.studentProblemWithMultipleEntriesCount,
            studentProblemCurrentRevisionNotLatestCount =
                stats.studentProblemCurrentRevisionNotLatestCount,
            masterySourceFactCount = stats.masterySourceFactCount,
            masteryProvenSourceFactCount = stats.masteryProvenSourceFactCount,
            latestStudentMutationAtEpochMillis =
                stats.latestStudentMutationAtEpochMillis,
            latestMasteryFactAtEpochMillis = stats.latestMasteryFactAtEpochMillis,
        )
    }

    suspend fun readLegacyStudentDocumentMigrationPage(
        afterExclusive: LegacyStudentDocumentMigrationCursor?,
        limit: Int,
    ): LegacyStudentDocumentMigrationPage {
        require(limit in 1..MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE)
        return database.withReadTransaction {
            val rows =
                database.legacyAuthorityMigrationSourceDao().readStudentDocumentPage(
                    afterCommittedAtEpochMillis = afterExclusive?.committedAtEpochMillis,
                    afterProblemId = afterExclusive?.problemId,
                    afterRevisionNumber = afterExclusive?.revisionNumber,
                    afterRevisionId = afterExclusive?.revisionId,
                    limit = limit + 1,
                )
            val pageRows = rows.take(limit)
            val assetsByRevision =
                if (pageRows.isEmpty()) {
                    emptyMap()
                } else {
                    val revisionIds =
                        pageRows.mapTo(linkedSetOf()) { row -> row.revisionId }.toList()
                    val assetBudgets =
                        database.legacyAuthorityMigrationSourceDao()
                            .readStudentDocumentAssetBudgets(revisionIds)
                    check(
                        assetBudgets.map { budget -> budget.problemRevisionId }.distinct().size ==
                            assetBudgets.size,
                    ) {
                        "Legacy student source returned duplicate asset budgets"
                    }
                    var pageAssetCount = 0L
                    var pageAssetBytes = 0L
                    assetBudgets.forEach { budget ->
                        check(budget.problemRevisionId in revisionIds) {
                            "Legacy student source returned an unrelated asset budget"
                        }
                        check(budget.brokenSourceAssetLinkCount == 0L) {
                            "Legacy student source contains a broken source-asset link"
                        }
                        check(
                            budget.sourceAssetLinkCount in
                                0L..MAX_LEGACY_STUDENT_SOURCE_ASSETS_PER_RECORD.toLong(),
                        ) {
                            "Legacy student document exceeds the source-asset count budget"
                        }
                        check(
                            budget.sourceAssetByteCount in
                                0L..MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_RECORD,
                        ) {
                            "Legacy student document exceeds the source-asset byte budget"
                        }
                        pageAssetCount =
                            Math.addExact(pageAssetCount, budget.sourceAssetLinkCount)
                        pageAssetBytes =
                            Math.addExact(pageAssetBytes, budget.sourceAssetByteCount)
                    }
                    check(
                        pageAssetCount <=
                            pageRows.size.toLong() *
                            MAX_LEGACY_STUDENT_SOURCE_ASSETS_PER_RECORD,
                    ) {
                        "Legacy student page exceeds the source-asset count budget"
                    }
                    check(
                        pageAssetBytes <= MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_PAGE,
                    ) {
                        "Legacy student page exceeds the source-asset byte budget"
                    }
                    val assetRows =
                        database.legacyAuthorityMigrationSourceDao()
                            .readStudentDocumentAssets(
                                revisionIds = revisionIds,
                                limit = Math.toIntExact(pageAssetCount) + 1,
                            )
                    check(assetRows.size.toLong() == pageAssetCount) {
                        "Legacy student source-asset details do not cover every link"
                    }
                    assetRows
                        .map { row -> row.requireDurableLegacyStudentDocumentAsset() }
                        .groupBy { asset -> asset.problemRevisionId }
                }
            LegacyStudentDocumentMigrationPage(
                records =
                    pageRows.map { row ->
                        row.toLegacyStudentDocumentMigrationRecord(
                            sourceAssets = assetsByRevision[row.revisionId].orEmpty(),
                        )
                    },
                hasMore = rows.size > limit,
            )
        }
    }

    suspend fun readLegacyMasteryFactMigrationPage(
        learnerId: String,
        afterExclusive: LegacyMasteryFactMigrationCursor?,
        limit: Int,
    ): LegacyMasteryFactMigrationPage {
        require(learnerId.isNotBlank())
        require(limit in 1..MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE)
        val rows =
            database.legacyAuthorityMigrationSourceDao().readMasteryFactPage(
                learnerId = learnerId,
                afterOccurredAtEpochMillis = afterExclusive?.occurredAtEpochMillis,
                afterSourceFactId = afterExclusive?.sourceFactId,
                limit = limit + 1,
            )
        return LegacyMasteryFactMigrationPage(
            records =
                rows.take(limit).map { row ->
                    LegacyMasteryFactMigrationRecord(
                        sourceFact =
                            LearningObservationSourceFact(
                                sourceFactId = row.sourceFactId,
                                learnerScopeId = row.learnerId,
                                source = LearningObservationSource.valueOf(row.source),
                                factKind =
                                    LearningObservationFactKind.valueOf(row.factKind),
                                anchorId = row.anchorId,
                                subject = SubjectKind.valueOf(row.subject),
                                conversationGeneration = row.conversationGeneration,
                                conversationId = row.conversationId,
                                turnReceiptId = row.turnReceiptId,
                                evidenceRequestId = row.evidenceRequestId,
                                responseFingerprint = row.responseFingerprint,
                                responseSummary = row.responseSummary,
                                occurredAtEpochMillis = row.occurredAtEpochMillis,
                                sourceVersion = row.sourceVersion,
                            ),
                        sourcePayloadCanonicalFingerprint = row.sourcePayloadFingerprint,
                        sourceProofCanonicalFingerprint = row.sourceProofFingerprint,
                        sourceReferenceId = row.sourceReferenceId,
                        targetKind = row.targetKind,
                        targetDatabase = row.targetDatabase,
                        targetId = row.targetId,
                        targetVersion = row.targetVersion,
                        targetCanonicalFingerprint = row.targetFingerprint,
                        attestedAtEpochMillis = row.attestedAtEpochMillis,
                    )
                },
            hasMore = rows.size > limit,
        )
    }

}

