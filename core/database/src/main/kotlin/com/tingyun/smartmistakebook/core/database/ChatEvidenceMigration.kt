package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.database.dao.EVENT_KIND_CHAT_EVIDENCE
import com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint

/**
 * v40: learner_chat_evidence（spec model-intent-routing §5）——模型在工具环内
 * 提交的学习证据事件表。模型只给 direction/reason，weight 由本地封顶常量赋值，
 * 掌握度数值仍由投影器公式产生。conversation_id + source_kind 是审计与批量
 * 撤销的锚点。
 */
internal val CHAT_EVIDENCE_MIGRATION_39_40 = object : Migration(39, 40) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(LEARNER_CHAT_EVIDENCE_DDL)
    }
}

/**
 * v41: chat evidence 升级为一等 ledger 事件——每条证据获得 projection_outbox
 * 行，增量和全量两条回放路径都能看到它。v40 只在整本重放时以续接序列临时
 * 拼装，增量路径完全看不到。回填按 learner 续接分配序列、从证据载荷重算
 * canonical fingerprint，并推进 learning_sequence，避免与后续分配撞号。
 */
internal val CHAT_EVIDENCE_OUTBOX_BACKFILL_MIGRATION_40_41 = object : Migration(40, 41) {
    override suspend fun migrate(connection: SQLiteConnection) {
        val orphans = connection.readOrphanChatEvidence()
        if (orphans.isEmpty()) return
        val newHeads = mutableMapOf<String, Long>()
        orphans.groupBy(ChatEvidenceOutboxBackfillRow::learnerId).forEach { (learnerId, rows) ->
            var head = connection.readLedgerHead(learnerId)
            rows.forEach { row ->
                head += 1
                val fingerprint = LearningLedgerFingerprint.chatEvidence(row.toEvent(head))
                connection.insertChatEvidenceOutboxRow(
                    learnerId = learnerId,
                    outboxSequence = head,
                    eventId = row.evidenceId,
                    canonicalFingerprint = fingerprint,
                    createdAtEpochMillis = row.createdAtEpochMillis,
                )
            }
            newHeads[learnerId] = head
        }
        connection.advanceLearningSequences(newHeads)
    }
}

private data class ChatEvidenceOutboxBackfillRow(
    val evidenceId: String,
    val learnerId: String,
    val conversationId: String,
    val knowledgeNodeId: String,
    val direction: String,
    val weight: Double,
    val reasonMarkdown: String,
    val confidence: Double,
    val createdAtEpochMillis: Long,
) {
    fun toEvent(eventSequence: Long): ChatEvidenceSubmitted = ChatEvidenceSubmitted(
        evidenceId = evidenceId,
        conversationId = conversationId,
        knowledgeNodeId = knowledgeNodeId,
        direction = LearningEvidenceDirection.valueOf(direction),
        weight = weight,
        reasonMarkdown = reasonMarkdown,
        confidence = confidence,
        occurredAtEpochMillis = createdAtEpochMillis,
        eventSequence = eventSequence,
    )
}

private fun SQLiteConnection.readOrphanChatEvidence(): List<ChatEvidenceOutboxBackfillRow> =
    buildList {
        prepare(
            """
            SELECT e.`evidence_id`, e.`learner_id`, e.`conversation_id`, e.`knowledge_node_id`,
                e.`direction`, e.`weight`, e.`reason_markdown`, e.`confidence`,
                e.`created_at_epoch_millis`
            FROM `learner_chat_evidence` e
            WHERE NOT EXISTS (
                SELECT 1 FROM `projection_outbox` o
                WHERE o.`event_kind` = 'CHAT_EVIDENCE_SUBMITTED' AND o.`event_id` = e.`evidence_id`
            )
            ORDER BY e.`learner_id`, e.`created_at_epoch_millis`, e.`evidence_id`
            """.trimIndent(),
        ).use { statement ->
            while (statement.step()) {
                add(
                    ChatEvidenceOutboxBackfillRow(
                        evidenceId = statement.getText(0),
                        learnerId = statement.getText(1),
                        conversationId = statement.getText(2),
                        knowledgeNodeId = statement.getText(3),
                        direction = statement.getText(4),
                        weight = statement.getDouble(5),
                        reasonMarkdown = statement.getText(6),
                        confidence = statement.getDouble(7),
                        createdAtEpochMillis = statement.getLong(8),
                    ),
                )
            }
        }
    }

