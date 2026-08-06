package com.tingyun.smartmistakebook.core.data.session

import android.content.Context
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseContextRequest
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentOpenResponseLearningScope
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorOpenResponseAuthorizationConsumeDisposition
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorOpenResponseContextAuthorization
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorOpenResponseContextOwner
import com.tingyun.smartmistakebook.core.data.tutor.CurrentSessionTutorInteractionRepositoryFactory
import com.tingyun.smartmistakebook.core.data.tutor.TutorLearningEvidenceCandidate
import com.tingyun.smartmistakebook.core.data.tutor.TutorLearningEvidenceCurrentSessionProofSource
import com.tingyun.smartmistakebook.core.data.tutor.TrustedTutorChoiceLearningFinalizer
import com.tingyun.smartmistakebook.core.database.ActivateCurrentTutorInteractionCommand
import com.tingyun.smartmistakebook.core.database.AppendCurrentTutorInteractionCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionActivationDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionActivationResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionAppendDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionAppendResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionBundle
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionEventKind
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionEventRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorInteractionScopeRecord
import com.tingyun.smartmistakebook.core.database.ConsumeCurrentTutorOpenResponseAuthorizationCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorOpenResponseAuthorizationConsumeDisposition as DatabaseOpenResponseConsumeDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorOpenResponseAuthorizationConsumeResult
import com.tingyun.smartmistakebook.core.database.TrustedTutorSessionDatabaseCapability
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningEvidenceCurrentSessionReference
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerEvaluationReceipt
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Complete owner-side activation. No field is accepted from a later feature submission. */
internal class CurrentTutorSessionActivation(
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val authorityConversationId: String,
    val authorityConversationGeneration: Long,
    val authorityConversationStateVersion: Long,
    val authorityTurnReceiptId: String,
    val authorityTurnOrdinal: Int,
    val authorityRequestVersion: Long,
    val questionRevisionNumber: Int,
    questionDocument: QuestionDocument,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val cycleOrdinal: Int,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
    val learningWritesAllowed: Boolean,
    val learningWritePermissionVersion: Long,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val attributionPolicyVersion: String,
    val responsePolicyVersion: String,
    val rubricCanonicalFingerprint: String,
    verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof>,
    teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    val evaluator: OpenResponseEvaluatorKind,
    val evaluatorPolicyFingerprint: String,
    val prohibitedEvaluatorExecutionFingerprint: String? = null,
    val occurredAtEpochMillis: Long,
) {
    val questionDocument: QuestionDocument = questionDocument.copy(
        blocks = Collections.unmodifiableList(questionDocument.blocks.toList()),
    )
    val verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof> =
        Collections.unmodifiableList(
            verifiedKnowledgeProofs.sortedWith(
                compareBy(
                    { it.ref.canonicalFingerprint },
                    { it.manifestFingerprint },
                    { it.activationGeneration },
                ),
            ),
        )
    val teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference> =
        Collections.unmodifiableList(
            teachingReferences.sortedWith(
                compareBy(
                    { it.proof.ref.canonicalFingerprint },
                    { it.label },
                    { it.constraint.name },
                ),
            ),
        )
    val questionFingerprint: String =
        OpenResponseEvaluationTaskFingerprints.question(this.questionDocument)
    internal val knowledgeAuthorityFingerprint: String =
        CanonicalSha256("current-tutor-knowledge-authority-v1")
            .field(
                "proofs",
                this.verifiedKnowledgeProofs.joinToString("|") { proof ->
                    "${proof.ref.canonicalFingerprint}:${proof.manifestFingerprint}:" +
                        proof.activationGeneration
                },
            )
            .field(
                "references",
                this.teachingReferences.joinToString("|") { reference ->
                    "${reference.proof.ref.canonicalFingerprint}:${reference.label}:" +
                        reference.constraint.name
                },
            )
            .nullableField(
                "prohibitedEvaluatorExecutionFingerprint",
                prohibitedEvaluatorExecutionFingerprint,
            )
            .finish()
    internal val activationFingerprint: String =
        CanonicalSha256("current-tutor-session-activation-v1")
            .field("learnerId", learnerId)
            .field("conversationId", conversationId)
            .field("conversationGeneration", conversationGeneration)
            .field("conversationStateVersion", conversationStateVersion)
            .field("authorityConversationId", authorityConversationId)
            .field("authorityConversationGeneration", authorityConversationGeneration)
            .field("authorityConversationStateVersion", authorityConversationStateVersion)
            .field("authorityTurnReceiptId", authorityTurnReceiptId)
            .field("authorityTurnOrdinal", authorityTurnOrdinal)
            .field("authorityRequestVersion", authorityRequestVersion)
            .field("questionDocumentId", this.questionDocument.id)
            .field("questionRevisionNumber", questionRevisionNumber)
            .field("questionFingerprint", questionFingerprint)
            .field("subject", subject.name)
            .field("problemAnchorId", problemAnchorId)
            .field("explanationMode", explanationMode.name)
            .field("modeVersion", modeVersion)
            .field("turnReferenceId", turnReferenceId)
            .field("turnOrdinal", turnOrdinal)
            .field("turnGeneration", turnGeneration)
            .field("cycleOrdinal", cycleOrdinal)
            .field("attemptOrdinal", attemptOrdinal)
            .field("hintCount", hintCount)
            .field("answerWasRevealed", answerWasRevealed)
            .field("requestVersion", requestVersion)
            .field("learningWritesAllowed", learningWritesAllowed)
            .field("learningWritePermissionVersion", learningWritePermissionVersion)
            .field("presentationFingerprint", presentationFingerprint)
            .field("problemFingerprint", problemFingerprint)
            .field("problemFamilyFingerprint", problemFamilyFingerprint)
            .field("attributionPolicyVersion", attributionPolicyVersion)
            .field("responsePolicyVersion", responsePolicyVersion)
            .field("rubricCanonicalFingerprint", rubricCanonicalFingerprint)
            .field("knowledgeAuthorityFingerprint", knowledgeAuthorityFingerprint)
            .field("evaluator", evaluator.name)
            .field("evaluatorPolicyFingerprint", evaluatorPolicyFingerprint)
            .nullableField(
                "prohibitedEvaluatorExecutionFingerprint",
                prohibitedEvaluatorExecutionFingerprint,
            )
            .finish()
    internal val scopeId: String = "tutor-current:$activationFingerprint"
    internal val learningEvidenceAuthorizationFingerprint: String =
        CanonicalSha256("current-tutor-learning-evidence-authorization-v1")
            .field("activationFingerprint", activationFingerprint)
            .field("learningWritePermissionVersion", learningWritePermissionVersion)
            .finish()

    init {
        SessionScope(learnerId)
        conversationId.requireSessionIdentifier("Conversation id")
        require(conversationGeneration > 0 && authorityConversationGeneration > 0)
        require(conversationStateVersion >= 0 && authorityConversationStateVersion >= 0)
        authorityConversationId.requireSessionIdentifier("Authority conversation id")
        authorityTurnReceiptId.requireSessionIdentifier("Authority turn receipt id")
        require(authorityTurnOrdinal > 0 && authorityRequestVersion >= 0)
        require(questionRevisionNumber > 0 && subject != SubjectKind.GENERAL)
        problemAnchorId.requireSessionIdentifier("Problem anchor id")
        require(
            modeVersion >= 0 && requestVersion >= 0 && learningWritePermissionVersion >= 0,
        )
        turnReferenceId.requireSessionIdentifier("Turn reference id")
        require(turnOrdinal > 0 && turnGeneration > 0 && cycleOrdinal > 0)
        require(attemptOrdinal in 0..17 && hintCount in 0..32)
        listOf(
            presentationFingerprint,
            problemFingerprint,
            problemFamilyFingerprint,
            rubricCanonicalFingerprint,
            evaluatorPolicyFingerprint,
        ).forEach { it.requireSessionFingerprint("Current Tutor fingerprint") }
        attributionPolicyVersion.requireSessionIdentifier("Attribution policy version")
        responsePolicyVersion.requireSessionIdentifier("Response policy version")
        prohibitedEvaluatorExecutionFingerprint?.let { fingerprint ->
            fingerprint.requireSessionFingerprint("Prohibited evaluator execution")
        }
        require(occurredAtEpochMillis >= 0)
        require(this.verifiedKnowledgeProofs.size <= 24)
        require(this.teachingReferences.size <= 24)
        require(
            this.verifiedKnowledgeProofs.distinctBy { it.ref.canonicalFingerprint }.size ==
                this.verifiedKnowledgeProofs.size,
        )
        require(
            this.teachingReferences.distinctBy { it.proof.ref.canonicalFingerprint }.size ==
                this.teachingReferences.size,
        )
        require(this.verifiedKnowledgeProofs.all { it.ref.subject == subject })
        require(this.teachingReferences.all { it.proof.ref.subject == subject })
        require(
            learningWritesAllowed ||
                this.verifiedKnowledgeProofs.isEmpty() && this.teachingReferences.isEmpty(),
        ) { "A write-disabled Current Tutor activation cannot carry knowledge authority" }
    }
}

