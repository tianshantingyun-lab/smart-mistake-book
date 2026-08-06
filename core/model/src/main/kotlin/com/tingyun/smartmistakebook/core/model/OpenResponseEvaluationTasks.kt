package com.tingyun.smartmistakebook.core.model

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Host-owned, content-free bindings for evaluating one open response.
 *
 * The actual question, answer, rubric, learner identity, and knowledge-base text are deliberately
 * absent. Their owning components disclose them through a separately authorized model channel and
 * bind that disclosure to these fingerprints.
 */
@Serializable
data class OpenResponseEvaluationBinding(
    val caseFingerprint: String,
    val sessionFingerprint: String,
    val questionFingerprint: String,
    val answerFingerprint: String,
    val rubricFingerprint: String,
) {
    init {
        caseFingerprint.requireOpenResponseFingerprint("Evaluation case")
        sessionFingerprint.requireOpenResponseFingerprint("Evaluation session")
        questionFingerprint.requireOpenResponseFingerprint("Evaluation question")
        answerFingerprint.requireOpenResponseFingerprint("Evaluation answer")
        rubricFingerprint.requireOpenResponseFingerprint("Evaluation rubric")
    }
}

@Serializable
enum class OpenResponseEvaluatorKind {
    RUBRIC,
}

/**
 * The exact evaluator policy selected by the host.
 *
 * The evaluator is a closed protocol value and the policy is a fingerprint. Arbitrary strings are
 * deliberately excluded so this envelope cannot be used to smuggle learner data or scoring values.
 */
@Serializable
class OpenResponseEvaluatorBinding internal constructor(
    val evaluator: OpenResponseEvaluatorKind,
    val policyFingerprint: String,
) {
    init {
        policyFingerprint.requireOpenResponseFingerprint("Evaluator policy")
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OpenResponseEvaluatorBinding &&
            evaluator == other.evaluator &&
            policyFingerprint == other.policyFingerprint

    override fun hashCode(): Int =
        31 * evaluator.hashCode() + policyFingerprint.hashCode()
}

/**
 * An opaque, version-bound knowledge reference admitted by the host for this exact question.
 *
 * Repeating [subject] and [questionFingerprint] is intentional: it makes a stale or cross-question
 * scope fail before the request can leave the process.
 */
@Serializable
data class OpenResponseKnowledgeScopeRef(
    val refFingerprint: String,
    val subject: SubjectKind,
    val questionFingerprint: String,
) {
    init {
        refFingerprint.requireOpenResponseFingerprint("Knowledge reference")
        require(subject != SubjectKind.GENERAL) {
            "Open-response knowledge references require one high-school subject"
        }
        questionFingerprint.requireOpenResponseFingerprint("Knowledge-reference question")
    }
}

/**
 * Content-free request envelope for one open-response evaluation.
 *
 * This type is not a model-task registry entry and grants no database or learning-event authority.
 * It only describes the opaque scope against which a later proposal must be checked.
 */
internal class OpenResponseEvaluationRequest private constructor(
    val schemaVersion: Int,
    val binding: OpenResponseEvaluationBinding,
    val subject: SubjectKind,
    knowledgeScope: List<OpenResponseKnowledgeScopeRef>,
    val evaluator: OpenResponseEvaluatorBinding,
    val evidenceFingerprint: String,
) {
    private val admittedKnowledgeScope = knowledgeScope.toList()

    /** A defensive copy; neither callers nor serialization can widen the admitted scope. */
    val knowledgeScope: List<OpenResponseKnowledgeScopeRef>
        get() = admittedKnowledgeScope.toList()

    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported open-response evaluation request schema"
        }
        require(subject != SubjectKind.GENERAL) {
            "Open-response evaluation requires one high-school subject"
        }
        require(admittedKnowledgeScope.size <= MAX_KNOWLEDGE_REFS) {
            "Open-response evaluation knowledge scope exceeds its budget"
        }
        require(
            admittedKnowledgeScope
                .map(OpenResponseKnowledgeScopeRef::refFingerprint)
                .distinct()
                .size == admittedKnowledgeScope.size,
        ) {
            "Open-response evaluation knowledge references must be unique"
        }
        require(admittedKnowledgeScope.all { it.subject == subject }) {
            "Open-response evaluation knowledge scope crossed a subject boundary"
        }
        require(
            admittedKnowledgeScope.all {
                it.questionFingerprint == binding.questionFingerprint
            },
        ) {
            "Open-response evaluation knowledge scope crossed a question boundary"
        }
        evidenceFingerprint.requireOpenResponseFingerprint("Evaluation evidence")
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OpenResponseEvaluationRequest &&
            schemaVersion == other.schemaVersion &&
            binding == other.binding &&
            subject == other.subject &&
            knowledgeScope == other.knowledgeScope &&
            evaluator == other.evaluator &&
            evidenceFingerprint == other.evidenceFingerprint

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + binding.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + knowledgeScope.hashCode()
        result = 31 * result + evaluator.hashCode()
        result = 31 * result + evidenceFingerprint.hashCode()
        return result
    }

    internal companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_KNOWLEDGE_REFS = 24

        fun issue(
            binding: OpenResponseEvaluationBinding,
            subject: SubjectKind,
            knowledgeScope: List<OpenResponseKnowledgeScopeRef>,
            evaluator: OpenResponseEvaluatorBinding,
            evidenceFingerprint: String,
        ): OpenResponseEvaluationRequest =
            OpenResponseEvaluationRequest(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                binding = binding,
                subject = subject,
                knowledgeScope = knowledgeScope.toList(),
                evaluator = evaluator,
                evidenceFingerprint = evidenceFingerprint,
            )
    }
}

