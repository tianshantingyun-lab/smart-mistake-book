package com.tingyun.smartmistakebook.core.model

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TutorLearningMemoryContractsTest {
    @Test
    fun `conversation and turn receipt require stable scoped versions`() {
        val conversation = conversation()
        val turn = turnReceipt()

        assertEquals(conversation.conversationId, turn.conversationId)
        assertEquals(conversation.generation, turn.conversationGeneration)
        assertTrue(conversation.status.canTransitionTo(TutorConversationStatus.ARCHIVED))
        assertFalse(TutorConversationStatus.ARCHIVED.canTransitionTo(TutorConversationStatus.ACTIVE))
        assertIllegalArgument { conversation.copy(generation = 0) }
        conversation.copy(stateVersion = 0)
        assertIllegalArgument { conversation.copy(stateVersion = -1) }
        assertIllegalArgument {
            conversation.copy(
                status = TutorConversationStatus.ARCHIVED,
                archivedAtEpochMillis = null,
            )
        }
        assertIllegalArgument {
            conversation.copy(
                status = TutorConversationStatus.ACTIVE,
                archivedAtEpochMillis = 1_100,
            )
        }
        assertIllegalArgument { turn.copy(turnOrdinal = 0) }
        assertIllegalArgument { turn.copy(conversationGeneration = 0) }
        turn.copy(
            conversationStateVersion = 0,
            requestVersion = 0,
            modeVersion = 0,
        )
        assertIllegalArgument { turn.copy(conversationStateVersion = -1) }
        assertIllegalArgument { turn.copy(requestVersion = -1) }
        assertIllegalArgument { turn.copy(modeVersion = -1) }
        assertIllegalArgument {
            turn.copy(
                subject = SubjectKind.GENERAL,
                problemAnchorId = "anchor-1",
            )
        }
        assertIllegalArgument {
            turn.copy(studentMessageFingerprint = "not-a-sha256")
        }
        assertIllegalArgument {
            turn.copy(directiveFingerprint = "not-a-sha256")
        }
    }

    @Test
    fun `turn and source summaries are bounded and trimmed`() {
        turnReceipt(studentMessageSummary = "a".repeat(TutorTurnReceipt.MAX_SUMMARY_CHARS))
        sourceFact(responseSummary = "b".repeat(LearningObservationSourceFact.MAX_SUMMARY_CHARS))

        assertIllegalArgument {
            turnReceipt(
                studentMessageSummary = "a".repeat(TutorTurnReceipt.MAX_SUMMARY_CHARS + 1),
            )
        }
        assertIllegalArgument {
            sourceFact(
                responseSummary = "b".repeat(
                    LearningObservationSourceFact.MAX_SUMMARY_CHARS + 1,
                ),
            )
        }
        assertIllegalArgument { turnReceipt(studentMessageSummary = " trailing ") }
        assertIllegalArgument { sourceFact(responseSummary = " leading") }
    }

    @Test
    fun `learning problem anchor is privacy minimal and subject scoped`() {
        val persistedFields = LearningProblemAnchor::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                "anchorId",
                "learnerScopeId",
                "subject",
                "questionFingerprint",
                "revisionFingerprint",
                "fingerprintVersion",
                "createdAtEpochMillis",
            ),
            persistedFields,
        )
        assertIllegalArgument {
            learningProblemAnchor(subject = SubjectKind.GENERAL)
        }
        assertIllegalArgument {
            learningProblemAnchor(questionFingerprint = "question text must never live here")
        }
        assertIllegalArgument {
            learningProblemAnchor(revisionFingerprint = "revision text must never live here")
        }
        assertIllegalArgument {
            learningProblemAnchor(fingerprintVersion = "")
        }
    }

    @Test
    fun `evidence request has one legal terminal resolution shape`() {
        val pending = evidenceRequest()
        val submitted = pending.copy(
            status = TutorEvidenceRequestStatus.SUBMITTED,
            resolvedAtEpochMillis = 1_100,
            terminalSourceFactId = "fact-1",
        )
        val cancelled = pending.copy(
            status = TutorEvidenceRequestStatus.CANCELLED,
            resolvedAtEpochMillis = 1_100,
        )
        assertFalse(pending.status.isTerminal)
        assertTrue(submitted.status.isTerminal)
        assertTrue(cancelled.status.isTerminal)
        assertTrue(pending.status.canTransitionTo(TutorEvidenceRequestStatus.SUBMITTED))
        assertTrue(pending.status.canTransitionTo(TutorEvidenceRequestStatus.CANCELLED))
        TutorEvidenceRequestStatus.entries
            .filter(TutorEvidenceRequestStatus::isTerminal)
            .forEach { terminal ->
                TutorEvidenceRequestStatus.entries.forEach { next ->
                    assertFalse(terminal.canTransitionTo(next))
                }
            }

        assertIllegalArgument {
            pending.copy(resolvedAtEpochMillis = 1_100)
        }
        assertIllegalArgument {
            submitted.copy(terminalSourceFactId = null)
        }
        assertIllegalArgument {
            cancelled.copy(terminalSourceFactId = "fact-1")
        }
        assertIllegalArgument {
            pending.copy(problemAnchorId = "")
        }
        assertIllegalArgument {
            pending.copy(stateVersion = -1)
        }
        assertIllegalArgument {
            pending.copy(explanationMode = TutorExplanationMode.DIRECT)
        }
        assertIllegalArgument {
            pending.copy(directiveFingerprint = "not-a-sha256")
        }
        assertTrue(pending.matches(turnReceipt()))
        assertFalse(
            pending.copy(modeVersion = pending.modeVersion + 1).matches(turnReceipt()),
        )
        assertFalse(
            pending.copy(turnOrdinal = pending.turnOrdinal + 1).matches(turnReceipt()),
        )
        assertFalse(
            pending.copy(directiveFingerprint = SHA256_B).matches(turnReceipt()),
        )
    }

    @Test
    fun `source facts require exact tutor scope and reject cross source leakage`() {
        mapOf(
            LearningObservationSource.TUTOR_CHOICE to
                LearningObservationFactKind.MODEL_EVALUATED_CORRECT_RESPONSE,
            LearningObservationSource.TUTOR_FREE_RESPONSE to
                LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED,
            LearningObservationSource.TUTOR_VISUAL_TARGET to
                LearningObservationFactKind.MODEL_EVALUATED_INCORRECT_RESPONSE,
            LearningObservationSource.TUTOR_SPECIFIC_STUCK to
                LearningObservationFactKind.SPECIFIC_STUCK,
        ).forEach { (source, factKind) ->
            sourceFact(source = source, factKind = factKind)
        }

        assertIllegalArgument {
            sourceFact(conversationId = null)
        }
        assertIllegalArgument {
            sourceFact(turnReceiptId = null)
        }
        assertIllegalArgument {
            sourceFact(evidenceRequestId = null)
        }
        assertIllegalArgument {
            sourceFact(
                source = LearningObservationSource.IMPORTED_MISTAKE,
                conversationGeneration = null,
                conversationId = "conversation-1",
                turnReceiptId = "turn-1",
                evidenceRequestId = "request-1",
            )
        }
        sourceFact(
            source = LearningObservationSource.IMPORTED_MISTAKE,
            factKind = LearningObservationFactKind.IMPORTED_VISIBLE_ERROR,
            conversationGeneration = null,
            conversationId = null,
            turnReceiptId = null,
            evidenceRequestId = null,
        )
        sourceFact(
            source = LearningObservationSource.TUTOR_SPECIFIC_STUCK,
            factKind = LearningObservationFactKind.SPECIFIC_STUCK,
            evidenceRequestId = null,
        )
        assertIllegalArgument {
            sourceFact(subject = SubjectKind.GENERAL)
        }
        assertIllegalArgument {
            sourceFact(conversationGeneration = 0)
        }
        assertIllegalArgument {
            sourceFact(responseFingerprint = "raw student answer")
        }
        assertIllegalArgument {
            sourceFact(
                source = LearningObservationSource.TUTOR_SPECIFIC_STUCK,
                factKind = LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED,
            )
        }
    }

    private fun conversation() = TutorConversation(
        conversationId = "conversation-1",
        learnerScopeId = "learner-1",
        generation = 1,
        status = TutorConversationStatus.ACTIVE,
        createdAtEpochMillis = 1_000,
        archivedAtEpochMillis = null,
        stateVersion = 1,
    )

    private fun turnReceipt(
        studentMessageSummary: String = "我不理解这里为什么要守恒。",
    ) = TutorTurnReceipt(
        turnReceiptId = "turn-1",
        conversationId = "conversation-1",
        conversationGeneration = 1,
        conversationStateVersion = 1,
        turnOrdinal = 1,
        subject = SubjectKind.PHYSICS,
        problemAnchorId = "anchor-1",
        requestVersion = 1,
        modeVersion = 1,
        explanationMode = TutorExplanationMode.GUIDED,
        directiveFingerprint = SHA256_A,
        studentMessageFingerprint = SHA256_A,
        studentMessageSummary = studentMessageSummary,
        occurredAtEpochMillis = 1_000,
    )

    private fun learningProblemAnchor(
        subject: SubjectKind = SubjectKind.PHYSICS,
        questionFingerprint: String = SHA256_A,
        revisionFingerprint: String = SHA256_B,
        fingerprintVersion: String = "problem-fingerprint-v1",
    ) = LearningProblemAnchor(
        anchorId = "anchor-1",
        learnerScopeId = "learner-1",
        subject = subject,
        questionFingerprint = questionFingerprint,
        revisionFingerprint = revisionFingerprint,
        fingerprintVersion = fingerprintVersion,
        createdAtEpochMillis = 1_000,
    )

    private fun evidenceRequest() = TutorEvidenceRequest(
        evidenceRequestId = "request-1",
        conversationId = "conversation-1",
        conversationGeneration = 1,
        conversationStateVersion = 1,
        turnReceiptId = "turn-1",
        turnOrdinal = 1,
        subject = SubjectKind.PHYSICS,
        problemAnchorId = "anchor-1",
        kind = TutorEvidenceRequestKind.FREE_RESPONSE,
        requestVersion = 1,
        modeVersion = 1,
        explanationMode = TutorExplanationMode.GUIDED,
        directiveFingerprint = SHA256_A,
        status = TutorEvidenceRequestStatus.PENDING,
        stateVersion = 0,
        createdAtEpochMillis = 1_000,
        resolvedAtEpochMillis = null,
        terminalSourceFactId = null,
    )

    private fun sourceFact(
        source: LearningObservationSource = LearningObservationSource.TUTOR_FREE_RESPONSE,
        factKind: LearningObservationFactKind = LearningObservationFactKind.OPEN_RESPONSE_SUBMITTED,
        subject: SubjectKind = SubjectKind.PHYSICS,
        conversationGeneration: Long? = 1,
        conversationId: String? = "conversation-1",
        turnReceiptId: String? = "turn-1",
        evidenceRequestId: String? = "request-1",
        responseFingerprint: String = SHA256_B,
        responseSummary: String = "学生指出动量守恒条件不清楚。",
    ) = LearningObservationSourceFact(
        sourceFactId = "fact-1",
        learnerScopeId = "learner-1",
        source = source,
        factKind = factKind,
        anchorId = "anchor-1",
        subject = subject,
        conversationGeneration = conversationGeneration,
        conversationId = conversationId,
        turnReceiptId = turnReceiptId,
        evidenceRequestId = evidenceRequestId,
        responseFingerprint = responseFingerprint,
        responseSummary = responseSummary,
        occurredAtEpochMillis = 1_000,
        sourceVersion = "tutor-source-v1",
    )

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private companion object {
        const val SHA256_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA256_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
