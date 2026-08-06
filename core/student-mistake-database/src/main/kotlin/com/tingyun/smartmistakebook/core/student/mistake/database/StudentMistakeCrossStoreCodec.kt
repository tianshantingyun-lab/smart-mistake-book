package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeCatalogActivatedV1
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsAcceptedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleState
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

internal object StudentMistakeCrossStoreCodec {
    fun encode(payload: CrossStoreEventPayload): String =
        when (payload) {
            is ProblemRevisionCommittedV1 ->
                encodeOrderedStrings(
                    buildList {
                        addRevision(payload.revision)
                        add(payload.commitReceiptId)
                        add(payload.commitReceiptCanonicalFingerprint)
                        add(payload.committedAtEpochMillis.toString())
                    },
                )

            is ProblemLifecycleChangedV1 ->
                encodeOrderedStrings(
                    buildList {
                        addRevision(payload.problemRevision)
                        add(payload.previousState.name)
                        add(payload.nextState.name)
                        add(payload.changedAtEpochMillis.toString())
                    },
                )

            is ProblemRevisionSupersededV1 ->
                encodeOrderedStrings(
                    buildList {
                        addRevision(payload.previousRevision)
                        addRevision(payload.nextRevision)
                        add(payload.changedAtEpochMillis.toString())
                    },
                )

            is ProblemKnowledgeBindingsAcceptedV1 ->
                encodeOrderedStrings(
                    buildList {
                        addRevision(payload.problemRevision)
                        addBindings(payload.bindings)
                        add(payload.acceptedAtEpochMillis.toString())
                    },
                )

            is ProblemKnowledgeBindingsSnapshotV2 ->
                encodeOrderedStrings(
                    buildList {
                        addRevision(payload.problemRevision)
                        addBindings(payload.bindings)
                        add(payload.bindingSetVersion.toString())
                        add(payload.changedAtEpochMillis.toString())
                    },
                )

            is ReviewObservationCapturedV1 ->
                encodeOrderedStrings(
                    buildList {
                        addRevision(payload.problemRevision)
                        add(payload.reviewSessionId)
                        add(payload.reviewQueueItemId)
                        add(payload.observationId)
                        add(payload.submissionId)
                        add(payload.presentationId)
                        add(payload.responseForm.name)
                        add(payload.responseCanonicalFingerprint)
                        add(payload.verificationOutcome.name)
                        add(payload.attemptOrdinal.toString())
                        add(payload.hintCount.toString())
                        add(payload.answerWasRevealed.toString())
                        add(payload.verificationPolicyVersion)
                        add(payload.elapsedDurationMillis?.toString().orEmpty())
                        add(payload.capturedAtEpochMillis.toString())
                    },
                )

            is ReviewObservationCapturedV2 ->
                encodeOrderedStrings(
                    buildList {
                        addRevision(payload.problemRevision)
                        add(payload.reviewSessionId)
                        add(payload.reviewQueueItemId)
                        add(payload.observationId)
                        add(payload.submissionId)
                        add(payload.presentationId)
                        add(payload.responseForm.name)
                        add(payload.responseOpaqueBinding)
                        add(payload.responseBindingAlgorithmVersion)
                        add(payload.verificationOutcome.name)
                        add(payload.attemptOrdinal.toString())
                        add(payload.hintCount.toString())
                        add(payload.answerWasRevealed.toString())
                        add(payload.verificationPolicyVersion)
                        add(payload.elapsedDurationMillis?.toString().orEmpty())
                        add(payload.capturedAtEpochMillis.toString())
                    },
                )

            is LearningAttemptRecordedV1 ->
                encodeOrderedStrings(
                    buildList {
                        add(payload.evidence.learnerId)
                        add(payload.evidence.eventKind)
                        add(payload.evidence.eventId)
                        add(payload.evidence.eventSequence.toString())
                        add(payload.evidence.eventCanonicalFingerprint)
                        addRevision(payload.problemRevision)
                        add(payload.reviewSessionId)
                        add(payload.reviewQueueItemId)
                        add(payload.submissionId)
                        add(payload.presentationId)
                        add(payload.recordedAtEpochMillis.toString())
                    },
                )

            is KnowledgeCatalogActivatedV1 ->
                encodeOrderedStrings(
                    listOf(
                        payload.knowledgePackVersion,
                        payload.taxonomyVersion,
                        payload.manifestCanonicalFingerprint,
                        payload.activatedAtEpochMillis.toString(),
                    ),
                )
        }

