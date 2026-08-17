package com.tingyun.smartmistakebook.core.domain

data class SaveTutorDraftRequest(
    val draftId: String,
    val sessionId: String,
    val occurredAtEpochMillis: Long = 0L,
    val requestId: String? = null,
) {
    init {
        require(draftId.isNotBlank()) { "Save-tutor draft id must not be blank" }
        require(sessionId.isNotBlank()) { "Save-tutor session id must not be blank" }
        require(occurredAtEpochMillis >= 0L) { "Save-tutor occurrence time must not be negative" }
        require(requestId == null || requestId.isNotBlank()) {
            "Save-tutor request id must be null or non-blank"
        }
    }
}

data class SaveTutorDraftReceipt(
    val entryId: String?,
    val created: Boolean,
    val alreadySaved: Boolean,
)

class SaveTutorDraftToLibraryUseCase(
    private val repository: CaptureWorkflowRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend operator fun invoke(request: SaveTutorDraftRequest): SaveTutorDraftReceipt {
        val existing = repository.readTutorSession(request.sessionId)
            ?: error("Tutor session ${request.sessionId} no longer exists")
        if (existing.isSaved) {
            return SaveTutorDraftReceipt(
                entryId = existing.errorBookEntryId,
                created = false,
                alreadySaved = true,
            )
        }
        val requestId = request.requestId
            ?: "save-tutor-draft:${request.draftId}:${request.sessionId}"
        val occurredAtEpochMillis = if (request.occurredAtEpochMillis == 0L) {
            clock()
        } else {
            request.occurredAtEpochMillis
        }
        val summary = repository.saveTutorSession(
            SaveTutorSessionRequest(
                requestId = requestId,
                sessionId = request.sessionId,
                occurredAtEpochMillis = occurredAtEpochMillis,
            ),
        )
        val refreshed = repository.readTutorSession(request.sessionId)
        return SaveTutorDraftReceipt(
            entryId = refreshed?.errorBookEntryId ?: summary.errorBookEntryId,
            created = summary.created,
            alreadySaved = false,
        )
    }
}
