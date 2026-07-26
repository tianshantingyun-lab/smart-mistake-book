package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorModePresentationPolicyTest {
    private val moves = listOf(
        TutorSuggestedMove("deepen", "再看关键一步", TutorMoveType.DEEPEN_REASONING),
        TutorSuggestedMove("visual", "换成图来看", TutorMoveType.CHANGE_REPRESENTATION),
        TutorSuggestedMove("connect", "联系当前定义", TutorMoveType.CONNECT_KNOWLEDGE),
    )

    @Test
    fun directModeSuppressesDiagnosticsAndShowsCompleteCurrentExplanation() {
        val presentation = tutorTurnPresentation(
            mode = TutorExplanationMode.DIRECT,
            guidedQuestionOrdinal = 1,
            strugglesObserved = 0,
            suggestedMoves = moves,
        )

        assertFalse(presentation.showDiagnostic)
        assertTrue(presentation.showCompleteExplanation)
        assertEquals(listOf("deepen", "visual"), presentation.followUpMoves.map { it.id })
    }

    @Test
    fun guidedModeFallsBackAfterQuestionOrStruggleBudget() {
        val withinBudget = tutorTurnPresentation(
            mode = TutorExplanationMode.GUIDED,
            guidedQuestionOrdinal = 3,
            strugglesObserved = 1,
            suggestedMoves = moves,
        )
        val questionLimit = tutorTurnPresentation(
            mode = TutorExplanationMode.GUIDED,
            guidedQuestionOrdinal = 4,
            strugglesObserved = 0,
            suggestedMoves = moves,
        )
        val struggleLimit = tutorTurnPresentation(
            mode = TutorExplanationMode.GUIDED,
            guidedQuestionOrdinal = 2,
            strugglesObserved = 2,
            suggestedMoves = moves,
        )

        assertTrue(withinBudget.showDiagnostic)
        assertFalse(withinBudget.showCompleteExplanation)
        assertFalse(questionLimit.showDiagnostic)
        assertTrue(questionLimit.showCompleteExplanation)
        assertTrue(struggleLimit.showCompleteExplanation)
    }
}