    fun decode(
        payloadType: String,
        payloadVersion: Int,
        wire: String,
    ): CrossStoreEventPayload {
        val fields = WireFields(decodeOrderedStrings(wire))
        val payload =
            when (Pair(payloadType, payloadVersion)) {
                Pair(ProblemRevisionCommittedV1.PAYLOAD_TYPE, 1) ->
                    ProblemRevisionCommittedV1(
                        revision = fields.readRevision(),
                        commitReceiptId = fields.next(),
                        commitReceiptCanonicalFingerprint = fields.next(),
                        committedAtEpochMillis = fields.nextLong(),
                    )

                Pair(ProblemLifecycleChangedV1.PAYLOAD_TYPE, 1) ->
                    ProblemLifecycleChangedV1(
                        problemRevision = fields.readRevision(),
                        previousState = fields.nextEnum<ProblemLifecycleState>(),
                        nextState = fields.nextEnum<ProblemLifecycleState>(),
                        changedAtEpochMillis = fields.nextLong(),
                    )

                Pair(ProblemRevisionSupersededV1.PAYLOAD_TYPE, 1) ->
                    ProblemRevisionSupersededV1(
                        previousRevision = fields.readRevision(),
                        nextRevision = fields.readRevision(),
                        changedAtEpochMillis = fields.nextLong(),
                    )

                Pair(ProblemKnowledgeBindingsAcceptedV1.PAYLOAD_TYPE, 1) -> {
                    val revision = fields.readRevision()
                    ProblemKnowledgeBindingsAcceptedV1(
                        problemRevision = revision,
                        bindings = fields.readBindings(revision),
                        acceptedAtEpochMillis = fields.nextLong(),
                    )
                }

                Pair(ProblemKnowledgeBindingsSnapshotV2.PAYLOAD_TYPE, 2) -> {
                    val revision = fields.readRevision()
                    ProblemKnowledgeBindingsSnapshotV2(
                        problemRevision = revision,
                        bindings = fields.readBindings(revision),
                        bindingSetVersion = fields.nextLong(),
                        changedAtEpochMillis = fields.nextLong(),
                    )
                }

                Pair(ReviewObservationCapturedV1.PAYLOAD_TYPE, 1) ->
                    ReviewObservationCapturedV1(
                        problemRevision = fields.readRevision(),
                        reviewSessionId = fields.next(),
                        reviewQueueItemId = fields.next(),
                        observationId = fields.next(),
                        submissionId = fields.next(),
                        presentationId = fields.next(),
                        responseForm = fields.nextEnum<ReviewResponseForm>(),
                        responseCanonicalFingerprint = fields.next(),
                        verificationOutcome = fields.nextEnum<ReviewVerificationOutcome>(),
                        attemptOrdinal = fields.nextInt(),
                        hintCount = fields.nextInt(),
                        answerWasRevealed = fields.nextBoolean(),
                        verificationPolicyVersion = fields.next(),
                        elapsedDurationMillis = fields.nextNullableLong(),
                        capturedAtEpochMillis = fields.nextLong(),
                    )

                Pair(ReviewObservationCapturedV2.PAYLOAD_TYPE, 2) ->
                    ReviewObservationCapturedV2(
                        problemRevision = fields.readRevision(),
                        reviewSessionId = fields.next(),
                        reviewQueueItemId = fields.next(),
                        observationId = fields.next(),
                        submissionId = fields.next(),
                        presentationId = fields.next(),
                        responseForm = fields.nextEnum<ReviewResponseForm>(),
                        responseOpaqueBinding = fields.next(),
                        responseBindingAlgorithmVersion = fields.next(),
                        verificationOutcome = fields.nextEnum<ReviewVerificationOutcome>(),
                        attemptOrdinal = fields.nextInt(),
                        hintCount = fields.nextInt(),
                        answerWasRevealed = fields.nextBoolean(),
                        verificationPolicyVersion = fields.next(),
                        elapsedDurationMillis = fields.nextNullableLong(),
                        capturedAtEpochMillis = fields.nextLong(),
                    )

                Pair(LearningAttemptRecordedV1.PAYLOAD_TYPE, 1) ->
                    LearningAttemptRecordedV1(
                        evidence =
                            LearningEvidenceRef(
                                learnerId = fields.next(),
                                eventKind = fields.next(),
                                eventId = fields.next(),
                                eventSequence = fields.nextLong(),
                                eventCanonicalFingerprint = fields.next(),
                            ),
                        problemRevision = fields.readRevision(),
                        reviewSessionId = fields.next(),
                        reviewQueueItemId = fields.next(),
                        submissionId = fields.next(),
                        presentationId = fields.next(),
                        recordedAtEpochMillis = fields.nextLong(),
                    )

                Pair(KnowledgeCatalogActivatedV1.PAYLOAD_TYPE, 1) ->
                    KnowledgeCatalogActivatedV1(
                        knowledgePackVersion = fields.next(),
                        taxonomyVersion = fields.next(),
                        manifestCanonicalFingerprint = fields.next(),
                        activatedAtEpochMillis = fields.nextLong(),
                    )

                else ->
                    error(
                        "Unsupported cross-store payload '$payloadType' version $payloadVersion",
                    )
            }
        fields.requireExhausted()
        check(payload.payloadVersion == payloadVersion) {
            "Decoded cross-store payload version does not match its header"
        }
        return payload
    }
}

