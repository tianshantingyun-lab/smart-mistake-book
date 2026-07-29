package com.tingyun.smartmistakebook.core.model.storage

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind

/**
 * Stable reference to a learner-owned problem.
 *
 * References cross store boundaries; they deliberately contain no presentation text or database
 * row identity.
 */
data class StudentProblemRef(
    val learnerId: String,
    val subject: SubjectKind,
    val problemId: String,
    val practiceUnitId: String,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported student problem reference version"
        }
        requireStoreIdentity(learnerId, "Learner id")
        requireStoreIdentity(problemId, "Problem id")
        requireStoreIdentity(practiceUnitId, "Practice unit id")
    }

    val canonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("schemaVersion", schemaVersion)
            .field("learnerId", learnerId)
            .field("subject", subject.name)
            .field("problemId", problemId)
            .field("practiceUnitId", practiceUnitId)
            .finish()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DOMAIN = "student-problem-ref-v1"
    }
}

/** Stable reference to one immutable revision of a learner-owned problem. */
data class StudentProblemRevisionRef(
    val problem: StudentProblemRef,
    val revisionId: String,
    val revisionNumber: Int,
    val documentCanonicalFingerprint: String,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported student problem revision reference version"
        }
        requireStoreIdentity(revisionId, "Problem revision id")
        require(revisionNumber > 0) { "Problem revision number must be positive" }
        requireCanonicalFingerprint(
            documentCanonicalFingerprint,
            "Problem revision document fingerprint",
        )
    }

    val canonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("schemaVersion", schemaVersion)
            .field("problemRef", problem.canonicalFingerprint)
            .field("revisionId", revisionId)
            .field("revisionNumber", revisionNumber)
            .field("documentCanonicalFingerprint", documentCanonicalFingerprint)
            .finish()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DOMAIN = "student-problem-revision-ref-v1"
    }
}

/** Version-bound reference into the active high-school knowledge catalog. */
data class KnowledgeNodeRef(
    val subject: SubjectKind,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val knowledgePackVersion: String,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported knowledge node reference version"
        }
        require(subject != SubjectKind.GENERAL) {
            "Knowledge node subject must be one of the nine high-school subjects"
        }
        requireStoreIdentity(knowledgeNodeId, "Knowledge node id")
        requireStoreVersion(taxonomyVersion, "Knowledge taxonomy version")
        requireStoreVersion(knowledgePackVersion, "Knowledge pack version")
    }

    val canonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("schemaVersion", schemaVersion)
            .field("subject", subject.name)
            .field("knowledgeNodeId", knowledgeNodeId)
            .field("taxonomyVersion", taxonomyVersion)
            .field("knowledgePackVersion", knowledgePackVersion)
            .finish()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DOMAIN = "knowledge-node-ref-v1"
    }
}

/**
 * Immutable proof reference for a problem-to-knowledge binding.
 *
 * The canonical binding fingerprint is produced by the owning mistake store and lets the mastery
 * store validate an attribution without reading or joining the mistake database.
 */
data class ProblemKnowledgeBindingRef(
    val bindingId: String,
    val problemRevision: StudentProblemRevisionRef,
    val knowledgeNode: KnowledgeNodeRef,
    val bindingCanonicalFingerprint: String,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported problem knowledge binding reference version"
        }
        requireStoreIdentity(bindingId, "Problem knowledge binding id")
        require(problemRevision.problem.subject == knowledgeNode.subject) {
            "Problem knowledge binding must stay within one subject"
        }
        requireCanonicalFingerprint(
            bindingCanonicalFingerprint,
            "Problem knowledge binding fingerprint",
        )
    }

    val canonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("schemaVersion", schemaVersion)
            .field("bindingId", bindingId)
            .field("problemRevisionRef", problemRevision.canonicalFingerprint)
            .field("knowledgeNodeRef", knowledgeNode.canonicalFingerprint)
            .field("bindingCanonicalFingerprint", bindingCanonicalFingerprint)
            .finish()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DOMAIN = "problem-knowledge-binding-ref-v1"
    }
}

/** Stable reference to an immutable, sequenced learning-ledger fact. */
data class LearningEvidenceRef(
    val learnerId: String,
    val eventKind: String,
    val eventId: String,
    val eventSequence: Long,
    val eventCanonicalFingerprint: String,
    val schemaVersion: Int = SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported learning evidence reference version"
        }
        requireStoreIdentity(learnerId, "Learning evidence learner id")
        requireStoreIdentity(eventKind, "Learning evidence event kind")
        requireStoreIdentity(eventId, "Learning evidence event id")
        require(eventSequence > 0) { "Learning evidence sequence must be positive" }
        requireCanonicalFingerprint(
            eventCanonicalFingerprint,
            "Learning evidence canonical fingerprint",
        )
    }

    val canonicalFingerprint: String
        get() = CanonicalSha256(DOMAIN)
            .field("schemaVersion", schemaVersion)
            .field("learnerId", learnerId)
            .field("eventKind", eventKind)
            .field("eventId", eventId)
            .field("eventSequence", eventSequence)
            .field("eventCanonicalFingerprint", eventCanonicalFingerprint)
            .finish()

    companion object {
        const val SCHEMA_VERSION = 1
        private const val DOMAIN = "learning-evidence-ref-v1"
    }
}

internal fun requireStoreIdentity(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_IDENTITY_CHARS &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value of at most $MAX_IDENTITY_CHARS characters"
    }
}

internal fun requireStoreVersion(value: String, label: String) {
    requireStoreIdentity(value, label)
    require(value.length <= MAX_VERSION_CHARS && STORE_VERSION_PATTERN.matches(value)) {
        "$label contains unsupported characters or exceeds $MAX_VERSION_CHARS characters"
    }
}

internal fun requireCanonicalFingerprint(value: String, label: String) {
    require(CANONICAL_FINGERPRINT_PATTERN.matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private const val MAX_IDENTITY_CHARS = 256
private const val MAX_VERSION_CHARS = 128
private val STORE_VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:+-]*")
private val CANONICAL_FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
