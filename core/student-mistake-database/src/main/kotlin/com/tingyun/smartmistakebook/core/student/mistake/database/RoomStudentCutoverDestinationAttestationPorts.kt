package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeCatalogActivatedV1
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsAcceptedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import java.util.function.LongSupplier

internal class RoomStudentCutoverDestinationReadSource(
    private val database: StudentMistakeRoomDatabase,
) : StudentCutoverDestinationReadSource {
    private val dao = database.cutoverAttestationDao()

    override suspend fun readSnapshot(): StudentCutoverDestinationSnapshot {
        val journal = dao.readJournalSnapshot()
        val indexes = dao.readIndexSnapshot()
        val journalConsistent =
            journal.pendingOutboxCount == 0L &&
                journal.invalidRetiredUnsafeLegacyOutboxCount == 0L &&
                journal.pendingInboxCount == 0L &&
                journal.duplicateOutboxIdempotencyCount == 0L &&
                journal.duplicateInboxIdempotencyCount == 0L &&
                journal.orphanReviewOutboxReceiptCount == 0L
        val indexesConsistent =
            indexes.searchState == StudentProblemSearchIndexState.READY.name &&
                indexes.searchIndexedDocumentCount == indexes.currentRevisionCount &&
                indexes.searchDocumentCount == indexes.currentRevisionCount &&
                indexes.searchFtsCount == indexes.searchDocumentCount
        return StudentCutoverDestinationSnapshot(
            journalFingerprint = journal.canonicalFingerprint(),
            indexFingerprint = indexes.canonicalFingerprint(),
            journalConsistent = journalConsistent,
            indexesConsistent = indexesConsistent,
            cutoverFencePresent = indexes.cutoverFenceCount != 0L,
            completionReceiptPresent = indexes.completionReceiptCount != 0L,
        )
    }

    override suspend fun readPage(
        phase: StudentCutoverVerificationPhase,
        afterExclusive: String?,
        limit: Int,
    ): List<StudentCutoverVerificationRecord> =
        when (phase) {
            StudentCutoverVerificationPhase.OUTBOX ->
                dao.readOutboxPage(afterExclusive, limit).map { row ->
                    row.requireAttestableOutbox()
                }

            StudentCutoverVerificationPhase.INBOX ->
                dao.readInboxPage(afterExclusive, limit).map { row ->
                    row.requireAttestableInbox()
                }

            StudentCutoverVerificationPhase.SEARCH ->
                dao.readSearchPage(afterExclusive, limit).map { row ->
                    row.requireAttestableSearchDocument()
                }

            StudentCutoverVerificationPhase.CLASSIFICATION ->
                dao.readClassificationPage(afterExclusive, limit).map { row ->
                    row.requireAttestableIndexedReference("classification")
                }

            StudentCutoverVerificationPhase.IDENTITY ->
                dao.readIdentityPage(afterExclusive, limit).map { row ->
                    row.requireAttestableIndexedReference("identity")
                }
        }

    override fun close() {
        database.close()
    }

    private suspend fun StudentStoreOutboxEntity.requireAttestableOutbox():
        StudentCutoverVerificationRecord {
        if (deliveryState == RETIRED_UNSAFE_LEGACY_STATE) {
            return requireAttestableRetiredLegacyReviewTombstone()
        }
        check(
            deliveryState == DELIVERED_STATE &&
                deliveredAtEpochMillis != null &&
                deliveredAtEpochMillis >= occurredAtEpochMillis &&
                deliveryAttemptCount >= 0
        ) {
            "Student outbox contains an unresolved migration-era message"
        }
        val payload = decodePayload()
        val messageFingerprint = requireCanonicalMessageFingerprint(payload)
        val learner = payload.learnerId()
        check(learner != null && learnerId == learner) {
            "Student outbox message is outside its learner scope"
        }
        when (payload) {
            is ProblemRevisionCommittedV1 ->
                requireRevision(payload.revision.revisionId)

            is ProblemKnowledgeBindingsAcceptedV1 ->
                requireRevision(payload.problemRevision.revisionId)

            is ProblemKnowledgeBindingsSnapshotV2 ->
                requireRevision(payload.problemRevision.revisionId)

            is ProblemLifecycleChangedV1 ->
                requireRevision(payload.problemRevision.revisionId)

            is ProblemRevisionSupersededV1 ->
                requireRevision(payload.previousRevision.revisionId)

            is ReviewObservationCapturedV1 -> {
                requireRevision(payload.problemRevision.revisionId)
                check(dao.countReviewTransitionForOutbox(eventId) == 1) {
                    "Student review outbox message has no exact local transition receipt"
                }
            }

            is ReviewObservationCapturedV2 -> {
                requireRevision(payload.problemRevision.revisionId)
                check(dao.countReviewTransitionForOutbox(eventId) == 1) {
                    "Student review outbox message has no exact local transition receipt"
                }
            }

            else -> error("Unsupported student outbox payload")
        }
        return StudentCutoverVerificationRecord(
            stableKey = eventId,
            canonicalFingerprint = messageFingerprint,
        )
    }

    private fun StudentStoreOutboxEntity.requireAttestableRetiredLegacyReviewTombstone():
        StudentCutoverVerificationRecord {
        val retiredAt = checkNotNull(deliveredAtEpochMillis) {
            "Retired legacy review tombstone has no retirement time"
        }
        val boundLearnerId = checkNotNull(learnerId) {
            "Retired legacy review tombstone has no learner scope"
        }
        check(
            eventId.isNotBlank() &&
                boundLearnerId.isNotBlank() &&
                aggregateId.isNotBlank() &&
                idempotencyKey.isNotBlank() &&
                sourceStoreGeneration.isNotBlank() &&
                aggregateVersion >= 0L &&
                occurredAtEpochMillis >= 0L &&
                availableAtEpochMillis >= occurredAtEpochMillis &&
                payloadCanonicalFingerprint.isCanonicalSha256() &&
                envelopeCanonicalFingerprint.isCanonicalSha256() &&
            sourceStore == StudyStoreKind.STUDENT_MISTAKES.name &&
                destinationStore == StudyStoreKind.LEARNER_MASTERY.name &&
                payloadType == ReviewObservationCapturedV1.PAYLOAD_TYPE &&
                (
                    payloadVersion == ReviewObservationCapturedV1.PAYLOAD_VERSION ||
                        payloadVersion == ReviewObservationCapturedV2.PAYLOAD_VERSION
                ) &&
                retiredAt >= occurredAtEpochMillis &&
                deliveryAttemptCount >= 0
        ) {
            "Student outbox contains an invalid retired legacy review tombstone"
        }
        val wireFingerprint = CanonicalSha256(RETIRED_LEGACY_WIRE_FINGERPRINT_DOMAIN)
            .field("wire", payloadWire)
            .finish()
        val tombstoneFingerprint =
            CanonicalSha256(RETIRED_LEGACY_TOMBSTONE_FINGERPRINT_DOMAIN)
                .field("eventId", eventId)
                .field("sourceStore", sourceStore)
                .field("destinationStore", destinationStore)
                .field("learnerId", boundLearnerId)
                .field("aggregateId", aggregateId)
                .field("aggregateVersion", aggregateVersion)
                .field("payloadType", payloadType)
                .field("payloadVersion", payloadVersion)
                .field("payloadCanonicalFingerprint", payloadCanonicalFingerprint)
                .field("payloadWireFingerprint", wireFingerprint)
                .field("envelopeCanonicalFingerprint", envelopeCanonicalFingerprint)
                .field("occurredAtEpochMillis", occurredAtEpochMillis)
                .field("availableAtEpochMillis", availableAtEpochMillis)
                .field("idempotencyKey", idempotencyKey)
                .field("sourceStoreGeneration", sourceStoreGeneration)
                .field("deliveryAttemptCount", deliveryAttemptCount)
                .field("retiredAtEpochMillis", retiredAt)
                .finish()
        return StudentCutoverVerificationRecord(
            stableKey = eventId,
            canonicalFingerprint = tombstoneFingerprint,
        )
    }

    private fun String.isCanonicalSha256(): Boolean =
        length == SHA_256_HEX_LENGTH && all { character -> character in '0'..'9' || character in 'a'..'f' }

    private suspend fun StudentStoreInboxEntity.requireAttestableInbox():
        StudentCutoverVerificationRecord {
        check(
            applyState == APPLIED_STATE &&
                appliedAtEpochMillis != null &&
                appliedAtEpochMillis >= receivedAtEpochMillis
        ) {
            "Student inbox contains an unresolved migration-era message"
        }
        val payload = decodePayload()
        val messageFingerprint = requireCanonicalMessageFingerprint(payload)
        when (payload) {
            is LearningAttemptRecordedV1 ->
                requireRevision(payload.problemRevision.revisionId)

            is KnowledgeCatalogActivatedV1 -> Unit
            else -> error("Unsupported student inbox payload")
        }
        return StudentCutoverVerificationRecord(
            stableKey = eventId,
            canonicalFingerprint = messageFingerprint,
        )
    }

    private fun StudentCutoverSearchRow.requireAttestableSearchDocument():
        StudentCutoverVerificationRecord {
        check(
            revisionProblemId == problemId &&
                resolvedPracticeUnitId == primaryPracticeUnitId &&
                practiceUnitTitle != null
        ) {
            "Student search source contains an orphaned current revision or practice unit"
        }
        val normalized =
            StudentMistakeSearchNormalizer.document(
                revisionId = revisionId,
                title = title,
                stemMarkdown = stemMarkdown,
                practiceUnitTitle = checkNotNull(practiceUnitTitle),
            )
        check(
            searchRowId != null &&
                searchSourceCanonicalFingerprint ==
                normalized.sourceCanonicalFingerprint &&
                searchNormalizedText == normalized.normalizedText &&
                searchTokenizedText == normalized.tokenizedText &&
                ftsTokenizedText == normalized.tokenizedText
        ) {
            "Student search index is stale or incomplete"
        }
        return StudentCutoverVerificationRecord(
            stableKey = revisionId,
            canonicalFingerprint =
                CanonicalSha256(SEARCH_RECORD_FINGERPRINT_DOMAIN)
                    .field("revisionId", revisionId)
                    .field(
                        "sourceCanonicalFingerprint",
                        normalized.sourceCanonicalFingerprint,
                    )
                    .finish(),
        )
    }

    private fun StudentCutoverIndexedReferenceRow.requireAttestableIndexedReference(
        label: String,
    ): StudentCutoverVerificationRecord {
        check(consistent) {
            "Student $label index contains an orphan or version-inconsistent row"
        }
        requireSha256(canonicalFingerprint, "Student $label row fingerprint")
        return StudentCutoverVerificationRecord(
            stableKey = stableKey,
            canonicalFingerprint =
                CanonicalSha256(INDEXED_REFERENCE_FINGERPRINT_DOMAIN)
                    .field("kind", label)
                    .field("stableKey", stableKey)
                    .field("canonicalFingerprint", canonicalFingerprint)
                    .finish(),
        )
    }

    private suspend fun requireRevision(revisionId: String) {
        check(dao.countRevision(revisionId) == 1) {
            "Student relay message refers to a missing problem revision"
        }
    }
}

