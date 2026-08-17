package com.tingyun.smartmistakebook.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorTurnSendStateMachineTest {
    @Test
    fun doubleTapCannotCreateASecondLogicalOperation() {
        val started = TutorTurnSendStateMachine.reduce(
            TutorSendState(),
            TutorSendAction.StudentMessagePersisted(
                logicalOperationId = "logical-1",
                messageId = "message-1",
            ),
        )

        val secondTap = TutorTurnSendStateMachine.reduce(
            started,
            TutorSendAction.StudentMessagePersisted(
                logicalOperationId = "logical-2",
                messageId = "message-2",
            ),
        )

        assertEquals(TutorSendPhase.PERMANENT_FAILURE, secondTap.phase)
        assertEquals("STALE_TUTOR_TURN_RESULT", secondTap.errorCode)
        assertEquals("logical-1", secondTap.logicalOperationId)
    }

    @Test
    fun retryBudgetIsCappedAtThreeRealDispatches() {
        var state = TutorSendState()
        state = TutorTurnSendStateMachine.reduce(
            state,
            TutorSendAction.StudentMessagePersisted("logical-1", "message-1"),
        )
        state = TutorTurnSendStateMachine.reduce(
            state,
            TutorSendAction.ConsentGranted("logical-1", "message-1"),
        )
        state = TutorTurnSendStateMachine.reduce(
            state,
            TutorSendAction.DispatchFailed(
                "logical-1",
                "message-1",
                errorCode = "TIMEOUT",
                retryable = true,
            ),
        )
        state = TutorTurnSendStateMachine.reduce(
            state,
            TutorSendAction.ConsentGranted("logical-1", "message-1"),
        )
        state = TutorTurnSendStateMachine.reduce(
            state,
            TutorSendAction.DispatchFailed(
                "logical-1",
                "message-1",
                errorCode = "TIMEOUT",
                retryable = true,
            ),
        )
        state = TutorTurnSendStateMachine.reduce(
            state,
            TutorSendAction.ConsentGranted("logical-1", "message-1"),
        )

        assertEquals(TutorSendPhase.DISPATCHING, state.phase)
        assertEquals(3, state.dispatchAttemptCount)

        val fourth = TutorTurnSendStateMachine.reduce(
            state,
            TutorSendAction.ConsentGranted("logical-1", "message-1"),
        )
        assertTrue(fourth.dispatchAttemptCount <= MAX_TUTOR_DISPATCH_ATTEMPTS)
        assertFalse(fourth.phase == TutorSendPhase.DISPATCHING)
    }

    @Test
    fun processRestartRequiresExplicitContinueBeforeDispatching() {
        val persisted = TutorTurnSendStateMachine.reduce(
            TutorSendState(),
            TutorSendAction.StudentMessagePersisted("logical-1", "message-1"),
        )

        val restarted = TutorTurnSendStateMachine.reduce(
            persisted,
            TutorSendAction.ResumeAfterRestart("logical-1", "message-1"),
        )

        assertEquals(TutorSendPhase.AWAITING_CONSENT, restarted.phase)
        assertEquals(0, restarted.dispatchAttemptCount)
    }

    @Test
    fun completedTurnAllowsANewLogicalOperation() {
        val completed = TutorTurnSendStateMachine.reduce(
            TutorSendState(),
            TutorSendAction.StudentMessagePersisted("logical-1", "message-1"),
        ).let {
            TutorTurnSendStateMachine.reduce(
                it,
                TutorSendAction.ConsentGranted("logical-1", "message-1"),
            )
        }.let {
            TutorTurnSendStateMachine.reduce(
                it,
                TutorSendAction.DispatchStarted("logical-1", "message-1"),
            )
        }.let {
            TutorTurnSendStateMachine.reduce(
                it,
                TutorSendAction.DispatchSucceeded("logical-1", "message-1"),
            )
        }

        val nextTurn = TutorTurnSendStateMachine.reduce(
            completed,
            TutorSendAction.StudentMessagePersisted("logical-2", "message-2"),
        )

        assertEquals(TutorSendPhase.PERSISTING, nextTurn.phase)
        assertEquals("logical-2", nextTurn.logicalOperationId)
    }
}
