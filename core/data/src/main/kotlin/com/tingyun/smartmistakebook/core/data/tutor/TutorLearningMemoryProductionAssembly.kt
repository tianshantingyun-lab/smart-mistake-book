package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.TutorConversationSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorLearningEvidenceSessionDatabasePort
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository

internal fun assembleTutorLearningMemoryRepository(
    boundLearnerId: String,
    conversationSession: TutorConversationSessionDatabasePort,
    evidenceSession: TutorLearningEvidenceSessionDatabasePort,
    observationSink: TutorMasteryObservationSink,
    currentSessionProofSource: TutorLearningEvidenceCurrentSessionProofSource,
    knowledgeEvidenceAuthorizer: TutorKnowledgeEvidenceAuthorizer,
    productionOwnerIsCurrent: () -> Boolean,
): TutorLearningMemoryRepository =
    LearnerMasteryTutorLearningMemoryRepository(
        delegate = TutorLearningMemoryRepositoryFactory.create(conversationSession),
        boundLearnerId = boundLearnerId,
        observationSink = observationSink,
        evidenceSession = RoomTutorLearningEvidenceSessionAdapter(evidenceSession),
        currentSessionProofSource = currentSessionProofSource,
        knowledgeEvidenceAuthorizer = knowledgeEvidenceAuthorizer,
        productionOwnerIsCurrent = productionOwnerIsCurrent,
    )