internal object StudentCutoverDestinationAttestationPortFactory {
    fun open(
        context: Context,
        ownerKey: StudentMistakeOwnerKey,
    ): StudentCutoverDestinationAttestationPorts =
        open(
            context = context,
            ownerKey = ownerKey,
            nowEpochMillis = LongSupplier { System.currentTimeMillis() },
        )

    fun open(
        context: Context,
        ownerKey: StudentMistakeOwnerKey,
        nowEpochMillis: LongSupplier,
    ): StudentCutoverDestinationAttestationPorts {
        check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
            "Student destination attestations require the core:data owner key"
        }
        val source =
            RoomStudentCutoverDestinationReadSource(
                StudentMistakeOwnedDatabase.openDatabase(context, ownerKey),
            )
        return StudentCutoverDestinationAttestationEngine(
            source = source,
            nowEpochMillis = nowEpochMillis,
            ownerKey = ownerKey,
        )
    }
}

/**
 * Adapts core:data's already-issued stage-five receipt into an owner-bound process-local
 * reference. It does not create, recompute, or infer that receipt.
 */
internal object StudentDocumentImportReceiptReferenceFactory {
    fun bind(
        cutoverGeneration: Long,
        legacyPrefixFingerprint: String,
        migratedRecordCount: Long,
        sourceCheckpoint: String,
        destinationFingerprint: String,
        receiptFingerprint: String,
        ownerKey: StudentMistakeOwnerKey,
    ): StudentDocumentImportReceiptReference =
        StudentDocumentImportReceiptReference.issue(
            ownerKey = ownerKey,
            cutoverGeneration = cutoverGeneration,
            legacyPrefixFingerprint = legacyPrefixFingerprint,
            migratedRecordCount = migratedRecordCount,
            sourceCheckpoint = sourceCheckpoint,
            destinationFingerprint = destinationFingerprint,
            receiptFingerprint = receiptFingerprint,
        )
}

