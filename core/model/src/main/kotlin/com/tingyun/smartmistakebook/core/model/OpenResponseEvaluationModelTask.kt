package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shared durable-ingress contract for raw answers that may reach the evaluator. */
object OpenResponseEvaluationInputPolicy {
    const val MAX_CURRENT_ANSWER_CHARS: Int = 8_000

    fun requireValidCurrentAnswer(currentAnswer: String) {
        currentAnswer.requireSafeModelText(
            label = "Current open response",
            maxChars = MAX_CURRENT_ANSWER_CHARS,
            allowLineBreaks = true,
        )
    }
}

/**
 * The only learning-state projection an open-response evaluator may receive.
 *
 * [refFingerprint] is an opaque, question-bound reference already admitted by the host authority.
 * It is not a knowledge-catalog id and carries no mastery score, confidence, weight, or timeline.
 */
@Serializable
data class OpenResponseEvaluationTeachingGuidance(
    val refFingerprint: String,
    val label: String,
    val constraint: TutorTeachingConstraint,
) {
    init {
        refFingerprint.requireEvaluationFingerprint("Open-response teaching reference")
        label.requireSafeModelText(
            label = "Open-response teaching label",
            maxChars = TutorKnowledgeGuidance.MAX_LABEL_CHARS,
            allowLineBreaks = false,
        )
    }
}

/**
 * A persisted, versioned model-task input for one current answer.
 *
 * The serializable fields are deliberately insufficient to dispatch the task. A live
 * [HostIssuedOpenResponseEvaluationRequest] must be rebound after decoding and is checked again
 * immediately before egress. This lets old task snapshots remain readable without turning JSON
 * into authority.
 */