/** 分配权威 learning_sequence 优先，outbox max 作防御性兜底，新 learner 从 0 起。 */
private fun SQLiteConnection.readLedgerHead(learnerId: String): Long =
    prepare(
        """
        SELECT COALESCE(
            (SELECT `last_allocated_sequence` FROM `learning_sequence` WHERE `learner_id` = ?),
            (SELECT COALESCE(MAX(`outbox_sequence`), 0) FROM `projection_outbox` WHERE `learner_id` = ?),
            0
        )
        """.trimIndent(),
    ).use { statement ->
        statement.bindText(1, learnerId)
        statement.bindText(2, learnerId)
        if (statement.step()) statement.getLong(0) else 0L
    }

private fun SQLiteConnection.insertChatEvidenceOutboxRow(
    learnerId: String,
    outboxSequence: Long,
    eventId: String,
    canonicalFingerprint: String,
    createdAtEpochMillis: Long,
) {
    prepare(
        """
        INSERT INTO `projection_outbox` (
            `outbox_id`, `learner_id`, `outbox_sequence`, `event_kind`, `event_id`,
            `canonical_fingerprint`, `status`, `created_at_epoch_millis`
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
    ).use { statement ->
        statement.bindText(1, "learning-outbox:$learnerId:$EVENT_KIND_CHAT_EVIDENCE:$eventId")
        statement.bindText(2, learnerId)
        statement.bindLong(3, outboxSequence)
        statement.bindText(4, EVENT_KIND_CHAT_EVIDENCE)
        statement.bindText(5, eventId)
        statement.bindText(6, canonicalFingerprint)
        statement.bindText(7, StudyDbValue.OutboxStatus.PENDING)
        statement.bindLong(8, createdAtEpochMillis)
        statement.step()
    }
}

private fun SQLiteConnection.advanceLearningSequences(headsByLearner: Map<String, Long>) {
    prepare("INSERT OR IGNORE INTO `learning_sequence` (`learner_id`, `last_allocated_sequence`) VALUES (?, 0)")
        .use { statement ->
            headsByLearner.keys.forEach { learnerId ->
                statement.bindText(1, learnerId)
                statement.step()
                statement.reset()
                statement.clearBindings()
            }
        }
    prepare(
        "UPDATE `learning_sequence` SET `last_allocated_sequence` = ? WHERE `learner_id` = ?",
    ).use { statement ->
        headsByLearner.forEach { (learnerId, head) ->
            statement.bindLong(1, head)
            statement.bindText(2, learnerId)
            statement.step()
            statement.reset()
            statement.clearBindings()
        }
    }
}

internal const val LEARNER_CHAT_EVIDENCE_DDL =
    "CREATE TABLE IF NOT EXISTS `learner_chat_evidence` (" +
        "`evidence_id` TEXT NOT NULL, " +
        "`learner_id` TEXT NOT NULL, " +
        "`conversation_id` TEXT NOT NULL, " +
        "`knowledge_node_id` TEXT NOT NULL, " +
        "`direction` TEXT NOT NULL, " +
        "`weight` REAL NOT NULL, " +
        "`reason_markdown` TEXT NOT NULL, " +
        "`confidence` REAL NOT NULL, " +
        "`source_kind` TEXT NOT NULL, " +
        "`created_at_epoch_millis` INTEGER NOT NULL, " +
        "PRIMARY KEY(`evidence_id`))"