private fun StudentStoreOutboxEntity.decodePayload(): CrossStoreEventPayload =
    StudentMistakeCrossStoreCodec.decode(
        payloadType = payloadType,
        payloadVersion = payloadVersion,
        wire = payloadWire,
    )

private fun StudentStoreInboxEntity.decodePayload(): CrossStoreEventPayload =
    StudentMistakeCrossStoreCodec.decode(
        payloadType = payloadType,
        payloadVersion = payloadVersion,
        wire = payloadWire,
    )

private fun StudentStoreOutboxEntity.requireCanonicalMessageFingerprint(
    payload: CrossStoreEventPayload,
): String {
    val envelope =
        CrossStoreEventEnvelope(
            eventId = eventId,
            sourceStore = enumValueOrCorrupt(sourceStore, "outbox source store"),
            destinationStore =
                enumValueOrCorrupt(destinationStore, "outbox destination store"),
            aggregateId = aggregateId,
            aggregateVersion = aggregateVersion,
            occurredAtEpochMillis = occurredAtEpochMillis,
            idempotencyKey = idempotencyKey,
            sourceStoreGeneration = sourceStoreGeneration,
            payload = payload,
            payloadType = payloadType,
            payloadVersion = payloadVersion,
            payloadCanonicalFingerprint = payloadCanonicalFingerprint,
        )
    check(
        envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES &&
            envelope.canonicalFingerprint == envelopeCanonicalFingerprint
    ) {
        "Student outbox envelope is not canonical"
    }
    return envelope.canonicalFingerprint
}