@Serializable
@SerialName("tutor_open_response_evaluate")
class TutorOpenResponseEvaluationInput internal constructor(
    val evaluationSchemaVersion: Int,
    override val subjectId: String,
    val subject: SubjectKind,
    @SerialName("questionDocument")
    private val storedQuestionDocument: QuestionDocument,
    val currentAnswer: String,
    @SerialName("teachingGuidance")
    private val storedTeachingGuidance: List<OpenResponseEvaluationTeachingGuidance>,
    private val authorizationEnvelope: String,
    /** Claim-scoped opaque HMAC. It is stable across fresh egress grants and contains no answer. */
    val operationBinding: String,
    val requestVersion: Long,
    val idempotencyKey: String,
    @Transient
    private val hostAuthorization: HostIssuedOpenResponseEvaluationRequest? = null,
) : ModelTaskInput {
    override val kind: ModelTaskKind
        get() = ModelTaskKind.TUTOR_EVALUATE

    val questionDocument: QuestionDocument
        get() = storedQuestionDocument.openResponseTaskSnapshot()

    val teachingGuidance: List<OpenResponseEvaluationTeachingGuidance>
        get() = storedTeachingGuidance.toList()

    init {
        require(evaluationSchemaVersion == CURRENT_EVALUATION_SCHEMA_VERSION) {
            "Unsupported open-response model-task input schema"
        }
        subjectId.requireEvaluationFingerprint("Open-response task subject")
        require(subject != SubjectKind.GENERAL) {
            "Open-response evaluation requires one high-school subject"
        }
        require(StructuredContentValidator.validate(storedQuestionDocument).isEmpty()) {
            "Open-response evaluation requires a validated question document"
        }
        OpenResponseEvaluationInputPolicy.requireValidCurrentAnswer(currentAnswer)
        require(storedTeachingGuidance.size <= MAX_TEACHING_GUIDANCE) {
            "Open-response teaching guidance exceeds its budget"
        }
        require(
            storedTeachingGuidance
                .map(OpenResponseEvaluationTeachingGuidance::refFingerprint)
                .distinct()
                .size == storedTeachingGuidance.size,
        ) {
            "Open-response teaching references must be unique"
        }
        require(authorizationEnvelope.length <= OpenResponseEvaluationJsonCodec.MAX_JSON_CHARS) {
            "Open-response authorization envelope exceeds its budget"
        }
        operationBinding.requireEvaluationFingerprint("Open-response operation binding")
        require(requestVersion >= 0) {
            "Open-response evaluation request version must not be negative"
        }
        idempotencyKey.requireEvaluationFingerprint("Open-response idempotency key")
        require(idempotencyKey == expectedIdempotencyKey()) {
            "Open-response evaluation idempotency key does not match its exact input"
        }
        hostAuthorization?.let(::validateHostAuthorization)
    }

    /**
     * Rebinds a decoded snapshot to authority already held by the trusted host.
     *
     * This does not mint authority. The provider module can call it only if a trusted owner has
     * supplied an already-issued capability.
     */
    fun withHostAuthorization(
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
    ): TutorOpenResponseEvaluationInput {
        validateHostAuthorization(issuedRequest)
        return TutorOpenResponseEvaluationInput(
            evaluationSchemaVersion = evaluationSchemaVersion,
            subjectId = subjectId,
            subject = subject,
            storedQuestionDocument = storedQuestionDocument.openResponseTaskSnapshot(),
            currentAnswer = currentAnswer,
            storedTeachingGuidance = storedTeachingGuidance.toList(),
            authorizationEnvelope = authorizationEnvelope,
            operationBinding = operationBinding,
            requestVersion = requestVersion,
            idempotencyKey = idempotencyKey,
            hostAuthorization = issuedRequest,
        )
    }

    /** Fails closed when a persisted or forged input has no exact live host authority. */
    fun requireCurrentHostAuthorization() {
        requireIssuedAuthorization()
    }

    /**
     * Returns only the content-free scope needed by the evaluator.
     *
     * The returned JSON contains opaque fingerprints, the subject, the admitted references, and
     * evaluator policy. It contains no learner id, storage id, SQL, mastery value, or timestamp.
     */
    fun authorizedScopeForProvider(): String {
        requireIssuedAuthorization()
        return authorizationEnvelope
    }

    internal fun requireIssuedAuthorization(): HostIssuedOpenResponseEvaluationRequest {
        val issuedRequest = requireNotNull(hostAuthorization) {
            "Open-response evaluation has no live host-issued authority"
        }
        validateHostAuthorization(issuedRequest)
        return issuedRequest
    }

    private fun validateHostAuthorization(
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
    ) {
        val restored =
            OpenResponseEvaluationJsonCodec.restoreIssuedRequest(
                value = authorizationEnvelope,
                expected = issuedRequest,
            )
        val request = restored.requireIssuedRequest()
        require(request.binding.caseFingerprint == subjectId) {
            "Open-response task crossed its evaluation case"
        }
        require(request.subject == subject) {
            "Open-response task crossed its subject boundary"
        }
        require(
            request.binding.questionFingerprint ==
                OpenResponseEvaluationTaskFingerprints.question(storedQuestionDocument),
        ) {
            "Open-response task question changed after host authorization"
        }
        require(
            request.binding.answerFingerprint ==
                OpenResponseEvaluationTaskFingerprints.answer(currentAnswer),
        ) {
            "Open-response task answer changed after host authorization"
        }
        require(
            request.knowledgeScope.map(OpenResponseKnowledgeScopeRef::refFingerprint) ==
                storedTeachingGuidance.map(
                    OpenResponseEvaluationTeachingGuidance::refFingerprint,
                ),
        ) {
            "Open-response teaching guidance changed its admitted knowledge scope"
        }
    }

    private fun expectedIdempotencyKey(): String =
        OpenResponseEvaluationTaskFingerprints.operationId(
            operationBinding = operationBinding,
            questionFingerprint =
                OpenResponseEvaluationTaskFingerprints.question(storedQuestionDocument),
            requestVersion = requestVersion,
        )

    companion object {
        const val CURRENT_EVALUATION_SCHEMA_VERSION: Int = 2
        const val MAX_CURRENT_ANSWER_CHARS: Int =
            OpenResponseEvaluationInputPolicy.MAX_CURRENT_ANSWER_CHARS
        const val MAX_TEACHING_GUIDANCE: Int = 24
    }
}

