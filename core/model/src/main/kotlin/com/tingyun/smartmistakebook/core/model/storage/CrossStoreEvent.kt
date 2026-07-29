package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

enum class StudyStoreKind {
    STUDENT_MISTAKES,
    LEARNER_MASTERY,
    HIGH_SCHOOL_KNOWLEDGE,
}

sealed interface CrossStoreEventPayload {
    val payloadType: String
    val payloadVersion: Int
    val payloadCanonicalFingerprint: String
    val sourceStore: StudyStoreKind
    val allowedDestinationStores: Set<StudyStoreKind>
    val aggregateId: String
    val occurredAtEpochMillis: Long
}

data class ProblemRevisionCommittedV1(
    val revision: StudentProblemRevisionRef,
    val commitReceiptId: String,
    val commitReceiptCanonicalFingerprint: String,
    val committedAtEpochMillis: Long,
) : CrossStoreEventPayload {
    init {
        requireStoreIdentity(commitReceiptId, "Problem commit receipt id")
        requireCanonicalFingerprint(
            commitReceiptCanonicalFingerprint,
            "Problem commit receipt fingerprint",
        )
        require(committedAtEpochMillis >= 0) {
            "Problem commit time must not be negative"
        }
    }

    override val payloadType: String = PAYLOAD_TYPE
    override val payloadVersion: Int = PAYLOAD_VERSION
    override val sourceStore: StudyStoreKind = StudyStoreKind.STUDENT_MISTAKES
    override val allowedDestinationStores: Set<StudyStoreKind> =
        setOf(StudyStoreKind.LEARNER_MASTERY)
    override val aggregateId: String
        get() = revision.revisionId
    override val occurredAtEpochMillis: Long
        get() = committedAtEpochMillis
    override val payloadCanonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("payloadType", payloadType)
            .field("payloadVersion", payloadVersion)
            .field("revisionRef", revision.canonicalFingerprint)
            .field("commitReceiptId", commitReceiptId)
            .field("commitReceiptCanonicalFingerprint", commitReceiptCanonicalFingerprint)
            .field("committedAtEpochMillis", committedAtEpochMillis)
            .finish()

    companion object {
        const val PAYLOAD_TYPE = "problem_revision_committed"
        const val PAYLOAD_VERSION = 1
        private const val DOMAIN = "$PAYLOAD_TYPE-v$PAYLOAD_VERSION"
    }
}

data class ProblemKnowledgeBindingsAcceptedV1(
    val problemRevision: StudentProblemRevisionRef,
    val bindings: List<ProblemKnowledgeBindingRef>,
    val acceptedAtEpochMillis: Long,
) : CrossStoreEventPayload {
    init {
        require(bindings.isNotEmpty()) { "Accepted knowledge bindings must not be empty" }
        require(bindings.size <= MAX_BINDINGS) {
            "Accepted knowledge bindings exceed the supported event budget"
        }
        require(bindings == bindings.sortedBy(ProblemKnowledgeBindingRef::bindingId)) {
            "Accepted knowledge bindings must use canonical binding-id order"
        }
        require(bindings.map(ProblemKnowledgeBindingRef::bindingId).distinct().size == bindings.size) {
            "Accepted knowledge bindings must have unique ids"
        }
        require(bindings.all { binding -> binding.problemRevision == problemRevision }) {
            "Accepted knowledge bindings must target the event problem revision"
        }
        require(acceptedAtEpochMillis >= 0) {
            "Knowledge binding acceptance time must not be negative"
        }
    }

    override val payloadType: String = PAYLOAD_TYPE
    override val payloadVersion: Int = PAYLOAD_VERSION
    override val sourceStore: StudyStoreKind = StudyStoreKind.STUDENT_MISTAKES
    override val allowedDestinationStores: Set<StudyStoreKind> =
        setOf(StudyStoreKind.LEARNER_MASTERY)
    override val aggregateId: String
        get() = problemRevision.revisionId
    override val occurredAtEpochMillis: Long
        get() = acceptedAtEpochMillis
    override val payloadCanonicalFingerprint: String
        get() {
            val hash = CanonicalSha256(DOMAIN)
                .field("payloadType", payloadType)
                .field("payloadVersion", payloadVersion)
                .field("problemRevisionRef", problemRevision.canonicalFingerprint)
                .field("bindingCount", bindings.size)
            bindings.forEachIndexed { index, binding ->
                hash.field("binding[$index]", binding.canonicalFingerprint)
            }
            return hash
                .field("acceptedAtEpochMillis", acceptedAtEpochMillis)
                .finish()
        }

    companion object {
        const val PAYLOAD_TYPE = "problem_knowledge_bindings_accepted"
        const val PAYLOAD_VERSION = 1
        private const val DOMAIN = "$PAYLOAD_TYPE-v$PAYLOAD_VERSION"
        private const val MAX_BINDINGS = 512
    }
}

