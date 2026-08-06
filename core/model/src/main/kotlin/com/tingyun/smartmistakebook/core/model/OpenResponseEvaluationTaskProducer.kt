package com.tingyun.smartmistakebook.core.model

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier

/**
 * One verified knowledge reference and its minimal teaching projection for the evaluator.
 *
 * The proof is process-local authority. The label and constraint are the only knowledge
 * projection that can enter the model prompt; no knowledge body or learner state is accepted.
 */
class VerifiedOpenResponseEvaluationTeachingReference(
    val proof: VerifiedKnowledgeReferenceProof,
    val label: String,
    val constraint: TutorTeachingConstraint,
)

/**
 * Exact-scope producer held by core:data.
 *
 * It can only recreate a signed task for the question and answer captured when it was opened.
 * Revocation, expiry, or a no-longer-current local scope also invalidates tasks already produced,
 * because every downstream authorization check re-enters the same lease.
 */
interface OpenResponseEvaluationTaskProducer {
    val scopeFingerprint: String

    fun createTask(requestVersion: Long): TutorOpenResponseEvaluationInput

    fun revoke()
}

internal object OpenResponseEvaluationTaskProducerFactory {
    fun open(
        authority: OpenResponseEvaluationHostAuthority,
        learnerId: String,
        conversationId: String,
        questionDocumentId: String,
        questionRevisionNumber: Int,
        subject: SubjectKind,
        questionDocument: QuestionDocument,
        rubricCanonicalFingerprint: String,
        operationBinding: String,
        currentAnswer: String,
        teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
        evaluator: OpenResponseEvaluatorKind,
        evaluatorPolicyFingerprint: String,
        knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
        expiresAtEpochMillis: Long,
        nowEpochMillis: LongSupplier,
        scopeIsCurrent: BooleanSupplier,
    ): OpenResponseEvaluationTaskProducer =
        HostBoundOpenResponseEvaluationTaskProducer(
            authority = authority,
            learnerId = learnerId.requireProducerIdentity("Learner id"),
            conversationId = conversationId.requireProducerIdentity("Conversation id"),
            questionDocumentId =
                questionDocumentId.requireProducerIdentity("Question document id"),
            questionRevisionNumber = questionRevisionNumber,
            subject = subject,
            questionDocument = questionDocument,
            rubricCanonicalFingerprint = rubricCanonicalFingerprint,
            operationBinding = operationBinding,
            currentAnswer = currentAnswer,
            teachingReferences = teachingReferences,
            evaluator = evaluator,
            evaluatorPolicyFingerprint = evaluatorPolicyFingerprint,
            knowledgeReferenceVerifier = knowledgeReferenceVerifier,
            expiresAtEpochMillis = expiresAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
            scopeIsCurrent = scopeIsCurrent,
        )
}