/**
 * Safe lower-half binder for the model-task chain.
 *
 * The model-owned production bridge supplies [issuedRequest] through the narrow
 * [OpenResponseEvaluationTaskProducer]. This object never creates
 * [OpenResponseEvaluationHostAuthority] and therefore cannot widen the authority boundary.
 */
object OpenResponseEvaluationTaskBinder {
    fun bind(
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
        operationBinding: String,
        questionDocument: QuestionDocument,
        currentAnswer: String,
        teachingGuidance: List<OpenResponseEvaluationTeachingGuidance>,
        requestVersion: Long,
    ): TutorOpenResponseEvaluationInput {
        val request = issuedRequest.requireIssuedRequest()
        val authorizationEnvelope =
            OpenResponseEvaluationJsonCodec.encodeRequest(issuedRequest)
        val input =
            TutorOpenResponseEvaluationInput(
                evaluationSchemaVersion =
                    TutorOpenResponseEvaluationInput.CURRENT_EVALUATION_SCHEMA_VERSION,
                subjectId = request.binding.caseFingerprint,
                subject = request.subject,
                storedQuestionDocument = questionDocument.openResponseTaskSnapshot(),
                currentAnswer = currentAnswer,
                storedTeachingGuidance = teachingGuidance.toList(),
                authorizationEnvelope = authorizationEnvelope,
                operationBinding = operationBinding,
                requestVersion = requestVersion,
                idempotencyKey =
                    OpenResponseEvaluationTaskFingerprints.operationId(
                        operationBinding = operationBinding,
                        questionFingerprint =
                            OpenResponseEvaluationTaskFingerprints.question(questionDocument),
                        requestVersion = requestVersion,
                    ),
                hostAuthorization = issuedRequest,
            )
        input.requireCurrentHostAuthorization()
        return input
    }
}

/** Stable fingerprints shared by the trusted producer and the task binder. */
object OpenResponseEvaluationTaskFingerprints {
    fun question(questionDocument: QuestionDocument): String =
        CanonicalSha256("open-response-question-v1")
            .field(
                "questionDocument",
                openResponseTaskJson.encodeToString(
                    QuestionDocument.serializer(),
                    questionDocument.openResponseTaskSnapshot(),
                ),
            )
            .finish()

    fun answer(currentAnswer: String): String {
        OpenResponseEvaluationInputPolicy.requireValidCurrentAnswer(currentAnswer)
        return CanonicalSha256("open-response-answer-v1")
            .field("currentAnswer", currentAnswer)
            .finish()
    }

    fun operationId(
        operationBinding: String,
        questionFingerprint: String,
        requestVersion: Long,
    ): String {
        operationBinding.requireEvaluationFingerprint("Open-response operation binding")
        questionFingerprint.requireEvaluationFingerprint("Open-response question fingerprint")
        require(requestVersion >= 0L) {
            "Open-response operation request version must not be negative"
        }
        return CanonicalSha256("open-response-evaluation-operation-v2")
            .field("operationBinding", operationBinding)
            .field("questionFingerprint", questionFingerprint)
            .field("requestVersion", requestVersion)
            .finish()
    }

    /**
     * Opaque question-bound identity for one already verified local knowledge reference.
     *
     * Keeping this derivation beside the producer prevents the persistence adapter from
     * reimplementing a security-sensitive scope mapping. The returned value carries no catalog
     * text, learner state, score, confidence, or weight.
     */
    fun knowledgeScopeReference(
        questionFingerprint: String,
        knowledgeNodeReferenceFingerprint: String,
        knowledgeManifestFingerprint: String,
        knowledgeActivationGeneration: Long,
    ): String {
        questionFingerprint.requireEvaluationFingerprint("Open-response question fingerprint")
        knowledgeNodeReferenceFingerprint.requireEvaluationFingerprint(
            "Open-response knowledge-node reference",
        )
        knowledgeManifestFingerprint.requireEvaluationFingerprint(
            "Open-response knowledge manifest",
        )
        require(knowledgeActivationGeneration > 0L) {
            "Open-response knowledge activation generation must be positive"
        }
        return CanonicalSha256("open-response-knowledge-scope-v1")
            .field("questionFingerprint", questionFingerprint)
            .field("knowledgeNodeReference", knowledgeNodeReferenceFingerprint)
            .field("knowledgeManifest", knowledgeManifestFingerprint)
            .field("knowledgeActivationGeneration", knowledgeActivationGeneration)
            .finish()
    }