internal fun interface OpenResponseEvaluationAuthorization {
    fun requireCurrent()
}

private class IssuedOpenResponseEvaluationRequestReference(
    issuedRequest: HostIssuedOpenResponseEvaluationRequest,
    val request: OpenResponseEvaluationRequest,
    val authorization: OpenResponseEvaluationAuthorization,
    queue: ReferenceQueue<HostIssuedOpenResponseEvaluationRequest>,
) : WeakReference<HostIssuedOpenResponseEvaluationRequest>(issuedRequest, queue) {
    val identityHashCode: Int = System.identityHashCode(issuedRequest)
}

/**
 * Process-local authority ledger for issued request identities.
 *
 * The request is bound to the exact capability instance, not to constructor arguments or a static
 * secret. Weak keys ensure abandoned requests do not become a process-lifetime retention source.
 */
private object IssuedOpenResponseEvaluationRequestRegistry {
    private val lock = Any()
    private val collectedRequests =
        ReferenceQueue<HostIssuedOpenResponseEvaluationRequest>()
    private val issuedRequests =
        hashMapOf<Int, MutableList<IssuedOpenResponseEvaluationRequestReference>>()

    fun register(
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
        request: OpenResponseEvaluationRequest,
        authorization: OpenResponseEvaluationAuthorization,
    ) {
        synchronized(lock) {
            removeCollectedRequests()
            val identityHashCode = System.identityHashCode(issuedRequest)
            val identityBucket =
                issuedRequests.getOrPut(identityHashCode) { mutableListOf() }
            check(
                identityBucket.none { it.get() === issuedRequest },
            ) {
                "Open-response evaluation authority was already registered"
            }
            identityBucket +=
                IssuedOpenResponseEvaluationRequestReference(
                    issuedRequest = issuedRequest,
                    request = request,
                    authorization = authorization,
                    queue = collectedRequests,
                )
        }
    }

    fun requireIssuedRequest(
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
    ): OpenResponseEvaluationRequest {
        val issued =
            synchronized(lock) {
                removeCollectedRequests()
                issuedRequests[System.identityHashCode(issuedRequest)]
                    ?.firstOrNull { it.get() === issuedRequest }
                    ?: throw IllegalArgumentException(
                        "Open-response evaluation request was not issued by its host authority",
                    )
            }
        issued.authorization.requireCurrent()
        return issued.request
    }