private class HostBoundOpenResponseEvaluationTaskProducer(
    authority: OpenResponseEvaluationHostAuthority,
    learnerId: String,
    conversationId: String,
    questionDocumentId: String,
    questionRevisionNumber: Int,
    subject: SubjectKind,
    questionDocument: QuestionDocument,
    rubricCanonicalFingerprint: String,
    operationBinding: String,
    currentAnswer: String,
    teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    evaluator: OpenResponseEvaluatorKind,
    evaluatorPolicyFingerprint: String,
    knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
    expiresAtEpochMillis: Long,
    nowEpochMillis: LongSupplier,
    scopeIsCurrent: BooleanSupplier,
) : OpenResponseEvaluationTaskProducer {
    private val questionSnapshot = questionDocument
    private val answerSnapshot = currentAnswer
    private val versionLock = Any()
    private var highestRequestVersion = -1L

    private val questionFingerprint =
        OpenResponseEvaluationTaskFingerprints.question(questionSnapshot)
    private val answerFingerprint =
        OpenResponseEvaluationTaskFingerprints.answer(answerSnapshot)
    private val rubricFingerprint =
        rubricCanonicalFingerprint.requireProducerFingerprint("Rubric")
    private val operationBinding =
        operationBinding.requireProducerFingerprint("Operation binding")

    override val scopeFingerprint: String =
        CanonicalSha256(PRODUCTION_SCOPE_DOMAIN)
            .field("learnerId", learnerId)
            .field("conversationId", conversationId)
            .field("questionDocumentId", questionDocumentId)
            .field("questionRevisionNumber", questionRevisionNumber)
            .field("subject", subject.name)
            .field("questionFingerprint", questionFingerprint)
            .field("operationBinding", operationBinding)
            .field("rubricFingerprint", rubricFingerprint)
            .finish()

    private val authorization =
        ExpiringOpenResponseEvaluationAuthorization(
            scopeFingerprint = scopeFingerprint,
            expiresAtEpochMillis = expiresAtEpochMillis,
            nowEpochMillis = nowEpochMillis,
            scopeIsCurrent = scopeIsCurrent,
        )

    private val guidance: List<OpenResponseEvaluationTeachingGuidance>
    private val issuedRequest: HostIssuedOpenResponseEvaluationRequest

    init {
        require(questionRevisionNumber > 0) {
            "Open-response evaluation requires a positive question revision"
        }
        require(subject != SubjectKind.GENERAL) {
            "Open-response evaluation requires one high-school subject"
        }
        evaluatorPolicyFingerprint.requireProducerFingerprint("Evaluator policy")
        require(teachingReferences.size <= TutorOpenResponseEvaluationInput.MAX_TEACHING_GUIDANCE) {
            "Open-response teaching references exceed their budget"
        }

        val admittedReferences =
            teachingReferences.map { reference ->
                require(knowledgeReferenceVerifier.verifies(reference.proof)) {
                    "Open-response teaching reference was not issued by the active knowledge owner"
                }
                require(reference.proof.ref.subject == subject) {
                    "Open-response teaching reference crossed its subject boundary"
                }
                AdmittedOpenResponseTeachingReference(
                    refFingerprint =
                        OpenResponseEvaluationTaskFingerprints.knowledgeScopeReference(
                            questionFingerprint = questionFingerprint,
                            knowledgeNodeReferenceFingerprint =
                                reference.proof.ref.canonicalFingerprint,
                            knowledgeManifestFingerprint =
                                reference.proof.manifestFingerprint,
                            knowledgeActivationGeneration =
                                reference.proof.activationGeneration,
                        ),
                    label = reference.label,
                    constraint = reference.constraint,
                )
            }
        require(
            admittedReferences
                .map(AdmittedOpenResponseTeachingReference::refFingerprint)
                .distinct()
                .size == admittedReferences.size,
        ) {
            "Open-response teaching references must be unique"
        }

        guidance =
            Collections.unmodifiableList(
                admittedReferences.map { admitted ->
                    OpenResponseEvaluationTeachingGuidance(
                        refFingerprint = admitted.refFingerprint,
                        label = admitted.label,
                        constraint = admitted.constraint,
                    )
                },
            )
        val knowledgeScope =
            admittedReferences.map { admitted ->
                OpenResponseKnowledgeScopeRef(
                    refFingerprint = admitted.refFingerprint,
                    subject = subject,
                    questionFingerprint = questionFingerprint,
                )
            }
        val sessionFingerprint =
            CanonicalSha256(PRODUCTION_SESSION_DOMAIN)
                .field("learnerId", learnerId)
                .field("conversationId", conversationId)
                .finish()
        val evidenceDigest =
            CanonicalSha256(PRODUCTION_EVIDENCE_DOMAIN)
                .field("scopeFingerprint", scopeFingerprint)
                .field("evaluator", evaluator.name)
                .field("evaluatorPolicyFingerprint", evaluatorPolicyFingerprint)
        knowledgeScope.forEachIndexed { index, knowledgeReference ->
            evidenceDigest.field(
                "knowledgeReference[$index]",
                knowledgeReference.refFingerprint,
            )
        }
        val evidenceFingerprint = evidenceDigest.finish()

        authorization.requireCurrent()
        issuedRequest =
            authority.issue(
                binding =
                    OpenResponseEvaluationBinding(
                        caseFingerprint = scopeFingerprint,
                        sessionFingerprint = sessionFingerprint,
                        questionFingerprint = questionFingerprint,
                        answerFingerprint = answerFingerprint,
                        rubricFingerprint = rubricFingerprint,
                    ),
                subject = subject,
                knowledgeScope = knowledgeScope,
                evaluator = evaluator,
                policyFingerprint = evaluatorPolicyFingerprint,
                evidenceFingerprint = evidenceFingerprint,
                authorization = authorization,
            )
    }

    override fun createTask(requestVersion: Long): TutorOpenResponseEvaluationInput {
        require(requestVersion >= 0L) {
            "Open-response request version must not be negative"
        }
        authorization.requireCurrent()
        synchronized(versionLock) {
            require(requestVersion >= highestRequestVersion) {
                "Open-response request version moved backwards"
            }
            highestRequestVersion = requestVersion
        }
        return OpenResponseEvaluationTaskBinder.bind(
            issuedRequest = issuedRequest,
            operationBinding = operationBinding,
            questionDocument = questionSnapshot,
            currentAnswer = answerSnapshot,
            teachingGuidance = guidance,
            requestVersion = requestVersion,
        )
    }

    override fun revoke() {
        authorization.revoke()
    }
}

