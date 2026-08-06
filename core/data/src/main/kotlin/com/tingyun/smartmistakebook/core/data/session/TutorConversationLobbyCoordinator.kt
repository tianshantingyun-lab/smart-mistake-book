package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorLobbyAllocatedTurn
import com.tingyun.smartmistakebook.core.domain.TutorLobbyConversation
import com.tingyun.smartmistakebook.core.domain.TutorLobbyTurnRequest
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.LongSupplier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class TutorConversationLobbyCoordinator(
    private val learnerId: String,
    private val learningMemory: TutorLearningMemoryRepository,
    private val nowEpochMillis: LongSupplier = LongSupplier { System.currentTimeMillis() },
    private val newOpaqueId: () -> String = { UUID.randomUUID().toString() },
) : TutorConversationLobbyPort {
    private val ownerSecret = newOpaqueId()
    private val mutationMutex = Mutex()
    private val issued = ConcurrentHashMap<String, ConversationBinding>()

    init {
        requireLobbyId(learnerId)
    }

    override suspend fun openCurrent(): TutorLobbyConversation = mutationMutex.withLock {
        val current = learningMemory.latestActiveConversationInNamespace(
            learnerScopeId = learnerId,
            conversationIdPrefix = LOBBY_NAMESPACE,
        ) ?: openLegacyConversation() ?: createConversation(LEGACY_CONVERSATION_ID)
        current.issue()
    }

    override suspend fun startNew(
        currentConversationToken: String,
    ): TutorLobbyConversation = mutationMutex.withLock {
        val current = requireCurrent(currentConversationToken)
        val now = trustedNow()
        val archiveKey = "tutor-lobby-archive:${newOpaqueId()}"
        learningMemory.archiveConversation(
            ArchiveTutorConversationCommand(
                learnerScopeId = learnerId,
                conversationId = current.conversationId,
                conversationGeneration = current.generation,
                expectedConversationStateVersion = current.stateVersion,
                clientIdempotencyKey = archiveKey,
                payloadFingerprint = CanonicalSha256("tutor-lobby-archive-v1")
                    .field("conversationId", current.conversationId)
                    .field("generation", current.generation)
                    .field("stateVersion", current.stateVersion)
                    .finish(),
                occurredAtEpochMillis = now,
            ),
        )
        issued.remove(currentConversationToken)
        createConversation("$LOBBY_NAMESPACE${newOpaqueId()}").issue()
    }

    override suspend fun allocateTurn(
        request: TutorLobbyTurnRequest,
    ): TutorLobbyAllocatedTurn = mutationMutex.withLock {
        val current = requireCurrent(request.conversationToken)
        check(current.stateVersion < Int.MAX_VALUE.toLong()) {
            "Tutor lobby conversation reached its turn limit"
        }
        val turnSeed = newOpaqueId()
        val turnReceiptId = "tutor-lobby-turn:$turnSeed"
        val idempotencyKey = "tutor-lobby-client-turn:$turnSeed"
        val result = learningMemory.allocateTurn(
            AllocateTutorTurnCommand(
                learnerScopeId = learnerId,
                conversationId = current.conversationId,
                conversationGeneration = current.generation,
                expectedConversationStateVersion = current.stateVersion,
                expectedTurnOrdinal = current.stateVersion.toInt() + 1,
                turnReceiptId = turnReceiptId,
                subject = SubjectKind.GENERAL,
                problemAnchorId = null,
                requestVersion = LOBBY_REQUEST_VERSION,
                modeVersion = request.modeVersion,
                mode = request.explanationMode,
                directiveFingerprint = CanonicalSha256("tutor-lobby-directive-v1")
                    .field("mode", request.explanationMode.name)
                    .field("modeVersion", request.modeVersion)
                    .finish(),
                studentMessageFingerprint = CanonicalSha256("tutor-lobby-message-v1")
                    .field("message", request.studentMessage)
                    .finish(),
                studentMessageSummary = request.studentMessage.take(TutorTurnReceipt.MAX_SUMMARY_CHARS),
                clientIdempotencyKey = idempotencyKey,
                payloadFingerprint = CanonicalSha256("tutor-lobby-turn-payload-v1")
                    .field("conversationId", current.conversationId)
                    .field("generation", current.generation)
                    .field("stateVersion", current.stateVersion)
                    .field("message", request.studentMessage)
                    .field("mode", request.explanationMode.name)
                    .field("modeVersion", request.modeVersion)
                    .field("turnReceiptId", turnReceiptId)
                    .finish(),
                occurredAtEpochMillis = trustedNow(),
            ),
        )
        issued.remove(request.conversationToken)
        TutorLobbyAllocatedTurn(
            conversation = result.conversation.issue(),
            turnOrdinal = result.receipt.turnOrdinal,
        )
    }

    private suspend fun openLegacyConversation(): TutorConversation? = when (
        val opened = learningMemory.openConversation(
            OpenTutorConversationCommand(
                learnerScopeId = learnerId,
                conversationId = LEGACY_CONVERSATION_ID,
                conversationGeneration = 1L,
            ),
        )
    ) {
        is OpenTutorConversationResult.Opened ->
            opened.conversation.takeIf { conversation ->
                conversation.status == TutorConversationStatus.ACTIVE
            }
        OpenTutorConversationResult.NotFound -> null
    }

    private suspend fun createConversation(conversationId: String): TutorConversation {
        val key = "tutor-lobby-create:${newOpaqueId()}"
        return learningMemory.createConversation(
            CreateTutorConversationCommand(
                learnerScopeId = learnerId,
                conversationId = conversationId,
                conversationGeneration = 1L,
                clientIdempotencyKey = key,
                payloadFingerprint = CanonicalSha256("tutor-lobby-create-v1")
                    .field("conversationId", conversationId)
                    .field("generation", 1L)
                    .finish(),
                occurredAtEpochMillis = trustedNow(),
            ),
        ).conversation
    }

    private suspend fun requireCurrent(token: String): TutorConversation {
        val expected = issued[token] ?: error("Tutor lobby conversation token is not current")
        val opened = learningMemory.openConversation(
            OpenTutorConversationCommand(
                learnerScopeId = learnerId,
                conversationId = expected.conversationId,
                conversationGeneration = expected.generation,
            ),
        ) as? OpenTutorConversationResult.Opened
            ?: error("Tutor lobby conversation is not current")
        val current = opened.conversation
        check(
            current.status == TutorConversationStatus.ACTIVE &&
                current.conversationId == expected.conversationId &&
                current.generation == expected.generation &&
                current.stateVersion == expected.stateVersion &&
                current.token() == token
        ) { "Tutor lobby conversation changed" }
        return current
    }

    private fun TutorConversation.issue(): TutorLobbyConversation {
        check(status == TutorConversationStatus.ACTIVE)
        val token = token()
        issued[token] = ConversationBinding(conversationId, generation, stateVersion)
        return TutorLobbyConversation(conversationId, token)
    }

    private fun TutorConversation.token(): String = CanonicalSha256("tutor-lobby-token-v1")
        .field("ownerSecret", ownerSecret)
        .field("learnerId", learnerId)
        .field("conversationId", conversationId)
        .field("generation", generation)
        .field("stateVersion", stateVersion)
        .finish()

    private fun trustedNow(): Long = nowEpochMillis.getAsLong().coerceAtLeast(0L)

    private data class ConversationBinding(
        val conversationId: String,
        val generation: Long,
        val stateVersion: Long,
    )

    private companion object {
        const val LEGACY_CONVERSATION_ID = "tutor-lobby"
        const val LOBBY_NAMESPACE = "tutor-lobby:"
        const val LOBBY_REQUEST_VERSION = 1L
    }
}

private fun requireLobbyId(value: String) {
    require(
        value.isNotBlank() && value == value.trim() && value.length <= 256 &&
            value.none(Char::isISOControl),
    )
}