    private fun removeCollectedRequests() {
        while (true) {
            val collectedRequest =
                collectedRequests.poll() as? IssuedOpenResponseEvaluationRequestReference
                    ?: return
            val identityBucket =
                issuedRequests[collectedRequest.identityHashCode]
                    ?: continue
            identityBucket.remove(collectedRequest)
            if (identityBucket.isEmpty()) {
                issuedRequests.remove(collectedRequest.identityHashCode)
            }
        }
    }
}

/**
 * An unforgeable-in-JSON capability proving that the host issued this exact evaluation scope.
 *
 * A provider can echo wire fields, but it cannot construct this type. Restoring an encoded request
 * also requires an already-issued capability and returns that same capability after exact matching.
 */
class HostIssuedOpenResponseEvaluationRequest private constructor() {
    val knowledgeScope: List<OpenResponseKnowledgeScopeRef>
        get() = requireIssuedRequest().knowledgeScope

    internal fun requireIssuedRequest(): OpenResponseEvaluationRequest =
        IssuedOpenResponseEvaluationRequestRegistry.requireIssuedRequest(this)

    internal companion object {
        fun issue(
            request: OpenResponseEvaluationRequest,
            authorization: OpenResponseEvaluationAuthorization,
            ownerKey: OpenResponseEvaluationOwnerKey,
        ): HostIssuedOpenResponseEvaluationRequest {
            check(ownerKey === OpenResponseEvaluationOwnerKey.INSTANCE) {
                "Open-response request issuance requires the model owner key"
            }
            authorization.requireCurrent()
            val issuedRequest = HostIssuedOpenResponseEvaluationRequest()
            IssuedOpenResponseEvaluationRequestRegistry.register(
                issuedRequest = issuedRequest,
                request = request,
                authorization = authorization,
            )
            return issuedRequest
        }
    }
}

/**
 * Module-owned issuer for open-response evaluation authority roots.
 *
 * Production callers receive only issued requests; creating the authority itself remains inside
 * the model boundary so feature or provider modules cannot mint capabilities.
 */
internal class OpenResponseEvaluationHostAuthority private constructor(
    private val ownerKey: OpenResponseEvaluationOwnerKey,
) {
    internal fun issue(
        binding: OpenResponseEvaluationBinding,
        subject: SubjectKind,
        knowledgeScope: List<OpenResponseKnowledgeScopeRef>,
        evaluator: OpenResponseEvaluatorKind,
        policyFingerprint: String,
        evidenceFingerprint: String,
        authorization: OpenResponseEvaluationAuthorization,
    ): HostIssuedOpenResponseEvaluationRequest {
        check(ownerKey === OpenResponseEvaluationOwnerKey.INSTANCE) {
            "Open-response authority requires the model owner key"
        }
        authorization.requireCurrent()
        val request =
            OpenResponseEvaluationRequest.issue(
                binding = binding,
                subject = subject,
                knowledgeScope = knowledgeScope.toList(),
                evaluator =
                    OpenResponseEvaluatorBinding(
                        evaluator = evaluator,
                        policyFingerprint = policyFingerprint,
                    ),
                evidenceFingerprint = evidenceFingerprint,
            )
        return HostIssuedOpenResponseEvaluationRequest.issue(
            request = request,
            authorization = authorization,
            ownerKey = ownerKey,
        )
    }

    internal companion object {
        fun create(
            ownerKey: OpenResponseEvaluationOwnerKey,
        ): OpenResponseEvaluationHostAuthority {
            check(ownerKey === OpenResponseEvaluationOwnerKey.INSTANCE) {
                "Open-response authority creation requires the model owner key"
            }
            return OpenResponseEvaluationHostAuthority(ownerKey)
        }
    }
}

@Serializable
enum class OpenResponseEvaluationOutcome {
    CORRECT,
    INCORRECT,
    ASSISTED_CORRECT,
    UNSCORABLE,
}

