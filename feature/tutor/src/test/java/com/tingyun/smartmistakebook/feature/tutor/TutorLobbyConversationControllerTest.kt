package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ArchiveTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.FinalizeTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceResult
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorLobbyConversationControllerTest {
    @Test
    fun firstUseCreatesTheCompatibleLegacyConversation() = runTest {
        val repository = FakeMemoryRepository()
        val conversation = controller(repository).loadInitialConversation()

        assertEquals(TUTOR_LOBBY_CONVERSATION_ID, conversation.conversationId)
        assertEquals(1, conversation.generation)
    }

    @Test
    fun latestActiveConversationWinsOverTheLegacyId() = runTest {
        val repository = FakeMemoryRepository().apply {
            put(activeConversation("tutor-lobby:active", createdAt = 20))
            put(activeConversation(TUTOR_LOBBY_CONVERSATION_ID, createdAt = 10))
        }

        assertEquals("tutor-lobby:active", controller(repository).loadInitialConversation().conversationId)
    }

    @Test
    fun archivedLegacyConversationStartsARandomNewConversation() = runTest {
        val repository = FakeMemoryRepository().apply {
            put(activeConversation(TUTOR_LOBBY_CONVERSATION_ID).copy(
                status = TutorConversationStatus.ARCHIVED,
                archivedAtEpochMillis = 2,
            ))
        }

        val conversation = controller(repository).loadInitialConversation()

        assertTrue(conversation.conversationId.startsWith("tutor-lobby:"))
        assertNotEquals(TUTOR_LOBBY_CONVERSATION_ID, conversation.conversationId)
    }

    @Test
    fun newConversationRefreshesThenArchivesBeforeCreatingAnEmptyConversation() = runTest {
        val repository = FakeMemoryRepository().apply { put(activeConversation("tutor-lobby:old")) }
        val controller = controller(repository)
        val next = controller.startNewConversation(controller.loadInitialConversation())

        assertEquals(TutorConversationStatus.ARCHIVED, repository.conversation("tutor-lobby:old")?.status)
        assertTrue(next.conversationId.startsWith("tutor-lobby:"))
        assertNotEquals("tutor-lobby:old", next.conversationId)
        assertEquals(0L, next.stateVersion)
    }

    @Test
    fun allocationUsesDatabaseOrdinalsAndReplaysOnePreparationIdentity() = runTest {
        val repository = FakeMemoryRepository()
        val controller = controller(repository)
        val conversation = controller.loadInitialConversation()
        val identity = controller.newTurnIdentity()

        val first = controller.allocateTurn(
            conversation, "第一条", TutorExplanationMode.DIRECT, 0, identity, 10,
        )
        val replay = controller.allocateTurn(
            conversation, "第一条", TutorExplanationMode.DIRECT, 0, identity, 10,
        )
        val second = controller.allocateTurn(
            first.conversation,
            "第二条",
            TutorExplanationMode.GUIDED,
            1,
            controller.newTurnIdentity(),
            11,
        )

        assertEquals(1, first.receipt.turnOrdinal)
        assertEquals(1, replay.receipt.turnOrdinal)
        assertEquals(2, second.receipt.turnOrdinal)
        assertEquals(2, repository.allocateCalls)
        assertEquals(TutorExplanationMode.GUIDED, second.receipt.explanationMode)
        assertEquals(1L, second.receipt.modeVersion)
    }

    private fun controller(repository: FakeMemoryRepository) = TutorLobbyConversationController(
        repository = repository,
        learnerScopeId = "learner",
        now = { 1 },
    )
}

private class FakeMemoryRepository : TutorLearningMemoryRepository {
    private val conversations = linkedMapOf<String, TutorConversation>()
    private val receipts = mutableMapOf<String, Pair<TutorConversation, TutorTurnReceipt>>()
    var allocateCalls = 0
        private set

    fun put(conversation: TutorConversation) {
        conversations[conversation.conversationId] = conversation
    }

    fun conversation(id: String): TutorConversation? = conversations[id]

    override suspend fun createConversation(command: CreateTutorConversationCommand): CreateTutorConversationResult {
        val conversation = activeConversation(command.conversationId, createdAt = command.occurredAtEpochMillis)
        conversations[conversation.conversationId] = conversation
        return CreateTutorConversationResult.Created(conversation)
    }

    override suspend fun openConversation(command: OpenTutorConversationCommand): OpenTutorConversationResult =
        conversations[command.conversationId]
            ?.takeIf { it.learnerScopeId == command.learnerScopeId && it.generation == command.conversationGeneration }
            ?.let(OpenTutorConversationResult::Opened)
            ?: OpenTutorConversationResult.NotFound

    override suspend fun latestActiveConversation(learnerScopeId: String): TutorConversation? = conversations
        .values
        .filter { it.learnerScopeId == learnerScopeId && it.status == TutorConversationStatus.ACTIVE }
        .maxByOrNull(TutorConversation::createdAtEpochMillis)

    override suspend fun archiveConversation(command: ArchiveTutorConversationCommand): ArchiveTutorConversationResult {
        val current = requireNotNull(conversations[command.conversationId])
        check(current.stateVersion == command.expectedConversationStateVersion)
        val archived = current.copy(
            status = TutorConversationStatus.ARCHIVED,
            archivedAtEpochMillis = command.occurredAtEpochMillis,
        )
        conversations[current.conversationId] = archived
        return ArchiveTutorConversationResult.Archived(archived)
    }

    override suspend fun allocateTurn(command: AllocateTutorTurnCommand): AllocateTutorTurnResult {
        receipts[command.clientIdempotencyKey]?.let { (conversation, receipt) ->
            return AllocateTutorTurnResult.Replayed(conversation, receipt)
        }
        val current = requireNotNull(conversations[command.conversationId])
        check(current.status == TutorConversationStatus.ACTIVE)
        check(current.stateVersion == command.expectedConversationStateVersion)
        check(command.expectedTurnOrdinal == current.stateVersion.toInt() + 1)
        allocateCalls += 1
        val updated = current.copy(stateVersion = current.stateVersion + 1)
        val receipt = TutorTurnReceipt(
            turnReceiptId = command.turnReceiptId,
            conversationId = current.conversationId,
            conversationGeneration = current.generation,
            conversationStateVersion = updated.stateVersion,
            turnOrdinal = command.expectedTurnOrdinal,
            subject = SubjectKind.GENERAL,
            problemAnchorId = null,
            requestVersion = command.requestVersion,
            modeVersion = command.modeVersion,
            explanationMode = command.mode,
            directiveFingerprint = command.directiveFingerprint,
            studentMessageFingerprint = command.studentMessageFingerprint,
            studentMessageSummary = command.studentMessageSummary,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        conversations[current.conversationId] = updated
        receipts[command.clientIdempotencyKey] = updated to receipt
        return AllocateTutorTurnResult.Created(updated, receipt)
    }

    override suspend fun prepareEvidenceRequest(command: PrepareTutorEvidenceCommand): PrepareTutorEvidenceResult =
        error("Not used by lobby")

    override suspend fun finalizeEvidence(command: FinalizeTutorEvidenceCommand): FinalizeTutorEvidenceResult =
        error("Not used by lobby")
}

private fun activeConversation(id: String, createdAt: Long = 1) = TutorConversation(
    conversationId = id,
    learnerScopeId = "learner",
    generation = 1,
    status = TutorConversationStatus.ACTIVE,
    createdAtEpochMillis = createdAt,
    archivedAtEpochMillis = null,
    stateVersion = 0,
)
