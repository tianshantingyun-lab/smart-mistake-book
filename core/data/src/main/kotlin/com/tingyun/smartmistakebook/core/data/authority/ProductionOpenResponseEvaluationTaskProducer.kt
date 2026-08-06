package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskProducer
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.openCoreDataOpenResponseEvaluationTaskProducer
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier

/**
 * Complete local identity of the answer that is current at authorization time.
 *
 * This value never crosses the model boundary. Only canonical fingerprints derived by core:model
 * enter the signed evaluator scope.
 */
internal class CurrentOpenResponseEvaluationBinding(
    val learnerId: String,
    val conversationId: String,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    val questionDocument: QuestionDocument,
    val rubricCanonicalFingerprint: String,
    /** Durable claim-scoped HMAC supplied by the encrypted Tutor outbox owner. */
    val operationBinding: String,
    val currentAnswer: String,
    teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    val evaluator: OpenResponseEvaluatorKind,
    val evaluatorPolicyFingerprint: String,
) {
    val teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference> =
        teachingReferences.toList()

    val scopeIdentity: CurrentOpenResponseEvaluationScopeIdentity =
        CurrentOpenResponseEvaluationScopeIdentity(
            learnerId = learnerId,
            conversationId = conversationId,
            questionDocumentId = questionDocumentId,
            questionRevisionNumber = questionRevisionNumber,
            subject = subject,
            questionFingerprint =
                OpenResponseEvaluationTaskFingerprints.question(questionDocument),
            answerFingerprint =
                OpenResponseEvaluationTaskFingerprints.answer(currentAnswer),
            operationBinding = operationBinding,
            rubricCanonicalFingerprint = rubricCanonicalFingerprint,
        )

    init {
        require(learnerId.isValidLocalOpenResponseIdentity())
        require(conversationId.isValidLocalOpenResponseIdentity())
        require(questionDocumentId.isValidLocalOpenResponseIdentity())
        require(questionRevisionNumber > 0)
        require(subject != SubjectKind.GENERAL)
        require(rubricCanonicalFingerprint.isLowercaseSha256())
        require(operationBinding.isLowercaseSha256())
        require(evaluatorPolicyFingerprint.isLowercaseSha256())
    }
}

/** Identity-only snapshot used to compare an authorization with the live local tutor state. */
internal data class CurrentOpenResponseEvaluationScopeIdentity(
    val learnerId: String,
    val conversationId: String,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    val questionFingerprint: String,
    val answerFingerprint: String,
    val operationBinding: String,
    val rubricCanonicalFingerprint: String,
)

internal fun interface CurrentOpenResponseEvaluationScopeAuthorization {
    /**
     * Must read the current local tutor state. Cached approval is insufficient: this is invoked
 * again when a produced task is authorized for model egress.
     */
    fun isCurrent(expected: CurrentOpenResponseEvaluationScopeIdentity): Boolean
}

/** Opens one exact, revocable producer without exposing the model authority root. */
internal object ProductionOpenResponseEvaluationTaskProducerFactory {
    fun create(
        binding: CurrentOpenResponseEvaluationBinding,
        knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
        expiresAtEpochMillis: Long,
        nowEpochMillis: LongSupplier,
        currentScopeAuthorization: CurrentOpenResponseEvaluationScopeAuthorization,
    ): OpenResponseEvaluationTaskProducer {
        val expectedScope = binding.scopeIdentity
        require(currentScopeAuthorization.isCurrent(expectedScope)) {
            "Open-response evaluation does not match the current local tutor scope"
        }
        return openCoreDataOpenResponseEvaluationTaskProducer(
            learnerId = binding.learnerId,
            conversationId = binding.conversationId,
            questionDocumentId = binding.questionDocumentId,
            questionRevisionNumber = binding.questionRevisionNumber,
            subject = binding.subject,
            questionDocument = binding.questionDocument,
            rubricCanonicalFingerprint = binding.rubricCanonicalFingerprint,
            operationBinding = binding.operationBinding,
            currentAnswer = binding.currentAnswer,
            teachingReferences = binding.teachingReferences,
            evaluator = binding.evaluator,
            evaluatorPolicyFingerprint = binding.evaluatorPolicyFingerprint,
            knowledgeReferenceVerifier = knowledgeReferenceVerifier,
            expiresAtEpochMillis = expiresAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
            scopeIsCurrent =
                BooleanSupplier {
                    currentScopeAuthorization.isCurrent(expectedScope)
                },
        )
    }
}

private fun String.isValidLocalOpenResponseIdentity(): Boolean =
    isNotBlank() &&
        this == trim() &&
        length <= MAX_LOCAL_OPEN_RESPONSE_IDENTITY_CHARS &&
        none(Char::isISOControl)

private fun String.isLowercaseSha256(): Boolean =
    length == SHA_256_CHARS && all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }

private const val MAX_LOCAL_OPEN_RESPONSE_IDENTITY_CHARS = 256
private const val SHA_256_CHARS = 64