/**
 * A qualitative explanation of a knowledge reference's role in this evaluation.
 *
 * These values are not mastery states and intentionally carry no score, confidence, or weight.
 */
@Serializable
enum class OpenResponseKnowledgeRole {
    SUPPORTED_CORRECTNESS,
    LOCATED_GAP,
    REQUIRED_ASSISTANCE,
    COULD_NOT_ASSESS,
}

@Serializable
data class OpenResponseKnowledgeEffect(
    val refFingerprint: String,
    val role: OpenResponseKnowledgeRole,
) {
    init {
        refFingerprint.requireOpenResponseFingerprint("Evaluated knowledge reference")
    }
}

/**
 * A proposal validated against one host-owned request.
 *
 * It is deliberately not a learning event and has no weight, persistence command, or event id.
 * The private constructor prevents another production module from bypassing the scoped parser.
 */
class OpenResponseEvaluationDecision private constructor(
    val binding: OpenResponseEvaluationBinding,
    val subject: SubjectKind,
    val outcome: OpenResponseEvaluationOutcome,
    knowledgeEffects: List<OpenResponseKnowledgeEffect>,
    val evaluator: OpenResponseEvaluatorBinding,
    val evidenceFingerprint: String,
) {
    private val admittedKnowledgeEffects = knowledgeEffects.toList()

    val knowledgeEffects: List<OpenResponseKnowledgeEffect>
        get() = admittedKnowledgeEffects.toList()

    init {
        require(subject != SubjectKind.GENERAL) {
            "Open-response evaluation decision requires one high-school subject"
        }
        require(admittedKnowledgeEffects.size <= OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS) {
            "Open-response evaluation effects exceed their budget"
        }
        require(
            admittedKnowledgeEffects
                .map(OpenResponseKnowledgeEffect::refFingerprint)
                .distinct()
                .size == admittedKnowledgeEffects.size,
        ) {
            "Open-response evaluation effects must use unique knowledge references"
        }
        evidenceFingerprint.requireOpenResponseFingerprint("Evaluated evidence")
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is OpenResponseEvaluationDecision &&
            binding == other.binding &&
            subject == other.subject &&
            outcome == other.outcome &&
            knowledgeEffects == other.knowledgeEffects &&
            evaluator == other.evaluator &&
            evidenceFingerprint == other.evidenceFingerprint

    override fun hashCode(): Int {
        var result = binding.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + knowledgeEffects.hashCode()
        result = 31 * result + evaluator.hashCode()
        result = 31 * result + evidenceFingerprint.hashCode()
        return result
    }

    internal companion object {
        fun verified(
            request: OpenResponseEvaluationRequest,
            binding: OpenResponseEvaluationBinding,
            subject: SubjectKind,
            outcome: OpenResponseEvaluationOutcome,
            knowledgeEffects: List<OpenResponseKnowledgeEffect>,
            evaluator: OpenResponseEvaluatorBinding,
            evidenceFingerprint: String,
        ): OpenResponseEvaluationDecision {
            require(binding == request.binding) {
                "Open-response evaluation decision crossed its host binding"
            }
            require(subject == request.subject) {
                "Open-response evaluation decision crossed its subject boundary"
            }
            require(evaluator == request.evaluator) {
                "Open-response evaluation decision changed its evaluator"
            }
            require(evidenceFingerprint == request.evidenceFingerprint) {
                "Open-response evaluation decision changed its evidence"
            }
            require(knowledgeEffects.size <= OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS) {
                "Open-response evaluation effects exceed their budget"
            }
            require(
                knowledgeEffects.map(OpenResponseKnowledgeEffect::refFingerprint).distinct().size ==
                    knowledgeEffects.size,
            ) {
                "Open-response evaluation effects must use unique knowledge references"
            }
            val admittedRefs =
                request.knowledgeScope
                    .mapTo(hashSetOf(), OpenResponseKnowledgeScopeRef::refFingerprint)
            require(knowledgeEffects.all { it.refFingerprint in admittedRefs }) {
                "Open-response evaluation used a knowledge reference outside the question scope"
            }
            return OpenResponseEvaluationDecision(
                binding = binding,
                subject = subject,
                outcome = outcome,
                knowledgeEffects = knowledgeEffects.toList(),
                evaluator = evaluator,
                evidenceFingerprint = evidenceFingerprint,
            )
        }
    }
}

@Serializable
private data class OpenResponseEvaluationRequestWire(
    val schemaVersion: Int,
    val binding: OpenResponseEvaluationBinding,
    val subject: SubjectKind,
    @SerialName("knowledgeScope")
    val knowledgeScope: List<OpenResponseKnowledgeScopeRef>,
    val evaluator: OpenResponseEvaluatorBinding,
    val evidenceFingerprint: String,
)

@Serializable
private data class OpenResponseEvaluationDecisionWire(
    val schemaVersion: Int,
    val binding: OpenResponseEvaluationBinding,
    val subject: SubjectKind,
    val outcome: OpenResponseEvaluationOutcome,
    val knowledgeEffects: List<OpenResponseKnowledgeEffect>,
    val evaluator: OpenResponseEvaluatorBinding,
    val evidenceFingerprint: String,
)

/**
 * Strict codec for the content-free evaluation protocol.
 *
 * Kotlin serialization supplies the schema and enum checks. [StrictOpenResponseJson] runs first
 * because a decoded JSON object can no longer reveal duplicate keys. The final request comparison
 * is the authority check: a well-formed but stale or cross-question proposal is still rejected.
 */
object OpenResponseEvaluationJsonCodec {
    const val MAX_JSON_CHARS = 16_384

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        useAlternativeNames = false
        allowSpecialFloatingPointValues = false
    }

    fun encodeRequest(issuedRequest: HostIssuedOpenResponseEvaluationRequest): String {
        val request = issuedRequest.requireIssuedRequest()
        return json.encodeToString(request.toWire()).boundedAndStrict()
    }

    /**
     * Restores only a request that exactly matches authority the host already holds.
     *
     * This deliberately returns [expected] instead of promoting decoded JSON into a new capability.
     */
    fun restoreIssuedRequest(
        value: String,
        expected: HostIssuedOpenResponseEvaluationRequest,
    ): HostIssuedOpenResponseEvaluationRequest {
        value.requireStrictOpenResponseJson()
        val decoded = json.decodeFromString<OpenResponseEvaluationRequestWire>(value).toRequest()
        require(decoded == expected.requireIssuedRequest()) {
            "Encoded open-response evaluation request did not match its host-issued authority"
        }
        return expected
    }

    fun decodeDecision(
        value: String,
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
    ): OpenResponseEvaluationDecision {
        value.requireStrictOpenResponseJson()
        val wire = json.decodeFromString<OpenResponseEvaluationDecisionWire>(value)
        require(wire.schemaVersion == OpenResponseEvaluationRequest.CURRENT_SCHEMA_VERSION) {
            "Unsupported open-response evaluation decision schema"
        }
        return OpenResponseEvaluationDecision.verified(
            request = issuedRequest.requireIssuedRequest(),
            binding = wire.binding,
            subject = wire.subject,
            outcome = wire.outcome,
            knowledgeEffects = wire.knowledgeEffects.toList(),
            evaluator = wire.evaluator,
            evidenceFingerprint = wire.evidenceFingerprint,
        )
    }

    fun decodeDecisionOrNull(
        value: String?,
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
    ): OpenResponseEvaluationDecision? =
        value?.let { runCatching { decodeDecision(it, issuedRequest) }.getOrNull() }

    fun encodeDecision(
        decision: OpenResponseEvaluationDecision,
        issuedRequest: HostIssuedOpenResponseEvaluationRequest,
    ): String {
        val revalidated =
            OpenResponseEvaluationDecision.verified(
                request = issuedRequest.requireIssuedRequest(),
                binding = decision.binding,
                subject = decision.subject,
                outcome = decision.outcome,
                knowledgeEffects = decision.knowledgeEffects,
                evaluator = decision.evaluator,
                evidenceFingerprint = decision.evidenceFingerprint,
            )
        return json.encodeToString(
            OpenResponseEvaluationDecisionWire(
                schemaVersion = OpenResponseEvaluationRequest.CURRENT_SCHEMA_VERSION,
                binding = revalidated.binding,
                subject = revalidated.subject,
                outcome = revalidated.outcome,
                knowledgeEffects = revalidated.knowledgeEffects,
                evaluator = revalidated.evaluator,
                evidenceFingerprint = revalidated.evidenceFingerprint,
            ),
        ).boundedAndStrict()
    }

    private fun String.boundedAndStrict(): String = also {
        it.requireStrictOpenResponseJson()
    }

    private fun String.requireStrictOpenResponseJson() {
        require(length <= MAX_JSON_CHARS) {
            "Open-response evaluation JSON exceeds its budget"
        }
        StrictOpenResponseJson(this).validate()
    }
}

