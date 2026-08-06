package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.TutorMasteryContext
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class TutorMasteryContextReadSnapshot(
    val repository: TutorMasteryContextRepository? = null,
    val request: TutorMasteryContextRequest? = null,
    val loading: Boolean = false,
    val context: TutorMasteryContext = TutorMasteryContext.EMPTY,
)

/**
 * Owns one latest-only read. A generation guard rejects late results even if an implementation
 * catches coroutine cancellation internally.
 */
internal class TutorMasteryContextReadCoordinator(
    private val scope: CoroutineScope,
    private val publish: (TutorMasteryContextReadSnapshot) -> Unit,
) {
    private var generation = 0L
    private var readJob: Job? = null

    fun refresh(
        repository: TutorMasteryContextRepository?,
        request: TutorMasteryContextRequest?,
    ) {
        val readGeneration = ++generation
        readJob?.cancel()
        if (repository == null || request == null) {
            readJob = null
            publish(
                TutorMasteryContextReadSnapshot(
                    repository = repository,
                    request = request,
                ),
            )
            return
        }
        publish(
            TutorMasteryContextReadSnapshot(
                repository = repository,
                request = request,
                loading = true,
            ),
        )
        readJob = scope.launch {
            val context = try {
                repository.read(request).boundedTo(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TutorMasteryContext.EMPTY
            }
            if (readGeneration == generation) {
                publish(
                    TutorMasteryContextReadSnapshot(
                        repository = repository,
                        request = request,
                        context = context,
                    ),
                )
            }
        }
    }

    fun close() {
        generation++
        readJob?.cancel()
        readJob = null
    }
}

/**
 * One question-bound, latest-only read used immediately before an external tutor recovery.
 *
 * Unlike the presentation snapshot above, this reader never caches a mastery projection. Every
 * call reaches the repository again. A newer call or [close] invalidates an older suspended read,
 * including repositories that return after swallowing cancellation.
 */
internal class TutorMasteryRecoveryReader(
    private val key: TutorMasteryRecoveryReadKey,
) {
    private var generation = 0L
    private var closed = false

    suspend fun read(
        repository: TutorMasteryContextRepository?,
        question: TutorQuestionContext,
    ): TutorMasteryContext? {
        if (closed || question.toTutorMasteryRecoveryReadKey() != key) return null
        val readGeneration = ++generation
        val request = key.request
        val context = if (repository == null || request == null) {
            TutorMasteryContext.EMPTY
        } else {
            try {
                repository.read(request).boundedTo(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                TutorMasteryContext.EMPTY
            }
        }
        currentCoroutineContext().ensureActive()
        return context.takeIf {
            !closed &&
                readGeneration == generation &&
                question.toTutorMasteryRecoveryReadKey() == key
        }
    }

    fun close() {
        closed = true
        generation++
    }
}

/**
 * The complete process-local authority which must still be current before a recovered request may
 * leave the device. It deliberately carries UI/runtime generations, not persisted model output.
 */
internal data class TutorRecoveryRuntimeAuthority(
    val question: TutorMasteryRecoveryReadKey,
    val questionDocumentFingerprint: String,
    val explanationMode: TutorExplanationMode,
    val effectiveExplanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val learningWritesAllowed: Boolean,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val conversationGeneration: Long,
    val activeOwnerEpoch: Long,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val providerAuthorityGeneration: Long,
    val executionLocation: ModelExecutionLocation,
    val taskKind: ModelTaskKind,
    val approvedAtEpochMillis: Long,
) {
    init {
        require(modeVersion >= 0)
        require(learningWritePermissionVersion >= 0)
        require(cycleOrdinal > 0)
        require(turnOrdinal > 0)
        require(conversationGeneration >= 0)
        require(activeOwnerEpoch >= 0)
        require(questionDocumentFingerprint.matches(SHA_256))
        require(providerId.isNotBlank())
        require(modelId.isNotBlank())
        require(providerConfigurationVersion.isNotBlank())
        require(providerAuthorityGeneration >= 0)
        require(executionLocation != ModelExecutionLocation.UNAVAILABLE)
        require(approvedAtEpochMillis >= 0)
        require(
            taskKind == ModelTaskKind.TUTOR_PLAN ||
                taskKind == ModelTaskKind.TUTOR_RESPOND,
        )
    }

    private companion object {
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

internal data class TutorRecoveryOperation(
    val taskKind: ModelTaskKind,
    val sourceRequest: ModelTaskRequest,
    val taskStateVersion: Long,
) {
    val requestId: String
        get() = sourceRequest.requestId

    init {
        require(
            taskKind == ModelTaskKind.TUTOR_PLAN ||
                taskKind == ModelTaskKind.TUTOR_RESPOND,
        )
        require(sourceRequest.input.kind == taskKind)
        require(taskStateVersion >= 0)
    }
}

internal class TutorRecoveryAuthorityToken private constructor(
    internal val runtime: TutorRecoveryRuntimeAuthority,
    internal val operation: TutorRecoveryOperation,
    internal val authoritySessionId: String,
    internal val generation: Long,
) {
    companion object {
        internal fun issue(
            runtime: TutorRecoveryRuntimeAuthority,
            operation: TutorRecoveryOperation,
            authoritySessionId: String,
            generation: Long,
        ) = TutorRecoveryAuthorityToken(
            runtime,
            operation,
            authoritySessionId,
            generation,
        )
    }
}

/**
 * Compact pending permit for one locally rebuilt request. It intentionally stores only
 * fingerprints and bounded runtime authority; the potentially large request payload remains
 * owned by Room and the executing coroutine.
 */
internal data class TutorLocalRecoveryAdmission(
    val recoveredRequestId: String,
    val recoveredRequestFingerprint: String,
    val authoritySessionId: String,
    val tokenGeneration: Long,
    val runtime: TutorRecoveryRuntimeAuthority,
    val sourceTaskKind: ModelTaskKind,
    val sourceRequestId: String,
    val sourceRequestFingerprint: String,
    val sourceTaskStateVersion: Long,
) {
    init {
        require(recoveredRequestId.isNotBlank())
        require(recoveredRequestFingerprint.matches(SHA_256))
        require(authoritySessionId.isNotBlank())
        require(tokenGeneration > 0)
        require(sourceRequestId.isNotBlank())
        require(sourceRequestFingerprint.matches(SHA_256))
        require(sourceTaskStateVersion >= 0)
    }

    internal fun belongsTo(token: TutorRecoveryAuthorityToken): Boolean =
        authoritySessionId == token.authoritySessionId &&
            tokenGeneration == token.generation &&
            runtime == token.runtime &&
            sourceTaskKind == token.operation.taskKind &&
            sourceRequestId == token.operation.requestId &&
            sourceRequestFingerprint == ModelTaskFingerprint.of(token.operation.sourceRequest) &&
            sourceTaskStateVersion == token.operation.taskStateVersion

    private companion object {
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Durable proof that one exact local recovery passed its final runtime/source-state check.
 *
 * This is an integrity-bound presentation permit, not a secret or a model authorization. The
 * route stores its encoded form in a bounded private ledger; saved state carries only a compact
 * lookup hint. Every projection still re-checks the durable recovered request, its source
 * snapshot, and the current question before showing it.
 */
@ConsistentCopyVisibility
internal data class TutorLocalRecoveryPresentationCredential private constructor(
    val recoveredRequestId: String,
    val recoveredRequestFingerprint: String,
    val authoritySessionId: String,
    val tokenGeneration: Long,
    val sourceTaskKind: ModelTaskKind,
    val sourceRequestId: String,
    val sourceRequestFingerprint: String,
    val sourceTaskStateVersion: Long,
    val presentationScopeKey: String,
    val questionDocumentFingerprint: String,
    val effectiveExplanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val learningWritesAllowed: Boolean,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val conversationGeneration: Long,
    val activeOwnerEpoch: Long,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val providerAuthorityGeneration: Long,
) {
    init {
        require(recoveredRequestId.isValidCredentialText())
        require(recoveredRequestFingerprint.matches(SHA_256))
        require(authoritySessionId.isValidCredentialText())
        require(tokenGeneration > 0)
        require(
            sourceTaskKind == ModelTaskKind.TUTOR_PLAN ||
                sourceTaskKind == ModelTaskKind.TUTOR_RESPOND,
        )
        require(sourceRequestId.isValidCredentialText())
        require(sourceRequestFingerprint.matches(SHA_256))
        require(sourceTaskStateVersion >= 0)
        require(presentationScopeKey.matches(SHA_256))
        require(questionDocumentFingerprint.matches(SHA_256))
        require(modeVersion >= 0)
        require(learningWritePermissionVersion >= 0)
        require(cycleOrdinal > 0)
        require(turnOrdinal > 0)
        require(conversationGeneration >= 0)
        require(activeOwnerEpoch >= 0)
        require(providerId.isValidCredentialText())
        require(modelId.isValidCredentialText())
        require(providerConfigurationVersion.isValidCredentialText())
        require(providerAuthorityGeneration >= 0)
    }

    fun encode(): String {
        val signedFields = listOf(CREDENTIAL_VERSION) + canonicalFields()
        return encodeFields(signedFields + signedFields.fingerprint())
    }

    private fun canonicalFields(): List<String> = listOf(
        recoveredRequestId,
        recoveredRequestFingerprint,
        authoritySessionId,
        tokenGeneration.toString(),
        sourceTaskKind.name,
        sourceRequestId,
        sourceRequestFingerprint,
        sourceTaskStateVersion.toString(),
        presentationScopeKey,
        questionDocumentFingerprint,
        effectiveExplanationMode.name,
        modeVersion.toString(),
        learningWritePermissionVersion.toString(),
        learningWritesAllowed.toString(),
        cycleOrdinal.toString(),
        turnOrdinal.toString(),
        conversationGeneration.toString(),
        activeOwnerEpoch.toString(),
        providerId,
        modelId,
        providerConfigurationVersion,
        providerAuthorityGeneration.toString(),
    )

    companion object {
        private const val CREDENTIAL_VERSION = "1"
        private const val ENCODED_FIELD_COUNT = 24
        private const val MAX_ENCODED_CHARS = 4_096
        private const val MAX_FIELD_CHARS = 1_024
        private val SHA_256 = Regex("^[0-9a-f]{64}$")

        internal fun accepted(admission: TutorLocalRecoveryAdmission) =
            TutorLocalRecoveryPresentationCredential(
                recoveredRequestId = admission.recoveredRequestId,
                recoveredRequestFingerprint = admission.recoveredRequestFingerprint,
                authoritySessionId = admission.authoritySessionId,
                tokenGeneration = admission.tokenGeneration,
                sourceTaskKind = admission.sourceTaskKind,
                sourceRequestId = admission.sourceRequestId,
                sourceRequestFingerprint = admission.sourceRequestFingerprint,
                sourceTaskStateVersion = admission.sourceTaskStateVersion,
                presentationScopeKey = localRecoveryPresentationScopeKey(
                    sessionId = admission.runtime.question.sessionId,
                    revisionNumber = admission.runtime.question.revisionNumber,
                    questionDocumentFingerprint = admission.runtime.questionDocumentFingerprint,
                ),
                questionDocumentFingerprint = admission.runtime.questionDocumentFingerprint,
                effectiveExplanationMode = admission.runtime.effectiveExplanationMode,
                modeVersion = admission.runtime.modeVersion,
                learningWritePermissionVersion =
                    admission.runtime.learningWritePermissionVersion,
                learningWritesAllowed = admission.runtime.learningWritesAllowed,
                cycleOrdinal = admission.runtime.cycleOrdinal,
                turnOrdinal = admission.runtime.turnOrdinal,
                conversationGeneration = admission.runtime.conversationGeneration,
                activeOwnerEpoch = admission.runtime.activeOwnerEpoch,
                providerId = admission.runtime.providerId,
                modelId = admission.runtime.modelId,
                providerConfigurationVersion = admission.runtime.providerConfigurationVersion,
                providerAuthorityGeneration = admission.runtime.providerAuthorityGeneration,
            )

        fun decode(encoded: String): TutorLocalRecoveryPresentationCredential? = runCatching {
            if (encoded.length > MAX_ENCODED_CHARS) return@runCatching null
            val fields = decodeFields(encoded) ?: return@runCatching null
            if (fields.size != ENCODED_FIELD_COUNT) return@runCatching null
            val signedFields = fields.dropLast(1)
            if (fields.last() != signedFields.fingerprint()) return@runCatching null
            if (signedFields.first() != CREDENTIAL_VERSION) return@runCatching null
            TutorLocalRecoveryPresentationCredential(
                recoveredRequestId = signedFields[1],
                recoveredRequestFingerprint = signedFields[2],
                authoritySessionId = signedFields[3],
                tokenGeneration = signedFields[4].toLong(),
                sourceTaskKind = ModelTaskKind.valueOf(signedFields[5]),
                sourceRequestId = signedFields[6],
                sourceRequestFingerprint = signedFields[7],
                sourceTaskStateVersion = signedFields[8].toLong(),
                presentationScopeKey = signedFields[9],
                questionDocumentFingerprint = signedFields[10],
                effectiveExplanationMode = TutorExplanationMode.valueOf(signedFields[11]),
                modeVersion = signedFields[12].toLong(),
                learningWritePermissionVersion = signedFields[13].toLong(),
                learningWritesAllowed = signedFields[14].toStrictBoolean(),
                cycleOrdinal = signedFields[15].toInt(),
                turnOrdinal = signedFields[16].toInt(),
                conversationGeneration = signedFields[17].toLong(),
                activeOwnerEpoch = signedFields[18].toLong(),
                providerId = signedFields[19],
                modelId = signedFields[20],
                providerConfigurationVersion = signedFields[21],
                providerAuthorityGeneration = signedFields[22].toLong(),
            )
        }.getOrNull()

        private fun decodeFields(encoded: String): List<String>? {
            val result = ArrayList<String>(ENCODED_FIELD_COUNT)
            var cursor = 0
            while (cursor < encoded.length && result.size <= ENCODED_FIELD_COUNT) {
                val separator = encoded.indexOf(':', startIndex = cursor)
                if (separator <= cursor || separator - cursor > 6) return null
                val length = encoded.substring(cursor, separator).toIntOrNull() ?: return null
                if (length !in 0..MAX_FIELD_CHARS) return null
                val valueStart = separator + 1
                val valueEnd = valueStart + length
                if (valueEnd > encoded.length) return null
                result += encoded.substring(valueStart, valueEnd)
                cursor = valueEnd
            }
            return result.takeIf { cursor == encoded.length }
        }

        private fun String.toStrictBoolean(): Boolean = when (this) {
            "true" -> true
            "false" -> false
            else -> error("Invalid recovery credential boolean")
        }

        private fun String.isValidCredentialText(): Boolean =
            isNotBlank() && length <= MAX_FIELD_CHARS

        private fun encodeFields(fields: List<String>): String = buildString {
            fields.forEach { field ->
                append(field.length)
                append(':')
                append(field)
            }
        }

        private fun List<String>.fingerprint(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            forEach { field ->
                digest.update(field.length.toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(':'.code.toByte())
                digest.update(field.toByteArray(StandardCharsets.UTF_8))
            }
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

internal data class TutorLocalRecoveryRequestAuthority(
    val authoritySessionId: String,
    val authorityGeneration: Long,
    val questionDocumentFingerprint: String,
    val providerAuthorityGeneration: Long,
    val conversationGeneration: Long,
    val activeOwnerEpoch: Long,
    val sourceRequestFingerprint: String,
    val sourceTaskStateVersion: Long,
) {
    init {
        require(authoritySessionId.isNotBlank())
        require(authorityGeneration > 0)
        require(questionDocumentFingerprint.matches(SHA_256))
        require(providerAuthorityGeneration >= 0)
        require(conversationGeneration >= 0)
        require(activeOwnerEpoch >= 0)
        require(sourceRequestFingerprint.matches(SHA_256))
        require(sourceTaskStateVersion >= 0)
    }

    private companion object {
        val SHA_256 = Regex("^[0-9a-f]{64}$")
    }
}

internal fun TutorRecoveryAuthorityToken.toLocalRecoveryRequestAuthority() =
    TutorLocalRecoveryRequestAuthority(
        authoritySessionId = authoritySessionId,
        authorityGeneration = generation,
        questionDocumentFingerprint = runtime.questionDocumentFingerprint,
        providerAuthorityGeneration = runtime.providerAuthorityGeneration,
        conversationGeneration = runtime.conversationGeneration,
        activeOwnerEpoch = runtime.activeOwnerEpoch,
        sourceRequestFingerprint = ModelTaskFingerprint.of(operation.sourceRequest),
        sourceTaskStateVersion = operation.taskStateVersion,
    )

/**
 * Issues latest-only pre-completion authority. Exact runtime equality prevents an old suspended
 * read from becoming valid again after a mode change, permission revocation, new turn, provider
 * refresh, or different pending operation. Accepted admissions are removed atomically; the caller
 * must durably commit the resulting bounded presentation credential before publishing it.
 */
internal class TutorRecoveryAuthorityCoordinator(
    private val authoritySessionId: String = UUID.randomUUID().toString(),
) {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var activeToken: TutorRecoveryAuthorityToken? = null
    private val pendingLocalAdmissions = linkedMapOf<String, TutorLocalRecoveryAdmission>()

    init {
        require(authoritySessionId.isNotBlank())
    }

    fun issue(
        runtime: TutorRecoveryRuntimeAuthority,
        operation: TutorRecoveryOperation,
    ): TutorRecoveryAuthorityToken = synchronized(lock) {
        issueLocked(runtime, operation)
    }

    /**
     * Returns the existing live token for the exact same recovery when one is already in flight.
     * Unlike [issue], this does not supersede the current authority, so duplicate recovery
     * triggers cannot revoke the stream they just submitted.
     */
    fun tryIssue(
        runtime: TutorRecoveryRuntimeAuthority,
        operation: TutorRecoveryOperation,
    ): TutorRecoveryAuthorityToken = synchronized(lock) {
        activeToken
            ?.takeIf { token ->
                !closed &&
                    generation == token.generation &&
                    token.operation == operation &&
                    token.runtime == runtime
            }
            ?: issueLocked(runtime, operation)
    }

    private fun issueLocked(
        runtime: TutorRecoveryRuntimeAuthority,
        operation: TutorRecoveryOperation,
    ): TutorRecoveryAuthorityToken {
        generation += 1
        pendingLocalAdmissions.clear()
        val token = TutorRecoveryAuthorityToken.issue(
            runtime = runtime,
            operation = operation,
            authoritySessionId = authoritySessionId,
            generation = generation,
        )
        activeToken = token
        return token
    }

    /**
     * Registers one exact local request before execution. When the pending budget is full we fail
     * closed instead of losing authority for a request that is already running.
     */
    fun admitLocalRequest(
        recoveredRequest: ModelTaskRequest,
        token: TutorRecoveryAuthorityToken,
    ): Boolean = synchronized(lock) {
        if (
            recoveredRequest.requestId.isBlank() ||
            recoveredRequest.input.kind != token.operation.taskKind ||
            recoveredRequest.egressManifest != null ||
            token.runtime.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS ||
            !token.isRuntimeCurrentLocked(token.runtime)
        ) {
            return@synchronized false
        }
        val candidate = TutorLocalRecoveryAdmission(
            recoveredRequestId = recoveredRequest.requestId,
            recoveredRequestFingerprint = ModelTaskFingerprint.of(recoveredRequest),
            authoritySessionId = token.authoritySessionId,
            tokenGeneration = token.generation,
            runtime = token.runtime,
            sourceTaskKind = token.operation.taskKind,
            sourceRequestId = token.operation.requestId,
            sourceRequestFingerprint = ModelTaskFingerprint.of(token.operation.sourceRequest),
            sourceTaskStateVersion = token.operation.taskStateVersion,
        )
        val existing = pendingLocalAdmissions[candidate.recoveredRequestId]
        if (existing != null) return@synchronized existing == candidate
        if (pendingLocalAdmissions.size >= MAX_PENDING_LOCAL_RECOVERIES) {
            return@synchronized false
        }
        pendingLocalAdmissions[candidate.recoveredRequestId] = candidate
        true
    }

    fun revokeLocalRequest(
        recoveredRequestId: String,
        token: TutorRecoveryAuthorityToken,
    ): Boolean = synchronized(lock) {
        val existing = pendingLocalAdmissions[recoveredRequestId] ?: return@synchronized false
        if (!existing.belongsTo(token)) return@synchronized false
        pendingLocalAdmissions.remove(recoveredRequestId)
        true
    }

    fun isCurrent(
        token: TutorRecoveryAuthorityToken,
        runtime: TutorRecoveryRuntimeAuthority?,
        operation: TutorRecoveryOperation?,
    ): Boolean = synchronized(lock) {
        token.isCurrentLocked(runtime, operation)
    }

    fun isRuntimeCurrent(
        token: TutorRecoveryAuthorityToken,
        runtime: TutorRecoveryRuntimeAuthority?,
    ): Boolean = synchronized(lock) {
        token.isRuntimeCurrentLocked(runtime)
    }

    /**
     * Atomically converts the latest recovery authority into permission to publish one exact
     * rebuilt request. A terminal repository row is not presentation authority by itself.
     */
    fun acceptCompletion(
        token: TutorRecoveryAuthorityToken,
        runtime: TutorRecoveryRuntimeAuthority?,
        operation: TutorRecoveryOperation?,
        recoveredRequestId: String,
    ): TutorLocalRecoveryPresentationCredential? = synchronized(lock) {
        if (recoveredRequestId.isBlank() || !token.isCurrentLocked(runtime, operation)) {
            return@synchronized null
        }
        val admission = pendingLocalAdmissions[recoveredRequestId]
            ?.takeIf { candidate -> candidate.belongsTo(token) }
            ?: return@synchronized null
        pendingLocalAdmissions.remove(recoveredRequestId)
        activeToken = null
        TutorLocalRecoveryPresentationCredential.accepted(admission)
    }

    internal fun pendingLocalAdmissionCount(): Int = synchronized(lock) {
        pendingLocalAdmissions.size
    }

    fun revoke() {
        synchronized(lock) {
            if (!closed) {
                generation += 1
                pendingLocalAdmissions.clear()
                activeToken = null
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            generation += 1
            pendingLocalAdmissions.clear()
            activeToken = null
        }
    }

    private fun TutorRecoveryAuthorityToken.isRuntimeCurrentLocked(
        runtime: TutorRecoveryRuntimeAuthority?,
    ): Boolean = !closed &&
        this@isRuntimeCurrentLocked.authoritySessionId ==
        this@TutorRecoveryAuthorityCoordinator.authoritySessionId &&
        this@TutorRecoveryAuthorityCoordinator.generation ==
        this@isRuntimeCurrentLocked.generation &&
        this@isRuntimeCurrentLocked.runtime == runtime

    private fun TutorRecoveryAuthorityToken.isCurrentLocked(
        runtime: TutorRecoveryRuntimeAuthority?,
        operation: TutorRecoveryOperation?,
    ): Boolean = isRuntimeCurrentLocked(runtime) && this.operation == operation

    private companion object {
        const val MAX_PENDING_LOCAL_RECOVERIES = 256
    }
}

internal data class TutorMasteryRecoveryReadKey(
    val sessionId: String,
    val revisionNumber: Int,
    val questionDocumentId: String,
    val request: TutorMasteryContextRequest?,
)

internal fun TutorQuestionContext.toTutorMasteryRecoveryReadKey() =
    TutorMasteryRecoveryReadKey(
        sessionId = sessionId,
        revisionNumber = revisionNumber,
        questionDocumentId = questionDocument.document.id,
        request = masteryContextRequestOrNull(),
    )

internal fun TutorQuestionContext.masteryContextRequestOrNull(): TutorMasteryContextRequest? {
    if (questionKnowledgeNodes.isEmpty() && fallbackKnowledgeNodes.isEmpty()) return null
    val subjectKind = SubjectKind.entries.singleOrNull { candidate ->
        candidate != SubjectKind.GENERAL && candidate.name == subject
    } ?: return null
    return TutorMasteryContextRequest(
        subject = subjectKind,
        questionKnowledgeNodes = questionKnowledgeNodes,
        fallbackKnowledgeNodes = fallbackKnowledgeNodes,
        relatedKnowledgeNodes = relatedKnowledgeNodes,
    )
}
