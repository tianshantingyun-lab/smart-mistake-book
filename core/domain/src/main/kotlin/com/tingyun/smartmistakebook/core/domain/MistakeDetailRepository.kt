package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Read-only projection of the current formal revision behind one error-book entry. */
interface MistakeDetailRepository {
    fun observe(errorBookEntryId: String): Flow<MistakeDetailState>

    /** Observes one immutable problem revision selected independently of the entry's current head. */
    fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState>

    /** Reads one immutable problem revision without an intermediate [MistakeDetailState.Loading]. */
    suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState

    /**
     * Reads an ordered immutable snapshot. Implementations may collapse this into one database
     * transaction; the default preserves compatibility for non-database repositories.
     */
    suspend fun readExact(keys: List<MistakeRevisionKey>): List<MistakeDetailState> =
        keys.map { key -> readExact(key) }

    /** Lists every immutable formal revision belonging to one error-book entry. */
    fun observeRevisionHistory(errorBookEntryId: String): Flow<List<MistakeRevisionSummary>> =
        flowOf(emptyList())

    /**
     * Updates the learner's private note on an error-book entry. The note is
     * user text (never model egress) and lives on the entry, not the
     * append-only revision. Blank/whitespace notes are stored as null.
     */
    suspend fun updateUserNote(
        entryId: String,
        note: String?,
        updatedAtEpochMillis: Long,
    ): Boolean = false
}

data class MistakeRevisionSummary(
    val entryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val revisionNumber: Int,
    val title: String,
    val createdAtEpochMillis: Long,
    val isCurrent: Boolean,
) {
    init {
        require(entryId.isNotBlank())
        require(problemId.isNotBlank())
        require(problemRevisionId.isNotBlank())
        require(revisionNumber > 0)
        require(title.isNotBlank())
        require(createdAtEpochMillis >= 0)
    }

    fun toKey(): MistakeRevisionKey = MistakeRevisionKey(
        entryId = entryId,
        problemId = problemId,
        problemRevisionId = problemRevisionId,
    )
}

data class MistakeRevisionKey(
    val entryId: String,
    val problemId: String,
    val problemRevisionId: String,
) {
    init {
        require(entryId.isNotBlank()) { "Error-book entry id must not be blank" }
        require(problemId.isNotBlank()) { "Problem id must not be blank" }
        require(problemRevisionId.isNotBlank()) { "Problem revision id must not be blank" }
    }
}

sealed interface MistakeDetailState {
    data object Loading : MistakeDetailState

    data class Ready(
        val detail: MistakeDetail,
        val questionDocument: CapturedQuestionDocument,
    ) : MistakeDetailState

    /** A pre-structured-content record. Markdown is authoritative only in this state. */
    data class Legacy(
        val detail: MistakeDetail,
    ) : MistakeDetailState

    /** A non-empty structured snapshot that failed strict decoding or commit validation. */
    data class CorruptSnapshot(
        val identity: MistakeDetailIdentity,
    ) : MistakeDetailState

    data object NotFound : MistakeDetailState
}

data class MistakeDetail(
    val identity: MistakeDetailIdentity,
    val fallbackMarkdown: String,
    val source: MistakeSourceSet,
    val tutorConversation: TutorConversationReference? = null,
    /** Learner's private note on the entry; never leaves the device in a model egress payload. */
    val userNote: String? = null,
) {
    init {
        require(fallbackMarkdown.isNotBlank()) { "Mistake fallback Markdown must not be blank" }
    }
}

/** Stable locator for a conversation that began before this formal revision was saved. */
data class TutorConversationReference(
    val sessionId: String,
    val questionRevisionNumber: Int,
) {
    init {
        require(sessionId.isNotBlank()) { "Tutor conversation session id must not be blank" }
        require(questionRevisionNumber > 0) {
            "Tutor conversation question revision must be positive"
        }
    }
}

data class MistakeDetailIdentity(
    val errorBookEntryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val revisionNumber: Int,
    val title: String,
    val subject: String,
    val practiceUnitId: String = "legacy-practice-unit",
) {
    init {
        require(errorBookEntryId.isNotBlank()) { "Error-book entry id must not be blank" }
        require(problemId.isNotBlank()) { "Problem id must not be blank" }
        require(problemRevisionId.isNotBlank()) { "Problem revision id must not be blank" }
        require(revisionNumber > 0) { "Problem revision number must be positive" }
        require(title.isNotBlank()) { "Problem title must not be blank" }
        require(subject.isNotBlank()) { "Problem subject must not be blank" }
        require(practiceUnitId.isNotBlank()) { "Practice unit id must not be blank" }
    }
}

sealed interface MistakeSourceSet {
    data object Missing : MistakeSourceSet

    data class Present(
        val assets: List<MistakeSourceAsset>,
    ) : MistakeSourceSet {
        init {
            require(assets.isNotEmpty()) { "A present source set must contain an asset" }
        }
    }
}

data class MistakeSourceAsset(
    val role: String,
    val sourceAssetId: String,
    val contentSha256: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int,
    val height: Int,
    val sourceType: String,
    val createdAtEpochMillis: Long,
    val location: MistakeSourceLocation,
) {
    init {
        require(role.isNotBlank()) { "Source role must not be blank" }
        require(sourceAssetId.isNotBlank()) { "Source asset id must not be blank" }
        require(SHA_256.matches(contentSha256)) { "Source asset SHA-256 is invalid" }
        require(mimeType.isNotBlank()) { "Source asset MIME type must not be blank" }
        require(byteSize > 0) { "Source asset byte size must be positive" }
        require(width > 0 && height > 0) { "Source asset dimensions must be positive" }
        require(sourceType.isNotBlank()) { "Source asset type must not be blank" }
        require(createdAtEpochMillis >= 0) { "Source asset creation time must not be negative" }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

sealed interface MistakeSourceLocation {
    data class Available(
        val localUri: String,
    ) : MistakeSourceLocation {
        init {
            require(localUri.isNotBlank()) { "Available source URI must not be blank" }
        }
    }

    data object Unavailable : MistakeSourceLocation
}
