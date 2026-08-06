package com.tingyun.smartmistakebook.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TutorSessionDatabasePortContractTest {
    @Test
    fun learningEvidenceSessionPortHasOnlyIntentAndAcknowledgement() {
        assertEquals(
            setOf(
                "beginTutorLearningEvidenceSessionIntent",
                "acknowledgeTutorLearningEvidenceSession",
            ),
            TutorLearningEvidenceSessionDatabasePort::class.java.declaredMethods
                .mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun trustedTutorSessionCapabilityCannotWriteLegacySubmittedFacts() {
        val signatures =
            listOf(
                TrustedTutorSessionDatabaseCapability::class.java,
                TutorConversationSessionDatabasePort::class.java,
                TutorLearningEvidenceSessionDatabasePort::class.java,
                BeginTutorLearningEvidenceSessionIntentCommand::class.java,
                AcknowledgeTutorLearningEvidenceSessionCommand::class.java,
                TutorLearningEvidenceSessionRecord::class.java,
            ).flatMap { type ->
                type.methods.map { it.toGenericString() } +
                    type.declaredFields.map { it.toGenericString() }
            }.joinToString("\n")

        listOf(
            "finalizeTutorEvidenceRequest",
            "FinalizeTutorEvidenceRequestCommand",
            "LearningObservationSourceFact",
            "responseSummary",
            "answerMarkdown",
            "prompt",
            "weight",
            "projection",
        ).forEach { forbidden ->
            assertFalse("Unexpected tutor session token: $forbidden", signatures.contains(forbidden))
        }
    }
}