data class LearningAttemptRecordedV1(
    val evidence: LearningEvidenceRef,
    val problemRevision: StudentProblemRevisionRef,
    val reviewSessionId: String,
    val reviewQueueItemId: String,
    val submissionId: String,
    val presentationId: String,
    val recordedAtEpochMillis: Long,
) : CrossStoreEventPayload {
    init {
        require(evidence.learnerId == problemRevision.problem.learnerId) {
            "Learning attempt and problem revision must belong to the same learner"
        }
        requireStoreIdentity(reviewSessionId, "Review session id")
        requireStoreIdentity(reviewQueueItemId, "Review queue item id")
        requireStoreIdentity(submissionId, "Attempt submission id")
        requireStoreIdentity(presentationId, "Attempt presentation id")
        require(recordedAtEpochMillis >= 0) {
            "Learning attempt record time must not be negative"
        }
    }

    override val payloadType: String = PAYLOAD_TYPE
    override val payloadVersion: Int = PAYLOAD_VERSION
    override val sourceStore: StudyStoreKind = StudyStoreKind.LEARNER_MASTERY
    override val allowedDestinationStores: Set<StudyStoreKind> =
        setOf(StudyStoreKind.STUDENT_MISTAKES)
    override val aggregateId: String
        get() = evidence.eventId
    override val occurredAtEpochMillis: Long
        get() = recordedAtEpochMillis
    override val payloadCanonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("payloadType", payloadType)
            .field("payloadVersion", payloadVersion)
            .field("evidenceRef", evidence.canonicalFingerprint)
            .field("problemRevisionRef", problemRevision.canonicalFingerprint)
            .field("reviewSessionId", reviewSessionId)
            .field("reviewQueueItemId", reviewQueueItemId)
            .field("submissionId", submissionId)
            .field("presentationId", presentationId)
            .field("recordedAtEpochMillis", recordedAtEpochMillis)
            .finish()

    companion object {
        const val PAYLOAD_TYPE = "learning_attempt_recorded"
        const val PAYLOAD_VERSION = 1
        private const val DOMAIN = "$PAYLOAD_TYPE-v$PAYLOAD_VERSION"
    }
}

data class KnowledgeCatalogActivatedV1(
    val knowledgePackVersion: String,
    val taxonomyVersion: String,
    val manifestCanonicalFingerprint: String,
    val activatedAtEpochMillis: Long,
) : CrossStoreEventPayload {
    init {
        requireStoreVersion(knowledgePackVersion, "Activated knowledge pack version")
        requireStoreVersion(taxonomyVersion, "Activated knowledge taxonomy version")
        requireCanonicalFingerprint(
            manifestCanonicalFingerprint,
            "Knowledge catalog manifest fingerprint",
        )
        require(activatedAtEpochMillis >= 0) {
            "Knowledge catalog activation time must not be negative"
        }
    }

    override val payloadType: String = PAYLOAD_TYPE
    override val payloadVersion: Int = PAYLOAD_VERSION
    override val sourceStore: StudyStoreKind = StudyStoreKind.HIGH_SCHOOL_KNOWLEDGE
    override val allowedDestinationStores: Set<StudyStoreKind> = setOf(
        StudyStoreKind.STUDENT_MISTAKES,
        StudyStoreKind.LEARNER_MASTERY,
    )
    override val aggregateId: String
        get() = knowledgePackVersion
    override val occurredAtEpochMillis: Long
        get() = activatedAtEpochMillis
    override val payloadCanonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("payloadType", payloadType)
            .field("payloadVersion", payloadVersion)
            .field("knowledgePackVersion", knowledgePackVersion)
            .field("taxonomyVersion", taxonomyVersion)
            .field("manifestCanonicalFingerprint", manifestCanonicalFingerprint)
            .field("activatedAtEpochMillis", activatedAtEpochMillis)
            .finish()

    companion object {
        const val PAYLOAD_TYPE = "knowledge_catalog_activated"
        const val PAYLOAD_VERSION = 1
        private const val DOMAIN = "$PAYLOAD_TYPE-v$PAYLOAD_VERSION"
    }
}