    /** Immutable digest of the complete, host-validated qualitative model output. */
    fun candidateOutput(output: TutorOpenResponseEvaluationCandidateOutput): String {
        val digest =
            CanonicalSha256("open-response-evaluation-candidate-output-v1")
                .field("evaluationSchemaVersion", output.evaluationSchemaVersion)
                .field("requestVersion", output.requestVersion)
                .field("idempotencyKey", output.idempotencyKey)
                .field("caseFingerprint", output.binding.caseFingerprint)
                .field("sessionFingerprint", output.binding.sessionFingerprint)
                .field("questionFingerprint", output.binding.questionFingerprint)
                .field("answerFingerprint", output.binding.answerFingerprint)
                .field("rubricFingerprint", output.binding.rubricFingerprint)
                .field("subject", output.subject.name)
                .field("outcome", output.outcome.name)
                .field("evaluator", output.evaluator.evaluator.name)
                .field("evaluatorPolicyFingerprint", output.evaluator.policyFingerprint)
                .field("evidenceFingerprint", output.evidenceFingerprint)
                .field("modelVersion", output.modelVersion)
                .field("knowledgeEffectCount", output.knowledgeEffects.size)
        output.knowledgeEffects.forEachIndexed { index, effect ->
            digest
                .field("knowledgeEffectRef[$index]", effect.refFingerprint)
                .field("knowledgeEffectRole[$index]", effect.role.name)
        }
        return digest.finish()
    }
}

/**
 * A provider-authored proposal. It is not a learning event, persistence command, mastery update,
 * confidence, or evidence weight.
 */
