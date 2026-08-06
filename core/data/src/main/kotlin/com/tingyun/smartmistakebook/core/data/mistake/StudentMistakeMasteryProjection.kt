package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.mastery.database.KnowledgeMasteryTrend
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayKnowledgeItem
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayPageRequest
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayRevision
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.util.Collections

/** One mistake and the exact knowledge references saved by the student-mistake authority. */
internal class StudentMistakeMasteryBinding(
    val mistakeId: String,
    knowledgeNodes: List<KnowledgeNodeRef>,
) {
    val knowledgeNodes: List<KnowledgeNodeRef> =
        Collections.unmodifiableList(knowledgeNodes.toList())

    init {
        requireMistakeIdentity(mistakeId)
        require(this.knowledgeNodes.map { it.subject }.distinct().size <= 1) {
            "A mistake mastery binding cannot cross subjects"
        }
        require(
            this.knowledgeNodes
                .map { it.stableMasteryIdentity() }
                .distinct()
                .size == this.knowledgeNodes.size,
        ) {
            "A mistake mastery binding cannot repeat a stable knowledge identity"
        }
    }
}

/** One bounded page returned by learner-mastery.db at an immutable display revision. */
internal class StudentMistakeMasteryDisplayBatch(
    val revision: LearnerMasteryDisplayRevision,
    items: List<LearnerMasteryDisplayKnowledgeItem>,
) {
    val items: List<LearnerMasteryDisplayKnowledgeItem> =
        Collections.unmodifiableList(items.toList())

    init {
        require(this.items.size <= LearnerMasteryDisplayPageRequest.MAX_LIMIT) {
            "A mistake mastery display batch exceeds the mastery page budget"
        }
    }
}

internal class StudentMistakeMasteryProjection(
    val revision: LearnerMasteryDisplayRevision,
    statusByMistakeId: Map<String, MasteryStatus>,
) {
    val statusByMistakeId: Map<String, MasteryStatus> =
        Collections.unmodifiableMap(LinkedHashMap(statusByMistakeId))
}

/**
 * Groups the exact knowledge references needed from learner-mastery.db.
 *
 * Repeated references across different mistakes are fetched once. Conflicting knowledge-pack
 * references for the same stable identity fail closed instead of choosing one arbitrarily.
 */
internal fun groupStudentMistakeMasteryRequests(
    bindings: List<StudentMistakeMasteryBinding>,
): Map<SubjectKind, List<KnowledgeNodeRef>> {
    validateMistakeBindings(bindings)
    val referencesBySubject =
        linkedMapOf<SubjectKind, MutableMap<StableKnowledgeIdentity, KnowledgeNodeRef>>()
    bindings.forEach { binding ->
        binding.knowledgeNodes.forEach { reference ->
            val references = referencesBySubject.getOrPut(reference.subject) { linkedMapOf() }
            val identity = reference.stableMasteryIdentity()
            val existing = references.putIfAbsent(identity, reference)
            require(existing == null || existing == reference) {
                "A stable knowledge identity cannot resolve to conflicting knowledge references"
            }
        }
    }
    val grouped = linkedMapOf<SubjectKind, List<KnowledgeNodeRef>>()
    SubjectKind.entries
        .asSequence()
        .filterNot { it == SubjectKind.GENERAL }
        .forEach { subject ->
            referencesBySubject[subject]
                ?.values
                ?.sortedWith(KNOWLEDGE_REFERENCE_ORDER)
                ?.takeIf { it.isNotEmpty() }
                ?.let { references ->
                    grouped[subject] = Collections.unmodifiableList(references)
                }
        }
    return Collections.unmodifiableMap(grouped)
}

/**
 * Projects mistake-list mastery in O(bindings + unique knowledge + returned mastery items).
 * No display text participates in identity or lookup.
 */
