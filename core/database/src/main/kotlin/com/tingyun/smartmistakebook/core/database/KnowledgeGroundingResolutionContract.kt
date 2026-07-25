package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind

internal object KnowledgeGroundingResolutionContract {
    private val groundingKeyPattern = Regex("grounding:[a-f0-9]{64}")
    private val subjects = SubjectKind.entries.mapTo(hashSetOf()) { it.name }

    fun validate(command: ResolveKnowledgeGroundingCommand): String {
        requireValid(command.groundingKey.matches(groundingKeyPattern)) {
            "Knowledge-grounding resolution key is invalid"
        }
        requireValid(command.subject in subjects) {
            "Knowledge-grounding resolution subject is unknown"
        }
        requireValid(
            command.knowledgeNodeId.isNotBlank() &&
                command.knowledgeNodeId == command.knowledgeNodeId.trim() &&
                command.knowledgeNodeId.length <= 256 &&
                command.knowledgeNodeId.none(Char::isISOControl),
        ) { "Knowledge-grounding resolution node id is invalid" }
        requireValid(command.resolvedAtEpochMillis > 0) {
            "Knowledge-grounding resolution time must be positive"
        }
        return KnowledgeGroundingFingerprint.resolutionId(
            groundingKey = command.groundingKey,
            knowledgeNodeId = command.knowledgeNodeId,
        )
    }

    private inline fun requireValid(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }
}