private fun StudentStoreInboxEntity.requireCanonicalMessageFingerprint(
    payload: CrossStoreEventPayload,
): String {
    val envelope =
        CrossStoreEventEnvelope(
            eventId = eventId,
            sourceStore = enumValueOrCorrupt(sourceStore, "inbox source store"),
            destinationStore =
                enumValueOrCorrupt(destinationStore, "inbox destination store"),
            aggregateId = aggregateId,
            aggregateVersion = aggregateVersion,
            occurredAtEpochMillis = occurredAtEpochMillis,
            idempotencyKey = idempotencyKey,
            sourceStoreGeneration = sourceStoreGeneration,
            payload = payload,
            payloadType = payloadType,
            payloadVersion = payloadVersion,
            payloadCanonicalFingerprint = payloadCanonicalFingerprint,
        )
    check(
        envelope.destinationStore == StudyStoreKind.STUDENT_MISTAKES &&
            envelope.canonicalFingerprint == envelopeCanonicalFingerprint
    ) {
        "Student inbox envelope is not canonical"
    }
    return envelope.canonicalFingerprint
}

private fun CrossStoreEventPayload.learnerId(): String? =
    when (this) {
        is ProblemRevisionCommittedV1 -> revision.problem.learnerId
        is ProblemLifecycleChangedV1 -> problemRevision.problem.learnerId
        is ProblemRevisionSupersededV1 -> previousRevision.problem.learnerId
        is ProblemKnowledgeBindingsAcceptedV1 ->
            problemRevision.problem.learnerId

        is ProblemKnowledgeBindingsSnapshotV2 ->
            problemRevision.problem.learnerId

        is ReviewObservationCapturedV1 ->
            problemRevision.problem.learnerId

        is ReviewObservationCapturedV2 ->
            problemRevision.problem.learnerId

        else -> null
    }