@Serializable
@SerialName("tutor_open_response_evaluation_candidate")
class TutorOpenResponseEvaluationCandidateOutput internal constructor(
    val evaluationSchemaVersion: Int,
    val requestVersion: Long,
    val idempotencyKey: String,
    val binding: OpenResponseEvaluationBinding,
    val subject: SubjectKind,
    val outcome: OpenResponseEvaluationOutcome,
    @SerialName("knowledgeEffects")
    private val storedKnowledgeEffects: List<OpenResponseKnowledgeEffect>,
    val evaluator: OpenResponseEvaluatorBinding,
    val evidenceFingerprint: String,
    val modelVersion: String,
) : ModelTaskOutput {
    val knowledgeEffects: List<OpenResponseKnowledgeEffect>
        get() = storedKnowledgeEffects.toList()

    init {
        require(evaluationSchemaVersion == CURRENT_EVALUATION_SCHEMA_VERSION) {
            "Unsupported open-response evaluation candidate schema"
        }
        require(requestVersion >= 0) {
            "Open-response evaluation candidate version must not be negative"
        }
        idempotencyKey.requireEvaluationFingerprint("Open-response candidate idempotency key")
        require(subject != SubjectKind.GENERAL) {
            "Open-response evaluation candidate requires one high-school subject"
        }
        require(storedKnowledgeEffects.size <= TutorOpenResponseEvaluationInput.MAX_TEACHING_GUIDANCE) {
            "Open-response evaluation candidate effects exceed their budget"
        }
        require(
            storedKnowledgeEffects
                .map(OpenResponseKnowledgeEffect::refFingerprint)
                .distinct()
                .size == storedKnowledgeEffects.size,
        ) {
            "Open-response evaluation candidate effects must be unique"
        }
        evidenceFingerprint.requireEvaluationFingerprint(
            "Open-response candidate evidence",
        )
        modelVersion.requireSafeModelText(
            label = "Open-response evaluator model version",
            maxChars = MAX_MODEL_VERSION_CHARS,
            allowLineBreaks = false,
        )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is TutorOpenResponseEvaluationCandidateOutput &&
            evaluationSchemaVersion == other.evaluationSchemaVersion &&
            requestVersion == other.requestVersion &&
            idempotencyKey == other.idempotencyKey &&
            binding == other.binding &&
            subject == other.subject &&
            outcome == other.outcome &&
            knowledgeEffects == other.knowledgeEffects &&
            evaluator == other.evaluator &&
            evidenceFingerprint == other.evidenceFingerprint &&
            modelVersion == other.modelVersion

    override fun hashCode(): Int {
        var result = evaluationSchemaVersion
        result = 31 * result + requestVersion.hashCode()
        result = 31 * result + idempotencyKey.hashCode()
        result = 31 * result + binding.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + knowledgeEffects.hashCode()
        result = 31 * result + evaluator.hashCode()
        result = 31 * result + evidenceFingerprint.hashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }

    companion object {
        const val CURRENT_EVALUATION_SCHEMA_VERSION: Int = 1
    }
}

/** Strict adapter between untrusted provider JSON and a local candidate-only model task output. */
object OpenResponseEvaluationModelTaskProtocol {
    fun decodeProviderDecision(
        input: TutorOpenResponseEvaluationInput,
        providerJson: String,
        modelVersion: String,
    ): TutorOpenResponseEvaluationCandidateOutput {
        val issuedRequest = input.requireIssuedAuthorization()
        val decision =
            OpenResponseEvaluationJsonCodec.decodeDecision(
                value = providerJson,
                issuedRequest = issuedRequest,
            )
        return TutorOpenResponseEvaluationCandidateOutput(
            evaluationSchemaVersion =
                TutorOpenResponseEvaluationCandidateOutput.CURRENT_EVALUATION_SCHEMA_VERSION,
            requestVersion = input.requestVersion,
            idempotencyKey = input.idempotencyKey,
            binding = decision.binding,
            subject = decision.subject,
            outcome = decision.outcome,
            storedKnowledgeEffects = decision.knowledgeEffects,
            evaluator = decision.evaluator,
            evidenceFingerprint = decision.evidenceFingerprint,
            modelVersion = modelVersion,
        ).also { candidate ->
            requireValidCompletion(input, candidate)
        }
    }

    fun requireValidCompletion(
        input: TutorOpenResponseEvaluationInput,
        output: TutorOpenResponseEvaluationCandidateOutput,
    ) {
        val request = input.requireIssuedAuthorization().requireIssuedRequest()
        require(
            output.evaluationSchemaVersion ==
                TutorOpenResponseEvaluationCandidateOutput.CURRENT_EVALUATION_SCHEMA_VERSION,
        ) {
            "Unsupported open-response evaluation candidate schema"
        }
        require(output.requestVersion == input.requestVersion) {
            "Open-response evaluation candidate is stale"
        }
        require(output.idempotencyKey == input.idempotencyKey) {
            "Open-response evaluation candidate crossed its idempotent operation"
        }
        OpenResponseEvaluationDecision.verified(
            request = request,
            binding = output.binding,
            subject = output.subject,
            outcome = output.outcome,
            knowledgeEffects = output.knowledgeEffects,
            evaluator = output.evaluator,
            evidenceFingerprint = output.evidenceFingerprint,
        )
    }
}

private fun QuestionDocument.openResponseTaskSnapshot(): QuestionDocument =
    openResponseTaskJson.decodeFromString(
        openResponseTaskJson.encodeToString(this),
    )

private fun String.requireEvaluationFingerprint(label: String) {
    require(length == SHA_256_HEX_CHARS && all(Char::isLowerHexDigit)) {
        "$label must be a lowercase SHA-256 value"
    }
}

private val openResponseTaskJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    useAlternativeNames = false
    allowSpecialFloatingPointValues = false
}
