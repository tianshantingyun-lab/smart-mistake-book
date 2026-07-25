package com.tingyun.smartmistakebook.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Editing mode is presentation state; the structured document always remains authoritative. */
@Serializable
enum class CaptureDraftEditorMode {
    STRUCTURED_DOCUMENT,
    MANUAL_TRANSCRIPTION,
}

/** Stable dirty-field identities used to prevent late candidates from overwriting user edits. */
@Serializable
enum class CaptureDraftEditedField {
    SUBJECT,
    TITLE,
    TRANSCRIPTION,
    WRITING_LAYER,
    STRUCTURE,
}

/** Stable identity reused when the final confirmation has to be replayed after process death. */
@Serializable
data class CaptureFinalConfirmationRequestIdentity(
    val requestId: String,
    val occurredAtEpochMillis: Long,
)

/**
 * A mutable editing workspace beside, rather than inside, the immutable draft revision chain.
 * Its [baseCandidateFingerprint] binds it to the exact candidate from which editing started.
 */
@Serializable
data class CaptureDraftWorkspace(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val subject: String?,
    val workingDocument: CapturedQuestionDocument,
    val editorMode: CaptureDraftEditorMode,
    val userEditedFields: Set<CaptureDraftEditedField> = emptySet(),
    val userEditedBlockIds: Set<String> = emptySet(),
    val baseCandidateFingerprint: String,
    val finalConfirmationRequest: CaptureFinalConfirmationRequestIdentity? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class CaptureDraftWorkspaceIssueCode {
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_SUBJECT,
    INVALID_DOCUMENT,
    INVALID_EDITED_BLOCK_ID,
    INVALID_BASE_CANDIDATE_FINGERPRINT,
    INVALID_FINAL_CONFIRMATION_REQUEST,
}

data class CaptureDraftWorkspaceIssue(
    val code: CaptureDraftWorkspaceIssueCode,
    val value: String? = null,
)

object CaptureDraftWorkspaceValidator {
    fun validate(value: CaptureDraftWorkspace): List<CaptureDraftWorkspaceIssue> = buildList {
        if (value.schemaVersion != CaptureDraftWorkspace.CURRENT_SCHEMA_VERSION) {
            add(
                CaptureDraftWorkspaceIssue(
                    CaptureDraftWorkspaceIssueCode.UNSUPPORTED_SCHEMA_VERSION,
                ),
            )
        }
        if (value.subject != null && value.subject !in SUBJECTS) {
            add(
                CaptureDraftWorkspaceIssue(
                    CaptureDraftWorkspaceIssueCode.INVALID_SUBJECT,
                    value.subject,
                ),
            )
        }
        if (CapturedQuestionDocumentValidator.validateDraft(value.workingDocument).isNotEmpty()) {
            add(CaptureDraftWorkspaceIssue(CaptureDraftWorkspaceIssueCode.INVALID_DOCUMENT))
        }

        val blockIds = value.workingDocument.document.blocks.map(ContentBlock::id).toSet()
        value.userEditedBlockIds
            .filter { it.isBlank() || it !in blockIds }
            .forEach { blockId ->
                add(
                    CaptureDraftWorkspaceIssue(
                        CaptureDraftWorkspaceIssueCode.INVALID_EDITED_BLOCK_ID,
                        blockId,
                    ),
                )
            }
        if (!SHA_256.matches(value.baseCandidateFingerprint)) {
            add(
                CaptureDraftWorkspaceIssue(
                    CaptureDraftWorkspaceIssueCode.INVALID_BASE_CANDIDATE_FINGERPRINT,
                ),
            )
        }
        value.finalConfirmationRequest?.let { identity ->
            if (
                identity.requestId.isBlank() ||
                identity.requestId.length > MAX_REQUEST_ID_CHARS ||
                identity.occurredAtEpochMillis < 0
            ) {
                add(
                    CaptureDraftWorkspaceIssue(
                        CaptureDraftWorkspaceIssueCode.INVALID_FINAL_CONFIRMATION_REQUEST,
                    ),
                )
            }
        }
    }.distinct()

    private val SUBJECTS = SubjectKind.entries.map(SubjectKind::name).toSet()
    private val SHA_256 = Regex("[a-f0-9]{64}")
    private const val MAX_REQUEST_ID_CHARS = 256
}

/** Strict, deterministic codec for the one-row-per-draft Room snapshot. */
object CaptureDraftWorkspaceCodec {
    const val MAX_ENCODED_CHARS = 160_000

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun encode(value: CaptureDraftWorkspace): String {
        require(CaptureDraftWorkspaceValidator.validate(value).isEmpty()) {
            "Capture draft workspace is invalid"
        }
        val canonical = value.copy(
            userEditedFields = value.userEditedFields
                .sortedBy(CaptureDraftEditedField::ordinal)
                .toCollection(linkedSetOf()),
            userEditedBlockIds = value.userEditedBlockIds.sorted().toCollection(linkedSetOf()),
        )
        return json.encodeToString(CaptureDraftWorkspace.serializer(), canonical).also {
            require(it.length <= MAX_ENCODED_CHARS) {
                "Capture draft workspace exceeds the snapshot budget"
            }
        }
    }

    fun decode(value: String): CaptureDraftWorkspace {
        require(value.length <= MAX_ENCODED_CHARS) {
            "Capture draft workspace exceeds the snapshot budget"
        }
        return json.decodeFromString(CaptureDraftWorkspace.serializer(), value).also {
            require(CaptureDraftWorkspaceValidator.validate(it).isEmpty()) {
                "Capture draft workspace is invalid"
            }
        }
    }
}

object CaptureDraftWorkspaceFingerprint {
    fun of(value: CaptureDraftWorkspace): String = sha256(CaptureDraftWorkspaceCodec.encode(value))

    fun ofEncoded(value: String): String = sha256(value)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