private fun MutableList<String>.addBindings(
    bindings: List<ProblemKnowledgeBindingRef>,
) {
    add(bindings.size.toString())
    bindings.forEach { binding ->
        add(binding.bindingId)
        add(binding.knowledgeNode.subject.name)
        add(binding.knowledgeNode.knowledgeNodeId)
        add(binding.knowledgeNode.taxonomyVersion)
        add(binding.knowledgeNode.knowledgePackVersion)
        add(binding.bindingCanonicalFingerprint)
    }
}

private fun MutableList<String>.addRevision(revision: StudentProblemRevisionRef) {
    add(revision.problem.learnerId)
    add(revision.problem.subject.name)
    add(revision.problem.problemId)
    add(revision.problem.practiceUnitId)
    add(revision.revisionId)
    add(revision.revisionNumber.toString())
    add(revision.documentCanonicalFingerprint)
}

private class WireFields(
    private val fields: List<String>,
) {
    private var cursor = 0

    fun next(): String {
        check(cursor < fields.size) { "Truncated cross-store payload" }
        return fields[cursor++]
    }

    fun nextInt(): Int =
        next().toIntOrNull() ?: error("Invalid integer in cross-store payload")

    fun nextLong(): Long =
        next().toLongOrNull() ?: error("Invalid long in cross-store payload")

    fun nextNullableLong(): Long? =
        next().let { value ->
            if (value.isEmpty()) {
                null
            } else {
                value.toLongOrNull() ?: error("Invalid nullable long in cross-store payload")
            }
        }

    fun nextBoolean(): Boolean =
        when (val value = next()) {
            "true" -> true
            "false" -> false
            else -> error("Invalid boolean '$value' in cross-store payload")
        }

    inline fun <reified T : Enum<T>> nextEnum(): T {
        val value = next()
        return enumValues<T>().firstOrNull { it.name == value }
            ?: error("Unknown enum value '$value' in cross-store payload")
    }

    fun readRevision(): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = next(),
                    subject = nextEnum<SubjectKind>(),
                    problemId = next(),
                    practiceUnitId = next(),
                ),
            revisionId = next(),
            revisionNumber = nextInt(),
            documentCanonicalFingerprint = next(),
        )

    fun readBindings(
        revision: StudentProblemRevisionRef,
    ): List<ProblemKnowledgeBindingRef> {
        val count = nextInt()
        check(count in 0..MAX_BINDINGS_PER_SNAPSHOT) {
            "Cross-store knowledge binding count is out of range"
        }
        return List(count) {
            ProblemKnowledgeBindingRef(
                bindingId = next(),
                problemRevision = revision,
                knowledgeNode =
                    KnowledgeNodeRef(
                        subject = nextEnum(),
                        knowledgeNodeId = next(),
                        taxonomyVersion = next(),
                        knowledgePackVersion = next(),
                    ),
                bindingCanonicalFingerprint = next(),
            )
        }
    }

    fun requireExhausted() {
        check(cursor == fields.size) { "Trailing fields in cross-store payload" }
    }
}

private const val MAX_BINDINGS_PER_SNAPSHOT = 512
