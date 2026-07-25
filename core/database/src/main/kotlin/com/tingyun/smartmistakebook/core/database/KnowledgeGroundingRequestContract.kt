package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind

internal object KnowledgeGroundingRequestContract {
    private const val MAX_BATCH_SIZE = 128
    private val sha256 = Regex("[a-f0-9]{64}")
    private val subjects = setOf(
        "CHINESE",
        "MATH",
        "ENGLISH",
        "PHYSICS",
        "CHEMISTRY",
        "BIOLOGY",
        "POLITICS",
        "HISTORY",
        "GEOGRAPHY",
    )

    fun validate(requests: List<KnowledgeGroundingRequestRecord>) {
        requireValid(requests.size <= MAX_BATCH_SIZE) { "Too many knowledge-grounding requests" }
        requireValid(requests.map { it.groundingRequestId }.distinct().size == requests.size) {
            "Knowledge-grounding request ids must be unique"
        }
        requireValid(
            requests.map { it.organizationRequestId to it.requestOrdinal }.distinct().size ==
                requests.size,
        ) { "Knowledge-grounding request ordinals must be unique per organization" }
        requests.forEach(::validate)
    }

    private fun validate(request: KnowledgeGroundingRequestRecord) {
        id(request.groundingRequestId, "groundingRequestId")
        id(request.groundingKey, "groundingKey")
        id(request.organizationRequestId, "organizationRequestId")
        requireValid(sha256.matches(request.organizationRequestFingerprint)) {
            "organizationRequestFingerprint must be a lowercase SHA-256 digest"
        }
        requireValid(request.requestOrdinal in 0..63) { "requestOrdinal is outside its budget" }
        id(request.problemId, "problemId")
        id(request.problemRevisionId, "problemRevisionId")
        id(request.practiceUnitId, "practiceUnitId")
        requireValid(request.subject in subjects) { "Unknown knowledge-grounding subject" }
        text(request.query, "query", 160, allowLineBreaks = false)
        text(
            request.expectedParentKnowledgeDisplayName,
            "expectedParentKnowledgeDisplayName",
            200,
            allowLineBreaks = false,
        )
        text(request.reasonMarkdown, "reasonMarkdown", 500, allowLineBreaks = true)
        val expectedGroundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.valueOf(request.subject),
            expectedParentKnowledgeDisplayName = request.expectedParentKnowledgeDisplayName,
            query = request.query,
        )
        requireValid(request.groundingKey == expectedGroundingKey) {
            "groundingKey does not match its normalized subject, parent, and query"
        }
        requireValid(
            request.groundingRequestId == KnowledgeGroundingFingerprint.occurrenceId(
                organizationRequestId = request.organizationRequestId,
                requestOrdinal = request.requestOrdinal,
                groundingKey = request.groundingKey,
            ),
        ) { "groundingRequestId does not match its organization occurrence" }
        requireValid(request.status == StudyDbValue.KnowledgeGroundingStatus.PENDING) {
            "New knowledge-grounding requests must be pending"
        }
        requireValid(request.createdAtEpochMillis > 0) { "createdAtEpochMillis must be positive" }
        requireValid(request.updatedAtEpochMillis == request.createdAtEpochMillis) {
            "A new knowledge-grounding request cannot already contain an update"
        }
    }

    private fun id(value: String, name: String) {
        requireValid(value.isNotBlank() && value == value.trim() && value.length <= 256) {
            "$name must be a trimmed non-blank string of at most 256 characters"
        }
        requireValid(value.none(Char::isISOControl)) { "$name contains control characters" }
    }

    private fun text(
        value: String,
        name: String,
        maxLength: Int,
        allowLineBreaks: Boolean,
    ) {
        requireValid(value.isNotBlank() && value.length <= maxLength) {
            "$name must be non-blank and at most $maxLength characters"
        }
        requireValid(
            value.none { character ->
                val allowedWhitespace = allowLineBreaks && character in "\n\r\t"
                (character.isISOControl() && !allowedWhitespace) ||
                    character.isBidirectionalControl()
            },
        ) { "$name contains unsupported control characters" }
    }

    private fun Char.isBidirectionalControl(): Boolean =
        this == '\u061C' || this == '\u200E' || this == '\u200F' ||
            this in '\u202A'..'\u202E' || this in '\u2066'..'\u2069'

    private inline fun requireValid(condition: Boolean, message: () -> String) {
        if (!condition) throw DatabaseContractViolationException(message())
    }
}