internal sealed interface CurrentTutorTrustedAnswerEvent {
    val responseForm: ReviewResponseForm

    data class Choice(
        val diagnosticStemMarkdown: String,
        val selectedChoiceId: String,
        val selectedChoiceMarkdown: String,
        val feedbackMarkdown: String,
    ) : CurrentTutorTrustedAnswerEvent {
        override val responseForm: ReviewResponseForm = ReviewResponseForm.CHOICE

        init {
            require(diagnosticStemMarkdown.isNotBlank())
            require(selectedChoiceId.isNotBlank())
            require(selectedChoiceMarkdown.isNotBlank())
            require(feedbackMarkdown.isNotBlank())
        }
    }

    data class VisualTarget(
        val hitProof: TutorVisualHitProof,
    ) : CurrentTutorTrustedAnswerEvent {
        override val responseForm: ReviewResponseForm = ReviewResponseForm.VISUAL_TARGET
    }
}

internal data class CurrentTutorTrustedAnswerCommitCommand(
    val learnerId: String,
    val sessionId: String,
    val presentationToken: String,
    val exactContentBinding: String,
    val evidenceRequestId: String,
    val expectedEvidenceState: CurrentTutorSessionEvidenceState,
    val evaluation: StudentTrustedSavedAnswerEvaluationReceipt,
    val event: CurrentTutorTrustedAnswerEvent,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(learnerId.isNotBlank())
        require(sessionId.isNotBlank())
        require(presentationToken.length == 64)
        require(exactContentBinding.isNotBlank() && exactContentBinding.length <= 256)
        require(evidenceRequestId.isNotBlank())
        require(evaluation.responseForm == event.responseForm)
        require(occurredAtEpochMillis >= 0L)
    }
}

internal fun interface CurrentTutorTrustedAnswerCommitter {
    suspend fun commit(
        command: CurrentTutorTrustedAnswerCommitCommand,
    ): CurrentTutorTrustedAnswerSubmissionResult
}

internal fun interface CurrentTutorTrustedEvidenceFinalizer {
    suspend fun finalize(command: FinalizeTutorEvidenceCommand): FinalizeTutorEvidenceResult
}

internal enum class CurrentTutorSessionActivationResult {
    ACTIVE,
    DUPLICATE,
    STALE,
    AUTHORITY_MISMATCH,
    OWNER_CLOSED,
}

/**
 * Runtime owner published as one unit so interaction and open-response grants cannot come from
 * different session authorities.
 */
class CurrentTutorSessionProductionOwner internal constructor(
    private val delegate: CurrentTutorInteractionSessionOwner,
    private val canonicalContext: Context,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val productionGeneration = AtomicReference<Any?>(null)
    val interactions: TutorInteractionRepository =
        CurrentSessionTutorInteractionRepositoryFactory.createProduction(delegate)
    internal val openResponseContextOwner: CurrentTutorOpenResponseContextOwner = delegate
    val learnerId: String
        get() = delegate.scope.learnerId

    internal suspend fun activate(
        activation: CurrentTutorSessionActivation,
    ): CurrentTutorSessionActivationResult =
        if (isOpen()) delegate.activate(activation)
        else CurrentTutorSessionActivationResult.OWNER_CLOSED

    internal fun revokeLearningWrites(scopeId: String) {
        if (isOpen()) delegate.revokeLearningWrites(scopeId)
    }

    internal suspend fun currentEvidenceState(
        sessionId: String,
    ): CurrentTutorSessionEvidenceState? =
        if (isOpen()) delegate.currentEvidenceState(sessionId) else null

    internal suspend fun recordHintShown(
        commit: CurrentTutorHintShownCommit,
    ): CurrentTutorHintShownCommitResult =
        if (isOpen()) delegate.recordHintShown(commit)
        else CurrentTutorHintShownCommitResult.REJECTED

    internal suspend fun currentLearningEvidenceReference(
        activation: CurrentTutorSessionActivation,
    ): TutorLearningEvidenceCurrentSessionReference? =
        if (isOpen()) delegate.currentLearningEvidenceReference(activation) else null

    internal val learningEvidenceProofSource: TutorLearningEvidenceCurrentSessionProofSource =
        TutorLearningEvidenceCurrentSessionProofSource { candidate ->
            if (isOpen()) delegate.resolve(candidate) else null
        }

    internal fun trustedChoiceLearningFinalizer(
        learningMemory: TutorLearningMemoryRepository,
    ): TrustedTutorChoiceLearningFinalizer =
        TrustedTutorChoiceLearningFinalizer { response ->
            check(isOpen()) { "Current tutor session owner is closed" }
            delegate.finalizeRecordedChoice(response, learningMemory)
        }

    internal fun trustedSavedAnswerCommitter(
        learningMemory: TutorLearningMemoryRepository,
    ): CurrentTutorTrustedAnswerCommitter =
        CurrentTutorTrustedAnswerCommitter { command ->
            if (!isOpen()) CurrentTutorTrustedAnswerSubmissionResult.Rejected
            else delegate.commitTrustedSavedAnswer(
                command,
                CurrentTutorTrustedEvidenceFinalizer { finalization ->
                    learningMemory.finalizeEvidence(finalization)
                },
            )
        }

    internal fun bindToProductionGeneration(
        context: Context,
        generationIdentity: Any,
    ): Boolean {
        if (canonicalContext !== (context.applicationContext ?: context)) return false
        if (closed.get() || !delegate.isOpen()) return false
        if (!productionGeneration.compareAndSet(null, generationIdentity)) return false
        if (closed.get() || !delegate.isOpen()) {
            productionGeneration.compareAndSet(generationIdentity, null)
            return false
        }
        return true
    }

    internal fun isOpen(): Boolean =
        !closed.get() && productionGeneration.get() != null && delegate.isOpen()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        productionGeneration.set(null)
        delegate.close()
    }
}

object CurrentTutorSessionProductionOwnerFactory {
    fun open(
        context: Context,
        learnerId: String,
        database: TrustedTutorSessionDatabaseCapability,
        nowEpochMillis: LongSupplier = LongSupplier { System.currentTimeMillis() },
    ): CurrentTutorSessionProductionOwner =
        CurrentTutorSessionProductionOwner(
            CurrentTutorInteractionSessionOwner(
                scope = SessionScope(learnerId),
                authority = TrustedCurrentTutorSessionAuthority(database),
                nowEpochMillis = nowEpochMillis,
            ),
            context.applicationContext ?: context,
        )
}

/** Small testable view over the sealed database capability; no raw database handle crosses it. */
internal interface CurrentTutorSessionAuthority {
    suspend fun activateCurrentTutorInteraction(
        command: ActivateCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionActivationResult

    suspend fun readCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): CurrentTutorInteractionBundle?

    fun observeCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): Flow<CurrentTutorInteractionBundle?>

    fun observeTutorInteractionHistory(
        learnerId: String,
        conversationId: String,
    ): Flow<List<CurrentTutorInteractionEventRecord>>

    suspend fun readTutorAnswerExposureEvents(
        learnerId: String,
        modelTaskRequestIds: Set<String>,
    ): List<CurrentTutorInteractionEventRecord>

    suspend fun appendCurrentTutorInteraction(
        command: AppendCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionAppendResult

    suspend fun consumeCurrentTutorOpenResponseAuthorization(
        command: ConsumeCurrentTutorOpenResponseAuthorizationCommand,
    ): CurrentTutorOpenResponseAuthorizationConsumeResult

    suspend fun openTutorEvidenceRequest(
        learnerId: String,
        evidenceRequestId: String,
    ): TutorEvidenceRequest?
}

private class TrustedCurrentTutorSessionAuthority(
    private val delegate: TrustedTutorSessionDatabaseCapability,
) : CurrentTutorSessionAuthority {
    override suspend fun activateCurrentTutorInteraction(
        command: ActivateCurrentTutorInteractionCommand,
    ) = delegate.activateCurrentTutorInteraction(command)

    override suspend fun readCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ) = delegate.readCurrentTutorInteraction(learnerId, conversationId)

    override fun observeCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ) = delegate.observeCurrentTutorInteraction(learnerId, conversationId)

    override fun observeTutorInteractionHistory(
        learnerId: String,
        conversationId: String,
    ) = delegate.observeTutorInteractionHistory(learnerId, conversationId)

    override suspend fun readTutorAnswerExposureEvents(
        learnerId: String,
        modelTaskRequestIds: Set<String>,
    ) = delegate.readTutorAnswerExposureEvents(learnerId, modelTaskRequestIds)

    override suspend fun appendCurrentTutorInteraction(
        command: AppendCurrentTutorInteractionCommand,
    ) = delegate.appendCurrentTutorInteraction(command)

    override suspend fun consumeCurrentTutorOpenResponseAuthorization(
        command: ConsumeCurrentTutorOpenResponseAuthorizationCommand,
    ) = delegate.consumeCurrentTutorOpenResponseAuthorization(command)

    override suspend fun openTutorEvidenceRequest(
        learnerId: String,
        evidenceRequestId: String,
    ) = delegate.openTutorEvidenceRequest(learnerId, evidenceRequestId)
}

