package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.data.session.SessionMutationDisposition
import com.tingyun.smartmistakebook.core.data.session.SessionMutationReceipt
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.SessionVersion
import com.tingyun.smartmistakebook.core.data.session.TutorAnswerExposureSessionQuery
import com.tingyun.smartmistakebook.core.data.session.TutorAnswerExposureSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.TutorAuthorizedInteractionSessionMutation
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionAuthorization
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionAuthorizationPurpose
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionAuthorizationQuery
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionContext
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionRecord
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.TutorEvidenceCancellationSessionQuery
import com.tingyun.smartmistakebook.core.data.session.TutorInteractionSessionMutation
import com.tingyun.smartmistakebook.core.data.session.TutorInteractionSessionMutationResult
import com.tingyun.smartmistakebook.core.data.session.TutorInteractionSessionPort
import com.tingyun.smartmistakebook.core.data.session.TutorTurnSessionKey
import com.tingyun.smartmistakebook.core.data.session.TutorTurnResponseSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.TutorVisualSelectionSessionSnapshot
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.TutorEvidenceRejectedException
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CurrentSessionTutorInteractionRepositoryTest {
    @Test
    fun choiceWriterIsPermanentlyFailClosedBeforeSessionAuthorization() = runBlocking {
        val context = context()
        val sessions = FakeCurrentTutorInteractionSessionPort(context)
        sessions.registerRequest("guided-request", context)
        val command = choiceCommand("guided-request")

        assertRejected {
            CurrentSessionTutorInteractionRepositoryFactory.createProduction(sessions)
                .recordChoice(command)
        }
        assertRejected {
            CurrentSessionTutorInteractionRepositoryFactory.createProduction(sessions)
                .recordChoice(command)
        }

        assertEquals(0, sessions.choiceAppendCount)
        assertEquals(0, sessions.authorizationCount)
        assertTrue(
            CurrentSessionTutorInteractionRepositoryFactory.createProduction(sessions)
                .observe(context.conversationId)
                .first()
                .isEmpty(),
        )
    }

    @Test
    fun choiceRejectionDoesNotTouchAnySupersededSessionContext() = runBlocking {
        val original = context()
        val replacements = listOf(
            original.copy(
                conversationGeneration = original.conversationGeneration + 1,
                version = testVersion(2),
            ),
            original.copy(
                questionDocumentId = "question-2",
                revisionNumber = original.revisionNumber + 1,
                version = testVersion(2),
            ),
            original.copy(modeVersion = original.modeVersion + 1, version = testVersion(2)),
            original.copy(turnGeneration = original.turnGeneration + 1, version = testVersion(2)),
        )

        replacements.forEachIndexed { index, replacement ->
            val requestId = "late-request-$index"
            val sessions = FakeCurrentTutorInteractionSessionPort(original)
            sessions.registerRequest(requestId, original)
            sessions.beforeNextMutation = { sessions.replaceCurrent(replacement) }
            val repository =
                CurrentSessionTutorInteractionRepositoryFactory.createProduction(sessions)

            assertRejected { repository.recordChoice(choiceCommand(requestId)) }
            assertEquals(0, sessions.choiceAppendCount)
            assertEquals(0, sessions.authorizationCount)
            assertEquals(original, sessions.current(original.conversationId))
        }
    }

    @Test
    fun durableCancellationSurvivesRepositoryRecoveryAndBlocksLateEvidence() = runBlocking {
        val context = context()
        val sessions = FakeCurrentTutorInteractionSessionPort(context)
        sessions.registerRequest("cancelled-request", context)
        CurrentSessionTutorInteractionRepositoryFactory
            .createProduction(sessions)
            .cancelEvidence(
                CancelTutorEvidenceCommand(
                    sessionId = context.conversationId,
                    questionDocumentId = context.questionDocumentId,
                    revisionNumber = context.revisionNumber,
                    evidenceRequestId = "cancelled-request",
                    occurredAtEpochMillis = 20,
                ),
            )

        val recovered =
            CurrentSessionTutorInteractionRepositoryFactory.createProduction(sessions)
        assertTrue(
            recovered.isEvidenceCancelled(
                CancelTutorEvidenceCommand(
                    sessionId = context.conversationId,
                    questionDocumentId = context.questionDocumentId,
                    revisionNumber = context.revisionNumber,
                    evidenceRequestId = "cancelled-request",
                    occurredAtEpochMillis = 21,
                ),
            ),
        )
        assertRejected {
            recovered.recordChoice(choiceCommand("cancelled-request"))
        }
        assertEquals(0, sessions.choiceAppendCount)
    }

    @Test
    fun archivedChoiceRowsCannotBeReadByAnyConversationGeneration() = runBlocking {
        val original = context()
        val sessions = FakeCurrentTutorInteractionSessionPort(original)
        val repository =
            CurrentSessionTutorInteractionRepositoryFactory.createProduction(sessions)
        sessions.seedArchivedChoice()

        assertTrue(repository.observe(original.conversationId).first().isEmpty())

        sessions.replaceCurrent(
            sessions.current(original.conversationId).copy(
                conversationGeneration = original.conversationGeneration + 1,
                modeVersion = 0,
                turnGeneration = 1,
                version = testVersion(20),
            ),
        )

        assertTrue(repository.observe(original.conversationId).first().isEmpty())
        assertEquals(1, sessions.storedResponseCount)
    }

    @Test
    fun productionRepositoryHasOnlyTheNarrowCurrentSessionDependency() {
        val root = projectRoot()
        val production = File(
            root,
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                "CurrentSessionTutorInteractionRepository.kt",
        ).readText()
        val legacy = File(
            root,
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/tutor/" +
                "RoomTutorInteractionRepository.kt",
        ).readText()

        assertTrue(production.contains("sessions: TutorInteractionSessionPort"))
        assertTrue(production.contains("authorizeCurrent("))
        assertTrue(production.contains("mutateCurrent("))
        assertTrue(production.contains("student-owner-answer-required"))
        assertFalse(production.contains("TutorInteractionSessionMutation.RecordChoice("))
        assertFalse(production.contains("TutorInteractionSessionMutation.RecordVisualSelection("))
        listOf(
            "com.tingyun.smartmistakebook.core.database",
            "StudyDatabasePort",
            "TrustedTutorSessionDatabaseCapability",
            "LearningObservation",
            "LearningEvidence",
            "MasteryRepository",
            "KnowledgeRepository",
            "createLegacyPreCutover",
        ).forEach { forbidden ->
            assertFalse("$forbidden leaked into the production tutor writer", production.contains(forbidden))
        }
        assertTrue(legacy.contains("fun createLegacyPreCutover("))
        assertFalse(legacy.contains("fun createProduction("))
    }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected TutorEvidenceRejectedException")
        } catch (_: TutorEvidenceRejectedException) {
            Unit
        }
    }

    private fun choiceCommand(requestId: String) = RecordTutorChoiceCommand(
        sessionId = "conversation-1",
        questionDocumentId = "question-1",
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        diagnosticStemMarkdown = "先判断受力方向。",
        selectedChoiceId = "choice-b",
        selectedChoiceMarkdown = "向左",
        selectionWasCorrect = true,
        feedbackMarkdown = "方向判断正确。",
        occurredAtEpochMillis = 10,
        evidenceRequestId = requestId,
    )

    private fun context() = TutorCurrentInteractionContext(
        scope = SessionScope("learner-1"),
        conversationId = "conversation-1",
        conversationGeneration = 1,
        conversationStateVersion = 1,
        questionDocumentId = "question-1",
        revisionNumber = 1,
        questionFingerprint = "1".repeat(64),
        subject = SubjectKind.MATH,
        problemAnchorId = "problem-1",
        explanationMode = TutorExplanationMode.GUIDED,
        modeVersion = 3,
        learningWritePermissionVersion = 1,
        turnReferenceId = "turn-1",
        turnGeneration = 7,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        attemptOrdinal = 1,
        hintCount = 0,
        answerWasRevealed = false,
        requestVersion = 1,
        version = testVersion(1),
    )

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory ->
            directory.parentFile
        }.firstOrNull { directory ->
            File(directory, "settings.gradle.kts").isFile
        } ?: error("Could not locate the project root")

    private class FakeCurrentTutorInteractionSessionPort(
        initialContext: TutorCurrentInteractionContext,
    ) : TutorInteractionSessionPort {
        override val scope: SessionScope = initialContext.scope

        private val contexts =
            linkedMapOf(initialContext.conversationId to initialContext)
        private val streams = linkedMapOf(
            initialContext.conversationId to
                MutableStateFlow(snapshot(initialContext, emptyList())),
        )
        private val requestContexts = mutableMapOf<String, TutorCurrentInteractionContext>()
        private val issuedTokens = mutableSetOf<String>()
        private val cancellations = mutableSetOf<String>()
        private val responses =
            mutableListOf<TutorCurrentInteractionRecord<TutorTurnResponseSessionSnapshot>>()
        private val applied =
            mutableMapOf<String, TutorInteractionSessionMutationResult>()

        var choiceAppendCount: Int = 0
            private set
        var authorizationCount: Int = 0
            private set
        var beforeNextMutation: (() -> Unit)? = null
        var lastApplied: TutorAuthorizedInteractionSessionMutation? = null
            private set

        val storedResponseCount: Int
            get() = responses.size

        fun current(conversationId: String): TutorCurrentInteractionContext =
            requireNotNull(contexts[conversationId])

        fun registerRequest(
            requestId: String,
            context: TutorCurrentInteractionContext,
        ) {
            requestContexts[requestId] = context
        }

        fun replaceCurrent(context: TutorCurrentInteractionContext) {
            require(context.scope == scope)
            contexts[context.conversationId] = context
            emit(context.conversationId)
        }

        fun seedArchivedChoice() {
            val archivedContext = contexts.values.single()
            responses += TutorCurrentInteractionRecord(
                context = archivedContext,
                value = TutorTurnResponseSessionSnapshot(
                    scope = scope,
                    key = TutorTurnSessionKey(
                        sessionId = archivedContext.conversationId,
                        questionDocumentId = archivedContext.questionDocumentId,
                        revisionNumber = archivedContext.revisionNumber,
                        cycleOrdinal = archivedContext.cycleOrdinal,
                        turnOrdinal = archivedContext.turnOrdinal,
                    ),
                    version = archivedContext.version,
                    diagnosticStemMarkdown = "archived stem",
                    selectedChoiceId = "choice-b",
                    selectedChoiceMarkdown = "archived answer",
                    selectionWasCorrect = true,
                    feedbackMarkdown = "archived outcome",
                    requestedMove = null,
                    solutionRevealed = false,
                    choiceSubmittedAtEpochMillis = 10,
                    submittedAtEpochMillis = 10,
                    updatedAtEpochMillis = 10,
                    evidenceRequestId = "archived-request",
                ),
            )
            emit(archivedContext.conversationId)
        }

        override fun observeCurrent(
            conversationId: String,
        ): Flow<TutorCurrentInteractionSessionSnapshot> =
            requireNotNull(streams[conversationId]) {
                "No current test conversation: $conversationId"
            }

        override suspend fun authorizeCurrent(
            query: TutorCurrentInteractionAuthorizationQuery,
        ): TutorCurrentInteractionAuthorization? {
            authorizationCount += 1
            if (query.scope != scope) return null
            val current = contexts[query.conversationId] ?: return null
            if (
                query.questionDocumentId != null &&
                (
                    current.questionDocumentId != query.questionDocumentId ||
                        current.revisionNumber != query.revisionNumber
                    )
            ) {
                return null
            }
            if (
                query.cycleOrdinal != null &&
                (
                    current.cycleOrdinal != query.cycleOrdinal ||
                        current.turnOrdinal != query.turnOrdinal
                    )
            ) {
                return null
            }
            query.requestId?.let { requestId ->
                val requestContext = requestContexts[requestId] ?: return null
                if (!current.sameInteractionEpoch(requestContext)) return null
                if (
                    query.purpose !=
                    TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE &&
                    requestId in cancellations
                ) {
                    return null
                }
            }
            val token = buildString {
                append("grant-")
                append(query.purpose.name.lowercase())
                append('-')
                append(current.conversationGeneration)
                append('-')
                append(current.modeVersion)
                append('-')
                append(current.turnGeneration)
                append('-')
                append(query.requestId ?: "direct")
            }
            issuedTokens += token
            return TutorCurrentInteractionAuthorization(
                context = current,
                purpose = query.purpose,
                requestId = query.requestId,
                token = token,
            )
        }

        override suspend fun mutateCurrent(
            command: TutorAuthorizedInteractionSessionMutation,
        ): TutorInteractionSessionMutationResult {
            applied[command.mutation.operation.idempotencyKey]?.let { previous ->
                return previous.copy(
                    receipt = previous.receipt.copy(
                        disposition = SessionMutationDisposition.DUPLICATE,
                    ),
                )
            }
            beforeNextMutation?.also {
                beforeNextMutation = null
                it()
            }
            val authorization = command.authorization
            val current = contexts[authorization.context.conversationId]
            if (
                authorization.token !in issuedTokens ||
                current != authorization.context ||
                (
                    authorization.requestId in cancellations &&
                        authorization.purpose !=
                        TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE
                    )
            ) {
                return rejected(command, current?.version)
            }

            val nextContext = current.copy(
                version = testVersion(current.version.sequence + 1),
            )
            val result = when (val mutation = command.mutation) {
                is TutorInteractionSessionMutation.RecordChoice -> {
                    val stored = TutorTurnResponseSessionSnapshot(
                        scope = scope,
                        key = mutation.key,
                        version = nextContext.version,
                        diagnosticStemMarkdown = mutation.diagnosticStemMarkdown,
                        selectedChoiceId = mutation.selectedChoiceId,
                        selectedChoiceMarkdown = mutation.selectedChoiceMarkdown,
                        selectionWasCorrect = mutation.selectionWasCorrect,
                        feedbackMarkdown = mutation.feedbackMarkdown,
                        requestedMove = null,
                        solutionRevealed = false,
                        choiceSubmittedAtEpochMillis = mutation.occurredAtEpochMillis,
                        submittedAtEpochMillis = mutation.occurredAtEpochMillis,
                        updatedAtEpochMillis = mutation.occurredAtEpochMillis,
                        evidenceRequestId = mutation.evidenceRequestId,
                    )
                    responses += TutorCurrentInteractionRecord(
                        context = authorization.context,
                        value = stored,
                    )
                    choiceAppendCount += 1
                    accepted(command, nextContext.version, response = stored)
                }
                is TutorInteractionSessionMutation.CancelEvidence -> {
                    cancellations += mutation.evidenceRequestId
                    accepted(command, nextContext.version)
                }
                else -> rejected(command, current.version)
            }
            if (result.receipt.disposition == SessionMutationDisposition.APPLIED) {
                contexts[current.conversationId] = nextContext
                applied[command.mutation.operation.idempotencyKey] = result
                lastApplied = command
                emit(current.conversationId)
            }
            return result
        }

        override fun observeResponses(
            scope: SessionScope,
            sessionId: String,
        ): Flow<List<TutorTurnResponseSessionSnapshot>> = flowOf(emptyList())

        override fun observeVisualSelections(
            scope: SessionScope,
            sessionId: String,
        ): Flow<List<TutorVisualSelectionSessionSnapshot>> = flowOf(emptyList())

        override suspend fun isEvidenceCancelled(
            query: TutorEvidenceCancellationSessionQuery,
        ): Boolean =
            query.scope == scope && query.evidenceRequestId in cancellations

        override suspend fun readExposures(
            query: TutorAnswerExposureSessionQuery,
        ): List<TutorAnswerExposureSessionSnapshot> = emptyList()

        override suspend fun mutate(
            command: TutorInteractionSessionMutation,
        ): TutorInteractionSessionMutationResult =
            error("Production test must not use the frozen interaction path")

        private fun emit(conversationId: String) {
            val current = requireNotNull(contexts[conversationId])
            streams.getOrPut(conversationId) {
                MutableStateFlow(snapshot(current, emptyList()))
            }.value = snapshot(
                current = current,
                responses = responses.filter { current.owns(it.context) },
            )
        }

        private fun accepted(
            command: TutorAuthorizedInteractionSessionMutation,
            currentVersion: SessionVersion,
            response: TutorTurnResponseSessionSnapshot? = null,
        ) = TutorInteractionSessionMutationResult(
            receipt = SessionMutationReceipt(
                operation = command.mutation.operation,
                disposition = SessionMutationDisposition.APPLIED,
                currentVersion = currentVersion,
                recordedAtEpochMillis = command.mutation.occurredAtEpochMillis,
            ),
            response = response,
        )

        private fun rejected(
            command: TutorAuthorizedInteractionSessionMutation,
            currentVersion: SessionVersion?,
        ) = TutorInteractionSessionMutationResult(
            receipt = SessionMutationReceipt(
                operation = command.mutation.operation,
                disposition = SessionMutationDisposition.REJECTED,
                currentVersion = currentVersion,
                recordedAtEpochMillis = command.mutation.occurredAtEpochMillis,
            ),
        )

        private companion object {
            fun snapshot(
                current: TutorCurrentInteractionContext,
                responses:
                    List<TutorCurrentInteractionRecord<TutorTurnResponseSessionSnapshot>>,
            ) = TutorCurrentInteractionSessionSnapshot(
                current = current,
                responses = responses,
                visualSelections = emptyList(),
                exposures = emptyList(),
            )
        }
    }
}

private fun TutorCurrentInteractionContext.sameInteractionEpoch(
    other: TutorCurrentInteractionContext,
): Boolean =
    scope == other.scope &&
        conversationId == other.conversationId &&
        conversationGeneration == other.conversationGeneration &&
        conversationStateVersion == other.conversationStateVersion &&
        questionDocumentId == other.questionDocumentId &&
        revisionNumber == other.revisionNumber &&
        questionFingerprint == other.questionFingerprint &&
        subject == other.subject &&
        problemAnchorId == other.problemAnchorId &&
        explanationMode == other.explanationMode &&
        modeVersion == other.modeVersion &&
        learningWritePermissionVersion == other.learningWritePermissionVersion &&
        turnReferenceId == other.turnReferenceId &&
        turnGeneration == other.turnGeneration &&
        cycleOrdinal == other.cycleOrdinal &&
        turnOrdinal == other.turnOrdinal &&
        attemptOrdinal == other.attemptOrdinal &&
        hintCount == other.hintCount &&
        answerWasRevealed == other.answerWasRevealed &&
        requestVersion == other.requestVersion

private fun testVersion(sequence: Long) = SessionVersion(
    sequence = sequence,
    fingerprint = sequence.toString(16).padStart(64, '0'),
)