private fun StudentCutoverJournalSnapshotRow.canonicalFingerprint(): String =
    CanonicalSha256(JOURNAL_SNAPSHOT_FINGERPRINT_DOMAIN)
        .field("outboxCount", outboxCount)
        .field("inboxCount", inboxCount)
        .field("retiredUnsafeLegacyOutboxCount", retiredUnsafeLegacyOutboxCount)
        .field("pendingOutboxCount", pendingOutboxCount)
        .field(
            "invalidRetiredUnsafeLegacyOutboxCount",
            invalidRetiredUnsafeLegacyOutboxCount,
        )
        .field("pendingInboxCount", pendingInboxCount)
        .field(
            "duplicateOutboxIdempotencyCount",
            duplicateOutboxIdempotencyCount,
        )
        .field(
            "duplicateInboxIdempotencyCount",
            duplicateInboxIdempotencyCount,
        )
        .field(
            "orphanReviewOutboxReceiptCount",
            orphanReviewOutboxReceiptCount,
        )
        .nullableField("maxOutboxEventId", maxOutboxEventId)
        .nullableField("maxInboxEventId", maxInboxEventId)
        .nullableField(
            "maxDeliveredAtEpochMillis",
            maxDeliveredAtEpochMillis?.toString(),
        )
        .nullableField(
            "maxAppliedAtEpochMillis",
            maxAppliedAtEpochMillis?.toString(),
        )
        .finish()

private fun StudentCutoverIndexSnapshotRow.canonicalFingerprint(): String =
    CanonicalSha256(INDEX_SNAPSHOT_FINGERPRINT_DOMAIN)
        .nullableField("searchState", searchState)
        .nullableField(
            "searchIndexedDocumentCount",
            searchIndexedDocumentCount?.toString(),
        )
        .field("currentRevisionCount", currentRevisionCount)
        .field("searchDocumentCount", searchDocumentCount)
        .field("searchFtsCount", searchFtsCount)
        .field("classificationCount", classificationCount)
        .field("canonicalIdentityCount", canonicalIdentityCount)
        .field("sourceBindingCount", sourceBindingCount)
        .field("identityReceiptCount", identityReceiptCount)
        .field("maxLearnerChangeVersion", maxLearnerChangeVersion)
        .field("sumLearnerChangeVersion", sumLearnerChangeVersion)
        .nullableField(
            "searchUpdatedAtEpochMillis",
            searchUpdatedAtEpochMillis?.toString(),
        )
        .field("cutoverFenceCount", cutoverFenceCount)
        .field("completionReceiptCount", completionReceiptCount)
        .finish()

private const val DELIVERED_STATE = "DELIVERED"
private const val RETIRED_UNSAFE_LEGACY_STATE = "RETIRED_UNSAFE_LEGACY"
private const val SHA_256_HEX_LENGTH = 64
private const val APPLIED_STATE = "APPLIED"
private const val JOURNAL_SNAPSHOT_FINGERPRINT_DOMAIN =
    "student-cutover-journal-snapshot-v1"
private const val INDEX_SNAPSHOT_FINGERPRINT_DOMAIN =
    "student-cutover-index-snapshot-v1"
private const val SEARCH_RECORD_FINGERPRINT_DOMAIN =
    "student-cutover-search-record-v1"
private const val INDEXED_REFERENCE_FINGERPRINT_DOMAIN =
    "student-cutover-indexed-reference-v1"
private const val RETIRED_LEGACY_WIRE_FINGERPRINT_DOMAIN =
    "student-retired-legacy-review-wire-v1"
private const val RETIRED_LEGACY_TOMBSTONE_FINGERPRINT_DOMAIN =
    "student-retired-legacy-review-tombstone-v1"