internal class CurrentTutorInteractionSessionOwner(
    override val scope: SessionScope,
    private val authority: CurrentTutorSessionAuthority,
    private val nowEpochMillis: LongSupplier,
) : TutorInteractionSessionPort, CurrentTutorOpenResponseContextOwner, AutoCloseable {
    override val learnerId: String = scope.learnerId
    private val closed = AtomicBoolean(false)
    private val lifecycleMonitor = Any()
    private val ownerSecret = UUID.randomUUID().toString()
    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + Dispatchers.Default)
    private val observers = ConcurrentHashMap<String, Job>()
    private val current = ConcurrentHashMap<String, CurrentTutorInteractionBundle>()
    private val interactionGrants = ConcurrentHashMap<String, InteractionGrant>()
    private val openResponseGrants = ConcurrentHashMap<String, OpenResponseGrant>()
    private val authorityMaterials = ConcurrentHashMap<String, OpenResponseAuthorityMaterial>()
    private val authorityConversationScopes = ConcurrentHashMap<String, String>()
    private val learningWriteEnabledScopes = ConcurrentHashMap.newKeySet<String>()

    suspend fun activate(
        activation: CurrentTutorSessionActivation,
    ): CurrentTutorSessionActivationResult {
        if (closed.get()) return CurrentTutorSessionActivationResult.OWNER_CLOSED
        if (activation.learnerId != learnerId) {
            return CurrentTutorSessionActivationResult.AUTHORITY_MISMATCH
        }
        val result =
            try {
                authority.activateCurrentTutorInteraction(activation.toDatabaseCommand())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CurrentTutorSessionActivationResult.AUTHORITY_MISMATCH
            }
        if (closed.get()) return CurrentTutorSessionActivationResult.OWNER_CLOSED
        val bundle = result.bundle
        synchronized(lifecycleMonitor) {
            if (closed.get()) return CurrentTutorSessionActivationResult.OWNER_CLOSED
            if (bundle != null) {
                current[activation.conversationId] = bundle
                observeInBackground(activation.conversationId)
            }
            if (
                bundle?.scope?.scopeId == activation.scopeId &&
                bundle.scope.knowledgeAuthorityFingerprint == activation.knowledgeAuthorityFingerprint
            ) {
                authorityMaterials[activation.scopeId] = activation.toAuthorityMaterial()
                authorityConversationScopes[activation.authorityConversationId] = activation.scopeId
                if (activation.learningWritesAllowed) {
                    learningWriteEnabledScopes += activation.scopeId
                } else {
                    learningWriteEnabledScopes -= activation.scopeId
                }
            }
        }
        return when (result.disposition) {
            CurrentTutorInteractionActivationDisposition.ACTIVATED ->
                CurrentTutorSessionActivationResult.ACTIVE
            CurrentTutorInteractionActivationDisposition.DUPLICATE ->
                CurrentTutorSessionActivationResult.DUPLICATE
            CurrentTutorInteractionActivationDisposition.STALE ->
                CurrentTutorSessionActivationResult.STALE
            CurrentTutorInteractionActivationDisposition.AUTHORITY_MISMATCH ->
                CurrentTutorSessionActivationResult.AUTHORITY_MISMATCH
        }
    }

    fun revokeLearningWrites(scopeId: String) {
        scopeId.requireSessionIdentifier("Current Tutor scope id")
        synchronized(lifecycleMonitor) {
            learningWriteEnabledScopes -= scopeId
            interactionGrants.entries.removeAll { entry -> entry.value.scopeId == scopeId }
            openResponseGrants.entries.removeAll { entry -> entry.value.scopeId == scopeId }
            authorityMaterials.remove(scopeId)
            authorityConversationScopes.entries.removeAll { entry -> entry.value == scopeId }
        }
    }

    override fun observeResponses(
        scope: SessionScope,
        sessionId: String,
    ): Flow<List<TutorTurnResponseSessionSnapshot>> {
        scope.requireBoundTo(this.scope)
        return authority.observeTutorInteractionHistory(learnerId, sessionId)
            .map(::foldResponses)
    }

    override fun observeVisualSelections(
        scope: SessionScope,
        sessionId: String,
    ): Flow<List<TutorVisualSelectionSessionSnapshot>> {
        scope.requireBoundTo(this.scope)
        return authority.observeTutorInteractionHistory(learnerId, sessionId)
            .map { emptyList() }
    }

    override suspend fun isEvidenceCancelled(
        query: TutorEvidenceCancellationSessionQuery,
    ): Boolean {
        query.scope.requireBoundTo(scope)
        return authority.observeTutorInteractionHistory(learnerId, query.key.sessionId)
            .first()
            .any { event ->
                event.eventKind == CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION &&
                    event.questionDocumentId == query.key.questionDocumentId &&
                    event.questionRevisionNumber == query.key.revisionNumber &&
                    event.authorizationRequestId == query.evidenceRequestId
            }
    }

    override suspend fun readExposures(
        query: TutorAnswerExposureSessionQuery,
    ): List<TutorAnswerExposureSessionSnapshot> {
        query.scope.requireBoundTo(scope)
        return authority.readTutorAnswerExposureEvents(learnerId, query.modelTaskRequestIds)
            .map(::toExposure)
    }

    override suspend fun mutate(
        command: TutorInteractionSessionMutation,
    ): TutorInteractionSessionMutationResult {
        command.scope.requireBoundTo(scope)
        return rejected(command.operation, command.occurredAtEpochMillis)
    }

    override fun observeCurrent(
        conversationId: String,
    ): Flow<TutorCurrentInteractionSessionSnapshot> {
        conversationId.requireSessionIdentifier("Tutor conversation id")
        observeInBackground(conversationId)
        return authority.observeCurrentTutorInteraction(learnerId, conversationId)
            .filterNotNull()
            .onEach { bundle ->
                if (!closed.get()) current[conversationId] = bundle
            }
            .map(::toCurrentSnapshot)
    }

    override suspend fun authorizeCurrent(
        query: TutorCurrentInteractionAuthorizationQuery,
    ): TutorCurrentInteractionAuthorization? {
        if (closed.get()) return null
        query.scope.requireBoundTo(scope)
        if (query.purpose.requiresStudentAnswerOwner()) return null
        val bundle = loadCurrent(query.conversationId) ?: return null
        if (closed.get()) return null
        if (!bundle.matches(query)) return null
        if (query.requestId != null && !requestMatches(bundle.scope, query.requestId, query.purpose)) {
            return null
        }
        val context = bundle.toContext(scope)
        val token = CanonicalSha256("current-tutor-interaction-authorization-v1")
            .field("ownerSecret", ownerSecret)
            .field("scopeId", bundle.scope.scopeId)
            .field("stateVersion", bundle.head.stateVersion)
            .field("stateFingerprint", bundle.head.stateFingerprint)
            .field("purpose", query.purpose.name)
            .nullableField("requestId", query.requestId)
            .finish()
        val authorization = TutorCurrentInteractionAuthorization(
            context = context,
            purpose = query.purpose,
            requestId = query.requestId,
            token = token,
        )
        return synchronized(lifecycleMonitor) {
            if (closed.get()) null
            else authorization.also { currentAuthorization ->
                interactionGrants[token] =
                    InteractionGrant(currentAuthorization, bundle.scope.scopeId)
            }
        }
    }

    override suspend fun mutateCurrent(
        command: TutorAuthorizedInteractionSessionMutation,
    ): TutorInteractionSessionMutationResult {
        if (closed.get()) return rejected(command.mutation.operation, command.mutation.occurredAtEpochMillis)
        if (command.mutation.requiresStudentAnswerOwner()) {
            return rejected(command.mutation.operation, command.mutation.occurredAtEpochMillis)
        }
        val granted = interactionGrants[command.authorization.token]
        if (granted?.authorization != command.authorization) {
            return rejected(command.mutation.operation, command.mutation.occurredAtEpochMillis)
        }
        val stored =
            try {
                authority.appendCurrentTutorInteraction(
                    command.toDatabaseCommand(granted.scopeId),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return rejected(command.mutation.operation, command.mutation.occurredAtEpochMillis)
            }
        val disposition = when (stored.disposition) {
            CurrentTutorInteractionAppendDisposition.APPLIED -> SessionMutationDisposition.APPLIED
            CurrentTutorInteractionAppendDisposition.DUPLICATE -> SessionMutationDisposition.DUPLICATE
            CurrentTutorInteractionAppendDisposition.RELOAD_REQUIRED ->
                SessionMutationDisposition.RELOAD_REQUIRED
            CurrentTutorInteractionAppendDisposition.NOT_FOUND -> SessionMutationDisposition.NOT_FOUND
            CurrentTutorInteractionAppendDisposition.REJECTED -> SessionMutationDisposition.REJECTED
        }
        val refreshed = authority.readCurrentTutorInteraction(
            learnerId,
            command.authorization.context.conversationId,
        )
        if (refreshed != null) current[refreshed.scope.conversationId] = refreshed
        if (disposition.isAccepted && command.mutation.revokesLearningEvidence()) {
            synchronized(lifecycleMonitor) {
                interactionGrants.entries.removeAll { entry ->
                    entry.value.scopeId == granted.scopeId
                }
                openResponseGrants.entries.removeAll { entry ->
                    entry.value.scopeId == granted.scopeId
                }
            }
        }
        val version = stored.head?.let { SessionVersion(it.stateVersion, it.stateFingerprint) }
        val event = stored.event
        return TutorInteractionSessionMutationResult(
            receipt = SessionMutationReceipt(
                operation = command.mutation.operation,
                disposition = disposition,
                currentVersion = version,
                recordedAtEpochMillis = stored.recordedAtEpochMillis,
            ),
            response = if (
                disposition.isAccepted && command.mutation.isResponseMutation()
            ) refreshed?.let(::foldResponses)?.lastOrNull { it.key == command.authorization.context.toKey() }
            else null,
            visualSelection = if (
                disposition.isAccepted &&
                command.mutation is TutorInteractionSessionMutation.RecordVisualSelection
            ) event?.let(::toVisual) else null,
            exposure = if (
                disposition.isAccepted &&
                command.mutation is TutorInteractionSessionMutation.RecordExposure
            ) event?.let(::toExposure) else null,
            anchor = if (
                disposition.isAccepted &&
                command.mutation is TutorInteractionSessionMutation.AnchorSession
            ) event?.let(::toAnchor) else null,
        )
    }

    override suspend fun authorizeCurrent(
        request: CoreDataTutorOpenResponseContextRequest,
        notAfterEpochMillis: Long,
    ): CurrentTutorOpenResponseContextAuthorization? {
        if (closed.get()) return null
        val now = nowEpochMillis.getAsLong()
        if (notAfterEpochMillis <= now) return null
        val bundle = loadCurrent(request.conversationId) ?: return null
        if (closed.get()) return null
        if (bundle.hasAnswerExposure()) return null
        val session = bundle.scope
        if (session.scopeId !in learningWriteEnabledScopes) return null
        val document = decodeQuestionDocument(session.questionDocumentSnapshot) ?: return null
        if (!session.matches(request, document)) return null
        val requestIsAuthorized =
            when (session.explanationMode) {
                TutorExplanationMode.DIRECT ->
                    request.evidenceRequestId == session.turnReferenceId
                TutorExplanationMode.GUIDED ->
                    authority.openTutorEvidenceRequest(learnerId, request.evidenceRequestId)
                        ?.matchesGuidedOpenResponse(session) == true
            }
        if (!requestIsAuthorized) return null
        val material = authorityMaterials[session.scopeId] ?: return null
        if (material.fingerprint != session.knowledgeAuthorityFingerprint) return null
        val expiry = minOf(notAfterEpochMillis, now + MAX_OPEN_RESPONSE_GRANT_MILLIS)
        val authorization = CurrentTutorOpenResponseContextAuthorization(
            learnerId = learnerId,
            requestFingerprint = request.canonicalFingerprint,
            presentationFingerprint = session.presentationFingerprint,
            problemFingerprint = session.problemFingerprint,
            problemFamilyFingerprint = session.problemFamilyFingerprint,
            attributionPolicyVersion = session.attributionPolicyVersion,
            responsePolicyVersion = session.responsePolicyVersion,
            rubricCanonicalFingerprint = session.rubricCanonicalFingerprint,
            verifiedKnowledgeProofs = material.verifiedKnowledgeProofs,
            teachingReferences = material.teachingReferences,
            evaluator = OpenResponseEvaluatorKind.valueOf(session.evaluator),
            evaluatorPolicyFingerprint = session.evaluatorPolicyFingerprint,
            prohibitedEvaluatorExecutionFingerprint =
                material.prohibitedEvaluatorExecutionFingerprint,
            expiresAtEpochMillis = expiry,
        )
        return synchronized(lifecycleMonitor) {
            if (closed.get()) null
            else authorization.also { currentAuthorization ->
                openResponseGrants[currentAuthorization.canonicalFingerprint] =
                    OpenResponseGrant(
                        session = session,
                        conversationId = session.conversationId,
                        scopeId = session.scopeId,
                        stateVersion = bundle.head.stateVersion,
                        stateFingerprint = bundle.head.stateFingerprint,
                        evidenceRequestId = request.evidenceRequestId,
                        requestFingerprint = request.canonicalFingerprint,
                        knowledgeAuthorityFingerprint = session.knowledgeAuthorityFingerprint,
                        expiresAtEpochMillis = expiry,
                    )
            }
        }
    }

    override fun isCurrent(
        authorization: CurrentTutorOpenResponseContextAuthorization,
    ): Boolean = isOpenResponseAuthorizationCurrent(authorization, enforceIssuanceDeadline = true)

    override fun isClaimedCurrent(
        authorization: CurrentTutorOpenResponseContextAuthorization,
    ): Boolean = isOpenResponseAuthorizationCurrent(authorization, enforceIssuanceDeadline = false)

    private fun isOpenResponseAuthorizationCurrent(
        authorization: CurrentTutorOpenResponseContextAuthorization,
        enforceIssuanceDeadline: Boolean,
    ): Boolean {
        if (
            closed.get() ||
            (
                enforceIssuanceDeadline &&
                    nowEpochMillis.getAsLong() >= authorization.expiresAtEpochMillis
            )
        ) return false
        val grant = openResponseGrants[authorization.canonicalFingerprint] ?: return false
        val bundle = current[grant.conversationId] ?: return false
        if (grant.scopeId !in learningWriteEnabledScopes) return false
        return authorization.learnerId == learnerId &&
            authorization.requestFingerprint == grant.requestFingerprint &&
            bundle.scope == grant.session &&
            bundle.scope.scopeId == grant.scopeId &&
            bundle.scope.knowledgeAuthorityFingerprint == grant.knowledgeAuthorityFingerprint &&
            !bundle.hasAnswerExposure() &&
            bundle.head.stateVersion == grant.stateVersion &&
            bundle.head.stateFingerprint == grant.stateFingerprint &&
            authorityMaterials[grant.scopeId]?.fingerprint == grant.knowledgeAuthorityFingerprint
    }

    override suspend fun consumeCurrentAuthorization(
        authorization: CurrentTutorOpenResponseContextAuthorization,
        scope: CurrentOpenResponseLearningScope,
        candidateIdempotencyKey: String,
    ): CurrentTutorOpenResponseAuthorizationConsumeDisposition =
        consumeOpenResponseAuthorization(
            authorization = authorization,
            scope = scope,
            candidateIdempotencyKey = candidateIdempotencyKey,
            enforceIssuanceDeadline = true,
        )

    override suspend fun consumeClaimedAuthorization(
        authorization: CurrentTutorOpenResponseContextAuthorization,
        scope: CurrentOpenResponseLearningScope,
        candidateIdempotencyKey: String,
    ): CurrentTutorOpenResponseAuthorizationConsumeDisposition =
        consumeOpenResponseAuthorization(
            authorization = authorization,
            scope = scope,
            candidateIdempotencyKey = candidateIdempotencyKey,
            enforceIssuanceDeadline = false,
        )

    private suspend fun consumeOpenResponseAuthorization(
        authorization: CurrentTutorOpenResponseContextAuthorization,
        scope: CurrentOpenResponseLearningScope,
        candidateIdempotencyKey: String,
        enforceIssuanceDeadline: Boolean,
    ): CurrentTutorOpenResponseAuthorizationConsumeDisposition {
        if (
            closed.get() ||
            (
                enforceIssuanceDeadline &&
                    nowEpochMillis.getAsLong() >= authorization.expiresAtEpochMillis
            )
        ) {
            return CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT
        }
        val grant = openResponseGrants[authorization.canonicalFingerprint]
            ?: return CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT
        if (grant.scopeId !in learningWriteEnabledScopes) {
            return CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT
        }
        val currentBundle = loadCurrent(grant.conversationId)
            ?: return CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT
        if (
            currentBundle.hasAnswerExposure() ||
            currentBundle.scope.scopeId != grant.scopeId ||
            currentBundle.head.stateVersion != grant.stateVersion ||
            currentBundle.head.stateFingerprint != grant.stateFingerprint
        ) {
            return CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT
        }
        if (
            authorization.learnerId != learnerId ||
            authorization.requestFingerprint != grant.requestFingerprint ||
            authorization.expiresAtEpochMillis != grant.expiresAtEpochMillis ||
            !scope.matches(grant.session, grant.evidenceRequestId) ||
            authorityMaterials[grant.scopeId]?.fingerprint != grant.knowledgeAuthorityFingerprint
        ) {
            return CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED
        }
        val result = authority.consumeCurrentTutorOpenResponseAuthorization(
            grant.toConsumeCommand(
                candidateScopeFingerprint = CanonicalSha256(
                    "current-tutor-open-response-candidate-scope-v1",
                )
                    .field("persistedScopeId", grant.scopeId)
                    .field("proposalScope", scope.canonicalFingerprint)
                    .finish(),
                candidateIdempotencyKey = candidateIdempotencyKey,
                occurredAtEpochMillis = nowEpochMillis.getAsLong(),
            ),
        )
        return when (result.disposition) {
            DatabaseOpenResponseConsumeDisposition.CONSUMED ->
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.CONSUMED
            DatabaseOpenResponseConsumeDisposition.DUPLICATE ->
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.DUPLICATE
            DatabaseOpenResponseConsumeDisposition.NOT_CURRENT ->
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT
            DatabaseOpenResponseConsumeDisposition.REJECTED ->
                CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED
        }
    }

    suspend fun currentLearningEvidenceReference(
        activation: CurrentTutorSessionActivation,
    ): TutorLearningEvidenceCurrentSessionReference? {
        if (
            closed.get() || activation.learnerId != learnerId ||
            !activation.learningWritesAllowed || activation.scopeId !in learningWriteEnabledScopes
        ) return null
        val bundle = loadCurrent(activation.conversationId) ?: return null
        if (closed.get()) return null
        if (
            bundle.hasAnswerExposure() ||
            bundle.scope.scopeId != activation.scopeId ||
            bundle.scope.activationFingerprint != activation.activationFingerprint ||
            bundle.scope.knowledgeAuthorityFingerprint != activation.knowledgeAuthorityFingerprint ||
            authorityMaterials[activation.scopeId]?.fingerprint !=
                activation.knowledgeAuthorityFingerprint
        ) return null
        return synchronized(lifecycleMonitor) {
            if (closed.get()) null
            else TutorLearningEvidenceCurrentSessionReference(
                authorizationFingerprint = activation.learningEvidenceAuthorizationFingerprint,
                learningWritePermissionVersion = activation.learningWritePermissionVersion,
            )
        }
    }

    suspend fun resolve(
        candidate: TutorLearningEvidenceCandidate,
    ): List<VerifiedKnowledgeReferenceProof>? {
        if (closed.get() || candidate.learnerId != learnerId) return null
        val scopeId = authorityConversationScopes[candidate.conversationId] ?: return null
        if (scopeId !in learningWriteEnabledScopes) return null
        val cached = current.values.singleOrNull { it.scope.scopeId == scopeId } ?: return null
        val bundle = loadCurrent(cached.scope.conversationId) ?: return null
        if (bundle.scope.scopeId != scopeId) return null
        if (bundle.hasAnswerExposure()) return null
        val session = bundle.scope
        if (!session.matches(candidate)) return null
        val requestIsAuthorized =
            when (session.explanationMode) {
                TutorExplanationMode.DIRECT ->
                    candidate.evidenceRequestId == session.turnReferenceId
                TutorExplanationMode.GUIDED ->
                    authority.openTutorEvidenceRequest(learnerId, candidate.evidenceRequestId)
                        ?.let { request ->
                            request.status == TutorEvidenceRequestStatus.PENDING &&
                                request.kind == candidate.kind &&
                                request.matchesAuthority(session)
                        } == true
            }
        if (!requestIsAuthorized) return null
        val material = authorityMaterials[scopeId] ?: return null
        if (
            closed.get() ||
            material.fingerprint != session.knowledgeAuthorityFingerprint ||
            material.verifiedKnowledgeProofs.isEmpty()
        ) return null
        return synchronized(lifecycleMonitor) {
            if (closed.get()) null else material.verifiedKnowledgeProofs
        }
    }

    /**
     * Settles one already-persisted guided choice without accepting security or behavior metadata
     * from the feature. Every field is reloaded from the durable current scope, choice event, and
     * evidence request immediately before the mastery repository is called.
     */
    suspend fun finalizeRecordedChoice(
        response: TutorTurnResponse,
        learningMemory: TutorLearningMemoryRepository,
    ) {
        check(!closed.get()) { "Current tutor session owner is closed" }
        check(response.hasChoicePayload) { "Tutor learning finalization requires a choice fact" }
        val requestId = checkNotNull(response.evidenceRequestId) {
            "Tutor choice is not bound to a guided evidence request"
        }
        val bundle = checkNotNull(loadCurrent(response.sessionId)) {
            "Tutor choice is outside the current session"
        }
        val session = bundle.scope
        check(session.scopeId in learningWriteEnabledScopes) {
            "Tutor learning writes are not authorized for the current scope"
        }
        check(session.matchesRecordedChoice(response)) {
            "Tutor choice differs from the current durable scope"
        }
        check(session.explanationMode == TutorExplanationMode.GUIDED) {
            "Only a guided choice can become tutor learning evidence"
        }
        check(!bundle.hasAnswerExposure()) {
            "Tutor answer exposure revokes learning evidence"
        }
        check(
            bundle.events.none { event ->
                event.eventKind == CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION &&
                    event.authorizationRequestId == requestId
            },
        ) { "Cancelled tutor choice cannot become learning evidence" }
        val choiceEvents = bundle.events.filter { event ->
            event.eventKind == CurrentTutorInteractionEventKind.CHOICE &&
                event.authorizationRequestId == requestId &&
                event.evidenceRequestId == requestId
        }
        check(choiceEvents.size == 1) {
            "Tutor choice request does not resolve to one exact durable fact"
        }
        val choice = choiceEvents.single()
        check(choice.matchesRecordedChoice(response, session)) {
            "Tutor choice response differs from its durable fact"
        }
        val request = checkNotNull(authority.openTutorEvidenceRequest(learnerId, requestId)) {
            "Tutor choice evidence request is outside the current learner scope"
        }
        check(request.kind == TutorEvidenceRequestKind.CHOICE && request.matchesAuthority(session)) {
            "Tutor choice evidence request differs from the current durable scope"
        }
        check(request.status != TutorEvidenceRequestStatus.CANCELLED) {
            "Cancelled tutor choice cannot become learning evidence"
        }
        check(
            loadCurrent(response.sessionId)?.let { currentBundle ->
                currentBundle.scope.scopeId == session.scopeId &&
                    currentBundle.head.currentScopeId == session.scopeId
            } == true,
        ) { "Tutor choice session changed before learning finalization" }

        learningMemory.finalizeEvidence(
            session.toChoiceFinalizationCommand(
                choice = choice,
                request = request,
            ),
        )
    }

    suspend fun commitTrustedSavedAnswer(
        command: CurrentTutorTrustedAnswerCommitCommand,
        finalizer: CurrentTutorTrustedEvidenceFinalizer,
    ): CurrentTutorTrustedAnswerSubmissionResult {
        if (closed.get() || command.learnerId != learnerId) {
            return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        }
        val before = loadCurrent(command.sessionId)
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val session = before.scope
        if (
            before.toEvidenceState() != command.expectedEvidenceState ||
            session.scopeId !in learningWriteEnabledScopes ||
            session.explanationMode != TutorExplanationMode.GUIDED ||
            before.hasAnswerExposure()
        ) return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val request = authority.openTutorEvidenceRequest(learnerId, command.evidenceRequestId)
            ?.takeIf { candidate ->
                candidate.kind == command.event.evidenceKind() &&
                    candidate.status != TutorEvidenceRequestStatus.CANCELLED &&
                    candidate.matchesAuthority(session)
            }
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val appendCommand = session.toTrustedSavedAnswerAppendCommand(command, before.head)
        if (
            before.events.any { event ->
                event.authorizationRequestId == command.evidenceRequestId &&
                    event.eventKind in TRUSTED_ANSWER_EVENT_KINDS &&
                    event.eventId != appendCommand.eventId
            }
        ) return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val append = try {
            authority.appendCurrentTutorInteraction(appendCommand)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        }
        val duplicate = when (append.disposition) {
            CurrentTutorInteractionAppendDisposition.APPLIED -> false
            CurrentTutorInteractionAppendDisposition.DUPLICATE -> true
            else -> return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        }
        val durableEvent = append.event
            ?.takeIf { event -> event.matchesTrustedSavedAnswer(appendCommand, command.evaluation) }
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val refreshed = authority.readCurrentTutorInteraction(learnerId, command.sessionId)
            ?.takeIf { bundle ->
                bundle.scope.scopeId == session.scopeId &&
                    bundle.head == append.head &&
                    !bundle.hasAnswerExposure() &&
                    bundle.events.singleOrNull { it.eventId == durableEvent.eventId } == durableEvent &&
                    bundle.events.count { event ->
                        event.authorizationRequestId == command.evidenceRequestId &&
                            event.eventKind in TRUSTED_ANSWER_EVENT_KINDS
                    } == 1
            }
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        current[command.sessionId] = refreshed
        val resultingState = refreshed.toEvidenceState()
        if (
            if (duplicate) resultingState != command.expectedEvidenceState
            else !resultingState.isTrustedAnswerSuccessorOf(command.expectedEvidenceState)
        ) return CurrentTutorTrustedAnswerSubmissionResult.Rejected

        val currentRequest = authority.openTutorEvidenceRequest(learnerId, request.evidenceRequestId)
            ?.takeIf { candidate ->
                candidate.kind == request.kind &&
                    candidate.status != TutorEvidenceRequestStatus.CANCELLED &&
                    candidate.matchesAuthority(refreshed.scope)
            }
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val finalization = try {
            finalizer.finalize(
                refreshed.scope.toTrustedAnswerFinalizationCommand(
                    response = durableEvent,
                    request = currentRequest,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        }
        val learningReceipt = when (finalization) {
            is FinalizeTutorEvidenceResult.Submitted -> finalization.receipt
            is FinalizeTutorEvidenceResult.Replayed ->
                finalization.receipt?.takeIf {
                    finalization.request.status == TutorEvidenceRequestStatus.SUBMITTED
                }
            is FinalizeTutorEvidenceResult.Cancelled -> null
        } ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val finalState = authority.readCurrentTutorInteraction(learnerId, command.sessionId)
            ?.takeIf { it.head == refreshed.head && !it.hasAnswerExposure() }
            ?.toEvidenceState()
            ?: return CurrentTutorTrustedAnswerSubmissionResult.Rejected
        val receiptFingerprint = CanonicalSha256("current-tutor-trusted-answer-commit-receipt-v1")
            .field("sessionId", command.sessionId)
            .field("presentationToken", command.presentationToken)
            .field("evidenceRequestId", command.evidenceRequestId)
            .field("exactContentBinding", command.exactContentBinding)
            .field("responseForm", command.event.responseForm.name)
            .field("responseBinding", command.evaluation.responseBinding)
            .field("eventId", durableEvent.eventId)
            .field("eventPayloadFingerprint", durableEvent.payloadFingerprint)
            .field("duplicate", duplicate)
            .field("scopeId", finalState.scopeId)
            .field("activationFingerprint", finalState.activationFingerprint)
            .field("presentationFingerprint", finalState.presentationFingerprint)
            .field("stateVersion", finalState.stateVersion)
            .field("stateFingerprint", finalState.stateFingerprint)
            .field("attemptOrdinal", finalState.attemptOrdinal)
            .field("hintCount", finalState.hintCount)
            .field("answerWasRevealed", finalState.answerWasRevealed)
            .field("learningReceiptFingerprint", learningReceipt.receiptFingerprint)
            .finish()
        return CurrentTutorTrustedAnswerSubmissionResult.Committed(
            CurrentTutorTrustedAnswerCommitReceipt(
                sessionId = command.sessionId,
                presentationToken = command.presentationToken,
                evidenceRequestId = command.evidenceRequestId,
                exactContentBinding = command.exactContentBinding,
                responseForm = command.event.responseForm,
                responseBinding = command.evaluation.responseBinding,
                eventId = durableEvent.eventId,
                eventPayloadFingerprint = durableEvent.payloadFingerprint,
                duplicate = duplicate,
                resultingEvidenceState = finalState,
                learningReceiptFingerprint = learningReceipt.receiptFingerprint,
                canonicalFingerprint = receiptFingerprint,
            ),
        )
    }

    suspend fun currentEvidenceState(
        conversationId: String,
    ): CurrentTutorSessionEvidenceState? {
        if (closed.get()) return null
        val bundle = loadCurrent(conversationId) ?: return null
        if (closed.get()) return null
        return bundle.toEvidenceState()
    }

    suspend fun recordHintShown(
        commit: CurrentTutorHintShownCommit,
    ): CurrentTutorHintShownCommitResult {
        if (closed.get()) return CurrentTutorHintShownCommitResult.REJECTED
        val bundle = loadCurrent(commit.sessionId)
            ?: return CurrentTutorHintShownCommitResult.REJECTED
        val session = bundle.scope
        if (
            closed.get() ||
            session.scopeId != commit.expectedScopeId ||
            session.questionDocumentId != commit.expectedQuestionDocumentId ||
            session.questionRevisionNumber != commit.expectedQuestionRevisionNumber ||
            session.cycleOrdinal != commit.expectedCycleOrdinal ||
            session.turnOrdinal != commit.expectedTurnOrdinal ||
            session.presentationFingerprint != commit.expectedPresentationFingerprint ||
            session.explanationMode != TutorExplanationMode.GUIDED ||
            session.modeVersion != commit.modeVersion ||
            bundle.hasAnswerExposure()
        ) return CurrentTutorHintShownCommitResult.REJECTED
        val payloadFingerprint = CanonicalSha256("current-tutor-hint-shown-v1")
            .field("scopeId", session.scopeId)
            .field("questionDocumentId", commit.expectedQuestionDocumentId)
            .field("questionRevisionNumber", commit.expectedQuestionRevisionNumber)
            .field("cycleOrdinal", commit.expectedCycleOrdinal)
            .field("turnOrdinal", commit.expectedTurnOrdinal)
            .field("presentationFingerprint", commit.expectedPresentationFingerprint)
            .field("modeVersion", commit.modeVersion)
            .field("slotToken", commit.slotToken)
            .finish()
        val eventId = "current-tutor-hint:${commit.slotToken}"
        if (session.hintCount != 0) {
            val exactReplay = session.hintCount == 1 && bundle.events.any { event ->
                event.eventId == eventId &&
                    event.eventKind == CurrentTutorInteractionEventKind.HINT_SHOWN &&
                    event.payloadFingerprint == payloadFingerprint &&
                    event.scopeId == session.scopeId &&
                    event.questionDocumentId == session.questionDocumentId &&
                    event.questionRevisionNumber == session.questionRevisionNumber &&
                    event.cycleOrdinal == session.cycleOrdinal &&
                    event.turnOrdinal == session.turnOrdinal &&
                    event.modeVersion == session.modeVersion
            }
            return if (exactReplay) {
                CurrentTutorHintShownCommitResult.DUPLICATE
            } else {
                CurrentTutorHintShownCommitResult.REJECTED
            }
        }
        val result = authority.appendCurrentTutorInteraction(
            AppendCurrentTutorInteractionCommand(
                scopeId = session.scopeId,
                learnerId = session.learnerId,
                conversationId = session.conversationId,
                conversationGeneration = session.conversationGeneration,
                conversationStateVersion = session.conversationStateVersion,
                questionDocumentId = session.questionDocumentId,
                questionRevisionNumber = session.questionRevisionNumber,
                questionFingerprint = session.questionFingerprint,
                subject = session.subject,
                problemAnchorId = session.problemAnchorId,
                explanationMode = session.explanationMode,
                modeVersion = session.modeVersion,
                learningWritePermissionVersion = session.learningWritePermissionVersion,
                turnReferenceId = session.turnReferenceId,
                turnOrdinal = session.turnOrdinal,
                turnGeneration = session.turnGeneration,
                cycleOrdinal = session.cycleOrdinal,
                attemptOrdinal = session.attemptOrdinal.coerceAtLeast(1),
                hintCount = session.hintCount,
                answerWasRevealed = false,
                expectedStateVersion = bundle.head.stateVersion,
                expectedStateFingerprint = bundle.head.stateFingerprint,
                eventId = eventId,
                eventKind = CurrentTutorInteractionEventKind.HINT_SHOWN,
                authorizationPurpose = CURRENT_TUTOR_HINT_SHOWN_PURPOSE,
                authorizationRequestId = null,
                idempotencyKey = eventId,
                requestVersion = session.requestVersion,
                payloadFingerprint = payloadFingerprint,
                occurredAtEpochMillis = commit.occurredAtEpochMillis,
                requestedMove = CURRENT_TUTOR_HINT_SHOWN_VALUE,
            ),
        )
        val refreshed = authority.readCurrentTutorInteraction(learnerId, commit.sessionId)
        if (refreshed != null) current[commit.sessionId] = refreshed
        return when (result.disposition) {
            CurrentTutorInteractionAppendDisposition.APPLIED -> if (
                refreshed != null &&
                refreshed.scope.scopeId == session.scopeId &&
                refreshed.scope.questionDocumentId == session.questionDocumentId &&
                refreshed.scope.questionRevisionNumber == session.questionRevisionNumber &&
                refreshed.scope.cycleOrdinal == session.cycleOrdinal &&
                refreshed.scope.turnOrdinal == session.turnOrdinal &&
                refreshed.scope.modeVersion == session.modeVersion &&
                refreshed.scope.presentationFingerprint == session.presentationFingerprint &&
                refreshed.scope.attemptOrdinal == session.attemptOrdinal &&
                refreshed.scope.hintCount == 1 &&
                !refreshed.hasAnswerExposure()
            ) {
                CurrentTutorHintShownCommitResult.RECORDED
            } else {
                CurrentTutorHintShownCommitResult.REJECTED
            }
            CurrentTutorInteractionAppendDisposition.DUPLICATE -> if (
                refreshed?.events?.any { event ->
                    event.eventId == eventId &&
                        event.eventKind == CurrentTutorInteractionEventKind.HINT_SHOWN &&
                        event.payloadFingerprint == payloadFingerprint
                } == true
            ) {
                CurrentTutorHintShownCommitResult.DUPLICATE
            } else {
                CurrentTutorHintShownCommitResult.REJECTED
            }
            else -> CurrentTutorHintShownCommitResult.REJECTED
        }
    }

    fun isOpen(): Boolean = !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ownerScope.cancel()
        synchronized(lifecycleMonitor) {
            observers.clear()
            current.clear()
            interactionGrants.clear()
            openResponseGrants.clear()
            authorityMaterials.clear()
            authorityConversationScopes.clear()
            learningWriteEnabledScopes.clear()
        }
    }

    private suspend fun loadCurrent(conversationId: String): CurrentTutorInteractionBundle? {
        val bundle = authority.readCurrentTutorInteraction(learnerId, conversationId) ?: return null
        if (closed.get()) return null
        current[conversationId] = bundle
        observeInBackground(conversationId)
        return bundle
    }

    private fun observeInBackground(conversationId: String) {
        if (closed.get()) return
        observers.computeIfAbsent(conversationId) {
            ownerScope.launch {
                    authority.observeCurrentTutorInteraction(learnerId, conversationId)
                    .collect { bundle ->
                        if (!closed.get()) {
                            if (bundle == null) current.remove(conversationId)
                            else current[conversationId] = bundle
                        }
                    }
            }
        }
    }

    private suspend fun requestMatches(
        session: CurrentTutorInteractionScopeRecord,
        requestId: String,
        purpose: TutorCurrentInteractionAuthorizationPurpose,
    ): Boolean {
        if (purpose == TutorCurrentInteractionAuthorizationPurpose.RECORD_EXPOSURE) {
            return when (session.explanationMode) {
                TutorExplanationMode.DIRECT -> requestId == session.turnReferenceId
                TutorExplanationMode.GUIDED ->
                    authority.openTutorEvidenceRequest(learnerId, requestId)
                        ?.let { request ->
                            request.status == TutorEvidenceRequestStatus.PENDING &&
                                request.matchesAuthority(session)
                        } == true
            }
        }
        val request = authority.openTutorEvidenceRequest(learnerId, requestId) ?: return false
        if (!request.matchesAuthority(session)) return false
        return when (purpose) {
            TutorCurrentInteractionAuthorizationPurpose.RECORD_CHOICE ->
                request.status == TutorEvidenceRequestStatus.PENDING &&
                    request.kind == TutorEvidenceRequestKind.CHOICE
            TutorCurrentInteractionAuthorizationPurpose.RECORD_VISUAL_SELECTION ->
                request.status == TutorEvidenceRequestStatus.PENDING &&
                    request.kind == TutorEvidenceRequestKind.VISUAL_TARGET
            TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE ->
                request.status == TutorEvidenceRequestStatus.PENDING ||
                    request.status == TutorEvidenceRequestStatus.CANCELLED
            else -> request.status != TutorEvidenceRequestStatus.CANCELLED
        }
    }

    private fun rejected(
        operation: SessionOperationIdentity,
        occurredAtEpochMillis: Long,
    ) = TutorInteractionSessionMutationResult(
        receipt = SessionMutationReceipt(
            operation = operation,
            disposition = SessionMutationDisposition.REJECTED,
            currentVersion = null,
            recordedAtEpochMillis = maxOf(nowEpochMillis.getAsLong(), occurredAtEpochMillis),
        ),
    )

    private companion object {
        const val MAX_OPEN_RESPONSE_GRANT_MILLIS = 120_000L
    }
}

private data class OpenResponseAuthorityMaterial(
    val fingerprint: String,
    val verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof>,
    val teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    val prohibitedEvaluatorExecutionFingerprint: String?,
)

private data class InteractionGrant(
    val authorization: TutorCurrentInteractionAuthorization,
    val scopeId: String,
)

private data class OpenResponseGrant(
    val session: CurrentTutorInteractionScopeRecord,
    val conversationId: String,
    val scopeId: String,
    val stateVersion: Long,
    val stateFingerprint: String,
    val evidenceRequestId: String,
    val requestFingerprint: String,
    val knowledgeAuthorityFingerprint: String,
    val expiresAtEpochMillis: Long,
)

private fun OpenResponseGrant.toConsumeCommand(
    candidateScopeFingerprint: String,
    candidateIdempotencyKey: String,
    occurredAtEpochMillis: Long,
) = ConsumeCurrentTutorOpenResponseAuthorizationCommand(
    scopeId = session.scopeId,
    learnerId = session.learnerId,
    conversationId = session.conversationId,
    conversationGeneration = session.conversationGeneration,
    conversationStateVersion = session.conversationStateVersion,
    questionDocumentId = session.questionDocumentId,
    questionRevisionNumber = session.questionRevisionNumber,
    questionFingerprint = session.questionFingerprint,
    subject = session.subject,
    problemAnchorId = session.problemAnchorId,
    explanationMode = session.explanationMode,
    modeVersion = session.modeVersion,
    learningWritePermissionVersion = session.learningWritePermissionVersion,
    turnReferenceId = session.turnReferenceId,
    turnOrdinal = session.turnOrdinal,
    turnGeneration = session.turnGeneration,
    cycleOrdinal = session.cycleOrdinal,
    attemptOrdinal = session.attemptOrdinal,
    hintCount = session.hintCount,
    answerWasRevealed = session.answerWasRevealed,
    requestVersion = session.requestVersion,
    expectedStateVersion = stateVersion,
    expectedStateFingerprint = stateFingerprint,
    evidenceRequestId = evidenceRequestId,
    candidateScopeFingerprint = candidateScopeFingerprint,
    candidateIdempotencyKey = candidateIdempotencyKey,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun CurrentOpenResponseLearningScope.matches(
    session: CurrentTutorInteractionScopeRecord,
    authorizedEvidenceRequestId: String,
): Boolean =
    learnerId == session.learnerId &&
        conversationId == session.conversationId &&
        conversationGeneration == session.conversationGeneration &&
        conversationStateVersion == session.conversationStateVersion &&
        questionDocumentId == session.questionDocumentId &&
        questionRevisionNumber == session.questionRevisionNumber &&
        subject == session.subject &&
        questionFingerprint == session.questionFingerprint &&
        evidenceRequestId == authorizedEvidenceRequestId &&
        modeVersion == session.modeVersion &&
        turnReferenceId == session.turnReferenceId &&
        turnOrdinal == session.turnOrdinal &&
        turnGeneration == session.turnGeneration &&
        attemptOrdinal == session.attemptOrdinal &&
        hintCount == session.hintCount &&
        answerWasRevealed == session.answerWasRevealed &&
        requestVersion == session.requestVersion

private fun CurrentTutorSessionActivation.toAuthorityMaterial() = OpenResponseAuthorityMaterial(
    fingerprint = knowledgeAuthorityFingerprint,
    verifiedKnowledgeProofs = verifiedKnowledgeProofs,
    teachingReferences = teachingReferences,
    prohibitedEvaluatorExecutionFingerprint = prohibitedEvaluatorExecutionFingerprint,
)

private fun CurrentTutorSessionActivation.toDatabaseCommand() =
    ActivateCurrentTutorInteractionCommand(
        scopeId = scopeId,
        learnerId = learnerId,
        conversationId = conversationId,
        conversationGeneration = conversationGeneration,
        conversationStateVersion = conversationStateVersion,
        authorityConversationId = authorityConversationId,
        authorityConversationGeneration = authorityConversationGeneration,
        authorityConversationStateVersion = authorityConversationStateVersion,
        authorityTurnReceiptId = authorityTurnReceiptId,
        authorityTurnOrdinal = authorityTurnOrdinal,
        authorityRequestVersion = authorityRequestVersion,
        questionDocumentId = questionDocument.id,
        questionRevisionNumber = questionRevisionNumber,
        questionDocumentSnapshot = QUESTION_DOCUMENT_JSON.encodeToString(questionDocument),
        questionFingerprint = questionFingerprint,
        subject = subject,
        problemAnchorId = problemAnchorId,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        turnReferenceId = turnReferenceId,
        turnOrdinal = turnOrdinal,
        turnGeneration = turnGeneration,
        cycleOrdinal = cycleOrdinal,
        attemptOrdinal = attemptOrdinal,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        requestVersion = requestVersion,
        learningWritePermissionVersion = learningWritePermissionVersion,
        presentationFingerprint = presentationFingerprint,
        problemFingerprint = problemFingerprint,
        problemFamilyFingerprint = problemFamilyFingerprint,
        attributionPolicyVersion = attributionPolicyVersion,
        responsePolicyVersion = responsePolicyVersion,
        rubricCanonicalFingerprint = rubricCanonicalFingerprint,
        knowledgeAuthorityFingerprint = knowledgeAuthorityFingerprint,
        evaluator = evaluator.name,
        evaluatorPolicyFingerprint = evaluatorPolicyFingerprint,
        activationFingerprint = activationFingerprint,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

private fun TutorAuthorizedInteractionSessionMutation.toDatabaseCommand(
    scopeId: String,
):
    AppendCurrentTutorInteractionCommand {
    val value = mutation
    val common = DatabaseEventPayload.from(value)
    return AppendCurrentTutorInteractionCommand(
        scopeId = scopeId,
        learnerId = authorization.context.scope.learnerId,
        conversationId = authorization.context.conversationId,
        conversationGeneration = authorization.context.conversationGeneration,
        conversationStateVersion = authorization.context.conversationStateVersion,
        questionDocumentId = authorization.context.questionDocumentId,
        questionRevisionNumber = authorization.context.revisionNumber,
        questionFingerprint = authorization.context.questionFingerprint,
        subject = authorization.context.subject,
        problemAnchorId = authorization.context.problemAnchorId,
        explanationMode = authorization.context.explanationMode,
        modeVersion = authorization.context.modeVersion,
        learningWritePermissionVersion = authorization.context.learningWritePermissionVersion,
        turnReferenceId = authorization.context.turnReferenceId,
        turnOrdinal = authorization.context.turnOrdinal,
        turnGeneration = authorization.context.turnGeneration,
        cycleOrdinal = authorization.context.cycleOrdinal,
        attemptOrdinal = authorization.context.attemptOrdinal,
        hintCount = authorization.context.hintCount,
        answerWasRevealed = authorization.context.answerWasRevealed,
        expectedStateVersion = authorization.context.version.sequence,
        expectedStateFingerprint = authorization.context.version.fingerprint,
        eventId = value.operation.requestId,
        eventKind = common.kind,
        authorizationPurpose = authorization.purpose.name,
        authorizationRequestId = authorization.requestId,
        idempotencyKey = value.operation.idempotencyKey,
        requestVersion = authorization.context.requestVersion,
        payloadFingerprint = value.operation.payloadFingerprint,
        occurredAtEpochMillis = value.occurredAtEpochMillis,
        diagnosticStemMarkdown = common.diagnosticStemMarkdown,
        selectedChoiceId = common.selectedChoiceId,
        selectedChoiceMarkdown = common.selectedChoiceMarkdown,
        selectionWasCorrect = common.selectionWasCorrect,
        feedbackMarkdown = common.feedbackMarkdown,
        evidenceRequestId = common.evidenceRequestId,
        requestedMove = common.requestedMove,
        solutionRevealed = common.solutionRevealed,
        surfaceKind = common.surfaceKind,
        modelTaskRequestId = common.modelTaskRequestId,
        responseOrdinal = common.responseOrdinal,
        sceneSourceKind = common.sceneSourceKind,
        sceneTaskRequestId = common.sceneTaskRequestId,
        sceneId = common.sceneId,
        sceneFingerprint = common.sceneFingerprint,
        hitProofId = common.hitProofId,
        panelId = common.panelId,
        frameFingerprint = common.frameFingerprint,
        stepIndex = common.stepIndex,
        selectedTargetId = common.selectedTargetId,
        targetRevisionRef = common.targetRevisionRef,
        targetPracticeRef = common.targetPracticeRef,
        sourceKind = common.sourceKind,
    )
}

private data class DatabaseEventPayload(
    val kind: CurrentTutorInteractionEventKind,
    val diagnosticStemMarkdown: String? = null,
    val selectedChoiceId: String? = null,
    val selectedChoiceMarkdown: String? = null,
    val selectionWasCorrect: Boolean? = null,
    val feedbackMarkdown: String? = null,
    val evidenceRequestId: String? = null,
    val requestedMove: String? = null,
    val solutionRevealed: Boolean = false,
    val surfaceKind: String? = null,
    val modelTaskRequestId: String? = null,
    val responseOrdinal: Int? = null,
    val sceneSourceKind: String? = null,
    val sceneTaskRequestId: String? = null,
    val sceneId: String? = null,
    val sceneFingerprint: String? = null,
    val hitProofId: String? = null,
    val panelId: String? = null,
    val frameFingerprint: String? = null,
    val stepIndex: Int? = null,
    val selectedTargetId: String? = null,
    val targetRevisionRef: String? = null,
    val targetPracticeRef: String? = null,
    val sourceKind: String? = null,
) {
    companion object {
        fun from(value: TutorInteractionSessionMutation): DatabaseEventPayload = when (value) {
            is TutorInteractionSessionMutation.RecordChoice -> DatabaseEventPayload(
                kind = CurrentTutorInteractionEventKind.CHOICE,
                diagnosticStemMarkdown = value.diagnosticStemMarkdown,
                selectedChoiceId = value.selectedChoiceId,
                selectedChoiceMarkdown = value.selectedChoiceMarkdown,
                selectionWasCorrect = value.selectionWasCorrect,
                feedbackMarkdown = value.feedbackMarkdown,
                evidenceRequestId = value.evidenceRequestId,
            )
            is TutorInteractionSessionMutation.RecordVisualSelection -> DatabaseEventPayload(
                kind = CurrentTutorInteractionEventKind.VISUAL_SELECTION,
                evidenceRequestId = value.evidenceRequestId,
                surfaceKind = value.surfaceKind,
                modelTaskRequestId = value.modelTaskRequestId,
                responseOrdinal = value.responseOrdinal,
                sceneSourceKind = value.sceneSourceKind,
                sceneTaskRequestId = value.sceneTaskRequestId,
                sceneId = value.sceneId,
                sceneFingerprint = value.sceneFingerprint,
                hitProofId = value.hitProofId,
                panelId = value.panelId,
                frameFingerprint = value.frameFingerprint,
                stepIndex = value.stepIndex,
                selectedTargetId = value.selectedTargetId,
                selectionWasCorrect = value.selectionWasCorrect,
            )
            is TutorInteractionSessionMutation.CancelEvidence -> DatabaseEventPayload(
                kind = CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION,
                evidenceRequestId = value.evidenceRequestId,
            )
            is TutorInteractionSessionMutation.RecordMove -> DatabaseEventPayload(
                kind = CurrentTutorInteractionEventKind.MOVE,
                requestedMove = value.requestedMove,
            )
            is TutorInteractionSessionMutation.RevealSolution -> DatabaseEventPayload(
                kind = CurrentTutorInteractionEventKind.SOLUTION_REVEAL,
                solutionRevealed = true,
            )
            is TutorInteractionSessionMutation.RecordExposure -> DatabaseEventPayload(
                kind = CurrentTutorInteractionEventKind.ANSWER_EXPOSURE,
                surfaceKind = value.surfaceKind,
                modelTaskRequestId = value.modelTaskRequestId,
                responseOrdinal = value.responseOrdinal,
            )
            is TutorInteractionSessionMutation.AnchorSession -> DatabaseEventPayload(
                kind = CurrentTutorInteractionEventKind.SESSION_ANCHOR,
                targetRevisionRef = value.targetRevisionRef,
                targetPracticeRef = value.targetPracticeRef,
                sourceKind = value.sourceKind,
            )
        }
    }
}

