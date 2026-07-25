package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentAssistanceEvent
import com.tingyun.smartmistakebook.core.model.PersistedAssessmentAssistance

fun interface AssessmentAssistanceRecorder {
    fun persist(event: AssessmentAssistanceEvent): PersistedAssessmentAssistance
}

fun interface TutorAssistancePresenter {
    fun present(assistance: PersistedAssessmentAssistance)
}

/** Persists reveal/hint evidence before any content is handed to the UI. */
class TutorAssistanceCoordinator(
    private val recorder: AssessmentAssistanceRecorder,
    private val presenter: TutorAssistancePresenter,
) {
    fun deliver(event: AssessmentAssistanceEvent): PersistedAssessmentAssistance {
        val persisted = recorder.persist(event)
        require(persisted.event == event) { "Recorder returned evidence for a different event" }
        presenter.present(persisted)
        return persisted
    }
}
