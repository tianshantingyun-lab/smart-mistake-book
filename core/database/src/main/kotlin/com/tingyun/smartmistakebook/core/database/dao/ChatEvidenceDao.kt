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
internal abstract class ChatEvidenceDao {    /**
     * IGNORE (not ABORT): the evidence_id is a deterministic idempotency key —
     * a retried write of the same evidence (same request + tool + KC) must
     * silently no-op instead of failing the whole transaction on a PK clash.
     * Id derivation guarantees same id ⇒ same semantics.
     *
     * 但 IGNORE **只是最后一道兜底**，幂等的判定发生在
     * [existingEvidenceIds]（见 [insertAsLedgerEvents]）——证据行的 PK 冲突可以
     * 静默，**序列号与 outbox 行不行**（那是账本的连续性凭证）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertAll(entries: List<LearnerChatEvidenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutboxRows(rows: List<ProjectionOutboxEntity>)

    /**
     * 已落库的 evidence_id（幂等判定用）。判在**分配序列号之前**，理由见
     * [insertAsLedgerEvents]。
     *
     * 只查 `evidence_id`、不带 learner 维度：证据表的 PK 就是 `evidence_id`
     * （`LearnerChatEvidenceEntity` 的 `primaryKeys = ["evidence_id"]`），所以
     * "是否重复"这件事本身与 learner 无关。
     */
    @Query("SELECT evidence_id FROM learner_chat_evidence WHERE evidence_id IN (:evidenceIds)")
    protected abstract suspend fun existingEvidenceIds(evidenceIds: List<String>): List<String>

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
        // 幂等判定必须在**分配序列号之前**，否则重试会变成账本损坏：
        // `allocateSequence` 一旦推进 `learning_sequence` 而对应的
        // projection_outbox 行被 IGNORE 掉，账本里就留下一个**永久空号**，
        // 读侧的严格连续性检查（`ProjectionTransactionDao.loadProjectionBatch`
        // 的 GAP、`loadLearningLedger` 的 "Sequence N was allocated but has no
        // immutable ledger event"）会把整本账判为损坏、投影从此停摆
        // （审计 S-1）。所以在分配之前先把已存在的 evidence_id 整条摘掉。
        //
        // 顺带说明为什么不能只把 insertOutboxRows 的 ABORT 改成 IGNORE：
        // 那正是上面那个空号的来源。保留 ABORT——修好之后它若再被触发，
        // 说明的是一件**真的不该发生**的事（证据行不在而 outbox 行在），
        // 静默放过等于把矛盾写进账本。
        //
        // 首次写入即不可变事实（ADR-0001 同源）：同 id 的重发既不覆盖也不
        // "升级"（含"上次被门控拒写、这次门控放行"这种跨状态重发），否则
        // 账本内容会随重试时机改变、重放不再确定。
        val existing = existingEvidenceIds(accepted.map { it.evidence_id }).toSet()
        val fresh = accepted.filterNot { it.evidence_id in existing }
        if (fresh.isEmpty()) return
        val outboxRows = fresh.map { entry ->
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
        insertAll(fresh)
        insertOutboxRows(outboxRows)
    }

    @Query("SELECT * FROM learner_chat_evidence WHERE conversation_id = :conversationId ORDER BY created_at_epoch_millis")
    abstract suspend fun readByConversation(conversationId: String): List<LearnerChatEvidenceEntity>

    @Query("SELECT * FROM learner_chat_evidence WHERE learner_id = :learnerId ORDER BY created_at_epoch_millis DESC LIMIT :limit")
    abstract fun observeRecent(learnerId: String, limit: Int): Flow<List<LearnerChatEvidenceEntity>>

    @Query("SELECT * FROM learner_chat_evidence WHERE learner_id = :learnerId ORDER BY created_at_epoch_millis")
    abstract suspend fun readByLearner(learnerId: String): List<LearnerChatEvidenceEntity>

    /**
     * Gate queries (batch-scale design: indexed, O(log n) instead of a full
     * table pull). All three only count accepted evidence — rejected rows are
     * observation-only and never gate.
     */

    /** Most recent accepted write to one KC by the learner (cross-conversation cooldown). */
    @Query(
        """
        SELECT MAX(created_at_epoch_millis) FROM learner_chat_evidence
        WHERE learner_id = :learnerId AND knowledge_node_id = :knowledgeNodeId
          AND rejected_reason IS NULL
        """,
    )
    abstract suspend fun lastAcceptedAtForKc(learnerId: String, knowledgeNodeId: String): Long?

    /** Accepted writes by the learner since [sinceEpochMillis] (learner rolling-window quota). */
    @Query(
        """
        SELECT COUNT(*) FROM learner_chat_evidence
        WHERE learner_id = :learnerId AND rejected_reason IS NULL
          AND created_at_epoch_millis >= :sinceEpochMillis
        """,
    )
    abstract suspend fun countAcceptedSince(learnerId: String, sinceEpochMillis: Long): Int

    /** Accepted writes in one conversation (conversation quota). */
    @Query(
        """
        SELECT COUNT(*) FROM learner_chat_evidence
        WHERE conversation_id = :conversationId AND rejected_reason IS NULL
        """,
    )
    abstract suspend fun countAcceptedInConversation(conversationId: String): Int

    /** Rejected-write counts grouped by gate reason (calibration input). */
    @Query(
        """
        SELECT rejected_reason AS reason, COUNT(*) AS count FROM learner_chat_evidence
        WHERE learner_id = :learnerId AND rejected_reason IS NOT NULL
        GROUP BY rejected_reason
        """,
    )
    abstract suspend fun countRejectedByReason(learnerId: String): List<RejectedReasonCountRow>

    /** Accepted-write counts bucketed by epoch hour (calibration: window pressure). */
    @Query(
        """
        SELECT created_at_epoch_millis / 3600000 AS hourBucket, COUNT(*) AS count
        FROM learner_chat_evidence
        WHERE learner_id = :learnerId AND rejected_reason IS NULL
          AND created_at_epoch_millis >= :sinceEpochMillis
        GROUP BY hourBucket
        ORDER BY hourBucket DESC
        """,
    )
    abstract suspend fun countAcceptedPerHour(learnerId: String, sinceEpochMillis: Long): List<HourlyAcceptedCountRow>

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


/** Projection row for rejected-write counts grouped by gate reason (calibration input). */
data class RejectedReasonCountRow(
    val reason: String,
    val count: Int,
)

/** Projection row for accepted-write counts per epoch hour (calibration input). */
data class HourlyAcceptedCountRow(
    val hourBucket: Long,
    val count: Int,
)
