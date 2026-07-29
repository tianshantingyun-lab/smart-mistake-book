package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal const val DEFAULT_TUTOR_LEARNER_SCOPE_ID = "local-learner"
private const val TUTOR_LOBBY_REQUEST_VERSION = 1L
private const val TUTOR_LOBBY_CONVERSATION_PREFIX = "tutor-lobby:"

/** Owns only locally generated lobby identities; model output never selects this scope. */
internal class TutorLobbyConversationController(
    private val repository: TutorLearningMemoryRepository,
    private val learnerScopeId: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun loadInitialConversation(): TutorConversation {
        repository.latestActiveConversationInNamespace(
            learnerScopeId,
            TUTOR_LOBBY_CONVERSATION_PREFIX,
        )?.let { return it }
        return when (
            val legacy = repository.openConversation(
                OpenTutorConversationCommand(
                    learnerScopeId = learnerScopeId,
                    conversationId = TUTOR_LOBBY_CONVERSATION_ID,
                    conversationGeneration = 1,
                ),
            )
        ) {
            is OpenTutorConversationResult.Opened -> when (legacy.conversation.status) {
                TutorConversationStatus.ACTIVE -> legacy.conversation
                TutorConversationStatus.ARCHIVED -> createConversation(newConversationId())
            }

            OpenTutorConversationResult.NotFound -> createConversation(TUTOR_LOBBY_CONVERSATION_ID)
        }
    }

    suspend fun startNewConversation(current: TutorConversation): TutorConversation {
        val refreshed = (repository.openConversation(
            OpenTutorConversationCommand(
                learnerScopeId = learnerScopeId,
                conversationId = current.conversationId,
                conversationGeneration = current.generation,
            ),
        ) as? OpenTutorConversationResult.Opened)?.conversation
            ?: error("The current tutor conversation is no longer available")
        check(refreshed.status == TutorConversationStatus.ACTIVE) {
            "The current tutor conversation is no longer active"
        }
        val archiveId = "tutor-archive:${newId()}"
        val archiveAt = now()
        repository.archiveConversation(
            ArchiveTutorConversationCommand(
                learnerScopeId = learnerScopeId,
                conversationId = refreshed.conversationId,
                conversationGeneration = refreshed.generation,
                expectedConversationStateVersion = refreshed.stateVersion,
                clientIdempotencyKey = archiveId,
                payloadFingerprint = sha256(
                    "archive:${refreshed.conversationId}:${refreshed.generation}:${refreshed.stateVersion}",
                ),
                occurredAtEpochMillis = archiveAt,
            ),
        )
        return createConversation(newConversationId())
    }

    suspend fun allocateTurn(
        conversation: TutorConversation,
        message: String,
        mode: TutorExplanationMode,
        modeVersion: Long,
        identity: TutorLobbyTurnIdentity,
        occurredAtEpochMillis: Long,
    ): TutorLobbyAllocatedTurn {
        val allocation = repository.allocateTurn(
            AllocateTutorTurnCommand(
                learnerScopeId = learnerScopeId,
                conversationId = conversation.conversationId,
                conversationGeneration = conversation.generation,
                expectedConversationStateVersion = conversation.stateVersion,
                expectedTurnOrdinal = conversation.stateVersion.toInt() + 1,
                turnReceiptId = identity.turnReceiptId,
                subject = SubjectKind.GENERAL,
                problemAnchorId = null,
                requestVersion = TUTOR_LOBBY_REQUEST_VERSION,
                modeVersion = modeVersion,
                mode = mode,
                directiveFingerprint = sha256("tutor-lobby:$mode"),
                studentMessageFingerprint = sha256(message),
                studentMessageSummary = message.take(TutorTurnReceipt.MAX_SUMMARY_CHARS),
                clientIdempotencyKey = identity.idempotencyKey,
                payloadFingerprint = sha256(
                    listOf(
                        conversation.conversationId,
                        conversation.generation,
                        conversation.stateVersion,
                        message,
                        mode,
                        modeVersion,
                        identity.turnReceiptId,
                    ).joinToString("\n"),
                ),
                occurredAtEpochMillis = occurredAtEpochMillis,
            ),
        )
        return TutorLobbyAllocatedTurn(allocation.conversation, allocation.receipt)
    }

    fun newTurnIdentity(): TutorLobbyTurnIdentity {
        val seed = newId()
        return TutorLobbyTurnIdentity(
            clientTurnId = "tutor-client-turn:$seed",
            turnReceiptId = "tutor-turn-receipt:$seed",
            idempotencyKey = "tutor-client-turn:$seed",
        )
    }

    private suspend fun createConversation(conversationId: String): TutorConversation {
        val idempotencyKey = "tutor-create:${newId()}"
        val occurredAt = now()
        return repository.createConversation(
            CreateTutorConversationCommand(
                learnerScopeId = learnerScopeId,
                conversationId = conversationId,
                conversationGeneration = 1,
                clientIdempotencyKey = idempotencyKey,
                payloadFingerprint = sha256("create:$conversationId:1"),
                occurredAtEpochMillis = occurredAt,
            ),
        ).conversation
    }

    private fun newConversationId(): String = "tutor-lobby:${newId()}"
}

internal data class TutorLobbyTurnIdentity(
    val clientTurnId: String,
    val turnReceiptId: String,
    val idempotencyKey: String,
)

internal data class TutorLobbyAllocatedTurn(
    val conversation: TutorConversation,
    val receipt: TutorTurnReceipt,
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
