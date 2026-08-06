package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse

/**
 * Owner-only continuation for one choice that is already durable in the current tutor session.
 *
 * The feature receives neither this capability nor any session reference, learner identity,
 * assistance fact, knowledge proof, or mastery weight. Implementations must reload and verify the
 * exact durable choice before deriving a learning submission.
 */
internal fun interface TrustedTutorChoiceLearningFinalizer {
    suspend fun finalizeRecordedChoice(response: TutorTurnResponse)
}

/**
 * Persists the exact interaction first and only then asks the current-session owner to settle its
 * learning consequence. A failed settlement is deliberately surfaced: the interaction remains
 * durable and can be reconciled without inventing a learning fact.
 */
internal class TrustedTutorChoiceInteractionRepository(
    private val delegate: TutorInteractionRepository,
    private val learningFinalizer: TrustedTutorChoiceLearningFinalizer,
) : TutorInteractionRepository by delegate {
    override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse {
        val persisted = delegate.recordChoice(command)
        learningFinalizer.finalizeRecordedChoice(persisted)
        return persisted
    }
}

internal object TrustedTutorChoiceInteractionRepositoryFactory {
    fun create(
        delegate: TutorInteractionRepository,
        learningFinalizer: TrustedTutorChoiceLearningFinalizer,
    ): TutorInteractionRepository =
        TrustedTutorChoiceInteractionRepository(delegate, learningFinalizer)
}