private fun OpenResponseEvaluationRequest.toWire(): OpenResponseEvaluationRequestWire =
    OpenResponseEvaluationRequestWire(
        schemaVersion = schemaVersion,
        binding = binding,
        subject = subject,
        knowledgeScope = knowledgeScope,
        evaluator = evaluator,
        evidenceFingerprint = evidenceFingerprint,
    )

private fun OpenResponseEvaluationRequestWire.toRequest(): OpenResponseEvaluationRequest {
    require(schemaVersion == OpenResponseEvaluationRequest.CURRENT_SCHEMA_VERSION) {
        "Unsupported open-response evaluation request schema"
    }
    return OpenResponseEvaluationRequest.issue(
        binding = binding,
        subject = subject,
        knowledgeScope = knowledgeScope.toList(),
        evaluator = evaluator,
        evidenceFingerprint = evidenceFingerprint,
    )
}

/**
 * A small validating scanner used only to preserve information the regular JSON decoder discards.
 *
 * It accepts standard JSON syntax, rejects duplicate keys at every depth, and bounds nesting,
 * strings, arrays, and objects before allocating serialization objects.
 */
private class StrictOpenResponseJson(
    private val source: String,
) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        readValue(depth = 0)
        skipWhitespace()
        require(index == source.length) { "Open-response evaluation JSON has trailing content" }
    }

    private fun readValue(depth: Int) {
        require(depth <= MAX_DEPTH) { "Open-response evaluation JSON is too deeply nested" }
        require(index < source.length) { "Open-response evaluation JSON ended unexpectedly" }
        when (source[index]) {
            '{' -> readObject(depth + 1)
            '[' -> readArray(depth + 1)
            '"' -> readString(MAX_VALUE_CHARS)
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            '-', in '0'..'9' -> readNumber()
            else -> throw IllegalArgumentException("Open-response evaluation JSON is malformed")
        }
    }

    private fun readObject(depth: Int) {
        index += 1
        skipWhitespace()
        if (consumeIf('}')) return
        val keys = hashSetOf<String>()
        var entries = 0
        while (true) {
            require(index < source.length && source[index] == '"') {
                "Open-response evaluation JSON object key is malformed"
            }
            val key = readString(MAX_KEY_CHARS)
            require(keys.add(key)) {
                "Open-response evaluation JSON contains a duplicate key"
            }
            entries += 1
            require(entries <= MAX_OBJECT_ENTRIES) {
                "Open-response evaluation JSON object exceeds its budget"
            }
            skipWhitespace()
            require(consumeIf(':')) { "Open-response evaluation JSON is missing ':'" }
            skipWhitespace()
            readValue(depth)
            skipWhitespace()
            when {
                consumeIf('}') -> return
                consumeIf(',') -> skipWhitespace()
                else -> throw IllegalArgumentException(
                    "Open-response evaluation JSON object is malformed",
                )
            }
        }
    }

    private fun readArray(depth: Int) {
        index += 1
        skipWhitespace()
        if (consumeIf(']')) return
        var entries = 0
        while (true) {
            readValue(depth)
            entries += 1
            require(entries <= MAX_ARRAY_ENTRIES) {
                "Open-response evaluation JSON array exceeds its budget"
            }
            skipWhitespace()
            when {
                consumeIf(']') -> return
                consumeIf(',') -> skipWhitespace()
                else -> throw IllegalArgumentException(
                    "Open-response evaluation JSON array is malformed",
                )
            }
        }
    }

    private fun readString(maxChars: Int): String {
        require(consumeIf('"')) { "Open-response evaluation JSON string is malformed" }
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> result.append(readEscapedCharacter())
                character.code < 0x20 ->
                    throw IllegalArgumentException(
                        "Open-response evaluation JSON contains a control character",
                    )
                else -> result.append(character)
            }
            require(result.length <= maxChars) {
                "Open-response evaluation JSON string exceeds its budget"
            }
        }
        throw IllegalArgumentException("Open-response evaluation JSON string is unfinished")
    }

    private fun readEscapedCharacter(): Char {
        require(index < source.length) { "Open-response evaluation JSON escape is unfinished" }
        return when (val escape = source[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> throw IllegalArgumentException(
                "Open-response evaluation JSON has an invalid escape: $escape",
            )
        }
    }

    private fun readUnicodeEscape(): Char {
        require(index + UNICODE_HEX_CHARS <= source.length) {
            "Open-response evaluation JSON unicode escape is unfinished"
        }
        var value = 0
        repeat(UNICODE_HEX_CHARS) {
            value = (value shl 4) or source[index++].hexValue()
        }
        return value.toChar()
    }

    private fun readNumber() {
        val start = index
        consumeIf('-')
        require(index < source.length) { "Open-response evaluation JSON number is unfinished" }
        if (consumeIf('0')) {
            require(index >= source.length || source[index] !in '0'..'9') {
                "Open-response evaluation JSON number has a leading zero"
            }
        } else {
            require(index < source.length && source[index] in '1'..'9') {
                "Open-response evaluation JSON number is malformed"
            }
            while (index < source.length && source[index] in '0'..'9') index += 1
        }
        require(index - start <= MAX_NUMBER_CHARS) {
            "Open-response evaluation JSON number exceeds its budget"
        }
        require(index >= source.length || source[index] !in NON_INTEGER_NUMBER_MARKERS) {
            "Open-response evaluation JSON accepts canonical integers only"
        }
    }

    private fun readLiteral(literal: String) {
        require(source.regionMatches(index, literal, 0, literal.length)) {
            "Open-response evaluation JSON literal is malformed"
        }
        index += literal.length
    }

    private fun consumeIf(expected: Char): Boolean =
        if (index < source.length && source[index] == expected) {
            index += 1
            true
        } else {
            false
        }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) index += 1
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        in 'A'..'F' -> code - 'A'.code + 10
        else -> throw IllegalArgumentException(
            "Open-response evaluation JSON unicode escape is malformed",
        )
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_OBJECT_ENTRIES = 16
        const val MAX_ARRAY_ENTRIES = OpenResponseEvaluationRequest.MAX_KNOWLEDGE_REFS
        const val MAX_KEY_CHARS = 64
        const val MAX_VALUE_CHARS = 256
        const val MAX_NUMBER_CHARS = 10
        const val UNICODE_HEX_CHARS = 4
        val JSON_WHITESPACE = charArrayOf(' ', '\t', '\r', '\n')
        val NON_INTEGER_NUMBER_MARKERS = charArrayOf('.', 'e', 'E')
    }
}

private val OPEN_RESPONSE_FINGERPRINT = Regex("[a-f0-9]{64}")

private fun String.requireOpenResponseFingerprint(label: String) {
    require(OPEN_RESPONSE_FINGERPRINT.matches(this)) {
        "$label fingerprint must be exactly 64 lowercase hexadecimal characters"
    }
}