private class ExpiringOpenResponseEvaluationAuthorization(
    private val scopeFingerprint: String,
    expiresAtEpochMillis: Long,
    private val nowEpochMillis: LongSupplier,
    private val scopeIsCurrent: BooleanSupplier,
) : OpenResponseEvaluationAuthorization {
    private val revoked = AtomicBoolean(false)
    private val expiresAtEpochMillis: Long

    init {
        scopeFingerprint.requireProducerFingerprint("Production scope")
        val now = nowEpochMillis.getAsLong()
        require(now >= 0L) {
            "Open-response authorization time must not be negative"
        }
        val lifetime = expiresAtEpochMillis - now
        require(lifetime in 1L..MAX_AUTHORIZATION_LIFETIME_MILLIS) {
            "Open-response authorization lifetime is outside the supported range"
        }
        this.expiresAtEpochMillis = expiresAtEpochMillis
        requireCurrent()
    }

    override fun requireCurrent() {
        check(!revoked.get()) {
            "Open-response evaluation authority was revoked"
        }
        val now = nowEpochMillis.getAsLong()
        check(now >= 0L && now < expiresAtEpochMillis) {
            "Open-response evaluation authority expired"
        }
        check(scopeIsCurrent.getAsBoolean()) {
            "Open-response evaluation no longer matches the current local scope"
        }
    }

    fun revoke() {
        revoked.set(true)
    }

    private companion object {
        const val MAX_AUTHORIZATION_LIFETIME_MILLIS = 15 * 60 * 1_000L
    }
}

private data class AdmittedOpenResponseTeachingReference(
    val refFingerprint: String,
    val label: String,
    val constraint: TutorTeachingConstraint,
)

private fun String.requireProducerIdentity(label: String): String {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= MAX_PRODUCTION_IDENTITY_CHARS &&
            none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank local identity"
    }
    return this
}

private fun String.requireProducerFingerprint(label: String): String {
    require(length == SHA_256_HEX_CHARS && all(Char::isLowerHexDigit)) {
        "$label must be a lowercase SHA-256 value"
    }
    return this
}

private const val MAX_PRODUCTION_IDENTITY_CHARS = 256
private const val PRODUCTION_SCOPE_DOMAIN = "open-response-production-scope-v2"
private const val PRODUCTION_SESSION_DOMAIN = "open-response-production-session-v1"
private const val PRODUCTION_EVIDENCE_DOMAIN = "open-response-production-evidence-v1"