/**
 * Versioned, idempotent delivery unit between two physical stores.
 *
 * The constructor validates the redundant payload header fields so persistence adapters may index
 * them without trusting caller-provided metadata.
 */
data class CrossStoreEventEnvelope(
    val eventId: String,
    val sourceStore: StudyStoreKind,
    val destinationStore: StudyStoreKind,
    val aggregateId: String,
    val aggregateVersion: Long,
    val occurredAtEpochMillis: Long,
    val idempotencyKey: String,
    val sourceStoreGeneration: String,
    val payload: CrossStoreEventPayload,
    val payloadType: String = payload.payloadType,
    val payloadVersion: Int = payload.payloadVersion,
    val payloadCanonicalFingerprint: String = payload.payloadCanonicalFingerprint,
) {
    init {
        requireStoreIdentity(eventId, "Cross-store event id")
        require(sourceStore != destinationStore) {
            "Cross-store event source and destination must differ"
        }
        require(sourceStore == payload.sourceStore) {
            "Cross-store event source does not match its payload"
        }
        require(destinationStore in payload.allowedDestinationStores) {
            "Cross-store event destination is not allowed for its payload"
        }
        requireStoreIdentity(aggregateId, "Cross-store aggregate id")
        require(aggregateId == payload.aggregateId) {
            "Cross-store aggregate id does not match its payload"
        }
        require(aggregateVersion > 0) { "Cross-store aggregate version must be positive" }
        require(occurredAtEpochMillis >= 0) {
            "Cross-store event time must not be negative"
        }
        require(occurredAtEpochMillis == payload.occurredAtEpochMillis) {
            "Cross-store event time does not match its payload"
        }
        requireStoreIdentity(idempotencyKey, "Cross-store idempotency key")
        requireStoreVersion(sourceStoreGeneration, "Cross-store source generation")
        requireStoreIdentity(payloadType, "Cross-store payload type")
        require(payloadType == payload.payloadType) {
            "Cross-store payload type does not match its payload"
        }
        require(payloadVersion > 0 && payloadVersion == payload.payloadVersion) {
            "Cross-store payload version does not match its payload"
        }
        requireCanonicalFingerprint(
            payloadCanonicalFingerprint,
            "Cross-store payload canonical fingerprint",
        )
        require(payloadCanonicalFingerprint == payload.payloadCanonicalFingerprint) {
            "Cross-store payload fingerprint does not match its payload"
        }
    }

    val canonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("eventId", eventId)
            .field("sourceStore", sourceStore.name)
            .field("destinationStore", destinationStore.name)
            .field("aggregateId", aggregateId)
            .field("aggregateVersion", aggregateVersion)
            .field("payloadType", payloadType)
            .field("payloadVersion", payloadVersion)
            .field("payloadCanonicalFingerprint", payloadCanonicalFingerprint)
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("idempotencyKey", idempotencyKey)
            .field("sourceStoreGeneration", sourceStoreGeneration)
            .finish()

    private companion object {
        const val DOMAIN = "cross-store-event-envelope-v1"
    }
}