internal fun projectStudentMistakeMastery(
    bindings: List<StudentMistakeMasteryBinding>,
    expectedRevision: LearnerMasteryDisplayRevision,
    masteryBatches: List<StudentMistakeMasteryDisplayBatch>,
): StudentMistakeMasteryProjection {
    val requestedBySubject = groupStudentMistakeMasteryRequests(bindings)
    require(masteryBatches.all { it.revision == expectedRevision }) {
        "Mistake mastery display batches must share the expected revision"
    }

    val requestedIdentities = HashSet<StableKnowledgeIdentity>()
    requestedBySubject.values.forEach { references ->
        references.forEach { reference ->
            requestedIdentities += reference.stableMasteryIdentity()
        }
    }

    val fingerprints = HashSet<String>()
    val masteryByIdentity =
        HashMap<StableKnowledgeIdentity, LearnerMasteryDisplayKnowledgeItem>()
    masteryBatches.forEach { batch ->
        batch.items.forEach { item ->
            val identity = item.knowledgeNode.stableMasteryIdentity()
            require(
                item.stableNodeIdentityFingerprint == identity.canonicalFingerprint(),
            ) {
                "Mastery display item has an invalid stable identity fingerprint"
            }
            require(identity in requestedIdentities) {
                "Mastery display batches cannot contain an unrequested knowledge identity"
            }
            require(fingerprints.add(item.stableNodeIdentityFingerprint)) {
                "Mastery display batches cannot repeat an identity fingerprint"
            }
            require(masteryByIdentity.putIfAbsent(identity, item) == null) {
                "Mastery display batches cannot repeat a stable knowledge identity"
            }
        }
    }

    val statusByMistakeId = LinkedHashMap<String, MasteryStatus>(bindings.size)
    bindings.forEach { binding ->
        val status =
            binding.knowledgeNodes
                .asSequence()
                .map { reference ->
                    masteryByIdentity[reference.stableMasteryIdentity()]
                        ?.toMistakeMasteryStatus()
                        ?: MasteryStatus.UNKNOWN
                }.minByOrNull { it.attentionPriority() }
                ?: MasteryStatus.UNKNOWN
        statusByMistakeId[binding.mistakeId] = status
    }
    return StudentMistakeMasteryProjection(
        revision = expectedRevision,
        statusByMistakeId = statusByMistakeId,
    )
}

private data class StableKnowledgeIdentity(
    val subject: SubjectKind,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
) {
    fun canonicalFingerprint(): String =
        CanonicalSha256(STABLE_IDENTITY_DOMAIN)
            .field("subject", subject.name)
            .field("knowledgeNodeId", knowledgeNodeId)
            .field("taxonomyVersion", taxonomyVersion)
            .finish()
}

private fun KnowledgeNodeRef.stableMasteryIdentity(): StableKnowledgeIdentity =
    StableKnowledgeIdentity(
        subject = subject,
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
    )

private fun LearnerMasteryDisplayKnowledgeItem.toMistakeMasteryStatus(): MasteryStatus =
    if (trend == KnowledgeMasteryTrend.WAVERING) {
        MasteryStatus.CONFLICTED
    } else {
        when (currentRecallState) {
            KnowledgeMasteryState.NEEDS_REINFORCEMENT -> MasteryStatus.STALE
            KnowledgeMasteryState.FAMILIARIZING -> MasteryStatus.LEARNING
            KnowledgeMasteryState.STEADY -> MasteryStatus.MASTERED
        }
    }

private fun MasteryStatus.attentionPriority(): Int =
    when (this) {
        MasteryStatus.CONFLICTED -> 0
        MasteryStatus.STALE -> 1
        MasteryStatus.LEARNING -> 2
        MasteryStatus.UNKNOWN -> 3
        MasteryStatus.MASTERED -> 4
    }

private fun validateMistakeBindings(bindings: List<StudentMistakeMasteryBinding>) {
    require(bindings.size <= MAX_MISTAKES_PER_PROJECTION) {
        "Mistake mastery projection exceeds its mistake budget"
    }
    val mistakeIds = HashSet<String>(bindings.size)
    bindings.forEach { binding ->
        require(mistakeIds.add(binding.mistakeId)) {
            "Mistake mastery projection cannot repeat a mistake id"
        }
    }
}

private fun requireMistakeIdentity(mistakeId: String) {
    require(
        mistakeId.isNotBlank() &&
            mistakeId == mistakeId.trim() &&
            mistakeId.length <= MAX_MISTAKE_ID_CHARS &&
            mistakeId.none(Char::isISOControl),
    ) {
        "Mistake id must be a trimmed non-blank value"
    }
}

private val KNOWLEDGE_REFERENCE_ORDER =
    compareBy<KnowledgeNodeRef>(
        { it.knowledgeNodeId },
        { it.taxonomyVersion },
        { it.knowledgePackVersion },
    )

private const val STABLE_IDENTITY_DOMAIN = "learner-mastery-stable-node-identity-v1"
private const val MAX_MISTAKES_PER_PROJECTION = 64
private const val MAX_MISTAKE_ID_CHARS = 256
