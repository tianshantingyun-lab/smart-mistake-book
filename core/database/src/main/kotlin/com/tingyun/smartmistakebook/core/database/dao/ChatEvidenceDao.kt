package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import kotlinx.coroutines.flow.Flow

/**
 * Chat evidence is a first-class ledger event (spec model-intent-routing §5):
 * every write allocates a learning sequence and appends a projection_outbox
 * row in the same transaction, so both the incremental batch path and the
 * full-replay path see the event. The evidence row stays the authoritative
 * payload; the outbox row only carries identity/sequence/fingerprint.
 */
@Dao
internal abstract class ChatEvidenceDao {
    /**
     * IGNORE (not ABORT): the evidence_id is a deterministic idempotency key —
     * a retried write of the same evidence (same request + tool + KC) must
     * silently no-op instead of failing the whole transaction on a PK clash.
     * Id derivation guarantees same id ⇒ same semantics.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAll(entries: List<LearnerChatEvidenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutboxRows(rows: List<ProjectionOutboxEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun initializeSequence(sequence: LearningSequenceEntity): Long

    @Query(
        """
        UPDATE learning_sequence
        SET last_allocated_sequence = :next
        WHERE learner_id = :learnerId AND last_allocated_sequence = :expected
        """,
    )
    protected abstract suspend fun compareAndSetSequence(
        learnerId: String,
        expected: Long,
        next: Long,
    ): Int

    @Query("SELECT last_allocated_sequence FROM learning_sequence WHERE learner_id = :learnerId")
    protected abstract suspend fun lastAllocatedSequence(learnerId: String): Long?

    @Transaction
    open suspend fun insertAsLedgerEvents(entries: List<LearnerChatEvidenceEntity>) {
        if (entries.isEmpty()) return
        // Rejected rows are observation-only (research tutor-evidence-gate
        // §3.3): they stay as an audit trail but never enter the ledger —
        // no learning-sequence allocation, no projection_outbox row, so the
        // projector can never see them.
        val (accepted, rejected) = entries.partition { !it.isRejected }
        if (rejected.isNotEmpty()) {
            insertAll(rejected)
        }
        if (accepted.isEmpty()) return
        val outboxRows = accepted.map { entry ->
            val sequence = allocateSequence(entry.learner_id)
            val event = entry.toChatEvidenceModel(sequence)
            ProjectionOutboxEntity(
                outboxId = "learning-outbox:${entry.learner_id}:$EVENT_KIND_CHAT_EVIDENCE:${entry.evidence_id}",
                learnerId = entry.learner_id,
                outboxSequence = sequence,
                eventKind = EVENT_KIND_CHAT_EVIDENCE,
                eventId = entry.evidence_id,
                canonicalFingerprint = LearningLedgerFingerprint.chatEvidence(event),
                status = StudyDbValue.OutboxStatus.PENDING,
                createdAtEpochMillis = entry.created_at_epoch_millis,
            )
        }
        insertAll(accepted)
        insertOutboxRows(outboxRows)
    }

    @Query("SELECT * FROM learner_chat_evidence WHERE conversation_id = :conversationId ORDER BY created_at_epoch_millis")
    abstract suspend fun readByConversation(conversationId: String): List<LearnerChatEvidenceEntity>

    @Query("SELECT * FROM learner_chat_evidence WHERE learner_id = :learnerId ORDER BY created_at_epoch_millis DESC LIMIT :limit")
    abstract fun observeRecent(learnerId: String, limit: Int): Flow<List<LearnerChatEvidenceEntity>>

    @Query("SELECT * FROM learner_chat_evidence WHERE learner_id = :learnerId ORDER BY created_at_epoch_millis")
    abstract suspend fun readByLearner(learnerId: String): List<LearnerChatEvidenceEntity>

    private suspend fun allocateSequence(learnerId: String): Long {
        initializeSequence(LearningSequenceEntity(learnerId, 0))
        val current = checkNotNull(lastAllocatedSequence(learnerId))
        check(current < Long.MAX_VALUE) { "Learning sequence exhausted for $learnerId" }
        val next = current + 1
        check(compareAndSetSequence(learnerId, current, next) == 1) {
            "Learning sequence CAS failed inside a serialized Room transaction"
        }
        return next
    }
}

internal fun LearnerChatEvidenceEntity.toChatEvidenceModel(eventSequence: Long): ChatEvidenceSubmitted =
    ChatEvidenceSubmitted(
        evidenceId = evidence_id,
        conversationId = conversation_id,
        knowledgeNodeId = knowledge_node_id,
        direction = LearningEvidenceDirection.valueOf(direction),
        weight = weight,
        reasonMarkdown = reason_markdown,
        confidence = confidence,
        occurredAtEpochMillis = created_at_epoch_millis,
        eventSequence = eventSequence,
    )
